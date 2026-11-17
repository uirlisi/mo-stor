package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Days that start at [startHour] local time in [zone] (SPEC §5.1). A day is the half-open range
 * [startOf(day), startOf(day + 1)), so DST days are 23 or 25 hours long. If the start hour doesn't
 * exist on a DST day, the day starts at the first moment after the gap.
 */
class DayClock(val startHour: Int, val zone: ZoneId) {
    init {
        require(startHour in 0..23) { "start hour must be 0–23" }
    }

    fun startOf(day: LocalDate): Long = day.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()

    fun endOf(day: LocalDate): Long = startOf(day.plusDays(1))

    /** The day [ms] belongs to. */
    fun dayOf(ms: Long): LocalDate {
        val local = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return if (ms < startOf(local)) local.minusDays(1) else local
    }

    /** When the day containing [ms] ends (the next day start). */
    fun nextDayStart(ms: Long): Long = endOf(dayOf(ms))

    data class Piece(val day: LocalDate, val startMs: Long, val endMs: Long)

    /** Splits [startMs, endMs) at day boundaries, for per-day totals. Empty if endMs ≤ startMs. */
    fun split(startMs: Long, endMs: Long): List<Piece> {
        if (endMs <= startMs) return emptyList()
        val out = mutableListOf<Piece>()
        var day = dayOf(startMs)
        var from = startMs
        while (from < endMs) {
            val to = minOf(endOf(day), endMs)
            out += Piece(day, from, to)
            from = to
            day = day.plusDays(1)
        }
        return out
    }
}
