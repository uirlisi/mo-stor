package io.github.uirlisi.mostor.sync

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.uirlisi.mostor.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Woken by Android's background scan when a phone nearby advertises a sync (SPEC §9.6; the spike
 * measured 0.1–22 s with the app killed and the screen off). Connects and syncs within the
 * receiver's time, then lets the process go.
 */
class SyncScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
        @Suppress("DEPRECATION")
        val results = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        val found = results.firstNotNullOfOrNull { BleTransport.parse(it)?.takeIf { f -> f.kind == BleTransport.KIND_SYNC } } ?: return
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                withTimeoutOrNull(9_500) { c.sync.onWoken(found) }
            } finally {
                pending.finish()
            }
        }
    }
}
