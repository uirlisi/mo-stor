package io.github.uirlisi.mostor.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.data.SettingEntity
import kotlinx.coroutines.launch

/**
 * Dev builds only: sets a per-phone setting from adb, so behavior like overrun can be tried with
 * short limits before Phase 2 has a settings screen. Example (nudge a breast feed after 1 minute):
 *
 *     adb shell am broadcast -a io.github.uirlisi.mostor.dev.SET_SETTING \
 *         -n io.github.uirlisi.mostor.dev/io.github.uirlisi.mostor.dev.DevSettingsReceiver \
 *         --es key nudge_breast_min --es value 1
 *
 * An empty value deletes the key (back to the default).
 */
class DevSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val value = intent.getStringExtra("value").orEmpty()
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val dao = c.db.settings()
                if (value.isEmpty()) dao.delete(key) else dao.put(SettingEntity(key, value))
                c.notifier.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}
