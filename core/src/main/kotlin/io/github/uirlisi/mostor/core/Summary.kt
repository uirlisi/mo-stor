package io.github.uirlisi.mostor.core

import java.time.LocalDate

/**
 * The counts the notification and the quick sheet show for a day. A mixed diaper counts as both
 * wet and poop; a dry one as neither.
 */
data class DayCounts(val feeds: Int, val wet: Int, val poop: Int, val diapers: Int) {
    companion object {
        val ZERO = DayCounts(0, 0, 0, 0)
    }
}

/** A supplement given on the day: [name] as recorded (the preset snapshot), how often. */
data class SupplementCount(val presetId: String?, val name: String?, val count: Int, val lastAt: Long)

/** Bottles of one content on the day. */
data class BottleTotal(val count: Int, val ml: Double)

/**
 * The day summary for one child (SPEC §5.8). Counts go by the day a record **started**. Durations
 * are split at day boundaries (SPEC §5.1): a sleep from 22:00 to 06:00 adds 2 h to one day and 6 h
 * to the next, and a running timer counts up to now. Records with an unknown end are counted but
 * left out of durations ([feedsWithoutEnd], [sleepsWithoutEnd]). [longestSleepMs] is the longest
 * whole sleep that started on the day.
 */
data class DayTotals(
    val feeds: Int,
    val breastFeeds: Int,
    val breastLeftMs: Long,
    val breastRightMs: Long,
    val feedsWithoutEnd: Int,
    val bottles: Map<BottleContent, BottleTotal>,
    val supplements: List<SupplementCount>,
    val sleeps: Int,
    val sleepMs: Long,
    val longestSleepMs: Long,
    val sleepsWithoutEnd: Int,
    val diapers: Map<Diaper, Int>,
    val wet: Int,
    val poop: Int,
    val pumps: Int,
    val pumpMl: Double,
    val notes: Int,
) {
    val breastMs: Long get() = breastLeftMs + breastRightMs
}

object DaySummary {
    /**
     * The full summary of [day] (SPEC §5.8). [events] must include records that started the day
     * before (a sleep across midnight), and [segments] the breast segments of those events. Only
     * live records count. Visibility is the caller's choice: pass the records to count.
     */
    fun totals(events: List<Event>, segments: List<BreastSegment>, day: java.time.LocalDate, clock: DayClock, now: Long): DayTotals {
        val from = clock.startOf(day)
        val to = clock.endOf(day)
        fun overlap(a: Long, b: Long): Long = (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0)
        val live = events.filter { it.deletedAt == null }
        val startedToday = live.filter { it.startAt in from until to }
        val segsByEvent = segments.filter { it.deletedAt == null }.groupBy { it.eventId }

        var left = 0L
        var right = 0L
        var sleepMs = 0L
        for (e in live) {
            when {
                e.kind == Kind.BREAST && e.status != EventStatus.END_UNKNOWN -> for (s in segsByEvent[e.id].orEmpty()) {
                    val end = s.endAt ?: if (e.status == EventStatus.RUNNING) now else continue
                    val ms = overlap(s.startAt, end)
                    if (s.side == Side.LEFT) left += ms else right += ms
                }
                e.kind == Kind.SLEEP && e.status != EventStatus.END_UNKNOWN -> {
                    val end = e.endAt ?: if (e.status == EventStatus.RUNNING) now else continue
                    sleepMs += overlap(e.startAt, end)
                }
            }
        }

        val sleeps = startedToday.filter { it.kind == Kind.SLEEP }
        val longest = sleeps.mapNotNull { s ->
            when (s.status) {
                EventStatus.ENDED -> s.endAt?.minus(s.startAt)
                EventStatus.RUNNING -> now - s.startAt
                else -> null
            }
        }.maxOrNull() ?: 0L

        val diapers = startedToday.filter { it.kind == Kind.DIAPER }
            .mapNotNull { Diaper.ofOrNull(it.subtype) }
            .groupingBy { it }.eachCount()
        val c = counts(startedToday, day, clock)
        val bottles = startedToday.filter { it.kind == Kind.BOTTLE }
            .groupBy { BottleContent.ofOrNull(it.subtype) ?: BottleContent.BREAST_MILK }
            .mapValues { (_, es) -> BottleTotal(es.size, es.sumOf { it.amountMl ?: 0.0 }) }
        // Skipped doses were not given (SPEC §5.10).
        val supplements = startedToday.filter { it.kind == Kind.SUPPLEMENT && it.status != EventStatus.SKIPPED }
            .groupBy { it.supplementPresetId ?: it.supplementName }
            .map { (_, es) -> SupplementCount(es.first().supplementPresetId, es.maxBy { it.startAt }.supplementName, es.size, es.maxOf { it.startAt }) }
            .sortedBy { it.lastAt }
        val pumps = startedToday.filter { it.kind == Kind.PUMP }

        return DayTotals(
            feeds = c.feeds,
            breastFeeds = startedToday.count { it.kind == Kind.BREAST },
            breastLeftMs = left,
            breastRightMs = right,
            feedsWithoutEnd = startedToday.count { it.kind == Kind.BREAST && it.status == EventStatus.END_UNKNOWN },
            bottles = bottles,
            supplements = supplements,
            sleeps = sleeps.size,
            sleepMs = sleepMs,
            longestSleepMs = longest,
            sleepsWithoutEnd = sleeps.count { it.status == EventStatus.END_UNKNOWN },
            diapers = diapers,
            wet = c.wet,
            poop = c.poop,
            pumps = pumps.size,
            pumpMl = pumps.sumOf { it.amountMl ?: 0.0 },
            notes = startedToday.count { it.kind == Kind.NOTE },
        )
    }

    /** Counts the live events that started on [day]. */
    fun counts(events: List<Event>, day: LocalDate, clock: DayClock): DayCounts {
        val from = clock.startOf(day)
        val to = clock.endOf(day)
        var feeds = 0
        var wet = 0
        var poop = 0
        var diapers = 0
        for (e in events) {
            if (e.deletedAt != null || e.startAt < from || e.startAt >= to) continue
            when (e.kind) {
                Kind.BREAST, Kind.BOTTLE -> feeds++
                Kind.DIAPER -> {
                    diapers++
                    when (Diaper.ofOrNull(e.subtype)) {
                        Diaper.WET -> wet++
                        Diaper.POOP -> poop++
                        Diaper.MIXED -> {
                            wet++
                            poop++
                        }
                        Diaper.DRY, null -> Unit
                    }
                }
                else -> Unit
            }
        }
        return DayCounts(feeds, wet, poop, diapers)
    }
}
