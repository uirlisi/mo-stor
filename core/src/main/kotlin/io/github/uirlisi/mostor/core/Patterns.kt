package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Clock minutes [from, to) since a day's start hour, 0–1440. */
data class MinuteRange(val from: Int, val to: Int)

/**
 * A drawn interval in a day row: a sleep, or one breast segment ([side] set). [fromPrevious]: it
 * began on an earlier day (drawn from the left edge). [toNext]: it goes on into the next day.
 * [running]: it ends at now because the timer is still running.
 */
data class PatternBar(
    val eventId: String,
    val kind: Kind,
    val side: Side?,
    val fromMin: Int,
    val toMin: Int,
    val fromPrevious: Boolean = false,
    val toNext: Boolean = false,
    val running: Boolean = false,
)

/**
 * A point in a day row: a diaper, bottle, supplement or pump, or the start of a timer whose end
 * is unknown ([unknownEnd]), which is never drawn as a guessed bar.
 */
data class PatternMark(
    val eventId: String,
    val kind: Kind,
    val subtype: String?,
    val atMin: Int,
    val unknownEnd: Boolean = false,
)

/**
 * One row of the pattern grid. Columns are **clock time** since the day-start hour, so 14:00 is
 * always the same column. [hours] is the real length (23 or 25 on DST days). [missing] is clock
 * time that didn't exist (spring forward); [repeated] is clock time that happened twice (fall
 * back), whose two passes are drawn in the same columns.
 */
data class PatternDay(
    val day: LocalDate,
    val hours: Int,
    val bars: List<PatternBar>,
    val marks: List<PatternMark>,
    val missing: List<MinuteRange> = emptyList(),
    val repeated: List<MinuteRange> = emptyList(),
)

/** The pattern grid (SPEC §5.8): for each day, intervals and point marks as minutes since day start. */
object Patterns {
    const val DAY_MINUTES = 24 * 60

    /**
     * Rows for [from]..[to] (inclusive, oldest first). [events] should already be narrowed with
     * [ChartFocus.select]; [focus] also picks breast segments by side. Notes aren't drawn. Records
     * that started before [from] (a sleep across midnight) must be included to be drawn.
     */
    fun grid(
        events: List<Event>,
        segments: List<BreastSegment>,
        from: LocalDate,
        to: LocalDate,
        clock: DayClock,
        now: Long,
        focus: ChartFocus = ChartFocus.All,
    ): List<PatternDay> {
        if (to < from) return emptyList()
        val rangeStart = clock.startOf(from)
        val rangeEnd = clock.endOf(to)
        val bars = mutableMapOf<LocalDate, MutableList<PatternBar>>()
        val marks = mutableMapOf<LocalDate, MutableList<PatternMark>>()
        val segsByEvent = segments.filter { it.deletedAt == null }.groupBy { it.eventId }

        fun mark(e: Event, at: Long, unknownEnd: Boolean = false) {
            if (at < rangeStart || at >= rangeEnd) return
            val day = clock.dayOf(at)
            marks.getOrPut(day) { mutableListOf() } += PatternMark(e.id, e.kind, e.subtype, minutes(clock, day, at), unknownEnd)
        }

        fun interval(e: Event, side: Side?, start: Long, end: Long, running: Boolean) {
            if (end <= rangeStart || start >= rangeEnd) return
            for (piece in clock.split(start, end)) {
                if (piece.day < from || piece.day > to) continue
                val fromPrevious = piece.startMs > start
                val toNext = piece.endMs < end
                val parts = splitAtTransitions(clock, piece.startMs, piece.endMs)
                parts.forEachIndexed { i, (a, b) ->
                    bars.getOrPut(piece.day) { mutableListOf() } += PatternBar(
                        eventId = e.id,
                        kind = e.kind,
                        side = side,
                        fromMin = minutes(clock, piece.day, a),
                        toMin = minutesEnd(clock, piece.day, b),
                        fromPrevious = fromPrevious && i == 0,
                        toNext = toNext && i == parts.lastIndex,
                        running = running && !toNext && i == parts.lastIndex,
                    )
                }
            }
        }

        for (e in events) {
            if (e.deletedAt != null) continue
            when (e.kind) {
                Kind.SLEEP -> when (e.status) {
                    EventStatus.RUNNING -> if (now > e.startAt) interval(e, null, e.startAt, now, running = true)
                    EventStatus.ENDED -> interval(e, null, e.startAt, e.endAt ?: e.startAt, running = false)
                    EventStatus.END_UNKNOWN, EventStatus.SKIPPED, null -> mark(e, e.startAt, unknownEnd = true)
                }
                Kind.BREAST -> {
                    val segs = segsByEvent[e.id].orEmpty().sortedBy { it.startAt }
                    if (segs.isEmpty() && e.status == EventStatus.END_UNKNOWN) mark(e, e.startAt, unknownEnd = true)
                    for (s in segs) {
                        if (!focus.matches(s)) continue
                        val end = s.endAt
                        when {
                            end != null -> interval(e, s.side, s.startAt, end, running = false)
                            e.status == EventStatus.RUNNING -> if (now > s.startAt) interval(e, s.side, s.startAt, now, running = true)
                            // An open segment of an event whose end is unknown: its end is unknown too.
                            else -> mark(e, s.startAt, unknownEnd = true)
                        }
                    }
                }
                // A skipped dose wasn't given: no mark (SPEC §5.10).
                Kind.SUPPLEMENT -> if (e.status != EventStatus.SKIPPED) mark(e, e.startAt)
                Kind.BOTTLE, Kind.DIAPER, Kind.PUMP -> mark(e, e.startAt)
                Kind.NOTE -> Unit
            }
        }

        val out = mutableListOf<PatternDay>()
        var day = from
        while (day <= to) {
            val (missing, repeated) = dstRanges(clock, day)
            out += PatternDay(
                day = day,
                hours = ((clock.endOf(day) - clock.startOf(day) + HALF_HOUR) / HOUR_MS).toInt(),
                bars = bars[day].orEmpty().sortedWith(compareBy({ it.fromMin }, { it.toMin })),
                marks = marks[day].orEmpty().sortedBy { it.atMin },
                missing = missing,
                repeated = repeated,
            )
            day = day.plusDays(1)
        }
        return out
    }

