package io.github.uirlisi.mostor.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.ShrinkingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based convergence (SPEC §9.7): three simulated phones with skewed clocks make random
 * records, start and stop timers, switch sides, edit by deleting, undo, and match children, while
 * random pairs sync in random orders, repeatedly and out of order. After a final round of syncs,
 * every phone holds the same rows, and every row is one some phone wrote (no invented states).
 */
class SyncConvergenceTest {
    /** One simulated phone: its rows, its clock (with its own skew) and its last change. */
    private class Phone(val origin: String, val skewMs: Long, var time: () -> Long) {
        val children = linkedMapOf<String, Child>()
        val events = linkedMapOf<String, Event>()
        val segments = linkedMapOf<String, BreastSegment>()
        val presets = linkedMapOf<String, SupplementPreset>()
        val clock = HybridClock(origin, null) { time() + skewMs }
        var lastChange: Change? = null
        private var ids = 0

        val cx: ChangeContext get() = object : ChangeContext {
            override val now = time() + skewMs
            override val tz = "Europe/Dublin"
            override fun newId() = "$origin-${++ids}"
            override fun nextHlc() = clock.now().encode()
        }

        fun rows() = Merge.Rows(children.values.toList(), events.values.toList(), segments.values.toList(), presets.values.toList())

        fun write(change: Change, undoable: Boolean = true) {
            change.events.forEach { events[it.after.id] = it.after }
            change.segments.forEach { segments[it.after.id] = it.after }
            change.children.forEach { children[it.after.id] = it.after }
            change.presets.forEach { presets[it.after.id] = it.after }
            if (undoable && !change.isEmpty) lastChange = change
        }

        fun liveChildren() = children.values.filter { it.deletedAt == null }.sortedBy { it.id }

        fun running(kind: Kind) = events.values.filter { it.kind == kind && it.status == EventStatus.RUNNING && it.deletedAt == null }
            .sortedBy { it.id }.map { TimedEvent.of(it, segments.values.toList()) }
    }

    sealed interface Op {
        data class Record(val phone: Int, val kind: Int) : Op
        data class Start(val phone: Int, val sleep: Boolean) : Op
        data class Stop(val phone: Int, val sleep: Boolean) : Op
        data class Switch(val phone: Int) : Op
        data class Delete(val phone: Int, val pick: Int) : Op
        data class UndoLast(val phone: Int) : Op
        data class AddChild(val phone: Int) : Op
        data class MatchChildren(val phone: Int, val other: Int) : Op
        data class SyncPair(val a: Int, val b: Int) : Op
        data class Wait(val ms: Long) : Op
    }

    @Provide
    fun histories(): Arbitrary<List<Op>> {
        val phone = Arbitraries.integers().between(0, 2)
        val op: Arbitrary<Op> = Arbitraries.frequencyOf(
            net.jqwik.api.Tuple.of(6, Combinators.combine(phone, Arbitraries.integers().between(0, 3)).`as` { p, k -> Op.Record(p, k) }),
            net.jqwik.api.Tuple.of(3, Combinators.combine(phone, Arbitraries.of(true, false)).`as` { p, s -> Op.Start(p, s) }),
            net.jqwik.api.Tuple.of(3, Combinators.combine(phone, Arbitraries.of(true, false)).`as` { p, s -> Op.Stop(p, s) }),
            net.jqwik.api.Tuple.of(2, phone.map { Op.Switch(it) }),
            net.jqwik.api.Tuple.of(2, Combinators.combine(phone, Arbitraries.integers().between(0, 50)).`as` { p, i -> Op.Delete(p, i) }),
            net.jqwik.api.Tuple.of(2, phone.map { Op.UndoLast(it) }),
            net.jqwik.api.Tuple.of(1, phone.map { Op.AddChild(it) }),
            net.jqwik.api.Tuple.of(1, Combinators.combine(phone, phone).`as` { p, o -> Op.MatchChildren(p, o) }),
            net.jqwik.api.Tuple.of(5, Combinators.combine(phone, phone).filter { a, b -> a != b }.`as` { a, b -> Op.SyncPair(a, b) }),
            net.jqwik.api.Tuple.of(3, Arbitraries.longs().between(1, 3 * 60_000L).map { Op.Wait(it) }),
        )
        return op.list().ofMinSize(1).ofMaxSize(80)
    }

