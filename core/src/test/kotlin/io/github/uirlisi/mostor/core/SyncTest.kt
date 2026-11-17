package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncTest {
    private val t0 = dublin(2026, 11, 14, 9)

    private fun stamp(wall: Long, origin: String) = HlcStamp(wall, 0, origin).encode()

    private fun diaper(id: String, hlc: String, child: String = CHILD) = Event(
        id = id, childId = child, kind = Kind.DIAPER, subtype = "wet", startAt = t0, tz = "Europe/Dublin",
        createdAt = t0, updatedAt = t0, hlc = hlc,
    )

    @Test
    fun `the origin is the device id after the wall time and counter, hyphens and all`() {
        assertEquals("0195a3c0-1111-7000-8000-000000000001", Sync.originOf(stamp(5, "0195a3c0-1111-7000-8000-000000000001")))
    }

    @Test
    fun `a vector holds the newest stamp per origin, without the seed stamp`() {
        val rows = Merge.Rows(
            events = listOf(diaper("1", stamp(5, "a")), diaper("2", stamp(9, "a")), diaper("3", stamp(7, "b"))),
            presets = Presets.seeds(CHILD, t0),
        )
        assertEquals(mapOf("a" to stamp(9, "a"), "b" to stamp(7, "b")), Sync.vector(rows))
    }

    @Test
    fun `since sends rows newer than the other's entry for their origin, unknown origins, and seeds`() {
        val rows = Merge.Rows(
            events = listOf(diaper("1", stamp(5, "a")), diaper("2", stamp(9, "a")), diaper("3", stamp(7, "b")), diaper("4", stamp(1, "c"))),
            presets = Presets.seeds(CHILD, t0),
        )
        val sent = Sync.since(rows, mapOf("a" to stamp(5, "a"), "b" to stamp(7, "b")))
        assertEquals(listOf("2", "4"), sent.events.map { it.id })
        assertEquals(2, sent.presets.size)
        assertTrue(Sync.since(rows, Sync.vector(rows)).events.isEmpty())
    }
}

class ChildMatchTest {
    private val t0 = dublin(2026, 11, 14, 9)
    private val cx = FakeCx(t0 + 60_000)
    private val mine = Child(id = "mine", name = "Aoife", birthDate = "2026-10-01", createdAt = t0, updatedAt = t0, hlc = "000000000001-0000-b")
    private val theirs = Child(id = "theirs", createdAt = t0, updatedAt = t0, hlc = "000000000001-0000-a")
    private val myMeds = Presets.seeds("mine", t0) + SupplementPreset(id = "iron", childId = "mine", name = "Iron", createdAt = t0, updatedAt = t0, hlc = "000000000002-0000-b")
    private val theirMeds = Presets.seeds("theirs", t0)

    private fun event(id: String, preset: String? = null) = Event(
        id = id, childId = "mine", kind = if (preset != null) Kind.SUPPLEMENT else Kind.DIAPER,
        supplementPresetId = preset, startAt = t0, tz = "Europe/Dublin", createdAt = t0, updatedAt = t0, hlc = "000000000003-0000-b",
    )

    @Test
    fun `my records move to their child, doses to their med of the same name, and my child goes`() {
        val vitD = myMeds.first { it.name == "Vit D" }
        val change = ChildMatch.same(mine, theirs, listOf(event("e1"), event("e2", vitD.id), event("e3", "iron")), myMeds, theirMeds, cx)

        val events = change.events.associate { it.after.id to it.after }
        assertTrue(events.values.all { it.childId == "theirs" && it.hlc > "000000000003-0000-b" })
        assertEquals(theirMeds.first { it.name == "Vit D" }.id, events.getValue("e2").supplementPresetId)
        assertEquals("iron", events.getValue("e3").supplementPresetId)
        assertNull(events.getValue("e1").supplementPresetId)

        val presets = change.presets.associate { it.after.id to it.after }
        assertTrue(Presets.seeds("mine", t0).all { presets.getValue(it.id).deletedAt != null })
        assertEquals("theirs", presets.getValue("iron").childId)
        assertNull(presets.getValue("iron").deletedAt)

        val children = change.children.associate { it.after.id to it.after }
        assertTrue(children.getValue("mine").deletedAt != null)
        // Their blank child takes my name and birth date rather than losing them.
        assertEquals("Aoife", children.getValue("theirs").name)
        assertEquals("2026-10-01", children.getValue("theirs").birthDate)
    }

    @Test
    fun `their fields win where both have one, and matching a child with itself does nothing`() {
        val named = theirs.copy(name = "Aoife Rose")
        val change = ChildMatch.same(mine, named, emptyList(), emptyList(), theirMeds, cx)
        assertEquals("Aoife Rose", change.children.single { it.after.id == "theirs" }.after.name)
        assertTrue(ChildMatch.same(mine, mine, emptyList(), emptyList(), emptyList(), cx).isEmpty)
    }
}
