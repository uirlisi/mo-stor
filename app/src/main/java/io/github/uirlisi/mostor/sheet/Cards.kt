package io.github.uirlisi.mostor.sheet

import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.uirlisi.mostor.ui.TimerTint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.chartColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.uirlisi.mostor.ui.caps
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import io.github.uirlisi.mostor.ui.RecordIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.PickedTime
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.data.Done
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.data.RunningTimer
import io.github.uirlisi.mostor.data.Source
import io.github.uirlisi.mostor.ui.ChildTag
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.FitText
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.childColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import java.time.LocalTime
import java.time.ZoneId

internal val STOP_OFFSETS = listOf(5, 10, 20, 30)

/** What a screen showing [TimerCards] provides: how it runs a write, and how it asks for a time. */
internal class TimerHost(
    /** Runs a write (on a scope that outlives the screen) and confirms it with Undo. */
    val act: (suspend () -> Done?) -> Unit,
    /** Opens the time picker. */
    val pickTime: (titleRes: Int, initial: Long, onPicked: (LocalTime) -> Unit) -> Unit,
    /** Runs a write that needs no confirmation ([Still going], "Leave unknown"). */
    val quietly: (suspend () -> Unit) -> Unit,
)

/**
 * The "has no end" cards (the focused one first) and a card per running timer, with all their
 * controls. The quick sheet and the Today screen both show them.
 */
@Composable
internal fun TimerCards(
    st: HomeState,
    now: Long,
    fmt: Formats,
    repository: Repository,
    host: TimerHost,
    focusEventId: String? = null,
) {
    val zone = ZoneId.systemDefault()
    for (k in st.conflicts) {
        ConflictCard(
            k, fmt, st.childOf(k.childId)?.let { fmt.childName(it.name) to childColor(it, st.colorIndex(it.id)) },
            onKeep = { keep -> host.act { repository.resolveConflict(k.key, keep, Source.SHEET) } },
        )
    }
    for (t in st.noEnd.sortedByDescending { it.event.id == focusEventId }) {
        NoEndCard(
            t, fmt, st.childOf(t.event.childId)?.let { fmt.childName(it.name) to childColor(it, st.colorIndex(it.id)) },
            onSetEnd = {
                host.pickTime(R.string.sheet_pick_end, t.event.startAt) { lt ->
                    val at = minOf(PickedTime.earliestAtOrAfter(lt, t.event.startAt, zone), System.currentTimeMillis())
                    host.act { repository.stop(t.event.id, Source.SHEET, at = at) }
                }
            },
            onLeave = { host.quietly { repository.dismissNoEnd(t.event.id) } },
        )
    }
    for (r in st.running) {
        val id = r.timed.event.id
        RunningCard(
            r, now, fmt, st.childOf(r.timed.event.childId)?.let { fmt.childName(it.name) to childColor(it, st.colorIndex(it.id)) },
            onStop = { m -> host.act { repository.stop(id, Source.SHEET, minutesAgo = m) } },
            onPickStop = {
                host.pickTime(R.string.sheet_pick_end, System.currentTimeMillis()) { lt ->
                    val at = PickedTime.latestAtOrBefore(lt, System.currentTimeMillis(), zone)
                    host.act { repository.stop(id, Source.SHEET, at = at) }
                }
            },
            onSwitch = { host.act { repository.switchSide(id, Source.SHEET) } },
            onPause = { host.act { repository.pause(id, Source.SHEET) } },
            onResume = { host.act { repository.resume(id, Source.SHEET) } },
            onStillGoing = { host.quietly { repository.stillGoing(id) } },
            onCancel = { host.act { repository.cancel(id, Source.SHEET) } },
        )
    }
}

/**
 * The record button (docs/PHASE3B_HANDOFF.md): neutral fill with a 1 dp border, radius 16, a 24 dp
 * icon above the word (64 dp tall) or before it (56 dp, a wide button). [primary] is the action
 * button: the action color, no border; there is only ever one per state. [selected] outlines the
 * button in [accent] (a record's color; the action color by default) over an 8% tint of it;
 * [outlined] gives the outline without the tint. [badge] sits in the top-right corner ("NEXT").
 * [container] replaces the neutral fill (the running card's controls).
 */
