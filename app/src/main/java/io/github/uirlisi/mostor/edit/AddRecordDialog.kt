package io.github.uirlisi.mostor.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.sheet.BigButton
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.iconOf
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.of
import io.github.uirlisi.mostor.ui.Route

/** [AddRecordDialog] on Today and in the log: the kind picked opens the edit screen. [onClose] closes it. */
@Composable
fun AddRecordDialog(ui: AppUi, visibility: Visibility, onClose: () -> Unit) {
    AddRecordDialog(visibility, ui.fmt, onPick = { kind ->
        onClose()
        ui.open(Route.Add(kind))
    }, onDismiss = onClose)
}

/** "Add a record": the kinds shown on this phone, then the edit screen prefilled at now. */
@Composable
private fun AddRecordDialog(visibility: Visibility, fmt: Formats, onPick: (Kind) -> Unit, onDismiss: () -> Unit) {
    val colors = chartColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ORDER.filter { visibility.shows(it) }) {
                    BigButton(fmt.what(k, null, null), Modifier.fillMaxWidth(), icon = KindIcons.of(k), iconTint = colors.iconOf(k), iconAbove = false) { onPick(k) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The quick sheet's order (SPEC §6.3). */
private val ORDER = listOf(Kind.BREAST, Kind.BOTTLE, Kind.SUPPLEMENT, Kind.SLEEP, Kind.DIAPER, Kind.PUMP, Kind.NOTE)
