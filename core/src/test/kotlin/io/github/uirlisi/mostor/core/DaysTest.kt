package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DaysTest {
    private val midnight = DayClock(0, DUBLIN)
    private val sixAm = DayClock(6, DUBLIN)

    @Test
    fun `a day starts at the configured hour`() {
        assertEquals(dublin(2026, 11, 10, 6), sixAm.startOf(LocalDate.of(2026, 11, 10)))
        assertEquals(LocalDate.of(2026, 11, 9), sixAm.dayOf(dublin(2026, 11, 10, 5, 59)))
        assertEquals(LocalDate.of(2026, 11, 10), sixAm.dayOf(dublin(2026, 11, 10, 6, 0)))
        assertEquals(LocalDate.of(2026, 11, 10), midnight.dayOf(dublin(2026, 11, 10, 0, 0)))
        assertEquals(LocalDate.of(2026, 11, 9), midnight.dayOf(dublin(2026, 11, 10, 0, 0) - 1))
    }

    @Test
    fun `spring-forward day is 23 hours and fall-back day is 25`() {
        val spring = LocalDate.of(2026, 3, 29)
        val autumn = LocalDate.of(2026, 10, 25)
        assertEquals(23 * HOUR, midnight.endOf(spring) - midnight.startOf(spring))
        assertEquals(25 * HOUR, midnight.endOf(autumn) - midnight.startOf(autumn))
    }

    @Test
    fun `a start hour inside the DST gap starts the day just after the gap`() {
        // Dublin skips 01:00–02:00 on 2026-03-29.
        val oneAm = DayClock(1, DUBLIN)
        val day = LocalDate.of(2026, 3, 29)
        assertEquals(dublin(2026, 3, 29, 2), oneAm.startOf(day))
        assertEquals(LocalDate.of(2026, 3, 28), oneAm.dayOf(oneAm.startOf(day) - 1))
        assertEquals(day, oneAm.dayOf(oneAm.startOf(day)))
    }

    @Test
    fun `next day start`() {
        assertEquals(dublin(2026, 11, 11, 6), sixAm.nextDayStart(dublin(2026, 11, 10, 23)))
        assertEquals(dublin(2026, 11, 10, 6), sixAm.nextDayStart(dublin(2026, 11, 10, 3)))
    }

    @Test
    fun `an interval crossing day boundaries is split`() {
        val pieces = midnight.split(dublin(2026, 11, 9, 22), dublin(2026, 11, 11, 1))
        assertEquals(
            listOf(
                DayClock.Piece(LocalDate.of(2026, 11, 9), dublin(2026, 11, 9, 22), dublin(2026, 11, 10, 0)),
                DayClock.Piece(LocalDate.of(2026, 11, 10), dublin(2026, 11, 10, 0), dublin(2026, 11, 11, 0)),
                DayClock.Piece(LocalDate.of(2026, 11, 11), dublin(2026, 11, 11, 0), dublin(2026, 11, 11, 1)),
            ),
            pieces,
        )
    }

    @Test
    fun `an interval inside one day is one piece, and an empty one is none`() {
        assertEquals(1, midnight.split(dublin(2026, 11, 10, 1), dublin(2026, 11, 10, 2)).size)
        assertEquals(0, midnight.split(dublin(2026, 11, 10, 2), dublin(2026, 11, 10, 2)).size)
    }

    @Test
    fun `a split across the fall-back night keeps every millisecond`() {
        val from = dublin(2026, 10, 24, 23)
        val to = dublin(2026, 10, 25, 3)
        val pieces = midnight.split(from, to)
        assertEquals(to - from, pieces.sumOf { it.endMs - it.startMs })
        assertEquals(2, pieces.size)
    }

    @Test
    fun `start hour must be valid`() {
        assertThrows<IllegalArgumentException> { DayClock(24, DUBLIN) }
    }
}
