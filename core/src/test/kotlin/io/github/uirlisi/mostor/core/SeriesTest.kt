package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Per-day series and time between feeds (SPEC §5.8). */
class SeriesTest {
    private val clock = DayClock(0, DUBLIN)
    private val cx = FakeCx(dublin(2026, 1, 1, 0))
    private val table = Table()

    private fun t(d: Int, h: Int, mi: Int = 0) = dublin(2026, 11, d, h, mi)
    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    private fun sleep(from: Long, to: Long?): String {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, from, cx)).newEventId()
        if (to != null) table.apply(Timers.stop(table.timed(id), to, cx))
        return id
    }

    private fun feed(at: Long, side: Side, minutes: Int, switchAfter: Int? = null): String {
        val id = table.apply(Timers.start(Kind.BREAST, CHILD, at, cx, side)).newEventId()
        if (switchAfter != null) table.apply(Timers.switchSide(table.timed(id), at + switchAfter * MIN, cx))
        table.apply(Timers.stop(table.timed(id), at + minutes * MIN, cx))
        return id
    }

    private fun days(from: Int, to: Int, now: Long, first: Long? = table.events.values.minOfOrNull { it.startAt }, focus: ChartFocus = ChartFocus.All) =
        Series.days(
            ChartFocus.select(table.events.values.toList(), focus, Visibility(), showHidden = false),
            table.segments.values.toList(), day(from), day(to), clock, now, first, focus,
        )

    @Test
    fun `each day's totals match the day summary, with sleep split at midnight`() {
        sleep(t(9, 22), t(10, 6))
        sleep(t(10, 13), t(10, 14, 30))
        feed(t(10, 8), Side.LEFT, 25, switchAfter = 10)
        val points = days(9, 11, t(11, 12))
        assertEquals(listOf(day(9), day(10), day(11)), points.map { it.day })
        assertEquals(listOf(2.0, 7.5, 0.0), points.map { Metric.SLEEP_MS.total(it) / HOUR })
        assertEquals(listOf(listOf(0.0, 0.0), listOf(10.0, 15.0), listOf(0.0, 0.0)), points.map { p -> Metric.BREAST_MS.values(p).map { it / MIN } })
        assertEquals(
            DaySummary.totals(table.events.values.toList(), table.segments.values.toList(), day(10), clock, t(11, 12)),
            points[1].totals,
        )
        // The longest stretch counts on the day the sleep started.
        assertEquals(listOf(8.0, 1.5, 0.0), points.map { Metric.LONGEST_SLEEP_MS.total(it) / HOUR })
    }

    @Test
    fun `today is partial, days before the first record have no data, averages use full days only`() {
        table.apply(Records.diaper(CHILD, Diaper.WET, t(9, 9), cx))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(9, 10), cx))
        table.apply(Records.diaper(CHILD, Diaper.POOP, t(10, 9), cx))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(11, 9), cx))
        val points = days(7, 11, t(11, 12))
        assertEquals(listOf(true, true, false, false, false), points.map { it.beforeFirst })
        assertEquals(listOf(false, false, false, false, true), points.map { it.partial })
        // Only the 9th (2) and 10th (1) are full days.
        assertEquals(1.5, Series.average(points, Metric.DIAPERS))
        assertNull(Series.average(points.takeLast(1), Metric.DIAPERS))
        assertEquals(listOf(2.0, 0.0, 0.0, 0.0), Metric.DIAPERS.values(points[2]))
    }

    @Test
    fun `no records at all - every day is before the first`() {
        val points = days(9, 11, t(11, 12), first = null)
        assertTrue(points.all { it.beforeFirst })
        assertNull(Series.average(points, Metric.FEEDS))
    }

    @Test
    fun `poop only keeps poop and mixed, stacked separately`() {
        table.apply(Records.diaper(CHILD, Diaper.POOP, t(10, 9), cx))
        table.apply(Records.diaper(CHILD, Diaper.MIXED, t(10, 10), cx))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(10, 11), cx))
        val p = days(10, 10, t(11, 12), focus = ChartFocus.Subtype(Kind.DIAPER, "poop")).single()
        assertEquals(listOf(0.0, 1.0, 1.0, 0.0), Metric.DIAPERS.values(p))
        assertEquals(2.0, Metric.DIAPERS.total(p))
    }

    @Test
    fun `left side only - left minutes, and only the feeds that used the left side`() {
        feed(t(10, 8), Side.LEFT, 25, switchAfter = 10)
        feed(t(10, 11), Side.RIGHT, 15)
        feed(t(10, 14), Side.LEFT, 20)
        val all = days(10, 10, t(11, 12)).single()
        assertEquals(mapOf(Side.LEFT to 2, Side.RIGHT to 2), all.feedsBySide)
        assertEquals(3, all.totals.breastFeeds)

        val left = days(10, 10, t(11, 12), focus = ChartFocus.OnSide(Side.LEFT)).single()
        assertEquals(listOf(30.0, 0.0), Metric.BREAST_MS.values(left).map { it / MIN })
        assertEquals(2, left.totals.breastFeeds)
        assertEquals(2, left.feedsBySide[Side.LEFT])
    }

    @Test
    fun `feeds stack breast and bottle, bottles stack by content, pumps add up`() {
        feed(t(10, 8), Side.LEFT, 20)
        table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 90.0, t(10, 11), cx))
        table.apply(Records.bottle(CHILD, BottleContent.BREAST_MILK, 60.0, t(10, 15), cx))
        table.apply(Records.bottle(CHILD, BottleContent.BREAST_MILK, 50.0, t(10, 18), cx))
        table.apply(Records.pump(CHILD, 120.0, t(10, 10), cx))
        table.apply(Records.pump(CHILD, 100.0, t(10, 16), cx))
        val p = days(10, 10, t(11, 12)).single()
        assertEquals(listOf(1.0, 3.0), Metric.FEEDS.values(p))
        assertEquals(listOf(110.0, 90.0), Metric.BOTTLE_ML.values(p))
        assertEquals(listOf(2.0, 1.0), Metric.BOTTLES.values(p))
        assertEquals(listOf(220.0), Metric.PUMP_ML.values(p))
        assertEquals(listOf(2.0), Metric.PUMPS.values(p))
    }

    @Test
    fun `a running sleep counts to now on each day it touches, unknown ends are counted apart`() {
        sleep(t(10, 22), null)
        val unknown = sleep(t(10, 13), null)
        table.apply(Timers.giveUp(table.timed(unknown), cx))
        val points = days(10, 11, t(11, 1))
        assertEquals(listOf(2.0, 1.0), points.map { Metric.SLEEP_MS.total(it) / HOUR })
        assertEquals(listOf(2.0, 0.0), points.map { Metric.SLEEPS.total(it) })
        assertEquals(1, Series.withoutEnd(points))
    }

    @Test
    fun `a 25 hour day can hold more than 24 hours of sleep time, in real hours`() {
        sleep(dublin(2026, 10, 24, 23), dublin(2026, 10, 26, 0, 30))
        val points = Series.days(
            table.events.values.toList(), table.segments.values.toList(),
            LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26), clock, t(11, 12), dublin(2026, 10, 24, 23),
        )
        assertEquals(listOf(1.0, 25.0, 0.5), points.map { Metric.SLEEP_MS.total(it) / HOUR })
    }

    @Test
    fun `the side share sums both sides over the range`() {
        feed(t(9, 8), Side.LEFT, 30)
        feed(t(10, 8), Side.RIGHT, 10)
        val share = Series.sideShare(days(9, 10, t(11, 12)))
        assertEquals(30 * MIN, share.leftMs)
        assertEquals(10 * MIN, share.rightMs)
    }

    // ---- Time between feeds ----

    @Test
    fun `gaps are start to start and count on the day of the later feed`() {
        feed(t(9, 22), Side.LEFT, 20)
        feed(t(10, 1), Side.RIGHT, 20) // 3 h, counted on the 10th
        table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 90.0, t(10, 3), cx)) // 2 h
        feed(t(10, 7), Side.LEFT, 20) // 4 h
        val gaps = Series.feedGaps(table.events.values.toList(), day(10), day(11), clock)
        assertEquals(GapDay(day(10), 3, 9 * HOUR, 4 * HOUR), gaps[0])
        assertEquals(3 * HOUR, gaps[0].avgMs)
        assertEquals(GapDay(day(11), 0, 0, null), gaps[1])
        assertNull(gaps[1].avgMs)
    }

    @Test
    fun `supplements are not feeds, a running feed is a start, deleted feeds are skipped`() {
        feed(t(10, 8), Side.LEFT, 20)
        val vitD = SupplementPreset("p-d", "c1", "Vit D", createdAt = 0, updatedAt = 0, hlc = "h")
        table.apply(Records.supplement(CHILD, vitD, t(10, 9), cx))
        val gone = table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 90.0, t(10, 10), cx)).newEventId()
        table.events[gone] = table.events.getValue(gone).copy(deletedAt = t(10, 11))
        table.apply(Timers.start(Kind.BREAST, CHILD, t(10, 11), cx, Side.RIGHT))
        val gap = Series.feedGaps(table.events.values.toList(), day(10), day(10), clock).single()
        assertEquals(1, gap.count)
        assertEquals(3 * HOUR, gap.longestMs)
    }

    @Test
    fun `the average gap weighs every gap the same over full days`() {
        feed(t(9, 0), Side.LEFT, 10)
        feed(t(9, 2), Side.LEFT, 10) // 2 h on the 9th
        feed(t(10, 0), Side.LEFT, 10) // 22 h on the 10th
        feed(t(10, 1), Side.LEFT, 10) // 1 h
        feed(t(10, 2), Side.LEFT, 10) // 1 h
        feed(t(11, 2), Side.LEFT, 10) // today: left out
        val gaps = Series.feedGaps(table.events.values.toList(), day(9), day(11), clock)
        assertEquals(26 * HOUR / 4, Series.averageGap(gaps) { it < day(11) })
        assertFalse(gaps.last().count == 0)
    }
}
