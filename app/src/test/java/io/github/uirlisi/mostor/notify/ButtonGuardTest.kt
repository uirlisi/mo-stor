package io.github.uirlisi.mostor.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonGuardTest {
    @Test
    fun `a second tap within the window is dropped`() {
        val g = ButtonGuard(windowMs = 1_000)
        assertTrue(g.accept(10_000))
        assertFalse(g.accept(10_150))
        assertFalse(g.accept(10_999))
    }

    @Test
    fun `a tap after the window counts, and the window starts again from it`() {
        val g = ButtonGuard(windowMs = 1_000)
        assertTrue(g.accept(10_000))
        assertTrue(g.accept(11_000))
        assertFalse(g.accept(11_500))
        assertTrue(g.accept(12_000))
    }

    @Test
    fun `dropped taps don't extend the window`() {
        val g = ButtonGuard(windowMs = 1_000)
        assertTrue(g.accept(0))
        assertFalse(g.accept(900))
        assertTrue(g.accept(1_000))
    }

    @Test
    fun `a clock that went backwards doesn't block taps`() {
        val g = ButtonGuard(windowMs = 1_000)
        assertTrue(g.accept(5_000))
        assertTrue(g.accept(100))
    }
}
