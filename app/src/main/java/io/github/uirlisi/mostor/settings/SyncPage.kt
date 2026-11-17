package io.github.uirlisi.mostor.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.data.PeerEntity
import io.github.uirlisi.mostor.sync.BleTransport
import io.github.uirlisi.mostor.sync.SyncUi
import io.github.uirlisi.mostor.ui.AppUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings › Phones and sync (SPEC §6.6, §9.4): the paired phones with their last sync, "Sync now",
 * "Add a phone" and "Join a phone", and the dialogs of pairing (the code, child matching).
 */
@Composable
internal fun SyncPage(ui: AppUi) {
    val context = LocalContext.current
    val sync = context.container.sync
    val transport = sync.transport
    val state by sync.ui.collectAsStateWithLifecycle()
    val peers by remember { ui.repository.peersFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Permission and Bluetooth state are read again whenever a launcher returns.
    var tick by remember { mutableIntStateOf(0) }
    val allowed = remember(tick) { transport.hasPermissions() }
    val on = remember(tick) { allowed && transport.isOn }
    var forget by remember { mutableStateOf<PeerEntity?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        ui.appScope.launch { sync.refreshBackground() }
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    SubPage(ui, stringResource(R.string.sync_title)) {
        Hint(R.string.sync_intro)
        if (!allowed) {
            Hint(R.string.sync_permission)
            Button(onClick = { askPermissions.launch(BleTransport.PERMISSIONS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_permission_button))
            }
        } else if (!on) {
            Hint(R.string.sync_bluetooth_off)
            Button(onClick = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_bluetooth_on))
            }
        }

        Section(R.string.sync_phones)
        if (peers.isEmpty()) Hint(R.string.sync_none)
        for (p in peers) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { forget = p },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        p.lastResult != null && p.lastResult != "ok" -> stringResource(R.string.sync_last_failed)
                        p.lastSyncAt != null -> stringResource(R.string.sync_last, ui.fmt.ago(now - p.lastSyncAt))
                        else -> stringResource(R.string.sync_never)
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { forget = p }) { Text(stringResource(R.string.sync_forget)) }
            }
        }
        if (peers.isNotEmpty()) {
            Button(onClick = { sync.syncNow() }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sync_now)) }
        }
        if (peers.isNotEmpty()) BatterySection(tick)
        OutlinedButton(onClick = { sync.startAdding() }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sync_add)) }
        OutlinedButton(onClick = { sync.startJoining() }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sync_join)) }
        Hint(R.string.sync_join_hint)
    }

    forget?.let { p ->
        AlertDialog(
            onDismissRequest = { forget = null },
            confirmButton = {
                TextButton(onClick = {
                    forget = null
                    ui.appScope.launch {
                        ui.repository.forgetPeer(p.deviceId)
                        sync.refreshBackground()
                    }
                }) { Text(stringResource(R.string.sync_forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.sync_forget_title, p.name)) },
            text = { Text(stringResource(R.string.sync_forget_text)) },
        )
    }

    SyncDialogs(state, onCancel = sync::cancel, onDismiss = sync::dismiss, onAnswer = sync::answerCode, onMatched = sync::finishMatching)
}

/**
 * Samsung's battery management can keep a closed app from waking for a sync (SPEC §9.6). Asks for
 * "Unrestricted" battery use, and on Samsung phones points to "Never sleeping apps". Opens the
 * screens Phase 0 found working (DECISIONS 2026-11-10); no permission is involved. Shown while the
 * app's battery use isn't unrestricted yet; read again whenever the page comes back.
 */
