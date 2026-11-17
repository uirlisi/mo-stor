package io.github.uirlisi.mostor.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side

/**
 * The record icons (DECISIONS, 2026-11-12): one drawable per kind, diaper type and breast side,
 * single-color so they take the record's color from [ChartColors].
 */
object KindIcons {
    fun of(kind: Kind, subtype: String? = null): Int = when (kind) {
        Kind.BREAST -> R.drawable.ic_feed
        Kind.BOTTLE -> R.drawable.ic_bottle
        Kind.SUPPLEMENT -> R.drawable.ic_supplement
        Kind.SLEEP -> R.drawable.ic_sleep
        Kind.DIAPER -> Diaper.ofOrNull(subtype)?.let(::of) ?: R.drawable.ic_diaper
        Kind.PUMP -> R.drawable.ic_pump
        Kind.NOTE -> R.drawable.ic_note
    }

    fun of(d: Diaper): Int = when (d) {
        Diaper.WET -> R.drawable.ic_wet
        Diaper.POOP -> R.drawable.ic_poop
        Diaper.MIXED -> R.drawable.ic_mixed
        Diaper.DRY -> R.drawable.ic_dry
    }

    fun of(side: Side): Int = if (side == Side.LEFT) R.drawable.ic_feed_left else R.drawable.ic_feed_right

    /** A chart filter's icon: its side, diaper type or kind. */
    fun of(focus: ChartFocus): Int? = when (focus) {
        ChartFocus.All -> null
        is ChartFocus.OnSide -> of(focus.side)
        is ChartFocus.Subtype -> of(focus.kind, focus.code)
        is ChartFocus.OfKind -> of(focus.kind)
        is ChartFocus.Preset -> R.drawable.ic_supplement
    }
}

/** A record's color: the same as its marks and bars in the charts. */
fun ChartColors.of(kind: Kind, subtype: String? = null): Color = when (kind) {
    Kind.BREAST -> left
    Kind.BOTTLE -> bottle(BottleContent.ofOrNull(subtype) ?: BottleContent.BREAST_MILK)
    Kind.SUPPLEMENT -> supplement
    Kind.SLEEP -> sleep
    Kind.DIAPER -> Diaper.ofOrNull(subtype)?.let(::diaper) ?: diaperGeneral
    Kind.PUMP -> pump
    Kind.NOTE -> note
}

/** A record's color for its icon or mark on the app's surfaces ([ChartColors.icon]). */
fun ChartColors.iconOf(kind: Kind, subtype: String? = null): Color = icon(of(kind, subtype))

/** A decorative record icon (the text next to it says what it is). */
@Composable
fun RecordIcon(icon: Int, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}
