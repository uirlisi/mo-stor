package io.github.uirlisi.mostor.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.ui.MoStorSwitch
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EditProblem
import io.github.uirlisi.mostor.core.EventDraft
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.PickedTime
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.TimeField
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.sheet.BigButton
import io.github.uirlisi.mostor.sheet.ChipButton
import io.github.uirlisi.mostor.sheet.TimePickDialog
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.ChildPicker
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.childOptions
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val MIN = 60_000L

/**
 * Edit a record, or add one after the fact (SPEC §6.5 "Edit event"). Built for one hand: the
 * times are chips that open a nudge panel at the bottom ([−5] [−1] [+1] [+5], [Pick time…]), and
 * [Save] is pinned at the bottom. Nothing is written until Save, which is one undoable action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(ui: AppUi, eventId: String?, newKind: Kind?) {
    val repo = ui.repository
    val fmt = ui.fmt
    val zone = remember { ZoneId.systemDefault() }
    val state = rememberEditState(ui, eventId, newKind)

    val d = state.draft
    val st = state.home
    val dirty = state.dirty
    BackHandler(enabled = dirty) { state.discardAsk = true }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Title bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
            IconButton(onClick = state::leave, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.edit_back))
            }
            val kind = d?.kind ?: newKind
            Text(
                if (kind == null) "" else stringResource(if (eventId == null) R.string.add_title else R.string.edit_title, fmt.what(kind, null, null)),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (state.gone) {
            Text(stringResource(R.string.edit_gone), modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (d == null || st == null) return@Column
        // The clock ticks every 15 s; a time set "now" must never read as later than now.
        val problems = EventEdit.problems(d, maxOf(state.now, System.currentTimeMillis()))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(summary(d, fmt, state.now), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.running) Text(stringResource(R.string.edit_running), style = MaterialTheme.typography.bodyMedium)

            // Child: only when there are several (and never moves a running timer).
            if (st.severalChildren && !d.running) {
                Label(R.string.edit_child)
                ChildField(st, d.childId, fmt) { state.draft = d.copy(childId = it) }
            }

            // Date: moves the whole record, keeping its clock times.
            if (!d.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(R.string.edit_date, Modifier.width(96.dp))
                    ChipButton(fmt.date(d.start), Modifier.widthIn(min = 160.dp)) { state.pickingDate = true }
                }
            }

            when (d.kind) {
                Kind.BREAST -> SegmentsEditor(d, fmt, state.now, problems, state.field, onField = { state.field = it }, onChange = { state.draft = it })
                Kind.SLEEP -> {
                    TimeRow(R.string.edit_start, d.startAt, d, fmt, state.field == TimeField.Start) { state.field = TimeField.Start }
                    if (!d.running && !d.endUnknown) {
                        val end = d.endAt
                        if (end != null) TimeRow(R.string.edit_end, end, d, fmt, state.field == TimeField.End) { state.field = TimeField.End }
                    }
                }
                else -> {
                    TimeRow(R.string.edit_time, d.startAt, d, fmt, state.field == TimeField.Start) { state.field = TimeField.Start }
                    PointFields(d, st, fmt, state.field, onField = { state.field = it }, onChange = { state.draft = it })
                }
            }

            // End unknown (timed records that aren't running).
            if (d.kind.timed && !d.running) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.edit_end_unknown), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.edit_end_unknown_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    MoStorSwitch(
                        checked = d.endUnknown,
                        onCheckedChange = { unknown ->
                            if (unknown) {
                                state.draft = EventEdit.setEndUnknown(d, true)
                                state.field = null
                            } else {
                                // Back to a known end: start from the original end (or the last start) and let the owner set it.
                                val base = state.initial?.end ?: d.segments.lastOrNull()?.startAt ?: d.startAt
                                state.draft = EventEdit.setEndUnknown(d, false, minOf(base, state.now))
                                state.field = TimeField.End
                            }
                        },
                    )
                }
            }

            // Note (the main text for a note record).
            OutlinedTextField(
                value = d.note.orEmpty(),
                onValueChange = { state.draft = d.copy(note = it) },
                label = { Text(stringResource(R.string.edit_note)) },
                minLines = if (d.kind == Kind.NOTE) 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )

            // What still stops a Save, and overlaps (allowed, only highlighted).
            for (p in problems.filter { it.segment == null }) ProblemText(p)
            for (o in state.overlaps.take(2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        stringResource(R.string.edit_overlaps, fmt.what(o.kind, o.subtype, o.supplementName).lowercase(), fmt.time(o.startAt)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.heightIn(min = 8.dp))
        }

        // Thumb zone: the nudge panel for the selected time, else Delete and Save.
        HorizontalDivider()
        val f = state.field
        val value = f?.let { EventEdit.value(d, it) }
        if (f != null && value != null) {
            NudgePanel(
                title = fieldTitle(f),
                value = timeLabel(value, d, fmt),
                onNudge = { minutes -> state.draft = EventEdit.nudge(d, f, minutes * MIN, System.currentTimeMillis()) },
                onPick = { state.picking = true },
                onDone = { state.field = null },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (eventId != null) {
                    OutlinedButton(onClick = {
                        ui.act({ repo.delete(eventId) }) { ui.back() }
                    }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.edit_delete))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { state.save(d) },
                    enabled = dirty && problems.isEmpty() && !state.saving,
                    modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 140.dp),
                ) {
                    Text(stringResource(R.string.edit_save), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (state.picking) {
        val f = state.field
        val value = f?.let { state.draft?.let { dd -> EventEdit.value(dd, it) } }
        if (f == null || value == null) {
            state.picking = false
        } else {
            TimePickDialog(
                title = stringResource(fieldTitle(f)),
                initial = value,
                onDismiss = { state.picking = false },
                onPicked = { lt ->
                    state.picking = false
                    state.draft = state.draft?.let { EventEdit.set(it, f, PickedTime.nearest(lt, value, zone)) }
                },
            )
        }
    }
    if (state.pickingDate) {
        state.draft?.let { dd ->
            DatePick(dd.start, zone, onDismiss = { state.pickingDate = false }) { date ->
                state.pickingDate = false
                state.draft = EventEdit.moveToDate(dd, date, zone)
            }
        }
    }
    if (state.discardAsk) {
        AlertDialog(
            onDismissRequest = { state.discardAsk = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    state.discardAsk = false
                    ui.back()
                }) { Text(stringResource(R.string.edit_discard)) }
            },
            dismissButton = { TextButton(onClick = { state.discardAsk = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

internal fun withPreset(d: EventDraft, p: SupplementPreset) =
    d.copy(supplementPresetId = p.id, supplementName = p.name, supplementAmount = p.amount, supplementUnit = p.unit)

/** "Wed, 11 Nov · 14:02 · 25m (L 15m · R 10m)". */
@Composable
private fun summary(d: EventDraft, fmt: Formats, now: Long): String {
    val parts = mutableListOf(fmt.date(d.start), fmt.time(d.start))
    when (d.kind) {
        Kind.BREAST -> {
            var left = 0L
            var right = 0L
            for (s in d.segments) {
                val ms = ((s.endAt ?: if (d.running) now else s.startAt) - s.startAt).coerceAtLeast(0)
                if (s.side == Side.LEFT) left += ms else right += ms
            }
            if (!d.endUnknown) {
                parts += fmt.duration(left + right) + " (" + listOfNotNull(
                    if (d.segments.any { it.side == Side.LEFT }) fmt.sideShort(Side.LEFT) + " " + fmt.duration(left) else null,
                    if (d.segments.any { it.side == Side.RIGHT }) fmt.sideShort(Side.RIGHT) + " " + fmt.duration(right) else null,
                ).joinToString(" · ") + ")"
            }
        }
        Kind.SLEEP -> (d.end ?: if (d.running) now else null)?.let { parts += fmt.duration(it - d.startAt) }
        else -> Unit
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Label(res: Int, modifier: Modifier = Modifier) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The record's child: the single-choice picker (Phase 3c). */
@Composable
private fun ChildField(st: HomeState, selected: String, fmt: Formats, onSelect: (String) -> Unit) {
    val res = LocalResources.current
    ChildPicker(
        childOptions(st.children, fmt, st::colorIndex), selected,
        contentDescription = { res.getString(R.string.picker_child_cd, it) },
        onSelect = onSelect,
    )
}

/** A time as a chip, with "(+1 day)" when it falls on another day than the record's start. */
private fun timeLabel(ms: Long, d: EventDraft, fmt: Formats): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val startDay = Instant.ofEpochMilli(d.start).atZone(zone).toLocalDate()
    return when {
        day.isAfter(startDay) -> fmt.timeWithDay(ms, R.string.edit_next_day)
        day.isBefore(startDay) -> fmt.timeWithDay(ms, R.string.edit_prev_day)
        else -> fmt.time(ms)
    }
}

@Composable
private fun TimeRow(label: Int, ms: Long, d: EventDraft, fmt: Formats, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(96.dp))
        TimeChip(timeLabel(ms, d, fmt), selected, Modifier.widthIn(min = 160.dp), onClick)
    }
}