    /** Clock minutes from [day]'s start hour to [ms], 0–1440. */
    fun minutes(clock: DayClock, day: LocalDate, ms: Long): Int {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), clock.zone)
        return wall(clock, day, local)
    }

    /**
     * As [minutes], for the (exclusive) end of an interval: mapped from the instant just before it,
     * so a part ending at a DST transition ends at the clock time before the jump. The day's end
     * is 1440 even if its clock time doesn't exist.
     */
    private fun minutesEnd(clock: DayClock, day: LocalDate, ms: Long): Int {
        if (ms >= clock.endOf(day)) return DAY_MINUTES
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms - 1), clock.zone)
        val millis = ChronoUnit.MILLIS.between(day.atTime(clock.startHour, 0), local) + 1
        return (millis / 60_000).toInt().coerceIn(0, DAY_MINUTES)
    }

    private fun wall(clock: DayClock, day: LocalDate, local: LocalDateTime): Int {
        val origin = day.atTime(clock.startHour, 0)
        return ChronoUnit.MINUTES.between(origin, local).toInt().coerceIn(0, DAY_MINUTES)
    }

    /**
     * Splits [a, b) at the zone's offset changes, so each part maps to clock time monotonically
     * (a sleep across the fall-back hour would otherwise end "before" it started).
     */
    private fun splitAtTransitions(clock: DayClock, a: Long, b: Long): List<Pair<Long, Long>> {
        val rules = clock.zone.rules
        val out = mutableListOf<Pair<Long, Long>>()
        var from = a
        while (true) {
            val t = rules.nextTransition(Instant.ofEpochMilli(from))?.instant?.toEpochMilli()
            if (t == null || t >= b) break
            if (t > from) out += from to t
            from = t
        }
        if (b > from) out += from to b
        return out
    }

    /** The clock time that didn't exist ([PatternDay.missing]) or happened twice ([PatternDay.repeated]) on [day]. */
    private fun dstRanges(clock: DayClock, day: LocalDate): Pair<List<MinuteRange>, List<MinuteRange>> {
        val rules = clock.zone.rules
        val start = clock.startOf(day)
        val end = clock.endOf(day)
        val missing = mutableListOf<MinuteRange>()
        val repeated = mutableListOf<MinuteRange>()
        // From a little before the start to the end inclusive: a start hour inside a gap moves a
        // day's start (or end) onto the transition. Clock times outside the row clamp to nothing.
        var t = rules.nextTransition(Instant.ofEpochMilli(start - HOUR_MS * 3))
        while (t != null && t.instant.toEpochMilli() <= end) {
            val a = wall(clock, day, t.dateTimeBefore)
            val b = wall(clock, day, t.dateTimeAfter)
            if (t.isGap && b > a) missing += MinuteRange(a, b)
            if (t.isOverlap && a > b) repeated += MinuteRange(b, a)
            t = rules.nextTransition(t.instant)
        }
        return missing to repeated
    }

    private const val HOUR_MS = 3_600_000L
    private const val HALF_HOUR = HOUR_MS / 2
}
