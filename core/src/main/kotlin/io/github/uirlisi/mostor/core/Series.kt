package io.github.uirlisi.mostor.core

import java.time.LocalDate

/**
 * One day of a per-day series (SPEC §5.8). [totals] is the same day summary Today shows, so the
 * two never disagree. [partial]: today, still in progress (drawn lighter, left out of averages).
 * [beforeFirst]: before the child's first record, so there is no data (blank, not zero).
 * [feedsBySide]: breast feeds started on the day that used each side.
 */
data class DayPoint(
    val day: LocalDate,
    val totals: DayTotals,
    val partial: Boolean,
    val beforeFirst: Boolean,
    val feedsBySide: Map<Side, Int>,
) {
    /** Whether the day counts in averages. */
    val full: Boolean get() = !partial && !beforeFirst
}

/** How a metric's values read. Durations are ms, volumes ml. */
enum class MetricUnit { COUNT, DURATION, VOLUME }

/**
 * A per-day metric. Each day's value is stacked from [parts] (e.g. left and right minutes); a
 * metric with one part is a plain bar. Subtype filters work by narrowing the records first
 * ([ChartFocus]), so "poop only" leaves the poop and mixed parts of [DIAPERS].
 */
enum class Metric(val unit: MetricUnit, val parts: List<String>) {
    BREAST_MS(MetricUnit.DURATION, listOf(Side.LEFT.code, Side.RIGHT.code)),
    FEEDS(MetricUnit.COUNT, listOf(Kind.BREAST.code, Kind.BOTTLE.code)),
    SLEEP_MS(MetricUnit.DURATION, listOf(Kind.SLEEP.code)),
    LONGEST_SLEEP_MS(MetricUnit.DURATION, listOf(Kind.SLEEP.code)),
    SLEEPS(MetricUnit.COUNT, listOf(Kind.SLEEP.code)),
    DIAPERS(MetricUnit.COUNT, Diaper.entries.map { it.code }),
    BOTTLE_ML(MetricUnit.VOLUME, BottleContent.entries.map { it.code }),
    BOTTLES(MetricUnit.COUNT, BottleContent.entries.map { it.code }),
    PUMP_ML(MetricUnit.VOLUME, listOf(Kind.PUMP.code)),
    PUMPS(MetricUnit.COUNT, listOf(Kind.PUMP.code)),
    ;

    /** The day's value per part, in [parts] order. */
    fun values(p: DayPoint): List<Double> {
        val t = p.totals
        return when (this) {
            BREAST_MS -> listOf(t.breastLeftMs.toDouble(), t.breastRightMs.toDouble())
            FEEDS -> listOf(t.breastFeeds.toDouble(), (t.feeds - t.breastFeeds).toDouble())
            SLEEP_MS -> listOf(t.sleepMs.toDouble())
            LONGEST_SLEEP_MS -> listOf(t.longestSleepMs.toDouble())
            SLEEPS -> listOf(t.sleeps.toDouble())
            DIAPERS -> Diaper.entries.map { (t.diapers[it] ?: 0).toDouble() }
            BOTTLE_ML -> BottleContent.entries.map { t.bottles[it]?.ml ?: 0.0 }
            BOTTLES -> BottleContent.entries.map { (t.bottles[it]?.count ?: 0).toDouble() }
            PUMP_ML -> listOf(t.pumpMl)
            PUMPS -> listOf(t.pumps.toDouble())
        }
    }

    fun total(p: DayPoint): Double = values(p).sum()
}

/**
 * Time between feeds on one day: the gaps from each feed's start to the next feed's start, counted
 * on the day of the later feed. [avgMs] and [longestMs] are null when there was no gap.
 */
data class GapDay(val day: LocalDate, val count: Int, val sumMs: Long, val longestMs: Long?) {
    val avgMs: Long? get() = if (count == 0) null else sumMs / count
}

