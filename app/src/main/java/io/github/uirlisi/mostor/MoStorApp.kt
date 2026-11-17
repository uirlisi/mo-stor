package io.github.uirlisi.mostor

import android.app.Application
import kotlinx.coroutines.launch

class MoStorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Bluetooth back on: Android dropped the background scan when it went off (found on the
        // phones). Only heard while the process is alive; the app start and the periodic job cover
        // the rest.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == android.bluetooth.BluetoothAdapter.STATE_ON) container.sync.onBluetoothOn()
                }
            },
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Off the main thread: opens the database (warming it for the quick sheet) and re-posts
        // the notification on every app start (SPEC §6.1); then the daily backup, if due (SPEC §8).
        container.appScope.launch {
            container.notifier.refresh()
            // The theme choice travels with the database (a phone-to-phone transfer); Android's
            // copy of it doesn't. Re-applying the same mode is a no-op.
            io.github.uirlisi.mostor.ui.applyThemeChoice(this@MoStorApp, container.repository.settings().theme)
            container.autoExport.maybeRun()
            // Android drops the background Bluetooth scan on a reboot or force stop (SPEC §9.6).
            container.sync.refreshBackground()
        }
    }
}