@Composable
private fun TimeChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
    }
}

/** The breast segments: side, start → end, duration, remove; the pauses between them; + Add segment. */
@Composable
private fun SegmentsEditor(
    d: EventDraft,
    fmt: Formats,
    now: Long,
    problems: List<EditProblem>,
    field: TimeField?,
    onField: (TimeField) -> Unit,
    onChange: (EventDraft) -> Unit,
) {
    d.segments.forEachIndexed { i, s ->
        if (i > 0) {
            val prevEnd = d.segments[i - 1].endAt
            if (prevEnd != null && s.startAt > prevEnd) {
                Text(
                    stringResource(R.string.edit_pause, fmt.duration(s.startAt - prevEnd)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val mine = problems.filter { it.segment == i }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = if (mine.isNotEmpty()) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (side in Side.entries) {
                        BigButton(fmt.sideShort(side), Modifier.width(64.dp), selected = s.side == side) {
                            onChange(EventEdit.setSide(d, i, side))
                        }
                    }
                    val end = s.endAt ?: if (d.running) now else null
                    Text(
                        end?.let { fmt.duration(it - s.startAt) }.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                    if (d.segments.size > 1) {
                        IconButton(onClick = { onChange(EventEdit.removeSegment(d, i)) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.edit_remove_segment, i + 1))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeChip(timeLabel(s.startAt, d, fmt), field == TimeField.SegmentStart(i), Modifier.weight(1f)) {
                        onField(TimeField.SegmentStart(i))
                    }
                    Text("→", style = MaterialTheme.typography.titleMedium)
                    val segEnd = s.endAt
                    when {
                        segEnd != null -> TimeChip(timeLabel(segEnd, d, fmt), field == TimeField.SegmentEnd(i), Modifier.weight(1f)) {
                            onField(TimeField.SegmentEnd(i))
                        }
                        d.running -> Text(stringResource(R.string.edit_now), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        else -> Text("?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                for (p in mine) ProblemText(p)
            }
        }
    }
    if (d.isPaused) {
        d.segments.lastOrNull()?.endAt?.let {
            Text(stringResource(R.string.edit_paused_since, fmt.time(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (EventEdit.canAddSegment(d, now)) {
        OutlinedButton(onClick = { onChange(EventEdit.addSegment(d, System.currentTimeMillis())) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.edit_add_segment), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Subtype, amount, supplement and bottle end for point records. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointFields(
    d: EventDraft,
    st: HomeState,
    fmt: Formats,
    field: TimeField?,
    onField: (TimeField) -> Unit,
    onChange: (EventDraft) -> Unit,
) {
    val unit = st.settings.unit
    when (d.kind) {
        Kind.DIAPER -> {
            Label(R.string.edit_type)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in Diaper.entries) {
                    BigButton(fmt.diaper(t), Modifier.weight(1f), selected = d.subtype == t.code) { onChange(d.copy(subtype = t.code)) }
                }
            }
        }
        Kind.BOTTLE -> {
            Label(R.string.edit_content)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in BottleContent.entries) {
                    BigButton(fmt.bottleContent(c), Modifier.weight(1f), selected = d.subtype == c.code) { onChange(d.copy(subtype = c.code)) }
                }
            }
            Label(R.string.edit_amount)
            AmountStepper(d.amountMl, unit, fmt) { onChange(d.copy(amountMl = it)) }
            // A bottle can have an end (SPEC §4.2: "an end can be added in Edit").
            val end = d.endAt
            if (end == null) {
                TextButton(onClick = {
                    onChange(d.copy(endAt = d.startAt))
                    onField(TimeField.End)
                }) { Text(stringResource(R.string.edit_add_end)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeRow(R.string.edit_end, end, d, fmt, field == TimeField.End) { onField(TimeField.End) }
                    IconButton(onClick = { onChange(d.copy(endAt = null)) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.edit_remove_end))
                    }
                }
            }
        }
        Kind.PUMP -> {
            Label(R.string.edit_amount)
            AmountStepper(d.amountMl, unit, fmt) { onChange(d.copy(amountMl = it)) }
        }
        Kind.SUPPLEMENT -> {
            Label(R.string.edit_supplement)
            val presets = st.presets.filter { (it.childId == d.childId && st.settings.visibility.showsPreset(it.id)) || it.id == d.supplementPresetId }
            if (presets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in presets) {
                        BigButton(p.name, Modifier.widthIn(min = 96.dp), selected = p.id == d.supplementPresetId) { onChange(withPreset(d, p)) }
                    }
                }
            }
            // Given or skipped, for a med with a schedule (SPEC §5.10); As needed meds are never skipped.
            val scheduled = st.presets.firstOrNull { it.id == d.supplementPresetId }?.let { it.schedule != MedSchedule.AS_NEEDED } == true
            if (scheduled || d.skipped) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.meds_given), Modifier.weight(1f), selected = !d.skipped) { onChange(d.copy(skipped = false)) }
                    BigButton(stringResource(R.string.meds_skipped), Modifier.weight(1f), selected = d.skipped) { onChange(d.copy(skipped = true)) }
                }
            }
            // The record keeps its own copy of the name, amount and unit (SPEC §4.2).
            OutlinedTextField(
                value = d.supplementName.orEmpty(),
                onValueChange = { onChange(d.copy(supplementName = it.ifEmpty { null })) },
                label = { Text(stringResource(R.string.edit_supplement_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(d.supplementAmount, R.string.edit_supplement_amount, Modifier.weight(1f), resetKey = d.supplementPresetId) { onChange(d.copy(supplementAmount = it)) }
                OutlinedTextField(
                    value = d.supplementUnit.orEmpty(),
                    onValueChange = { onChange(d.copy(supplementUnit = it.ifEmpty { null })) },
                    label = { Text(stringResource(R.string.edit_supplement_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        else -> Unit
    }
}

/** A decimal field that keeps what is typed while it's being typed. */
@Composable
internal fun NumberField(value: Double?, label: Int, modifier: Modifier = Modifier, resetKey: Any? = null, onChange: (Double?) -> Unit) {
    // What is typed stays as typed ("1.", or more digits than the value keeps); [resetKey] changing
    // (another med preset) shows the value it brings.
    var text by rememberSaveable(resetKey) { mutableStateOf(value?.let { DecimalFormat("0.##").format(it) }.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            onChange(t.replace(',', '.').toDoubleOrNull())
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** [−] amount [+], in the phone's unit, on the same grid as the quick sheet's stepper. */
@Composable
internal fun AmountStepper(ml: Double?, unit: VolumeUnit, fmt: Formats, onChange: (Double) -> Unit) {
    val amount = ml?.let { Volume.snapped(it, unit) } ?: unit.firstAmount
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BigButton(stringResource(R.string.sheet_less), Modifier.widthIn(min = 72.dp)) {
            onChange(Volume.toMl((amount - unit.step).coerceAtLeast(unit.step), unit))
        }
        Text(
            fmt.volume(Volume.toMl(amount, unit), unit),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        BigButton(stringResource(R.string.sheet_more), Modifier.widthIn(min = 72.dp)) { onChange(Volume.toMl(amount + unit.step, unit)) }
    }
}

@Composable
private fun ProblemText(p: EditProblem) {
    Text(
        stringResource(
            when (p.type) {
                EditProblem.Type.NO_SEGMENTS -> R.string.problem_no_segments
                EditProblem.Type.END_BEFORE_START -> R.string.problem_end_before_start
                EditProblem.Type.OVERLAP -> R.string.problem_overlap
                EditProblem.Type.NO_END -> R.string.problem_no_end
                EditProblem.Type.OPEN_SEGMENT -> R.string.problem_open_segment
                EditProblem.Type.IN_FUTURE -> R.string.problem_in_future
                EditProblem.Type.NO_AMOUNT -> R.string.problem_no_amount
                EditProblem.Type.EMPTY_NOTE -> R.string.problem_empty_note
            },
        ),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun fieldTitle(f: TimeField): Int = when (f) {
    TimeField.Start -> R.string.edit_start
    TimeField.End -> R.string.edit_end
    is TimeField.SegmentStart -> R.string.edit_start
    is TimeField.SegmentEnd -> R.string.edit_end
}

/** The nudge panel at the bottom of the screen, within thumb reach. */
@Composable
private fun NudgePanel(title: Int, value: String, onNudge: (Int) -> Unit, onPick: () -> Unit, onDone: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(-5, -1, 1, 5)) {
                    BigButton(
                        if (m < 0) stringResource(R.string.minus_minutes, -m) else stringResource(R.string.edit_plus_minutes, m),
                        Modifier.weight(1f),
                    ) { onNudge(m) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_pick)) }
                Button(onClick = onDone, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_done)) }
            }
        }
    }
}

/** Picks a date up to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePick(current: Long, zone: ZoneId, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val today = LocalDate.now(zone)
    val initial = Instant.ofEpochMilli(current).atZone(zone).toLocalDate()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state)
    }
}
