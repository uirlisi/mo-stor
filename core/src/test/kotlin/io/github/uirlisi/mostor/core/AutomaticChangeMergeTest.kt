package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A timer given up automatically on a phone that never saw what happened elsewhere (SPEC §5.5, §9.5):
 * its "end unknown" must not overwrite a stop, a delete or a side switch made on another phone, though
 * its own stamp is newer. It still wins over the version it was made from.
 */
class AutomaticChangeMergeTest {
    private val t0 = dublin(2026, 11, 19, 9)

    /** One phone: its rows and its own clock. */
    private inner class Phone(device: String) : ChangeContext {
        override var now: Long = t0
        override val tz: String = "Europe/Dublin"
        private var n = 0
        private val clock = HybridClock(device, null) { now }
        val table = Table()

        override fun newId(): String = "id-${++n}"
        override fun nextHlc(): String = clock.now().encode()

        fun timed(id: String) = table.timed(id)
        fun event(id: String) = table.events.getValue(id)

        /** Merges the other phone's rows in, as a sync does. */
        fun receive(from: Phone) {
            val rows = Merge.Rows(events = from.table.events.values.toList(), segments = from.table.segments.values.toList())
            from.table.events.values.maxOfOrNull { it.hlc }?.let { clock.receive(HlcStamp.parse(it)) }
            val plan = Merge.plan(
                Merge.Rows(events = table.events.values.toList(), segments = table.segments.values.toList()),
                rows,
            )
            table.apply(plan.change)
        }

        /** A local write, as the repository makes it. */
        fun write(change: Change): Change = table.apply(change.asLocal())
    }

    private val a = Phone("phone-a")
    private val b = Phone("phone-b")

    private fun sleepOnA(): String {
        val id = a.write(Timers.start(Kind.SLEEP, CHILD, t0, a)).newEventId()
        b.receive(a)
        return id
    }

    private fun syncBoth() {
        b.receive(a)
        a.receive(b)
        b.receive(a)
    }

    @Test
    fun `a stop made elsewhere beats a later give-up from the running version`() {
        val id = sleepOnA()
        a.now = t0 + 30 * MIN
        a.write(Timers.stop(a.timed(id), a.now, a))
        // B is out of range and gives up hours later.
        b.now = t0 + 15 * HOUR
        val giveUp = b.write(Timers.giveUp(b.timed(id), b))
        assertEquals(EventStatus.END_UNKNOWN, b.event(id).status)
        assertNotNull(giveUp.events.single().after.autoBase)

        syncBoth()
        for (p in listOf(a, b)) {
            assertEquals(EventStatus.ENDED, p.event(id).status)
            assertEquals(t0 + 30 * MIN, p.event(id).endAt)
        }
        assertEquals(a.event(id), b.event(id))
    }

    @Test
    fun `a delete made elsewhere beats a give-up`() {
        val id = sleepOnA()
        a.now = t0 + 5 * MIN
        a.write(EventEdit.delete(a.event(id), emptyList(), a))
        b.now = t0 + 15 * HOUR
        b.write(Timers.giveUp(b.timed(id), b))

        syncBoth()
        assertNotNull(a.event(id).deletedAt)
        assertEquals(a.event(id), b.event(id))
    }

    @Test
    fun `with nothing done elsewhere, the give-up wins on both phones`() {
        val id = sleepOnA()
        b.now = t0 + 15 * HOUR
        b.write(Timers.giveUp(b.timed(id), b))

        syncBoth()
        assertEquals(EventStatus.END_UNKNOWN, a.event(id).status)
        assertEquals(a.event(id), b.event(id))
    }

    @Test
    fun `a side switch made elsewhere beats a give-up, and a later give-up from it wins`() {
        val id = a.write(Timers.start(Kind.BREAST, CHILD, t0, a, Side.LEFT)).newEventId()
        b.receive(a)
        a.now = t0 + 20 * MIN
        a.write(Timers.switchSide(a.timed(id), a.now, a))
        b.now = t0 + 4 * HOUR
        b.write(Timers.giveUp(b.timed(id), b))

        syncBoth()
        assertEquals(EventStatus.RUNNING, a.event(id).status)
        assertEquals(a.event(id), b.event(id))
        assertEquals(listOf(Side.LEFT, Side.RIGHT), b.timed(id).segments.map { it.side })

        // B, which now holds A's version, gives up again: nothing newer exists, so it wins.
        b.now = t0 + 5 * HOUR
        b.write(Timers.giveUp(b.timed(id), b))
        syncBoth()
        assertEquals(EventStatus.END_UNKNOWN, a.event(id).status)
        assertEquals(a.event(id), b.event(id))
    }

