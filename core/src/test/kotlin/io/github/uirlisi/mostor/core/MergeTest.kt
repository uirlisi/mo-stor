package io.github.uirlisi.mostor.core

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeTest {
    private val t0 = dublin(2026, 11, 14, 9)

    private fun stamp(wall: Long, origin: String = "phone-b", counter: Int = 0) = HlcStamp(wall, counter, origin).encode()

    private fun diaper(id: String, hlc: String, subtype: String = "wet", deletedAt: Long? = null) = Event(
        id = id, childId = CHILD, kind = Kind.DIAPER, subtype = subtype, startAt = t0, tz = "Europe/Dublin",
        createdAt = t0, updatedAt = t0, deletedAt = deletedAt, hlc = hlc,
    )

    /** Applies a plan to an in-memory table, as the repository would. */
    private fun MutableMap<String, Event>.merge(incoming: List<Event>): Merge.Plan {
        val plan = Merge.plan(Merge.Rows(events = incoming.mapNotNull { this[it.id] }), Merge.Rows(events = incoming))
        plan.change.events.forEach { this[it.after.id] = it.after }
        return plan
    }

    @Test
    fun `a new row is added, a newer one replaces, an older or equal one changes nothing`() {
        val local = mutableMapOf(
            "a" to diaper("a", stamp(100)),
            "b" to diaper("b", stamp(100)),
            "c" to diaper("c", stamp(100)),
        )
        val plan = local.merge(
            listOf(
                diaper("a", stamp(200), subtype = "poop"),
                diaper("b", stamp(50), subtype = "poop"),
                diaper("c", stamp(100)),
                diaper("d", stamp(10)),
            ),
        )
        assertEquals(Triple(1, 1, 2), Triple(plan.added, plan.updated, plan.unchanged))
        assertEquals("poop", local.getValue("a").subtype)
        assertEquals("wet", local.getValue("b").subtype)
        assertTrue("d" in local)
        // The merged row keeps the other phone's stamp: it is their change, not a new one.
        assertEquals(stamp(200), local.getValue("a").hlc)
        val before = plan.change.events.single { it.after.id == "a" }.before
        assertEquals(stamp(100), before?.hlc)
    }

    @Test
    fun `merging the same rows twice changes nothing the second time`() {
        val local = mutableMapOf("a" to diaper("a", stamp(100)))
        val incoming = listOf(diaper("a", stamp(200), subtype = "poop"), diaper("b", stamp(150)))
        local.merge(incoming)
        val again = local.merge(incoming)
        assertTrue(again.change.isEmpty)
        assertEquals(Triple(0, 0, 2), Triple(again.added, again.updated, again.unchanged))
    }

    @Test
    fun `a tombstone wins when newer and is kept against an older live row`() {
        val local = mutableMapOf("a" to diaper("a", stamp(100)))
        local.merge(listOf(diaper("a", stamp(300), deletedAt = t0 + 1)))
        assertEquals(t0 + 1, local.getValue("a").deletedAt)
        local.merge(listOf(diaper("a", stamp(200))))
        assertEquals(t0 + 1, local.getValue("a").deletedAt)
    }

    @Test
    fun `duplicate ids in the incoming rows settle on the newest`() {
        val local = mutableMapOf<String, Event>()
        val plan = local.merge(listOf(diaper("a", stamp(100), "wet"), diaper("a", stamp(300), "mixed"), diaper("a", stamp(200), "poop")))
        assertEquals("mixed", local.getValue("a").subtype)
        assertEquals(Triple(1, 0, 2), Triple(plan.added, plan.updated, plan.unchanged))
    }

    @Test
    fun `stamps from the same millisecond order by counter, then origin`() {
        val local = mutableMapOf("a" to diaper("a", stamp(100, "phone-a", counter = 2)))
        local.merge(listOf(diaper("a", stamp(100, "phone-b", counter = 1), subtype = "poop")))
        assertEquals("wet", local.getValue("a").subtype)
        local.merge(listOf(diaper("a", stamp(100, "phone-b", counter = 2), subtype = "poop")))
        assertEquals("poop", local.getValue("a").subtype)
    }

    @Test
    fun `any order of the same batches converges to the same rows`() {
        // Three phones' versions of ten rows, with random stamps; merged in random orders and
        // repeated batches, every order ends in the same table.
        repeat(50) { seed ->
            val r = Random(seed.toLong())
            val batches = listOf("phone-a", "phone-b", "phone-c").map { origin ->
                (0 until 10).map { i ->
                    diaper("row-$i", stamp(r.nextInt(20).toLong(), origin), subtype = listOf("wet", "poop", "mixed")[r.nextInt(3)],
                        deletedAt = if (r.nextInt(4) == 0) t0 else null)
                }.shuffled(r)
            }
            val results = (0 until 6).map {
                val table = mutableMapOf<String, Event>()
                val order = (batches + batches.take(r.nextInt(3))).shuffled(r)
                order.forEach { table.merge(it) }
                table.toSortedMap()
            }
            results.forEach { assertEquals(results[0], it, "seed $seed") }
            // Each row holds its newest version among all batches.
            val newest = batches.flatten().groupBy { it.id }.mapValues { (_, v) -> v.maxBy { it.hlc } }
            assertEquals(newest.toSortedMap(), results[0])
        }
    }

    @Test
    fun `segments, children and meds merge the same way`() {
        val child = Child(id = "k", createdAt = t0, updatedAt = t0, hlc = stamp(100))
        val seg = BreastSegment(id = "s", eventId = "e", side = Side.LEFT, startAt = t0, createdAt = t0, updatedAt = t0, hlc = stamp(100))
        val med = SupplementPreset(id = "m", childId = "k", name = "Vit D", createdAt = t0, updatedAt = t0, hlc = stamp(100))
        val plan = Merge.plan(
            Merge.Rows(children = listOf(child), segments = listOf(seg), presets = listOf(med)),
            Merge.Rows(
                children = listOf(child.copy(name = "Aoife", hlc = stamp(200))),
                segments = listOf(seg.copy(side = Side.RIGHT, hlc = stamp(50))),
                presets = listOf(med.copy(amount = 1.0, hlc = stamp(200))),
            ),
        )
        assertEquals("Aoife", plan.change.children.single().after.name)
        assertTrue(plan.change.segments.isEmpty())
        assertEquals(1.0, plan.change.presets.single().after.amount)
        assertEquals(Triple(0, 2, 1), Triple(plan.added, plan.updated, plan.unchanged))
    }

    @Test
    fun `newest stamp of a data set`() {
        val rows = Merge.Rows(events = listOf(diaper("a", stamp(5)), diaper("b", stamp(0x10))))
        assertEquals(stamp(0x10), rows.newestHlc())
        assertNull(Merge.Rows().newestHlc())
    }
}

