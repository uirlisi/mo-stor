package io.github.uirlisi.mostor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.ui.ChildPicker
import io.github.uirlisi.mostor.ui.FitText
import io.github.uirlisi.mostor.ui.childOptions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.uirlisi.mostor.ui.MoStorSwitch
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.iconOf
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.of
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedPlan
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.plan
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.edit.NumberField
import io.github.uirlisi.mostor.ui.AppUi

/**
 * "What's shown on this phone" (SPEC §6.6): a switch for every kind, subtype and supplement
 * preset. Stored per phone; hidden items stay in the log.
 */
@Composable
fun ShownSettings(ui: AppUi) {
    val repo = ui.repository
    val fmt = ui.fmt
    val settings by remember { repo.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val presets by remember { repo.presetsFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val home by remember { repo.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    val v = settings?.visibility ?: return
    fun toggle(key: String, show: Boolean) = ui.quietly { repo.setSetting(key, if (show) null else "1") }
    val colors = chartColors()

    SubPage(ui, stringResource(R.string.set_shown)) {
        Hint(R.string.set_shown_hint)
        for (k in listOf(Kind.BREAST, Kind.BOTTLE, Kind.SUPPLEMENT, Kind.SLEEP, Kind.DIAPER, Kind.PUMP, Kind.NOTE)) {
            val label = if (k == Kind.BREAST) stringResource(R.string.set_shown_breast) else fmt.what(k, null, null)
            Section0(label, KindIcons.of(k) to colors.iconOf(k), v.shows(k)) { toggle(Visibility.key(k), it) }
            if (!v.shows(k)) continue
            val subs: List<Pair<String, String>> = when (k) {
                Kind.BOTTLE -> BottleContent.entries.map { it.code to fmt.bottleContent(it) }
                Kind.DIAPER -> Diaper.entries.map { it.code to fmt.diaper(it) }
                else -> emptyList()
            }
            for ((code, label) in subs) {
                Indented(label, KindIcons.of(k, code) to colors.iconOf(k, code), v.shows(k, code)) { toggle(Visibility.key(k, code), it) }
            }
            if (k == Kind.SUPPLEMENT) {
                // Each child has their own meds: with several children, each one says whose it is.
                val children = home?.children.orEmpty()
                val several = children.size > 1
                val ordered = presets.filter { p -> children.any { it.id == p.childId } }
                    .sortedBy { p -> children.indexOfFirst { it.id == p.childId } }
                for (p in ordered) {
                    val med = fmt.supplement(p.name, p.amount, p.unit)
                    val label = if (several) {
                        stringResource(R.string.set_shown_med_child, med, fmt.childName(children.first { it.id == p.childId }.name))
                    } else med
                    Indented(label, KindIcons.of(k) to colors.icon(colors.supplement), v.showsPreset(p.id)) {
                        toggle(Visibility.presetKey(p.id), it)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section0(label: String, icon: Pair<Int, Color>, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordIcon(icon.first, icon.second, 22.dp, Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        MoStorSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Indented(label: String, icon: Pair<Int, Color>, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordIcon(icon.first, icon.second, 18.dp, Modifier.padding(end = 10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        MoStorSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Settings › Meds (synced, SPEC §6.6): each child's meds, picked with the child switcher at the top,
 * with name, amount, unit, order and "How often" (shown only, SPEC §5.10).
 */
@Composable
fun PresetSettings(ui: AppUi) {
    val repo = ui.repository
    val fmt = ui.fmt
    val home by remember { repo.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    val all by remember { repo.presetsFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // The med being edited, by id (saveable: the dialog stays open through a rotation).
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val st = home ?: return
    val childId = picked?.takeIf { id -> st.children.any { it.id == id } } ?: st.childId
    val presets = all.filter { it.childId == childId }

    SubPage(ui, stringResource(R.string.set_supplements)) {
        if (st.severalChildren) {
            val res = LocalResources.current
            ChildPicker(
                childOptions(st.children, fmt, st::colorIndex), childId,
                contentDescription = { res.getString(R.string.picker_child_cd, it) },
            ) { id ->
                picked = id
            }
        }
        Hint(R.string.set_supplements_hint)
        presets.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { editingId = p.id }.padding(vertical = 8.dp)) {
                    Text(fmt.supplement(p.name, p.amount, p.unit), style = MaterialTheme.typography.titleMedium)
                    Text(planText(p.plan()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ui.act({ repo.movePreset(p.id, -1) }) }, enabled = i > 0, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_up), contentDescription = stringResource(R.string.preset_move_up))
                }
                IconButton(onClick = { ui.act({ repo.movePreset(p.id, 1) }) }, enabled = i < presets.lastIndex, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_down), contentDescription = stringResource(R.string.preset_move_down))
                }
                IconButton(onClick = { ui.act({ repo.deletePreset(p.id) }) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.preset_delete, p.name))
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.preset_add)) }
    }

    val target = editingId?.let { id -> all.firstOrNull { it.id == id } }
    if (adding || target != null) {
        PresetDialog(
            target,
            onDismiss = {
                adding = false
                editingId = null
            },
            onSave = { name, amount, unit, plan ->
                if (target == null) ui.act({ repo.addPreset(childId, name, amount, unit, plan) })
                else ui.act({ repo.editPreset(target.id, name, amount, unit, plan) })
                adding = false
                editingId = null
            },
        )
    }
}

/** "Daily", "Weekly · Saturday", "Every 3 days", "As needed". */
@Composable
private fun planText(plan: MedPlan): String = when (plan.schedule) {
    MedSchedule.DAILY -> stringResource(R.string.set_meds_daily)
    MedSchedule.WEEKLY -> stringResource(R.string.set_meds_weekly) + " · " + weekdayName(plan.weekday ?: 1, TextStyle.FULL)
    MedSchedule.EVERY_N_DAYS -> (plan.everyDays ?: 2).let { pluralStringResource(R.plurals.set_meds_every, it, it) }
    MedSchedule.AS_NEEDED -> stringResource(R.string.set_meds_as_needed)
}

private fun weekdayName(day: Int, style: TextStyle): String = DayOfWeek.of(day).getDisplayName(style, Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PresetDialog(p: SupplementPreset?, onDismiss: () -> Unit, onSave: (String, Double?, String?, MedPlan) -> Unit) {
    var name by rememberSaveable { mutableStateOf(p?.name.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(p?.amount) }
    var unit by rememberSaveable { mutableStateOf(p?.unit.orEmpty()) }
    val current = p?.plan() ?: MedPlan()
    var schedule by rememberSaveable { mutableStateOf(current.schedule) }
    var weekday by rememberSaveable { mutableStateOf(current.weekday ?: LocalDate.now().dayOfWeek.value) }
    var every by rememberSaveable { mutableStateOf(current.everyDays ?: 2) }
    // Every N days counts from a start the owner can pick (today by default); the others from when they are saved.
    var start by rememberSaveable { mutableStateOf(current.start?.takeIf { current.schedule == MedSchedule.EVERY_N_DAYS }) }
    var picking by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.preset_name)) }, singleLine = true)
                NumberField(amount, R.string.preset_amount) { amount = it }
                OutlinedTextField(unit, { unit = it }, label = { Text(stringResource(R.string.preset_unit)) }, singleLine = true)
                Text(stringResource(R.string.set_meds_how_often), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val choices = listOf(
                        MedSchedule.DAILY to R.string.set_meds_daily,
                        MedSchedule.WEEKLY to R.string.set_meds_weekly,
                        MedSchedule.EVERY_N_DAYS to R.string.set_meds_every_n,
                        MedSchedule.AS_NEEDED to R.string.set_meds_as_needed,
                    )
                    choices.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            modifier = Modifier.heightIn(min = 56.dp),
                            selected = schedule == value,
                            onClick = { schedule = value },
                            shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            icon = {},
                            // Two lines at most ("Every N / days"): the dialog is too narrow for four words in a row.
                            label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2) },
                        )
                    }
                }
                when (schedule) {
                    MedSchedule.WEEKLY -> {
                        Text(stringResource(R.string.set_meds_on), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (d in 1..7) {
                                val on = weekday == d
                                val s = MaterialTheme.colorScheme
                                // The full name for a screen reader: the narrow letters repeat (S, S; T, T).
                                val fullName = weekdayName(d, TextStyle.FULL)
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .background(if (on) s.primary else s.surfaceContainerHigh, CircleShape)
                                        .border(1.dp, if (on) s.primary else s.outlineVariant, CircleShape)
                                        .selectable(selected = on, role = Role.RadioButton) { weekday = d }
                                        .semantics { contentDescription = fullName },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        weekdayName(d, TextStyle.NARROW),
                                        color = if (on) s.onPrimary else s.onSurface,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.set_meds_weekly_hint, weekdayName(weekday, TextStyle.FULL)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MedSchedule.EVERY_N_DAYS -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { every = (every - 1).coerceAtLeast(2) }, enabled = every > 2) { Text(stringResource(R.string.sheet_less)) }
                            Text(pluralStringResource(R.plurals.set_meds_every, every, every), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { every += 1 }) { Text(stringResource(R.string.sheet_more)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.set_meds_starting), style = MaterialTheme.typography.titleSmall)
                            val shown = start?.let(LocalDate::parse) ?: LocalDate.now()
                            OutlinedButton(onClick = { picking = true }) {
                                Text(shown.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val plan = when (schedule) {
                        MedSchedule.WEEKLY -> MedPlan(schedule, weekday = weekday)
                        MedSchedule.EVERY_N_DAYS -> MedPlan(schedule, everyDays = every, start = start)
                        else -> MedPlan(schedule)
                    }
                    onSave(name, amount, unit.ifBlank { null }, plan)
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picking) {
        val initial = (start?.let(LocalDate::parse) ?: LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        start = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                    }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** About (SPEC §6.6): version, the missing Internet permission and how to check it, the app's license and source, libraries' licenses. */
@Composable
fun AboutPage(ui: AppUi) {
    val context = LocalContext.current
    val version = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    SubPage(ui, stringResource(R.string.set_about)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.about_version, version))
        Text(stringResource(R.string.about_no_internet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.about_no_internet))
        Text(stringResource(R.string.about_check), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_licences_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(namedString(R.string.about_free_software))
        Text(stringResource(R.string.about_licences), style = MaterialTheme.typography.bodyMedium)
    }
}
