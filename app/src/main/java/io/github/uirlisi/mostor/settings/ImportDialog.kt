package io.github.uirlisi.mostor.settings

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BabyTracker
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.data.Done
import io.github.uirlisi.mostor.data.Repository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Message(val text: Int) : ImportState
    data class Preview(val report: BabyTracker.Report, val plan: BabyTracker.Plan, val bringBack: Boolean = false) : ImportState
    data class Imported(val done: Done) : ImportState
}

/**
 * "Import from Baby Tracker…": pick the PDF report (no storage permission: the system file
 * picker), see what it adds, import as one undoable action. Shows the dialogs and returns what
 * the button calls. Call it outside any lazy list: a list item can be disposed mid-import.
 */
@Composable
fun rememberBabyTrackerImport(repository: Repository, appScope: CoroutineScope): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!PdfText.supported) {
            state = ImportState.Message(R.string.import_unsupported)
            return@rememberLauncherForActivityResult
        }
        state = ImportState.Reading
        scope.launch {
            state = try {
                val report = withContext(Dispatchers.IO) { BabyTracker.parse(PdfText.read(context, uri)) }
                if (report.records.isEmpty()) {
                    ImportState.Message(R.string.import_failed)
                } else {
                    ImportState.Preview(report, repository.previewImport(report))
                }
            } catch (e: Exception) {
                Log.w("MoStor", "PDF import failed: ${e.javaClass.simpleName}")
                ImportState.Message(R.string.import_failed)
            }
        }
    }

    when (val s = state) {
        ImportState.Idle -> Unit
        ImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(R.string.import_reading)) },
        )
        is ImportState.Message -> AlertDialog(
            onDismissRequest = { state = ImportState.Idle },
            confirmButton = { TextButton(onClick = { state = ImportState.Idle }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(s.text)) },
        )
        is ImportState.Preview -> AlertDialog(
            onDismissRequest = { state = ImportState.Idle },
            confirmButton = {
                if (s.plan.newEvents > 0) {
                    TextButton(onClick = {
                        state = ImportState.Reading
                        appScope.launch {
                            val done = repository.importBabyTracker(s.report, s.bringBack)
                            state = if (done != null) ImportState.Imported(done) else ImportState.Message(R.string.import_nothing)
                        }
                    }) { Text(stringResource(R.string.import_action)) }
                }
            },
            dismissButton = { TextButton(onClick = { state = ImportState.Idle }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                PreviewText(s.report, s.plan, s.bringBack) { on ->
                    scope.launch { state = s.copy(plan = repository.previewImport(s.report, on), bringBack = on) }
                }
            },
        )
        is ImportState.Imported -> AlertDialog(
            onDismissRequest = { state = ImportState.Idle },
            confirmButton = { TextButton(onClick = { state = ImportState.Idle }) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                TextButton(onClick = {
                    appScope.launch { repository.undo(s.done.undoId) }
                    state = ImportState.Idle
                }) { Text(stringResource(R.string.action_undo)) }
            },
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                val n = s.done.info.count ?: 0
                Text(pluralStringResource(R.plurals.label_imported, n, n))
            },
        )
    }
    return remember(pick) { { pick.launch(arrayOf("application/pdf")) } }
}

@Composable
private fun PreviewText(report: BabyTracker.Report, plan: BabyTracker.Plan, bringBack: Boolean, setBringBack: (Boolean) -> Unit) {
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val appName = stringResource(R.string.app_name)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val first = report.firstDay
        val last = report.lastDay
        if (first != null && last != null) {
            Text(stringResource(R.string.import_range, first.format(dateFormat), last.format(dateFormat)), style = MaterialTheme.typography.titleMedium)
        }
        val events = plan.change.events.map { it.after }
        val feeds = events.count { it.kind == Kind.BREAST }
        val diapers = events.count { it.kind == Kind.DIAPER }
        if (plan.newEvents > 0) {
            Text(
                stringResource(
                    R.string.import_adds,
                    pluralStringResource(R.plurals.import_feeds, feeds, feeds),
                    pluralStringResource(R.plurals.import_diapers, diapers, diapers),
                ),
            )
        } else {
            Text(stringResource(R.string.import_nothing))
        }
        if (plan.alreadyImported > 0) {
            Text(pluralStringResource(R.plurals.import_already_imported, plan.alreadyImported, plan.alreadyImported))
        }
        if (plan.alreadyRecorded > 0) {
            Text(pluralStringResource(R.plurals.import_already_recorded, plan.alreadyRecorded, plan.alreadyRecorded, appName))
        }
        val deleted = if (bringBack) plan.broughtBack else plan.deletedSince
        if (deleted > 0) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = bringBack, role = Role.Checkbox, onValueChange = setBringBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = bringBack, onCheckedChange = null)
                Text(pluralStringResource(R.plurals.import_bring_back, deleted, deleted), Modifier.padding(start = 8.dp))
            }
        }
        for (issue in report.issues) {
            val day = issue.date.format(dateFormat)
            Text(
                when (issue) {
                    is BabyTracker.CountMismatch -> stringResource(
                        R.string.import_issue_count,
                        day,
                        issue.expected,
                        stringResource(if (issue.kind == Kind.DIAPER) R.string.import_what_diapers else R.string.import_what_feeds),
                        issue.found,
                    )
                    is BabyTracker.SidesUnknown -> stringResource(R.string.import_issue_sides, day)
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (report.skipped.isNotEmpty()) {
            Text(stringResource(R.string.import_skipped))
            report.skipped.take(5).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        Text(stringResource(R.string.import_zone, ZoneId.systemDefault().id), style = MaterialTheme.typography.bodySmall)
    }
}
