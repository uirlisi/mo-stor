package io.github.uirlisi.mostor.core

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `uuid v7 has the version, variant and timestamp`() {
        val ms = 1_793_000_000_123L
        val id = Ids.uuidV7(ms, Random(1))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        assertEquals(ms, Ids.timestampOf(id))
    }

    @Test
    fun `ids from later milliseconds sort later`() {
        val a = Ids.uuidV7(1_000)
        val b = Ids.uuidV7(1_001)
        assertTrue(a < b)
    }
}

class HlcTest {
    @Test
    fun `encode and parse round trip, with an origin containing hyphens`() {
        val s = HlcStamp(1_793_000_000_123L, 7, "0195a3c0-1111-7000-8000-000000000001")
        assertEquals(s, HlcStamp.parse(s.encode()))
    }

    @Test
    fun `encoded stamps sort like the stamps`() {
        val stamps = listOf(
            HlcStamp(5, 0, "b"), HlcStamp(5, 1, "a"), HlcStamp(4, 0xFFFF, "z"),
            HlcStamp(0x10, 0, "a"), HlcStamp(5, 0, "a"), HlcStamp(0xFFF, 2, "a"),
        )
        assertEquals(stamps.sorted().map { it.encode() }, stamps.map { it.encode() }.sorted())
    }

    @Test
    fun `local stamps strictly increase even when the wall clock stands still or goes back`() {
        var wall = 1_000L
        val clock = HybridClock("a", null) { wall }
        val first = clock.now()
        val second = clock.now()
        wall = 500
        val third = clock.now()
        assertTrue(first < second && second < third)
        assertEquals(HlcStamp(1_000, 2, "a"), third)
        wall = 2_000
        assertEquals(HlcStamp(2_000, 0, "a"), clock.now())
    }

    @Test
    fun `counter overflow moves the wall time on`() {
        val clock = HybridClock("a", HlcStamp(1_000, HlcStamp.MAX_COUNTER, "a")) { 1_000 }
        assertEquals(HlcStamp(1_001, 0, "a"), clock.now())
    }

    @Test
    fun `receiving a stamp from the future makes later local stamps sort after it`() {
        val clock = HybridClock("a", null) { 1_000 }
        val remote = HlcStamp(9_000, 3, "b")
        val received = clock.receive(remote)
        assertTrue(received > remote)
        assertTrue(clock.now() > received)
    }

    @Test
    fun `a stamp more than a day ahead doesn't move the clock, even one at the encoding's limit`() {
        val clock = HybridClock("a", null) { 1_000 }
        clock.receive(HlcStamp(1_000 + HybridClock.MAX_AHEAD_MS + 1, 0, "b"))
        assertTrue(clock.now().wallMs < 1_000 + HybridClock.MAX_AHEAD_MS)
        clock.receive(HlcStamp.parse("ffffffffffff-ffff-evil"))
        val after = clock.now()
        assertTrue(after.wallMs < 10_000)
        // A day ahead still counts (a phone whose clock runs a little fast).
        clock.receive(HlcStamp(1_000 + HybridClock.MAX_AHEAD_MS, 0, "b"))
        assertEquals(1_000 + HybridClock.MAX_AHEAD_MS, clock.now().wallMs)
    }

    @Test
    fun `receiving an old stamp keeps the local clock`() {
        val clock = HybridClock("a", null) { 5_000 }
        assertEquals(HlcStamp(5_000, 0, "a"), clock.receive(HlcStamp(1_000, 9, "b")))
    }

    @Test
    fun `bad stamps are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { HlcStamp.parse("nonsense") }
        assertThrows(IllegalArgumentException::class.java) { HlcStamp(-1, 0, "a") }
    }
}
