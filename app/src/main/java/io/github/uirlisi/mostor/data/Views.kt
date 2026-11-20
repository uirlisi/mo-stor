package io.github.uirlisi.mostor.data

import androidx.room.withTransaction
import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.ChildChoice
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.DayCounts
import io.github.uirlisi.mostor.core.DaySummary
import io.github.uirlisi.mostor.core.DayTotals
import io.github.uirlisi.mostor.core.Dose
import io.github.uirlisi.mostor.core.Duplicates
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Feeds
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedDay
import io.github.uirlisi.mostor.core.MedStatus
import io.github.uirlisi.mostor.core.Meds
import io.github.uirlisi.mostor.core.Overrun
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.Patterns
import io.github.uirlisi.mostor.core.Series
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.data.Repository.Companion.RECENT_MS
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class RunningTimer(val timed: TimedEvent, val overrun: OverrunState)

data class RecentAction(val undoId: String, val info: ActionInfo, val createdAt: Long)

/** The 3am answers for one child: what the notification shows per child. */
data class ChildStatus(
    val child: Child,
    val lastFeed: Event?,
    val lastSide: Side?,
    val lastFeedMs: Long?,
    val awakeSince: Long?,
    val today: DayCounts,
)

/** "Synced with Galaxy S22 · 4m ago". */
data class LastSync(val peerName: String, val at: Long)

/** Everything the status notification and the quick sheet show. */
data class HomeState(
    val now: Long,
    /** The active child: what the quick sheet records for. */
    val childId: String,
    /** Active (not archived) children, in order. Child UI shows only when there are several. */
    val children: List<Child>,
    /** Every live child by id, archived ones included (names on running timers). */
    val childrenById: Map<String, Child>,
    /** The other active children's answers, for the notification's per-child lines. */
    val others: List<ChildStatus>,
    /** Most recently started first. */
    val running: List<RunningTimer>,
    /** Ends marked unknown in the last day whose notification hasn't been dismissed. */
    val noEnd: List<TimedEvent>,
    /**
     * Feeds ended automatically at their pause in the last day (SPEC §5.5), untouched since, with no
     * later feed and their notification not dismissed.
     */
    val autoEnded: List<TimedEvent> = emptyList(),
    val presets: List<SupplementPreset>,
    val lastFeed: Event?,
    /** The side of the last segment of the most recent breast feed. */
    val lastSide: Side?,
    /** How long the last feed lasted: null for a bottle, a running feed or an unknown end. */
    val lastFeedMs: Long?,
    val awakeSince: Long?,
    val lastDiaper: Event?,
    val lastPump: Event?,
    /** The active child's meds' status today (SPEC §5.10), by preset id. */
    val meds: Map<String, MedStatus>,
    /** Supplement preset id → when it was last given today. */
    val supplementsToday: Map<String, Long>,
    val today: DayCounts,
    /** The last notification action, for 10 s. */
    val recent: RecentAction?,
    val settings: SettingsSnapshot,
    /** When the notification must be redrawn next (overrun, day change, end of the Undo line). */
    val nextWakeAt: Long?,
    /** The most recent sync with another phone (Phase 5), or null before the first. */
    val lastSync: LastSync? = null,
    /** Running timers of one kind for one child, after a sync (SPEC §9.5); the owner chooses. */
    val conflicts: List<io.github.uirlisi.mostor.core.TimerConflict> = emptyList(),
) {
    val severalChildren: Boolean get() = children.size > 1

    /** The child to name on a record: only when there are several children. */
    fun childOf(childId: String): Child? = if (severalChildren) childrenById[childId] else null

    /** A child's place among all live children: picks its color when it has none of its own. */
    fun colorIndex(childId: String): Int = childrenById.keys.indexOf(childId)
}

/** One child's day summary on Today ([child] is null with a single child), with its Meds row. */
data class ChildTotals(val child: Child?, val totals: DayTotals, val meds: MedDay? = null)

data class DayView(
    val day: LocalDate,
    val isToday: Boolean,
    val endMs: Long,
    val items: List<DayItem>,
    val counts: DayCounts,
    val totals: List<ChildTotals>,
    /** Records left out because they are hidden on this phone. */
    val hiddenCount: Int,
    /** The children shown (Phase 3c: one, several or all), in the app's child order. */
    val childIds: List<String>,
) {
    /** Several children are shown: summary cards and timeline rows name their child. */
    val several: Boolean get() = childIds.size > 1
}

/** A log line: an event, with its segments when it's a timed event, and possible duplicates (SPEC §5.6). */
data class DayItem(val event: Event, val timed: TimedEvent?, val duplicates: Set<String> = emptySet())

