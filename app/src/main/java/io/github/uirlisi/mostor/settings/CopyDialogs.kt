package io.github.uirlisi.mostor.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.rememberOperationState
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.data.DataCopy
import java.io.File
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A restore's own steps: what the copy holds against what it replaces, to confirm; then done. */
private sealed interface CopyStep {
    data class Confirm(val rows: DataCopy.Rows, val copy: DataCopy.Summary, val here: DataCopy.Summary) : CopyStep
    data class Restored(val records: Int) : CopyStep
}

/** The two actions under Settings › Backup and import. */
class CopyActions(val save: () -> Unit, val restore: () -> Unit)

/**
 * "Save a copy of the data…" and "Restore from a copy…" (DECISIONS 2026-11-13). Both go through
 * the system file picker, so no storage permission. Work runs in [appScope], so leaving the screen
 * doesn't cut a save or restore short. Call it outside any lazy list.
 */
@Composable
fun rememberDataCopy(appScope: CoroutineScope): CopyActions {
    // The application context: the work outlives the screen in the app scope, and must not hold the activity.
    val context = LocalContext.current.applicationContext
    val container = context.container
    val state = rememberOperationState<Operation<CopyStep>>("copy", Operation.Idle)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.runThenShowOrFail(appScope, R.string.copy_save_title, R.string.copy_saving, R.string.copy_save_failed, "Saving a copy") {
            withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "copy-out.db")
                try {
                    DataCopy.write(container.db, temp)
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    out.use { o -> temp.inputStream().use { it.copyTo(o) } }
                } finally {
                    temp.delete()
                }
            }
            Operation.Message(R.string.copy_save_title, R.string.copy_saved)
        }
    }

    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val title = R.string.copy_restore_title
        state.runThenShowOrFail(appScope, title, R.string.copy_reading, R.string.copy_not_a_copy, "Reading a copy") {
            withContext(Dispatchers.IO) {
                val temp = scratchIn(context)
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: error("no input stream")
                    input.use { i -> temp.outputStream().use { i.copyTo(it) } }
                    when (val read = DataCopy.read(context, temp)) {
                        DataCopy.Read.NotACopy -> Operation.Message(title, R.string.copy_not_a_copy)
                        DataCopy.Read.Newer -> Operation.Message(title, R.string.copy_newer)
                        is DataCopy.Read.Ok -> {
                            val copy = read.rows.summary()
                            if (copy.children == 0) {
                                Operation.Message(title, R.string.copy_not_a_copy)
                            } else {
                                val here = DataCopy.readRows(container.db.openHelper.readableDatabase).summary()
                                Operation.Step(CopyStep.Confirm(read.rows, copy, here))
                            }
                        }
                    }
                } finally {
                    scratchFiles(context).forEach { it.delete() }
                }
            }
        }
    }

    OperationDialog(state) { s ->
        when (s) {
            is CopyStep.Confirm -> AlertDialog(
                onDismissRequest = { state.value = Operation.Idle },
                confirmButton = {
                    TextButton(onClick = {
                        val title = R.string.copy_restore_title
                        state.runThenShowOrFail(appScope, title, R.string.copy_restoring, R.string.copy_restore_failed, "Restoring a copy") {
                            Operation.Step(CopyStep.Restored(container.repository.restoreFrom(s.rows)))
                        }
                    }) { Text(stringResource(R.string.copy_replace)) }
                },
                dismissButton = { TextButton(onClick = { state.value = Operation.Idle }) { Text(stringResource(R.string.cancel)) } },
                title = { Text(stringResource(R.string.copy_restore_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.copy_has, children(s.copy), records(s.copy)))
                        s.copy.newestStart?.let { Text(stringResource(R.string.copy_newest, Formats(LocalContext.current).dateTime(it))) }
                        Text(stringResource(R.string.copy_replaces, children(s.here), records(s.here)))
                    }
                },
            )
            is CopyStep.Restored -> AlertDialog(
                onDismissRequest = { state.value = Operation.Idle },
                confirmButton = { TextButton(onClick = { state.value = Operation.Idle }) { Text(stringResource(R.string.ok)) } },
                title = { Text(stringResource(R.string.copy_restore_title)) },
                text = { Text(pluralStringResource(R.plurals.copy_restored, s.records, s.records)) },
            )
        }
    }

    return remember(save, restore) {
        CopyActions(
            save = { save.launch(fileName(context)) },
            // Files apps label .db files inconsistently (octet-stream, x-sqlite3…); the header is checked.
            restore = { restore.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun children(s: DataCopy.Summary) = pluralStringResource(R.plurals.copy_children, s.children, s.children)

@Composable
private fun records(s: DataCopy.Summary) = pluralStringResource(R.plurals.copy_records, s.records, s.records)



private const val MIME = "application/vnd.sqlite3"

/** mostor-copy-2026-11-13-1402.db (mostor-dev-copy-… from the dev build). */
private fun fileName(context: Context): String {
    val dev = if (context.packageName.endsWith(".dev")) "-dev" else ""
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(java.time.LocalDateTime.now())
    return "mostor$dev-copy-$stamp.db"
}

private fun scratchIn(context: Context) = File(context.cacheDir, "copy-in.db")

/** The scratch copy and any side files SQLite left next to it. */
private fun scratchFiles(context: Context): List<File> {
    val base = scratchIn(context)
    return listOf(base, File(base.path + "-journal"), File(base.path + "-wal"), File(base.path + "-shm"))
}
