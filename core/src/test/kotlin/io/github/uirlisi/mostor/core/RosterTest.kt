package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The household's list of phones (SPEC §9.4): last writer wins per phone, and removals spread. */
class RosterTest {
    private fun stamp(ms: Long, origin: String) = HlcStamp(ms, 0, origin).encode()

    private fun m(id: String, hlc: String, removedAt: Long? = null, by: String? = null, addedAt: Long = 1) =
        Member(id, "Phone $id", addedBy = by, addedAt = addedAt, removedAt = removedAt, removedBy = removedAt?.let { "x" }, hlc = hlc)

    @Test
    fun `a removal beats an older add, and a newer add beats the removal`() {
        val added = m("c", stamp(100, "a"), by = "a")
        val removed = m("c", stamp(200, "b"), removedAt = 200)
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(added), listOf(removed)), "c"))
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(removed), listOf(added)), "c"))
        val again = m("c", stamp(300, "a"), by = "a", addedAt = 300)
        assertEquals(Standing.ACTIVE, Roster.standing(Roster.merge(listOf(removed), listOf(again)), "c"))
        assertEquals(Standing.ACTIVE, Roster.standing(Roster.merge(listOf(again), listOf(removed)), "c"))
        assertEquals(Standing.UNKNOWN, Roster.standing(listOf(added), "d"))
    }

    @Test
    fun `a removed phone renaming itself before it hears of the removal stays removed`() {
        val removed = m("c", stamp(200, "a"), removedAt = 200)
        // C's own row, renamed later (a newer stamp) but from before the removal (the old added time).
        val renamed = m("c", stamp(500, "c")).copy(name = "C's new name")
        for (merged in listOf(Roster.merge(listOf(removed), listOf(renamed)), Roster.merge(listOf(renamed), listOf(removed)))) {
            assertEquals(Standing.REMOVED, Roster.standing(merged, "c"))
        }
        assertEquals(listOf(removed), Roster.changes(listOf(renamed), listOf(removed)))
        assertTrue(Roster.changes(listOf(removed), listOf(renamed)).isEmpty())
    }

    @Test
    fun `rows from an upgrade lose to any real change, and equal stamps settle the same way everywhere`() {
        val seeded = m("b", Roster.SEED_HLC)
        val removed = m("b", stamp(1, "a"), removedAt = 1)
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(removed), listOf(seeded)), "b"))
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(seeded), listOf(removed)), "b"))

        val one = seeded.copy(name = "Galaxy S22")
        val two = seeded.copy(name = "SM-S901U1")
        assertEquals(Roster.merge(listOf(one), listOf(two)), Roster.merge(listOf(two), listOf(one)))
        assertEquals(null, Roster.newestHlc(listOf(one, two)))
    }

    @Test
    fun `an upgrade row made after a removal still loses to it`() {
        // B removed A while A still ran the old version; A's upgrade then wrote its own row, later.
        val removed = m("a", stamp(100, "b"), removedAt = 100)
        val upgraded = m("a", Roster.SEED_HLC, addedAt = 500)
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(upgraded), listOf(removed)), "a"))
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(removed), listOf(upgraded)), "a"))
    }

    @Test
    fun `adding a phone again wins over its removal even when the adding phone's clock is behind`() {
        val removed = m("c", stamp(1_000, "a"), removedAt = 1_000)
        // B saw the removal (its stamp is later), but its wall clock says 900.
        val again = m("c", stamp(1_001, "b"), by = "b", addedAt = 900)
        assertEquals(Standing.ACTIVE, Roster.standing(Roster.merge(listOf(removed), listOf(again)), "c"))
        assertEquals(Standing.ACTIVE, Roster.standing(Roster.merge(listOf(again), listOf(removed)), "c"))
    }

    @Test
    fun `a phone that removed itself has left, and a newer removal by another phone makes it removed`() {
        val left = Member("b", "Phone b", addedAt = 1, removedAt = 100, removedBy = "b", hlc = stamp(100, "b"))
        assertEquals(Standing.LEFT, Roster.standing(listOf(left), "b"))
        assertTrue(Roster.leaving(listOf(left), "b"))
        val removed = left.copy(removedAt = 200, removedBy = "a", hlc = stamp(200, "a"))
        assertEquals(Standing.REMOVED, Roster.standing(Roster.merge(listOf(left), listOf(removed)), "b"))
        assertEquals(false, Roster.leaving(Roster.merge(listOf(removed), listOf(left)), "b"))
        assertEquals(false, Roster.leaving(listOf(m("b", stamp(1, "a"))), "b"))
    }

    @Test
    fun `changes lists only the rows a merge would write`() {
        // B was added again (at 100) after an older removal (at 50).
        val mine = listOf(m("a", stamp(100, "a")), m("b", stamp(100, "a"), addedAt = 100))
        val theirs = listOf(m("a", stamp(100, "a")), m("b", stamp(50, "b"), removedAt = 50), m("c", stamp(150, "b")))
        assertEquals(listOf("c"), Roster.changes(mine, theirs).map { it.deviceId })
        assertTrue(Roster.changes(mine, mine).isEmpty())
    }

    @Test
    fun `add, rename and remove stamp a new change`() {
        var t = 1_000L
        val clock = HybridClock("a", null) { t }
        val cx = object : ChangeContext {
            override val now get() = t
            override val tz = "UTC"
            override fun newId() = "id"
            override fun nextHlc() = clock.now().encode()
        }
        val c = Roster.add("c", "Galaxy A", by = "a", cx)
        t += 10
        val renamed = Roster.rename(c, "Galaxy B", cx)
        t += 10
        val removed = Roster.remove(renamed, by = "b", cx)
        assertTrue(c.hlc < renamed.hlc && renamed.hlc < removed.hlc)
        assertEquals("b", removed.removedBy)
        assertEquals(listOf(removed), Roster.merge(listOf(c, renamed), listOf(removed)))
        assertEquals(emptyList<Member>(), Roster.active(listOf(removed)))
    }
}