@Composable
internal fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    outlined: Boolean = false,
    /** A record icon, above the word ([iconAbove]) or before it. */
    icon: Int? = null,
    /** The icon's color (an icon tone, [ChartColors.icon]); the action button uses its text color. */
    iconTint: Color? = null,
    iconAbove: Boolean = true,
    accent: Color? = null,
    badge: String? = null,
    container: Color? = null,
    /** Replaces the neutral 1 dp border (the running card's controls). */
    edge: Color? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val s = MaterialTheme.colorScheme
    val above = icon != null && iconAbove
    val mark = accent ?: s.primary
    val fill = container ?: s.surfaceContainerHigh
    val colors = when {
        primary -> ButtonDefaults.buttonColors(containerColor = s.primary, contentColor = s.onPrimary)
        selected -> ButtonDefaults.buttonColors(containerColor = mark.copy(alpha = 0.08f).compositeOver(fill), contentColor = s.onSurface)
        else -> ButtonDefaults.buttonColors(containerColor = fill, contentColor = s.onSurface)
    }
    val border = when {
        primary -> null
        selected || outlined -> BorderStroke(1.5.dp, mark)
        else -> BorderStroke(1.dp, edge ?: s.outlineVariant)
    }
    // The button takes the wrapper's minimum size: it fills a weighted or full-width slot, and is as wide
    // as its content (at least the given minimum) in a row of other things, like the [−] [+] steppers.
    Box(modifier, propagateMinConstraints = true) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = if (above) 64.dp else 56.dp)
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
            shape = RoundedCornerShape(16.dp),
            colors = colors,
            border = border,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (above) 6.dp else 0.dp),
        ) {
            val text = @Composable {
                FitText(label, MoStor.type.button, textAlign = TextAlign.Center)
            }
            if (icon == null) {
                text()
            } else {
                val tint = if (primary || iconTint == null) LocalContentColor.current else iconTint
                if (above) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RecordIcon(icon, tint, 24.dp)
                        text()
                    }
                } else {
                    RecordIcon(icon, tint, 24.dp, Modifier.padding(end = 8.dp))
                    text()
                }
            }
        }
        if (badge != null) {
            // Its own box: the outer one hands its minimum size on, which would stretch the badge.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopEnd) {
                Text(
                    caps(badge),
                    style = MoStor.type.badge,
                    color = if (mark.luminance() > 0.4f) Color(0xFF16171A) else Color.White,
                    modifier = Modifier
                        .padding(top = 6.dp, end = 6.dp)
                        .clearAndSetSemantics {}
                        .background(mark, RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * A time chip (−5, −10 … Time…): 44 dp tall to look at (48 dp to touch), radius 12, a 1 dp border,
 * equal widths in a row. On the confirmation strip the border is [border] (`stripOutline`); on a
 * running card, [border] and [fill] are its record's tint.
 */
@Composable
internal fun ChipButton(label: String, modifier: Modifier = Modifier, border: Color? = null, fill: Color = Color.Transparent, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, border ?: MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = LocalContentColor.current),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        FitText(label, MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * A running timer (SPEC §6.3; layout from docs/PHASE3B_HANDOFF.md, "Running card"): the clock as
 * its headline, the notification's controls in its order on the sheet's 3-column grid, then the
 * late-stop chips. Exactly one button is action-colored: [Stop] while feeding, [Resume] while
 * paused, [Woke up] while asleep.
 */
@Composable
private fun RunningCard(
    r: RunningTimer,
    now: Long,
    fmt: Formats,
    /** The child's name and color, only with several children. */
    child: Pair<String, Color>?,
    onStop: (Int) -> Unit,
    onPickStop: () -> Unit,
    onSwitch: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStillGoing: () -> Unit,
    onCancel: () -> Unit,
) {
    val t = r.timed
    val e = t.event
    val breast = e.kind == Kind.BREAST
    val paused = t.isPaused
    val colors = chartColors()
    val scheme = MaterialTheme.colorScheme
    val side = t.lastSide ?: Side.LEFT
    // The card is the one live thing on the sheet: it takes its record's color (Phase 3c).
    val tint = if (breast) MoStor.colors.feedTimer else MoStor.colors.sleepTimer
    TimerCard(tint) {
        // Header: the record's disc, what's running and since when, and the clock.
        val sides = if (breast) Durations.breast(t.segments, now) else null
        CardHeader(
            icon = if (breast) KindIcons.of(side) else KindIcons.of(Kind.SLEEP),
            tint = colors.icon(if (breast) colors.left else colors.sleep),
            disc = tint.disc,
            title = when {
                paused -> stringResource(R.string.sheet_feed_paused, fmt.sideLong(side))
                breast -> stringResource(R.string.notif_feeding, fmt.sideLong(side))
                else -> stringResource(R.string.notif_asleep)
            },
            // Pieces that never break inside themselves; the line wraps between them.
            subtitle = listOfNotNull(
                t.pausedAt?.let { stringResource(R.string.timer_paused_since, fmt.time(it)) }
                    ?: stringResource(R.string.sheet_since, fmt.time(e.startAt)),
                sides?.let { fmt.sideShort(Side.LEFT) + " " + fmt.duration(it.leftMs) },
                sides?.let { fmt.sideShort(Side.RIGHT) + " " + fmt.duration(it.rightMs) },
            ),
            live = if (paused) LiveMark(stringResource(R.string.timer_paused_mark), scheme.onSurfaceVariant, pulse = false)
            else LiveMark(stringResource(R.string.timer_live), tint.live, pulse = true),
            child = child,
        ) {
            // A feed counts its feeding time only, so the clock stands still while paused.
            Text(
                fmt.elapsed(sides?.totalMs ?: (now - e.startAt)),
                style = MoStor.type.clock,
                color = if (paused) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
            )
        }
        if (r.overrun == OverrunState.NUDGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (breast) R.string.sheet_still_feeding else R.string.sheet_still_asleep),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStillGoing) { Text(stringResource(R.string.action_still_going), style = MoStor.type.button) }
            }
        }
        // The notification's order: [Switch side] [Pause]/[Resume] [Stop]; a sleep has [Woke up] only.
        if (breast) {
            val control = tint.control
            val edge = tint.controlBorder
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(
                    stringResource(R.string.sheet_switch_side), Modifier.weight(1f), icon = R.drawable.ic_switch_side,
                    iconTint = scheme.onSurface, container = control, edge = edge,
                ) { onSwitch() }
                if (paused) {
                    BigButton(stringResource(R.string.action_resume), Modifier.weight(1f), primary = true, icon = R.drawable.ic_resume) { onResume() }
                } else {
                    BigButton(
                        stringResource(R.string.action_pause), Modifier.weight(1f), icon = R.drawable.ic_pause,
                        iconTint = scheme.onSurface, container = control, edge = edge,
                    ) { onPause() }
                }
                BigButton(
                    stringResource(R.string.sheet_stop), Modifier.weight(1f), primary = !paused, icon = R.drawable.ic_stop,
                    iconTint = scheme.onSurface, container = control, edge = edge,
                ) { onStop(0) }
            }
        } else {
            BigButton(stringResource(R.string.sheet_woke_up), Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_awake, iconAbove = false) { onStop(0) }
        }
        // Late stop: "Stopped earlier?" and Cancel, then the chips.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (breast) R.string.sheet_ended_earlier else R.string.sheet_woke_earlier),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) {
                    Text(
                        stringResource(if (breast) R.string.sheet_cancel_feed else R.string.sheet_cancel_sleep),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in STOP_OFFSETS) {
                    ChipButton(stringResource(R.string.minus_minutes, m), Modifier.weight(1f), border = tint.chipBorder, fill = tint.chip) { onStop(m) }
                }
                ChipButton(stringResource(R.string.sheet_pick_time), Modifier.weight(1f), border = tint.chipBorder, fill = tint.chip) { onPickStop() }
            }
        }
    }
}