/** Log filters (SPEC §6.5). Null, or no children, means any. */
data class LogFilter(
    /** The children to show (Phase 3c: several at once). */
    val childIds: Set<String> = emptySet(),
    val kind: Kind? = null,
    val subtype: String? = null,
    val showHidden: Boolean = false,
)

data class LogDay(val day: LocalDate, val items: List<DayItem>)

data class LogView(
    val days: List<LogDay>,
    /** Whether there are records before the loaded range. */
    val hasOlder: Boolean,
    val hiddenCount: Int,
)

/**
 * The read side of the repository: the status notification's and the quick sheet's state, and the
 * Today, Log and chart views. Reads only; it never takes the write lock. Reached through
 * [Repository]'s read methods.
 */
internal class Views(private val core: DataCore) {
    private val db = core.db
    private val wallClock = core.wallClock
    private val zone = core.zone

    private suspend fun ensureInitialized() = core.ensureInitialized()
    private fun changes() = core.changes()
    private suspend fun settings() = core.settings()
    private suspend fun childId() = core.childId()
    private suspend fun timed(e: EventEntity) = core.timed(e)

    /** Built off the main thread, like every view below: grouping a month of records takes a while. */
    fun homeStates(): Flow<HomeState> = changes().conflate().map { homeState() }.flowOn(Dispatchers.Default)

    /**
     * The status notification's and the quick sheet's view, read in one transaction so its parts
     * agree: a sync that deletes or archives the active child between two reads can't make it throw.
     * Takes no write lock (reads only), so it never waits on, or holds up, a write's lock.
     */
    suspend fun homeState(): HomeState {
        ensureInitialized()
        return db.withTransaction { readHomeState() }
    }

    private suspend fun readHomeState(): HomeState {
        val now = wallClock()
        val s = settings()
        val child = childId()
        val days = DayClock(s.dayStartHour, zone())
        val all = db.children().live().map { it.toModel() }
        val active = all.filter { it.archivedAt == null }

        val running = db.events().running().map { e ->
            val t = timed(e)
            RunningTimer(t, Overrun.state(t, now, s.limitsFor(t.event.kind), s.snoozes[e.id]))
        }
        val noEnd = db.events().endUnknownSince(now - NO_END_WINDOW_MS)
            .filter { it.id !in s.noEndDismissed }
            .map { timed(it) }
        val autoEnded = db.events().endedAutomaticallySince(now - NO_END_WINDOW_MS)
            .filter { it.id !in s.noEndDismissed }
            .filter { e -> db.events().latestOfKinds(e.childId, FEED_KINDS)?.let { it.id == e.id || it.startAt <= e.startAt } ?: true }
            .map { timed(it) }
        val today = days.dayOf(now)
        // Hidden kinds and subtypes leave the summary (SPEC §6.6).
        val todays = db.events().inRange(days.startOf(today), days.endOf(today)).map { it.toModel() }
            .filter { s.visibility.shows(it) }
        val mine = status(all.first { it.id == child }, todays, today, days, now)
        val others = if (active.size > 1) active.filter { it.id != child }.map { status(it, todays, today, days, now) } else emptyList()
        val recent = db.undo().latest()
            ?.takeIf { it.undoneAt == null && it.source == Source.NOTIFICATION.code && now - it.createdAt < RECENT_MS }
            ?.let { RecentAction(it.id, UndoCodec.decode(it.payload).info, it.createdAt) }

        val presets = db.presets().live().map { it.toModel() }
        val doses = dosesOf(child, days.endOf(today), days)

        val wakeTimes = running.mapNotNull {
            Overrun.nextCheck(it.timed, now, s.limitsFor(it.timed.event.kind), s.snoozes[it.timed.event.id])
        } + listOfNotNull(days.nextDayStart(now), recent?.let { it.createdAt + RECENT_MS })

        return HomeState(
            now = now,
            childId = child,
            children = active,
            childrenById = all.associateBy { it.id },
            others = others,
            running = running,
            noEnd = noEnd,
            autoEnded = autoEnded,
            presets = presets,
            meds = presets.filter { it.childId == child }.associate { p ->
                p.id to Meds.status(p, doses[p.id].orEmpty(), today)
            },
            lastFeed = mine.lastFeed,
            lastSide = mine.lastSide,
            lastFeedMs = mine.lastFeedMs,
            awakeSince = mine.awakeSince,
            lastDiaper = db.events().latestOfKinds(child, listOf(Kind.DIAPER.code))?.toModel(),
            lastPump = db.events().latestOfKinds(child, listOf(Kind.PUMP.code))?.toModel(),
            supplementsToday = todays.filter { it.kind == Kind.SUPPLEMENT && it.childId == child && it.supplementPresetId != null }
                .groupBy { it.supplementPresetId!! }
                .mapValues { (_, es) -> es.maxOf { it.startAt } },
            today = mine.today,
            recent = recent,
            settings = s,
            nextWakeAt = wakeTimes.minOrNull(),
            lastSync = db.peers().all().filter { it.lastSyncAt != null }.maxByOrNull { it.lastSyncAt!! }
                ?.let { LastSync(it.name, it.lastSyncAt!!) },
            conflicts = io.github.uirlisi.mostor.core.Conflicts.find(running.map { it.timed }, s.conflictsKept),
        )
    }

