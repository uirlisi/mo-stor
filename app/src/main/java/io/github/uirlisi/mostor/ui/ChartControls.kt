package io.github.uirlisi.mostor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.data.ChartRange
import io.github.uirlisi.mostor.data.ChartView

/** A horizontally scrolling row of chips, inset like the Log's. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The chart's child (only with several live children): the single-choice picker at the top of the
 * bottom controls, its menu opening upward. Picking one never changes who is recorded for.
 */
@Composable
fun ChartChildPicker(view: ChartView, fmt: Formats, onPick: (String) -> Unit) {
    if (view.children.size < 2) return
    val context = LocalContext.current
    ChildPicker(
        childOptions(view.children, fmt, view::colorIndex), view.childId,
        contentDescription = { context.getString(R.string.picker_child_cd, it) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        onSelect = onPick,
    )
}

/** "Days [7] [14] [30] [All]". */
@Composable
fun RangeChips(ranges: List<ChartRange>, selected: ChartRange, onPick: (ChartRange) -> Unit) {
    ChipRow {
        Text(stringResource(R.string.chart_days), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (r in ranges) {
            FilterChip(r == selected, { onPick(r) }, { Text(r.days?.toString() ?: stringResource(R.string.log_all)) })
        }
    }
}

/**
 * The subtype chips of [kind] (sides of a breast feed, diaper types, bottle contents, supplement
 * presets), leaving out hidden ones unless [showHidden].
 */
fun subtypeChoices(kind: Kind?, v: Visibility, showHidden: Boolean, presets: List<SupplementPreset>, fmt: Formats): List<Pair<ChartFocus, String>> =
    when (kind) {
        Kind.BREAST -> Side.entries.map { ChartFocus.OnSide(it) to fmt.sideLong(it) }
        Kind.DIAPER -> Diaper.entries.filter { showHidden || v.shows(Kind.DIAPER, it.code) }
            .map { ChartFocus.Subtype(Kind.DIAPER, it.code) to fmt.diaper(it) }
        Kind.BOTTLE -> BottleContent.entries.filter { showHidden || v.shows(Kind.BOTTLE, it.code) }
            .map { ChartFocus.Subtype(Kind.BOTTLE, it.code) to fmt.bottleContent(it) }
        Kind.SUPPLEMENT -> presets.filter { showHidden || v.showsPreset(it.id) }.map { ChartFocus.Preset(it.id) to it.name }
        else -> emptyList()
    }.takeIf { it.size > 1 } ?: emptyList()

/** One row of single-choice subtype chips; tapping the chosen one again clears it ([onClear]). */
@Composable
fun SubtypeChips(choices: List<Pair<ChartFocus, String>>, selected: ChartFocus, onPick: (ChartFocus) -> Unit, onClear: () -> Unit) {
    if (choices.isEmpty()) return
    ChipRow {
        for ((f, label) in choices) {
            FilterChip(
                f == selected, { if (f == selected) onClear() else onPick(f) }, { Text(label) },
                leadingIcon = KindIcons.of(f)?.let { icon -> { RecordIcon(icon, chartColors().let { it.icon(it.of(f)) }, 18.dp) } },
            )
        }
    }
}

/** A chart filter's color: the one its records are drawn in (both sides use the stronger tone). */
fun ChartColors.of(f: ChartFocus): Color = when (f) {
    ChartFocus.All -> text
    is ChartFocus.OnSide -> left
    is ChartFocus.Subtype -> of(f.kind, f.code)
    is ChartFocus.OfKind -> of(f.kind)
    is ChartFocus.Preset -> supplement
}

/** The "Hidden types (n)" chip, the same as the Log's; shown only when something is hidden. */
@Composable
fun HiddenTypesChip(v: Visibility, showHidden: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    if (!v.hidesAnything && !showHidden) return
    FilterChip(showHidden, onToggle, {
        Text(stringResource(R.string.log_show_hidden) + (hiddenCount.takeIf { it > 0 && !showHidden }?.let { " ($it)" } ?: ""))
    })
}

/** The controls at the bottom of a chart screen, within thumb reach. */
@Composable
fun ChartControlsColumn(content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}
