package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.LocalDate
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
            instants(date, time, zone).filter { it <= notAfter }.maxOrNull()?.let { return it }
            date = date.minusDays(1)
        }
        error("unreachable")
    }

    /** The earliest moment ≥ [notBefore] whose local clock reads [time]. For setting an unknown end. */
    fun earliestAtOrAfter(time: LocalTime, notBefore: Long, zone: ZoneId): Long {
        var date = Instant.ofEpochMilli(notBefore).atZone(zone).toLocalDate()
        repeat(3) {
            instants(date, time, zone).filter { it >= notBefore }.minOrNull()?.let { return it }
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
        return (-1L..1L).flatMap { instants(date.plusDays(it), time, zone) }.minBy { kotlin.math.abs(it - around) }
    }

    /**
     * The moments on [date] whose clock reads [time]: one on most days; two in the hour the clocks go
     * back (01:10 summer time, then 01:10 winter time); in the hour they skip, the moment of the change
     * itself (a time that never showed on the clock).
     */
    private fun instants(date: LocalDate, time: LocalTime, zone: ZoneId): List<Long> {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return listOfNotNull(zone.rules.getTransition(local)?.instant?.toEpochMilli())
        return offsets.map { local.toInstant(it).toEpochMilli() }
    }

    /** [minutes] before [base]: the −5 / −10 / −15 chips. */
    fun minutesBefore(base: Long, minutes: Int): Long = base - minutes * 60_000L
}
