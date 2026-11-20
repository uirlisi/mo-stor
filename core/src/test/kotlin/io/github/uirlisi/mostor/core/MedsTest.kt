package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC §5.10: med status, shown only. Days are app days; the walk-through is the handoff's. */
class MedsTest {
    // Tuesday 10 November 2026; Saturday is the 14th.
    private val tue = LocalDate.of(2026, 11, 10)
    private val sat = LocalDate.of(2026, 11, 14)

    private fun med(schedule: MedSchedule, weekday: Int? = null, every: Int? = null, start: LocalDate? = tue, id: String = "m") =
        SupplementPreset(
            id, "c1", "Vit K", schedule = schedule, scheduleWeekday = weekday, scheduleEveryDays = every,
            scheduleStart = start?.toString(), createdAt = 0, updatedAt = 0, hlc = "h",
        )

    private fun dose(day: LocalDate, hour: Int = 9, skipped: Boolean = false) = Dose(day.toEpochDay() * DAY + hour * HOUR, day, skipped)

    private val weeklySat = med(MedSchedule.WEEKLY, weekday = 6)

    @Test
    fun `the weekly Vit K walk-through`() {
        // Next: Sat → Due on Saturday → 1 day late on Sunday → ✓ + time once given → Next: Sat.
        assertEquals(MedStatus.NotItsDay(sat), Meds.status(weeklySat, emptyList(), tue.plusDays(1)))
        assertEquals(MedStatus.Due, Meds.status(weeklySat, emptyList(), sat))
        assertEquals(MedStatus.Late(1), Meds.status(weeklySat, emptyList(), sat.plusDays(1)))
        val sunday = dose(sat.plusDays(1), hour = 10)
        assertEquals(MedStatus.GivenToday(sunday.at, 1), Meds.status(weeklySat, listOf(sunday), sat.plusDays(1)))
        // The late dose covered the missed Saturday, so the next one is due as usual.
        assertEquals(MedStatus.NotItsDay(sat.plusDays(7)), Meds.status(weeklySat, listOf(sunday), sat.plusDays(2)))
        // On that past Saturday, with no dose, Today's row says Missed.
        val past = Meds.day(listOf(weeklySat), mapOf("m" to listOf(sunday)), sat, isToday = false)
        assertEquals(listOf(MedDayLine.Kind.MISSED), past.lines.map { it.kind })
        assertEquals(0 to 1, past.given to past.listed)
    }

    @Test
    fun `a weekly skip covers its week, and the next week is due as usual`() {
        val skip = dose(sat, skipped = true)
        assertEquals(MedStatus.Skipped(sat), Meds.status(weeklySat, listOf(skip), sat))
        // Not late on Sunday: the skip covered Saturday.
        assertEquals(MedStatus.NotItsDay(sat.plusDays(7)), Meds.status(weeklySat, listOf(skip), sat.plusDays(1)))
        assertEquals(MedStatus.Due, Meds.status(weeklySat, listOf(skip), sat.plusDays(7)))
    }

    @Test
    fun `a dose early in the week counts for that week's day`() {
        // Set up on Sunday the 15th, so nothing was missed before.
        val weeklySat = med(MedSchedule.WEEKLY, weekday = 6, start = sat.plusDays(1))
        val mon = dose(LocalDate.of(2026, 11, 16))
        val nextSat = sat.plusDays(7)
        assertEquals(MedStatus.GivenThisCycle(mon.day), Meds.status(weeklySat, listOf(mon), mon.day.plusDays(1)))
        assertEquals(MedStatus.GivenThisCycle(mon.day), Meds.status(weeklySat, listOf(mon), nextSat))
        assertEquals(MedStatus.NotItsDay(nextSat.plusDays(7)), Meds.status(weeklySat, listOf(mon), nextSat.plusDays(1)))
        // On its Saturday it is listed as given; on the other days it isn't listed.
        assertEquals(listOf(MedDayLine.Kind.GIVEN), Meds.day(listOf(weeklySat), mapOf("m" to listOf(mon)), nextSat, true).lines.map { it.kind })
        assertEquals(emptyList<MedDayLine>(), Meds.day(listOf(weeklySat), mapOf("m" to listOf(mon)), mon.day.plusDays(1), true).lines)
    }

    @Test
    fun `several cycles late, one dose catches up to the most recent missed day`() {
        val today = sat.plusDays(9) // two Saturdays missed
        assertEquals(MedStatus.Late(9), Meds.status(weeklySat, emptyList(), today))
        val d = dose(today)
        assertEquals(MedStatus.NotItsDay(sat.plusDays(14)), Meds.status(weeklySat, listOf(d), today.plusDays(1)))
    }

