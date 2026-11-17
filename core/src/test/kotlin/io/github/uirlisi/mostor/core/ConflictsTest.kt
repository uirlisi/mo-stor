package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConflictsTest {
    private val t0 = dublin(2026, 11, 14, 21)
    private val cx = FakeCx(t0 + 10 * MIN)

    private fun sleep(id: String, start: Long, child: String = CHILD) = TimedEvent.of(
        Event(id = id, childId = child, kind = Kind.SLEEP, startAt = start, status = EventStatus.RUNNING, tz = "Europe/Dublin", createdAt = start, updatedAt = start, hlc = "h"),
    )

    private fun feed(id: String, start: Long) = TimedEvent.of(
        Event(id = id, childId = CHILD, kind = Kind.BREAST, startAt = start, status = EventStatus.RUNNING, tz = "Europe/Dublin", createdAt = start, updatedAt = start, hlc = "h"),
        listOf(BreastSegment(id = "s-$id", eventId = id, side = Side.LEFT, startAt = start, createdAt = start, updatedAt = start, hlc = "h")),
    )

    @Test
    fun `two timers of one kind for one child conflict, other kinds and children don't`() {
        val found = Conflicts.find(
            listOf(sleep("b", t0 + 2 * MIN), sleep("a", t0), feed("f", t0), sleep("x", t0, child = "other")),
            emptySet(),
        )
        assertEquals(1, found.size)
        assertEquals(listOf("a", "b"), found.single().timers.map { it.event.id })
        assertEquals(Kind.SLEEP, found.single().kind)
    }

    @Test
    fun `keep earlier cancels the later one, keep later the earlier, keep both nothing`() {
        val c = Conflicts.find(listOf(sleep("a", t0), sleep("b", t0 + 2 * MIN)), emptySet()).single()
        assertEquals(listOf("b"), Conflicts.resolve(c, Keep.EARLIER, cx).events.map { it.after.id })
        assertTrue(Conflicts.resolve(c, Keep.EARLIER, cx).events.all { it.after.deletedAt != null })
        assertEquals(listOf("a"), Conflicts.resolve(c, Keep.LATER, cx).events.map { it.after.id })
        assertTrue(Conflicts.resolve(c, Keep.BOTH, cx).isEmpty)
    }

    @Test
    fun `a canceled feed takes its segments with it`() {
        val c = Conflicts.find(listOf(feed("a", t0), feed("b", t0 + MIN)), emptySet()).single()
        val change = Conflicts.resolve(c, Keep.EARLIER, cx)
        assertEquals(listOf("s-b"), change.segments.map { it.after.id })
        assertTrue(change.segments.single().after.deletedAt != null)
    }

    @Test
    fun `a kept pair is not asked about again, but a third timer makes a new question`() {
        val pair = Conflicts.find(listOf(sleep("a", t0), sleep("b", t0 + MIN)), emptySet()).single()
        assertTrue(Conflicts.find(listOf(sleep("a", t0), sleep("b", t0 + MIN)), setOf(pair.key)).isEmpty())
        assertEquals(1, Conflicts.find(listOf(sleep("a", t0), sleep("b", t0 + MIN), sleep("c", t0 + 2 * MIN)), setOf(pair.key)).size)
    }
}
