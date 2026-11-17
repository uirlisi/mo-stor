package io.github.uirlisi.mostor.ui

import android.app.UiModeManager
import android.content.Context
import android.graphics.Typeface
import io.github.uirlisi.mostor.data.ThemeChoice
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Mo Stór's fixed look (Phase 3b; DECISIONS "The look moves forward to Phase 3b"). Surfaces and
 * text are neutral gray; color appears only where it means something: a record's icon and status
 * line (its chart color), a child's color, the Meds marks, and one action blue for the single
 * "do it now" button, the confirmation strip and links. The wallpaper no longer changes anything.
 */
/**
 * Hands the theme choice to Android (API 31+), which keeps it for the app and applies it before
 * any window is drawn: the splash, the first frame and `isSystemInDarkTheme()` all follow it.
 * Setting the mode it already has changes nothing (DECISIONS, "Theme choice").
 */
fun applyThemeChoice(context: Context, choice: ThemeChoice) {
    val mode = when (choice) {
        ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
    }
    context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
}

@Composable
fun MoStorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalMoStorColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** The tokens Material 3 has no role for (docs/PHASE3B_HANDOFF.md, "Theme and color"). */
@Immutable
data class MoStorColors(
    val dark: Boolean,
    /** CAPS section and summary labels. */
    val label: Color,
    /** Secondary text on the confirmation strip ("Started earlier?"). */
    val onStripVariant: Color,
    /** The border of the time chips on the strip. */
    val stripOutline: Color,
    /** Meds: the ✓ mark and its time. */
    val given: Color,
    /** Meds: the due and late marks. */
    val due: Color,
    /** Meds: the due and late words (a darker tone in light mode, for text contrast). */
    val dueText: Color,
    /** Meds: the skipped mark and word. */
    val skipped: Color,
    /** The Record button's "has no end" capsule, and its text. */
    val noEnd: Color,
    val onNoEnd: Color,
    /** A running feed's card (Phase 3c). */
    val feedTimer: TimerTint,
    /** A running sleep's card. */
    val sleepTimer: TimerTint,
)

/**
 * A running timer card's colors (docs/PHASE3C_HANDOFF.md, "Running card"): the record's color at
 * a given alpha, or a solid color picked for the controls.
 */
@Immutable
data class TimerTint(
    /** The card's fill, laid over the sheet's surface. */
    val wash: Color,
    /** The card's 1 dp border. */
    val border: Color,
    /** The header disc behind the record's icon. */
    val disc: Color,
    /** [Switch side] and [Pause]: fill and 1 dp border. */
    val control: Color,
    val controlBorder: Color,
    /** The late-stop time chips: fill and 1 dp border. */
    val chip: Color,
    val chipBorder: Color,
    /** The LIVE mark and its dot. */
    val live: Color,
)

private val LocalMoStorColors = staticCompositionLocalOf { DarkExtras }

/** `MoStor.colors.label`, `MoStor.type.sectionLabel`… */
object MoStor {
    val colors: MoStorColors
        @Composable @ReadOnlyComposable
        get() = LocalMoStorColors.current

    val type = MoStorType
}

/** The type that differs from Material 3's defaults. */
object MoStorType {
    /** Android's condensed system family at weight 600 (API 28+); no font dependency. */
    private val condensed = FontFamily(Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 600, false))

    /** CAPS section labels: write the text with [caps]. */
    val sectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, lineHeight = 16.sp)

    /** The summary card's row labels. */
    val summaryLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.em, lineHeight = 16.sp)

    /** Labels on record buttons, Meds chips and action buttons. */
    val button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)

    /** A section's live status ("L · 2:45 PM · 10m ago"), in the record's color. */
    val status = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)

    /** The summary card's totals ("12h 50m"). */
    val total = TextStyle(fontFamily = condensed, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)

    /** The running card's clock, with tabular figures so it doesn't jitter. */
    val clock = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", lineHeight = 40.sp)

    /** The "NEXT" badge on a feed side. */
    val badge = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, lineHeight = 14.sp)

    /** The LIVE / PAUSED mark on a running card (write the text with [caps]). */
    val live = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, lineHeight = 14.sp)

    /** Times and durations in lists, tabular. */
    val tabular = TextStyle(fontFeatureSettings = "tnum")
}

/** Upper-cases a section label for the phone's language. */
fun caps(text: String): String = text.uppercase(java.util.Locale.getDefault())

