package io.github.uirlisi.mostor.core

import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverrunTest {
    private val start = dublin(2026, 11, 10, 14, 2)
    private val breast = OverrunLimits.BREAST_DEFAULT

    @Test
    fun `normal, then nudge at 45 min, then give up at 3 h`() {
        assertEquals(OverrunState.NORMAL, Overrun.state(start, start + 44 * MIN, breast, null))
        assertEquals(OverrunState.NUDGE, Overrun.state(start, start + 45 * MIN, breast, null))
        assertEquals(OverrunState.GIVE_UP, Overrun.state(start, start + 3 * HOUR, breast, null))
    }

    @Test
    fun `still going waits the same interval again before nudging`() {
        val pressed = start + 50 * MIN
        val snooze = Overrun.snoozeUntil(pressed, breast)
        assertEquals(pressed + 45 * MIN, snooze)
        assertEquals(OverrunState.NORMAL, Overrun.state(start, pressed + 44 * MIN, breast, snooze))
        assertEquals(OverrunState.NUDGE, Overrun.state(start, pressed + 45 * MIN, breast, snooze))
    }

    @Test
    fun `still going doesn't move the give-up time`() {
        val snooze = start + 4 * HOUR
        assertEquals(OverrunState.GIVE_UP, Overrun.state(start, start + 3 * HOUR, breast, snooze))
    }

    @Test
    fun `give up can be never`() {
        val never = OverrunLimits(nudgeAfterMs = 6 * HOUR, giveUpAfterMs = null)
        assertEquals(OverrunState.NUDGE, Overrun.state(start, start + 100 * HOUR, never, null))
        assertNull(Overrun.giveUpAt(start, never))
    }

    @Test
    fun `next check is the next nudge or give-up time still ahead`() {
        assertEquals(start + 45 * MIN, Overrun.nextCheck(start, start + MIN, breast, null))
        assertEquals(start + 3 * HOUR, Overrun.nextCheck(start, start + 50 * MIN, breast, null))
        assertNull(Overrun.nextCheck(start, start + 4 * HOUR, breast, null))
        assertNull(Overrun.nextCheck(start, start, OverrunLimits(null, null), null))
    }

    @Test
    fun `sleep defaults`() {
        val sleep = OverrunLimits.SLEEP_DEFAULT
        assertEquals(OverrunState.NORMAL, Overrun.state(start, start + 5 * HOUR, sleep, null))
        assertEquals(OverrunState.NUDGE, Overrun.state(start, start + 6 * HOUR, sleep, null))
        assertEquals(OverrunState.GIVE_UP, Overrun.state(start, start + 14 * HOUR, sleep, null))
    }
}

class UndoTest {
    private val t0 = dublin(2026, 11, 10, 14, 0)
    private val cx = FakeCx(t0)
    private val table = Table()

    @Test
    fun `undoing a record soft-deletes it`() {
        val record = table.apply(Records.diaper(CHILD, Diaper.WET, t0, cx))
        cx.now = t0 + MIN
        assertNotNull(table.undo(record, cx))
        assertEquals(t0 + MIN, table.events.getValue(record.newEventId()).deletedAt)
    }

    @Test
    fun `undoing a stop makes the timer run again`() {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT)).newEventId()
        cx.now = t0 + 20 * MIN
        val stop = table.apply(Timers.stop(table.timed(id), cx.now, cx))
        cx.now = t0 + 21 * MIN
        val undo = table.undo(stop, cx)
        assertNotNull(undo)
        val t = table.timed(id)
        assertEquals(EventStatus.RUNNING, t.event.status)
        assertNull(t.event.endAt)
        assertNull(t.openSegment?.endAt)
        assertNotNull(t.openSegment)
        // The undo is a new change: newer stamps than the stop it reverts.
        assertTrue(t.event.hlc > stop.events.single().after.hlc)
    }

    @Test
    fun `undoing a switch restores one open segment`() {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT)).newEventId()
        cx.now = t0 + 10 * MIN
        val switch = table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        table.undo(switch, cx)
        val t = table.timed(id)
        assertEquals(1, t.segments.size)
        assertEquals(Side.LEFT, t.openSegment?.side)
    }

    @Test
    fun `undoing a cancel brings the timer back`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        val cancel = table.apply(Timers.cancel(table.timed(id), cx))
        table.undo(cancel, cx)
        assertNull(table.events.getValue(id).deletedAt)
        assertEquals(EventStatus.RUNNING, table.events.getValue(id).status)
    }

    @Test
    fun `undo is refused if a row changed since`() {
        val start = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx))
        table.apply(Timers.stop(table.timed(start.newEventId()), t0 + HOUR, cx))
        assertNull(table.undo(start, cx))
        assertEquals(EventStatus.ENDED, table.events.getValue(start.newEventId()).status)
    }

    @Test
    fun `a start and its offset fix undo together`() {
        val start = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT))
        val id = start.newEventId()
        val moved = table.apply(Timers.moveStart(table.timed(id), t0 - 10 * MIN, cx))
        val combined = start.then(moved)
        assertNull(combined.events.single().before)
        assertNotNull(table.undo(combined, cx))
        assertNotNull(table.events.getValue(id).deletedAt)
        assertTrue(table.segments.values.all { it.deletedAt != null })
    }
}

class PickedTimeTest {
    @Test
    fun `a picked time for a stop is the latest one not after now`() {
        val now = dublin(2026, 11, 10, 0, 10)
        assertEquals(dublin(2026, 11, 9, 23, 50), PickedTime.latestAtOrBefore(LocalTime.of(23, 50), now, DUBLIN))
        assertEquals(dublin(2026, 11, 10, 0, 5), PickedTime.latestAtOrBefore(LocalTime.of(0, 5), now, DUBLIN))
        assertEquals(now, PickedTime.latestAtOrBefore(LocalTime.of(0, 10), now, DUBLIN))
    }

    @Test
    fun `a picked end for an unknown end is the first one after the start`() {
        val start = dublin(2026, 11, 9, 22, 0)
        assertEquals(dublin(2026, 11, 9, 22, 40), PickedTime.earliestAtOrAfter(LocalTime.of(22, 40), start, DUBLIN))
        assertEquals(dublin(2026, 11, 10, 1, 15), PickedTime.earliestAtOrAfter(LocalTime.of(1, 15), start, DUBLIN))
    }

    @Test
    fun `minus chips`() {
        assertEquals(1_000_000L - 10 * MIN, PickedTime.minutesBefore(1_000_000L, 10))
    }
}

class VolumeTest {
    @Test
    fun `ounces convert to millilitres and back`() {
        val ml = Volume.toMl(3.0, VolumeUnit.FL_OZ)
        assertEquals(88.72, ml, 0.01)
        assertEquals(3.0, Volume.fromMl(ml, VolumeUnit.FL_OZ), 1e-9)
        assertEquals(3.0, Volume.snapped(ml, VolumeUnit.FL_OZ), 1e-9)
        assertEquals(90.0, Volume.snapped(88.72, VolumeUnit.ML), 1e-9)
    }

    @Test
    fun `default unit by region`() {
        assertEquals(VolumeUnit.FL_OZ, VolumeUnit.defaultFor("US"))
        assertEquals(VolumeUnit.ML, VolumeUnit.defaultFor("IE"))
        assertEquals(VolumeUnit.ML, VolumeUnit.defaultFor(null))
    }
}
