package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ElapsedTest {
    @Test
    fun `zero is zero`() = assertEquals(Elapsed(0, 0), Elapsed.of(0))

    @Test
    fun `rounds down to the minute`() = assertEquals(Elapsed(0, 1), Elapsed.of(119_999))

    @Test
    fun `splits hours and minutes`() = assertEquals(Elapsed(2, 40), Elapsed.of((2 * 60 + 40) * 60_000L + 5_000))

    @Test
    fun `negative counts as zero`() = assertEquals(Elapsed(0, 0), Elapsed.of(-5_000))

    @Test
    fun `long durations keep counting hours`() = assertEquals(Elapsed(30, 0), Elapsed.of(30 * 3_600_000L))
}