/** Per-day series for the Stats screen (SPEC §5.8). */
object Series {
    /**
     * [from]..[to] (inclusive, oldest first). [events] are the records to count (already narrowed
     * with [ChartFocus.select]) and must include the day before [from], so a sleep across
     * midnight is split correctly. [focus] also keeps only one side's segments ([ChartFocus.OnSide]),
     * and then only the feeds that used that side. [firstRecordAt] is the child's earliest record
     * of any kind (days before it have no data).
     */
    fun days(
        events: List<Event>,
        segments: List<BreastSegment>,
        from: LocalDate,
        to: LocalDate,
        clock: DayClock,
        now: Long,
        firstRecordAt: Long?,
        focus: ChartFocus = ChartFocus.All,
    ): List<DayPoint> {
        if (to < from) return emptyList()
        val segs = segments.filter { it.deletedAt == null && focus.matches(it) }
        val segsByEvent = segs.groupBy { it.eventId }
        val sidesByEvent = segsByEvent.mapValues { (_, list) -> list.mapTo(HashSet()) { it.side } }
        val live = events.filter { e ->
            e.deletedAt == null && (focus !is ChartFocus.OnSide || focus.side in sidesByEvent[e.id].orEmpty())
        }
        val today = clock.dayOf(now)
        val firstDay = firstRecordAt?.let(clock::dayOf)
        // Only the records that can touch a day: each one by the days it spans.
        val byDay = mutableMapOf<LocalDate, MutableList<Event>>()
        for (e in live) {
            val startDay = clock.dayOf(e.startAt)
            val end = when (e.status) {
                EventStatus.RUNNING -> maxOf(now, e.startAt)
                else -> e.endAt ?: e.startAt
            }
            var d = maxOf(startDay, from)
            val last = minOf(clock.dayOf(end), to)
            while (d <= last) {
                byDay.getOrPut(d) { mutableListOf() } += e
                d = d.plusDays(1)
            }
        }
        val out = mutableListOf<DayPoint>()
        var day = from
        while (day <= to) {
            val es = byDay[day].orEmpty()
            val started = es.filter { clock.dayOf(it.startAt) == day && it.kind == Kind.BREAST }
            out += DayPoint(
                day = day,
                // Only the day's own records' segments: a 90-day chart needn't regroup all of them daily.
                totals = DaySummary.totals(es, es.flatMap { segsByEvent[it.id].orEmpty() }, day, clock, now),
                partial = day >= today,
                beforeFirst = firstDay == null || day < firstDay,
                feedsBySide = Side.entries.associateWith { side -> started.count { side in sidesByEvent[it.id].orEmpty() } },
            )
            day = day.plusDays(1)
        }
        return out
    }

    /** The average of [metric] per full day, or null when no full day is in range. */
    fun average(points: List<DayPoint>, metric: Metric): Double? {
        val full = points.filter { it.full }
        return if (full.isEmpty()) null else full.sumOf { metric.total(it) } / full.size
    }

    /** Left and right breast time over [points], full days and today alike. */
    fun sideShare(points: List<DayPoint>): BreastTotals =
        BreastTotals(points.sumOf { it.totals.breastLeftMs }, points.sumOf { it.totals.breastRightMs })

    /** Feeds and sleeps whose end is unknown in [points] (they count, but add no time). */
    fun withoutEnd(points: List<DayPoint>): Int = points.filter { !it.beforeFirst }.sumOf { it.totals.feedsWithoutEnd + it.totals.sleepsWithoutEnd }

    /**
     * Time between feeds, start to start (breast and bottle, never supplements: SPEC §5.3), for
     * [from]..[to]. Each gap counts on the day of the later feed, so a gap across midnight belongs
     * to the morning. [events] must include the feed before [from] for its first gap to count.
     */
    fun feedGaps(events: List<Event>, from: LocalDate, to: LocalDate, clock: DayClock): List<GapDay> {
        val starts = events.filter { it.deletedAt == null && it.kind.isFeed }.map { it.startAt }.sorted()
        val gaps = mutableMapOf<LocalDate, MutableList<Long>>()
        for (i in 1 until starts.size) {
            val day = clock.dayOf(starts[i])
            if (day < from || day > to) continue
            gaps.getOrPut(day) { mutableListOf() } += starts[i] - starts[i - 1]
        }
        val out = mutableListOf<GapDay>()
        var day = from
        while (day <= to) {
            val g = gaps[day].orEmpty()
            out += GapDay(day, g.size, g.sum(), g.maxOrNull())
            day = day.plusDays(1)
        }
        return out
    }

    /** The average gap over the full days of [days] (every gap weighs the same), or null. */
    fun averageGap(days: List<GapDay>, full: (LocalDate) -> Boolean): Long? {
        val counted = days.filter { full(it.day) }
        val n = counted.sumOf { it.count }
        return if (n == 0) null else counted.sumOf { it.sumMs } / n
    }
}
