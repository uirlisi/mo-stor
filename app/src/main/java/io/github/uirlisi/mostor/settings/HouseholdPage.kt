package io.github.uirlisi.mostor.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.ui.rememberOperationState
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.HouseholdList
import io.github.uirlisi.mostor.core.HouseholdSlot
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.data.BackupFiles
import io.github.uirlisi.mostor.data.BackupJson
import io.github.uirlisi.mostor.data.Source
import io.github.uirlisi.mostor.ui.AppUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Households and phones (SPEC §6.6, §9.4), with the phone sections of [HouseholdsAndPhones]:
 * this phone's households (rename the open one, switch to another after asking to stop a running
 * timer, add one), and "Delete this household" while the phone holds another one and no other phone
 * syncs with this one.
 */
@Composable
internal fun HouseholdPage(ui: AppUi) {
    // The application context: the work outlives the screen in the app scope, and must not hold the activity.
    val context = LocalContext.current.applicationContext
    val c = context.container
    val scope = rememberCoroutineScope()
    val list by c.households.flow.collectAsStateWithLifecycle()
    val phones by remember { ui.repository.sync.syncPhonesFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val active = list.active
    /** The name typed in the rename dialog, while it is open. */
    var renaming by rememberSaveable(stateSaver = OptionalTextSaver) { mutableStateOf<TextFieldValue?>(null) }
    /** While asking to stop the timers running here before going to another household. */
    var stopFirst by remember { mutableStateOf<StopFirst?>(null) }
    var deleting by remember { mutableStateOf(false) }
    /** The name typed in "Add a household", while its dialog is open. */
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = rememberOperationState<Operation<Nothing>>("household-export", Operation.Idle)
    // This household left another with "Leave now" and hasn't told it yet (said before deleting).
    val parting by produceState(false, active.id) { value = ui.repository.sync.parting() != null }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.JSON_MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val title = R.string.backup_export_title
        // No busy note: the result shows when the backup is written.
        saved.runThenShowOrFail(ui.appScope, title, busy = null, R.string.backup_export_failed, "Backup before deleting") {
            val json = BackupJson.encode(ui.repository.backupRows(), System.currentTimeMillis(), BackupFiles.appVersion(context))
            withContext(Dispatchers.IO) { BackupFiles.write(context, uri, json) }
            Operation.Message(title, R.string.backup_exported)
        }
    }

    /**
     * Goes to another household ([to] is its name) by [then], asking first to stop the timers running
     * in this one, which would otherwise run on unseen (SPEC §9.4).
     */
    fun leaveFor(to: String, then: suspend () -> Unit) {
        scope.launch {
            val running = ui.repository.homeState().running.size
            if (running == 0) ui.appScope.launch { then() } else stopFirst = StopFirst(to, running, then)
        }
    }

    fun switchTo(target: HouseholdSlot) = leaveFor(c.nameOf(target)) { c.switchTo(target.id) }

    HouseholdsAndPhones(
        ui,
        households = {
            // This phone's households: the open one first (tap to rename), then the others to switch to.
            Section(R.string.household_list)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { active.name.orEmpty().let { renaming = TextFieldValue(it, TextRange(it.length)) } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        c.nameOf(active),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.household_open), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.household_rename))
            }
            for (other in list.others) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        c.nameOf(other),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { switchTo(other) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.household_switch))
                    }
                }
            }
            OutlinedButton(onClick = { adding = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.household_add))
            }
        },
        delete = {
            if (list.others.isNotEmpty()) {
                // Deleting needs another household to go to, and no other phone still syncing with this one.
                val name = code(c.nameOf(active))
                Section(styled(stringResource(R.string.household_delete_section, name)))
                val shared = phones?.let { JoinWay.MERGE !in it.ways } ?: true
                if (shared) {
                    Hint(R.string.household_delete_shared)
                } else {
                    Hint(R.string.household_delete_hint)
                    OutlinedButton(onClick = { exportJson.launch(BackupFiles.manualJson(context)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.household_backup))
                    }
                    OutlinedButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.household_delete))
                    }
                }
            }
        },
    )

    renaming?.let { typed ->
        // Opens with the keyboard up and the cursor after the name. Not selected: selected text brings
        // up the Cut/Copy toolbar, and the first tap on Cancel or OK only closed that toolbar.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.household_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { renaming = it },
                        label = { Text(stringResource(R.string.household_name)) },
                        placeholder = { Text(stringResource(R.string.household_default, active.number)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    Text(stringResource(R.string.household_name_hint), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    c.renameHousehold(active.id, typed.text)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    stopFirst?.let { s ->
        AlertDialog(
            onDismissRequest = { stopFirst = null },
            title = { Text(stringResource(R.string.household_stop_title)) },
            text = { Text(pluralStringResource(R.plurals.household_stop_text, s.running, s.running, c.nameOf(active), s.to)) },
            confirmButton = {
                TextButton(onClick = {
                    stopFirst = null
                    ui.appScope.launch {
                        val repo = ui.repository
                        for (r in repo.homeState().running) repo.stop(r.timed.event.id, Source.APP)
                        s.then()
                    }
                }) { Text(stringResource(R.string.household_stop_switch)) }
            },
            dismissButton = { TextButton(onClick = { stopFirst = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    adding?.let { typed ->
        val placeholder = stringResource(R.string.household_default, list.nextNumber)
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(stringResource(R.string.household_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.household_add_text))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { adding = it },
                        label = { Text(stringResource(R.string.household_name)) },
                        placeholder = { Text(placeholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adding = null
                    val newName = HouseholdList.cleanName(typed)
                    leaveFor(newName ?: placeholder) { c.addHousehold(newName) }
                }) { Text(stringResource(R.string.household_add_create)) }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (deleting) {
        val next = list.others.firstOrNull()
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.household_delete_title, c.nameOf(active))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.household_delete_text, c.nameOf(active), next?.let(c::nameOf).orEmpty()))
                    if (parting) Text(stringResource(R.string.household_delete_parting))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    val id = active.id
                    ui.appScope.launch { c.deleteHousehold(id) }
                }) { Text(stringResource(R.string.household_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    OperationDialog(saved)
}

/** Timers run in this household ([running]) while going to the household named [to] by [then]. */
private class StopFirst(val to: String, val running: Int, val then: suspend () -> Unit)

/**
 * A dialog's typed text with its cursor, or null while the dialog is closed, for rememberSaveable:
 * the dialog and what was typed survive a rotation or a dark-mode switch.
 */
internal val OptionalTextSaver: Saver<TextFieldValue?, Any> = Saver(
    save = { v -> v?.let { arrayListOf<Any>(it.text, it.selection.start, it.selection.end) } },
    restore = { saved ->
        (saved as? List<*>)?.let { TextFieldValue(it[0] as String, TextRange(it[1] as Int, it[2] as Int)) }
    },
)
