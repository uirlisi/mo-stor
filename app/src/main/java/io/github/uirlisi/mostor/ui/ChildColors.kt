package io.github.uirlisi.mostor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.core.Child

/**
 * Children's colors: eight that each have a matching colored-circle emoji, so the dot in the app
 * and the emoji in the notification (which can only show text) are the same color.
 */
val CHILD_PALETTE: List<Int> = listOf(
    0xFF1E88E5.toInt(), // blue
    0xFFE53935.toInt(), // red
    0xFF43A047.toInt(), // green
    0xFFFB8C00.toInt(), // orange
    0xFF8E24AA.toInt(), // purple
    0xFFFDD835.toInt(), // yellow
    0xFF6D4C41.toInt(), // brown
    0xFF424242.toInt(), // black
)

private val CHILD_EMOJI = listOf("🔵", "🔴", "🟢", "🟠", "🟣", "🟡", "🟤", "⚫")

/**
 * A child's color: its own, or, with none set (the implicit first child), the palette entry for
 * [index], its place among all live children. Callers pass that same index everywhere.
 */
fun childArgb(child: Child, index: Int): Int = child.color ?: CHILD_PALETTE[index.mod(CHILD_PALETTE.size)]

fun childColor(child: Child, index: Int): Color = Color(childArgb(child, index))

/** The colored-circle emoji closest to the child's color, for notification text. */
fun childEmoji(child: Child, index: Int): String {
    val argb = childArgb(child, index)
    val i = CHILD_PALETTE.indexOf(argb).takeIf { it >= 0 } ?: CHILD_PALETTE.indices.minBy { distance(CHILD_PALETTE[it], argb) }
    return CHILD_EMOJI[i]
}

private fun distance(a: Int, b: Int): Int {
    fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
    return listOf(16, 8, 0).sumOf { (ch(a, it) - ch(b, it)).let { d -> d * d } }
}

/** A child's color dot. */
@Composable
fun ChildDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/**
 * A child's name in a chip of their color (Phase 3b): a 1 dp border in the color over a 15% tint
 * (dark) or 10% (light). Heads each summary card when Today shows several children (28 dp, 8 dp dot, 13 sp), and starts a
 * timeline row's detail line ([small]: 20 dp, 11 sp, no dot).
 */
@Composable
fun ChildTag(name: String, color: Color, small: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (small) 10.dp else 14.dp)
    val tint = if (MoStor.colors.dark) 0.15f else 0.10f
    Row(
        modifier
            .height(if (small) 20.dp else 28.dp)
            .background(color.copy(alpha = tint), shape)
            .border(1.dp, color, shape)
            .padding(horizontal = if (small) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!small) ChildDot(color, 8.dp)
        Text(
            name,
            style = MoStor.type.button.copy(fontSize = if (small) 11.sp else 13.sp, lineHeight = if (small) 14.sp else 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
