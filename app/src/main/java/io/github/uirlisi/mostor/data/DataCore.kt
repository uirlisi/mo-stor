package io.github.uirlisi.mostor.data

import androidx.room.withTransaction
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.ChangeContext
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.HybridClock
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Presets
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.core.Timers
import io.github.uirlisi.mostor.core.asLocal
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A write's context: its time and time zone, and this phone's clock for new stamps. */
internal class Cx(override val now: Long, override val tz: String, private val hlc: HybridClock) : ChangeContext {
    override fun newId(): String = Ids.uuidV7(now)
    override fun nextHlc(): String = hlc.now().encode()

    /** Moves this phone's clock past a stamp from elsewhere, so later changes sort after it. */
    fun observe(stamp: HlcStamp) {
        hlc.receive(stamp)
    }

    /** [observe] for the newest stamp of rows from elsewhere, if any; one that doesn't parse is ignored. */
    fun observeNewest(stamp: String?) {
        stamp?.let { runCatching { HlcStamp.parse(it) }.getOrNull() }?.let(::observe)
    }
}

/**
 * What [Repository], [HouseholdSync] and [Views] share, owned by [Repository]: setup, the write
 * lock and transaction, this phone's clock, writing rows and undo entries, and settings. Writes are
 * serialized, each runs in one transaction, stamps fresh HLCs, and then refreshes the notification.
 */
internal class DataCore(
    val db: MoStorDb,
    private val sink: StatusSink,
    val wallClock: () -> Long,
    val zone: () -> ZoneId,
    private val locale: () -> Locale,
    private val installId: (() -> String)?,
) {
    private val writeLock = Mutex()
    private val initLock = Mutex()

    @Volatile
    private var clock: HybridClock? = null

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
                        for (key in listOf(Keys.HOUSEHOLD_ID, Keys.HOUSEHOLD_KEY, Keys.SYNC_PENDING_MATCH, Keys.SYNC_FAREWELL)) db.settings().delete(key)
                        db.peers().deleteAll()
                        db.members().deleteAll()
                    }
                    if (stored != mine) db.settings().put(SettingEntity(Keys.INSTALL_ID, mine))
                }
                val c = HybridClock(meta.deviceId, meta.hlc?.let(HlcStamp::parse), wallClock)
                if (db.children().countLive() == 0) seedFirstChild(now, c.now().encode())
                c.last?.let { db.meta().setHlc(it.encode()) }
                c
            }.also { clock = it }
        }
    }

    /** The implicit first child, with their own Vit D and Vit K (schema version 2). */
    private suspend fun seedFirstChild(now: Long, hlc: String) {
        val child = Child(id = Ids.uuidV7(now), createdAt = now, updatedAt = now, hlc = hlc)
        db.children().upsert(listOf(child.toEntity()))
        db.presets().upsert(Presets.seeds(child.id, now).map { it.toEntity() })
    }

    // ---- Writes ----

    /**
     * [local]: a change made on this phone, which other phones should get soon (SPEC §9.6). Rows
     * received from another phone are not local, so a sync never sets off another one back.
     */
    suspend fun <T> write(notify: Boolean = true, local: Boolean = true, block: suspend (Cx) -> T): T {
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
    var syncedWrites = 0L

    /** Writes a change made on this phone (a person's, or an automatic one: [asLocal]). */
    suspend fun apply(change: Change) = writeRows(change.asLocal())

    /** Writes rows merged in from elsewhere (sync, a backup) as they came. */
    /**
     * Writes merged rows (a sync, a backup), then tidies every ended feed they touched
     * ([Timers.tidyEnded]: a stop merged with a switch made elsewhere left a segment open).
     */
    suspend fun applyMerged(change: Change, cx: ChangeContext) {
        writeRows(change)
        val touched = (change.events.map { it.after.id } + change.segments.map { it.after.eventId }).distinct()
        for (id in touched) {
            val e = db.events().event(id)?.takeIf { it.kind == Kind.BREAST.code } ?: continue
            apply(Timers.tidyEnded(timed(e), cx))
        }
    }

    private suspend fun writeRows(change: Change) {
        if (!change.isEmpty) syncedWrites++
        if (change.events.isNotEmpty()) db.events().upsertEvents(change.events.map { it.after.toEntity() })
        if (change.segments.isNotEmpty()) db.events().upsertSegments(change.segments.map { it.after.toEntity() })
        if (change.children.isNotEmpty()) db.children().upsert(change.children.map { it.after.toEntity() })
        if (change.presets.isNotEmpty()) db.presets().upsert(change.presets.map { it.after.toEntity() })
    }

    suspend fun commit(made: Change, info: ActionInfo, source: Source, cx: Cx): Done? {
        if (made.isEmpty) return null
        val change = made.asLocal()
        apply(change)
        val id = Ids.uuidV7(cx.now)
        db.undo().insert(UndoEntity(id, cx.now, source.code, UndoCodec.encode(UndoPayload(info, change)), null))
        db.undo().pruneBefore(cx.now - UNDO_KEEP_MS)
        return Done(id, info)
    }

    // ---- Settings ----

    suspend fun settings(): SettingsSnapshot =
        SettingsSnapshot.from(db.settings().all().associate { it.key to it.value }, locale())

    suspend fun putSetting(key: String, value: String) = db.settings().put(SettingEntity(key, value))

    /**
     * Writes settings without refreshing the notification (nor, through it, starting the daily
     * backup): for the backup's own bookkeeping. Screens still see the change.
     */
    suspend fun setSettingsQuietly(values: Map<String, String?>) {
        write(notify = false) {
            for ((key, value) in values) if (value == null) db.settings().delete(key) else putSetting(key, value)
        }
    }

    // ---- Reads ----

    /** Emits whenever a table the screens show changes. */
    fun changes(): Flow<Set<String>> =
        db.invalidationTracker.createFlow("event", "breast_segment", "supplement_preset", "setting", "undo", "child", "peer")

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

    /** This phone's rows with [ids], deleted ones included, read 500 ids at a time. */
    suspend fun localRows(ids: Merge.Ids): Merge.Rows = Merge.Rows(
        children = ids.children.chunked(500).flatMap { db.children().byIds(it) }.map { it.toModel() },
        events = ids.events.chunked(500).flatMap { db.events().eventsByIds(it) }.map { it.toModel() },
        segments = ids.segments.chunked(500).flatMap { db.events().segmentsByIds(it) }.map { it.toModel() },
        presets = ids.presets.chunked(500).flatMap { db.presets().byIds(it) }.map { it.toModel() },
    )

    /** The child this phone records for: the active one, or the first that isn't archived. */
    suspend fun childId(): String {
        db.settings().get(Keys.ACTIVE_CHILD)?.let { id ->
            db.children().child(id)?.takeIf { it.deletedAt == null && it.archivedAt == null }?.let { return it.id }
        }
        // Settings never archives or deletes the last active child, but two phones apart can each
        // archive a different one, and a sync then leaves none: record for an archived child rather
        // than fail on every screen (Settings › Children can bring one back).
        return db.children().firstActive()?.id ?: db.children().live().firstOrNull()?.id ?: error("no child")
    }

    suspend fun timed(e: EventEntity): TimedEvent {
        val segments = if (e.kind == Kind.BREAST.code) db.events().liveSegments(listOf(e.id)).map { it.toModel() } else emptyList()
        return TimedEvent.of(e.toModel(), segments)
    }

    companion object {
        private const val UNDO_KEEP_MS = 24 * 3_600_000L
    }
}
