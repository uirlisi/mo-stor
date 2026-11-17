package io.github.uirlisi.mostor.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Elapsed
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.RunningTimer
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.childColor

/**
 * Today's Record button (Phase 3b; DECISIONS "Today drops its timer cards"): an extended button that
 * opens the quick sheet, carrying every running timer for every active child as a capsule with its
 * live clock, and a "has no end" capsule first. Two capsules at most, then "+N"; the word "Record"
 * only while there is room for it. One target: the sheet has the controls. [focus] is called with
 * the "has no end" record the sheet should open on, if any.
 */
@Composable
fun RecordButton(st: HomeState, now: Long, fmt: Formats, maxWidth: Dp, modifier: Modifier = Modifier, onClick: (focus: String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val capsules = st.noEnd.map { Capsule.NoEnd(it) } + st.running.map { Capsule.Timer(it) }
    val shown = capsules.take(2)
    val more = capsules.size - shown.size
    val spoken = capsules.map { it.spoken(st, now, fmt) }
    val record = stringResource(R.string.today_record)
    val description = if (spoken.isEmpty()) record else stringResource(R.string.today_record_running_cd, spoken.joinToString(". "))
    Surface(
        onClick = { onClick(st.noEnd.firstOrNull()?.event?.id) },
        shape = RoundedCornerShape(16.dp),
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        shadowElevation = 3.dp,
        modifier = modifier
            .heightIn(min = 56.dp)
            .widthIn(max = maxWidth)
            .semantics { contentDescription = description },
    ) {
        Row(
            Modifier.clearAndSetSemantics {}.padding(start = if (shown.isEmpty()) 16.dp else 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in shown) c.Draw(st, now, fmt)
            // The timers that don't fit, in a circle like the capsules' fill.
            if (more > 0) {
                Box(
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.today_more_timers, more), style = MoStor.type.button.copy(fontSize = 14.sp), maxLines = 1)
                }
            }
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(22.dp))
            if (shown.size <= 1) Text(record, style = MoStor.type.button, maxLines = 1)
        }
    }
}

private sealed interface Capsule {
    @Composable
    fun Draw(st: HomeState, now: Long, fmt: Formats)

    @Composable
    fun spoken(st: HomeState, now: Long, fmt: Formats): String

    /** A timer: its record icon (with the child's dot when there are several) and its clock. */
    data class Timer(val r: RunningTimer) : Capsule {
        @Composable
        override fun Draw(st: HomeState, now: Long, fmt: Formats) {
            val t = r.timed
            val onPrimary = MaterialTheme.colorScheme.onPrimary
            Row(
                Modifier.height(36.dp).background(onPrimary.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    val icon = if (t.event.kind == Kind.BREAST) KindIcons.of(t.lastSide ?: Side.LEFT) else KindIcons.of(Kind.SLEEP)
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                    st.childOf(t.event.childId)?.let { c ->
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(3.dp, 3.dp)
                                .size(9.dp)
                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .background(childColor(c, st.colorIndex(c.id)), CircleShape),
                        )
                    }
                }
                Text(fmt.elapsed(clockMs(t, now)), style = MoStor.type.tabular.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
                if (t.isPaused) Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }

        @Composable
        override fun spoken(st: HomeState, now: Long, fmt: Formats): String {
            val t = r.timed
            val d = spokenDuration(clockMs(t, now))
            val side = fmt.sideLong(t.lastSide ?: Side.LEFT).lowercase()
            val what = when {
                t.event.kind != Kind.BREAST -> stringResource(R.string.today_cd_asleep, d)
                t.isPaused -> stringResource(R.string.today_cd_paused, side, d)
                else -> stringResource(R.string.today_cd_feeding, side, d)
            }
            return withChild(st, t.event.childId, fmt, what)
        }
    }

    /** A timer whose end is unknown: amber, with the overdue mark and "Set sleep end" / "Set feed end". */
    data class NoEnd(val t: TimedEvent) : Capsule {
        @Composable
        override fun Draw(st: HomeState, now: Long, fmt: Formats) {
            Row(
                Modifier.height(36.dp).background(MoStor.colors.noEnd, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_overdue), contentDescription = null, tint = MoStor.colors.onNoEnd, modifier = Modifier.size(18.dp))
                Text(label(), style = MoStor.type.button, color = MoStor.colors.onNoEnd, maxLines = 1)
            }
        }

        @Composable
        private fun label() = stringResource(if (t.event.kind == Kind.SLEEP) R.string.today_set_sleep_end else R.string.today_set_feed_end)

        @Composable
        override fun spoken(st: HomeState, now: Long, fmt: Formats): String = withChild(st, t.event.childId, fmt, label())
    }
}

/** A feed's feeding time (frozen while paused), or a sleep's time since it started. */
private fun clockMs(t: TimedEvent, now: Long): Long =
    if (t.event.kind == Kind.BREAST) Durations.breast(t.segments, now).totalMs else (now - t.event.startAt).coerceAtLeast(0)

@Composable
private fun withChild(st: HomeState, childId: String, fmt: Formats, what: String): String =
    st.childOf(childId)?.let { stringResource(R.string.today_cd_child, fmt.childName(it.name), what) } ?: what

/** "12 minutes", "1 hour 5 minutes", for TalkBack. */
@Composable
private fun spokenDuration(ms: Long): String {
    val r = LocalContext.current.resources
    val e = Elapsed.of(ms)
    val minutes = r.getQuantityString(R.plurals.cd_minutes, e.minutes, e.minutes)
    return if (e.hours > 0) r.getQuantityString(R.plurals.cd_hours, e.hours.toInt(), e.hours) + " " + minutes else minutes
}
