package io.github.uirlisi.mostor.patterns

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import io.github.uirlisi.mostor.ui.RecordIcon
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.PatternBar
import io.github.uirlisi.mostor.core.PatternDay
import io.github.uirlisi.mostor.core.PatternMark
import io.github.uirlisi.mostor.core.Patterns
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.ui.ChartColors
import io.github.uirlisi.mostor.ui.ChipRow
import io.github.uirlisi.mostor.ui.Formats

/** A day row's lanes, top to bottom. With a focus, only the lane it needs is drawn, full height. */
enum class Lane { SLEEP, FEED, MARKS }

fun lanesFor(focus: ChartFocus): List<Lane> = when (focus.kind) {
    null -> Lane.entries
    Kind.SLEEP -> listOf(Lane.SLEEP)
    Kind.BREAST -> listOf(Lane.FEED)
    else -> listOf(Lane.MARKS)
}

private fun laneOf(kind: Kind): Lane = when (kind) {
    Kind.SLEEP -> Lane.SLEEP
    Kind.BREAST -> Lane.FEED
    else -> Lane.MARKS
}

private const val DAY = Patterns.DAY_MINUTES.toFloat()

/**
 * One day of the pattern grid: 24 clock hours across from the day start. Sleep and breast segments
 * are bars (right in a lighter tone), point records are marks, an unknown end is a short stub with
 * "?". Clock time that didn't exist (spring forward) is hatched; clock time that happened twice
 * (fall back) is tinted. On today's row, a line marks now and the rest of the day is shaded.
 */
@Composable
fun PatternRow(
    day: PatternDay,
    lanes: List<Lane>,
    colors: ChartColors,
    nowMin: Int?,
    selected: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val icons = markIcons()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        fun x(min: Int) = min / DAY * w
        val pad = 1.5.dp.toPx()
        // With every lane on show, the marks lane gets more room, so its icons read.
        val weights = lanes.map { if (it == Lane.MARKS && lanes.size > 1) 1.7f else 1f }
        val unit = h / weights.sum()
        val tops = weights.runningFold(0f) { acc, wt -> acc + wt * unit }
        fun laneTop(i: Int) = tops[i]
        fun laneHeight(i: Int) = weights[i] * unit

        // Shading first, then gridlines every 3 hours (stronger every 6), then the records.
        nowMin?.let { drawRect(colors.future, Offset(x(it), 0f), Size(w - x(it), h)) }
        for (r in day.repeated) drawRect(colors.grid, Offset(x(r.from), 0f), Size(x(r.to) - x(r.from), h))
        for (r in day.missing) hatch(x(r.from), x(r.to), h, colors.hatch)
        for (hour in 3 until 24 step 3) {
            val gx = x(hour * 60)
            drawLine(if (hour % 6 == 0) colors.gridStrong else colors.grid, Offset(gx, 0f), Offset(gx, h), 1.dp.toPx())
        }

        for (b in day.bars) {
            val lane = lanes.indexOf(laneOf(b.kind)).takeIf { it >= 0 } ?: continue
            val top = laneTop(lane) + pad
            val bh = laneHeight(lane) - 2 * pad
            val color = barColor(b, colors)
            val left = x(b.fromMin)
            val width = (x(b.toMin) - left).coerceAtLeast(2.dp.toPx())
            drawRoundRect(color, Offset(left, top), Size(width, bh), CornerRadius(2.dp.toPx()))
            if (b.toNext) arrow(left + width, top, bh, colors.text, pointsRight = true, inside = true)
            if (b.fromPrevious) arrow(left, top, bh, colors.text, pointsRight = false, inside = true)
            if (b.running) arrow(left + width, top, bh, color, pointsRight = true, inside = false)
        }

        for (m in day.marks) {
            val lane = lanes.indexOf(if (m.unknownEnd) laneOf(m.kind) else Lane.MARKS).takeIf { it >= 0 } ?: continue
            val lh = laneHeight(lane)
            val cy = laneTop(lane) + lh / 2
            val s = (lh - 2 * pad).coerceIn(4.dp.toPx(), 22.dp.toPx())
            mark(m, x(m.atMin), cy, s, lh - 2 * pad, colors, measurer, icons)
        }

        nowMin?.let { drawLine(colors.now, Offset(x(it), 0f), Offset(x(it), h), 1.5.dp.toPx()) }
        if (selected) drawRect(colors.selected, Offset.Zero, size, style = Stroke(2.dp.toPx()))
    }
}

private fun barColor(b: PatternBar, c: ChartColors): Color = when {
    b.kind == Kind.SLEEP -> c.sleep
    b.side == Side.RIGHT -> c.right
    else -> c.left
}

private fun DrawScope.hatch(from: Float, to: Float, h: Float, color: Color) {
    clipRect(from, 0f, to, h) {
        val step = 4.dp.toPx()
        var x = from - h
        while (x < to) {
            drawLine(color, Offset(x, h), Offset(x + h, 0f), 1.dp.toPx())
            x += step
        }
    }
}

/** A small triangle at [atX]: inside a bar's end (continues on the next/previous row) or just after it (running). */
private fun DrawScope.arrow(atX: Float, top: Float, bh: Float, color: Color, pointsRight: Boolean, inside: Boolean) {
    val s = minOf(bh * 0.7f, 6.dp.toPx())
    val cy = top + bh / 2
    val p = Path()
    if (pointsRight) {
        val tip = if (inside) atX - 1.dp.toPx() else atX + s
        p.moveTo(tip - s, cy - s / 2); p.lineTo(tip, cy); p.lineTo(tip - s, cy + s / 2)
    } else {
        val tip = atX + 1.dp.toPx()
        p.moveTo(tip + s, cy - s / 2); p.lineTo(tip, cy); p.lineTo(tip + s, cy + s / 2)
    }
    p.close()
    drawPath(p, color)
}