/** A timer whose end is unknown: the same card, with [Set end…] (the action) and [Leave unknown]. */
/**
 * Two (or more) timers of one kind running for one child after a sync (SPEC §9.5): which counts?
 * Never decided by the app. "Keep both" leaves them; the others cancel one, with Undo.
 */
@Composable
private fun ConflictCard(
    k: io.github.uirlisi.mostor.core.TimerConflict,
    fmt: Formats,
    child: Pair<String, Color>?,
    onKeep: (io.github.uirlisi.mostor.core.Keep) -> Unit,
) {
    val colors = chartColors()
    val times = k.timers.map { fmt.time(it.event.startAt) }
    TimerCard {
        CardHeader(
            icon = if (k.kind == Kind.BREAST) KindIcons.of(Side.LEFT) else KindIcons.of(k.kind),
            tint = colors.icon(if (k.kind == Kind.BREAST) colors.left else colors.sleep),
            title = stringResource(R.string.conflict_title, k.timers.size, fmt.timedKind(k.kind).lowercase()),
            // Two pieces, so the line wraps between them rather than cutting the question off.
            subtitle = listOf(
                stringResource(R.string.conflict_starts, times.dropLast(1).joinToString(", "), times.last()),
                stringResource(R.string.conflict_question),
            ),
            child = child,
            mark = R.drawable.ic_overdue,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(stringResource(R.string.conflict_keep_earlier), Modifier.weight(1f)) { onKeep(io.github.uirlisi.mostor.core.Keep.EARLIER) }
            BigButton(stringResource(R.string.conflict_keep_later), Modifier.weight(1f)) { onKeep(io.github.uirlisi.mostor.core.Keep.LATER) }
        }
        TextButton(onClick = { onKeep(io.github.uirlisi.mostor.core.Keep.BOTH) }) {
            Text(stringResource(R.string.conflict_keep_both), style = MoStor.type.button)
        }
    }
}