    /** One child's last feed, last side, awake since and today's counts ([todays]: the day's visible records). */
    private suspend fun status(child: Child, todays: List<Event>, today: LocalDate, days: DayClock, now: Long): ChildStatus {
        val lastFeed = db.events().latestOfKinds(child.id, FEED_KINDS)?.toModel()
        val lastBreast = db.events().latestOfKinds(child.id, listOf(Kind.BREAST.code))?.let { timed(it) }
        val sleeps = db.events().recentOfKind(child.id, Kind.SLEEP.code, 5).map { it.toModel() }
        return ChildStatus(
            child = child,
            lastFeed = lastFeed,
            lastSide = Feeds.lastSide(lastBreast),
            lastFeedMs = lastBreast?.takeIf { it.event.id == lastFeed?.id && it.event.status == EventStatus.ENDED }
                ?.let { Durations.of(it, now) },
            awakeSince = Durations.awakeSince(sleeps),
            today = DaySummary.counts(todays.filter { it.childId == child.id }, today, days),
        )
    }

    /**
     * Today + [dayOffset] days (negative = earlier): the summary and the timeline, newest first,
     * for the children chosen on Today (by default the active child). Hidden kinds are left out
     * unless [showHidden].
     */
    suspend fun dayView(dayOffset: Int, showHidden: Boolean = false): DayView {
        ensureInitialized()
        val now = wallClock()
        val s = settings()
        val days = DayClock(s.dayStartHour, zone())
        val today = days.dayOf(now)
        val day = today.plusDays(dayOffset.toLong())
        val active = db.children().live().map { it.toModel() }.filter { it.archivedAt == null }
        val child = childId()
        val chosen = ChildChoice.parseToday(s.todayChild, active.map { it.id }, child)
        // Every child: archived children's records stay in, as they always have.
        val all = active.size > 1 && chosen.size == active.size

        // From the day before, so a sleep across midnight counts in this day's totals.
        val events = db.events().inRange(days.startOf(day.minusDays(1)), days.endOf(day)).map { it.toModel() }
        val segments = segmentsOf(events)
        val scoped = events.filter { all || it.childId in chosen }
        val visible = scoped.filter { s.visibility.shows(it) }
        val inDay = (if (showHidden) scoped else visible).filter { days.dayOf(it.startAt) == day }
        val dups = Duplicates.find(events.filter { days.dayOf(it.startAt) == day })
        val items = inDay.map { e -> DayItem(e, if (e.kind.timed) TimedEvent.of(e, segments) else null, dups[e.id].orEmpty()) }
        val presets = db.presets().live().map { it.toModel() }
        val totals = active.filter { it.id in chosen }.map { c ->
            // The child's meds shown on this phone, in their order (SPEC §5.10).
            val meds = presets.filter { it.childId == c.id && s.visibility.showsPreset(it.id) }
                .takeIf { s.visibility.shows(Kind.SUPPLEMENT) }.orEmpty()
            ChildTotals(
                if (active.size > 1) c else null,
                DaySummary.totals(visible.filter { it.childId == c.id }, segments, day, days, now),
                Meds.day(meds, dosesOf(c.id, days.endOf(day), days), day, day == today),
            )
        }
        val hidden = scoped.count { days.dayOf(it.startAt) == day && !s.visibility.shows(it) }
        return DayView(day, day == today, days.endOf(day), items, DaySummary.counts(visible, day, days), totals, hidden, chosen)
    }

    fun dayViews(dayOffset: Int, showHidden: Boolean): Flow<DayView> =
        changes().conflate().map { dayView(dayOffset, showHidden) }.flowOn(Dispatchers.Default)

