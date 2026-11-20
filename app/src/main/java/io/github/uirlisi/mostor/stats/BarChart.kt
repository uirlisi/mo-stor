package io.github.uirlisi.mostor.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.ui.ChartColors
import io.github.uirlisi.mostor.ui.Formats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import io.github.uirlisi.mostor.ui.currentLocale
import io.github.uirlisi.mostor.ui.localizedPattern
import kotlin.math.ceil

/**
 * One bar per day. [stacks] holds each day's parts bottom-up (null: no data that day, drawn as
 * nothing). [partial] days (today) are drawn lighter. [dots] overlays one value per day (e.g. the
 * longest gap). [average] is a dashed line.
 */
data class BarSeries(
    val days: List<LocalDate>,
    val stacks: List<List<Double>?>,
    val colors: List<Color>,
    val partial: List<Boolean>,
    val average: Double? = null,
    val dots: List<Double?>? = null,
    val dotColor: Color = Color.Unspecified,
)

/** The value axis: a round top value and how to write values. */
class Scale(val max: Double, val label: (Double) -> String)

private const val HOUR = 3_600_000.0
private const val MINUTE = 60_000.0

/** Durations in ms: to the next 30 minutes up to 2 h, the next hour up to 8 h, then an even number of hours. */
fun durationScale(max: Double, fmt: Formats): Scale {
    val top = when {
        max <= 0 -> HOUR
        max <= 2 * HOUR -> ceil(max / (30 * MINUTE)) * 30 * MINUTE
        max <= 8 * HOUR -> ceil(max / HOUR) * HOUR
        else -> ceil(max / (2 * HOUR)) * 2 * HOUR
    }
    return Scale(top) { v ->
        if (v % HOUR == 0.0) fmt.string(R.string.chart_axis_hours, (v / HOUR).toInt()) else fmt.duration(v.toLong())
    }
}

/** Counts: to the next even number, so the middle line is a whole number too. */
fun countScale(max: Double): Scale {
    val top = (ceil(max / 2) * 2).coerceAtLeast(2.0)
    return Scale(top) { v -> v.toInt().toString() }
}

/** Volumes in ml, rounded in the unit shown: 100 ml or 2 fl oz steps. */
fun volumeScale(maxMl: Double, unit: VolumeUnit, fmt: Formats): Scale {
    val step = if (unit == VolumeUnit.ML) 100.0 else 2.0
    val top = (ceil(Volume.fromMl(maxMl, unit) / step) * step).coerceAtLeast(step)
    return Scale(Volume.toMl(top, unit)) { v -> fmt.volume(v, unit) }
}

/**
 * A per-day bar chart drawn with Canvas: value labels on the left (top and middle), day labels
 * below. Tapping a bar picks its day ([onSelect]); the [selected] day's column is shaded.
 */
@Composable
fun BarChart(
    series: BarSeries,
    scale: Scale,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    colors: ChartColors,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val selectedShade = MaterialTheme.colorScheme.surfaceVariant
    val n = series.days.size
    // Weekdays for a week, day numbers for two weeks, then day and month (labels further apart).
    val locale = currentLocale()
    val dayFormat = remember(n, locale) {
        when {
            n <= 7 -> DateTimeFormatter.ofPattern("EEE", locale)
            n <= 14 -> DateTimeFormatter.ofPattern("d", locale)
            else -> localizedPattern(locale, "dMMM")
        }
    }
    val labelGap = if (n <= 14) 28.dp else 64.dp
    // The value labels, measured once: the plot starts after the widest. The tap handler reads
    // the same left edge.
    val valueLabels = listOf(scale.max, scale.max / 2).map { it to measurer.measure(scale.label(it), style) }
    val leftEdge = remember { FloatArray(1) }
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = description }
            .pointerInput(series.days) {
                detectTapGestures { pos ->
                    val left = leftEdge[0]
                    val slot = (size.width - left) / n.coerceAtLeast(1)
                    val i = ((pos.x - left) / slot).toInt()
                    if (pos.x >= left && i in 0 until n) onSelect(series.days[i])
                }
            },
    ) {
        if (n == 0) return@Canvas
        val left = (valueLabels.maxOf { it.second.size.width } + 8.dp.toPx()).coerceAtLeast(28.dp.toPx())
        leftEdge[0] = left
        val bottom = size.height - 16.dp.toPx()
        val top = 6.dp.toPx()
        val plotH = bottom - top
        val slot = (size.width - left) / n
        val barW = (slot * 0.7f).coerceAtLeast(1f)
        fun y(v: Double) = bottom - (v / scale.max * plotH).toFloat().coerceIn(0f, plotH)

        // Gridlines and value labels: top, middle, zero.
        for ((v, t) in valueLabels) {
            drawLine(colors.grid, Offset(left, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
            drawText(t, topLeft = Offset((left - t.size.width - 4.dp.toPx()).coerceAtLeast(0f), y(v) - t.size.height / 2f))
        }
        drawLine(colors.gridStrong, Offset(left, bottom), Offset(size.width, bottom), 1.dp.toPx())

        val labelEvery = ceil(labelGap.toPx() / slot).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val x0 = left + i * slot
            if (series.days[i] == selected) drawRect(selectedShade, Offset(x0, top), Size(slot, plotH))
            val bx = x0 + (slot - barW) / 2
            val alpha = if (series.partial[i]) 0.45f else 1f
            var acc = 0.0
            series.stacks[i]?.forEachIndexed { p, v ->
                if (v <= 0) return@forEachIndexed
                val y1 = y(acc + v)
                val y0 = y(acc)
                drawRoundRect(series.colors[p].copy(alpha = alpha), Offset(bx, y1), Size(barW, (y0 - y1).coerceAtLeast(1f)), CornerRadius(if (barW > 6.dp.toPx()) 2.dp.toPx() else 0f))
                acc += v
            }
            series.dots?.getOrNull(i)?.let { d ->
                drawCircle(series.dotColor.copy(alpha = alpha), (barW / 2).coerceIn(2.dp.toPx(), 4.dp.toPx()), Offset(x0 + slot / 2, y(d)))
            }
            // Day labels, counted from the newest day so today is always labeled.
            if ((n - 1 - i) % labelEvery == 0) {
                val t = measurer.measure(series.days[i].format(dayFormat), style)
                val tx = (x0 + slot / 2 - t.size.width / 2f).coerceIn(left, size.width - t.size.width)
                drawText(t, topLeft = Offset(tx, bottom + 2.dp.toPx()))
            }
        }
        series.average?.let { avg ->
            drawLine(
                colors.text, Offset(left, y(avg)), Offset(size.width, y(avg)), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
        if (selected in series.days) {
            val i = series.days.indexOf(selected)
            drawRect(colors.selected, Offset(left + i * slot, top), Size(slot, plotH), style = Stroke(1.5.dp.toPx()))
        }
    }
}