private val DarkExtras = MoStorColors(
    dark = true,
    label = Color(0xFF85878F),
    onStripVariant = Color(0xFFB9C6EA),
    stripOutline = Color(0xFF5B73B0),
    given = Color(0xFF81C784),
    due = Color(0xFFFFCA28),
    dueText = Color(0xFFFFCA28),
    skipped = Color(0xFF85878F),
    noEnd = Color(0xFFFFCA28),
    onNoEnd = Color(0xFF3A2A00),
    feedTimer = TimerTint(
        wash = Color(0x1A80CBC4), border = Color(0x5980CBC4), disc = Color(0x3380CBC4),
        control = Color(0xFF366159), controlBorder = Color(0x3380CBC4),
        chip = Color.Transparent, chipBorder = Color(0xFF366159), live = Color(0xFF80CBC4),
    ),
    sleepTimer = TimerTint(
        wash = Color(0x2A8596F8), border = Color(0x599FA8DA), disc = Color(0x339FA8DA),
        control = Color(0xFF3B4060), controlBorder = Color(0x339FA8DA),
        chip = Color.Transparent, chipBorder = Color(0xFF3B4060), live = Color(0xFF9FA8DA),
    ),
)

private val LightExtras = MoStorColors(
    dark = false,
    label = Color(0xFF6E7079),
    onStripVariant = Color(0xFF33456F),
    stripOutline = Color(0xFF8EA0D8),
    given = Color(0xFF2E7D32),
    due = Color(0xFFB07800),
    dueText = Color(0xFF8A5A00),
    skipped = Color(0xFF6E7079),
    noEnd = Color(0xFFFFD54F),
    onNoEnd = Color(0xFF3A2A00),
    feedTimer = TimerTint(
        wash = Color(0x1500796B), border = Color(0x4D00796B), disc = Color(0x1F00796B),
        control = Color(0xFFD3E8E5), controlBorder = Color(0x3300796B),
        chip = Color.White, chipBorder = Color(0xFF9CCBC5), live = Color(0xFF00796B),
    ),
    sleepTimer = TimerTint(
        wash = Color(0x243949AB), border = Color(0x4D3949AB), disc = Color(0x1F3949AB),
        control = Color(0xFFDCDFF2), controlBorder = Color(0x333949AB),
        chip = Color.White, chipBorder = Color(0xFFAEB5E0), live = Color(0xFF3949AB),
    ),
)

// Roles the handoff doesn't name follow its rule: neutral, or the action blue where Material uses
// an accent (selected chips and tonal buttons use the strip color, not a second hue).
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF1A2E60),
    primaryContainer = Color(0xFF2E4578),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF3451B2),
    secondary = Color(0xFFB3C5FF),
    onSecondary = Color(0xFF1A2E60),
    secondaryContainer = Color(0xFF2E4578),
    onSecondaryContainer = Color(0xFFDAE2FF),
    tertiary = Color(0xFFFFCA28),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3B00),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0F1012),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF17181B),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF1E1F23),
    onSurfaceVariant = Color(0xFFA8AAB2),
    surfaceTint = Color(0xFF17181B),
    inverseSurface = Color(0xFFEDEDF0),
    inverseOnSurface = Color(0xFF17181B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF3A3B41),
    outlineVariant = Color(0xFF2E2F34),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF26272C),
    surfaceDim = Color(0xFF0F1012),
    surfaceContainerLowest = Color(0xFF0F1012),
    surfaceContainerLow = Color(0xFF17181B),
    surfaceContainer = Color(0xFF1A1B1F),
    surfaceContainerHigh = Color(0xFF1E1F23),
    surfaceContainerHighest = Color(0xFF26272C),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3451B2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF0F2A5C),
    inversePrimary = Color(0xFFB3C5FF),
    secondary = Color(0xFF3451B2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2FF),
    onSecondaryContainer = Color(0xFF0F2A5C),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE9A8),
    onTertiaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFF6F6F7),
    onBackground = Color(0xFF16171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16171A),
    surfaceVariant = Color(0xFFF7F7F9),
    onSurfaceVariant = Color(0xFF5B5D66),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2B2C30),
    inverseOnSurface = Color(0xFFEDEDF0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFD4D5DA),
    outlineVariant = Color(0xFFE3E4E8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9E9EC),
    surfaceContainerLowest = Color(0xFFF2F3F5),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFBFBFC),
    surfaceContainerHigh = Color(0xFFF7F7F9),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

/**
 * Material's switch with the off state drawn in the secondary text color: with the neutral scheme
 * the default (the outline color) almost disappears into the track.
 */
@Composable
fun MoStorSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val s = MaterialTheme.colorScheme
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            uncheckedThumbColor = s.onSurfaceVariant,
            uncheckedBorderColor = s.onSurfaceVariant,
            uncheckedTrackColor = s.surfaceContainerHigh,
        ),
    )
}

/**
 * One line of text that shrinks (down to [minFontSize], 10 sp by default) instead of clipping when
 * its box is too narrow:
 * labels on fixed-size buttons and chips at the largest font sizes (Phase 3b: nothing is clipped).
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: Color = Color.Unspecified,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    minFontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    val c = color.takeOrElse { style.color.takeOrElse { androidx.compose.material3.LocalContentColor.current } }
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = style.merge(TextStyle(color = c, textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified)),
        maxLines = 1,
        softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}