@Composable
private fun NoEndCard(t: TimedEvent, fmt: Formats, child: Pair<String, Color>?, onSetEnd: () -> Unit, onLeave: () -> Unit) {
    val colors = chartColors()
    val kind = t.event.kind
    TimerCard {
        CardHeader(
            icon = if (kind == Kind.BREAST) KindIcons.of(t.lastSide ?: Side.LEFT) else KindIcons.of(kind),
            tint = colors.icon(if (kind == Kind.BREAST) colors.left else colors.sleep),
            title = stringResource(R.string.notif_no_end_title, fmt.timedKind(kind), fmt.time(t.event.startAt)),
            subtitle = emptyList(),
            child = child,
            mark = R.drawable.ic_overdue,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(stringResource(R.string.sheet_set_end), Modifier.weight(1f), primary = true, onClick = onSetEnd)
            TextButton(onClick = onLeave) { Text(stringResource(R.string.sheet_leave_unknown), style = MoStor.type.button) }
        }
    }
}

/**
 * The running card's shell: radius 20, padding 16, 14 dp between rows. A running timer's [tint]
 * washes it in its record's color with a 1 dp border of it (Phase 3c); without one (the "has no
 * end" card) it stays neutral, with a 1 dp border in light mode.
 */
@Composable
private fun TimerCard(tint: TimerTint? = null, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val look = when {
        tint != null -> Modifier.background(tint.wash.compositeOver(scheme.surface), shape).border(1.dp, tint.border, shape)
        MoStor.colors.dark -> Modifier.background(scheme.surfaceContainerHigh, shape)
        else -> Modifier.background(scheme.surfaceContainerHigh, shape).border(1.dp, scheme.outlineVariant, shape)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(look)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** The mark at the start of a running card's subtitle: "LIVE" with a pulsing dot, or "PAUSED", still. */
private class LiveMark(val label: String, val color: Color, val pulse: Boolean)

/**
 * A 40 dp disc ([disc], or the record's color at 12%) holding its icon, the title over the subtitle
 * (with the child's dot and name first when there are several), and [trailing] at the end (the
 * clock). The subtitle starts with the [live] mark; its pieces wrap between each other, never inside
 * one ("PM" never sits alone). [mark] (the "has no end" card) puts the amber mark beside the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardHeader(
    icon: Int,
    tint: Color,
    title: String,
    subtitle: List<String>,
    child: Pair<String, Color>?,
    disc: Color = tint.copy(alpha = 0.12f),
    live: LiveMark? = null,
    mark: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
            RecordIcon(icon, tint, 22.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            // The child's chip (as on Today's cards and timeline rows): a dot alone doesn't tell them apart.
            child?.let { (name, color) -> ChildTag(name, color, modifier = Modifier.padding(bottom = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mark?.let { RecordIcon(it, MoStor.colors.dueText, 16.dp) }
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            }
            if (live != null || subtitle.isNotEmpty()) {
                val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                    live?.let { LiveMarkView(it, Modifier.padding(end = 6.dp)) }
                    // A piece never breaks; one wider than a whole line (the largest fonts) shrinks.
                    subtitle.forEachIndexed { i, piece ->
                        FitText(
                            if (i < subtitle.lastIndex) "$piece · " else piece,
                            style,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * A 7 dp dot with a 3 dp halo of its color at 20%, 5 dp, then the word in CAPS (11 sp, bold). The
 * dot pulses (opacity 1 → 0.35 → 1 over 1.6 s) unless the phone's "Remove animations" is on.
 */
@Composable
private fun LiveMarkView(mark: LiveMark, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = mark.pulse && remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    Row(modifier.clearAndSetSemantics { contentDescription = mark.label }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(13.dp).background(mark.color.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            val dot = Modifier.size(7.dp)
            if (animate) {
                val alpha = rememberInfiniteTransition(label = "live").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse),
                    label = "live dot",
                )
                Box(dot.graphicsLayer { this.alpha = alpha.value }.background(mark.color, CircleShape))
            } else {
                Box(dot.background(mark.color, CircleShape))
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            caps(mark.label),
            style = MoStor.type.live,
            color = mark.color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
