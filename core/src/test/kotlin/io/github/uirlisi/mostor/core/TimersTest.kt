package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimersTest {
    private val t0 = dublin(2026, 11, 10, 14, 2)
    private val cx = FakeCx(t0)
    private val table = Table()

    private fun startBreast(side: Side = Side.LEFT, at: Long = cx.now) =
        table.apply(Timers.start(Kind.BREAST, CHILD, at, cx, side)).newEventId()

    @Test
    fun `starting a breast feed opens a segment on that side`() {
        val id = startBreast(Side.LEFT, at = t0 - 10 * MIN)
        val t = table.timed(id)
        assertEquals(EventStatus.RUNNING, t.event.status)
        assertEquals(t0 - 10 * MIN, t.event.startAt)
        assertEquals(1, t.segments.size)
        assertEquals(Side.LEFT, t.openSegment?.side)
        assertEquals(t.event.startAt, t.segments[0].startAt)
        assertEquals("Europe/Dublin", t.event.tz)
    }

    @Test
    fun `starting a sleep has no segments`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        assertTrue(table.timed(id).segments.isEmpty())
    }

    @Test
    fun `stop ends the event and its open segment at the same time`() {
        val id = startBreast()
        cx.now = t0 + 20 * MIN
        table.apply(Timers.stop(table.timed(id), cx.now - 5 * MIN, cx))
        val t = table.timed(id)
        assertEquals(EventStatus.ENDED, t.event.status)
        assertEquals(t0 + 15 * MIN, t.event.endAt)
        assertEquals(t0 + 15 * MIN, t.segments.single().endAt)
    }

    @Test
    fun `a stop time before the start is clamped to the start`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        cx.now = t0 + 3 * MIN
        table.apply(Timers.stop(table.timed(id), cx.now - 30 * MIN, cx))
        assertEquals(t0, table.events.getValue(id).endAt)
    }

    @Test
    fun `a stop time before the current segment's start is clamped to that start`() {
        val id = startBreast()
        cx.now = t0 + 10 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        cx.now = t0 + 12 * MIN
        table.apply(Timers.stop(table.timed(id), cx.now - 20 * MIN, cx))
        val t = table.timed(id)
        assertEquals(t0 + 10 * MIN, t.event.endAt)
        assertEquals(t0 + 10 * MIN, t.segments.last().endAt)
    }

    @Test
    fun `stopping an ended timer does nothing`() {
        val id = startBreast()
        table.apply(Timers.stop(table.timed(id), t0 + MIN, cx))
        assertTrue(Timers.stop(table.timed(id), t0 + 2 * MIN, cx).isEmpty)
    }

    @Test
    fun `switch side closes the segment and opens the other side`() {
        val id = startBreast(Side.LEFT)
        cx.now = t0 + 12 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now - 2 * MIN, cx))
        val t = table.timed(id)
        assertEquals(listOf(Side.LEFT, Side.RIGHT), t.segments.map { it.side })
        assertEquals(t0 + 10 * MIN, t.segments[0].endAt)
        assertEquals(t0 + 10 * MIN, t.segments[1].startAt)
        assertNull(t.segments[1].endAt)
        assertEquals(Side.RIGHT, t.lastSide)
    }

    @Test
    fun `a switch clamped to the segment start just changes the side`() {
        val id = startBreast(Side.LEFT)
        cx.now = t0 + 3 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now - 10 * MIN, cx))
        val t = table.timed(id)
        assertEquals(1, t.segments.size)
        assertEquals(Side.RIGHT, t.segments.single().side)
    }

    @Test
    fun `switching sides on a sleep or an ended feed does nothing`() {
        val sleep = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        assertTrue(Timers.switchSide(table.timed(sleep), t0 + MIN, cx).isEmpty)
        val feed = startBreast()
        table.apply(Timers.stop(table.timed(feed), t0 + MIN, cx))
        assertTrue(Timers.switchSide(table.timed(feed), t0 + 2 * MIN, cx).isEmpty)
    }

    @Test
    fun `cancel soft-deletes the event and its segments`() {
        val id = startBreast()
        cx.now = t0 + MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        table.apply(Timers.cancel(table.timed(id), cx))
        assertEquals(t0 + MIN, table.events.getValue(id).deletedAt)
        assertTrue(table.segments.values.all { it.deletedAt == t0 + MIN })
    }

    @Test
    fun `give up marks the end unknown and leaves the end empty`() {
        val id = startBreast()
        cx.now = t0 + 3 * HOUR
        table.apply(Timers.giveUp(table.timed(id), cx))
        val t = table.timed(id)
        assertEquals(EventStatus.END_UNKNOWN, t.event.status)
        assertNull(t.event.endAt)
        assertNull(t.segments.single().endAt)
    }

    @Test
    fun `an unknown end can be set later, closing the segment`() {
        val id = startBreast()
        cx.now = t0 + 3 * HOUR
        table.apply(Timers.giveUp(table.timed(id), cx))
        cx.now = t0 + 5 * HOUR
        table.apply(Timers.stop(table.timed(id), t0 + 25 * MIN, cx))
        val t = table.timed(id)
        assertEquals(EventStatus.ENDED, t.event.status)
        assertEquals(t0 + 25 * MIN, t.event.endAt)
        assertEquals(t0 + 25 * MIN, t.segments.single().endAt)
    }

    @Test
    fun `move start moves the event and its first segment`() {
        val id = startBreast()
        table.apply(Timers.moveStart(table.timed(id), t0 - 10 * MIN, cx))
        val t = table.timed(id)
        assertEquals(t0 - 10 * MIN, t.event.startAt)
        assertEquals(t0 - 10 * MIN, t.segments.single().startAt)
    }

    @Test
    fun `move start never passes the end of the first segment`() {
        val id = startBreast()
        cx.now = t0 + 10 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        table.apply(Timers.moveStart(table.timed(id), t0 + 30 * MIN, cx))
        assertEquals(t0 + 10 * MIN, table.timed(id).event.startAt)
    }

    @Test
    fun `move last switch moves the boundary between the last two segments`() {
        val id = startBreast()
        cx.now = t0 + 10 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        table.apply(Timers.moveLastSwitch(table.timed(id), t0 + 5 * MIN, cx))
        val t = table.timed(id)
        assertEquals(t0 + 5 * MIN, t.segments[0].endAt)
        assertEquals(t0 + 5 * MIN, t.segments[1].startAt)
        // Never before the previous segment's start.
        table.apply(Timers.moveLastSwitch(table.timed(id), t0 - HOUR, cx))
        assertEquals(t0, table.timed(id).segments[1].startAt)
    }

    @Test
    fun `segments stay ordered and non-overlapping through many switches`() {
        val id = startBreast()
        repeat(6) { i ->
            cx.now = t0 + (i + 1) * 7 * MIN
            table.apply(Timers.switchSide(table.timed(id), cx.now - 5 * MIN, cx))
        }
        cx.now += MIN
        table.apply(Timers.stop(table.timed(id), cx.now, cx))
        val segs = table.timed(id).segments
        segs.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endAt!! <= b.startAt)
            assertTrue(a.side != b.side)
        }
        assertEquals(table.events.getValue(id).startAt, segs.first().startAt)
        assertEquals(table.events.getValue(id).endAt, segs.last().endAt)
        assertTrue(segs.all { it.endAt != null && it.endAt!! >= it.startAt })
    }

    @Test
    fun `every written row gets a fresh, increasing HLC`() {
        val change = Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT)
        val stamps = change.events.map { it.after.hlc } + change.segments.map { it.after.hlc }
        assertEquals(stamps.sorted(), stamps)
        assertEquals(stamps.size, stamps.toSet().size)
    }
}
