package io.github.uirlisi.mostor.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import io.github.uirlisi.mostor.core.MedDayLine
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.DayTotals
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.ChildTotals
import io.github.uirlisi.mostor.ui.ChildTag
import io.github.uirlisi.mostor.ui.FitText
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.caps
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.iconOf
import kotlin.math.roundToInt

/** One summary row: its kind (icon and color), label, total, and the details' segments. */
private data class SummaryRow(
    val kind: Kind,
    val label: String,
    val total: String,
    val details: List<String>,
    val balance: Pair<Long, Long>? = null,
    /** The Meds row's lines, one per med (SPEC §5.10), in place of [details]. */
    val meds: List<MedDayLine> = emptyList(),
)

/**
 * A child's day summary (SPEC §5.8; layout from docs/PHASE3B_HANDOFF.md, "Summary card"): one row
 * per kind in the order Feeds, Meds, Sleep, Diapers, Pump (then Notes), each a 4-column grid of icon,
 * CAPS label, condensed total and details. A row shows only when its kind is visible on this phone
 * and has something to show. With "All", [child] heads the card with its chip.
 */
@Composable
fun SummaryCard(ct: ChildTotals, fmt: Formats, unit: VolumeUnit, v: Visibility, childColor: Color? = null) {
    val r = LocalResources.current
    val t: DayTotals = ct.totals
    val rows = buildList {
        if ((t.feeds > 0 || t.breastMs > 0) && (v.shows(Kind.BREAST) || v.shows(Kind.BOTTLE))) {
            val details = buildList {
                if (t.breastMs > 0) add(stringResource(R.string.sum_breast, fmt.duration(t.breastLeftMs), fmt.duration(t.breastRightMs)))
                if (t.feedsWithoutEnd > 0) add(r.getQuantityString(R.plurals.count_without_end, t.feedsWithoutEnd, t.feedsWithoutEnd))
                for ((content, b) in t.bottles.entries.sortedBy { it.key.ordinal }) {
                    val res = if (content == BottleContent.FORMULA) R.string.sum_bottle_formula else R.string.sum_bottle_milk
                    add(stringResource(res, fmt.volume(b.ml, unit)))
                }
            }
            add(SummaryRow(Kind.BREAST, stringResource(R.string.sum_feeds), t.feeds.toString(), details, (t.breastLeftMs to t.breastRightMs).takeIf { t.breastMs > 0 }))
        }
        // Meds: given / listed over the scheduled meds (skips left out); without scheduled ones, the doses given.
        val meds = ct.meds
        if (meds != null && meds.lines.isNotEmpty()) {
            val anyScheduled = meds.lines.any { it.kind != MedDayLine.Kind.AS_NEEDED }
            val total = when {
                meds.listed > 0 -> "${meds.given}/${meds.listed}"
                anyScheduled -> "–" // every scheduled med skipped: nothing to count
                else -> meds.lines.sumOf { it.count }.toString()
            }
            add(SummaryRow(Kind.SUPPLEMENT, stringResource(R.string.sum_supplements), total, emptyList(), meds = meds.lines))
        }
        if ((t.sleeps > 0 || t.sleepMs > 0) && v.shows(Kind.SLEEP)) {
            val details = buildList {
                add(r.getQuantityString(R.plurals.count_sleeps, t.sleeps, t.sleeps))
                if (t.longestSleepMs > 0) add(stringResource(R.string.sum_longest, fmt.duration(t.longestSleepMs)))
                if (t.sleepsWithoutEnd > 0) add(r.getQuantityString(R.plurals.count_without_end, t.sleepsWithoutEnd, t.sleepsWithoutEnd))
            }
            add(SummaryRow(Kind.SLEEP, stringResource(R.string.sum_sleep), fmt.duration(t.sleepMs), details))
        }
        val diapers = t.diapers.filterKeys { it in v.diapers }
        if (diapers.isNotEmpty() && v.shows(Kind.DIAPER)) {
            val details = Diaper.entries.mapNotNull { d ->
                diapers[d]?.let { n -> r.getQuantityString(R.plurals.count_diaper_type, n, n, fmt.diaper(d).lowercase()) }
            }
            add(SummaryRow(Kind.DIAPER, stringResource(R.string.sum_diapers), diapers.values.sum().toString(), details))
        }
        if (t.pumps > 0 && v.shows(Kind.PUMP)) {
            add(SummaryRow(Kind.PUMP, stringResource(R.string.sum_pump), fmt.volume(t.pumpMl, unit), listOf(r.getQuantityString(R.plurals.sum_sessions, t.pumps, t.pumps))))
        }
        if (t.notes > 0 && v.shows(Kind.NOTE)) add(SummaryRow(Kind.NOTE, stringResource(R.string.sum_notes), t.notes.toString(), emptyList()))
    }

    // With one child and nothing recorded, the timeline below already says so.
    if (rows.isEmpty() && ct.child == null) return
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface, shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(start = 10.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
    ) {
        ct.child?.let { c ->
            ChildTag(fmt.childName(c.name), childColor ?: scheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.sum_nothing), color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            SummaryLine(row)
        }
    }
}

