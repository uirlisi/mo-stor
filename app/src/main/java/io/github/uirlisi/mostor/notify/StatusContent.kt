package io.github.uirlisi.mostor.notify

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.core.TimerConflict
import io.github.uirlisi.mostor.data.ChildStatus
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.childEmoji

/**
 * One line of the status notification; with several children, [child]'s dot starts it. With
 * [counterFrom] (a running timer's start, wall clock), a counter ticks at its end.
 */
internal class Row(val text: CharSequence, val child: Child? = null, val style: Style = Style.TEXT, val counterFrom: Long? = null) {
    enum class Style(val layout: Int) {
        TITLE(R.layout.notif_row_title),
        NAME(R.layout.notif_row_name),
        TEXT(R.layout.notif_row),
    }

    fun asTitle(counterFrom: Long? = null) = Row(text, child, Style.TITLE, counterFrom)
}

/**
 * A notification button as data: [label] is its string resource, [action] an [ActionReceiver]
 * action, sent with [eventId] or [undoId].
 */
internal data class StatusAction(val label: Int, val action: String, val eventId: String? = null, val undoId: String? = null)

/** What the status notification shows (SPEC §6.1, §6.2), before it is drawn. */
internal data class StatusModel(
    /** The title row: the running timer, the last feed, or the two-timers question. */
    val title: Row,
    /**
     * When the running timer's counter started (wall clock), or null for no counter. With one, the
     * header shows it as a chronometer and the collapsed title counts up.
     */
    val counterFrom: Long?,
    /** In the header after the app's name: the household's name, when the phone holds several. */
    val subText: String?,
    /**
     * The expanded body's sections, with a divider between them: first the latest action (or the
     * question), then the feeding, sleep and diaper lines, or one block per child. Some may be empty.
     */
    val sections: List<List<Row>>,
    /** The line under the title when collapsed. */
    val collapsed: Row,
    /** The buttons, at most three, in order. */
    val actions: List<StatusAction>,
)

/**
 * Decides what the status notification says, from a [HomeState]: the text, the rows, the counter
 * and the buttons. [StatusNotifier] draws it.
 */
internal class StatusContent(context: Context, private val fmt: Formats) {
    private val app = context

