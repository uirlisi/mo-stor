package io.github.uirlisi.mostor.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.data.DataCopy
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface CopyState {
    data object Idle : CopyState
    data class Busy(val title: Int, val text: Int) : CopyState
    data class Message(val title: Int, val text: Int) : CopyState
    data class Confirm(val rows: DataCopy.Rows, val copy: DataCopy.Summary, val here: DataCopy.Summary) : CopyState
    data class Restored(val records: Int) : CopyState
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
    val context = LocalContext.current
    val container = context.container
    var state by remember { mutableStateOf<CopyState>(CopyState.Idle) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = CopyState.Busy(R.string.copy_save_title, R.string.copy_saving)
        appScope.launch {
            state = try {
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
                CopyState.Message(R.string.copy_save_title, R.string.copy_saved)
            } catch (e: Exception) {
                Log.w("MoStor", "Saving a copy failed: ${e.javaClass.simpleName}")
                CopyState.Message(R.string.copy_save_title, R.string.copy_save_failed)
            }
        }
    }

    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = CopyState.Busy(R.string.copy_restore_title, R.string.copy_reading)
        appScope.launch {
            state = try {
                withContext(Dispatchers.IO) {
                    val temp = scratchIn(context)
                    try {
                        val input = context.contentResolver.openInputStream(uri) ?: error("no input stream")
                        input.use { i -> temp.outputStream().use { i.copyTo(it) } }
                        when (val read = DataCopy.read(context, temp)) {
                            DataCopy.Read.NotACopy -> CopyState.Message(R.string.copy_restore_title, R.string.copy_not_a_copy)
                            DataCopy.Read.Newer -> CopyState.Message(R.string.copy_restore_title, R.string.copy_newer)
                            is DataCopy.Read.Ok -> {
                                val copy = read.rows.summary()
                                if (copy.children == 0) {
                                    CopyState.Message(R.string.copy_restore_title, R.string.copy_not_a_copy)
                                } else {
                                    val here = DataCopy.readRows(container.db.openHelper.readableDatabase).summary()
                                    CopyState.Confirm(read.rows, copy, here)
                                }
                            }
                        }
                    } finally {
                        scratchFiles(context).forEach { it.delete() }
                    }
                }
            } catch (e: Exception) {
                Log.w("MoStor", "Reading a copy failed: ${e.javaClass.simpleName}")
                CopyState.Message(R.string.copy_restore_title, R.string.copy_not_a_copy)
            }
        }
    }

    when (val s = state) {
        CopyState.Idle -> Unit
        is CopyState.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(s.title)) },
            text = { Text(stringResource(s.text)) },
        )
        is CopyState.Message -> AlertDialog(
            onDismissRequest = { state = CopyState.Idle },
            confirmButton = { TextButton(onClick = { state = CopyState.Idle }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(s.title)) },
            text = { Text(stringResource(s.text)) },
        )
        is CopyState.Confirm -> AlertDialog(
            onDismissRequest = { state = CopyState.Idle },
            confirmButton = {
                TextButton(onClick = {
                    state = CopyState.Busy(R.string.copy_restore_title, R.string.copy_restoring)
                    appScope.launch {
                        state = try {
                            CopyState.Restored(container.repository.restoreFrom(s.rows))
                        } catch (e: Exception) {
                            Log.w("MoStor", "Restoring a copy failed: ${e.javaClass.simpleName}")
                            CopyState.Message(R.string.copy_restore_title, R.string.copy_restore_failed)
                        }
                    }
                }) { Text(stringResource(R.string.copy_replace)) }
            },
            dismissButton = { TextButton(onClick = { state = CopyState.Idle }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.copy_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.copy_has, children(s.copy), records(s.copy)))
                    s.copy.newestStart?.let { Text(stringResource(R.string.copy_newest, dateTime(it))) }
                    Text(stringResource(R.string.copy_replaces, children(s.here), records(s.here)))
                }
            },
        )
        is CopyState.Restored -> AlertDialog(
            onDismissRequest = { state = CopyState.Idle },
            confirmButton = { TextButton(onClick = { state = CopyState.Idle }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.copy_restore_title)) },
            text = { Text(pluralStringResource(R.plurals.copy_restored, s.records, s.records)) },
        )
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

private fun dateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

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