/** Icon 18 dp · label 58 dp · total 62 dp (right-aligned) · details; 8 dp apart, every cell centered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryLine(row: SummaryRow) {
    val colors = chartColors()
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecordIcon(KindIcons.of(row.kind), colors.iconOf(row.kind), 18.dp)
        Spacer(Modifier.width(8.dp))
        FitText(caps(row.label), MoStor.type.summaryLabel, Modifier.width(58.dp), color = MoStor.colors.label)
        Spacer(Modifier.width(8.dp))
        FitText(row.total, MoStor.type.total, Modifier.width(62.dp), color = scheme.onSurface, textAlign = TextAlign.End)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.meds.isNotEmpty()) MedLines(row.meds)
            if (row.details.isNotEmpty()) {
                // Wraps only between segments: each segment is one unbreakable piece.
                FlowRow {
                    row.details.forEachIndexed { i, part ->
                        Text(
                            if (i < row.details.lastIndex) "$part · " else part,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            row.balance?.let { (l, r) -> SideBalance(l, r) }
        }
    }
}

/**
 * One line per med: ✓ and the name (given), the amber mark with "Iron · Due", "Vit K · 5 days late"
 * or "Missed", the gray cross with "Skipped", and As needed doses in gray, "+ Gas drops ×2".
 */
@Composable
private fun MedLines(lines: List<MedDayLine>) {
    val c = MoStor.colors
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            val name = line.preset.name
            val (mark, markColor, text, color) = when (line.kind) {
                MedDayLine.Kind.GIVEN -> Line(R.drawable.ic_given, c.given, name, grey)
                MedDayLine.Kind.DUE -> Line(R.drawable.ic_due, c.due, stringResource(R.string.meds_item, name, stringResource(R.string.meds_due)), c.dueText)
                MedDayLine.Kind.LATE -> Line(
                    R.drawable.ic_overdue, c.due,
                    stringResource(R.string.meds_item, name, pluralStringResource(R.plurals.meds_late, line.lateDays, line.lateDays)), c.dueText,
                )
                MedDayLine.Kind.MISSED -> Line(R.drawable.ic_overdue, c.due, stringResource(R.string.meds_item, name, stringResource(R.string.meds_missed)), c.dueText)
                MedDayLine.Kind.SKIPPED -> Line(R.drawable.ic_skipped, c.skipped, stringResource(R.string.meds_item, name, stringResource(R.string.meds_skipped)), c.skipped)
                MedDayLine.Kind.AS_NEEDED -> Line(
                    null, grey,
                    stringResource(R.string.meds_as_needed_given, if (line.count > 1) name + " " + stringResource(R.string.meds_times, line.count) else name), grey,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mark?.let { Icon(painterResource(it), contentDescription = null, tint = markColor, modifier = Modifier.size(13.dp)) }
                Text(text, style = style, color = color)
            }
        }
    }
}

private data class Line(val mark: Int?, val markColor: Color, val text: String, val color: Color)

/** The day's left/right balance as a 6 dp two-color bar; the shares are read out, not shown. */
@Composable
private fun SideBalance(leftMs: Long, rightMs: Long) {
    val colors = chartColors()
    val l = (leftMs * 100.0 / (leftMs + rightMs)).roundToInt()
    val text = stringResource(R.string.stats_share, l, 100 - l)
    Canvas(Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = text }) {
        val split = size.width * leftMs / (leftMs + rightMs)
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(colors.right, cornerRadius = radius)
        if (split > 0f) drawRoundRect(colors.left, size = Size(split, size.height), cornerRadius = radius)
    }
}