class BlankChildTest {
    private val t0 = dublin(2026, 11, 14, 9)
    private val fresh = Child(id = "k", createdAt = t0, updatedAt = t0, hlc = "000000000001-0000-phone")
    private val seeds = Presets.seeds("k", t0)

    @Test
    fun `a fresh install's child with its seeded meds is blank`() {
        assertTrue(Merge.isBlank(fresh, eventCount = 0, presets = seeds))
    }

    @Test
    fun `any record, edit, archive or touched med makes a child not blank`() {
        assertFalse(Merge.isBlank(fresh, eventCount = 1, presets = seeds))
        assertFalse(Merge.isBlank(fresh.copy(name = "Aoife", updatedAt = t0 + 1), 0, seeds))
        assertFalse(Merge.isBlank(fresh.copy(updatedAt = t0 + 1), 0, seeds))
        assertFalse(Merge.isBlank(fresh.copy(archivedAt = t0), 0, seeds))
        assertFalse(Merge.isBlank(fresh.copy(deletedAt = t0), 0, seeds))
        assertFalse(Merge.isBlank(fresh, 0, listOf(seeds[0].copy(amount = 400.0, hlc = "000000000002-0000-phone"), seeds[1])))
        assertFalse(Merge.isBlank(fresh, 0, seeds + SupplementPreset(id = "m", childId = "k", name = "Iron", createdAt = t0, updatedAt = t0, hlc = "000000000003-0000-phone")))
    }

    @Test
    fun `replacing a blank child soft-deletes it and its meds as a new change`() {
        val cx = FakeCx(t0 + 1000)
        val change = Merge.replaceBlank(fresh, seeds, cx)
        val child = change.children.single().after
        assertEquals(t0 + 1000, child.deletedAt)
        assertTrue(child.hlc > fresh.hlc)
        assertEquals(2, change.presets.size)
        assertTrue(change.presets.all { it.after.deletedAt == t0 + 1000 && it.after.hlc > Presets.SEED_HLC })
    }
}
