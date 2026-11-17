package io.github.uirlisi.mostor.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A dose of a med: when, the app's day it falls on (SPEC §5.1), and whether it was skipped. */
data class Dose(val at: Long, val day: LocalDate, val skipped: Boolean = false)

/** What a med's chip says today (SPEC §5.10). */
sealed interface MedStatus {
    /** A dose today: the latest one's time, and how many today. */
    data class GivenToday(val at: Long, val count: Int) : MedStatus

    /** Weekly or every N: a dose (on [day]) covers a scheduled day that hasn't passed yet. */
    data class GivenThisCycle(val day: LocalDate) : MedStatus

    /** The last cover was a skip (on [day]) and its scheduled day hasn't passed. */
    data class Skipped(val day: LocalDate) : MedStatus

    /** Weekly or every N: covered, and the next scheduled day is [next]. */
    data class NotItsDay(val next: LocalDate) : MedStatus

    data object Due : MedStatus

    /** Weekly or every N: the next scheduled day passed [days] days ago. */
    data class Late(val days: Int) : MedStatus

    /** As needed, given before: the last dose, and how many today. */
    data class AsNeededGiven(val lastAt: Long, val lastDay: LocalDate, val countToday: Int) : MedStatus

    /** As needed, never given. */
    data object NeverGiven : MedStatus
}

/** One line of a day's Meds row on Today. */
data class MedDayLine(val preset: SupplementPreset, val kind: Kind, val lateDays: Int = 0, val count: Int = 0) {
    enum class Kind { GIVEN, SKIPPED, DUE, LATE, MISSED, AS_NEEDED }
}

/** A day's Meds row: its lines in the meds' order, and the total "given / listed" (scheduled items, skips left out). */
data class MedDay(val lines: List<MedDayLine>, val given: Int, val listed: Int)

/**
 * Med schedules, shown only (SPEC §5.10). Nothing here notifies or judges; it decides what the
 * quick sheet's chips and Today's Meds row show.
 *
 * Scheduled days: every day from the start (Daily), the chosen weekday (Weekly), or start + k·N
 * (Every N days). A dose or a skip given on day G covers the first scheduled day on or after G (a
 * dose early in the week counts for that week's day), except when the med is late: then it covers
 * the most recent missed scheduled day. Doses before the schedule's start don't count towards it.
 */
object Meds {
    fun status(p: SupplementPreset, doses: List<Dose>, today: LocalDate): MedStatus {
        val given = doses.filter { !it.skipped }
        val todays = given.filter { it.day == today }
        if (p.schedule == MedSchedule.AS_NEEDED) {
            val last = given.maxByOrNull { it.at } ?: return MedStatus.NeverGiven
            return MedStatus.AsNeededGiven(last.at, last.day, todays.size)
        }
        if (todays.isNotEmpty()) return MedStatus.GivenToday(todays.maxOf { it.at }, todays.size)
        val s = Schedule.of(p, today)
        val w = walk(s, doses.filter { it.day <= today })
        val last = w.last
        if (last != null && w.covered!! >= today) {
            return if (last.skipped) MedStatus.Skipped(last.day) else MedStatus.GivenThisCycle(last.day)
        }
        val next = w.next
        return when {
            today == next -> MedStatus.Due
            today < next -> MedStatus.NotItsDay(next)
            // Daily is never late: a missed day just shows Due again today.
            p.schedule == MedSchedule.DAILY -> MedStatus.Due
            else -> MedStatus.Late(ChronoUnit.DAYS.between(next, today).toInt())
        }
    }