    /**
     * The log (SPEC §6.5): the last [dayCount] days, newest first, grouped by day, filtered.
     * Records hidden on this phone are left out unless [LogFilter.showHidden].
     */
    suspend fun logView(dayCount: Int, filter: LogFilter): LogView {
        ensureInitialized()
        val now = wallClock()
        val s = settings()
        val days = DayClock(s.dayStartHour, zone())
        val today = days.dayOf(now)
        val from = days.startOf(today.minusDays(dayCount - 1L))
        val events = db.events().inRange(from, days.endOf(today)).map { it.toModel() }
        val segments = segmentsOf(events)
        val dups = Duplicates.find(events)
        val matching = events.filter { e ->
            (filter.childIds.isEmpty() || e.childId in filter.childIds) &&
                (filter.kind == null || e.kind == filter.kind) &&
                (filter.subtype == null || e.subtype == filter.subtype)
        }
        val shown = if (filter.showHidden) matching else matching.filter { s.visibility.shows(it) }
        val grouped = shown.groupBy { days.dayOf(it.startAt) }.toSortedMap(reverseOrder()).map { (day, es) ->
            LogDay(day, es.map { e -> DayItem(e, if (e.kind.timed) TimedEvent.of(e, segments) else null, dups[e.id].orEmpty()) })
        }
        val earliest = db.events().earliestStart()
        return LogView(grouped, earliest != null && earliest < from, matching.size - shown.size)
    }

    fun logViews(dayCount: Int, filter: LogFilter): Flow<LogView> =
        changes().conflate().map { logView(dayCount, filter) }.flowOn(Dispatchers.Default)

    /**
     * What Patterns and Stats draw (SPEC §5.8, §6.5) for the chart child: the last [range] days up
     * to today, or every day from the child's first record. [focus] narrows the records; records
     * hidden on this phone are left out unless [showHidden]. The pattern rows and the gaps between
     * feeds are computed only when asked for.
     */
    suspend fun chartView(
        range: ChartRange,
        focus: ChartFocus,
        showHidden: Boolean,
        withPatterns: Boolean,
        withGaps: Boolean,
    ): ChartView {
        ensureInitialized()
        val now = wallClock()
        val s = settings()
        val clock = DayClock(s.dayStartHour, zone())
        val today = clock.dayOf(now)
        val children = db.children().live().map { it.toModel() }
        val childId = s.chartChild?.takeIf { id -> children.any { it.id == id } } ?: childId()
        val first = db.events().earliestStartOf(childId)
        val from = range.days?.let { today.minusDays(it - 1L) } ?: first?.let { minOf(clock.dayOf(it), today) } ?: today

        // From the day before, so a sleep across midnight and the first gap between feeds count.
        val mine = db.events().inRange(clock.startOf(from.minusDays(1)), clock.endOf(today)).map { it.toModel() }
            .filter { it.childId == childId }
        val segments = segmentsOf(mine)
        val selected = ChartFocus.select(mine, focus, s.visibility, showHidden)
        val inRange = { e: Event -> clock.dayOf(e.startAt) >= from }
        val hidden = if (showHidden) 0 else ChartFocus.select(mine, focus, s.visibility, true).count { inRange(it) && !s.visibility.shows(it) }

        return ChartView(
            now = now,
            clock = clock,
            today = today,
            from = from,
            to = today,
            childId = childId,
            children = children,
            firstDay = first?.let(clock::dayOf),
            points = Series.days(selected, segments, from, today, clock, now, first, focus),
            patterns = if (withPatterns) Patterns.grid(selected, segments, from, today, clock, now, focus) else emptyList(),
            gaps = if (withGaps) Series.feedGaps(selected, from, today, clock) else emptyList(),
            hiddenCount = hidden,
            // The chart child's meds only (each child has their own).
            presets = db.presets().live().map { it.toModel() }.filter { it.childId == childId },
            settings = s,
        )
    }

    /** [chartView] whenever the data or a setting changes, computed off the main thread. */
    fun chartViews(range: ChartRange, focus: ChartFocus, showHidden: Boolean, withPatterns: Boolean, withGaps: Boolean): Flow<ChartView> =
        changes().conflate().map { chartView(range, focus, showHidden, withPatterns, withGaps) }.flowOn(Dispatchers.Default)

    private suspend fun segmentsOf(events: List<Event>): List<BreastSegment> {
        val ids = events.filter { it.kind == Kind.BREAST }.map { it.id }
        return ids.chunked(500).flatMap { db.events().liveSegments(it) }.map { it.toModel() }
    }

    /** A child's med doses before [before], by preset id, on the app's days. */
    private suspend fun dosesOf(childId: String, before: Long, days: DayClock): Map<String, List<Dose>> =
        db.events().doses(childId, before).filter { it.supplementPresetId != null }
            .groupBy({ it.supplementPresetId!! }) { Dose(it.startAt, days.dayOf(it.startAt), it.status == EventStatus.SKIPPED.code) }

    private companion object {
        val FEED_KINDS = listOf(Kind.BREAST.code, Kind.BOTTLE.code)
        const val NO_END_WINDOW_MS = 24 * 3_600_000L
    }
}