/** The painters for the marks, from the record icons. */
private class MarkIcons(
    val wet: Painter,
    val poop: Painter,
    val mixedWet: Painter,
    val mixedPoop: Painter,
    val dry: Painter,
    val bottle: Painter,
    val pump: Painter,
    val supplement: Painter,
)

@Composable
private fun markIcons() = MarkIcons(
    wet = painterResource(R.drawable.ic_wet),
    poop = painterResource(R.drawable.ic_poop),
    mixedWet = painterResource(R.drawable.ic_mixed_part_wet),
    mixedPoop = painterResource(R.drawable.ic_mixed_part_poop),
    dry = painterResource(R.drawable.ic_dry),
    bottle = painterResource(R.drawable.ic_bottle),
    pump = painterResource(R.drawable.ic_pump),
    supplement = painterResource(R.drawable.ic_supplement),
)

/** Draws [p] in [color] (an icon tone, [ChartColors.icon]), [s] wide, centered on ([x], [cy]). */
private fun DrawScope.icon(p: Painter, x: Float, cy: Float, s: Float, color: Color) {
    translate(x - s / 2, cy - s / 2) {
        with(p) { draw(Size(s, s), colorFilter = ColorFilter.tint(color)) }
    }
}

private fun DrawScope.mark(m: PatternMark, x: Float, cy: Float, s: Float, laneH: Float, c: ChartColors, measurer: TextMeasurer, icons: MarkIcons) {
    if (m.unknownEnd) {
        val color = if (m.kind == Kind.SLEEP) c.sleep else c.left
        drawRect(color, Offset(x, cy - laneH / 2), Size(3.dp.toPx(), laneH))
        val text = measurer.measure("?", TextStyle(color = color, fontSize = 10.sp))
        drawText(text, topLeft = Offset(x + 4.dp.toPx(), cy - text.size.height / 2f))
        return
    }
    when (m.kind) {
        Kind.DIAPER -> when (Diaper.ofOrNull(m.subtype)) {
            Diaper.WET -> icon(icons.wet, x, cy, s, c.icon(c.wet))
            Diaper.POOP -> icon(icons.poop, x, cy, s, c.icon(c.poop))
            Diaper.MIXED -> {
                icon(icons.mixedWet, x, cy, s, c.icon(c.wet))
                icon(icons.mixedPoop, x, cy, s, c.icon(c.poop))
            }
            Diaper.DRY, null -> icon(icons.dry, x, cy, s, c.icon(c.dry))
        }
        Kind.BOTTLE -> icon(icons.bottle, x, cy, s, c.icon(c.bottle(BottleContent.ofOrNull(m.subtype) ?: BottleContent.BREAST_MILK)))
        Kind.PUMP -> icon(icons.pump, x, cy, s, c.icon(c.pump))
        Kind.SUPPLEMENT -> icon(icons.supplement, x, cy, s, c.icon(c.supplement))
        else -> Unit
    }
}

/** The hour labels over the grid, every 3 hours from the day start, in the phone's 12/24-hour style. */
@Composable
fun HourAxis(startHour: Int, colors: ChartColors, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    val h24 = DateFormat.is24HourFormat(context)
    val am = stringResource(R.string.chart_axis_am)
    val pm = stringResource(R.string.chart_axis_pm)
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        for (i in 0 until 24 step 3) {
            val hour = (startHour + i) % 24
            val label = when {
                h24 -> "%02d".format(hour)
                hour == 0 -> am.format(12)
                hour < 12 -> am.format(hour)
                hour == 12 -> pm.format(12)
                else -> pm.format(hour - 12)
            }
            val t = measurer.measure(label, style)
            val x = (i * 60 / DAY * size.width - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, 0f))
        }
    }
}

/** What the bars' colors and the marks' icons mean, for the lanes on show. */
@Composable
fun PatternLegend(lanes: List<Lane>, colors: ChartColors, fmt: Formats) {
    val bars = buildList {
        if (Lane.SLEEP in lanes) add(colors.sleep to fmt.timedKind(Kind.SLEEP))
        if (Lane.FEED in lanes) {
            add(colors.left to fmt.sideLong(Side.LEFT))
            add(colors.right to fmt.sideLong(Side.RIGHT))
        }
    }
    val marks = if (Lane.MARKS !in lanes) emptyList() else listOf(
        Triple(R.drawable.ic_wet, colors.wet, fmt.diaper(Diaper.WET)),
        Triple(R.drawable.ic_poop, colors.poop, fmt.diaper(Diaper.POOP)),
        Triple(R.drawable.ic_bottle, colors.bottle, fmt.what(Kind.BOTTLE, null, null)),
        Triple(R.drawable.ic_pump, colors.pump, fmt.what(Kind.PUMP, null, null)),
        Triple(R.drawable.ic_supplement, colors.supplement, fmt.what(Kind.SUPPLEMENT, null, null)),
    )
    ChipRow {
        for ((color, label) in bars) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) }
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.text)
            }
        }
        for ((icon, color, label) in marks) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                RecordIcon(icon, colors.icon(color), 14.dp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.text)
            }
        }
    }
}