    /**
     * A day's Meds row for one child: [presets] in their order (only the ones shown on this phone),
     * [doses] by preset id. Listed: every Daily med; Weekly and every-N meds due, late, given or
     * skipped that day, or whose scheduled day it is; As needed meds given that day (after, in gray).
     * On a past day a scheduled day with nothing given is "Missed", and nothing is late.
     */
    fun day(presets: List<SupplementPreset>, doses: Map<String, List<Dose>>, day: LocalDate, isToday: Boolean): MedDay {
        val scheduled = mutableListOf<MedDayLine>()
        val asNeeded = mutableListOf<MedDayLine>()
        for (p in presets) {
            val mine = doses[p.id].orEmpty().filter { it.day <= day }
            if (p.schedule == MedSchedule.AS_NEEDED) {
                val n = mine.count { it.day == day && !it.skipped }
                if (n > 0) asNeeded += MedDayLine(p, MedDayLine.Kind.AS_NEEDED, count = n)
                continue
            }
            val s = Schedule.of(p, day)
            val itsDay = s.isDay(day)
            val line = when (val st = status(p, mine, day)) {
                is MedStatus.GivenToday -> MedDayLine.Kind.GIVEN
                is MedStatus.GivenThisCycle -> MedDayLine.Kind.GIVEN.takeIf { itsDay }
                is MedStatus.Skipped -> MedDayLine.Kind.SKIPPED.takeIf { itsDay || st.day == day }
                MedStatus.Due -> if (isToday) MedDayLine.Kind.DUE else MedDayLine.Kind.MISSED
                is MedStatus.Late -> when {
                    isToday -> MedDayLine.Kind.LATE
                    itsDay -> MedDayLine.Kind.MISSED
                    else -> null
                }
                else -> null
            } ?: continue
            val late = (status(p, mine, day) as? MedStatus.Late)?.days ?: 0
            scheduled += MedDayLine(p, line, lateDays = if (line == MedDayLine.Kind.LATE) late else 0)
        }
        val counted = scheduled.filter { it.kind != MedDayLine.Kind.SKIPPED }
        return MedDay(scheduled + asNeeded, counted.count { it.kind == MedDayLine.Kind.GIVEN }, counted.size)
    }

    // ---- The coverage walk ----

    private class Walk(val covered: LocalDate?, val last: Dose?, val next: LocalDate)

    private fun walk(s: Schedule, doses: List<Dose>): Walk {
        var covered: LocalDate? = null
        var last: Dose? = null
        for (d in doses.filter { it.day >= s.start }.sortedBy { it.at }) {
            val due = covered?.let { s.nextAfter(it) } ?: s.nextOnOrAfter(s.start)
            val covers = if (d.day > due) s.prevOnOrBefore(d.day)!! else s.nextOnOrAfter(d.day)
            if (covered == null || covers > covered) {
                covered = covers
                last = d
            } else if (covers == covered) {
                // Another dose for the same day: the latest one says what the day was (given or skipped).
                last = d
            }
        }
        return Walk(covered, last, covered?.let { s.nextAfter(it) } ?: s.nextOnOrAfter(s.start))
    }

    /** A med's scheduled days. */
    private class Schedule(val start: LocalDate, private val weekday: Int?, private val every: Long?) {
        fun isDay(d: LocalDate): Boolean = d >= start && when {
            weekday != null -> d.dayOfWeek.value == weekday
            every != null -> ChronoUnit.DAYS.between(start, d) % every == 0L
            else -> true
        }

        fun nextOnOrAfter(d: LocalDate): LocalDate {
            val from = maxOf(d, start)
            return when {
                weekday != null -> from.plusDays(((weekday - from.dayOfWeek.value + 7) % 7).toLong())
                every != null -> {
                    val k = (ChronoUnit.DAYS.between(start, from) + every - 1) / every
                    start.plusDays(k * every)
                }
                else -> from
            }
        }

        fun nextAfter(d: LocalDate): LocalDate = nextOnOrAfter(d.plusDays(1))

        fun prevOnOrBefore(d: LocalDate): LocalDate? {
            if (d < start) return null
            val day = when {
                weekday != null -> d.minusDays(((d.dayOfWeek.value - weekday + 7) % 7).toLong())
                every != null -> start.plusDays(ChronoUnit.DAYS.between(start, d) / every * every)
                else -> d
            }
            return day.takeIf { it >= start }
        }

        companion object {
            /** A schedule without a start counts from [fallback] (it is always set when saved). */
            fun of(p: SupplementPreset, fallback: LocalDate): Schedule {
                val start = p.scheduleStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: fallback
                return when (p.schedule) {
                    MedSchedule.WEEKLY -> Schedule(start, (p.scheduleWeekday ?: start.dayOfWeek.value).coerceIn(1, 7), null)
                    MedSchedule.EVERY_N_DAYS -> Schedule(start, null, (p.scheduleEveryDays ?: 2).coerceAtLeast(2).toLong())
                    else -> Schedule(start, null, null)
                }
            }
        }
    }
}