@Composable
private fun BatterySection(tick: Int) {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val unrestricted = remember(tick, resumed) { power?.isIgnoringBatteryOptimizations(context.packageName) == true }
    val samsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    if (unrestricted && !samsung) return

    Section(R.string.battery_section)
    if (unrestricted) {
        Hint(R.string.battery_ok)
    } else {
        Hint(R.string.battery_hint)
        OutlinedButton(onClick = { openAppDetails(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.battery_open_app))
        }
    }
    if (samsung) {
        Hint(R.string.battery_samsung_hint)
        OutlinedButton(onClick = { openSamsungBattery(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.battery_open_samsung))
        }
    }
}

private fun openAppDetails(context: android.content.Context) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Samsung Device Care's battery screen (not a public API: falls back to the app's details). */
private fun openSamsungBattery(context: android.content.Context) {
    try {
        context.startActivity(
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        openAppDetails(context)
    }
}

@Composable
private fun SyncDialogs(
    state: SyncUi,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onMatched: (Map<String, String>) -> Unit,
) {
    fun cancelButton(): @Composable () -> Unit = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    when (state) {
        SyncUi.Idle -> Unit
        SyncUi.WaitingForJoiner -> Waiting(R.string.sync_waiting_title, R.string.sync_waiting, cancelButton())
        SyncUi.Searching -> Waiting(R.string.sync_searching_title, R.string.sync_searching, cancelButton())
        SyncUi.Connecting -> Waiting(R.string.sync_title, R.string.sync_connecting, cancelButton())
        SyncUi.Syncing -> Waiting(R.string.sync_title, R.string.sync_syncing, null)
        is SyncUi.Code -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        state.code.chunked(3).joinToString(" "),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(if (state.answered) R.string.sync_code_waiting else R.string.sync_code_text))
                }
            },
            confirmButton = {
                if (!state.answered) TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.sync_code_same)) }
            },
            dismissButton = {
                if (!state.answered) TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.sync_code_different)) }
            },
        )
        is SyncUi.Matching -> Matching(state, onMatched)
        is SyncUi.Paired -> Message(stringResource(R.string.sync_paired, state.peerName), onDismiss)
        is SyncUi.Synced -> Message(stringResource(R.string.sync_synced, state.peers.joinToString()), onDismiss)
        is SyncUi.Failed -> Message(
            stringResource(
                when (state.reason) {
                    SyncUi.Reason.NO_PHONE -> R.string.sync_failed_no_phone
                    SyncUi.Reason.CODES_DIFFER -> R.string.sync_failed_codes
                    SyncUi.Reason.CONNECTION -> R.string.sync_failed_connection
                    SyncUi.Reason.NO_ANSWER -> R.string.sync_failed_no_answer
                    SyncUi.Reason.BLUETOOTH -> R.string.sync_failed_bluetooth
                },
            ),
            onDismiss,
        )
    }
}

@Composable
private fun Waiting(title: Int, text: Int, cancel: (@Composable () -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { cancel?.invoke() },
    )
}

@Composable
private fun Message(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_failed_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** One question per child of this phone: which of the other phone's children it is, if any. */
@Composable
private fun Matching(state: SyncUi.Matching, onMatched: (Map<String, String>) -> Unit) {
    // One child on each phone: most likely the same child, so that is the starting answer.
    val choice = remember {
        mutableStateMapOf<String, String?>().apply {
            if (state.mine.size == 1 && state.theirs.size == 1) put(state.mine[0].id, state.theirs[0].id)
        }
    }
    val other = state.peerName
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.sync_match_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (mine in state.mine) {
                    val myName = mine.name ?: stringResource(R.string.child_unnamed)
                    Text(stringResource(R.string.sync_match_text, other, myName), style = MaterialTheme.typography.titleSmall)
                    for (theirs in state.theirs) {
                        Option(theirs.name ?: stringResource(R.string.child_unnamed), choice[mine.id] == theirs.id) { choice[mine.id] = theirs.id }
                    }
                    Option(stringResource(R.string.sync_match_different), choice[mine.id] == null) { choice[mine.id] = null }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMatched(choice.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()) }) {
                Text(stringResource(R.string.sync_match_done))
            }
        },
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
