package io.github.uirlisi.mostor.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.core.Member
import io.github.uirlisi.mostor.data.HouseholdSync
import io.github.uirlisi.mostor.sync.BleTransport
import io.github.uirlisi.mostor.sync.SyncUi
import io.github.uirlisi.mostor.ui.AppUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The questions asked before leaving a household. */
private enum class Confirm { LEAVE, LEAVE_NOW }

/**
 * Settings › Households and phones (SPEC §6.6, §9.4), naming the active household throughout. From
 * the top: Bluetooth prompts and notices; [households] (this phone's households, from
 * [HouseholdPage]); the household's phones with their last sync and "Sync now"; "Invite a phone",
 * "Join a household" (merging this phone's children, or keeping its records apart) and "Leave
 * household" (only with other phones); battery help; then [delete]. Also the dialogs of pairing
 * (the code, child matching).
 */
@Composable
internal fun HouseholdsAndPhones(
    ui: AppUi,
    households: @Composable ColumnScope.() -> Unit,
    delete: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val sync = context.container.sync
    val transport = sync.transport
    val state by sync.ui.collectAsStateWithLifecycle()
    val phones by remember { ui.repository.sync.syncPhonesFlow() }.collectAsStateWithLifecycle(initialValue = null)
    // Permission and Bluetooth state are read again whenever a launcher returns, the page comes back
    // (a permission granted in system settings) and Bluetooth changes (the quick-settings shade).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        val bt = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { tick++ }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, bt, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            lifecycle.removeObserver(obs)
            context.unregisterReceiver(bt)
        }
    }
    val allowed = remember(tick) { transport.hasPermissions() }
    val on = remember(tick) { allowed && transport.isOn }
    var forget by remember { mutableStateOf<Member?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var joining by remember { mutableStateOf<List<JoinWay>?>(null) }
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

    val name = activeHouseholdCode()

    SubPage(ui, stringResource(R.string.household_title)) {
        if (!allowed) {
            Hint(AnnotatedString(namedString(R.string.sync_permission)))
            Button(onClick = { askPermissions.launch(BleTransport.PERMISSIONS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_permission_button))
            }
        } else if (!on) {
            Hint(R.string.sync_bluetooth_off)
            Button(onClick = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_bluetooth_on))
            }
        }

        val p = phones
        if (p != null) {
            val notice = when {
                p.removedBy != null -> stringResource(R.string.sync_removed_notice, p.removedBy, name)
                p.leftLater -> stringResource(R.string.sync_left_later_notice, name)
                p.left -> stringResource(R.string.sync_left_notice, name)
                else -> null
            }
            notice?.let { Notice(styled(it)) { ui.appScope.launch { ui.repository.sync.dismissRemovedNotice() } } }
            // Phones that left the household by themselves, once each (SPEC §9.4).
            for (m in p.departed) {
                val at = m.removedAt ?: continue
                Notice(styled(stringResource(R.string.sync_departed, m.name, name, ui.fmt.date(at) + ", " + ui.fmt.time(at)))) {
                    ui.appScope.launch { ui.repository.sync.dismissDeparted(m) }
                }
            }
        }

        households()

        if (p != null) {
            // Every phone of the household, as all of them see it (SPEC §9.4); this phone first.
            Section(styled(stringResource(R.string.sync_phones, name)))
            Hint(R.string.sync_intro)
            val others = p.others
            for (m in p.active.sortedBy { if (it.deviceId == p.me) 0 else 1 }) {
                PhoneRow(ui, p, m, now, onRemove = { forget = m })
            }
            if (p.leaving) {
                Hint(styled(stringResource(R.string.sync_leaving, name)))
            } else if (others.isEmpty()) {
                Hint(AnnotatedString(namedString(R.string.sync_none)))
            }
            // Sync is automatic; "Sync now" is the fallback, under the phones it reaches.
            if (p.leaving || others.isNotEmpty()) {
                OutlinedButton(onClick = { sync.syncNow() }, enabled = on, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sync_now))
                }
            }

            Section(R.string.sync_household_section)
            if (p.leaving) {
                Action(AnnotatedString(stringResource(R.string.sync_leave_now_hint)), R.string.sync_leave_now) { confirm = Confirm.LEAVE_NOW }
            } else {
                Action(styled(stringResource(R.string.sync_add_hint, name)), R.string.sync_add, enabled = on) { sync.startAdding() }
                // Joining merges this database or keeps its records apart (SPEC §9.4 "Joining"); a
                // database holding only the untouched starter child merges without a question.
                Action(styled(stringResource(R.string.sync_join_hint, name)), R.string.sync_join, enabled = on) {
                    if (p.blank && JoinWay.MERGE in p.ways) sync.startJoining(JoinWay.MERGE) else joining = p.ways
                }
                // The only active phone of a household has no Leave: it already is a household of its own.
                if (others.isNotEmpty()) {
                    Action(styled(stringResource(R.string.sync_leave_hint, name)), R.string.sync_leave) { confirm = Confirm.LEAVE }
                }
            }
            if (p.leaving || others.isNotEmpty()) BatterySection(tick)
        }

        delete()
    }

    joining?.let { ways ->
        JoinDialog(ways, name, onJoin = { way ->
            joining = null
            sync.startJoining(way)
        }, onCancel = { joining = null })
    }

    confirm?.let { c ->
        val (title, text, button) = when (c) {
            Confirm.LEAVE -> Triple(stringResource(R.string.sync_leave_title, name), R.string.sync_leave_text, R.string.sync_leave)
            Confirm.LEAVE_NOW -> Triple(stringResource(R.string.sync_leave_now_title), R.string.sync_leave_now_text, R.string.sync_leave_now)
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (c) {
                        Confirm.LEAVE -> sync.leave()
                        Confirm.LEAVE_NOW -> sync.leaveNow()
                    }
                }) { Text(stringResource(button)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(styled(title)) },
            text = { Text(stringResource(text)) },
        )
    }

    forget?.let { p ->
        AlertDialog(
            onDismissRequest = { forget = null },
            confirmButton = {
                TextButton(onClick = {
                    forget = null
                    ui.appScope.launch {
                        ui.repository.sync.removeMember(p.deviceId)
                        sync.refreshBackground()
                    }
                }) { Text(stringResource(R.string.sync_forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(styled(stringResource(R.string.sync_forget_title, p.name, name))) },
            text = { Text(styled(stringResource(R.string.sync_forget_text, p.name, name))) },
        )
    }

    SyncDialogs(state, name, onCancel = sync::cancel, onDismiss = sync::dismiss, onAnswer = sync::answerCode, onMatched = sync::finishMatching)
}

/** One phone of the household: its name, its last sync, who added it; "Remove" on the others. */
@Composable
private fun PhoneRow(ui: AppUi, p: HouseholdSync.SyncPhones, m: Member, now: Long, onRemove: () -> Unit) {
    val mine = m.deviceId == p.me
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (mine) Modifier else Modifier.clickable(onClick = onRemove)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium)
            val stats = p.stats[m.deviceId]
            val status = when {
                mine -> stringResource(R.string.sync_this_phone)
                stats?.lastResult != null && stats.lastResult != "ok" -> stringResource(R.string.sync_last_failed)
                stats?.lastSyncAt != null -> stringResource(R.string.sync_last, ui.fmt.ago(now - stats.lastSyncAt))
                else -> stringResource(R.string.sync_never)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.name(m.addedBy)?.let { by ->
                Text(
                    stringResource(R.string.sync_added_by, by, ui.fmt.date(m.addedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!mine) TextButton(onClick = onRemove) { Text(stringResource(R.string.sync_forget)) }
    }
}

/** A hint, then the button it explains (the same order everywhere on this page). */
@Composable
private fun Action(hint: AnnotatedString, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(hint)
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
    }
}

/** Something that happened to the household, shown until OK. */
@Composable
private fun Notice(text: AnnotatedString, onOk: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onOk, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.ok)) }
        }
    }
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
        Hint(AnnotatedString(namedString(R.string.battery_hint)))
        OutlinedButton(onClick = { openAppDetails(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.battery_open_app))
        }
    }
    if (samsung) {
        Hint(AnnotatedString(namedString(R.string.battery_samsung_hint)))
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
    name: String,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onMatched: (Map<String, String>) -> Unit,
) {
    fun cancelButton(): @Composable () -> Unit = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    when (state) {
        SyncUi.Idle -> Unit
        SyncUi.WaitingForJoiner -> Waiting(R.string.sync_waiting_title, stringResource(R.string.sync_waiting, name, stringResource(R.string.app_name)), cancelButton())
        SyncUi.Searching -> Waiting(R.string.sync_searching_title, stringResource(R.string.sync_searching), cancelButton())
        SyncUi.Connecting -> Waiting(R.string.sync_failed_title, stringResource(R.string.sync_connecting), cancelButton())
        SyncUi.Syncing -> Waiting(R.string.sync_failed_title, stringResource(R.string.sync_syncing), cancelButton())
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
        is SyncUi.Paired -> Message(stringResource(R.string.sync_paired, state.peerName, name), onDismiss)
        is SyncUi.Synced -> Message(
            if (state.missed.isEmpty()) {
                stringResource(R.string.sync_synced, name, state.peers.joinToString())
            } else {
                stringResource(R.string.sync_synced_missed, name, state.peers.joinToString(), state.missed.joinToString())
            },
            onDismiss,
        )
        is SyncUi.Failed -> Message(
            when (state.reason) {
                SyncUi.Reason.NO_PHONE -> namedString(R.string.sync_failed_no_phone)
                SyncUi.Reason.CODES_DIFFER -> stringResource(R.string.sync_failed_codes)
                SyncUi.Reason.CONNECTION -> stringResource(R.string.sync_failed_connection)
                SyncUi.Reason.NO_ANSWER -> stringResource(R.string.sync_failed_no_answer)
                SyncUi.Reason.BLUETOOTH -> stringResource(R.string.sync_failed_bluetooth)
                SyncUi.Reason.ALREADY_THERE -> stringResource(R.string.sync_failed_already_there, name)
            },
            onDismiss,
        )
        is SyncUi.AlreadyIn -> Message(
            if (state.name.isNullOrEmpty()) {
                stringResource(R.string.sync_already_in_this)
            } else {
                stringResource(R.string.sync_already_in, code(state.name))
            },
            onDismiss,
        )
    }
}

@Composable
private fun Waiting(title: Int, text: String, cancel: (@Composable () -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(title)) },
        text = { Text(styled(text)) },
        confirmButton = { cancel?.invoke() },
    )
}

@Composable
private fun Message(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_failed_title)) },
        text = { Text(styled(text)) },
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

/**
 * "Join a household" (SPEC §9.4 "Joining"): merge this phone's children (the default, offered only
 * when no other phone syncs with this household) or keep its records apart.
 */
@Composable
private fun JoinDialog(ways: List<JoinWay>, name: String, onJoin: (JoinWay) -> Unit, onCancel: () -> Unit) {
    var way by remember { mutableStateOf(ways.first()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.sync_join_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (JoinWay.MERGE !in ways) Text(styled(stringResource(R.string.sync_join_shared, name)))
                for (w in ways) {
                    val (label, hint) = when (w) {
                        JoinWay.MERGE -> R.string.sync_join_merge to R.string.sync_join_merge_hint
                        JoinWay.APART -> R.string.sync_join_apart to R.string.sync_join_apart_hint
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { way = w },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = way == w, onClick = { way = w })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                            Text(styled(stringResource(hint, name)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(way) }) { Text(stringResource(R.string.sync_join_go)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
