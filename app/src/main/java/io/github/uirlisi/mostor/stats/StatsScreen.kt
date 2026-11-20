package io.github.uirlisi.mostor.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.DayPoint
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Metric
import io.github.uirlisi.mostor.core.Series
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.data.ChartRange
import io.github.uirlisi.mostor.data.ChartView
import io.github.uirlisi.mostor.data.Keys
import io.github.uirlisi.mostor.data.StatsTopic
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.ChartChildPicker
import io.github.uirlisi.mostor.ui.ChartColors
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
import kotlin.math.roundToInt

/** The kind whose subtypes a topic's chips offer. */
private val StatsTopic.subtypeKind: Kind?
    get() = when (this) {
        StatsTopic.FEEDS -> Kind.BREAST
        StatsTopic.DIAPER -> Kind.DIAPER
        StatsTopic.BOTTLE -> Kind.BOTTLE
        StatsTopic.SUPPLEMENT -> Kind.SUPPLEMENT
        StatsTopic.SLEEP, StatsTopic.PUMP -> null
    }

/**
 * Stats (SPEC §6.5): per-day bar charts for one topic at a time, over 7, 14, 30 or 90 days. Tap a
 * bar to read that day in every chart. Today is drawn lighter and left out of averages; days before
 * the first record are blank. The controls are at the bottom and remembered on this phone.
 */
