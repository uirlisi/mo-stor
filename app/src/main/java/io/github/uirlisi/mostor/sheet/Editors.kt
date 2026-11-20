package io.github.uirlisi.mostor.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.Verb
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.MoStor

/** The footer: today's counts and the last sync ([synced], live), the note button and [Open app]. */
@Composable
internal fun Footer(summary: String, synced: () -> String?, showNote: Boolean, noteOpen: Boolean, onNote: () -> Unit, onOpenApp: () -> Unit) {
    val s = MaterialTheme.colorScheme
    HorizontalDivider(color = s.outlineVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Today's counts, and under them the last sync with another phone (Phase 5).
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = s.onSurfaceVariant)
            synced()?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = s.onSurfaceVariant) }
        }
        if (showNote) {
            IconButton(onClick = onNote, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_note),
                    contentDescription = stringResource(R.string.sheet_note_cd),
                    tint = if (noteOpen) s.primary else s.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        TextButton(onClick = onOpenApp) { Text(stringResource(R.string.sheet_open_app), style = MoStor.type.button) }
    }
}

@Composable
internal fun ConfirmationStrip(
    s: Strip,
    unit: VolumeUnit,
    fmt: Formats,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    onEarlier: (Int) -> Unit,
    onPick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = fmt.action(s.done.info, unit) + " · " + fmt.time(s.done.info.at)
                val text = when (s.outcome) {
                    Strip.Outcome.DONE -> label
                    Strip.Outcome.UNDONE -> stringResource(R.string.sheet_undone, label)
                    Strip.Outcome.UNDO_REFUSED -> stringResource(R.string.sheet_undo_refused)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                // A due or late med's dose can be turned into a skip (SPEC §5.10).
                if (s.outcome == Strip.Outcome.DONE && s.skippable) {
                    ChipButton(stringResource(R.string.meds_skip), Modifier.padding(end = 8.dp).widthIn(min = 64.dp), border = MoStor.colors.stripOutline) { onSkip() }
                }
                if (s.outcome == Strip.Outcome.DONE) {
                    Button(onClick = onUndo, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_undo), style = MoStor.type.button)
                    }
                }
            }
            val isStart = s.done.info.verb in ADJUSTABLE
            if (isStart && s.outcome == Strip.Outcome.DONE) {
                Text(
                    stringResource(
                        when (s.done.info.verb) {
                            Verb.PAUSE -> R.string.sheet_paused_earlier
                            Verb.RESUME -> R.string.sheet_resumed_earlier
                            Verb.SWITCH -> R.string.sheet_switched_earlier
                            else -> R.string.sheet_started_earlier
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MoStor.colors.onStripVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val edge = MoStor.colors.stripOutline
                    for (m in START_OFFSETS) ChipButton(stringResource(R.string.minus_minutes, m), Modifier.weight(1f), border = edge) { onEarlier(m) }
                    ChipButton(stringResource(R.string.sheet_pick_time), Modifier.weight(1f), border = edge) { onPick() }
                }
            }
        }
    }
}

@Composable
internal fun BottleEditor(st: HomeState, fmt: Formats, contents: List<BottleContent>, onSave: (BottleContent, Double) -> Unit) {
    var content by rememberSaveable { mutableStateOf(st.settings.lastBottleContent.takeIf { it in contents } ?: contents.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The content toggle only when both breast milk and formula are shown on this phone (SPEC §6.3).
        if (contents.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in contents) {
                    BigButton(fmt.bottleContent(c), Modifier.weight(1f), selected = content == c) { content = c }
                }
            }
        }
        AmountEditor(st.settings.bottleStartMl, st.settings.unit, fmt) { ml -> onSave(content, ml) }
    }
}

/** An amount stepper that starts at the last amount (or a round first value), then [Save]. */
@Composable
internal fun AmountEditor(lastMl: Double?, unit: VolumeUnit, fmt: Formats, onSave: (Double) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(lastMl?.let { Volume.snapped(it, unit) } ?: unit.firstAmount) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BigButton(stringResource(R.string.sheet_less), Modifier.widthIn(min = 64.dp)) {
            amount = (amount - unit.step).coerceAtLeast(unit.step)
        }
        Text(
            fmt.volume(Volume.toMl(amount, unit), unit),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        BigButton(stringResource(R.string.sheet_more), Modifier.widthIn(min = 64.dp)) { amount += unit.step }
        BigButton(stringResource(R.string.sheet_save), Modifier.widthIn(min = 88.dp), primary = true) { onSave(Volume.toMl(amount, unit)) }
    }
}

@Composable
internal fun NoteEditor(onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.sheet_note_hint)) },
            modifier = Modifier.weight(1f),
        )
        BigButton(stringResource(R.string.sheet_save), Modifier.widthIn(min = 88.dp), primary = true) { if (text.isNotBlank()) onSave(text) }
    }
}

private val START_OFFSETS = listOf(5, 10, 15)

/** Actions whose time the strip's "Earlier" chips can move. */
private val ADJUSTABLE = setOf(Verb.START, Verb.SWITCH, Verb.PAUSE, Verb.RESUME)
