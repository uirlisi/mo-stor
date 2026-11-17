package io.github.uirlisi.mostor.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import io.github.uirlisi.mostor.core.BabyTracker
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.ChangeContext
import io.github.uirlisi.mostor.core.ChildChoice
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Children
import io.github.uirlisi.mostor.core.DayTotals
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.DayCounts
import io.github.uirlisi.mostor.core.DaySummary
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Duplicates
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.EditProblem
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventDraft
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Feeds
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.HybridClock
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Dose
import io.github.uirlisi.mostor.core.MedDay
import io.github.uirlisi.mostor.core.MedPlan
import io.github.uirlisi.mostor.core.MedStatus
import io.github.uirlisi.mostor.core.Meds
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.ChildMatch
import io.github.uirlisi.mostor.core.Sync
import io.github.uirlisi.mostor.sync.KeyVault
import io.github.uirlisi.mostor.sync.SyncStore
import io.github.uirlisi.mostor.core.CsvExport
import io.github.uirlisi.mostor.core.RowChange
import io.github.uirlisi.mostor.core.Overrun
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.Presets
import io.github.uirlisi.mostor.core.Records
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.core.Timers
import io.github.uirlisi.mostor.core.Undo
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Patterns
import io.github.uirlisi.mostor.core.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called after every committed write (CLAUDE.md: every write refreshes the status notification). */
fun interface StatusSink {
    suspend fun refresh()
}

/** Where an action was made. Notification actions get the 60 s "Recorded · Undo" line (SPEC §5.7). */
enum class Source(val code: String) {
    SHEET("sheet"),
    NOTIFICATION("notification"),
    APP("app"),
}

/** A committed user action: what to show in the confirmation strip, and how to undo it. */
data class Done(val undoId: String, val info: ActionInfo)

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

/** Everything the status notification and the quick sheet show. */
/** "Synced with Galaxy S22 · 4m ago". */
data class LastSync(val peerName: String, val at: Long)

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
    /** The last notification action, for 60 s. */
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

/** An event as loaded into the edit screen. */
data class Editing(val event: Event, val segments: List<BreastSegment>)

/** What saving an edit did. */
sealed interface SaveResult {
    data class Saved(val done: Done) : SaveResult

    /** Nothing differed. */
    data object Unchanged : SaveResult

    /** The record changed (e.g. stopped from the notification) since the edit screen loaded it. */
    data object Stale : SaveResult

    data class Invalid(val problems: List<EditProblem>) : SaveResult

    /** It would make two timers of the same kind run for one child (SPEC §4.4). */
    data object Refused : SaveResult
}

/**
 * The only class that writes to the database (CLAUDE.md). Writes are serialized, each runs in one
 * transaction, stamps fresh HLCs, stores an undo entry, and then refreshes the notification.
 */
