package io.github.uirlisi.mostor.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.edit.DatePick
import io.github.uirlisi.mostor.edit.NumberField
import io.github.uirlisi.mostor.ui.CHILD_PALETTE
import io.github.uirlisi.mostor.ui.AppUi
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val G_PER_OZ = 28.349523125

/**
 * A child's details (SPEC §4.2, §6.6). Every field is optional; a child with no name shows as
 * "Baby". Weight and length are stored in g and mm, entered in kg/cm, or lb-oz/in when the phone
 * shows fluid ounces.
 */
@Composable
fun ChildEditor(ui: AppUi, childId: String?) {
    val repo = ui.repository
    val fmt = ui.fmt
    val children by remember { repo.childrenFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val settings by remember { repo.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val all = children ?: return
    val unit = settings?.unit ?: return
    val child = childId?.let { id -> all.firstOrNull { it.id == id } }
    if (childId != null && child == null) {
        // Deleted (with Undo in the snackbar): nothing left to edit here.
        LaunchedEffect(Unit) { ui.back() }
        return
    }
    val imperial = unit == VolumeUnit.FL_OZ

    // Saveable: what was typed survives a rotation, a dark-mode switch or the app being stopped.
    var name by rememberSaveable { mutableStateOf(child?.name.orEmpty()) }
    var birthDate by rememberSaveable { mutableStateOf(child?.birthDate) }
    var sex by rememberSaveable { mutableStateOf(child?.sex) }
    var weightG by rememberSaveable { mutableStateOf(child?.birthWeightG) }
    var lengthMm by rememberSaveable { mutableStateOf(child?.birthLengthMm) }
    // A child without a color of its own shows the one of its place in the list (childArgb); a new
    // child takes the next place's.
    val place = child?.let { c -> all.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: all.size
    var color by rememberSaveable { mutableStateOf(child?.color ?: CHILD_PALETTE[place.mod(CHILD_PALETTE.size)]) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val fields = ChildFields(name, birthDate, sex, weightG, lengthMm, color)
    // The last child that isn't archived must stay, so there's always one to record for.
    val isLastActive = child != null && child.archivedAt == null && all.none { it.id != child.id && it.archivedAt == null }

    SubPage(
        ui,
        stringResource(if (child == null) R.string.child_title_new else R.string.child_title),
        bottom = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Box(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (child == null) ui.act({ repo.addChild(fields) }) else ui.act({ repo.editChild(child.id, fields) })
                        ui.back()
                    },
                    modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 140.dp),
                ) { Text(stringResource(R.string.edit_save), style = MaterialTheme.typography.titleMedium) }
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.child_name)) },
            supportingText = { Text(stringResource(R.string.child_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Label(R.string.child_birth_date)
        ChipRow {
            OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(birthDate?.let { runCatching { fmt.date(LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 12 * 3_600_000L) }.getOrNull() }
                    ?: stringResource(R.string.child_not_set))
            }
            if (birthDate != null) FilterChip(false, { birthDate = null }, { Text(stringResource(R.string.child_not_set)) })
        }

        Label(R.string.child_sex)
        ChipRow {
            FilterChip(sex == null, { sex = null }, { Text(stringResource(R.string.child_not_set)) })
            FilterChip(sex == "f", { sex = "f" }, { Text(stringResource(R.string.child_sex_f)) })
            FilterChip(sex == "m", { sex = "m" }, { Text(stringResource(R.string.child_sex_m)) })
        }

        Label(R.string.child_birth_weight)
        if (imperial) {
            val totalOz = weightG?.let { it / G_PER_OZ }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var lb by rememberSaveable { mutableStateOf(totalOz?.let { (it / 16).toInt().toDouble() }) }
                var oz by rememberSaveable { mutableStateOf(totalOz?.let { ((it % 16) * 10).roundToInt() / 10.0 }) }
                fun update() {
                    weightG = if (lb == null && oz == null) null else (((lb ?: 0.0) * 16 + (oz ?: 0.0)) * G_PER_OZ).roundToInt()
                }
                NumberField(lb, R.string.child_lb, Modifier.weight(1f)) { lb = it; update() }
                NumberField(oz, R.string.child_oz, Modifier.weight(1f)) { oz = it; update() }
            }
        } else {
            NumberField(weightG?.let { it / 1000.0 }, R.string.child_kg, Modifier.fillMaxWidth()) { kg -> weightG = kg?.let { (it * 1000).roundToInt() } }
        }

        Label(R.string.child_birth_length)
        if (imperial) {
            NumberField(lengthMm?.let { (it / 25.4 * 10).roundToInt() / 10.0 }, R.string.child_in, Modifier.fillMaxWidth()) { inch ->
                lengthMm = inch?.let { (it * 25.4).roundToInt() }
            }
        } else {
            NumberField(lengthMm?.let { it / 10.0 }, R.string.child_cm, Modifier.fillMaxWidth()) { cm -> lengthMm = cm?.let { (it * 10).roundToInt() } }
        }

        Label(R.string.child_color)
        ChipRow {
            for ((i, c) in CHILD_PALETTE.withIndex()) {
                // A screen reader hears "Color 3, selected": one choice of several, like a radio button.
                val description = stringResource(R.string.child_color_cd, i + 1)
                Box(
                    Modifier.size(48.dp)
                        .selectable(selected = c == color, role = Role.RadioButton) { color = c }
                        .semantics { contentDescription = description }
                        .padding(4.dp)
                        .background(Color(c), CircleShape)
                        .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                )
            }
        }

        if (child != null) {
            Section(if (child.archivedAt == null) R.string.child_archive else R.string.child_unarchive)
            if (isLastActive) {
                Hint(R.string.child_last)
            } else {
                Hint(R.string.child_archive_hint)
                OutlinedButton(
                    onClick = {
                        ui.act({ repo.setChildArchived(child.id, child.archivedAt == null) })
                        ui.back()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(if (child.archivedAt == null) R.string.child_archive else R.string.child_unarchive)) }
                Hint(R.string.child_delete_hint)
                OutlinedButton(
                    onClick = {
                        ui.act({ repo.deleteChild(child.id) })
                        ui.back()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.child_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (pickingDate) {
        val zone = ZoneId.systemDefault()
        val current = birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(zone)
        DatePick(current.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L, zone, onDismiss = { pickingDate = false }) {
            pickingDate = false
            birthDate = it.toString()
        }
    }
}