    fun build(state: HomeState, householdName: String? = null): StatusModel {
        // The body has one line per topic, always in this order: feeding, sleep, diapers.
        var top: String? = null
        var feedLine: CharSequence? = null
        var sleepLine: CharSequence? = null
        val actions = mutableListOf<StatusAction>()
        val primary = state.running.firstOrNull { it.overrun == OverrunState.NUDGE } ?: state.running.firstOrNull()
        var title: Row
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
            title = named(titleText, e.childId, state)
            counterFrom = when {
                // Paused: nothing counts, so no counter.
                t.isPaused -> null
                // Feeding time without the pauses.
                e.kind == Kind.BREAST -> state.now - feedingMs
                else -> e.startAt
            }
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
                            state,
                        ),
                        state,
                    )
                } else {
                    sleepLine = asText(named(app.getString(R.string.notif_also_running, app.getString(R.string.notif_asleep), fmt.time(oe.startAt)), oe.childId, state), state)
                }
            }
            if (state.running.none { it.timed.event.kind == Kind.BREAST }) feedLine = lastFeedLine(state.lastFeed, state.lastSide, state.lastFeedMs, state)
            if (nudge) {
                actions += StatusAction(R.string.action_stop_now, ActionReceiver.ACTION_STOP, e.id)
                actions += StatusAction(R.string.action_still_going, ActionReceiver.ACTION_STILL_GOING, e.id)
            } else if (e.kind == Kind.BREAST) {
                // The same order while feeding and paused; only the middle button changes.
                actions += StatusAction(R.string.action_switch_side, ActionReceiver.ACTION_SWITCH, e.id)
                actions += if (t.isPaused) {
                    StatusAction(R.string.action_resume, ActionReceiver.ACTION_RESUME, e.id)
                } else {
                    StatusAction(R.string.action_pause, ActionReceiver.ACTION_PAUSE, e.id)
                }
                actions += StatusAction(R.string.action_stop, ActionReceiver.ACTION_STOP, e.id)
            } else {
                actions += StatusAction(R.string.action_woke_up, ActionReceiver.ACTION_STOP, e.id)
                actions += StatusAction(R.string.action_cancel, ActionReceiver.ACTION_CANCEL, e.id)
            }
        } else {
            // No counter here: the title says when the last feed started and how long it lasted.
            title = named(lastFeedLine(state.lastFeed, state.lastSide, state.lastFeedMs, state) ?: app.getString(R.string.notif_no_feeds), state.childId, state)
            if (state.lastFeed?.kind == Kind.BOTTLE) state.lastSide?.let { feedLine = app.getString(R.string.notif_last_side, fmt.sideShort(it)) }
            state.awakeSince?.let { sleepLine = app.getString(R.string.notif_awake_since, fmt.time(it)) }
        }

        // Timers running twice after a sync (SPEC §9.5): the question takes the title and the buttons.
        state.conflicts.firstOrNull()?.let { k ->
            title = named(app.getString(R.string.conflict_title, k.timers.size, fmt.timedKind(k.kind).lowercase()), k.childId, state)
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
        val showFeeds = v.showsFeeds
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

        // While a two-timers question is asked, its three answers keep the buttons ([Undo] would take
        // [Keep earlier]'s place); the Undo is still in the quick sheet.
        if (recent != null && state.conflicts.isEmpty()) {
            // Android shows at most three buttons: [Undo] takes the first one's place (Switch side).
            if (actions.size >= 3) actions.removeAt(0)
            actions.add(0, StatusAction(R.string.action_undo, ActionReceiver.ACTION_UNDO, undoId = recent.undoId))
        }

        // Collapsed, a notification shows one line of text: the first line, never a mix of topics.
        val shown = sections.filter { it.isNotEmpty() }
        // With every topic hidden and nothing running, no section has a line: say what a tap does.
        val second = collapsed ?: shown.firstOrNull()?.first() ?: Row(app.getString(R.string.notif_hidden_text))
        return StatusModel(
            title = title,
            counterFrom = counterFrom,
            subText = householdName,
            sections = sections,
            collapsed = second,
            // A notification shows at most three buttons.
            actions = actions.take(3),
        )
    }

    private fun named(text: String, childId: String, state: HomeState): Row = state.childOf(childId).let { ch ->
        Row(ch?.let { "${fmt.childName(it.name)} · $text" } ?: text, ch)
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
            v.showsFeeds ->
                lastFeedLine(o.lastFeed, o.lastSide, o.lastFeedMs, state) ?: app.getString(R.string.notif_no_feeds)
            else -> null
        }
        val sleeping = when {
            sleep != null -> app.getString(R.string.notif_child_asleep, fmt.time(sleep.event.startAt))
            v.shows(Kind.SLEEP) -> o.awakeSince?.let { app.getString(R.string.notif_awake_since, fmt.time(it)) }
            else -> null
        }
        val counts = listOfNotNull(
            r.getQuantityString(R.plurals.count_feeds, c.feeds, c.feeds).takeIf { v.showsFeeds },
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

    /** A child's lines in the expanded notification, and its one-line summary for the collapsed view. */
    private class ChildBlock(val lines: List<Row>, val summary: Row)

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

    /**
     * Title and text of a "has no end" notification, or of a timer the app stopped by itself (SPEC
     * §5.5): given up ("end unknown"), or a feed ended at its forgotten pause.
     */
    fun stoppedTexts(t: TimedEvent): Pair<String, String> {
        val e = t.event
        val kind = fmt.timedKind(e.kind)
        val start = fmt.time(e.startAt)
        return when {
            e.autoBase == null -> app.getString(R.string.notif_no_end_title, kind, start) to app.getString(R.string.notif_no_end_text)
            e.status == EventStatus.END_UNKNOWN -> app.getString(R.string.notif_auto_title, kind, start) to app.getString(R.string.notif_auto_no_end_text)
            else -> app.getString(R.string.notif_auto_title, kind, start) to app.getString(R.string.notif_auto_paused_text, fmt.time(e.endAt ?: e.startAt))
        }
    }

    /** The two-timers question's answers (SPEC §9.5), for the status notification and its own. */
    fun conflictActions(k: TimerConflict): List<StatusAction> = listOf(
        StatusAction(R.string.conflict_keep_earlier, ActionReceiver.ACTION_KEEP_EARLIER, k.key),
        StatusAction(R.string.conflict_keep_later, ActionReceiver.ACTION_KEEP_LATER, k.key),
        StatusAction(R.string.conflict_keep_both, ActionReceiver.ACTION_KEEP_BOTH, k.key),
    )

    /** "Started 21:02 and 21:04". */
    fun conflictStarts(k: TimerConflict): String {
        val times = k.timers.map { fmt.time(it.event.startAt) }
        val starts = app.getString(R.string.conflict_starts, times.dropLast(1).joinToString(app.getString(R.string.list_separator)), times.last())
        return app.getString(R.string.conflict_text, starts, app.getString(R.string.conflict_question))
    }

    /** "🔵 Aoife": the child's color as an emoji; a notification drops text colors (DECISIONS, 2026-11-16). */
    fun label(child: Child, state: HomeState): CharSequence =
        childEmoji(child, state.colorIndex(child.id)) + " " + fmt.childName(child.name)

    /** [parts] joined with [separator], keeping their spans (TextUtils.join drops them). */
    fun join(parts: List<CharSequence>, separator: String): CharSequence {
        val out = SpannableStringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) out.append(separator)
            out.append(part)
        }
        return out
    }

    /** A row as text: the dot is the child's emoji, the nearest that text gets to a color. */
    fun asText(row: Row, state: HomeState): CharSequence {
        val text: CharSequence = row.child?.let { childEmoji(it, state.colorIndex(it.id)) + " " + row.text } ?: row.text
        if (row.style != Row.Style.NAME) return text
        return SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private companion object {
        /**
         * Lines for the children's blocks. The tallest notification that keeps its buttons on the
         * Android 17 emulator: about ten lines, of which the title and the latest action take two.
         */
        const val CHILD_LINES_MAX = 8
    }
}
