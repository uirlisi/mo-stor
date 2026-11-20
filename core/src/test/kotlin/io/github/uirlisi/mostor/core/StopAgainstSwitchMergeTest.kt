package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stop on one phone against a switch or resume made a little earlier on another, out of range
 * (SPEC §9.5). The stop's event row wins, but the other phone's new segment has nothing to compete
 * with: without [Timers.tidyEnded] it stayed open, and the feed's length grew for ever.
 */
class StopAgainstSwitchMergeTest {
    private val t0 = dublin(2026, 11, 20, 9)

    private inner class Phone(val device: String) : ChangeContext {
        override var now: Long = t0
        override val tz: String = "Europe/Dublin"
        private var n = 0
        private val clock = HybridClock(device, null) { now }
        val table = Table()

        override fun newId(): String = "$device-${++n}"
        override fun nextHlc(): String = clock.now().encode()

        fun timed(id: String) = table.timed(id)
        fun write(change: Change): Change = table.apply(change.asLocal())

        /** As the app's sync does: merge, then tidy every ended feed the merge touched. */
        fun receive(from: Phone) {
            val rows = Merge.Rows(events = from.table.events.values.toList(), segments = from.table.segments.values.toList())
            rows.newestHlc()?.let { clock.receive(HlcStamp.parse(it)) }
            val change = Merge.plan(Merge.Rows(events = table.events.values.toList(), segments = table.segments.values.toList()), rows).change
            table.apply(change)
            val touched = change.events.map { it.after.id } + change.segments.map { it.after.eventId }
            for (id in touched.distinct()) write(Timers.tidyEnded(timed(id), this))
        }
    }

    private val a = Phone("a")
    private val b = Phone("b")

    private fun feedOnA(): String {
        val id = a.write(Timers.start(Kind.BREAST, CHILD, t0, a, Side.LEFT)).newEventId()
        b.receive(a)
        return id
    }

    private fun syncBoth() {
        b.receive(a)
        a.receive(b)
        b.receive(a)
        a.receive(b)
    }

    private fun segmentsOf(p: Phone, id: String) = p.timed(id).segments.map { Triple(it.side, (it.startAt - t0) / MIN, it.endAt?.let { e -> (e - t0) / MIN }) }

    @Test
    fun `a switch on one phone, then a stop on the other, ends consistently`() {
        val id = feedOnA()
        b.now = t0 + 10 * MIN
        b.write(Timers.switchSide(b.timed(id), b.now, b))
        a.now = t0 + 11 * MIN
        a.write(Timers.stop(a.timed(id), a.now, a))

        syncBoth()
        for (p in listOf(a, b)) {
            val t = p.timed(id)
            assertEquals(EventStatus.ENDED, t.event.status)
            assertEquals(t0 + 11 * MIN, t.event.endAt)
            assertEquals(listOf(Triple(Side.LEFT, 0L, 10L), Triple(Side.RIGHT, 10L, 11L)), segmentsOf(p, id))
            // Six hours later it still lasted 11 minutes.
            assertEquals(11 * MIN, Durations.of(t, t0 + 6 * HOUR))
        }
        assertEquals(a.table.segments.values.map { it.copy(hlc = "", updatedAt = 0) }.toSet(), b.table.segments.values.map { it.copy(hlc = "", updatedAt = 0) }.toSet())
    }

    @Test
    fun `a resume on one phone, then a stop of the paused feed on the other, ends at the pause`() {
        val id = feedOnA()
        a.now = t0 + 10 * MIN
        a.write(Timers.pause(a.timed(id), a.now, a))
        b.receive(a)
        b.now = t0 + 20 * MIN
        b.write(Timers.resume(b.timed(id), Side.RIGHT, b.now, b))
        a.now = t0 + 25 * MIN
        a.write(Timers.stop(a.timed(id), a.now, a))

        syncBoth()
        for (p in listOf(a, b)) {
            val t = p.timed(id)
            assertEquals(EventStatus.ENDED, t.event.status)
            assertEquals(t0 + 10 * MIN, t.event.endAt)
            assertEquals(listOf(Triple(Side.LEFT, 0L, 10L)), segmentsOf(p, id))
            assertEquals(10 * MIN, Durations.of(t, t0 + 6 * HOUR))
        }
    }

    @Test
    fun `a stop, then a later switch on the other phone, keeps the feed running`() {
        val id = feedOnA()
        a.now = t0 + 10 * MIN
        a.write(Timers.stop(a.timed(id), a.now, a))
        b.now = t0 + 11 * MIN
        b.write(Timers.switchSide(b.timed(id), b.now, b))

        syncBoth()
        for (p in listOf(a, b)) assertEquals(EventStatus.RUNNING, p.timed(id).event.status)
    }

    @Test
    fun `an ordinary ended feed needs no tidying`() {
        val id = feedOnA()
        a.now = t0 + 10 * MIN
        a.write(Timers.switchSide(a.timed(id), a.now, a))
        a.now = t0 + 20 * MIN
        a.write(Timers.stop(a.timed(id), a.now, a))
        assertTrue(Timers.tidyEnded(a.timed(id), a).isEmpty)
    }
}
