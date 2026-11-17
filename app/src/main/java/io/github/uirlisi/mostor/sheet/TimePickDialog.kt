package io.github.uirlisi.mostor.sheet

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.uirlisi.mostor.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The [time…] chips: a clock picker. The caller decides which date the picked time means. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(title: String, initial: Long, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val start = Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault())
    val state = rememberTimePickerState(start.hour, start.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
