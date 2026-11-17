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
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.Side
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.ui.childArgb
import io.github.uirlisi.mostor.ui.childEmoji
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.uirlisi.mostor.data.ChildStatus
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
) : StatusSink {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val fmt = Formats(app)

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

    private fun buildStatus(state: HomeState): Notification {
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

        // The body has one line per topic, always in this order: feeding, sleep, diapers.
        var top: String? = null
        var feedLine: CharSequence? = null
        var sleepLine: CharSequence? = null
        val actions = mutableListOf<NotificationCompat.Action>()
        val primary = state.running.firstOrNull { it.overrun == OverrunState.NUDGE } ?: state.running.firstOrNull()
        fun named(text: String, childId: String): Row = state.childOf(childId).let { ch ->
            Row(ch?.let { "${fmt.childName(it.name)} · $text" } ?: text, ch)
        }
        var title = Row("")
        // When the running timer's counter started (wall clock), or null for no counter.
        var counterFrom: Long? = null
        val v = state.settings.visibility

        if (primary != null) {
            val t = primary.timed
            val e = t.event
            val nudge = primary.overrun == OverrunState.NUDGE
            val side = fmt.sideLong(t.lastSide ?: Side.LEFT)
            val feedingMs = Durations.breast(t.segments, state.now).totalMs
            val titleText = when {
                nudge && e.kind == Kind.BREAST -> app.getString(R.string.notif_still_feeding, fmt.time(e.startAt))
                nudge -> app.getString(R.string.notif_still_asleep, fmt.time(e.startAt))
                t.isPaused -> app.getString(R.string.notif_feed_paused, side, fmt.duration(feedingMs))
                e.kind == Kind.BREAST -> app.getString(R.string.notif_feeding, side)
                else -> app.getString(R.string.notif_asleep)
            }
            title = named(titleText, e.childId)
            counterFrom = when {
                // Paused: nothing counts, so no counter.
                t.isPaused -> null
                // Feeding time without the pauses.
                e.kind == Kind.BREAST -> state.now - feedingMs
                else -> e.startAt
            }
            counterFrom?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true) } ?: b.setShowWhen(false)
            when {
                nudge -> top = app.getString(R.string.notif_nudge_text)
                t.isPaused -> feedLine = app.getString(R.string.notif_paused_at, fmt.time(t.pausedAt!!))
                e.kind == Kind.BREAST -> feedLine = app.getString(R.string.notif_started, fmt.time(e.startAt))
                else -> sleepLine = app.getString(R.string.notif_since, fmt.time(e.startAt))
            }
            for (other in state.running) {
                if (other === primary) continue
                val oe = other.timed.event
                if (oe.kind == Kind.BREAST) {
                    val oSide = fmt.sideLong(other.timed.lastSide ?: Side.LEFT)
                    feedLine = asText(
                        named(
                            other.timed.pausedAt?.let { app.getString(R.string.notif_also_paused, oSide, fmt.time(it)) }
                                ?: app.getString(R.string.notif_also_running, app.getString(R.string.notif_feeding, oSide), fmt.time(oe.startAt)),
                            oe.childId,
                        ),
                        state,
                    )
                } else {
                    sleepLine = asText(named(app.getString(R.string.notif_also_running, app.getString(R.string.notif_asleep), fmt.time(oe.startAt)), oe.childId), state)
                }
            }
            if (state.running.none { it.timed.event.kind == Kind.BREAST }) feedLine = lastFeedLine(state.lastFeed, state.lastSide, state.lastFeedMs, state)
            if (nudge) {
                actions += action(R.string.action_stop_now, ActionReceiver.ACTION_STOP, e.id)
                actions += action(R.string.action_still_going, ActionReceiver.ACTION_STILL_GOING, e.id)
            } else if (e.kind == Kind.BREAST) {
                // The same order while feeding and paused; only the middle button changes.
                actions += action(R.string.action_switch_side, ActionReceiver.ACTION_SWITCH, e.id)
                actions += if (t.isPaused) {
                    action(R.string.action_resume, ActionReceiver.ACTION_RESUME, e.id)
                } else {
                    action(R.string.action_pause, ActionReceiver.ACTION_PAUSE, e.id)
                }
                actions += action(R.string.action_stop, ActionReceiver.ACTION_STOP, e.id)
            } else {
                actions += action(R.string.action_woke_up, ActionReceiver.ACTION_STOP, e.id)
                actions += action(R.string.action_cancel, ActionReceiver.ACTION_CANCEL, e.id)
            }
        } else {
            title = named(lastFeedLine(state.lastFeed, state.lastSide, state.lastFeedMs, state) ?: app.getString(R.string.notif_no_feeds), state.childId)
            // No counter here: the title says when the last feed started and how long it lasted.
            b.setShowWhen(false)
            if (state.lastFeed?.kind == Kind.BOTTLE) state.lastSide?.let { feedLine = app.getString(R.string.notif_last_side, fmt.sideShort(it)) }
            state.awakeSince?.let { sleepLine = app.getString(R.string.notif_awake_since, fmt.time(it)) }
        }

        // Timers running twice after a sync (SPEC §9.5): the question takes the title and the buttons.
        state.conflicts.firstOrNull()?.let { k ->
            title = named(app.getString(R.string.conflict_title, k.timers.size, fmt.timedKind(k.kind).lowercase()), k.childId)
            top = conflictStarts(k)
            actions.clear()
            actions += conflictActions(k)
        }

        val r = app.resources
        val c = state.today
        val feedsToday = app.getString(R.string.notif_feeds_today, r.getQuantityString(R.plurals.count_feeds, c.feeds, c.feeds))
        val diapersToday = app.getString(
            R.string.notif_diapers_today,
            r.getQuantityString(R.plurals.count_wet, c.wet, c.wet),
            r.getQuantityString(R.plurals.count_poop, c.poop, c.poop),
        )
        // Sections, with a divider between them: the latest action (or the question), then the
        // feeding, sleep and diaper lines, or with several children one block per child.
        val head = mutableListOf<Row>()
        val body = mutableListOf<Row>()
        val sections = mutableListOf<List<Row>>(head, body)
        val recent = state.recent
        if (recent != null) {
            head += Row(app.getString(R.string.notif_recorded, fmt.action(recent.info, state.settings.unit), fmt.time(recent.info.at)))
        }
        top?.let { head += Row(it) }
        val showFeeds = v.shows(Kind.BREAST) || v.shows(Kind.BOTTLE)
        if (!state.severalChildren) {
            listOfNotNull(feedLine, feedsToday.takeIf { showFeeds }).takeIf { it.isNotEmpty() }?.let { body += Row(join(it, " · ")) }
            sleepLine?.takeIf { v.shows(Kind.SLEEP) || state.running.any { r -> r.timed.event.kind == Kind.SLEEP } }?.let { body += Row(it) }
            if (v.shows(Kind.DIAPER)) body += Row(diapersToday)
        }
        // Several children: every metric in a block per child, the active one first (SPEC §6.1).
        // Collapsed, the line under the title is about another child than the title's.
        var collapsed: Row? = (head + body).firstOrNull()
        if (state.severalChildren) {
            val titleChild = primary?.timed?.event?.childId ?: state.childId
            // This phone's child first, then children with a running timer (the most recently
            // started first), then the rest in the order they were added (DECISIONS, 2026-11-16).
            val timerOrder = state.running.map { it.timed.event.childId }.distinct()
            val blocks = listOfNotNull(
                state.childrenById[state.childId]?.let { me ->
                    ChildStatus(me, state.lastFeed, state.lastSide, state.lastFeedMs, state.awakeSince, state.today)
                },
            ) + state.others.sortedBy { o -> timerOrder.indexOf(o.child.id).let { if (it < 0) Int.MAX_VALUE else it } }
            val built = blocks.map { it.child.id to childBlock(it, state) }
            // Android caps a custom notification's height, buttons included, and never says what it
            // cut; so the children get a budget of lines (DECISIONS, 2026-11-16). Every block if
            // they fit; else this phone's child's block, then one summary line per other child,
            // and "+N more" when even those don't fit.
            if (built.sumOf { it.second.lines.size } <= CHILD_LINES_MAX) {
                for ((_, block) in built) sections += block.lines
            } else {
                val mine = built.first().second.lines
                sections += mine
                val others = built.drop(1)
                val room = CHILD_LINES_MAX - mine.size
                val brief = if (others.size <= room) others else others.take(room - 1)
                val hidden = others.size - brief.size
                sections += brief.map { it.second.summary } + listOfNotNull(
                    Row(r.getQuantityString(R.plurals.notif_more_children, hidden, hidden)).takeIf { hidden > 0 },
                )
            }
            if (collapsed == null) collapsed = built.firstOrNull { it.first != titleChild }?.second?.summary
        }

        if (recent != null) {
            val undo = NotificationCompat.Action.Builder(0, app.getString(R.string.action_undo), broadcast(ActionReceiver.ACTION_UNDO, undoId = recent.undoId))
                .setAuthenticationRequired(false)
                .build()
            // Android shows at most three buttons: [Undo] takes the first one's place (Switch side).
            if (actions.size >= 3) actions.removeAt(0)
            actions.add(0, undo)
        }
        // A notification shows at most three buttons.
        actions.take(3).forEach(b::addAction)

        // Collapsed, a notification shows one line of text: the first line, never a mix of topics.
        val shown = sections.filter { it.isNotEmpty() }
        val second = collapsed ?: shown.first().first()
        b.setContentTitle(asText(title, state))
        b.setContentText(asText(second, state))
        // Drawn as a layout: the dots in the children's exact colors and real lines (DECISIONS,
        // 2026-11-16). The title and text above stay for screen readers and the like.
        b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        b.setCustomContentView(rowsView(state, listOf(listOf(title.asTitle(counterFrom), second)), collapsed = true))
        // Expanded: the title opens the first section, with the latest action under it; the
        // counter is in Android's header.
        b.setCustomBigContentView(rowsView(state, listOf(listOf(title.asTitle()) + head) + sections.drop(1), collapsed = false))

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
     * One child's block when there are several (SPEC §6.1): the name, then one short line per topic,
     * so nothing wraps on a 360 dp screen:
     *
     *     Aoife
     *     Feeding L since 19:00
     *     Awake since 13:10
     *     3 feeds · 2 wet · 1 poop today
     */
    private fun childBlock(o: ChildStatus, state: HomeState): ChildBlock {
        val r = app.resources
        val v = state.settings.visibility
        val mine = state.running.filter { it.timed.event.childId == o.child.id }.map { it.timed }
        val feed = mine.firstOrNull { it.event.kind == Kind.BREAST }
        val sleep = mine.firstOrNull { it.event.kind == Kind.SLEEP }
        val c = o.today

        val feeding = when {
            feed?.isPaused == true -> app.getString(R.string.notif_child_paused, fmt.sideShort(feed.lastSide ?: Side.LEFT), fmt.time(feed.pausedAt!!))
            feed != null -> app.getString(R.string.notif_child_feeding, fmt.sideShort(feed.lastSide ?: Side.LEFT), fmt.time(feed.event.startAt))
            v.shows(Kind.BREAST) || v.shows(Kind.BOTTLE) ->
                lastFeedLine(o.lastFeed, o.lastSide, o.lastFeedMs, state) ?: app.getString(R.string.notif_no_feeds)
            else -> null
        }
        val sleeping = when {
            sleep != null -> app.getString(R.string.notif_child_asleep, fmt.time(sleep.event.startAt))
            v.shows(Kind.SLEEP) -> o.awakeSince?.let { app.getString(R.string.notif_awake_since, fmt.time(it)) }
            else -> null
        }
        val counts = listOfNotNull(
            r.getQuantityString(R.plurals.count_feeds, c.feeds, c.feeds).takeIf { v.shows(Kind.BREAST) || v.shows(Kind.BOTTLE) },
            r.getQuantityString(R.plurals.count_wet, c.wet, c.wet).takeIf { v.shows(Kind.DIAPER) },
            r.getQuantityString(R.plurals.count_poop, c.poop, c.poop).takeIf { v.shows(Kind.DIAPER) },
        ).takeIf { it.isNotEmpty() }?.let { app.getString(R.string.notif_child_today, it.joinToString(" · ")) }

        // A running timer's line counts up, like the title; a paused feed doesn't count.
        val feedRow = feeding?.let { Row(it, counterFrom = feed?.takeUnless { f -> f.isPaused }?.let { f -> state.now - Durations.breast(f.segments, state.now).totalMs }) }
        val sleepRow = sleeping?.let { Row(it, counterFrom = sleep?.event?.startAt) }
        val countsRow = counts?.let { Row(it) }
        // The one-line summary leads with a running timer, then the last feed.
        val lead = when {
            feed != null -> feedRow
            sleep != null -> sleepRow
            else -> feedRow ?: sleepRow ?: countsRow
        }
        val childName = fmt.childName(o.child.name)
        return ChildBlock(
            lines = listOf(Row(childName, o.child, Row.Style.NAME)) + listOfNotNull(feedRow, sleepRow, countsRow),
            summary = Row(listOfNotNull(childName, lead?.text).joinToString(" · "), o.child, counterFrom = lead?.counterFrom),
        )
    }

    /** "🔵 Aoife": the child's color as an emoji; a notification drops text colors (DECISIONS, 2026-11-16). */
    private fun label(child: Child, state: HomeState): CharSequence =
        childEmoji(child, state.colorIndex(child.id)) + " " + fmt.childName(child.name)

    /** [parts] joined with [separator], keeping their spans (TextUtils.join drops them). */
    private fun join(parts: List<CharSequence>, separator: String): CharSequence {
        val out = SpannableStringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) out.append(separator)
            out.append(part)
        }
        return out
    }

    /** A child's lines in the expanded notification, and its one-line summary for the collapsed view. */
    private class ChildBlock(val lines: List<Row>, val summary: Row)

    /**
     * One line of the status notification; with several children, [child]'s dot starts it. With
     * [counterFrom] (a running timer's start, wall clock), a counter ticks at its end.
     */
    private class Row(val text: CharSequence, val child: Child? = null, val style: Style = Style.TEXT, val counterFrom: Long? = null) {
        enum class Style(val layout: Int) {
            TITLE(R.layout.notif_row_title),
            NAME(R.layout.notif_row_name),
            TEXT(R.layout.notif_row),
        }

        fun asTitle(counterFrom: Long? = null) = Row(text, child, Style.TITLE, counterFrom)
    }

    /** A row as text: the dot is the child's emoji, the nearest that text gets to a color. */
    private fun asText(row: Row, state: HomeState): CharSequence {
        val text: CharSequence = row.child?.let { childEmoji(it, state.colorIndex(it.id)) + " " + row.text } ?: row.text
        if (row.style != Row.Style.NAME) return text
        return SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
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

    /** "Last feed R · 14:02 · 25m", "… · no end", or "Last feed bottle · 14:02 · 4 oz". */
    private fun lastFeedLine(feed: Event?, lastSide: Side?, lastFeedMs: Long?, state: HomeState): String? {
        feed ?: return null
        val what: String
        val detail: String?
        if (feed.kind == Kind.BREAST) {
            what = lastSide?.let(fmt::sideShort) ?: app.getString(R.string.kind_feed)
            detail = when (feed.status) {
                EventStatus.END_UNKNOWN -> app.getString(R.string.log_no_end)
                else -> lastFeedMs?.let(fmt::duration)
            }
        } else {
            what = app.getString(R.string.notif_bottle)
            detail = feed.amountMl?.let { fmt.volume(it, state.settings.unit) }
        }
        val start = fmt.time(feed.startAt)
        return if (detail != null) {
            app.getString(R.string.notif_last_feed_detail, what, start, detail)
        } else {
            app.getString(R.string.notif_last_feed, what, start)
        }
    }

    // ---- "Has no end" notifications (SPEC §5.5) ----

    @Suppress("MissingPermission") // only called after canPost()
    private fun postNoEnd(state: HomeState) {
        val wanted = state.noEnd.associateBy { it.event.id }
        val active = nm.activeNotifications.filter { it.id == ID_NO_END }.mapNotNull { it.tag }.toSet()
        for (tag in active - wanted.keys) nm.cancel(tag, ID_NO_END)
        for ((id, t) in wanted) {
            // Re-posted on every refresh (silently: only alert once), so its tap target follows the
            // lock-screen setting and its title follows the child's name and color.
            if (t.event.status != EventStatus.END_UNKNOWN) continue
            val e = t.event
            val n = NotificationCompat.Builder(app, CHANNEL_ATTENTION)
                .setSmallIcon(smallIcon())
                .setColor(app.getColor(R.color.mostor_notification))
                .setContentTitle(
                    join(
                        listOfNotNull(
                            app.getString(R.string.notif_no_end_title, fmt.timedKind(e.kind), fmt.time(e.startAt)),
                            state.childOf(e.childId)?.let { label(it, state) },
                        ),
                        " · ",
                    ),
                )
                .setContentText(app.getString(R.string.notif_no_end_text))
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
     * Re-posted silently on every refresh (only alert once); canceled once answered or gone. A
     * swipe stops it for that question; the status notification and the sheet still ask.
     */
    @Suppress("MissingPermission") // only called after canPost()
    private fun postConflicts(state: HomeState) {
        val wanted = state.conflicts.filter { it.key !in state.settings.conflictAlertsDismissed }.associateBy { it.key }
        val active = nm.activeNotifications.filter { it.id == ID_CONFLICT }.mapNotNull { it.tag }.toSet()
        for (tag in active - wanted.keys) nm.cancel(tag, ID_CONFLICT)
        for ((key, k) in wanted) {
            val title = app.getString(R.string.conflict_title, k.timers.size, fmt.timedKind(k.kind).lowercase())
            val b = NotificationCompat.Builder(app, CHANNEL_SYNC_QUESTIONS)
                .setSmallIcon(smallIcon())
                .setColor(app.getColor(R.color.mostor_notification))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentTitle(state.childOf(k.childId)?.let { TextUtils.concat(label(it, state), " · ", title) } ?: title)
                .setContentText(conflictStarts(k))
                .setStyle(NotificationCompat.BigTextStyle().bigText(conflictStarts(k)))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(sheetIntent(null, state))
                .setDeleteIntent(broadcast(ActionReceiver.ACTION_CONFLICT_DISMISSED, eventId = key))
            conflictActions(k).forEach(b::addAction)
            nm.notify(key, ID_CONFLICT, b.lockScreen(state).build())
        }
    }

    private fun conflictActions(k: io.github.uirlisi.mostor.core.TimerConflict) = listOf(
        action(R.string.conflict_keep_earlier, ActionReceiver.ACTION_KEEP_EARLIER, k.key),
        action(R.string.conflict_keep_later, ActionReceiver.ACTION_KEEP_LATER, k.key),
        action(R.string.conflict_keep_both, ActionReceiver.ACTION_KEEP_BOTH, k.key),
    )

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

    /** The Undo line lasts 60 s. The alarm is the fallback; this is the punctual path while the process lives. */
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

    /** "Started 21:02 and 21:04". */
    private fun conflictStarts(k: io.github.uirlisi.mostor.core.TimerConflict): String {
        val times = k.timers.map { fmt.time(it.event.startAt) }
        return app.getString(R.string.conflict_starts, times.dropLast(1).joinToString(", "), times.last()) + ". " +
            app.getString(R.string.conflict_question)
    }

    private fun action(label: Int, action: String, eventId: String) =
        NotificationCompat.Action.Builder(iconFor(label, action), app.getString(label), broadcast(action, eventId = eventId))
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
