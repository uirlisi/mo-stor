package io.github.uirlisi.mostor.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.data.Source
import kotlinx.coroutines.launch

/**
 * Notification buttons, the notification's delete intent and the overrun/day-change alarm. Every
 * button fires a broadcast, never an activity, so it works on a locked phone (DECISIONS.md).
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val undoId = intent.getStringExtra(EXTRA_UNDO_ID)
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val repo = c.repository
                val n = Source.NOTIFICATION
                when (action) {
                    ACTION_STOP -> eventId?.let { repo.stop(it, n) }
                    ACTION_SWITCH -> eventId?.let { repo.switchSide(it, n) }
                    ACTION_CANCEL -> eventId?.let { repo.cancel(it, n) }
                    ACTION_PAUSE -> eventId?.let { repo.pause(it, n) }
                    ACTION_RESUME -> eventId?.let { repo.resume(it, n) }
                    ACTION_STILL_GOING -> eventId?.let { repo.stillGoing(it) }
                    ACTION_UNDO -> undoId?.let { repo.undo(it) }
                    ACTION_KEEP_EARLIER -> eventId?.let { repo.resolveConflict(it, io.github.uirlisi.mostor.core.Keep.EARLIER, n) }
                    ACTION_KEEP_LATER -> eventId?.let { repo.resolveConflict(it, io.github.uirlisi.mostor.core.Keep.LATER, n) }
                    ACTION_KEEP_BOTH -> eventId?.let { repo.resolveConflict(it, io.github.uirlisi.mostor.core.Keep.BOTH, n) }
                    ACTION_NO_END_DISMISSED -> eventId?.let { repo.dismissNoEnd(it) }
                    ACTION_CONFLICT_DISMISSED -> eventId?.let { repo.dismissConflictAlert(it) }
                    ACTION_REPOST -> c.notifier.swipedAway()
                    ACTION_TICK -> c.notifier.refresh()
                }
                // Every repository write (no-ops included) has already redrawn the notification.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val P = "io.github.uirlisi.mostor.action."
        const val ACTION_STOP = P + "STOP"
        const val ACTION_SWITCH = P + "SWITCH"
        const val ACTION_CANCEL = P + "CANCEL"
        const val ACTION_PAUSE = P + "PAUSE"
        const val ACTION_RESUME = P + "RESUME"
        const val ACTION_STILL_GOING = P + "STILL_GOING"
        const val ACTION_UNDO = P + "UNDO"

        /** Running timers twice after a sync (SPEC §9.5); the event id extra holds the conflict's key. */
        const val ACTION_KEEP_EARLIER = P + "KEEP_EARLIER"
        const val ACTION_KEEP_LATER = P + "KEEP_LATER"
        const val ACTION_KEEP_BOTH = P + "KEEP_BOTH"
        const val ACTION_CONFLICT_DISMISSED = P + "CONFLICT_DISMISSED"
        const val ACTION_NO_END_DISMISSED = P + "NO_END_DISMISSED"
        const val ACTION_REPOST = P + "REPOST"
        const val ACTION_TICK = P + "TICK"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_UNDO_ID = "undo_id"
    }
}

/** Re-posts the notification after a reboot, an app update, or a clock, zone or language change. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> Unit
            else -> return
        }
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                // A restart brings back a notification swiped away with "Keep the notification" off.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) c.notifier.showAgain() else c.notifier.refresh()
                c.sync.refreshBackground()
            } finally {
                pending.finish()
            }
        }
    }
}
