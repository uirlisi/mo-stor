package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pausing a breast feed (SPEC §5.4) and what a forgotten pause does (§5.5). */
class PauseTest {
    private val t0 = dublin(2026, 11, 11, 14, 0)
    private val cx = FakeCx(t0)
    private val table = Table()

    private fun startLeft(): String = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT)).newEventId()

    private fun at(min: Int): Long = t0 + min * MIN

    private fun pausedAt10(): String {
        val id = startLeft()
        cx.now = at(10)
        table.apply(Timers.pause(table.timed(id), cx.now, cx))
        return id
    }

    @Test
    fun `pause closes the segment and the feed stays running, paused`() {
        val id = pausedAt10()
        val t = table.timed(id)
        assertEquals(EventStatus.RUNNING, t.event.status)
        assertTrue(t.isPaused)
        assertEquals(at(10), t.pausedAt)
        assertNull(t.openSegment)
        assertEquals(Side.LEFT, t.lastSide)
        // Pausing again does nothing.
        assertTrue(Timers.pause(t, at(12), cx).isEmpty)
    }

    @Test
    fun `resume on the same side, and the paused time doesn't count`() {
        val id = pausedAt10()
        cx.now = at(15)
        table.apply(Timers.resume(table.timed(id), Side.LEFT, cx.now, cx))
        val t = table.timed(id)
        assertFalse(t.isPaused)
        assertEquals(Side.LEFT, t.openSegment?.side)
        assertEquals(at(15), t.openSegment?.startAt)
        assertEquals(15 * MIN, Durations.of(t, at(20)))
    }

    @Test
    fun `switch side while paused resumes on the other side`() {
        val id = pausedAt10()
        cx.now = at(13)
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        val t = table.timed(id)
        assertEquals(Side.RIGHT, t.openSegment?.side)
        assertEquals(at(13), t.openSegment?.startAt)
        assertEquals(listOf(Side.LEFT, Side.RIGHT), t.segments.map { it.side })
    }

    @Test
    fun `a resume can't start before the pause`() {
        val id = pausedAt10()
        cx.now = at(12)
        table.apply(Timers.resume(table.timed(id), Side.RIGHT, at(5), cx))
        assertEquals(at(10), table.timed(id).openSegment?.startAt)
    }

    @Test
    fun `stop while paused ends the feed at the pause`() {
        val id = pausedAt10()
        cx.now = at(40)
        table.apply(Timers.stop(table.timed(id), cx.now, cx))
        val t = table.timed(id)
        assertEquals(EventStatus.ENDED, t.event.status)
        assertEquals(at(10), t.event.endAt)
        assertEquals(at(10), t.segments.single().endAt)
    }

    @Test
    fun `an earlier stop on a paused feed shortens its last segment`() {
        val id = pausedAt10()
        cx.now = at(30)
        table.apply(Timers.stop(table.timed(id), at(6), cx))
        val t = table.timed(id)
        assertEquals(at(6), t.event.endAt)
        assertEquals(at(6), t.segments.single().endAt)
        assertEquals(6 * MIN, Durations.of(t, cx.now))
    }

    @Test
    fun `move the pause earlier, clamped to the segment's start and now`() {
        val id = pausedAt10()
        table.apply(Timers.moveLastPause(table.timed(id), at(5), cx))
        assertEquals(at(5), table.timed(id).pausedAt)
        table.apply(Timers.moveLastPause(table.timed(id), at(-30), cx))
        assertEquals(t0, table.timed(id).pausedAt)
        table.apply(Timers.moveLastPause(table.timed(id), at(60), cx))
        assertEquals(cx.now, table.timed(id).pausedAt)
    }

    @Test
    fun `move the resume earlier, but not before the pause`() {
        val id = pausedAt10()
        cx.now = at(20)
        table.apply(Timers.resume(table.timed(id), Side.RIGHT, cx.now, cx))
        table.apply(Timers.moveLastResume(table.timed(id), at(15), cx))
        assertEquals(at(15), table.timed(id).openSegment?.startAt)
        table.apply(Timers.moveLastResume(table.timed(id), at(2), cx))
        assertEquals(at(10), table.timed(id).openSegment?.startAt)
    }

    @Test
    fun `pause and resume can be undone`() {
        val id = startLeft()
        cx.now = at(10)
        val pause = table.apply(Timers.pause(table.timed(id), cx.now, cx))
        cx.now = at(12)
        val resume = table.apply(Timers.resume(table.timed(id), Side.RIGHT, cx.now, cx))
        table.undo(resume, cx)
        assertTrue(table.timed(id).isPaused)
        table.undo(pause, cx)
        val t = table.timed(id)
        assertFalse(t.isPaused)
        assertEquals(Side.LEFT, t.openSegment?.side)
        assertEquals(1, t.segments.size)
    }

    @Test
    fun `a paused feed doesn't nudge, and ends at its pause after the nudge interval`() {
        val id = pausedAt10()
        val t = table.timed(id)
        val limits = OverrunLimits.BREAST_DEFAULT
        // Long past the usual nudge (45 min from the start), but only 40 min paused.
        assertEquals(OverrunState.NORMAL, Overrun.state(t, at(50), limits, null))
        assertEquals(at(55), Overrun.nextCheck(t, at(50), limits, null))
        assertEquals(OverrunState.END_AT_PAUSE, Overrun.state(t, at(55), limits, null))
        // Never, when the nudge is off.
        assertNull(Overrun.nextCheck(t, at(50), OverrunLimits(null, null), null))
        assertEquals(OverrunState.NORMAL, Overrun.state(t, at(500), OverrunLimits(null, null), null))
    }

    @Test
    fun `a running feed keeps the usual overrun rules`() {
        val id = startLeft()
        val t = table.timed(id)
        assertEquals(OverrunState.NUDGE, Overrun.state(t, at(45), OverrunLimits.BREAST_DEFAULT, null))
    }
}
