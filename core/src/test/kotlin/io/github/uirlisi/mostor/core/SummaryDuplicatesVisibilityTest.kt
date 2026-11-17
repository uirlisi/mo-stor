package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The day summary (SPEC §5.8), possible duplicates (§5.6), visibility (§6.6), children and presets. */
class SummaryDuplicatesVisibilityTest {
    private val day = LocalDate.of(2026, 11, 11)
    private val clock = DayClock(0, DUBLIN)
    private val cx = FakeCx(dublin(2026, 11, 10, 20, 0))
    private val table = Table()

    private fun t(h: Int, mi: Int = 0, d: Int = 11) = dublin(2026, 11, d, h, mi)

    private fun sleep(from: Long, to: Long?): String {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, from, cx)).newEventId()
        if (to != null) table.apply(Timers.stop(table.timed(id), to, cx))
        return id
    }

    private fun totals(now: Long) = DaySummary.totals(table.events.values.toList(), table.segments.values.toList(), day, clock, now)

    @Test
    fun `sleep across midnight is split, counted on the day it started`() {
        sleep(t(22, d = 10), t(6))
        sleep(t(13), t(14, 30))
        val s = totals(t(20))
        assertEquals(6 * HOUR + 90 * MIN, s.sleepMs)
        assertEquals(1, s.sleeps)
        assertEquals(90 * MIN, s.longestSleepMs)
    }

    @Test
    fun `a running sleep counts to now, an unknown end counts but adds no time`() {
        sleep(t(13), null)
        val unknown = sleep(t(9), null)
        table.apply(Timers.giveUp(table.timed(unknown), cx))
        val s = totals(t(14))
        assertEquals(HOUR, s.sleepMs)
        assertEquals(2, s.sleeps)
        assertEquals(1, s.sleepsWithoutEnd)
        assertEquals(HOUR, s.longestSleepMs)
    }

    @Test
    fun `breast minutes per side, bottles, supplements, diapers, pumps`() {
        val f = table.apply(Timers.start(Kind.BREAST, CHILD, t(8), cx, Side.LEFT)).newEventId()
        table.apply(Timers.switchSide(table.timed(f), t(8, 10), cx))
        table.apply(Timers.stop(table.timed(f), t(8, 25), cx))
        table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 90.0, t(11), cx))
        table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 60.0, t(15), cx))
        table.apply(Records.bottle(CHILD, BottleContent.BREAST_MILK, 50.0, t(17), cx))
        val vitD = SupplementPreset("p-d", "c1", "Vit D", amount = 1.0, unit = "drops", createdAt = 0, updatedAt = 0, hlc = "h")
        table.apply(Records.supplement(CHILD, vitD, t(9), cx))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(9), cx))
        table.apply(Records.diaper(CHILD, Diaper.MIXED, t(12), cx))
        table.apply(Records.diaper(CHILD, Diaper.DRY, t(13), cx))
        table.apply(Records.pump(CHILD, 120.0, t(10), cx))
        table.apply(Records.note(CHILD, "hello", t(10), cx))
        // Yesterday's records don't count.
        table.apply(Records.diaper(CHILD, Diaper.WET, t(23, d = 10), cx))

        val s = totals(t(20))
        assertEquals(4, s.feeds)
        assertEquals(1, s.breastFeeds)
        assertEquals(10 * MIN, s.breastLeftMs)
        assertEquals(15 * MIN, s.breastRightMs)
        assertEquals(BottleTotal(2, 150.0), s.bottles[BottleContent.FORMULA])
        assertEquals(BottleTotal(1, 50.0), s.bottles[BottleContent.BREAST_MILK])
        assertEquals(listOf(SupplementCount("p-d", "Vit D", 1, t(9))), s.supplements)
        assertEquals(mapOf(Diaper.WET to 1, Diaper.MIXED to 1, Diaper.DRY to 1), s.diapers)
        assertEquals(2, s.wet)
        assertEquals(1, s.poop)
        assertEquals(1, s.pumps)
        assertEquals(120.0, s.pumpMl)
        assertEquals(1, s.notes)
    }

    @Test
    fun `a running feed counts to now, a feed with no end adds no minutes`() {
        table.apply(Timers.start(Kind.BREAST, CHILD, t(8), cx, Side.RIGHT))
        val g = table.apply(Timers.start(Kind.BREAST, "other", t(6), cx, Side.LEFT)).newEventId()
        table.apply(Timers.giveUp(table.timed(g), cx))
        val s = totals(t(8, 20))
        assertEquals(20 * MIN, s.breastRightMs)
        assertEquals(0L, s.breastLeftMs)
        assertEquals(1, s.feedsWithoutEnd)
    }

    // ---- Duplicates ----

    private fun event(id: String, at: Long, hlcOrigin: String, kind: Kind = Kind.DIAPER, sub: String? = "wet", child: String = CHILD) =
        Event(id = id, childId = child, kind = kind, subtype = sub, startAt = at, tz = "Europe/Dublin", createdAt = at, updatedAt = at,
            hlc = HlcStamp(at, 0, hlcOrigin).encode())

    @Test
    fun `same kind and subtype from different phones within 5 minutes are flagged`() {
        val a = event("a", t(14), "phone-a")
        val b = event("b", t(14, 4), "phone-b")
        val dup = Duplicates.find(listOf(a, b))
        assertEquals(setOf("b"), dup["a"])
        assertEquals(setOf("a"), dup["b"])
    }

    @Test
    fun `the same phone, another subtype, another child, more than 5 minutes or deleted are not flagged`() {
        val a = event("a", t(14), "phone-a")
        assertTrue(Duplicates.find(listOf(a, event("b", t(14, 1), "phone-a"))).isEmpty())
        assertTrue(Duplicates.find(listOf(a, event("b", t(14, 1), "phone-b", sub = "poop"))).isEmpty())
        assertTrue(Duplicates.find(listOf(a, event("b", t(14, 1), "phone-b", child = "c2"))).isEmpty())
        assertTrue(Duplicates.find(listOf(a, event("b", t(14, 6), "phone-b"))).isEmpty())
        assertTrue(Duplicates.find(listOf(a, event("b", t(14, 1), "phone-b").copy(deletedAt = 1))).isEmpty())
    }

    @Test
    fun `supplements compare by preset`() {
        val d = event("a", t(9), "phone-a", Kind.SUPPLEMENT, null).copy(supplementPresetId = "vit-d")
        val k = event("b", t(9, 1), "phone-b", Kind.SUPPLEMENT, null).copy(supplementPresetId = "vit-k")
        val d2 = event("c", t(9, 2), "phone-b", Kind.SUPPLEMENT, null).copy(supplementPresetId = "vit-d")
        assertEquals(mapOf("a" to setOf("c"), "c" to setOf("a")), Duplicates.find(listOf(d, k, d2)))
    }

    @Test
    fun `origin is read from the hlc`() {
        assertEquals("dev-1", Duplicates.origin("019a6f3c2b10-0003-dev-1"))
        assertEquals("seed", Duplicates.origin(SEED_HLC))
        assertEquals("", Duplicates.origin("garbage"))
    }

    // ---- Visibility ----

    @Test
    fun `visibility round-trips through setting keys`() {
        val keys = listOf(
            Visibility.key(Kind.PUMP),
            Visibility.key(Kind.BOTTLE, "formula"),
            Visibility.key(Kind.DIAPER, "dry"),
            Visibility.presetKey("00000000-0000-7000-8000-00000000d002"),
            "hidden:kind:nonsense",
            "unrelated",
        )
        val v = Visibility.fromKeys(keys)
        assertEquals(setOf(Kind.PUMP), v.hiddenKinds)
        assertEquals(listOf(BottleContent.BREAST_MILK), v.bottleContents)
        assertEquals(listOf(Diaper.WET, Diaper.POOP, Diaper.MIXED), v.diapers)
        assertFalse(v.showsPreset("00000000-0000-7000-8000-00000000d002"))
        assertTrue(v.showsPreset("00000000-0000-7000-8000-00000000d001"))
        assertTrue(v.hidesAnything)
        assertFalse(Visibility().hidesAnything)
    }

    @Test
    fun `a hidden kind hides its records, subtypes and presets`() {
        val v = Visibility(hiddenKinds = setOf(Kind.SUPPLEMENT), hiddenSubtypes = setOf(Kind.DIAPER to "dry"))
        assertFalse(v.shows(event("a", t(1), "x", Kind.DIAPER, "dry")))
        assertTrue(v.shows(event("a", t(1), "x", Kind.DIAPER, "wet")))
        assertFalse(v.shows(event("a", t(1), "x", Kind.SUPPLEMENT, null)))
        assertFalse(v.showsPreset("any"))
        assertTrue(Visibility(hiddenKinds = setOf(Kind.BOTTLE)).bottleContents.isEmpty())
    }

    // ---- Children and presets ----

    @Test
    fun `children are added, edited, archived and deleted, each undoable`() {
        val children = linkedMapOf<String, Child>()
        fun apply(c: Change) = c.also { ch -> ch.children.forEach { children[it.after.id] = it.after } }
        val add = apply(Children.add(ChildFields(name = "  Aoife "), 1, cx))
        val id = add.children.single().after.id
        assertEquals("Aoife", children.getValue(id).name)
        assertTrue(Children.edit(children.getValue(id), ChildFields(name = "Aoife"), cx).isEmpty)
        apply(Children.edit(children.getValue(id), ChildFields(name = "Aoife", sex = "x", birthWeightG = 3400), cx))
        assertNull(children.getValue(id).sex)
        assertEquals(3400, children.getValue(id).birthWeightG)
        val arch = apply(Children.setArchived(children.getValue(id), true, cx))
        assertTrue(children.getValue(id).archivedAt != null)
        val undo = Undo.revert(arch, { null }, { null }, cx, currentChild = { children[it] })!!
        apply(undo)
        assertNull(children.getValue(id).archivedAt)
        apply(Children.delete(children.getValue(id), cx))
        assertTrue(children.getValue(id).deletedAt != null)
    }

    @Test
    fun `presets move by renumbering only the keys that change`() {
        val ps = (0..2).map { SupplementPreset("p$it", "c1", "P$it", sortKey = it, createdAt = 0, updatedAt = 0, hlc = "h") }
        val change = Presets.move(ps, 2, 0, cx)
        assertEquals(listOf("p2" to 0, "p0" to 1, "p1" to 2), change.presets.map { it.after.id to it.after.sortKey })
        assertTrue(Presets.move(ps, 1, 1, cx).isEmpty)
        val edit = Presets.edit(ps[0], " Vit D ", 400.0, " IU ", MedPlan(), "2026-11-13", cx).presets.single().after
        assertEquals("Vit D", edit.name)
        assertEquals("IU", edit.unit)
    }

    private companion object {
        const val SEED_HLC = "000000000000-0000-seed"
    }
}
