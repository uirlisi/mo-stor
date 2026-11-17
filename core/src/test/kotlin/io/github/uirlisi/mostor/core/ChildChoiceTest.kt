package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The multi-select child picker (Phase 3c): Today's stored selection and the "at least one child" rule. */
class ChildChoiceTest {
    private val active = listOf("a", "b", "c")

    @Test
    fun `nothing stored means the child this phone records for`() {
        assertEquals(listOf("b"), ChildChoice.parseToday(null, active, "b"))
        assertEquals(listOf("b"), ChildChoice.parseToday("", active, "b"))
    }

    @Test
    fun `the old values still parse`() {
        assertEquals(active, ChildChoice.parseToday("all", active, "a"))
        // A single id, as an older build could have stored.
        assertEquals(listOf("c"), ChildChoice.parseToday("c", active, "a"))
    }

    @Test
    fun `a list of ids comes back in the app's child order`() {
        assertEquals(listOf("a", "c"), ChildChoice.parseToday("c,a", active, "b"))
        assertEquals(listOf("a", "b"), ChildChoice.parseToday(" b , a ", active, "c"))
    }

    @Test
    fun `a deleted or archived child is dropped from the list`() {
        // "x" is no longer an active child.
        assertEquals(listOf("a", "c"), ChildChoice.parseToday("a,x,c", active, "b"))
        assertEquals(listOf("c"), ChildChoice.parseToday("x,c", active, "a"))
        // Nobody left: back to the child this phone records for.
        assertEquals(listOf("b"), ChildChoice.parseToday("x,y", active, "b"))
        assertEquals(listOf("b"), ChildChoice.parseToday("x", active, "b"))
    }

    @Test
    fun `with one active child it is always that child`() {
        assertEquals(listOf("a"), ChildChoice.parseToday("all", listOf("a"), "a"))
        assertEquals(listOf("a"), ChildChoice.parseToday("a,b", listOf("a"), "a"))
    }

    @Test
    fun `a new child is in all but not in an older list`() {
        val four = active + "d"
        assertEquals(four, ChildChoice.parseToday("all", four, "a"))
        assertEquals(listOf("a", "b"), ChildChoice.parseToday("a,b", four, "a"))
    }

    @Test
    fun `encoding gives all, a list in child order, or nothing for one child`() {
        assertEquals("all", ChildChoice.encodeToday(listOf("c", "a", "b"), active))
        assertEquals("a,c", ChildChoice.encodeToday(listOf("c", "a"), active))
        assertNull(ChildChoice.encodeToday(listOf("b"), active))
        // Round trip.
        for (chosen in listOf(listOf("a", "b"), listOf("b", "c"), active)) {
            assertEquals(chosen, ChildChoice.parseToday(ChildChoice.encodeToday(chosen, active), active, "a"))
        }
    }

    @Test
    fun `ticking adds and removes, keeping the child order`() {
        assertEquals(listOf("a", "b"), ChildChoice.toggle(listOf("b"), "a", active))
        assertEquals(listOf("a", "b", "c"), ChildChoice.toggle(listOf("c", "a"), "b", active))
        assertEquals(listOf("c"), ChildChoice.toggle(listOf("a", "c"), "a", active))
    }

    @Test
    fun `the last chosen child can't be unticked`() {
        assertFalse(ChildChoice.canUntick(listOf("b"), "b"))
        assertTrue(ChildChoice.canUntick(listOf("a", "b"), "b"))
        // Ticking another child is always allowed.
        assertTrue(ChildChoice.canUntick(listOf("b"), "a"))
        assertEquals(listOf("b"), ChildChoice.toggle(listOf("b"), "b", active))
    }

    @Test
    fun `all children is ticked only when everyone is chosen`() {
        assertTrue(ChildChoice.isAll(active, active))
        assertFalse(ChildChoice.isAll(listOf("a", "c"), active))
    }
}
