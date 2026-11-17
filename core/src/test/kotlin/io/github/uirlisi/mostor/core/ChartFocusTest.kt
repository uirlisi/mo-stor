package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Chart filters (SPEC §6.5): kind, subtype (mixed counts as wet and poop), side, preset, hidden types. */
class ChartFocusTest {
    private val cx = FakeCx(dublin(2026, 11, 11, 8))
    private val at = dublin(2026, 11, 11, 9)

    private fun diaper(d: Diaper) = Records.diaper(CHILD, d, at, cx).events.single().after
    private fun bottle(c: BottleContent) = Records.bottle(CHILD, c, 90.0, at, cx).events.single().after
    private val vitD = SupplementPreset("p-d", "c1", "Vit D", createdAt = 0, updatedAt = 0, hlc = "h")
    private val vitK = SupplementPreset("p-k", "c1", "Vit K", createdAt = 0, updatedAt = 0, hlc = "h")

    @Test
    fun `wet and poop include mixed, mixed and dry are exact`() {
        val wet = diaper(Diaper.WET)
        val poop = diaper(Diaper.POOP)
        val mixed = diaper(Diaper.MIXED)
        val dry = diaper(Diaper.DRY)
        val all = listOf(wet, poop, mixed, dry)
        fun keeps(code: String) = all.filter { ChartFocus.Subtype(Kind.DIAPER, code).matches(it) }
        assertEquals(listOf(poop, mixed), keeps("poop"))
        assertEquals(listOf(wet, mixed), keeps("wet"))
        assertEquals(listOf(mixed), keeps("mixed"))
        assertEquals(listOf(dry), keeps("dry"))
    }

    @Test
    fun `bottle contents, kinds, presets`() {
        val formula = bottle(BottleContent.FORMULA)
        val milk = bottle(BottleContent.BREAST_MILK)
        val d = Records.supplement(CHILD, vitD, at, cx).events.single().after
        val k = Records.supplement(CHILD, vitK, at, cx).events.single().after
        assertTrue(ChartFocus.Subtype(Kind.BOTTLE, "formula").matches(formula))
        assertFalse(ChartFocus.Subtype(Kind.BOTTLE, "formula").matches(milk))
        assertTrue(ChartFocus.OfKind(Kind.BOTTLE).matches(milk))
        assertFalse(ChartFocus.OfKind(Kind.BOTTLE).matches(d))
        assertTrue(ChartFocus.Preset("p-d").matches(d))
        assertFalse(ChartFocus.Preset("p-d").matches(k))
        assertTrue(ChartFocus.All.matches(k))
    }

    @Test
    fun `a side keeps breast feeds and that side's segments`() {
        val change = Timers.start(Kind.BREAST, CHILD, at, cx, Side.LEFT)
        val feed = change.events.single().after
        val seg = change.segments.single().after
        assertTrue(ChartFocus.OnSide(Side.LEFT).matches(feed))
        assertTrue(ChartFocus.OnSide(Side.LEFT).matches(seg))
        assertFalse(ChartFocus.OnSide(Side.RIGHT).matches(seg))
        assertFalse(ChartFocus.OnSide(Side.LEFT).matches(diaper(Diaper.WET)))
        assertTrue(ChartFocus.All.matches(seg))
    }

    @Test
    fun `focus round-trips through its text form, junk reads as null`() {
        val all = listOf(
            ChartFocus.All, ChartFocus.OfKind(Kind.SLEEP), ChartFocus.Subtype(Kind.DIAPER, "poop"),
            ChartFocus.OnSide(Side.RIGHT), ChartFocus.Preset("019a-preset"),
        )
        for (f in all) assertEquals(f, ChartFocus.decode(f.encode()))
        assertEquals(listOf(null, Kind.SLEEP, Kind.DIAPER, Kind.BREAST, Kind.SUPPLEMENT), all.map { it.kind })
        for (junk in listOf(null, "", "kind:nope", "sub:diaper", "side:X", "preset:", "what")) {
            assertEquals(null, ChartFocus.decode(junk), junk)
        }
    }

    @Test
    fun `hidden types are left out unless asked for, deleted records always`() {
        val formula = bottle(BottleContent.FORMULA)
        val milk = bottle(BottleContent.BREAST_MILK)
        val dry = diaper(Diaper.DRY)
        val deleted = diaper(Diaper.WET).copy(deletedAt = at)
        val v = Visibility(hiddenSubtypes = setOf(Kind.BOTTLE to "formula", Kind.DIAPER to "dry"))
        val all = listOf(formula, milk, dry, deleted)
        assertEquals(listOf(milk), ChartFocus.select(all, ChartFocus.All, v, showHidden = false))
        assertEquals(listOf(formula, milk, dry), ChartFocus.select(all, ChartFocus.All, v, showHidden = true))
        assertEquals(listOf(formula), ChartFocus.select(all, ChartFocus.Subtype(Kind.BOTTLE, "formula"), v, showHidden = true))
    }
}