    @Test
    fun `a forgotten pause ended at the pause loses to a resume made elsewhere`() {
        val id = a.write(Timers.start(Kind.BREAST, CHILD, t0, a, Side.LEFT)).newEventId()
        a.now = t0 + 10 * MIN
        a.write(Timers.pause(a.timed(id), a.now, a))
        b.receive(a)
        a.now = t0 + 30 * MIN
        a.write(Timers.resume(a.timed(id), Side.LEFT, a.now, a))
        b.now = t0 + 60 * MIN
        b.write(Timers.endForgottenPause(b.timed(id), b))
        assertEquals(EventStatus.ENDED, b.event(id).status)

        syncBoth()
        assertEquals(EventStatus.RUNNING, b.event(id).status)
        assertEquals(a.event(id), b.event(id))
    }

    @Test
    fun `a person's change after a give-up is an ordinary change, and an undo too`() {
        val id = sleepOnA()
        b.now = t0 + 15 * HOUR
        b.write(Timers.giveUp(b.timed(id), b))
        b.now += MIN
        val setEnd = b.write(Timers.stop(b.timed(id), t0 + 2 * HOUR, b))
        assertNull(b.event(id).autoBase)

        b.now += MIN
        val undo = Undo.revert(setEnd, { b.table.events[it] }, { b.table.segments[it] }, b)!!
        b.write(undo)
        assertEquals(EventStatus.END_UNKNOWN, b.event(id).status)
        assertNull(b.event(id).autoBase)
    }

    @Test
    fun `a local write keeps the mark only when it was made from the version it replaces`() {
        val e = Event(id = "e", childId = CHILD, kind = Kind.SLEEP, startAt = t0, tz = "Europe/Dublin", createdAt = t0, updatedAt = t0, hlc = "h2")
        val auto = e.copy(hlc = "h3", autoBase = "h2")
        assertEquals("h2", Change(listOf(RowChange(e, auto))).asLocal().events.single().after.autoBase)
        val carried = auto.copy(hlc = "h4", note = "x")
        assertNull(Change(listOf(RowChange(auto, carried))).asLocal().events.single().after.autoBase)
        assertNull(Change(listOf(RowChange(null, auto))).asLocal().events.single().after.autoBase)
    }

    @Test
    fun `rows stamped far ahead of this phone's clock wait, the others merge`() {
        val ok = Event(id = "ok", childId = CHILD, kind = Kind.DIAPER, startAt = t0, tz = "Europe/Dublin", createdAt = t0, updatedAt = t0,
            hlc = HlcStamp(t0, 0, "x").encode())
        val ahead = ok.copy(id = "ahead", hlc = HlcStamp(t0 + 3 * 24 * HOUR, 0, "x").encode())
        val rows = Merge.Rows(events = listOf(ok, ahead)).notAfter(t0 + HybridClock.MAX_AHEAD_MS)
        assertEquals(listOf("ok"), rows.events.map { it.id })
    }

    @Test
    fun `merge order is total, by rank first and then the stamp`() {
        val e = Event(id = "e", childId = CHILD, kind = Kind.SLEEP, startAt = t0, tz = "Europe/Dublin", createdAt = t0, updatedAt = t0, hlc = "h5")
        val stop = e.copy(hlc = "h3")
        val giveUp = e.copy(hlc = "h9", autoBase = "h2")
        assertEquals(true, Merge.newer(stop, giveUp))
        assertEquals(false, Merge.newer(giveUp, stop))
        val giveUpFromStop = e.copy(hlc = "h9", autoBase = "h3")
        assertEquals(true, Merge.newer(giveUpFromStop, stop))
        assertEquals(false, Merge.newer(stop, stop))
    }
}
