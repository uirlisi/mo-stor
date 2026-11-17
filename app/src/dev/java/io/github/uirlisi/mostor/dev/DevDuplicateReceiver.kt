package io.github.uirlisi.mostor.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.core.Kind
import kotlinx.coroutines.launch

/**
 * Dev builds only: makes a "possible duplicate" (SPEC §5.6) to look at before Phase 5's sync can
 * bring one in. It copies the newest live record (or the one given with `--es id`), 2 minutes
 * later, stamped as if another phone had made it. Like the sync it stands in for, it writes the
 * row directly and then refreshes the notification.
 *
 *     adb shell am broadcast -a io.github.uirlisi.mostor.dev.ADD_DUPLICATE \
 *         -n io.github.uirlisi.mostor.dev/io.github.uirlisi.mostor.dev.DevDuplicateReceiver
 *
 * With `--ez running true` it copies the running sleep instead, 2 minutes later and still running:
 * the two-timers question (SPEC §9.5) without a second phone.
 */
class DevDuplicateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wanted = intent.getStringExtra("id")
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val events = c.db.events()
                val now = System.currentTimeMillis()
                val running = intent.getBooleanExtra("running", false)
                val source = wanted?.let { events.event(it) }
                    ?: (
                        if (running) {
                            events.running().firstOrNull { it.kind == Kind.SLEEP.code }
                        } else {
                            events.inRange(0, Long.MAX_VALUE).firstOrNull { it.status == null }
                        }
                        )
                    ?: return@launch
                val at = source.startAt + 2 * 60_000L
                val copy = source.copy(
                    id = Ids.uuidV7(at),
                    startAt = at,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    hlc = HlcStamp(now, 0, "dev-other-phone").encode(),
                )
                events.upsertEvents(listOf(copy))
                c.notifier.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}
