package io.github.uirlisi.mostor.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.uirlisi.mostor.R
import android.text.TextUtils
import io.github.uirlisi.mostor.ui.childArgb
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.data.StatusSink
import io.github.uirlisi.mostor.sheet.QuickSheetActivity
import io.github.uirlisi.mostor.sheet.QuickSheetAfterUnlockActivity
import io.github.uirlisi.mostor.ui.Formats
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The status notification (SPEC §6.1, §6.2), the "has no end" notifications (Attention channel)
 * and the two-timers question (its own pop-up channel).
 *
 * Refreshes are serialized and conflated (DECISIONS.md, Phase 0 S22 lesson): each one reads the
 * latest state inside the lock, and a request already covered by a later render is skipped, so an
 * older state can never be posted last.
 */
class StatusNotifier(
    context: Context,
    private val repository: () -> Repository,
    private val scope: CoroutineScope,
    /** The active household's name when the phone holds two or more (SPEC §6.1), else null. */
    private val householdName: () -> String? = { null },
) : StatusSink {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val fmt = Formats(app)
    private val content = StatusContent(app, fmt)

    private val lock = Mutex()
    private val requested = AtomicLong()

    @Volatile
    private var rendered = 0L
    private var recentExpiry: Job? = null

    fun ensureChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, app.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                    description = app.getString(R.string.channel_status_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ATTENTION, app.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = app.getString(R.string.channel_attention_desc)
                },
                // High importance, so the question pops up (DECISIONS, "The two-timers question pops up").
                NotificationChannel(CHANNEL_SYNC_QUESTIONS, app.getString(R.string.channel_sync_questions), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = app.getString(R.string.channel_sync_questions_desc)
                },
            ),
        )
    }

    fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun refresh() {
        val ticket = requested.incrementAndGet()
        lock.withLock {
            if (rendered >= ticket) return
            val covers = requested.get()
            val repo = repository()
            repo.applyOverruns()
            val state = repo.homeState()
            scheduleWake(state.nextWakeAt)
            if (canPost()) {
                ensureChannels()
                if (state.settings.statusDismissed && !state.settings.keepNotification) {
                    nm.cancel(ID_STATUS)
                } else {
                    @Suppress("MissingPermission") // checked by canPost()
                    nm.notify(ID_STATUS, buildStatus(state))
                }
                postNoEnd(state)
                postConflicts(state)
            }
            scheduleRecentExpiry(state)
            rendered = covers
        }
    }

    /**
     * The status notification was swiped away. With "Keep the notification" on it comes straight
     * back; with it off it stays away, through background refreshes (sync, alarms, the Undo
     * expiring, a restart of the process), until the app is opened or the phone restarts. Under
     * the lock, so a refresh already under way can't post it again afterwards.
     */
    suspend fun swipedAway() {
        val repo = repository()
        if (repo.settings().keepNotification) return refresh()
        lock.withLock {
            repo.setStatusDismissed(true)
            nm.cancel(ID_STATUS)
        }
    }

    /** The app was opened or the phone restarted: a swiped-away notification comes back. */
    suspend fun showAgain() {
        val repo = repository()
        if (repo.settings().statusDismissed) repo.setStatusDismissed(false)
        refresh()
    }

    // ---- Status notification ----

    /** Draws what [StatusContent] decided. */
    private fun buildStatus(state: HomeState): Notification {
        val m = content.build(state, householdName())
        val b = NotificationCompat.Builder(app, CHANNEL_STATUS)
            .setSmallIcon(smallIcon())
            .setColor(app.getColor(R.color.mostor_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(sheetIntent(null, state))
            .setDeleteIntent(broadcast(ActionReceiver.ACTION_REPOST))
        // In the header, after the app's name: which household this is (SPEC §6.1, §9.4). The hidden
        // lock-screen version doesn't have it.
        m.subText?.let(b::setSubText)
        m.counterFrom?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true) } ?: b.setShowWhen(false)
        m.actions.forEach { b.addAction(action(it)) }

        b.setContentTitle(content.asText(m.title, state))
        b.setContentText(content.asText(m.collapsed, state))
        // Drawn as a layout: the dots in the children's exact colors and real lines (DECISIONS,
        // 2026-11-16). The title and text above stay for screen readers and the like.
        b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        b.setCustomContentView(rowsView(state, listOf(listOf(m.title.asTitle(m.counterFrom), m.collapsed)), collapsed = true))
        // Expanded: the title opens the first section, with the latest action under it; the
        // counter is in Android's header.
        b.setCustomBigContentView(rowsView(state, listOf(listOf(m.title.asTitle()) + m.sections.first()) + m.sections.drop(1), collapsed = false))

        b.lockScreen(
            state,
            hidden = NotificationCompat.Builder(app, CHANNEL_STATUS)
                .setSmallIcon(smallIcon())
                .setColor(app.getColor(R.color.mostor_notification))
                .setContentTitle(app.getString(R.string.app_name))
                .setContentText(app.getString(R.string.notif_hidden_text))
                .setContentIntent(sheetIntent(null, state))
                .build(),
        )
        return b.build()
    }

    /**
     * What the lock screen shows. The phone decides whether to hide a notification's content there
     * (its own "Hide content", or the app's "Hide when locked"), and when it hides it, it shows the
     * notification's public version instead. So the switch sets that public version: with "Hide
     * details" off, a copy of the whole notification, which stays readable even when the phone
     * hides content; with it on, [hidden], or without one Android's own "app name only".
     */
    private fun NotificationCompat.Builder.lockScreen(state: HomeState, hidden: Notification? = null): NotificationCompat.Builder {
        if (state.settings.hideDetailsOnLockScreen) {
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            hidden?.let(::setPublicVersion)
        } else {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPublicVersion(build())
        }
        return this
    }

    /**
     * The rows as a custom view, with a thin line between sections. Unlike notification text, a
     * layout keeps the dot's exact color, the line, the bold and live counters. [collapsed] keeps
     * each row to one line.
     */
    private fun rowsView(state: HomeState, sections: List<List<Row>>, collapsed: Boolean): RemoteViews {
        val pkg = app.packageName
        val root = RemoteViews(pkg, R.layout.notif_rows)
        sections.filter { it.isNotEmpty() }.forEachIndexed { i, section ->
            if (i > 0) root.addView(R.id.rows, RemoteViews(pkg, R.layout.notif_divider))
            for (row in section) {
                val view = RemoteViews(pkg, row.style.layout)
                view.setTextViewText(R.id.text, row.text)
                // A child's summary line keeps to one line too, so many children stay short.
                if (collapsed || row.style == Row.Style.TEXT && row.child != null) view.setInt(R.id.text, "setMaxLines", 1)
                row.child?.let {
                    view.setViewVisibility(R.id.dot, View.VISIBLE)
                    view.setInt(R.id.dot, "setColorFilter", childArgb(it, state.colorIndex(it.id)))
                }
                row.counterFrom?.let { from ->
                    // A Chronometer counts from an elapsedRealtime base.
                    val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - from)
                    view.setChronometer(R.id.counter, base, null, true)
                    view.setViewVisibility(R.id.counter, View.VISIBLE)
                }
                root.addView(R.id.rows, view)
            }
        }
        return root
    }

    // ---- "Has no end" notifications (SPEC §5.5) ----

    /** What each side notification showed when last posted (by kind and tag), to post only changes. */
    private val posted = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Suppress("MissingPermission") // only called after canPost()
    private fun postNoEnd(state: HomeState) {
        // An end marked unknown, and a feed the app ended at its forgotten pause (SPEC §5.5).
        val wanted = (state.noEnd + state.autoEnded).associateBy { it.event.id }
        val active = nm.activeNotifications.filter { it.id == ID_NO_END }.mapNotNull { it.tag }.toSet()
        for (tag in active - wanted.keys) nm.cancel(tag, ID_NO_END)
        for ((id, t) in wanted) {
            // Re-posted when what it shows changed (silently: only alert once), so its tap target follows
            // the lock-screen setting and its title the child's name and color. Not on every refresh:
            // Android drops updates beyond a few a second, and the status notification's could go.
            val e = t.event
            val (title, text) = content.stoppedTexts(t)
            val sig = listOf(title, text, state.childOf(e.childId)?.let { it.name to it.color }, state.settings.hideDetailsOnLockScreen, state.settings.lockScreenSheet).toString()
            if (id in active && posted["noend/$id"] == sig) continue
            posted["noend/$id"] = sig
            val n = NotificationCompat.Builder(app, CHANNEL_ATTENTION)
                .setSmallIcon(smallIcon())
                .setColor(app.getColor(R.color.mostor_notification))
                .setContentTitle(content.join(listOfNotNull(title, state.childOf(e.childId)?.let { content.label(it, state) }), " · "))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(sheetIntent(id, state))
                .setDeleteIntent(broadcast(ActionReceiver.ACTION_NO_END_DISMISSED, eventId = id))
                .lockScreen(state)
                .build()
            nm.notify(id, ID_NO_END, n)
        }
    }

    // ---- Two timers running after a sync (SPEC §9.5), on a channel of its own that pops up ----

    /**
     * One alerting notification per question, so it isn't missed in the quiet status notification.
     * Re-posted silently (only alert once) when what it shows changed; canceled once answered or gone. A
     * swipe stops it for that question; the status notification and the sheet still ask.
     */
    @Suppress("MissingPermission") // only called after canPost()
    private fun postConflicts(state: HomeState) {
        val wanted = state.conflicts.filter { it.key !in state.settings.conflictAlertsDismissed }.associateBy { it.key }
        val active = nm.activeNotifications.filter { it.id == ID_CONFLICT }.mapNotNull { it.tag }.toSet()
        for (tag in active - wanted.keys) nm.cancel(tag, ID_CONFLICT)
        for ((key, k) in wanted) {
            // As for "has no end": only when what it shows changed.
            val starts = content.conflictStarts(k)
            val sig = listOf(starts, k.timers.size, state.childOf(k.childId)?.let { it.name to it.color }, state.settings.hideDetailsOnLockScreen, state.settings.lockScreenSheet).toString()
            if (key in active && posted["conflict/$key"] == sig) continue
            posted["conflict/$key"] = sig
            val title = app.getString(R.string.conflict_title, k.timers.size, fmt.timedKind(k.kind).lowercase())
            val b = NotificationCompat.Builder(app, CHANNEL_SYNC_QUESTIONS)
                .setSmallIcon(smallIcon())
                .setColor(app.getColor(R.color.mostor_notification))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentTitle(state.childOf(k.childId)?.let { TextUtils.concat(content.label(it, state), " · ", title) } ?: title)
                .setContentText(starts)
                .setStyle(NotificationCompat.BigTextStyle().bigText(starts))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(sheetIntent(null, state))
                .setDeleteIntent(broadcast(ActionReceiver.ACTION_CONFLICT_DISMISSED, eventId = key))
            content.conflictActions(k).forEach { b.addAction(action(it)) }
            nm.notify(key, ID_CONFLICT, b.lockScreen(state).build())
        }
    }

    /**
     * The face for the status bar (Phase 6): always the outline. Android paints it dark or white to
     * suit what is behind the status bar and doesn't tell the app, so a filled variant drawn dark
     * read as a dark blob (DECISIONS, 2026-11-16).
     */
    private fun smallIcon(): Int = R.drawable.ic_stat_mostor

    // ---- Timing ----

    /** One inexact alarm (no exact-alarm permission) for the next overrun step, day change or Undo expiry. */
    private fun scheduleWake(at: Long?) {
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = broadcast(ActionReceiver.ACTION_TICK)
        am.cancel(pi)
        if (at != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** The Undo line lasts 10 s ([Repository.RECENT_MS]). The alarm is the fallback; this is the punctual path while the process lives. */
    private fun scheduleRecentExpiry(state: HomeState) {
        recentExpiry?.cancel()
        val recent = state.recent ?: return
        val wait = recent.createdAt + Repository.RECENT_MS - System.currentTimeMillis()
        recentExpiry = scope.launch {
            delay(wait.coerceAtLeast(0) + 500)
            refresh()
        }
    }

    // ---- Intents ----

    /** The running card's control icons (Phase 3b); Android's own template shows only the words. */
    private fun iconFor(label: Int, action: String): Int = when {
        action == ActionReceiver.ACTION_SWITCH -> R.drawable.ic_switch_side
        action == ActionReceiver.ACTION_PAUSE -> R.drawable.ic_pause
        action == ActionReceiver.ACTION_RESUME -> R.drawable.ic_resume
        label == R.string.action_woke_up -> R.drawable.ic_awake
        action == ActionReceiver.ACTION_STOP -> R.drawable.ic_stop
        else -> 0
    }

    private fun action(a: StatusAction) =
        NotificationCompat.Action.Builder(iconFor(a.label, a.action), app.getString(a.label), broadcast(a.action, eventId = a.eventId, undoId = a.undoId))
            .setAuthenticationRequired(false)
            .build()

    private fun broadcast(action: String, eventId: String? = null, undoId: String? = null): PendingIntent {
        val intent = Intent(app, ActionReceiver::class.java).setAction(action)
        eventId?.let { intent.putExtra(ActionReceiver.EXTRA_EVENT_ID, it) }
        undoId?.let { intent.putExtra(ActionReceiver.EXTRA_UNDO_ID, it) }
        return PendingIntent.getBroadcast(
            app,
            (action + (eventId ?: undoId ?: "")).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Body tap → quick sheet, launched directly with plain options (DECISIONS.md, §12 item 4). When
     * the sheet isn't allowed on the lock screen, it opens the entry without `showWhenLocked`, so
     * Android asks for the unlock before anything is shown.
     */
    private fun sheetIntent(focusEventId: String?, state: HomeState): PendingIntent {
        val target = if (state.settings.lockScreenSheet) QuickSheetActivity::class.java else QuickSheetAfterUnlockActivity::class.java
        val intent = Intent(app, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        focusEventId?.let { intent.putExtra(QuickSheetActivity.EXTRA_FOCUS_EVENT, it) }
        return PendingIntent.getActivity(
            app,
            focusEventId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /**
         * Lines for the children's blocks. The tallest notification that keeps its buttons on the
         * Android 17 emulator: about ten lines, of which the title and the latest action take two.
         */
        private const val CHILD_LINES_MAX = 8
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_SYNC_QUESTIONS = "sync_questions"
        const val ID_STATUS = 1
        const val ID_NO_END = 2
        const val ID_CONFLICT = 3
    }
}
