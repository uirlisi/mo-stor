package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Turns a clock time picked in a time picker ("14:30") into an instant (SPEC §5.4 "or a picked
 * time"). A picker gives no date, so the date is implied by what is being fixed.
 */
object PickedTime {
    /** The latest moment ≤ [notAfter] whose local clock reads [time]. For late starts and stops. */
    fun latestAtOrBefore(time: LocalTime, notAfter: Long, zone: ZoneId): Long {
        var date = Instant.ofEpochMilli(notAfter).atZone(zone).toLocalDate()
        repeat(3) {
            val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (candidate <= notAfter) return candidate
            date = date.minusDays(1)
        }
        error("unreachable")
    }

    /** The earliest moment ≥ [notBefore] whose local clock reads [time]. For setting an unknown end. */
    fun earliestAtOrAfter(time: LocalTime, notBefore: Long, zone: ZoneId): Long {
        var date = Instant.ofEpochMilli(notBefore).atZone(zone).toLocalDate()
        repeat(3) {
            val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (candidate >= notBefore) return candidate
            date = date.plusDays(1)
        }
        error("unreachable")
    }

    /**
     * The moment whose local clock reads [time] nearest to [around] (the day before, the same day
     * or the day after). For editing: a time picked near midnight keeps the record's own date.
     */
    fun nearest(time: LocalTime, around: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(around).atZone(zone).toLocalDate()
        return (-1L..1L).map { date.plusDays(it).atTime(time).atZone(zone).toInstant().toEpochMilli() }
            .minBy { kotlin.math.abs(it - around) }
    }

    /** [minutes] before [base]: the −5 / −10 / −15 chips. */
    fun minutesBefore(base: Long, minutes: Int): Long = base - minutes * 60_000L
}
