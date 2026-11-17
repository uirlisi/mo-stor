package io.github.uirlisi.mostor.patterns

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.DayPoint
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Patterns
import io.github.uirlisi.mostor.core.Series
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.ChartRange
import io.github.uirlisi.mostor.data.ChartView
import io.github.uirlisi.mostor.data.Keys
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.ChartChildPicker
import io.github.uirlisi.mostor.ui.ChartControlsColumn
import io.github.uirlisi.mostor.ui.ChipRow
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.HiddenTypesChip
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.iconOf
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.of
import io.github.uirlisi.mostor.ui.RangeChips
import io.github.uirlisi.mostor.ui.SubtypeChips
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.subtypeChoices
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** The kinds the Patterns screen can narrow to, in chip order. */
private val PATTERN_KINDS = listOf(Kind.SLEEP, Kind.BREAST, Kind.BOTTLE, Kind.DIAPER, Kind.SUPPLEMENT, Kind.PUMP)

/** Where the grid's date labels sit. */
private val GUTTER = 44.dp

/**
 * Patterns (SPEC §6.5): one row per day, newest on top, 24 clock hours across from the day start.
 * Tap a row to read its day below the grid, then [Open day] for its log. The range, kind and
 * subtype chips are at the bottom, within thumb reach; they are remembered on this phone.
 */