    @Property(tries = 300, shrinking = ShrinkingMode.FULL)
    fun `all phones converge after syncing`(@ForAll("histories") ops: List<Op>) {
        var now = dublin(2026, 11, 14, 9)
        // Skews up to ±2 min: a phone whose clock is behind still orders its changes after what it received.
        val phones = listOf(Phone("a", 0, { now }), Phone("b", -90_000, { now }), Phone("c", 120_000, { now }))
        val written = HashSet<Pair<String, String>>() // (id, hlc) of every row state a phone wrote

        // Each phone starts with its own implicit child and seeded meds, as a fresh install does.
        phones.forEach { p ->
            val cx = p.cx
            val child = Child(id = "${p.origin}-child", createdAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
            p.write(Change(children = listOf(RowChange(null, child)), presets = Presets.seeds(child.id, cx.now).map { RowChange(null, it) }), undoable = false)
        }

        fun record(p: Phone, change: Change, undoable: Boolean = true) {
            p.write(change, undoable)
            (change.events + change.segments + change.children + change.presets).forEach { written += it.after.id to it.after.hlc }
        }

        fun sync(a: Phone, b: Phone) {
            val toB = Sync.since(a.rows(), Sync.vector(b.rows()))
            val toA = Sync.since(b.rows(), Sync.vector(a.rows()))
            for ((from, to, rows) in listOf(Triple(a, b, toB), Triple(b, a, toA))) {
                rows.newestHlc()?.let { to.clock.receive(HlcStamp.parse(it)) }
                val ids = rows.ids()
                val local = Merge.Rows(
                    ids.children.mapNotNull { to.children[it] }, ids.events.mapNotNull { to.events[it] },
                    ids.segments.mapNotNull { to.segments[it] }, ids.presets.mapNotNull { to.presets[it] },
                )
                to.write(Merge.plan(local, rows).change, undoable = false)
                check(from !== to)
            }
        }

        for (op in ops) {
            when (op) {
                is Op.Wait -> now += op.ms
                is Op.Record -> phones[op.phone].let { p ->
                    val child = p.liveChildren().firstOrNull() ?: return@let
                    val cx = p.cx
                    val change = when (op.kind) {
                        0 -> Records.diaper(child.id, Diaper.WET, cx.now, cx)
                        1 -> Records.bottle(child.id, BottleContent.FORMULA, 90.0, cx.now, cx)
                        2 -> p.presets.values.firstOrNull { it.childId == child.id && it.deletedAt == null }
                            ?.let { Records.supplement(child.id, it, cx.now, cx) } ?: return@let
                        else -> Records.note(child.id, "n", cx.now, cx)
                    }
                    record(p, change)
                }
                is Op.Start -> phones[op.phone].let { p ->
                    val child = p.liveChildren().firstOrNull() ?: return@let
                    val kind = if (op.sleep) Kind.SLEEP else Kind.BREAST
                    if (p.running(kind).any { it.event.childId == child.id }) return@let
                    val cx = p.cx
                    record(p, Timers.start(kind, child.id, cx.now, cx, if (op.sleep) null else Side.LEFT))
                }
                is Op.Stop -> phones[op.phone].let { p ->
                    val t = p.running(if (op.sleep) Kind.SLEEP else Kind.BREAST).firstOrNull() ?: return@let
                    val cx = p.cx
                    record(p, Timers.stop(t, cx.now, cx))
                }
                is Op.Switch -> phones[op.phone].let { p ->
                    val t = p.running(Kind.BREAST).firstOrNull() ?: return@let
                    val cx = p.cx
                    record(p, Timers.switchSide(t, cx.now, cx))
                }
                is Op.Delete -> phones[op.phone].let { p ->
                    val live = p.events.values.filter { it.deletedAt == null }.sortedBy { it.id }
                    if (live.isEmpty()) return@let
                    val e = live[op.pick % live.size]
                    val cx = p.cx
                    record(p, EventEdit.delete(e, p.segments.values.filter { it.eventId == e.id }, cx))
                }
                is Op.UndoLast -> phones[op.phone].let { p ->
                    val change = p.lastChange ?: return@let
                    val cx = p.cx
                    Undo.revert(change, { p.events[it] }, { p.segments[it] }, cx, { p.children[it] }, { p.presets[it] })
                        ?.let { record(p, it, undoable = false) }
                    p.lastChange = null
                }
                is Op.AddChild -> phones[op.phone].let { p ->
                    val cx = p.cx
                    record(p, Children.add(ChildFields(name = "k"), p.children.size, cx))
                }
                is Op.MatchChildren -> {
                    // A phone matches its first child with another phone's first child it already knows.
                    val p = phones[op.phone]
                    val mine = p.liveChildren().firstOrNull() ?: continue
                    val theirs = p.liveChildren().firstOrNull { it.id.startsWith(phones[op.other].origin) && it.id != mine.id } ?: continue
                    val cx = p.cx
                    record(
                        p,
                        ChildMatch.same(
                            mine, theirs,
                            p.events.values.filter { it.childId == mine.id },
                            p.presets.values.filter { it.childId == mine.id },
                            p.presets.values.filter { it.childId == theirs.id },
                            cx,
                        ),
                    )
                }
                is Op.SyncPair -> sync(phones[op.a], phones[op.b])
            }
            now += 1_000
        }

        // Settle: every pair, twice, in a ring.
        repeat(2) { for ((i, j) in listOf(0 to 1, 1 to 2, 2 to 0)) sync(phones[i], phones[j]) }

        val first = phones[0].rows()
        for (p in phones.drop(1)) {
            val r = p.rows()
            assertEquals(first.children.sortedBy { it.id }, r.children.sortedBy { it.id }, "children on ${p.origin}")
            assertEquals(first.events.sortedBy { it.id }, r.events.sortedBy { it.id }, "events on ${p.origin}")
            assertEquals(first.segments.sortedBy { it.id }, r.segments.sortedBy { it.id }, "segments on ${p.origin}")
            assertEquals(first.presets.sortedBy { it.id }, r.presets.sortedBy { it.id }, "presets on ${p.origin}")
        }
        // Every row state held is one a phone wrote (seeds included), never a mix.
        val seeds = phones.flatMap { p -> p.rows().let { it.children + it.presets } }.filter { it.hlc == Presets.SEED_HLC || it.id.endsWith("-child") }
        for (row in first.children + first.events + first.segments + first.presets) {
            assertTrue((row.id to row.hlc) in written || row in seeds, "row ${row.id} with stamp ${row.hlc} was never written")
        }
        // Per-row invariants that every write keeps (SPEC §4.4); cross-row ones may break after a merge (§9.5).
        for (e in first.events) {
            e.endAt?.let { assertTrue(it >= e.startAt, "end before start: ${e.id}") }
            if (e.kind.timed && e.status == EventStatus.ENDED) assertTrue(e.endAt != null, "ended without an end: ${e.id}")
        }
        // Nothing left to send after settling.
        for (a in phones) for (b in phones) if (a !== b) {
            val left = Sync.since(a.rows(), Sync.vector(b.rows()))
            val plan = Merge.plan(b.rows(), left)
            assertTrue(plan.change.isEmpty, "${a.origin} still had changes for ${b.origin}")
        }
    }
}
