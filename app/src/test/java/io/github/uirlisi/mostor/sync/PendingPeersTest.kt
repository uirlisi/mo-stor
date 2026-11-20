package io.github.uirlisi.mostor.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sync round ends once every phone has answered, synced or refused (SPEC §9.6). */
class PendingPeersTest {
    @Test
    fun `the round ends when the last phone answers, and a stranger or a repeat changes nothing`() {
        val p = PendingPeers(listOf("b", "c"))
        assertFalse(p.isDone)
        assertFalse(p.answered("x"))
        assertFalse(p.answered("b"))
        assertFalse(p.answered("b"))
        assertEquals(setOf("c"), p.left())
        assertTrue(p.answered("c"))
        assertTrue(p.isDone)
        assertTrue(p.left().isEmpty())
    }

    @Test
    fun `with no other phone there is nothing to wait for`() {
        assertTrue(PendingPeers(emptyList()).isDone)
    }
}