@Composable
fun StatsScreen(ui: AppUi) {
    val repository = ui.repository
    val fmt = ui.fmt
    var rangeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var topicCode by rememberSaveable { mutableStateOf<String?>(null) }
    var focusCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (rangeCode == null) {
            val s = repository.settings()
            rangeCode = s.statsRange.code
            topicCode = s.statsTopic.code
            focusCode = s.statsFocus.encode()
        }
    }
    val range = ChartRange.ofOrNull(rangeCode)
    val topic = StatsTopic.ofOrNull(topicCode) ?: StatsTopic.FEEDS
    val focus = ChartFocus.decode(focusCode)?.takeIf { it == ChartFocus.All || it.kind == topic.subtypeKind } ?: ChartFocus.All
    // Collected only while the screen is started: the app mostly sits in the background while the
    // notification records, and a 90-day chart needn't be rebuilt after every tap there.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val view by produceState<ChartView?>(null, range, focus, showHidden, topic, lifecycle) {
        if (range == null) return@produceState
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            repository.chartViews(range, focus, showHidden, withPatterns = false, withGaps = topic == StatsTopic.FEEDS).collect { value = it }
        }
    }
    val v = view ?: return
    LaunchedEffect(v.settings.unit) { ui.unit = v.settings.unit }

    fun setFocus(f: ChartFocus) {
        focusCode = f.encode()
        ui.quietly { repository.setSetting(Keys.STATS_FOCUS, f.encode()) }
    }
    fun setTopic(t: StatsTopic) {
        topicCode = t.code
        focusCode = ChartFocus.All.encode()
        ui.quietly {
            repository.setSetting(Keys.STATS_TOPIC, t.code)
            repository.setSetting(Keys.STATS_FOCUS, null)
        }
    }

    val colors = chartColors()
    val chosen = selectedDay?.let(LocalDate::parse)?.takeIf { it in v.points.map { p -> p.day } } ?: v.today
    val ctx = ChartContext(v, fmt, colors, chosen) { selectedDay = it.toString() }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "day") {
                Column {
                    Text(ctx.dayTitle(chosen), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    // Blank days at the start of the range are days before the first record, not zeros.
                    if (v.points.any { it.beforeFirst }) {
                        val first = v.points.firstOrNull { !it.beforeFirst }?.day
                        Text(
                            if (first == null) stringResource(R.string.chart_no_data)
                            else stringResource(R.string.chart_before_first, fmt.date(v.clock.startOf(first) + 12 * 3_600_000L)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (topic) {
                StatsTopic.FEEDS -> {
                    item(key = "breast") { BreastCard(ctx, focus) }
                    item(key = "feeds") { FeedsCard(ctx) }
                    if (focus !is ChartFocus.OnSide) item(key = "gaps") { GapsCard(ctx) }
                }
                StatsTopic.SLEEP -> {
                    item(key = "sleep") { MetricCard(ctx, R.string.stats_sleep_total, Metric.SLEEP_MS, listOf(colors.sleep), withoutEnd = true) }
                    item(key = "longest") { MetricCard(ctx, R.string.stats_sleep_longest, Metric.LONGEST_SLEEP_MS, listOf(colors.sleep)) }
                    item(key = "sleeps") { MetricCard(ctx, R.string.stats_sleeps, Metric.SLEEPS, listOf(colors.sleep)) }
                }
                StatsTopic.DIAPER -> item(key = "diapers") { DiaperCard(ctx, focus) }
                StatsTopic.BOTTLE -> {
                    val c = BottleContent.entries.map(colors::bottle)
                    item(key = "volume") { MetricCard(ctx, R.string.stats_bottle_volume, Metric.BOTTLE_ML, c) }
                    item(key = "bottles") { MetricCard(ctx, R.string.stats_bottles, Metric.BOTTLES, c) }
                }
                StatsTopic.PUMP -> {
                    item(key = "volume") { MetricCard(ctx, R.string.stats_pump_volume, Metric.PUMP_ML, listOf(colors.pump)) }
                    item(key = "pumps") { MetricCard(ctx, R.string.stats_pumps, Metric.PUMPS, listOf(colors.pump)) }
                }
                StatsTopic.SUPPLEMENT -> item(key = "supplements") { SupplementCard(ctx, focus) }
            }
        }

        HorizontalDivider()
        ChartControlsColumn {
            RangeChips(ChartRange.STATS, range ?: ChartRange.D14) { r ->
                rangeCode = r.code
                ui.quietly { repository.setSetting(Keys.STATS_RANGE, r.code) }
            }
            val vis = v.settings.visibility
            ChipRow {
                for (t in StatsTopic.entries.filter { showHidden || it == topic || it.kinds.any(vis::shows) }) {
                    FilterChip(
                        t == topic, { setTopic(t) }, { Text(topicLabel(t, fmt)) },
                        leadingIcon = { RecordIcon(KindIcons.of(t.kinds.first()), colors.iconOf(t.kinds.first()), 18.dp) },
                    )
                }
                HiddenTypesChip(vis, showHidden, v.hiddenCount) { showHidden = !showHidden }
            }
            topic.subtypeKind?.let { kind ->
                SubtypeChips(subtypeChoices(kind, vis, showHidden, v.presets, fmt), focus, onPick = ::setFocus, onClear = { setFocus(ChartFocus.All) })
            }
            // Last, under the chart's own controls (one child per chart).
            ChartChildPicker(v, fmt) { id -> ui.quietly { repository.setSetting(Keys.CHART_CHILD, id) } }
        }
    }
}

@Composable
private fun topicLabel(t: StatsTopic, fmt: Formats): String = when (t) {
    StatsTopic.FEEDS -> stringResource(R.string.sum_feeds)
    StatsTopic.SLEEP -> stringResource(R.string.sum_sleep)
    StatsTopic.DIAPER -> stringResource(R.string.sum_diapers)
    StatsTopic.BOTTLE -> fmt.what(Kind.BOTTLE, null, null)
    StatsTopic.PUMP -> fmt.what(Kind.PUMP, null, null)
    StatsTopic.SUPPLEMENT -> stringResource(R.string.sum_supplements)
}

/** What every card needs: the data, formats, colors and the chosen day. */
private class ChartContext(
    val v: ChartView,
    val fmt: Formats,
    val colors: ChartColors,
    val selected: LocalDate,
    val onSelect: (LocalDate) -> Unit,
) {
    val points: List<DayPoint> = v.points
    val point: DayPoint? = points.firstOrNull { it.day == selected }
    val unit = v.settings.unit

    fun dayTitle(day: LocalDate): String {
        val date = fmt.date(v.clock.startOf(day) + 12 * 3_600_000L)
        return if (day == v.today) fmt.string(R.string.chart_so_far, date) else date
    }

    fun series(metric: Metric, colors: List<Color>, average: Boolean = true) = BarSeries(
        days = points.map { it.day },
        stacks = points.map { if (it.beforeFirst) null else metric.values(it) },
        colors = colors,
        partial = points.map { it.partial },
        average = if (average) Series.average(points, metric) else null,
    )

    fun scale(metric: Metric, extra: Double = 0.0): Scale {
        val max = maxOf(points.maxOfOrNull { metric.total(it) } ?: 0.0, extra)
        return when (metric.unit) {
            io.github.uirlisi.mostor.core.MetricUnit.DURATION -> durationScale(max, fmt)
            io.github.uirlisi.mostor.core.MetricUnit.COUNT -> countScale(max)
            io.github.uirlisi.mostor.core.MetricUnit.VOLUME -> volumeScale(max, unit, fmt)
        }
    }

    fun value(metric: Metric, x: Double): String = when (metric.unit) {
        io.github.uirlisi.mostor.core.MetricUnit.DURATION -> fmt.duration(x.toLong())
        io.github.uirlisi.mostor.core.MetricUnit.COUNT -> if (x == x.toLong().toDouble()) x.toLong().toString() else "%.1f".format(x)
        io.github.uirlisi.mostor.core.MetricUnit.VOLUME -> fmt.volume(x, unit)
    }

    /** "Average 1h 38m", or null with no full day yet. */
    fun average(metric: Metric): String? = Series.average(points, metric)?.let { fmt.string(R.string.stats_average, value(metric, it)) }
}

@Composable
private fun ChartCard(
    title: String,
    readout: String,
    footer: List<String>,
    legend: List<Pair<Color, String>> = emptyList(),
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(readout, style = MaterialTheme.typography.bodyMedium)
            if (legend.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((color, label) in legend) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(color) }
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            content()
            for (line in footer) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The readout for a day with no data, or [text] for one with data. */
private fun ChartContext.readout(text: (DayPoint) -> String): String {
    val p = point ?: return ""
    return if (p.beforeFirst) fmt.string(R.string.chart_no_data) else text(p)
}

@Composable
private fun MetricCard(ctx: ChartContext, title: Int, metric: Metric, colors: List<Color>, withoutEnd: Boolean = false, note: String? = null) {
    val r = LocalResources.current
    val footer = listOfNotNull(
        ctx.average(metric),
        Series.withoutEnd(ctx.points).takeIf { withoutEnd && it > 0 }?.let { r.getQuantityString(R.plurals.count_without_end, it, it) },
        note,
    )
    val readout = ctx.readout { p ->
        val parts = metric.values(p)
        if (parts.size == 1) ctx.value(metric, parts[0])
        else metric.parts.indices.filter { parts[it] > 0 }.joinToString(" · ") { i ->
            ctx.fmt.string(R.string.chart_part, partLabel(metric.parts[i], ctx.fmt), ctx.value(metric, parts[i]))
        }.ifEmpty { ctx.value(metric, 0.0) }
    }
    // Only the parts that show up in the range (no "Wet" under a poop filter).
    val legend = metric.parts.indices.filter { i -> ctx.points.any { !it.beforeFirst && metric.values(it)[i] > 0 } }
        .map { i -> colors[i] to partLabel(metric.parts[i], ctx.fmt) }
    ChartCard(stringResource(title), readout, footer, legend) {
        BarChart(ctx.series(metric, colors), ctx.scale(metric), ctx.selected, ctx.onSelect, ctx.colors, stringResource(title) + ". " + footer.joinToString(". "))
    }
}

private fun partLabel(code: String, fmt: Formats): String =
    Diaper.ofOrNull(code)?.let(fmt::diaper)
        ?: BottleContent.ofOrNull(code)?.let(fmt::bottleContent)
        ?: Side.entries.firstOrNull { it.code == code }?.let(fmt::sideShort)
        ?: Kind.entries.firstOrNull { it.code == code }?.let { if (it == Kind.BREAST) fmt.string(R.string.chart_breast) else fmt.what(it, null, null) }
        ?: code

@Composable
private fun BreastCard(ctx: ChartContext, focus: ChartFocus) {
    val metric = Metric.BREAST_MS
    val title = stringResource(R.string.stats_breast_time)
    val share = Series.sideShare(ctx.points)
    val footer = listOfNotNull(
        ctx.average(metric),
        share.takeIf { it.totalMs > 0 && focus !is ChartFocus.OnSide }?.let {
            val l = (it.leftMs * 100.0 / it.totalMs).roundToInt()
            ctx.fmt.string(R.string.stats_share, l, 100 - l)
        },
    )
    val readout = ctx.readout { p ->
        if (focus is ChartFocus.OnSide) {
            val ms = if (focus.side == Side.LEFT) p.totals.breastLeftMs else p.totals.breastRightMs
            ctx.fmt.string(R.string.chart_part, ctx.fmt.sideShort(focus.side), ctx.fmt.duration(ms))
        } else {
            ctx.fmt.string(R.string.sum_breast, ctx.fmt.duration(p.totals.breastLeftMs), ctx.fmt.duration(p.totals.breastRightMs))
        }
    }
    val legend = if (focus is ChartFocus.OnSide) emptyList() else Side.entries.map { (if (it == Side.LEFT) ctx.colors.left else ctx.colors.right) to ctx.fmt.sideLong(it) }
    ChartCard(title, readout, footer, legend) {
        BarChart(ctx.series(metric, listOf(ctx.colors.left, ctx.colors.right)), ctx.scale(metric), ctx.selected, ctx.onSelect, ctx.colors, "$title. ${footer.joinToString(". ")}")
    }
}

@Composable
private fun FeedsCard(ctx: ChartContext) {
    val metric = Metric.FEEDS
    val title = stringResource(R.string.stats_feeds)
    val r = LocalResources.current
    val footer = listOfNotNull(ctx.average(metric))
    val readout = ctx.readout { p ->
        val t = p.totals
        val bottles = t.feeds - t.breastFeeds
        listOfNotNull(
            r.getQuantityString(R.plurals.count_feeds, t.feeds, t.feeds),
            ctx.fmt.string(R.string.chart_part, ctx.fmt.string(R.string.chart_breast), t.breastFeeds.toString()).takeIf { bottles > 0 },
            ctx.fmt.string(R.string.chart_part, ctx.fmt.what(Kind.BOTTLE, null, null), bottles.toString()).takeIf { bottles > 0 },
        ).joinToString(" · ")
    }
    val legend = listOf(ctx.colors.left to ctx.fmt.string(R.string.chart_breast), ctx.colors.bottle to ctx.fmt.what(Kind.BOTTLE, null, null))
    ChartCard(title, readout, footer, legend.takeIf { ctx.points.any { (it.totals.feeds - it.totals.breastFeeds) > 0 } } ?: emptyList()) {
        BarChart(ctx.series(metric, listOf(ctx.colors.left, ctx.colors.bottle)), ctx.scale(metric), ctx.selected, ctx.onSelect, ctx.colors, "$title. ${footer.joinToString(". ")}")
    }
}

@Composable
private fun GapsCard(ctx: ChartContext) {
    val title = stringResource(R.string.stats_gaps)
    val gaps = ctx.v.gaps
    val full = ctx.points.filter { it.full }.map { it.day }.toSet()
    val avg = Series.averageGap(gaps) { it in full }
    val footer = listOfNotNull(avg?.let { ctx.fmt.string(R.string.stats_average, ctx.fmt.duration(it)) }, stringResource(R.string.stats_gaps_hint))
    val gap = gaps.firstOrNull { it.day == ctx.selected }
    val readout = ctx.readout {
        if (gap?.avgMs == null) ctx.fmt.string(R.string.stats_none)
        else ctx.fmt.string(R.string.stats_gap_value, ctx.fmt.duration(gap.avgMs!!), ctx.fmt.duration(gap.longestMs!!))
    }
    val before = ctx.points.associate { it.day to it.beforeFirst }
    val series = BarSeries(
        days = gaps.map { it.day },
        stacks = gaps.map { g -> if (before[g.day] == true) null else listOf((g.avgMs ?: 0L).toDouble()) },
        colors = listOf(ctx.colors.left.copy(alpha = 0.6f)),
        partial = ctx.points.map { it.partial },
        average = avg?.toDouble(),
        dots = gaps.map { g -> g.longestMs?.toDouble()?.takeIf { before[g.day] != true } },
        dotColor = ctx.colors.left,
    )
    val scale = durationScale(gaps.maxOfOrNull { it.longestMs ?: 0L }?.toDouble() ?: 0.0, ctx.fmt)
    ChartCard(title, readout, footer) {
        BarChart(series, scale, ctx.selected, ctx.onSelect, ctx.colors, "$title. ${footer.joinToString(". ")}")
    }
}

@Composable
private fun DiaperCard(ctx: ChartContext, focus: ChartFocus) {
    val note = if (focus is ChartFocus.Subtype && focus.code in setOf(Diaper.POOP.code, Diaper.WET.code)) {
        if (focus.code == Diaper.POOP.code) stringResource(R.string.stats_poop_mixed) else stringResource(R.string.stats_wet_mixed)
    } else null
    val colors = Diaper.entries.map(ctx.colors::diaper)
    MetricCard(ctx, R.string.stats_diapers, Metric.DIAPERS, colors, note = note)
}

/** One row of dots per supplement: given (filled) or not (a small gray dot) on each day. */
@Composable
private fun SupplementCard(ctx: ChartContext, focus: ChartFocus) {
    val shown: List<SupplementPreset> = ctx.v.presets.filter { focus !is ChartFocus.Preset || it.id == focus.presetId }
        .filter { p -> ctx.points.any { pt -> pt.totals.supplements.any { it.presetId == p.id } } || focus is ChartFocus.Preset }
    val days = ctx.points.filter { !it.beforeFirst }.size
    val footer = shown.map { p ->
        val given = ctx.points.count { pt -> pt.totals.supplements.any { it.presetId == p.id } }
        ctx.fmt.plural(R.plurals.stats_supplement_days, days, p.name, given, days)
    }
    val readout = ctx.readout { p ->
        p.totals.supplements.joinToString(" · ") { s ->
            val name = s.name ?: ctx.fmt.what(Kind.SUPPLEMENT, null, null)
            if (s.count > 1) ctx.fmt.string(R.string.stats_given_times, name, s.count) else ctx.fmt.string(R.string.sum_given, name, ctx.fmt.time(s.lastAt))
        }.ifEmpty { ctx.fmt.string(R.string.stats_none) }
    }
    ChartCard(stringResource(R.string.stats_supplements), readout, footer) {
        for (preset in shown) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.name, style = MaterialTheme.typography.labelSmall, color = ctx.colors.text, modifier = Modifier.width(56.dp))
                DotRow(ctx, preset.id)
            }
        }
    }
}

@Composable
private fun DotRow(ctx: ChartContext, presetId: String) {
    val n = ctx.points.size
    Canvas(
        Modifier.fillMaxWidth().height(24.dp).pointerInput(ctx.points) {
            detectTapGestures { pos ->
                val i = (pos.x / (size.width / n.coerceAtLeast(1))).toInt()
                if (i in 0 until n) ctx.onSelect(ctx.points[i].day)
            }
        },
    ) {
        val slot = size.width / n.coerceAtLeast(1)
        val r = (slot * 0.35f).coerceIn(1.5.dp.toPx(), 6.dp.toPx())
        ctx.points.forEachIndexed { i, p ->
            if (p.beforeFirst) return@forEachIndexed
            val c = Offset(i * slot + slot / 2, size.height / 2)
            val given = p.totals.supplements.any { it.presetId == presetId }
            if (given) drawCircle(ctx.colors.supplement.copy(alpha = if (p.partial) 0.45f else 1f), r, c)
            else drawCircle(ctx.colors.grid, r / 2, c)
            if (p.day == ctx.selected) drawRect(ctx.colors.selected, Offset(i * slot, 0f), Size(slot, size.height), style = Stroke(1.5.dp.toPx()))
        }
    }
}