@Composable
fun PatternsScreen(ui: AppUi) {
    val repository = ui.repository
    val fmt = ui.fmt
    var rangeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var focusCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by rememberSaveable { mutableIntStateOf(0) }

    // The remembered choices, once; after that every tap writes through.
    LaunchedEffect(Unit) {
        if (rangeCode == null) {
            val s = repository.settings()
            rangeCode = s.patternsRange.code
            focusCode = s.patternsFocus.encode()
        }
    }
    // Once a minute, so a running timer's bar and the "now" line move on.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick++
        }
    }
    val range = ChartRange.ofOrNull(rangeCode)
    val focus = ChartFocus.decode(focusCode) ?: ChartFocus.All
    val view by produceState<ChartView?>(null, range, focus, showHidden, tick) {
        if (range == null) return@produceState
        repository.chartViews(range, focus, showHidden, withPatterns = true, withGaps = false).collect { value = it }
    }
    val v = view ?: return
    LaunchedEffect(v.settings.unit) { ui.unit = v.settings.unit }

    fun setRange(r: ChartRange) {
        rangeCode = r.code
        ui.quietly { repository.setSetting(Keys.PATTERNS_RANGE, r.code) }
    }
    fun setFocus(f: ChartFocus) {
        focusCode = f.encode()
        ui.quietly { repository.setSetting(Keys.PATTERNS_FOCUS, f.encode()) }
    }

    val colors = chartColors()
    val lanes = lanesFor(focus)
    val res = LocalContext.current.resources
    val rows = v.patterns.asReversed()
    val points = v.points.associateBy { it.day }
    val chosen = selectedDay?.let(LocalDate::parse)?.takeIf { it in points } ?: v.today
    val dayLabel = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
    val nowMin = Patterns.minutes(v.clock, v.today, System.currentTimeMillis())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp + GUTTER, end = 12.dp, top = 4.dp)) {
            HourAxis(v.settings.dayStartHour, colors, Modifier.weight(1f))
        }
        // Rows share the height there is (7 and 14 days fit without scrolling), 18–44 dp each.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rowHeight = ((maxHeight - 8.dp) / rows.size.coerceAtLeast(1) - 2.dp).coerceIn(18.dp, 44.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(rows, key = { it.day.toString() }) { row ->
                    val p = points[row.day]
                    val described = fmt.date(v.clock.startOf(row.day) + 12 * 3_600_000L) + ": " + (p?.let { daySummary(it, fmt, v.settings.unit, res, focus) } ?: "")
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { selectedDay = row.day.toString() }
                            .semantics(mergeDescendants = true) { contentDescription = described }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.day.format(dayLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (p?.beforeFirst == true) colors.grid else if (row.day == chosen) colors.selected else colors.text,
                            modifier = Modifier.width(GUTTER),
                        )
                        PatternRow(
                            row, lanes, colors,
                            nowMin = if (row.day == v.today) nowMin else null,
                            selected = row.day == chosen,
                            height = rowHeight,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val firstShown = v.points.firstOrNull { !it.beforeFirst }?.day
                if (v.points.any { it.beforeFirst }) {
                    item(key = "before") {
                        Text(
                            if (firstShown == null) stringResource(R.string.chart_no_data)
                            else stringResource(R.string.chart_before_first, fmt.date(v.clock.startOf(firstShown) + 12 * 3_600_000L)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text,
                            modifier = Modifier.padding(start = GUTTER, top = 8.dp),
                        )
                    }
                }
            }
        }
        PatternLegend(lanes, colors, fmt)

        // The chosen day, in words.
        val p = points[chosen]
        val row = v.patterns.firstOrNull { it.day == chosen }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val date = fmt.date(v.clock.startOf(chosen) + 12 * 3_600_000L)
                Text(
                    listOfNotNull(
                        if (chosen == v.today) stringResource(R.string.chart_so_far, date) else date,
                        row?.hours?.takeIf { it != 24 }?.let { stringResource(R.string.chart_dst, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    p?.let { daySummary(it, fmt, v.settings.unit, res, focus) } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { ui.showDay(chosen) }) { Text(stringResource(R.string.chart_open_day)) }
        }

        HorizontalDivider(Modifier.padding(top = 4.dp))
        ChartControlsColumn {
            RangeChips(ChartRange.PATTERNS, range ?: ChartRange.D14, ::setRange)
            val vis = v.settings.visibility
            ChipRow {
                FilterChip(focus == ChartFocus.All, { setFocus(ChartFocus.All) }, { Text(stringResource(R.string.log_all)) })
                for (k in PATTERN_KINDS.filter { showHidden || vis.shows(it) || focus.kind == it }) {
                    FilterChip(focus.kind == k, {
                        setFocus(if (focus == ChartFocus.OfKind(k)) ChartFocus.All else ChartFocus.OfKind(k))
                    }, { Text(kindLabel(k, fmt)) }, leadingIcon = { RecordIcon(KindIcons.of(k), colors.iconOf(k), 18.dp) })
                }
                HiddenTypesChip(vis, showHidden, v.hiddenCount) { showHidden = !showHidden }
            }
            val kind = focus.kind
            if (kind != null) {
                SubtypeChips(
                    subtypeChoices(kind, vis, showHidden, v.presets, fmt), focus,
                    onPick = ::setFocus, onClear = { setFocus(ChartFocus.OfKind(kind)) },
                )
            }
            // Last, under the chart's own controls (one child per chart).
            ChartChildPicker(v, fmt) { id -> ui.quietly { repository.setSetting(Keys.CHART_CHILD, id) } }
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun kindLabel(k: Kind, fmt: Formats): String = when (k) {
    Kind.BREAST -> stringResource(R.string.chart_breast)
    Kind.DIAPER -> stringResource(R.string.kind_diaper)
    else -> fmt.what(k, null, null)
}

/** A day in words: "sleep 11h 20m · 8 feeds · L 52m · R 47m · 6 wet · 3 poop". */
fun daySummary(p: DayPoint, fmt: Formats, unit: VolumeUnit, res: Resources, focus: ChartFocus = ChartFocus.All): String {
    if (p.beforeFirst) return res.getString(R.string.chart_no_data)
    val t = p.totals
    val parts = mutableListOf<String>()
    if (t.sleepMs > 0 || t.sleeps > 0) parts += fmt.string(R.string.chart_sum_sleep, fmt.duration(t.sleepMs))
    if (t.feeds > 0) parts += res.getQuantityString(R.plurals.count_feeds, t.feeds, t.feeds)
    if (focus is ChartFocus.OnSide) {
        val ms = if (focus.side == Side.LEFT) t.breastLeftMs else t.breastRightMs
        parts += fmt.string(R.string.log_side_time, fmt.sideShort(focus.side), fmt.duration(ms))
    } else if (t.breastMs > 0) {
        parts += fmt.string(R.string.sum_breast, fmt.duration(t.breastLeftMs), fmt.duration(t.breastRightMs))
    }
    if (t.diapers.isNotEmpty()) {
        parts += res.getQuantityString(R.plurals.count_wet, t.wet, t.wet)
        parts += res.getQuantityString(R.plurals.count_poop, t.poop, t.poop)
    }
    val bottles = t.bottles.values.sumOf { it.count }
    if (bottles > 0) parts += fmt.string(R.string.chart_sum_bottles, bottles, fmt.volume(t.bottles.values.sumOf { it.ml }, unit))
    if (t.pumps > 0) parts += fmt.string(R.string.chart_sum_pump, fmt.volume(t.pumpMl, unit))
    for (s in t.supplements) parts += if (s.count > 1) fmt.string(R.string.stats_given_times, s.name ?: "", s.count) else (s.name ?: "")
    val noEnd = Series.withoutEnd(listOf(p))
    if (noEnd > 0) parts += res.getQuantityString(R.plurals.count_without_end, noEnd, noEnd)
    return if (parts.isEmpty()) res.getString(R.string.chart_nothing) else parts.joinToString(" · ")
}
