package io.github.uirlisi.mostor.core

import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Editing records (SPEC §6.5), keeping SPEC §4.4's invariants. */
class EventEditTest {
    private val t0 = dublin(2026, 11, 11, 14, 0)
    private val cx = FakeCx(t0)
    private val table = Table()

    private fun at(min: Int): Long = t0 + min * MIN

    /** A feed: L 14:00–14:15, R 14:18–14:30, ended (cx.now is then 14:30). */
    private fun endedFeed(): String {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, at(0), cx, Side.LEFT)).newEventId()
        cx.now = at(15)
        table.apply(Timers.pause(table.timed(id), cx.now, cx))
        cx.now = at(18)
        table.apply(Timers.resume(table.timed(id), Side.RIGHT, cx.now, cx))
        cx.now = at(30)
        table.apply(Timers.stop(table.timed(id), cx.now, cx))
        return id
    }

    private fun draft(id: String) = EventEdit.draftOf(table.events.getValue(id), table.segments.values.toList())

    private fun save(id: String, d: EventDraft): Change {
        val change = EventEdit.toChange(table.events.getValue(id), table.segments.values.toList(), d, cx)
        return table.apply(change)
    }

    @Test
    fun `a skipped med dose stays skipped through Edit, and Given turns it back`() {
        val e = Event(
            "dose", "c1", Kind.SUPPLEMENT, startAt = at(0), status = EventStatus.SKIPPED, supplementPresetId = "p",
            supplementName = "Vit K", tz = "Europe/Dublin", createdAt = at(0), updatedAt = at(0), hlc = "h",
        )
        val d = EventEdit.draftOf(e)
        assertTrue(d.skipped)
        assertTrue(EventEdit.toChange(e, emptyList(), d.copy(note = "  "), cx).isEmpty)
        val given = EventEdit.toChange(e, emptyList(), d.copy(skipped = false), cx).events.single().after
        assertNull(given.status)
    }

    @Test
    fun `draft of a feed has its segments in order and nothing changes when saved as is`() {
        val id = endedFeed()
        val d = draft(id)
        assertEquals(listOf(Side.LEFT, Side.RIGHT), d.segments.map { it.side })
        assertEquals(at(0), d.start)
        assertEquals(at(30), d.end)
        assertTrue(EventEdit.problems(d, at(60)).isEmpty())
        assertTrue(EventEdit.toChange(table.events.getValue(id), table.segments.values.toList(), d, cx).isEmpty)
    }

    @Test
    fun `changing a segment's side and times updates only that segment, and the event follows its segments`() {
        val id = endedFeed()
        cx.now = at(60)
        var d = draft(id)
        d = EventEdit.setSide(d, 1, Side.LEFT)
        d = EventEdit.nudge(d, TimeField.SegmentEnd(1), 5 * MIN, at(60))
        val change = save(id, d)
        assertEquals(1, change.segments.size)
        val t = table.timed(id)
        assertEquals(listOf(Side.LEFT, Side.LEFT), t.segments.map { it.side })
        assertEquals(at(35), t.event.endAt)
        assertEquals(EventStatus.ENDED, t.event.status)
    }

    @Test
    fun `moving the first segment's start moves the event's start`() {
        val id = endedFeed()
        cx.now = at(60)
        val d = EventEdit.nudge(draft(id), TimeField.SegmentStart(0), -10 * MIN, at(60))
        save(id, d)
        assertEquals(at(-10), table.events.getValue(id).startAt)
    }

    @Test
    fun `nudges stop at the neighbouring segments and at now`() {
        val id = endedFeed()
        val d = draft(id)
        // The first segment's end can't pass the second's start (14:18).
        assertEquals(at(18), EventEdit.value(EventEdit.nudge(d, TimeField.SegmentEnd(0), 30 * MIN, at(60)), TimeField.SegmentEnd(0)))
        // The second's start can't go before the first's end (14:15).
        assertEquals(at(15), EventEdit.value(EventEdit.nudge(d, TimeField.SegmentStart(1), -30 * MIN, at(60)), TimeField.SegmentStart(1)))
        // The last end can't pass now.
        assertEquals(at(40), EventEdit.value(EventEdit.nudge(d, TimeField.SegmentEnd(1), 30 * MIN, at(40)), TimeField.SegmentEnd(1)))
        // A start can't pass its own end.
        assertEquals(at(15), EventEdit.value(EventEdit.nudge(d, TimeField.SegmentStart(0), 60 * MIN, at(60)), TimeField.SegmentStart(0)))
    }

    @Test
    fun `a picked time that overlaps is reported, not clamped`() {
        val id = endedFeed()
        val d = EventEdit.set(draft(id), TimeField.SegmentStart(1), at(10))
        assertEquals(listOf(EditProblem(EditProblem.Type.OVERLAP, 1)), EventEdit.problems(d, at(60)))
        val back = EventEdit.set(draft(id), TimeField.SegmentEnd(0), at(-5))
        assertTrue(EditProblem(EditProblem.Type.END_BEFORE_START, 0) in EventEdit.problems(back, at(60)))
    }

    @Test
    fun `times after now are reported`() {
        val id = endedFeed()
        val d = EventEdit.set(draft(id), TimeField.SegmentEnd(1), at(90))
        assertEquals(listOf(EditProblem(EditProblem.Type.IN_FUTURE, 1)), EventEdit.problems(d, at(60)))
    }

    @Test
    fun `removing a segment soft-deletes it, adding one creates it on the other side`() {
        val id = endedFeed()
        cx.now = at(60)
        var d = EventEdit.removeSegment(draft(id), 1)
        assertEquals(1, d.segments.size)
        // The only segment can't be removed.
        assertEquals(d, EventEdit.removeSegment(d, 0))
        d = EventEdit.addSegment(d, at(60))
        assertEquals(Side.RIGHT, d.segments[1].side)
        assertEquals(at(15), d.segments[1].startAt)
        assertEquals(at(20), d.segments[1].endAt)
        val change = save(id, d)
        assertEquals(1, change.segments.count { it.before == null })
        assertEquals(1, change.segments.count { it.after.deletedAt != null })
        val t = table.timed(id)
        assertEquals(2, t.segments.size)
        assertEquals(at(20), t.event.endAt)
    }

    @Test
    fun `an added segment never passes now`() {
        val id = endedFeed()
        val d = EventEdit.addSegment(draft(id), at(33))
        assertEquals(at(30), d.segments.last().startAt)
        assertEquals(at(33), d.segments.last().endAt)
    }

    @Test
    fun `without room after the feed, the last segment is split onto the other side`() {
        // A feed added after the fact ends at now: "it was both sides" splits it.
        val d = EventEdit.blank(Kind.BREAST, CHILD, at(60), Side.LEFT)
        assertTrue(EventEdit.canAddSegment(d, at(60)))
        val split = EventEdit.addSegment(d, at(60))
        assertEquals(listOf(Side.LEFT, Side.RIGHT), split.segments.map { it.side })
        assertEquals(listOf(at(45), at(52)), split.segments.map { it.startAt })
        assertEquals(listOf(at(52), at(60)), split.segments.map { it.endAt })
        assertTrue(EventEdit.problems(split, at(60)).isEmpty())
        // A segment under 2 minutes with no room after it can't take another.
        val tiny = EventDraft(id = null, childId = CHILD, kind = Kind.BREAST, startAt = at(59), segments = listOf(SegmentDraft(null, Side.LEFT, at(59), at(60))))
        assertFalse(EventEdit.canAddSegment(tiny, at(60)))
        assertEquals(tiny, EventEdit.addSegment(tiny, at(60)))
    }

    @Test
    fun `end unknown clears the last segment's end and can be set again`() {
        val id = endedFeed()
        cx.now = at(60)
        val unknown = EventEdit.setEndUnknown(draft(id), true)
        assertNull(unknown.segments.last().endAt)
        assertTrue(EventEdit.problems(unknown, at(60)).isEmpty())
        assertFalse(EventEdit.canAddSegment(unknown, at(60)))
        save(id, unknown)
        val e = table.events.getValue(id)
        assertEquals(EventStatus.END_UNKNOWN, e.status)
        assertNull(e.endAt)

        val known = EventEdit.setEndUnknown(draft(id), false, at(28))
        save(id, known)
        val t = table.timed(id)
        assertEquals(EventStatus.ENDED, t.event.status)
        assertEquals(at(28), t.event.endAt)
        assertEquals(at(28), t.segments.last().endAt)
    }

    @Test
    fun `a running feed keeps its open segment and stays running`() {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, at(0), cx, Side.LEFT)).newEventId()
        cx.now = at(20)
        val d = draft(id)
        assertTrue(d.running)
        assertNull(d.end)
        assertTrue(EventEdit.problems(d, at(20)).isEmpty())
        // End unknown isn't offered on a running timer.
        assertEquals(d, EventEdit.setEndUnknown(d, true))
        assertFalse(EventEdit.canAddSegment(d, at(20)))
        save(id, EventEdit.nudge(d, TimeField.SegmentStart(0), -5 * MIN, at(20)))
        val t = table.timed(id)
        assertEquals(EventStatus.RUNNING, t.event.status)
        assertEquals(at(-5), t.event.startAt)
        assertNotNull(t.openSegment)
    }

    @Test
    fun `an open segment that isn't last is reported`() {
        val id = endedFeed()
        val d = draft(id).let { it.copy(running = true, segments = listOf(it.segments[0].copy(endAt = null), it.segments[1])) }
        assertTrue(EditProblem(EditProblem.Type.OPEN_SEGMENT, 0) in EventEdit.problems(d, at(60)))
    }

    @Test
    fun `a paused feed stays paused after an edit`() {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, at(0), cx, Side.LEFT)).newEventId()
        cx.now = at(10)
        table.apply(Timers.pause(table.timed(id), cx.now, cx))
        cx.now = at(20)
        val d = draft(id)
        assertTrue(d.isPaused)
        save(id, EventEdit.nudge(d, TimeField.SegmentEnd(0), -2 * MIN, at(20)))
        val t = table.timed(id)
        assertTrue(t.isPaused)
        assertEquals(at(8), t.pausedAt)
    }

    @Test
    fun `an edit is undone in one step`() {
        val id = endedFeed()
        cx.now = at(60)
        val before = table.timed(id)
        val d = EventEdit.removeSegment(EventEdit.setSide(draft(id), 0, Side.RIGHT), 1)
        val change = save(id, d)
        assertNotNull(table.undo(change, cx))
        val after = table.timed(id)
        assertEquals(before.segments.map { it.side to it.startAt }, after.segments.map { it.side to it.startAt })
        assertEquals(before.event.endAt, after.event.endAt)
    }

    @Test
    fun `sleep times, end unknown and problems`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, at(0), cx)).newEventId()
        cx.now = at(90)
        table.apply(Timers.stop(table.timed(id), cx.now, cx))
        var d = draft(id)
        d = EventEdit.nudge(d, TimeField.End, 60 * MIN, at(100))
        assertEquals(at(100), d.endAt)
        d = EventEdit.nudge(d, TimeField.Start, 300 * MIN, at(100))
        assertEquals(at(100), d.startAt)
        val bad = EventEdit.set(draft(id), TimeField.End, at(-1))
        assertEquals(listOf(EditProblem(EditProblem.Type.END_BEFORE_START)), EventEdit.problems(bad, at(100)))
        save(id, EventEdit.setEndUnknown(draft(id), true))
        assertEquals(EventStatus.END_UNKNOWN, table.events.getValue(id).status)
    }

    @Test
    fun `point records - subtype, amount, note, child`() {
        val change = table.apply(Records.bottle(CHILD, BottleContent.BREAST_MILK, 90.0, at(0), cx))
        val id = change.newEventId()
        cx.now = at(10)
        val d = draft(id).copy(subtype = BottleContent.FORMULA.code, amountMl = 120.0, note = "  spat up ", childId = "child-2")
        save(id, d)
        val e = table.events.getValue(id)
        assertEquals("formula", e.subtype)
        assertEquals(120.0, e.amountMl)
        assertEquals("spat up", e.note)
        assertEquals("child-2", e.childId)
        assertNull(e.status)

        assertEquals(listOf(EditProblem(EditProblem.Type.NO_AMOUNT)), EventEdit.problems(d.copy(amountMl = null), at(10)))
        val note = EventDraft(id = null, childId = CHILD, kind = Kind.NOTE, startAt = at(0), note = " ")
        assertEquals(listOf(EditProblem(EditProblem.Type.EMPTY_NOTE)), EventEdit.problems(note, at(10)))
    }

    @Test
    fun `a record added after the fact is a new event with new segments`() {
        cx.now = at(60)
        val d = EventEdit.blank(Kind.BREAST, CHILD, at(60), Side.RIGHT)
        assertTrue(EventEdit.problems(d, at(60)).isEmpty())
        val change = table.apply(EventEdit.toChange(null, emptyList(), d, cx))
        val id = change.newEventId()
        val t = table.timed(id)
        assertEquals(EventStatus.ENDED, t.event.status)
        assertEquals(at(45), t.event.startAt)
        assertEquals(at(60), t.event.endAt)
        assertEquals(Side.RIGHT, t.segments.single().side)
        assertEquals("Europe/Dublin", t.event.tz)

        val diaper = EventEdit.blank(Kind.DIAPER, CHILD, at(60)).copy(subtype = "wet", startAt = at(-120))
        val e = table.apply(EventEdit.toChange(null, emptyList(), diaper, cx)).events.single().after
        assertEquals(at(-120), e.startAt)
        assertNull(e.status)
    }

    @Test
    fun `moving a record to another day keeps its clock times`() {
        val id = endedFeed()
        val d = EventEdit.moveToDate(draft(id), LocalDate.of(2026, 11, 10), DUBLIN)
        assertEquals(dublin(2026, 11, 10, 14, 0), d.start)
        assertEquals(dublin(2026, 11, 10, 14, 30), d.end)
        assertEquals(dublin(2026, 11, 10, 14, 18), d.segments[1].startAt)
    }

    @Test
    fun `moving across a DST change keeps the clock reading`() {
        // Dublin goes back an hour on 2026-10-25.
        val start = dublin(2026, 10, 26, 9, 0)
        val d = EventDraft(id = null, childId = CHILD, kind = Kind.DIAPER, subtype = "wet", startAt = start)
        assertEquals(dublin(2026, 10, 24, 9, 0), EventEdit.moveToDate(d, LocalDate.of(2026, 10, 24), DUBLIN).startAt)
    }

    @Test
    fun `a picked time goes to the nearest day`() {
        val around = dublin(2026, 11, 11, 0, 10)
        assertEquals(dublin(2026, 11, 10, 23, 50), PickedTime.nearest(LocalTime.of(23, 50), around, DUBLIN))
        assertEquals(dublin(2026, 11, 11, 0, 30), PickedTime.nearest(LocalTime.of(0, 30), around, DUBLIN))
        val late = dublin(2026, 11, 11, 23, 40)
        assertEquals(dublin(2026, 11, 12, 0, 5), PickedTime.nearest(LocalTime.of(0, 5), late, DUBLIN))
    }

    @Test
    fun `overlaps with the same child's records of the same kind are found`() {
        val s1 = table.apply(Timers.start(Kind.SLEEP, CHILD, at(0), cx)).newEventId()
        cx.now = at(60)
        table.apply(Timers.stop(table.timed(s1), cx.now, cx))
        val other = table.apply(Timers.start(Kind.SLEEP, "child-2", at(10), cx)).newEventId()
        val d = EventDraft(id = null, childId = CHILD, kind = Kind.SLEEP, startAt = at(50), endAt = at(90))
        val all = table.events.values.toList()
        assertEquals(listOf(s1), EventEdit.overlaps(d, all, at(100)).map { it.id })
        assertTrue(EventEdit.overlaps(d.copy(startAt = at(60)), all, at(100)).isEmpty())
        assertFalse(other in EventEdit.overlaps(d, all, at(100)).map { it.id })

        val w1 = table.apply(Records.diaper(CHILD, Diaper.WET, at(0), cx)).newEventId()
        val w2 = EventDraft(id = null, childId = CHILD, kind = Kind.DIAPER, subtype = "wet", startAt = at(0) + 30_000)
        assertEquals(listOf(w1), EventEdit.overlaps(w2, table.events.values.toList(), at(100)).map { it.id })
    }

    @Test
    fun `delete soft-deletes the event and its segments`() {
        val id = endedFeed()
        val change = table.apply(EventEdit.delete(table.events.getValue(id), table.segments.values.toList(), cx))
        assertEquals(1, change.events.size)
        assertEquals(2, change.segments.size)
        assertNotNull(table.events.getValue(id).deletedAt)
        assertTrue(table.segments.values.all { it.deletedAt != null })
        table.undo(change, cx)
        assertNull(table.events.getValue(id).deletedAt)
        assertEquals(2, table.timed(id).segments.size)
    }
}
