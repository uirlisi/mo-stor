package io.github.uirlisi.mostor.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.data.AutoExport
import io.github.uirlisi.mostor.data.BackupFiles
import io.github.uirlisi.mostor.data.BackupJson
import io.github.uirlisi.mostor.data.Keys
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.ui.AppUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface BackupState {
    data object Idle : BackupState
    data class Busy(val title: Int, val text: Int) : BackupState
    data class Message(val title: Int, val text: Int) : BackupState
    data class Confirm(val backup: BackupJson.Backup, val outcome: Repository.ImportOutcome) : BackupState
}

/**
 * Settings › Backup, export and import (SPEC §8, Phase 4): the JSON backup and its import, the
 * daily backup to a folder, the CSV export, then the 0.3.1 copy of the database and the Baby
 * Tracker import (DECISIONS 2026-11-14). Everything goes through the system file picker, so no
 * storage permission. Work runs in the app scope, so leaving the page doesn't cut it short.
 */
@Composable
internal fun BackupPage(ui: AppUi) {
    val context = LocalContext.current
    val repo = ui.repository
    val autoExport = context.container.autoExport
    val settingsState by remember { repo.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val copies = rememberDataCopy(ui.appScope)
    val startImport = rememberBabyTrackerImport(repo, ui.appScope)
    var state by remember { mutableStateOf<BackupState>(BackupState.Idle) }

    fun run(title: Int, busy: Int, done: Int, failed: Int, work: suspend () -> Unit) {
        state = BackupState.Busy(title, busy)
        ui.appScope.launch {
            state = try {
                work()
                BackupState.Message(title, done)
            } catch (e: Exception) {
                Log.w("MoStor", "Backup step failed: ${e.javaClass.simpleName}")
                BackupState.Message(title, failed)
            }
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.JSON_MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        run(R.string.backup_export_title, R.string.backup_exporting, R.string.backup_exported, R.string.backup_export_failed) {
            val json = BackupJson.encode(repo.backupRows(), System.currentTimeMillis(), BackupFiles.appVersion(context))
            withContext(Dispatchers.IO) { BackupFiles.write(context, uri, json) }
        }
    }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.CSV_MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        run(R.string.csv_title, R.string.backup_exporting, R.string.csv_saved, R.string.csv_failed) {
            val text = repo.csv(context.getString(R.string.child_unnamed))
            withContext(Dispatchers.IO) { BackupFiles.write(context, uri, text) }
        }
    }

    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = BackupState.Busy(R.string.backup_import_title, R.string.backup_reading)
        ui.appScope.launch {
            state = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("no input stream")
                }
                when (val read = withContext(Dispatchers.Default) { BackupJson.decode(text) }) {
                    BackupJson.Read.NotABackup -> BackupState.Message(R.string.backup_import_title, R.string.backup_not_a_backup)
                    BackupJson.Read.Newer -> BackupState.Message(R.string.backup_import_title, R.string.backup_newer)
                    is BackupJson.Read.Ok -> BackupState.Confirm(read.backup, repo.previewBackup(read.backup.rows))
                }
            } catch (e: Exception) {
                Log.w("MoStor", "Reading a backup failed: ${e.javaClass.simpleName}")
                BackupState.Message(R.string.backup_import_title, R.string.backup_not_a_backup)
            }
        }
    }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = settingsState?.autoExportUri
        run(R.string.daily_section, R.string.backup_exporting, R.string.daily_saved, R.string.daily_folder_failed) {
            context.contentResolver.takePersistableUriPermission(tree, flags)
            repo.setSettingsQuietly(mapOf(Keys.AUTO_EXPORT_URI to tree.toString(), Keys.AUTO_EXPORT_LAST to null, Keys.AUTO_EXPORT_STATUS to null))
            // Let go of the old folder's grant: the app keeps access only where it writes.
            if (previous != null && previous != tree.toString()) releaseFolder(context, previous)
            if (autoExport.runIfDue(force = true) != AutoExport.Result.Written) error("first backup failed")
        }
    }

    val s = settingsState
    SubPage(ui, stringResource(R.string.set_backup)) {
        Hint(R.string.backup_page_warning)

        Section(R.string.backup_section)
        Hint(R.string.backup_hint)
        Button(R.string.backup_export) { exportJson.launch(BackupFiles.manualJson(context)) }
        // Files apps label .json files inconsistently (or not at all); the content is checked.
        Button(R.string.backup_import) { importJson.launch(arrayOf("*/*")) }

        Section(R.string.daily_section)
        val folder = s?.autoExportUri
        if (folder == null) {
            Text(stringResource(R.string.daily_hint, AutoExport.KEEP), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(R.string.daily_choose) { chooseFolder.launch(null) }
        } else {
            val name by produceState<String?>(null, folder) { value = withContext(Dispatchers.IO) { folderName(context, folder) } }
            Text(stringResource(R.string.daily_folder, name ?: "…"), style = MaterialTheme.typography.bodyLarge)
            when {
                s.autoExportFailed -> Text(stringResource(R.string.daily_failed), color = MaterialTheme.colorScheme.error)
                s.autoExportLast != null -> Hint(stringResource(R.string.daily_last, day(s.autoExportLast)))
                else -> Hint(R.string.daily_never)
            }
            Button(R.string.daily_now) {
                state = BackupState.Busy(R.string.daily_section, R.string.backup_exporting)
                ui.appScope.launch {
                    val ok = autoExport.runIfDue(force = true) == AutoExport.Result.Written
                    state = BackupState.Message(R.string.daily_section, if (ok) R.string.daily_saved else R.string.daily_save_failed)
                }
            }
            Button(R.string.daily_change) { chooseFolder.launch(null) }
            Button(R.string.daily_off) {
                ui.appScope.launch {
                    releaseFolder(context, folder)
                    repo.setSettingsQuietly(mapOf(Keys.AUTO_EXPORT_URI to null, Keys.AUTO_EXPORT_LAST to null, Keys.AUTO_EXPORT_STATUS to null))
                }
            }
        }

        Section(R.string.csv_section)
        Hint(R.string.csv_hint)
        Button(R.string.csv_export) { exportCsv.launch(BackupFiles.csv(context)) }

        Section(R.string.copy_section)
        Hint(R.string.copy_hint)
        Button(R.string.copy_save, copies.save)
        Button(R.string.copy_restore, copies.restore)

        Section(R.string.other_app_section)
        Button(R.string.import_button, startImport)
    }

    when (val st = state) {
        BackupState.Idle -> Unit
        is BackupState.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(st.title)) },
            text = { Text(stringResource(st.text)) },
        )
        is BackupState.Message -> AlertDialog(
            onDismissRequest = { state = BackupState.Idle },
            confirmButton = { TextButton(onClick = { state = BackupState.Idle }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(st.title)) },
            text = { Text(stringResource(st.text)) },
        )
        is BackupState.Confirm -> ImportConfirm(st, onCancel = { state = BackupState.Idle }) {
            run(R.string.backup_import_title, R.string.backup_importing, R.string.backup_imported, R.string.backup_import_failed) {
                repo.importBackup(st.backup.rows)
            }
        }
    }
}

