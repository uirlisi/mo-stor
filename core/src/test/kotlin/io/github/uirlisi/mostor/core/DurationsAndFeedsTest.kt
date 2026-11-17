package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DurationsTest {
    private val t0 = dublin(2026, 11, 10, 14, 0)
    private val cx = FakeCx(t0)
    private val table = Table()

    @Test
    fun `breast duration sums segments per side, a running one up to now`() {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.LEFT)).newEventId()
        cx.now = t0 + 12 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        val now = t0 + 20 * MIN
        val totals = Durations.breast(table.timed(id).segments, now)
        assertEquals(BreastTotals(12 * MIN, 8 * MIN), totals)
        assertEquals(20 * MIN, Durations.of(table.timed(id), now))
    }

    @Test
    fun `sleep duration is end minus start`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        table.apply(Timers.stop(table.timed(id), t0 + 95 * MIN, cx))
        assertEquals(95 * MIN, Durations.of(table.timed(id), t0 + 10 * HOUR))
    }

    @Test
    fun `an unknown end has no duration`() {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, t0, cx)).newEventId()
        table.apply(Timers.giveUp(table.timed(id), cx))
        assertNull(Durations.of(table.timed(id), t0 + 20 * HOUR))
    }

    private fun sleep(start: Long, end: Long?, status: EventStatus = EventStatus.ENDED) = Event(
        id = "s$start", childId = CHILD, kind = Kind.SLEEP, startAt = start, endAt = end, status = status,
        tz = "UTC", createdAt = start, updatedAt = start, hlc = "h",
    )

    @Test
    fun `awake for counts from the end of the last ended sleep`() {
        val sleeps = listOf(sleep(t0 - 5 * HOUR, t0 - 4 * HOUR), sleep(t0 - 2 * HOUR, t0 - 65 * MIN))
        assertEquals(t0 - 65 * MIN, Durations.awakeSince(sleeps))
        assertEquals(65 * MIN, Durations.awakeFor(sleeps, t0))
    }

    @Test
    fun `no awake time while a sleep runs or when the last end is unknown`() {
        val ended = sleep(t0 - 5 * HOUR, t0 - 4 * HOUR)
        assertNull(Durations.awakeFor(listOf(ended, sleep(t0 - HOUR, null, EventStatus.RUNNING)), t0))
        assertNull(Durations.awakeFor(listOf(ended, sleep(t0 - 3 * HOUR, null, EventStatus.END_UNKNOWN)), t0))
        assertNull(Durations.awakeFor(emptyList(), t0))
    }

    @Test
    fun `an older unknown end doesn't hide a later known one`() {
        val sleeps = listOf(sleep(t0 - 20 * HOUR, null, EventStatus.END_UNKNOWN), sleep(t0 - 2 * HOUR, t0 - HOUR))
        assertEquals(HOUR, Durations.awakeFor(sleeps, t0))
    }

    @Test
    fun `deleted sleeps don't count`() {
        val sleeps = listOf(sleep(t0 - 2 * HOUR, t0 - HOUR), sleep(t0 - 30 * MIN, null, EventStatus.RUNNING).copy(deletedAt = t0))
        assertEquals(HOUR, Durations.awakeFor(sleeps, t0))
    }
}

class FeedsTest {
    private val t0 = dublin(2026, 11, 10, 14, 0)

    private fun ev(kind: Kind, start: Long, end: Long? = null, status: EventStatus? = null) = Event(
        id = "$kind$start", childId = CHILD, kind = kind, startAt = start, endAt = end, status = status,
        tz = "UTC", createdAt = start, updatedAt = start, hlc = "h",
    )

    @Test
    fun `the last feed is the latest breast or bottle, never a supplement`() {
        val events = listOf(
            ev(Kind.BREAST, t0 - 3 * HOUR, t0 - 150 * MIN, EventStatus.ENDED),
            ev(Kind.BOTTLE, t0 - 2 * HOUR),
            ev(Kind.SUPPLEMENT, t0 - HOUR),
            ev(Kind.DIAPER, t0 - 30 * MIN),
        )
        assertEquals(Kind.BOTTLE, Feeds.lastFeed(events)?.kind)
        assertEquals(2 * HOUR, Feeds.sinceLastFeed(events, t0, FeedAnchor.START))
    }

    @Test
    fun `measured from the end when set`() {
        val feed = ev(Kind.BREAST, t0 - 3 * HOUR, t0 - 150 * MIN, EventStatus.ENDED)
        assertEquals(150 * MIN, Feeds.sinceLastFeed(listOf(feed), t0, FeedAnchor.END))
        assertNull(Feeds.anchor(ev(Kind.BREAST, t0, null, EventStatus.RUNNING), FeedAnchor.END))
        assertNull(Feeds.anchor(ev(Kind.BREAST, t0, null, EventStatus.END_UNKNOWN), FeedAnchor.END))
        assertEquals(t0, Feeds.anchor(ev(Kind.BOTTLE, t0), FeedAnchor.END))
    }

    @Test
    fun `no feeds, no answer`() {
        assertNull(Feeds.sinceLastFeed(listOf(ev(Kind.DIAPER, t0)), t0, FeedAnchor.START))
    }

    @Test
    fun `last side is the side of the last segment`() {
        val cx = FakeCx(t0)
        val table = Table()
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, t0, cx, Side.RIGHT)).newEventId()
        cx.now = t0 + 10 * MIN
        table.apply(Timers.switchSide(table.timed(id), cx.now, cx))
        assertEquals(Side.LEFT, Feeds.lastSide(table.timed(id)))
        assertNull(Feeds.lastSide(null))
    }
}

class SummaryTest {
    private val clock = DayClock(0, DUBLIN)
    private val day = LocalDate.of(2026, 11, 10)

    private fun ev(kind: Kind, start: Long, subtype: String? = null, deleted: Long? = null) = Event(
        id = "$kind$start$subtype", childId = CHILD, kind = kind, subtype = subtype, startAt = start,
        tz = "UTC", createdAt = start, updatedAt = start, deletedAt = deleted, hlc = "h",
    )

    @Test
    fun `counts feeds and diapers, mixed as both, by start day`() {
        val events = listOf(
            ev(Kind.BREAST, dublin(2026, 11, 9, 23, 50)), // yesterday
            ev(Kind.BREAST, dublin(2026, 11, 10, 2)),
            ev(Kind.BOTTLE, dublin(2026, 11, 10, 5), BottleContent.FORMULA.code),
            ev(Kind.SUPPLEMENT, dublin(2026, 11, 10, 6)),
            ev(Kind.DIAPER, dublin(2026, 11, 10, 7), Diaper.WET.code),
            ev(Kind.DIAPER, dublin(2026, 11, 10, 8), Diaper.MIXED.code),
            ev(Kind.DIAPER, dublin(2026, 11, 10, 9), Diaper.POOP.code),
            ev(Kind.DIAPER, dublin(2026, 11, 10, 10), Diaper.DRY.code),
            ev(Kind.DIAPER, dublin(2026, 11, 10, 11), Diaper.WET.code, deleted = 1),
            ev(Kind.DIAPER, dublin(2026, 11, 11, 0), Diaper.WET.code), // tomorrow
        )
        assertEquals(DayCounts(feeds = 2, wet = 2, poop = 2, diapers = 4), DaySummary.counts(events, day, clock))
    }
}
