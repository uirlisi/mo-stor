package io.github.uirlisi.mostor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper

/**
 * Colors for Patterns and Stats. Kinds get fixed hues, so they read the same with any wallpaper,
 * in a light and a dark tone. Left and right are two tones of one hue (SPEC §6.5: "shaded by
 * side"). Gridlines and text come from the Material scheme. A record's icon and status line use
 * these colors too (Phase 3b): [icon] and [text] darken the few light-mode fills that are too pale
 * for an icon (3:1) or for text (4.5:1) on the app's surfaces. Bars keep the fills.
 */
data class ChartColors(
    val dark: Boolean,
    val sleep: Color,
    val left: Color,
    val right: Color,
    val wet: Color,
    val poop: Color,
    val mixed: Color,
    val dry: Color,
    val bottle: Color,
    val formula: Color,
    val pump: Color,
    val supplement: Color,
    val diaperGeneral: Color,
    val note: Color,
    val awake: Color,
    val grid: Color,
    val gridStrong: Color,
    val hatch: Color,
    val future: Color,
    val now: Color,
    val text: Color,
    val selected: Color,
) {
    fun diaper(d: Diaper): Color = when (d) {
        Diaper.WET -> wet
        Diaper.POOP -> poop
        Diaper.MIXED -> mixed
        Diaper.DRY -> dry
    }

    fun bottle(c: BottleContent): Color = if (c == BottleContent.FORMULA) formula else bottle

    /** [fill] as an icon or mark on a surface: the same color, or a darker tone in light mode. */
    fun icon(fill: Color): Color = if (dark) fill else LIGHT_ICON[fill] ?: fill

    /** [fill] as text (a section's status line): the same color, or a darker tone in light mode. */
    fun text(fill: Color): Color = if (dark) fill else LIGHT_TEXT[fill] ?: fill
}

// Light fills under 3:1 as an icon on the button surface (#F7F7F9) or its 12–15% disc, and under
// 4.5:1 as text on white: the smallest darkening that passes (DECISIONS 2026-11-13, "Record
// colors on the new surfaces"). Wet's text tone is the handoff's.
private val LIGHT_ICON = mapOf(
    Color(0xFF80CBC4) to Color(0xFF3D948C), // right
    Color(0xFFD4A373) to Color(0xFFB97839), // mixed
    Color(0xFF9E9E9E) to Color(0xFF888888), // dry
    Color(0xFFEF6C00) to Color(0xFFDC6300), // bottle
    Color(0xFFFFB74D) to Color(0xFFC47500), // formula
    Color(0xFF43A047) to Color(0xFF409A44), // supplement
    Color(0xFFE09A00) to Color(0xFFB57D00), // awake
)
private val LIGHT_TEXT = mapOf(
    Color(0xFF80CBC4) to Color(0xFF337C76),
    Color(0xFF1E88E5) to Color(0xFF1565C0), // wet
    Color(0xFFD4A373) to Color(0xFF9B6530),
    Color(0xFF9E9E9E) to Color(0xFF707070),
    Color(0xFFEF6C00) to Color(0xFFBA5400),
    Color(0xFFFFB74D) to Color(0xFFA36100),
    Color(0xFF43A047) to Color(0xFF368039),
    Color(0xFFE09A00) to Color(0xFF986900),
)

@Composable
fun chartColors(): ChartColors {
    val s = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    fun c(light: Long, night: Long) = Color(if (dark) night else light)
    return ChartColors(
        dark = dark,
        sleep = c(0xFF3949AB, 0xFF9FA8DA),
        left = c(0xFF00796B, 0xFF80CBC4),
        right = c(0xFF80CBC4, 0xFF26A69A),
        wet = c(0xFF1E88E5, 0xFF64B5F6),
        poop = c(0xFF795548, 0xFFBCAAA4),
        mixed = c(0xFFD4A373, 0xFFA1887F),
        dry = c(0xFF9E9E9E, 0xFF9E9E9E),
        bottle = c(0xFFEF6C00, 0xFFFFB74D),
        formula = c(0xFFFFB74D, 0xFFE65100),
        pump = c(0xFF8E24AA, 0xFFCE93D8),
        supplement = c(0xFF43A047, 0xFF81C784),
        diaperGeneral = c(0xFF546E7A, 0xFFB0BEC5),
        note = s.onSurfaceVariant,
        awake = c(0xFFE09A00, 0xFFFFD54F),
        grid = s.outlineVariant.copy(alpha = 0.5f),
        gridStrong = s.outlineVariant,
        hatch = s.outline.copy(alpha = 0.6f),
        future = s.surfaceVariant.copy(alpha = 0.5f),
        now = s.error,
        text = s.onSurfaceVariant,
        selected = s.primary,
    )
}