class Repository(
    private val db: MoStorDb,
    private val sink: StatusSink,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = Locale::getDefault,
    /**
     * This install's id from a file Android never backs up (null: no check, as in tests that don't
     * need it). Compared with the copy in the database to notice a database moved from another phone.
     */
    private val installId: (() -> String)? = null,
    /** Wraps the household key at rest (Phase 5); null in tests that don't sync. */
    private val vault: KeyVault? = null,
) : SyncStore {
    private val writeLock = Mutex()
    private val initLock = Mutex()

    @Volatile
    private var clock: HybridClock? = null

    private inner class Cx(override val now: Long, override val tz: String, private val hlc: HybridClock) : ChangeContext {
        override fun newId(): String = Ids.uuidV7(now)
        override fun nextHlc(): String = hlc.now().encode()

        /** Moves this phone's clock past a stamp from elsewhere, so later changes sort after it. */
        fun observe(stamp: HlcStamp) {
            hlc.receive(stamp)
        }
    }

    // ---- Setup ----

    /** Creates this phone's id and the implicit child with their seeded meds on first use. */
    suspend fun ensureInitialized(): HybridClock {
        clock?.let { return it }
        return initLock.withLock {
            clock ?: db.withTransaction {
                val now = wallClock()
                var meta = db.meta().get()
                    ?: MetaEntity(deviceId = Ids.uuidV7(now), deviceName = null, hlc = null).also { db.meta().upsert(it) }
                installId?.let { read ->
                    val mine = read()
                    val stored = db.settings().get(Keys.INSTALL_ID)
                    if (stored != null && stored != mine) {
                        // The database came from another phone (a phone-to-phone transfer): this phone
                        // takes its own device id, so its changes are never taken for the other
                        // phone's. The clock keeps its time, so later stamps still sort after the old.
                        meta = meta.copy(deviceId = Ids.uuidV7(now)).also { db.meta().upsert(it) }
                        // Its household key can't be opened here, and its peers know the old id:
                        // this phone pairs again (SPEC §9.4).
                        for (key in listOf(Keys.HOUSEHOLD_ID, Keys.HOUSEHOLD_KEY, Keys.SYNC_PENDING_MATCH)) db.settings().delete(key)
                        db.peers().deleteAll()
                    }
                    if (stored != mine) db.settings().put(SettingEntity(Keys.INSTALL_ID, mine))
                }
                val c = HybridClock(meta.deviceId, meta.hlc?.let(HlcStamp::parse), wallClock)
                // The implicit first child, with their own Vit D and Vit K (schema version 2).
                if (db.children().countLive() == 0) {
                    val child = Child(id = Ids.uuidV7(now), createdAt = now, updatedAt = now, hlc = c.now().encode())
                    db.children().upsert(listOf(child.toEntity()))
                    db.presets().upsert(Presets.seeds(child.id, now).map { it.toEntity() })
                }
                c.last?.let { db.meta().setHlc(it.encode()) }
                c
            }.also { clock = it }
        }
    }

    // ---- Recording (SPEC §6.3) ----

    /**
     * [Left] / [Right]: starts a feed, switches side if one is running on the other side, or
     * resumes a paused feed on that side.
     */
    suspend fun tapSide(side: Side, source: Source): Done? = write { cx ->
        val child = childId()
        val running = runningOf(child, Kind.BREAST)
        when {
            running == null -> {
                val change = Timers.start(Kind.BREAST, child, cx.now, cx, side)
                commit(change, ActionInfo(Verb.START, Kind.BREAST, change.newEventId(), cx.now, side = side), source, cx)
            }
            running.isPaused -> commit(
                Timers.resume(running, side, cx.now, cx),
                ActionInfo(Verb.RESUME, Kind.BREAST, running.event.id, cx.now, side = side),
                source,
                cx,
            )
            running.openSegment?.side == side -> null
            else -> commit(
                Timers.switchSide(running, cx.now, cx),
                ActionInfo(Verb.SWITCH, Kind.BREAST, running.event.id, cx.now, side = side),
                source,
                cx,
            )
        }
    }

    /** [Switch side]; on a paused feed it resumes on the other side. */
    suspend fun switchSide(eventId: String, source: Source): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        val side = t.lastSide?.other ?: return@write null
        val verb = if (t.isPaused) Verb.RESUME else Verb.SWITCH
        commit(Timers.switchSide(t, cx.now, cx), ActionInfo(verb, Kind.BREAST, eventId, cx.now, side = side), source, cx)
    }

    /** [Pause] a breast feed. */
    suspend fun pause(eventId: String, source: Source): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        val side = t.openSegment?.side ?: return@write null
        commit(Timers.pause(t, cx.now, cx), ActionInfo(Verb.PAUSE, Kind.BREAST, eventId, cx.now, side = side), source, cx)
    }

    /** [Resume] a paused feed, on the side it was paused on. */
    suspend fun resume(eventId: String, source: Source): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        val side = t.lastSide ?: return@write null
        commit(Timers.resume(t, side, cx.now, cx), ActionInfo(Verb.RESUME, Kind.BREAST, eventId, cx.now, side = side), source, cx)
    }

    suspend fun startSleep(source: Source): Done? = write { cx ->
        val child = childId()
        if (runningOf(child, Kind.SLEEP) != null) return@write null
        val change = Timers.start(Kind.SLEEP, child, cx.now, cx)
        commit(change, ActionInfo(Verb.START, Kind.SLEEP, change.newEventId(), cx.now), source, cx)
    }

    /**
     * Stops a running timer [minutesAgo] minutes ago, or at [at] (a picked time). Also sets the
     * end of an event whose end was marked unknown.
     */
    suspend fun stop(eventId: String, source: Source, minutesAgo: Int = 0, at: Long? = null): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        val change = Timers.stop(t, at ?: (cx.now - minutesAgo * 60_000L), cx)
        val end = change.events.firstOrNull()?.after?.endAt ?: return@write null
        forgetTimerState(eventId)
        val verb = if (t.event.status == EventStatus.END_UNKNOWN) Verb.SET_END else Verb.STOP
        commit(change, ActionInfo(verb, t.event.kind, eventId, end), source, cx)
    }

    /** Discards a timer that shouldn't count (undoable). */
    suspend fun cancel(eventId: String, source: Source): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        forgetTimerState(eventId)
        commit(Timers.cancel(t, cx), ActionInfo(Verb.CANCEL, t.event.kind, eventId, cx.now), source, cx)
    }

    /**
     * The owner's choice for timers running twice (SPEC §9.5), named by [key]: keep the earlier, the
     * later (the other is canceled, and Undo brings it back), or both (remembered on this phone).
     */
    suspend fun resolveConflict(key: String, keep: io.github.uirlisi.mostor.core.Keep, source: Source): Done? = write { cx ->
        val running = db.events().running().map { timed(it) }
        val conflict = io.github.uirlisi.mostor.core.Conflicts.find(running, emptySet()).firstOrNull { it.key == key }
            ?: return@write null
        if (keep == io.github.uirlisi.mostor.core.Keep.BOTH) {
            putSetting(Keys.CONFLICT_KEPT_PREFIX + key, "1")
            return@write null
        }
        val change = io.github.uirlisi.mostor.core.Conflicts.resolve(conflict, keep, cx)
        val cancelled = change.events.firstOrNull()?.after ?: return@write null
        change.events.forEach { forgetTimerState(it.after.id) }
        commit(change, ActionInfo(Verb.CANCEL, cancelled.kind, cancelled.id, cx.now), source, cx)
    }

    suspend fun recordDiaper(type: Diaper, source: Source): Done? = write { cx ->
        val change = Records.diaper(childId(), type, cx.now, cx)
        commit(change, ActionInfo(Verb.RECORD, Kind.DIAPER, change.newEventId(), cx.now, subtype = type.code), source, cx)
    }

    suspend fun recordBottle(content: BottleContent, amountMl: Double, source: Source): Done? = write { cx ->
        val change = Records.bottle(childId(), content, amountMl, cx.now, cx)
        putSetting(Keys.LAST_BOTTLE_ML, amountMl.toString())
        putSetting(Keys.LAST_BOTTLE_CONTENT, content.code)
        val info = ActionInfo(Verb.RECORD, Kind.BOTTLE, change.newEventId(), cx.now, subtype = content.code, amountMl = amountMl)
        commit(change, info, source, cx)
    }

    /** Records a dose of a med, for the child the med belongs to. */
    suspend fun recordSupplement(presetId: String, source: Source): Done? = write { cx ->
        val preset = db.presets().preset(presetId)?.takeIf { it.deletedAt == null }?.toModel() ?: return@write null
        val change = Records.supplement(preset.childId, preset, cx.now, cx)
        commit(change, ActionInfo(Verb.RECORD, Kind.SUPPLEMENT, change.newEventId(), cx.now, name = preset.name), source, cx)
    }

    /**
     * Marks a med dose as skipped (or back to given): the strip's [Skip] and Edit's Given / Skipped
     * (SPEC §5.10). Undoable like any edit.
     */
    suspend fun setSkipped(eventId: String, skipped: Boolean, source: Source): Done? = write { cx ->
        val e = db.events().event(eventId)?.takeIf { it.deletedAt == null && it.kind == Kind.SUPPLEMENT.code }?.toModel() ?: return@write null
        val status = if (skipped) EventStatus.SKIPPED else null
        if (e.status == status) return@write null
        val after = e.copy(status = status, updatedAt = cx.now, hlc = cx.nextHlc())
        val change = Change(events = listOf(RowChange(e, after)))
        commit(change, ActionInfo(if (skipped) Verb.SKIP else Verb.EDIT, Kind.SUPPLEMENT, e.id, e.startAt, name = e.supplementName), source, cx)
    }

    suspend fun recordPump(amountMl: Double, source: Source): Done? = write { cx ->
        val change = Records.pump(childId(), amountMl, cx.now, cx)
        putSetting(Keys.LAST_PUMP_ML, amountMl.toString())
        commit(change, ActionInfo(Verb.RECORD, Kind.PUMP, change.newEventId(), cx.now, amountMl = amountMl), source, cx)
    }

    suspend fun recordNote(text: String, source: Source): Done? = write { cx ->
        val trimmed = text.trim().takeIf { it.isNotEmpty() } ?: return@write null
        val change = Records.note(childId(), trimmed, cx.now, cx)
        commit(change, ActionInfo(Verb.RECORD, Kind.NOTE, change.newEventId(), cx.now), source, cx)
    }

    /**
     * The confirmation strip's [−5] [−10] [−15] [time…] after a start or a side switch: moves that
     * start (or switch) to [at]. The fix joins the same undo entry, so one Undo reverts both.
     * Refused if anything else has changed those rows since.
     */
    suspend fun adjust(undoId: String, at: Long): Done? = write { cx ->
        val row = db.undo().get(undoId)?.takeIf { it.undoneAt == null } ?: return@write null
        val payload = UndoCodec.decode(row.payload)
        if (!unchangedSince(payload.change)) return@write null
        val t = timedById(payload.info.eventId) ?: return@write null
        val change = when (payload.info.verb) {
            Verb.START -> Timers.moveStart(t, at, cx)
            Verb.SWITCH -> Timers.moveLastSwitch(t, at, cx)
            Verb.PAUSE -> Timers.moveLastPause(t, at, cx)
            Verb.RESUME -> Timers.moveLastResume(t, at, cx)
            else -> return@write null
        }
        if (change.isEmpty) return@write Done(undoId, payload.info)
        apply(change)
        val newAt = when (payload.info.verb) {
            Verb.START -> change.events.firstOrNull()?.after?.startAt
            Verb.PAUSE -> change.segments.lastOrNull()?.after?.endAt
            else -> change.segments.lastOrNull()?.after?.startAt
        } ?: payload.info.at
        val info = payload.info.copy(at = newAt)
        db.undo().setPayload(undoId, UndoCodec.encode(UndoPayload(info, payload.change.then(change))))
        Done(undoId, info)
    }

    /** Reverts an action (SPEC §5.7). False if it was already undone or something changed since. */
    suspend fun undo(undoId: String): Boolean = write { cx ->
        val row = db.undo().get(undoId)?.takeIf { it.undoneAt == null } ?: return@write false
        val payload = UndoCodec.decode(row.payload)
        val events = payload.change.events.associate { it.after.id to db.events().event(it.after.id)?.toModel() }
        val segments = payload.change.segments.associate { it.after.id to db.events().segment(it.after.id)?.toModel() }
        val children = payload.change.children.associate { it.after.id to db.children().child(it.after.id)?.toModel() }
        val presets = payload.change.presets.associate { it.after.id to db.presets().preset(it.after.id)?.toModel() }
        val change = Undo.revert(payload.change, { events[it] }, { segments[it] }, cx, { children[it] }, { presets[it] })
            ?: return@write false
        apply(change)
        db.undo().markUndone(undoId, cx.now)
        true
    }

    // ---- Editing (SPEC §6.5) ----

    /** An event and its live segments, deleted ones excluded, for the edit screen. */
    suspend fun loadForEdit(eventId: String): Editing? {
        ensureInitialized()
        val e = db.events().event(eventId)?.takeIf { it.deletedAt == null } ?: return null
        val segments = if (e.kind == Kind.BREAST.code) db.events().liveSegments(listOf(e.id)).map { it.toModel() } else emptyList()
        return Editing(e.toModel(), segments)
    }

    /** Records of the same child and kind whose times overlap [d] (highlighted, never refused). */
    suspend fun overlapsFor(d: EventDraft): List<Event> {
        val now = wallClock()
        val to = (d.end ?: if (d.running) now else d.start) + 1
        val nearby = db.events().nearby(d.childId, d.kind.code, d.start, to).map { it.toModel() }
        return EventEdit.overlaps(d, nearby, now)
    }

    /**
     * Saves an edit (or, with [original] null, a record added after the fact) as one undoable
     * action. Refused if the record changed since [original] was loaded.
     */
    suspend fun saveEdit(original: Editing?, d: EventDraft): SaveResult = write { cx ->
        if (original != null) {
            val cur = db.events().event(original.event.id)?.toModel()
            val curSegs = if (original.event.kind == Kind.BREAST) db.events().liveSegments(listOf(original.event.id)).map { it.hlc } else emptyList()
            if (cur == null || cur.hlc != original.event.hlc || cur.deletedAt != null || curSegs.toSet() != original.segments.map { it.hlc }.toSet()) {
                return@write SaveResult.Stale
            }
        }
        val problems = EventEdit.problems(d, cx.now)
        if (problems.isNotEmpty()) return@write SaveResult.Invalid(problems)
        if (d.running && d.childId != original?.event?.childId) {
            if (runningOf(d.childId, d.kind) != null) return@write SaveResult.Refused
        }
        val change = EventEdit.toChange(original?.event, original?.segments.orEmpty(), d, cx)
        if (change.isEmpty) return@write SaveResult.Unchanged
        val eventId = change.events.firstOrNull()?.after?.id ?: original!!.event.id
        val verb = if (original == null) Verb.ADD else Verb.EDIT
        val info = ActionInfo(verb, d.kind, eventId, d.start, subtype = d.subtype, amountMl = d.amountMl, name = d.supplementName)
        commit(change, info, Source.APP, cx)?.let { SaveResult.Saved(it) } ?: SaveResult.Unchanged
    }

    /** Deletes a record (soft, undoable). Also how "keep one" of a possible duplicate drops the other. */
    suspend fun delete(eventId: String): Done? = write { cx ->
        val e = db.events().event(eventId)?.toModel()?.takeIf { it.deletedAt == null } ?: return@write null
        val segs = if (e.kind == Kind.BREAST) db.events().liveSegments(listOf(e.id)).map { it.toModel() } else emptyList()
        forgetTimerState(eventId)
        commit(EventEdit.delete(e, segs, cx), ActionInfo(Verb.DELETE, e.kind, e.id, e.startAt, subtype = e.subtype, name = e.supplementName), Source.APP, cx)
    }

    // ---- Children (SPEC §6.6) ----

    /** Live children, archived ones included, in order. */
    fun childrenFlow(): Flow<List<Child>> = changes().conflate().map {
        ensureInitialized()
        db.children().live().map { it.toModel() }
    }

    suspend fun addChild(fields: ChildFields): Done? = write { cx ->
        val sortKey = (db.children().live().maxOfOrNull { it.sortKey } ?: -1) + 1
        val change = Children.add(fields, sortKey, cx)
        commit(change, ActionInfo(Verb.CHILD_ADD, null, change.children.single().after.id, cx.now, name = fields.name), Source.APP, cx)
    }

    suspend fun editChild(id: String, fields: ChildFields): Done? = write { cx ->
        val c = liveChild(id) ?: return@write null
        commit(Children.edit(c, fields, cx), ActionInfo(Verb.CHILD_EDIT, null, id, cx.now, name = fields.name), Source.APP, cx)
    }

    /** Archives or brings back a child. The last active child can't be archived. */
    suspend fun setChildArchived(id: String, archived: Boolean): Done? = write { cx ->
        val c = liveChild(id) ?: return@write null
        if (archived && db.children().live().count { it.archivedAt == null && it.id != id } == 0) return@write null
        val verb = if (archived) Verb.CHILD_ARCHIVE else Verb.CHILD_UNARCHIVE
        commit(Children.setArchived(c, archived, cx), ActionInfo(verb, null, id, cx.now, name = c.name), Source.APP, cx)
    }

    /**
     * Deletes a child (soft, undoable); their records are hidden with them. The last child that
     * isn't archived can't be deleted, so there is always one to record for.
     */
    suspend fun deleteChild(id: String): Done? = write { cx ->
        val c = liveChild(id) ?: return@write null
        if (db.children().live().count { it.archivedAt == null && it.id != id } == 0) return@write null
        commit(Children.delete(c, cx), ActionInfo(Verb.CHILD_DELETE, null, id, cx.now, name = c.name), Source.APP, cx)
    }

    /**
     * The child this phone records for ("last used", SPEC §6.3), picked on Today or in the quick
     * sheet. Today then shows that child alone (no longer several).
     */
    suspend fun setActiveChild(id: String) {
        write {
            putSetting(Keys.ACTIVE_CHILD, id)
            db.settings().delete(Keys.TODAY_CHILD)
        }
    }

    /**
     * The children Today shows (Phase 3c), in child order. Exactly one also makes that child the one
     * this phone records for, as picking a child on Today did before.
     */
    suspend fun setTodayChildren(ids: List<String>) {
        write {
            val active = db.children().live().filter { it.archivedAt == null }.map { it.id }
            val chosen = active.filter { it in ids }
            when (val value = ChildChoice.encodeToday(chosen, active)) {
                null -> chosen.singleOrNull()?.let { id ->
                    putSetting(Keys.ACTIVE_CHILD, id)
                    db.settings().delete(Keys.TODAY_CHILD)
                }
                else -> putSetting(Keys.TODAY_CHILD, value)
            }
        }
    }

    private suspend fun liveChild(id: String): Child? = db.children().child(id)?.takeIf { it.deletedAt == null }?.toModel()

    // ---- Restore from a copy (DECISIONS 2026-11-13) ----

    /**
     * Replaces this phone's records with a copy's: every synced row in [copy] is written as it is,
     * and this phone's live rows that aren't in the copy are soft-deleted (no hard deletes of
     * synced rows). This phone keeps its device id, settings and day start; its clock moves past the
     * copy's newest stamp first, so its own later changes (these deletions included) sort after
     * everything in the copy. The undo history is cleared, since it describes the replaced state.
     * Not undoable. Returns how many records the phone shows afterwards.
     */
    suspend fun restoreFrom(copy: DataCopy.Rows): Int = write { cx ->
        copy.newestHlc()?.let { runCatching { HlcStamp.parse(it) }.getOrNull() }?.let(cx::observe)
        val sql = db.openHelper.writableDatabase
        for (table in DataCopy.SYNCED_TABLES) {
            val rows = copy.tables[table].orEmpty()
            val incoming = rows.mapTo(HashSet()) { it.getAsString("id") }
            val local = sql.query("SELECT id FROM `$table` WHERE deleted_at IS NULL")
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            for (id in local) {
                if (id in incoming) continue
                sql.execSQL(
                    "UPDATE `$table` SET deleted_at = ?, updated_at = ?, hlc = ? WHERE id = ?",
                    arrayOf<Any>(cx.now, cx.now, cx.nextHlc(), id),
                )
            }
            for (row in rows) {
                val id = row.getAsString("id")
                if (sql.update(table, SQLiteDatabase.CONFLICT_ABORT, row, "id = ?", arrayOf(id)) == 0) {
                    sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, row)
                }
            }
        }
        db.undo().clear()
        syncedWrites++
        DataCopy.readRows(sql).summary().records
    }

    // ---- JSON backup and CSV export (SPEC §8, Phase 4) ----

    /** Every synced row, deleted ones included, read in one transaction so they agree. */
    suspend fun backupRows(): Merge.Rows {
        ensureInitialized()
        return db.withTransaction {
            Merge.Rows(
                children = db.children().all().map { it.toModel() },
                events = db.events().all().map { it.toModel() },
                segments = db.events().allSegments().map { it.toModel() },
                presets = db.presets().all().map { it.toModel() },
            )
        }
    }

    /** The CSV export (one row per record). [babyName] names a child without a name. */
    suspend fun csv(babyName: String): String {
        val rows = backupRows()
        return CsvExport.write(rows.children, rows.events, rows.segments) { it.name ?: babyName }
    }

    /**
     * What importing [backup] would do, counted in records (events) as the app shows them: a record
     * whose segments changed counts as updated. [replacesBlank] says that this phone's untouched
     * child gives way to the backup's children (DECISIONS 2026-11-14).
     */
    data class ImportOutcome(val added: Int, val updated: Int, val unchanged: Int, val otherChanges: Int, val replacesBlank: Boolean) {
        val changesAnything: Boolean get() = added > 0 || updated > 0 || otherChanges > 0 || replacesBlank
    }

    suspend fun previewBackup(backup: Merge.Rows): ImportOutcome {
        ensureInitialized()
        return db.withTransaction { planBackup(backup).third }
    }

    /**
     * Merges [backup] into this phone (SPEC §5.9): per row, the newer change wins, so importing the
     * same file again changes nothing. Not undoable (DECISIONS 2026-11-14); the caller asks first.
     */
    suspend fun importBackup(backup: Merge.Rows): ImportOutcome = write { cx ->
        // Later local changes (the blank child's replacement among them) sort after the backup's.
        backup.newestHlc()?.let { runCatching { HlcStamp.parse(it) }.getOrNull() }?.let(cx::observe)
        val (plan, blanks, outcome) = planBackup(backup)
        apply(plan.change)
        for ((child, presets) in blanks) apply(Merge.replaceBlank(child, presets, cx))
        outcome
    }

    private suspend fun planBackup(backup: Merge.Rows): Triple<Merge.Plan, List<Pair<Child, List<SupplementPreset>>>, ImportOutcome> {
        val ids = backup.ids()
        val local = Merge.Rows(
            children = ids.children.chunked(500).flatMap { db.children().byIds(it) }.map { it.toModel() },
            events = ids.events.chunked(500).flatMap { db.events().eventsByIds(it) }.map { it.toModel() },
            segments = ids.segments.chunked(500).flatMap { db.events().segmentsByIds(it) }.map { it.toModel() },
            presets = ids.presets.chunked(500).flatMap { db.presets().byIds(it) }.map { it.toModel() },
        )
        val plan = Merge.plan(local, backup)

        // This phone's untouched child gives way, but only to a backup that brings a live child.
        val incomingChildren = ids.children.toSet()
        val blanks = if (backup.children.none { it.deletedAt == null }) {
            emptyList()
        } else {
            db.children().live().map { it.toModel() }
                .filter { it.id !in incomingChildren }
                .map { it to db.presets().ofChild(it.id).map { p -> p.toModel() } }
                .filter { (child, presets) -> Merge.isBlank(child, db.events().countOf(child.id), presets) }
        }

        val c = plan.change
        val added = c.events.count { it.before == null }
        val addedIds = c.events.filter { it.before == null }.mapTo(HashSet()) { it.after.id }
        val updatedIds = c.events.filter { it.before != null }.mapTo(HashSet()) { it.after.id }
        c.segments.map { it.after.eventId }.filterTo(updatedIds) { it !in addedIds }
        val incomingEvents = backup.events.mapTo(HashSet()) { it.id }
        val unchanged = incomingEvents.size - added - updatedIds.count { it in incomingEvents }
        val outcome = ImportOutcome(
            added = added,
            updated = updatedIds.size,
            unchanged = unchanged.coerceAtLeast(0),
            otherChanges = c.children.size + c.presets.size,
            replacesBlank = blanks.isNotEmpty(),
        )
        return Triple(plan, blanks, outcome)
    }

    // ---- Bluetooth sync (SPEC §9, Phase 5) ----

    data class Household(val id: String, val key: ByteArray)

    /** This phone's household, or null when it has none (or its key can't be opened here). */
    suspend fun household(): Household? {
        val id = db.settings().get(Keys.HOUSEHOLD_ID) ?: return null
        val wrapped = db.settings().get(Keys.HOUSEHOLD_KEY) ?: return null
        val key = vault?.unwrap(wrapped) ?: return null
        return Household(id, key)
    }

    /** The household, made now if this phone has none (the first "Add a phone"). */
    suspend fun ensureHousehold(): Household {
        household()?.let { return it }
        val v = vault ?: error("no key vault")
        val h = Household(Ids.uuidV7(wallClock()), io.github.uirlisi.mostor.sync.SyncCrypto.randomBytes(32))
        setSettingsQuietly(mapOf(Keys.HOUSEHOLD_ID to h.id, Keys.HOUSEHOLD_KEY to v.wrap(h.key)))
        return h
    }

    /**
     * After joining: the household, the phone that added this one, and that phone's children and meds
     * to match against (sessions wait until [matchChildren]). A phone that was in another household
     * leaves it: its old peers are forgotten.
     */
    suspend fun joinHousehold(id: String, key: ByteArray, peerId: String, peerName: String, theirs: Merge.Rows) {
        val v = vault ?: error("no key vault")
        val pending = BackupJson.encode(theirs, wallClock(), "pairing")
        write(notify = false) {
            if (db.settings().get(Keys.HOUSEHOLD_ID) != id) db.peers().deleteAll()
            putSetting(Keys.HOUSEHOLD_ID, id)
            putSetting(Keys.HOUSEHOLD_KEY, v.wrap(key))
            putSetting(Keys.SYNC_PENDING_MATCH, pending)
            db.peers().upsert(PeerEntity(peerId, peerName, wallClock(), null, null))
        }
    }

    suspend fun addPeer(id: String, name: String) = write(notify = false) {
        db.peers().upsert(PeerEntity(id, name, wallClock(), db.peers().get(id)?.lastSyncAt, null))
    }

    suspend fun forgetPeer(id: String) = write(notify = false) { db.peers().delete(id) }

    suspend fun peers(): List<PeerEntity> = db.peers().all()

    fun peersFlow(): Flow<List<PeerEntity>> =
        db.invalidationTracker.createFlow("peer", "setting").conflate().map { db.peers().all() }

    /** Records how a session with [id] went: [ok], or a short reason. */
    suspend fun peerResult(id: String, ok: Boolean, reason: String? = null) = write(notify = false) {
        val at = if (ok) wallClock() else db.peers().get(id)?.lastSyncAt
        db.peers().setResult(id, at, if (ok) "ok" else reason ?: "failed")
    }

    /** The other phone's children and meds waiting to be matched, or null. */
    suspend fun pendingMatch(): Merge.Rows? =
        db.settings().get(Keys.SYNC_PENDING_MATCH)?.let { (BackupJson.decode(it) as? BackupJson.Read.Ok)?.backup?.rows }

    /** This phone's device id and its clock's origin. */
    suspend fun deviceId(): String = ensureInitialized().origin

    /**
     * Child matching (SPEC §9.4 step 4), as one local change, then sessions may run. [same] maps
     * this phone's child ids to the other phone's (absent: a different child, kept as it is). An
     * untouched child (a fresh install's "Baby") gives way without a question, as on import.
     */
    suspend fun matchChildren(same: Map<String, String>) = write(local = false) { cx ->
        val theirs = pendingMatch() ?: return@write
        // Their children and meds first (the rows the first sync would bring anyway), so this phone
        // always has a child to record for, also when its own blank one gives way.
        mergeIn(theirs, cx)
        val theirChildren = theirs.children.filter { it.deletedAt == null }.associateBy { it.id }
        var change = io.github.uirlisi.mostor.core.Change()
        for (mine in db.children().live().map { it.toModel() }) {
            if (mine.id in theirChildren) continue
            val presets = db.presets().ofChild(mine.id).map { it.toModel() }
            val target = same[mine.id]?.let { theirChildren[it] }
            change = when {
                target != null -> change.then(
                    ChildMatch.same(
                        mine, target,
                        db.events().all().filter { it.childId == mine.id }.map { it.toModel() },
                        presets, theirs.presets.filter { it.childId == target.id }, cx,
                    ),
                )
                theirChildren.isNotEmpty() && Merge.isBlank(mine, db.events().countOf(mine.id), presets) ->
                    change.then(Merge.replaceBlank(mine, presets, cx))
                else -> change
            }
        }
        apply(change)
        db.settings().delete(Keys.SYNC_PENDING_MATCH)
    }

    // SyncStore: the sync session's view of the data.

    override suspend fun vector(): Map<String, String> = Sync.vector(backupRows())

    override suspend fun vectorFor(peerId: String): Map<String, String> =
        if (db.peers().get(peerId)?.lastSyncAt == null) emptyMap() else vector()

    override suspend fun rowsSince(vector: Map<String, String>): Merge.Rows = Sync.since(backupRows(), vector)

    override suspend fun applyRows(rows: Merge.Rows): Int = write(local = false) { cx ->
        val change = mergeIn(rows, cx)
        change.events.size + change.segments.size + change.children.size + change.presets.size
    }

    /** Merges rows from another phone (SPEC §5.9) inside the caller's transaction. */
    private suspend fun mergeIn(rows: Merge.Rows, cx: Cx): io.github.uirlisi.mostor.core.Change {
        rows.newestHlc()?.let { runCatching { HlcStamp.parse(it) }.getOrNull() }?.let(cx::observe)
        val ids = rows.ids()
        val local = Merge.Rows(
            children = ids.children.chunked(500).flatMap { db.children().byIds(it) }.map { it.toModel() },
            events = ids.events.chunked(500).flatMap { db.events().eventsByIds(it) }.map { it.toModel() },
            segments = ids.segments.chunked(500).flatMap { db.events().segmentsByIds(it) }.map { it.toModel() },
            presets = ids.presets.chunked(500).flatMap { db.presets().byIds(it) }.map { it.toModel() },
        )
        val change = Merge.plan(local, rows).change
        apply(change)
        return change
    }

    override suspend fun knows(deviceId: String): Boolean =
        db.settings().get(Keys.SYNC_PENDING_MATCH) == null && db.peers().get(deviceId) != null

    // ---- Supplement presets (SPEC §6.6) ----

    fun presetsFlow(): Flow<List<SupplementPreset>> = changes().conflate().map {
        ensureInitialized()
        db.presets().live().map { it.toModel() }
    }

    suspend fun addPreset(childId: String, name: String, amount: Double?, unit: String?, plan: MedPlan): Done? = write { cx ->
        if (name.isBlank()) return@write null
        val sortKey = (db.presets().live().filter { it.childId == childId }.maxOfOrNull { it.sortKey } ?: -1) + 1
        val change = Presets.add(childId, name, amount, unit, plan, sortKey, today(cx.now), cx)
        commit(change, ActionInfo(Verb.PRESET_ADD, null, change.presets.single().after.id, cx.now, name = name.trim()), Source.APP, cx)
    }

    suspend fun editPreset(id: String, name: String, amount: Double?, unit: String?, plan: MedPlan): Done? = write { cx ->
        if (name.isBlank()) return@write null
        val p = db.presets().preset(id)?.takeIf { it.deletedAt == null }?.toModel() ?: return@write null
        commit(Presets.edit(p, name, amount, unit, plan, today(cx.now), cx), ActionInfo(Verb.PRESET_EDIT, null, id, cx.now, name = name.trim()), Source.APP, cx)
    }

    suspend fun deletePreset(id: String): Done? = write { cx ->
        val p = db.presets().preset(id)?.takeIf { it.deletedAt == null }?.toModel() ?: return@write null
        commit(Presets.delete(p, cx), ActionInfo(Verb.PRESET_DELETE, null, id, cx.now, name = p.name), Source.APP, cx)
    }

    /** Moves a med one place up ([by] = −1) or down (+1) among its child's meds. */
    suspend fun movePreset(id: String, by: Int): Done? = write { cx ->
        val childId = db.presets().preset(id)?.childId ?: return@write null
        val list = db.presets().live().map { it.toModel() }.filter { it.childId == childId }
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@write null
        val p = list[from]
        commit(Presets.move(list, from, from + by, cx), ActionInfo(Verb.PRESET_MOVE, null, id, cx.now, name = p.name), Source.APP, cx)
    }

    // ---- Per-phone settings (SPEC §6.6) ----

    fun settingsFlow(): Flow<SettingsSnapshot> = changes().conflate().map { settings() }

    /** Sets a per-phone setting, or removes it ([value] null) so its default applies. */
    suspend fun setSetting(key: String, value: String?) {
        write {
            if (value == null) db.settings().delete(key) else putSetting(key, value)
        }
    }

    /**
     * Writes settings without refreshing the notification (nor, through it, starting the daily
     * backup): for the backup's own bookkeeping. Screens still see the change.
     */
    suspend fun setSettingsQuietly(values: Map<String, String?>) {
        write(notify = false) {
            for ((key, value) in values) if (value == null) db.settings().delete(key) else putSetting(key, value)
        }
    }

    // ---- Import of a Baby Tracker report ----

    /** What [importBabyTracker] would add, without writing anything. */
    suspend fun previewImport(report: BabyTracker.Report, bringBack: Boolean = false): BabyTracker.Plan {
        ensureInitialized()
        return planImport(report, bringBack, DryRun(wallClock(), zone().id))
    }

    /**
     * Adds the report's records to the active child, as one action that Undo can take back.
     * [bringBack] also restores records imported before and deleted since.
     */
    suspend fun importBabyTracker(report: BabyTracker.Report, bringBack: Boolean = false): Done? = write { cx ->
        val plan = planImport(report, bringBack, cx)
        val first = plan.change.events.firstOrNull()?.after ?: return@write null
        commit(plan.change, ActionInfo(Verb.IMPORT, first.kind, first.id, cx.now, count = plan.newEvents), Source.APP, cx)
    }

    private suspend fun planImport(report: BabyTracker.Report, bringBack: Boolean, cx: ChangeContext): BabyTracker.Plan {
        val zone = zone()
        val child = childId()
        val first = report.firstDay ?: return BabyTracker.Plan(Change(), 0, 0, 0, 0)
        val last = report.lastDay ?: first
        val nearby = db.events().inRange(
            first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
        ).filter { it.childId == child }.map { it.toModel() }
        val (eventIds, segmentIds) = BabyTracker.ids(report, zone)
        val stored = eventIds.chunked(500).flatMap { db.events().eventsByIds(it) }.map { it.toModel() }.associateBy { it.id }
        val storedSegments = segmentIds.chunked(500).flatMap { db.events().segmentsByIds(it) }.map { it.toModel() }.associateBy { it.id }
        return BabyTracker.plan(report, child, zone, stored, storedSegments, nearby, cx, bringBack)
    }

    /** For planning without writing: no ids or HLC stamps are handed out. */
    private class DryRun(override val now: Long, override val tz: String) : ChangeContext {
        override fun newId(): String = ""
        override fun nextHlc(): String = ""
    }

    // ---- Overrun (SPEC §5.5) ----

    /** [Still going]: nudge again after the same interval. Local to this phone. */
    suspend fun stillGoing(eventId: String) {
        write { cx ->
            val e = db.events().event(eventId)?.takeIf { it.status == EventStatus.RUNNING.code && it.deletedAt == null }
                ?: return@write
            val until = Overrun.snoozeUntil(cx.now, settings().limitsFor(Kind.of(e.kind))) ?: return@write
            putSetting(Keys.SNOOZE_PREFIX + eventId, until.toString())
        }
    }

    /** The "has no end" notification was swiped away: leave the end unknown and stop asking. */
    /**
     * The status notification was swiped away with "Keep the notification" off (true), or the app
     * was opened or the phone restarted (false). Quiet: the notifier decides what to post.
     */
    suspend fun setStatusDismissed(dismissed: Boolean) {
        setSettingsQuietly(mapOf(Keys.STATUS_DISMISSED to if (dismissed) "true" else null))
    }

    suspend fun dismissNoEnd(eventId: String) {
        write { putSetting(Keys.NO_END_DISMISSED_PREFIX + eventId, "1") }
    }

    /**
     * The two-timers pop-up notification was swiped away: don't alert again for
     * it. The question stays in the status notification and the quick sheet.
     */
    suspend fun dismissConflictAlert(key: String) {
        write { putSetting(Keys.CONFLICT_ALERT_DISMISSED_PREFIX + key, "1") }
    }

    /**
     * Marks the end unknown for every running timer past its give-up time. Called before the
     * notification is drawn; it doesn't trigger another refresh.
     */
    suspend fun applyOverruns() {
        ensureInitialized()
        val now = wallClock()
        val s = settings()
        val due = db.events().running().map { timed(it) }.filter {
            Overrun.state(it, now, s.limitsFor(it.event.kind), s.snoozes[it.event.id]) in AUTOMATIC
        }
        if (due.isEmpty()) return
        write(notify = false) { cx ->
            for (d in due) {
                val t = timedById(d.event.id) ?: continue
                val pausedAt = t.pausedAt
                if (pausedAt != null) {
                    // A forgotten pause: the feed ends at its pause, shown with Undo like a button.
                    commit(Timers.stop(t, pausedAt, cx), ActionInfo(Verb.STOP, Kind.BREAST, t.event.id, pausedAt), Source.NOTIFICATION, cx)
                } else {
                    apply(Timers.giveUp(t, cx))
                }
                forgetTimerState(t.event.id)
            }
        }
    }

    // ---- Reads ----

    /** Emits whenever a table the screens show changes. */
    private fun changes(): Flow<Set<String>> =
        db.invalidationTracker.createFlow("event", "breast_segment", "supplement_preset", "setting", "undo", "child", "peer")

    fun homeStates(): Flow<HomeState> = changes().conflate().map { homeState() }


    suspend fun homeState(): HomeState {
        ensureInitialized()
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

    fun dayViews(dayOffset: Int, showHidden: Boolean): Flow<DayView> = changes().conflate().map { dayView(dayOffset, showHidden) }

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

    fun logViews(dayCount: Int, filter: LogFilter): Flow<LogView> = changes().conflate().map { logView(dayCount, filter) }

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

    /** The app's date for [now] (its day start, SPEC §5.1), as yyyy-MM-dd: when a med schedule starts. */
    private suspend fun today(now: Long): String = DayClock(settings().dayStartHour, zone()).dayOf(now).toString()

    suspend fun settings(): SettingsSnapshot =
        SettingsSnapshot.from(db.settings().all().associate { it.key to it.value }, locale())

    /** For the first-launch notification permission request (asked once, then a banner). */
    suspend fun markNotificationPermissionAsked(): Boolean {
        val asked = db.settings().get(Keys.NOTIFICATION_PERMISSION_ASKED) != null
        if (!asked) write(notify = false) { putSetting(Keys.NOTIFICATION_PERMISSION_ASKED, "1") }
        return !asked
    }

    // ---- Internals ----

    /**
     * [local]: a change made on this phone, which other phones should get soon (SPEC §9.6). Rows
     * received from another phone are not local, so a sync never sets off another one back.
     */
    private suspend fun <T> write(notify: Boolean = true, local: Boolean = true, block: suspend (Cx) -> T): T {
        val c = ensureInitialized()
        var changedSynced = false
        val result = writeLock.withLock {
            val before = syncedWrites
            db.withTransaction {
                val cx = Cx(wallClock(), zone().id, c)
                val r = block(cx)
                c.last?.let { db.meta().setHlc(it.encode()) }
                r
            }.also { changedSynced = syncedWrites != before }
        }
        if (notify) sink.refresh()
        if (local && changedSynced) onLocalChange?.invoke()
        return result
    }

    /** Called after this phone changed synced rows itself (Phase 5: sync on change). */
    @Volatile
    var onLocalChange: (() -> Unit)? = null

    /** Counts writes of synced rows, under the write lock. */
    private var syncedWrites = 0L

    private suspend fun apply(change: Change) {
        if (!change.isEmpty) syncedWrites++
        if (change.events.isNotEmpty()) db.events().upsertEvents(change.events.map { it.after.toEntity() })
        if (change.segments.isNotEmpty()) db.events().upsertSegments(change.segments.map { it.after.toEntity() })
        if (change.children.isNotEmpty()) db.children().upsert(change.children.map { it.after.toEntity() })
        if (change.presets.isNotEmpty()) db.presets().upsert(change.presets.map { it.after.toEntity() })
    }

    private suspend fun commit(change: Change, info: ActionInfo, source: Source, cx: Cx): Done? {
        if (change.isEmpty) return null
        apply(change)
        val id = Ids.uuidV7(cx.now)
        db.undo().insert(UndoEntity(id, cx.now, source.code, UndoCodec.encode(UndoPayload(info, change)), null))
        db.undo().pruneBefore(cx.now - UNDO_KEEP_MS)
        return Done(id, info)
    }

    private suspend fun unchangedSince(change: Change): Boolean =
        change.events.all { db.events().event(it.after.id)?.hlc == it.after.hlc } &&
            change.segments.all { db.events().segment(it.after.id)?.hlc == it.after.hlc } &&
            change.children.all { db.children().child(it.after.id)?.hlc == it.after.hlc } &&
            change.presets.all { db.presets().preset(it.after.id)?.hlc == it.after.hlc }

    private suspend fun putSetting(key: String, value: String) = db.settings().put(SettingEntity(key, value))

    private suspend fun forgetTimerState(eventId: String) {
        db.settings().delete(Keys.SNOOZE_PREFIX + eventId)
        db.settings().delete(Keys.NO_END_DISMISSED_PREFIX + eventId)
    }

    private suspend fun childId(): String {
        db.settings().get(Keys.ACTIVE_CHILD)?.let { id ->
            db.children().child(id)?.takeIf { it.deletedAt == null && it.archivedAt == null }?.let { return it.id }
        }
        return db.children().firstActive()?.id ?: error("no active child")
    }

    private suspend fun timed(e: EventEntity): TimedEvent {
        val segments = if (e.kind == Kind.BREAST.code) db.events().liveSegments(listOf(e.id)).map { it.toModel() } else emptyList()
        return TimedEvent.of(e.toModel(), segments)
    }

    private suspend fun timedById(id: String): TimedEvent? =
        db.events().event(id)?.takeIf { it.deletedAt == null && Kind.of(it.kind).timed }?.let { timed(it) }

    private suspend fun runningOf(childId: String, kind: Kind): TimedEvent? =
        db.events().running().firstOrNull { it.childId == childId && it.kind == kind.code }?.let { timed(it) }

    private fun Change.newEventId(): String = events.first { it.before == null }.after.id

    companion object {
        private val FEED_KINDS = listOf(Kind.BREAST.code, Kind.BOTTLE.code)

        /** Overrun states the repository acts on by itself. */
        private val AUTOMATIC = setOf(OverrunState.GIVE_UP, OverrunState.END_AT_PAUSE)

        /** How long the notification shows "Recorded … [Undo]" after a notification action. */
        const val RECENT_MS = 60_000L
        private const val UNDO_KEEP_MS = 24 * 3_600_000L
        private const val NO_END_WINDOW_MS = 24 * 3_600_000L
    }
}