    @Test
    fun `daily is never late, and a skip covers the day without counting as given`() {
        val daily = med(MedSchedule.DAILY)
        assertEquals(MedStatus.Due, Meds.status(daily, emptyList(), tue))
        assertEquals(MedStatus.Due, Meds.status(daily, listOf(dose(tue)), tue.plusDays(3)))
        val given = dose(tue.plusDays(1))
        assertEquals(MedStatus.GivenToday(given.at, 1), Meds.status(daily, listOf(given), given.day))
        val skip = dose(tue.plusDays(2), skipped = true)
        assertEquals(MedStatus.Skipped(skip.day), Meds.status(daily, listOf(skip), skip.day))
        assertEquals(MedStatus.Due, Meds.status(daily, listOf(skip), skip.day.plusDays(1)))
    }

    @Test
    fun `every N days counts from the start, early and late doses included`() {
        val every3 = med(MedSchedule.EVERY_N_DAYS, every = 3) // Tue 10, Fri 13, Mon 16…
        assertEquals(MedStatus.Due, Meds.status(every3, emptyList(), tue))
        assertEquals(MedStatus.Late(1), Meds.status(every3, emptyList(), tue.plusDays(1)))
        val late = dose(tue.plusDays(1))
        assertEquals(MedStatus.NotItsDay(tue.plusDays(3)), Meds.status(every3, listOf(late), tue.plusDays(2)))
        val early = dose(tue.plusDays(2))
        assertEquals(MedStatus.GivenThisCycle(early.day), Meds.status(every3, listOf(late, early), tue.plusDays(3)))
        assertEquals(MedStatus.NotItsDay(tue.plusDays(6)), Meds.status(every3, listOf(late, early), tue.plusDays(4)))
    }

    @Test
    fun `doses before the schedule started don't count, and a future start is not due yet`() {
        val before = dose(tue.minusDays(1))
        assertEquals(MedStatus.Due, Meds.status(weeklySat, listOf(before), sat))
        val later = med(MedSchedule.DAILY, start = sat)
        assertEquals(MedStatus.NotItsDay(sat), Meds.status(later, emptyList(), tue))
    }

    @Test
    fun `as needed shows the last dose and today's count, or nothing`() {
        val asNeeded = med(MedSchedule.AS_NEEDED, start = null)
        assertEquals(MedStatus.NeverGiven, Meds.status(asNeeded, emptyList(), tue))
        val a = dose(tue, hour = 9)
        val b = dose(tue, hour = 14)
        assertEquals(MedStatus.AsNeededGiven(b.at, tue, 2), Meds.status(asNeeded, listOf(a, b), tue))
        assertEquals(MedStatus.AsNeededGiven(b.at, tue, 0), Meds.status(asNeeded, listOf(a, b), tue.plusDays(1)))
        // Skips never count as given.
        assertEquals(MedStatus.NeverGiven, Meds.status(asNeeded, listOf(dose(tue, skipped = true)), tue))
    }

    @Test
    fun `today's row counts scheduled meds, leaves skips out of the total, and lists as-needed doses after`() {
        val vitD = med(MedSchedule.DAILY, id = "d")
        val iron = med(MedSchedule.DAILY, id = "i")
        val vitK = med(MedSchedule.WEEKLY, weekday = 6, id = "k")
        val drops = med(MedSchedule.AS_NEEDED, start = null, id = "g")
        val doses = mapOf(
            "d" to listOf(dose(sat)),
            "i" to listOf(dose(sat, skipped = true)),
            "g" to listOf(dose(sat, 8), dose(sat, 12)),
        )
        val row = Meds.day(listOf(vitD, iron, vitK, drops), doses, sat, isToday = true)
        assertEquals(
            listOf("d" to MedDayLine.Kind.GIVEN, "i" to MedDayLine.Kind.SKIPPED, "k" to MedDayLine.Kind.DUE, "g" to MedDayLine.Kind.AS_NEEDED),
            row.lines.map { it.preset.id to it.kind },
        )
        assertEquals(2, row.lines.last().count)
        // Vit D given, Vit K due; Iron skipped is left out: 1/2.
        assertEquals(1 to 2, row.given to row.listed)
        // A late weekly med is listed on Today with its days.
        val late = Meds.day(listOf(vitK), emptyMap(), sat.plusDays(3), isToday = true).lines.single()
        assertEquals(MedDayLine.Kind.LATE to 3, late.kind to late.lateDays)
        // On a past day, nothing is late: off its day it isn't listed.
        assertEquals(emptyList<MedDayLine>(), Meds.day(listOf(vitK), emptyMap(), sat.plusDays(3), isToday = false).lines)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