@Composable
private fun ImportConfirm(st: BackupState.Confirm, onCancel: () -> Unit, onImport: () -> Unit) {
    val o = st.outcome
    val (children, records) = counts(st.backup.rows)
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            if (o.changesAnything) TextButton(onClick = onImport) { Text(stringResource(R.string.backup_import_action)) }
            else TextButton(onClick = onCancel) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = if (o.changesAnything) ({ TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }) else null,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.backup_has,
                        pluralStringResource(R.plurals.copy_children, children, children),
                        pluralStringResource(R.plurals.copy_records, records, records),
                    ),
                )
                Text(stringResource(R.string.backup_made, dateTime(st.backup.exportedAt)))
                if (!o.changesAnything) {
                    Text(stringResource(R.string.backup_nothing))
                } else {
                    if (o.added > 0) Text(pluralStringResource(R.plurals.backup_adds, o.added, o.added))
                    if (o.updated > 0) Text(pluralStringResource(R.plurals.backup_updates, o.updated, o.updated))
                    if (o.unchanged > 0) Text(pluralStringResource(R.plurals.backup_same, o.unchanged, o.unchanged))
                    if (o.otherChanges > 0 && o.added == 0 && o.updated == 0) Text(stringResource(R.string.backup_other))
                    if (o.replacesBlank) Text(stringResource(R.string.backup_replaces_blank))
                    Text(stringResource(R.string.backup_no_undo))
                }
            }
        },
    )
}

@Composable
private fun Button(res: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(res)) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Live children, and live records of live children, as the app counts them. */
private fun counts(rows: Merge.Rows): Pair<Int, Int> {
    val live = rows.children.filter { it.deletedAt == null }.mapTo(HashSet()) { it.id }
    return live.size to rows.events.count { it.deletedAt == null && it.childId in live }
}

private fun dateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun day(isoDate: String): String =
    runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.parse(isoDate)) }.getOrDefault(isoDate)

/** The folder's name as the files app shows it. */
private fun folderName(context: Context, tree: String): String? = runCatching {
    val uri = Uri.parse(tree)
    val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

private fun releaseFolder(context: Context, tree: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(tree),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
