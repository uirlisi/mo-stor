package io.github.uirlisi.mostor.data

import android.content.ContentValues
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
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EditProblem
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventDraft
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.HybridClock
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedPlan
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.sync.KeyVault
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
import java.time.ZoneId
import java.util.Locale
import io.github.uirlisi.mostor.core.ChartFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

/** Called after every committed write (CLAUDE.md: every write refreshes the status notification). */
fun interface StatusSink {
    suspend fun refresh()
}

/** Where an action was made. Notification actions get the 10 s "Recorded · Undo" line (SPEC §5.7). */
enum class Source(val code: String) {
    SHEET("sheet"),
    NOTIFICATION("notification"),
    APP("app"),
}

/** A committed user action: what to show in the confirmation strip, and how to undo it. */
data class Done(val undoId: String, val info: ActionInfo)

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
 *
 * Recording, editing, children, meds, settings, restore, backup and import live here. The household
 * and sync are [sync]; the reads are [Views], reached through the read methods below. All three
 * share one [DataCore] (the write lock and transaction, the clock, settings).
 */
class Repository(
    private val db: MoStorDb,
    sink: StatusSink,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    locale: () -> Locale = Locale::getDefault,
    /**
     * This install's id from a file Android never backs up (null: no check, as in tests that don't
     * need it). Compared with the copy in the database to notice a database moved from another phone.
     */
    installId: (() -> String)? = null,
    /** Wraps the household key at rest (Phase 5); null in tests that don't sync. */
    vault: KeyVault? = null,
) {
    private val core = DataCore(db, sink, wallClock, zone, locale, installId)
    private val views = Views(core)

    /** The household, its phones, and the sync session's view of the data (SPEC §9, Phase 5). */
    val sync = HouseholdSync(core, vault)

    /** Called after this phone changed synced rows itself (Phase 5: sync on change). */
    var onLocalChange: (() -> Unit)?
        get() = core.onLocalChange
        set(value) {
            core.onLocalChange = value
        }

    // ---- Setup ----

    /** Creates this phone's id and the implicit child with their seeded meds on first use. */
    suspend fun ensureInitialized(): HybridClock = core.ensureInitialized()

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

    /** "Set end…" on a timer that ended automatically at its pause: moves its end to [at]. */
    suspend fun moveEnd(eventId: String, at: Long, source: Source): Done? = write { cx ->
        val t = timedById(eventId) ?: return@write null
        val change = Timers.moveEnd(t, at, cx)
        val end = change.events.firstOrNull()?.after?.endAt ?: return@write null
        commit(change, ActionInfo(Verb.SET_END, t.event.kind, eventId, end), source, cx)
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
        // Locally at most one timer runs per child and kind (SPEC §4.4): undoing a stop or a delete
        // while another timer of that kind has started since would make two. (Timers running before
        // the action, like the two of a "Keep earlier" being undone, may run again together.)
        val running = db.events().running().map { it.toModel() }
        val revived = change.events.map { it.after }.filter { it.status == EventStatus.RUNNING && it.deletedAt == null }
        val startedSince = { r: Event -> running.any { it.id != r.id && it.childId == r.childId && it.kind == r.kind && it.createdAt > row.createdAt } }
        if (revived.any(startedSince)) return@write false
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

    /**
     * "Keep this one" on possible duplicates (SPEC §5.6): deletes the others as one action, with one
     * Undo that brings them all back.
     */
    suspend fun deleteAll(eventIds: Collection<String>): Done? = write { cx ->
        var change = io.github.uirlisi.mostor.core.Change()
        var first: Event? = null
        for (id in eventIds) {
            val e = db.events().event(id)?.toModel()?.takeIf { it.deletedAt == null } ?: continue
            val segs = if (e.kind == Kind.BREAST) db.events().liveSegments(listOf(e.id)).map { it.toModel() } else emptyList()
            forgetTimerState(id)
            change = change.then(EventEdit.delete(e, segs, cx))
            if (first == null) first = e
        }
        val e = first ?: return@write null
        commit(change, ActionInfo(Verb.DELETE, e.kind, e.id, e.startAt, subtype = e.subtype, name = e.supplementName), Source.APP, cx)
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
     * Replaces this phone's records with a copy's: this phone's live rows that aren't in the copy are
     * soft-deleted (no hard deletes of synced rows), and every row of [copy] that differs from the one
     * here is written as a new change from this phone (a fresh stamp, `updated_at` now). Rows that
     * are the same stay as they are. This phone keeps its device id, settings and day start; its
     * clock moves past the copy's newest stamp first.
     *
     * The fresh stamps matter once other phones share the household: a restored row keeping its old
     * stamp would lose to the newer version another phone holds, and since this phone's clock is past
     * that version, a sync would never send it here either; the phones would disagree for good
     * (review 2026-11-19). As new changes, the copy's records reach every phone. The undo history is
     * cleared, since it describes the replaced state. Not undoable. Returns how many records the
     * phone shows afterwards.
     */
    suspend fun restoreFrom(copy: DataCopy.Rows): Int = write { cx ->
        cx.observeNewest(copy.newestHlc())
        val sql = db.openHelper.writableDatabase
        for (table in DataCopy.SYNCED_TABLES) {
            val rows = copy.tables[table].orEmpty()
            val incoming = rows.mapTo(HashSet()) { it.getAsString("id") }
            val here = sql.query("SELECT * FROM `$table`").use { c ->
                with(DataCopy) { buildMap { while (c.moveToNext()) c.toValues().let { put(it.getAsString("id"), it) } } }
            }
            for ((id, row) in here) {
                if (id in incoming || row.getAsLong("deleted_at") != null) continue
                // A person's change: an event loses any automatic mark (Event.autoBase) it had.
                val clearMark = if (table == "event") ", auto_base = NULL" else ""
                sql.execSQL(
                    "UPDATE `$table` SET deleted_at = ?, updated_at = ?, hlc = ?$clearMark WHERE id = ?",
                    arrayOf<Any>(cx.now, cx.now, cx.nextHlc(), id),
                )
            }
            for (copied in rows) {
                val id = copied.getAsString("id")
                if (here[id] == copied) continue
                // Untouched seeded meds keep their shared stamp (it means "the same on every phone"),
                // but only where this phone has no other version: one edited or deleted since the copy
                // has a newer stamp on the other phones, which would never take the copy's back.
                val seed = copied.getAsString("hlc") == Presets.SEED_HLC && here[id] == null
                val row = if (seed) copied else ContentValues(copied).apply {
                    put("updated_at", cx.now)
                    put("hlc", cx.nextHlc())
                    if (table == "event") putNull("auto_base")
                }
                if (sql.update(table, SQLiteDatabase.CONFLICT_ABORT, row, "id = ?", arrayOf(id)) == 0) {
                    sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, row)
                }
            }
        }
        db.undo().clear()
        core.syncedWrites++
        DataCopy.readRows(sql).summary().records
    }

    // ---- JSON backup and CSV export (SPEC §8, Phase 4) ----

    /** Every synced row, deleted ones included, read in one transaction so they agree. */
    suspend fun backupRows(): Merge.Rows = core.backupRows()

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
        cx.observeNewest(backup.newestHlc())
        val (plan, blanks, outcome) = planBackup(backup)
        applyMerged(plan.change, cx)
        for ((child, presets) in blanks) apply(Merge.replaceBlank(child, presets, cx))
        outcome
    }

    private suspend fun planBackup(file: Merge.Rows): Triple<Merge.Plan, List<Pair<Child, List<SupplementPreset>>>, ImportOutcome> {
        // As in a sync: rows stamped far ahead of this phone's clock wait (Merge.Rows.notAfter).
        val backup = file.notAfter(wallClock() + HybridClock.MAX_AHEAD_MS)
        val ids = backup.ids()
        val plan = Merge.plan(core.localRows(ids), backup)

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

    suspend fun settings(): SettingsSnapshot = core.settings()

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
    suspend fun setSettingsQuietly(values: Map<String, String?>) = core.setSettingsQuietly(values)

    /** For the first-launch notification permission request (asked once, then a banner). */
    suspend fun markNotificationPermissionAsked(): Boolean {
        val asked = db.settings().get(Keys.NOTIFICATION_PERMISSION_ASKED) != null
        if (!asked) write(notify = false) { putSetting(Keys.NOTIFICATION_PERMISSION_ASKED, "1") }
        return !asked
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

    /**
     * The status notification was swiped away with "Keep the notification" off (true), or the app
     * was opened or the phone restarted (false). Quiet: the notifier decides what to post.
     */
    suspend fun setStatusDismissed(dismissed: Boolean) {
        setSettingsQuietly(mapOf(Keys.STATUS_DISMISSED to if (dismissed) "true" else null))
    }

    /** The "has no end" notification was swiped away: leave the end unknown and stop asking. */
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
            // Decided again inside the write: a Resume or "Still going" committed meanwhile wins.
            val current = settings()
            for (d in due) {
                val t = timedById(d.event.id) ?: continue
                if (Overrun.state(t, cx.now, current.limitsFor(t.event.kind), current.snoozes[t.event.id]) !in AUTOMATIC) continue
                // Both are said in a notification of their own, on every phone ([HomeState.noEnd], [HomeState.autoEnded]).
                apply(if (t.pausedAt != null) Timers.endForgottenPause(t, cx) else Timers.giveUp(t, cx))
                forgetTimerState(t.event.id)
            }
        }
    }

    // ---- Reads ([Views]) ----

    /** [homeState] whenever a table it shows changes, built off the main thread. */
    fun homeStates(): Flow<HomeState> = views.homeStates()

    /** The status notification's and the quick sheet's view, read in one transaction ([Views.homeState]). */
    suspend fun homeState(): HomeState = views.homeState()

    /** Today + [dayOffset] days: the summary and the timeline ([Views.dayView]). */
    suspend fun dayView(dayOffset: Int, showHidden: Boolean = false): DayView = views.dayView(dayOffset, showHidden)

    fun dayViews(dayOffset: Int, showHidden: Boolean): Flow<DayView> = views.dayViews(dayOffset, showHidden)

    /** The log: the last [dayCount] days, grouped by day, filtered ([Views.logView]). */
    suspend fun logView(dayCount: Int, filter: LogFilter): LogView = views.logView(dayCount, filter)

    fun logViews(dayCount: Int, filter: LogFilter): Flow<LogView> = views.logViews(dayCount, filter)

    /** What Patterns and Stats draw ([Views.chartView]). */
    suspend fun chartView(range: ChartRange, focus: ChartFocus, showHidden: Boolean, withPatterns: Boolean, withGaps: Boolean): ChartView =
        views.chartView(range, focus, showHidden, withPatterns, withGaps)

    /** [chartView] whenever the data or a setting changes, computed off the main thread. */
    fun chartViews(range: ChartRange, focus: ChartFocus, showHidden: Boolean, withPatterns: Boolean, withGaps: Boolean): Flow<ChartView> =
        views.chartViews(range, focus, showHidden, withPatterns, withGaps)

    // ---- Internals ----

    private suspend fun <T> write(notify: Boolean = true, local: Boolean = true, block: suspend (Cx) -> T): T = core.write(notify, local, block)
    private suspend fun apply(change: Change) = core.apply(change)
    private suspend fun applyMerged(change: Change, cx: Cx) = core.applyMerged(change, cx)
    private suspend fun commit(made: Change, info: ActionInfo, source: Source, cx: Cx): Done? = core.commit(made, info, source, cx)
    private suspend fun putSetting(key: String, value: String) = core.putSetting(key, value)
    private fun changes() = core.changes()
    private suspend fun childId() = core.childId()
    private suspend fun timed(e: EventEntity) = core.timed(e)

    /** The app's date for [now] (its day start, SPEC §5.1), as yyyy-MM-dd: when a med schedule starts. */
    private suspend fun today(now: Long): String = DayClock(settings().dayStartHour, zone()).dayOf(now).toString()

    private suspend fun unchangedSince(change: Change): Boolean =
        change.events.all { db.events().event(it.after.id)?.hlc == it.after.hlc } &&
            change.segments.all { db.events().segment(it.after.id)?.hlc == it.after.hlc } &&
            change.children.all { db.children().child(it.after.id)?.hlc == it.after.hlc } &&
            change.presets.all { db.presets().preset(it.after.id)?.hlc == it.after.hlc }

    private suspend fun forgetTimerState(eventId: String) {
        db.settings().delete(Keys.SNOOZE_PREFIX + eventId)
        db.settings().delete(Keys.NO_END_DISMISSED_PREFIX + eventId)
    }

    private suspend fun timedById(id: String): TimedEvent? =
        db.events().event(id)?.takeIf { it.deletedAt == null && Kind.of(it.kind).timed }?.let { timed(it) }

    private suspend fun runningOf(childId: String, kind: Kind): TimedEvent? =
        db.events().running().firstOrNull { it.childId == childId && it.kind == kind.code }?.let { timed(it) }

    private fun Change.newEventId(): String = events.first { it.before == null }.after.id

    companion object {
        /** Overrun states the repository acts on by itself. */
        private val AUTOMATIC = setOf(OverrunState.GIVE_UP, OverrunState.END_AT_PAUSE)

        /** How long the notification shows "Recorded … [Undo]" after a notification action. */
        const val RECENT_MS = 10_000L
    }
}
