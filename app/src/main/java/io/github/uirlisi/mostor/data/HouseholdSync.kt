package io.github.uirlisi.mostor.data

import androidx.room.withTransaction
import io.github.uirlisi.mostor.core.ChildMatch
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.HybridClock
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.core.Joining
import io.github.uirlisi.mostor.core.Member
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Roster
import io.github.uirlisi.mostor.core.Standing
import io.github.uirlisi.mostor.core.Sync
import io.github.uirlisi.mostor.sync.KeyVault
import io.github.uirlisi.mostor.sync.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

/**
 * Bluetooth sync's side of the data (SPEC §9, Phase 5): this phone's household and its key,
 * joining, leaving and parting, the household's list of phones, and the sync session's view of the
 * data ([SyncStore]). Reached as [Repository.sync]; its writes go through the repository's shared
 * core, like every other write.
 */
class HouseholdSync internal constructor(
    private val core: DataCore,
    /** Wraps the household key at rest (Phase 5); null in tests that don't sync. */
    private val vault: KeyVault?,
) : SyncStore {
    private val db = core.db
    private val wallClock = core.wallClock

    private suspend fun ensureInitialized() = core.ensureInitialized()
    private suspend fun <T> write(notify: Boolean = true, local: Boolean = true, block: suspend (Cx) -> T): T = core.write(notify, local, block)
    private suspend fun apply(change: io.github.uirlisi.mostor.core.Change) = core.apply(change)
    private suspend fun putSetting(key: String, value: String) = core.putSetting(key, value)
    private suspend fun setSettingsQuietly(values: Map<String, String?>) = core.setSettingsQuietly(values)
    private suspend fun backupRows() = core.backupRows()

    data class Household(val id: String, val key: ByteArray)

    /** This phone's household, or null when it has none (or its key can't be opened here). */
    suspend fun household(): Household? {
        val id = db.settings().get(Keys.HOUSEHOLD_ID) ?: return null
        val wrapped = db.settings().get(Keys.HOUSEHOLD_KEY) ?: return null
        val key = vault?.unwrap(wrapped) ?: return null
        return Household(id, key)
    }

    /** The household, made now if this phone has none (the first "Invite a phone"). */
    suspend fun ensureHousehold(): Household {
        household()?.let { return it }
        val v = vault ?: error("no key vault")
        val h = Household(Ids.uuidV7(wallClock()), io.github.uirlisi.mostor.sync.SyncCrypto.randomBytes(32))
        setSettingsQuietly(mapOf(Keys.HOUSEHOLD_ID to h.id, Keys.HOUSEHOLD_KEY to v.wrap(h.key)))
        return h
    }

    /**
     * After joining: the household, the phone that added this one, the household's phones as that
     * phone has them, and its children and meds to match against (sessions wait until
     * [matchChildren]). This database merges into the household (SPEC §9.4 "Joining"), so only one
     * no other phone is active in may join ([JoinWay.MERGE] in [joinWays]); a household it had on its
     * own is given up. Its rows marked deleted are deleted for good first ([purgeDeleted]). This
     * phone's own row comes with the stamp of an upgrade row, so the adding phone's row for it (sent
     * at the first session) wins.
     */
    suspend fun joinHousehold(
        id: String,
        key: ByteArray,
        peerId: String,
        peerName: String,
        theirs: Merge.Rows,
        members: List<Member> = emptyList(),
        myName: String = "",
    ) {
        val v = vault ?: error("no key vault")
        val pending = BackupJson.encode(theirs, wallClock(), "pairing")
        val me = deviceId()
        write(notify = false, local = false) { cx ->
            check(JoinWay.MERGE in waysHere(me)) { "another phone is still active in this household" }
            purgeDeleted()
            if (db.settings().get(Keys.HOUSEHOLD_ID) != id) {
                db.peers().deleteAll()
                db.members().deleteAll()
                db.settings().delete(Keys.SYNC_FAREWELL)
            }
            db.settings().delete(Keys.SYNC_REMOVED_BY)
            db.settings().delete(Keys.SYNC_LEFT)
            putSetting(Keys.HOUSEHOLD_ID, id)
            putSetting(Keys.HOUSEHOLD_KEY, v.wrap(key))
            putSetting(Keys.SYNC_PENDING_MATCH, pending)
            db.peers().upsert(PeerEntity(peerId, peerName, wallClock(), null, null))
            val adder = members.firstOrNull { it.deviceId == peerId }
                ?: Member(peerId, peerName, addedAt = cx.now, hlc = Roster.SEED_HLC)
            val mine = Member(me, myName, addedBy = peerId, addedAt = cx.now, hlc = Roster.SEED_HLC)
            mergeMembers(members.filter { it.deviceId != me && it.deviceId != peerId } + adder + mine, cx)
        }
    }

    /**
     * Before this database merges into a household (SPEC §9.4 "Joining"): its rows marked deleted are
     * deleted for good, with everything under a deleted child (its records, their segments, its meds).
     * They would carry names and notes into the household unseen, and no other phone is active in
     * this database's household, so no phone needs them. A deliberate exception to "no hard deletes
     * of synced rows" (DECISIONS, 2026-11-18). A live child's deleted meds stay: its past doses may
     * still show their names. The undo history goes too, since it may point at the deleted rows.
     * Inside the caller's transaction.
     */
    private suspend fun purgeDeleted() {
        val sql = db.openHelper.writableDatabase
        val deadChildren = "SELECT id FROM child WHERE deleted_at IS NOT NULL"
        val deadEvents = "SELECT id FROM event WHERE deleted_at IS NOT NULL OR child_id IN ($deadChildren)"
        sql.execSQL("DELETE FROM breast_segment WHERE deleted_at IS NOT NULL OR event_id IN ($deadEvents)")
        sql.execSQL("DELETE FROM event WHERE id IN ($deadEvents)")
        sql.execSQL("DELETE FROM supplement_preset WHERE child_id IN ($deadChildren)")
        sql.execSQL("DELETE FROM child WHERE deleted_at IS NOT NULL")
        db.undo().clear()
    }

    /**
     * The adding phone, once a phone has joined: it is a phone of the household from now on, on
     * every phone (added by this one).
     */
    suspend fun addMember(id: String, name: String) {
        val me = deviceId()
        write(notify = false) { cx ->
            db.peers().upsert(PeerEntity(id, name, wallClock(), null, null))
            db.members().upsert(listOf(Roster.add(id, name, by = me, cx).toEntity()))
            db.settings().get(Keys.SYNC_FAREWELL)?.let { putSetting(Keys.SYNC_FAREWELL, HouseholdJson.farewellJson(HouseholdJson.farewellMap(it) - id)) }
            core.syncedWrites++
        }
    }

    /**
     * "Remove" (SPEC §9.4): [id] is removed from the household, on every phone once they sync. This
     * phone keeps listening for it until it has been told (or for [FAREWELL_MS]). It keeps the
     * records it has: this isn't revocation (the household key stays the same until Phase 7).
     */
    suspend fun removeMember(id: String) {
        val me = deviceId()
        write(notify = false) { cx ->
            val m = members().firstOrNull { it.deviceId == id && it.active } ?: return@write
            db.members().upsert(listOf(Roster.remove(m, by = me, cx).toEntity()))
            db.peers().delete(id)
            val farewell = db.settings().get(Keys.SYNC_FAREWELL)?.let(HouseholdJson::farewellMap).orEmpty()
            putSetting(Keys.SYNC_FAREWELL, HouseholdJson.farewellJson(farewell + (id to cx.now)))
            core.syncedWrites++
        }
    }

    /**
     * Keeps the household's list right on this phone: after an upgrade from schema version 3 it is
     * made from the paired phones (with upgrade stamps, so a removal made elsewhere still wins), and
     * this phone's own row follows its current [myName].
     */
    suspend fun ensureRoster(myName: String) {
        if (db.settings().get(Keys.HOUSEHOLD_ID) == null) return
        val me = deviceId()
        write(notify = false) { cx ->
            var members = members()
            if (members.isEmpty()) {
                members = db.peers().all().map { Member(it.deviceId, it.name, addedAt = it.pairedAt, hlc = Roster.SEED_HLC) } +
                    Member(me, myName, addedAt = cx.now, hlc = Roster.SEED_HLC)
                db.members().upsert(members.map { it.toEntity() })
            }
            val mine = members.firstOrNull { it.deviceId == me }
            when {
                mine == null -> db.members().upsert(listOf(Member(me, myName, addedAt = cx.now, hlc = Roster.SEED_HLC).toEntity()))
                mine.active && mine.name != myName && myName.isNotBlank() -> {
                    db.members().upsert(listOf(Roster.rename(mine, myName, cx).toEntity()))
                    core.syncedWrites++
                }
            }
        }
    }

    /** The household's phones, removed ones included. */
    override suspend fun members(): List<Member> = db.members().all().map { it.toModel() }

    /** The other phones this one syncs with: the household's phones, less removed ones and this one. */
    suspend fun others(): List<Member> {
        val me = deviceId()
        return Roster.active(members()).filter { it.deviceId != me }
    }

    /**
     * The phones a sync round waits for: the other active phones, and phones removed here that
     * haven't been told yet. Without the latter, a removed phone only learned it was removed when it
     * next asked for a sync itself (minutes later, or never while it was idle).
     */
    suspend fun roundPeers(): Set<String> = others().mapTo(LinkedHashSet()) { it.deviceId } + farewells()

    /** Phones removed here that haven't been told yet (younger than [FAREWELL_MS]). */
    suspend fun farewells(): Set<String> {
        val now = wallClock()
        return db.settings().get(Keys.SYNC_FAREWELL)?.let(HouseholdJson::farewellMap).orEmpty().filterValues { now - it < FAREWELL_MS }.keys
    }

    /**
     * "Leave household" (SPEC §9.4): this phone removes itself. Its own row says it left, and it
     * stays until its next session with one of the others, which lets it in once more: that phone
     * gets its leaving and its last records, and spreads both (see [SyncStore.standing]). Returns
     * false then. The only active phone of a household has no Leave (it already is a household of
     * its own): nothing changes, and it returns true.
     */
    suspend fun leave(): Boolean {
        val me = deviceId()
        return write(notify = false, local = false) { cx ->
            if (db.settings().get(Keys.HOUSEHOLD_ID) == null) return@write true
            val members = members()
            if (Roster.active(members).none { it.deviceId != me }) return@write true
            val mine = members.firstOrNull { it.deviceId == me } ?: Member(me, "", addedAt = cx.now, hlc = Roster.SEED_HLC)
            db.members().upsert(listOf(Roster.remove(mine, by = me, cx).toEntity()))
            false
        }
    }

    /** This phone has left its household and hasn't told another phone yet. */
    suspend fun isLeaving(): Boolean = db.settings().get(Keys.HOUSEHOLD_ID) != null && Roster.leaving(members(), deviceId())

    /**
     * This database leaves its household (SPEC §9.4 "Leaving and being removed"): another phone
     * removed it ([removedBy] is that phone's name), it told another phone it left, or it left without
     * telling ([removedBy] null). It becomes a household of its own and keeps its records: the old
     * household's id and key, its list of phones and their sync state go, so the old household's
     * phones can't reach it any more, and the next "Invite a phone" makes a new id and key. A notice
     * shows once in Settings.
     */
    suspend fun leaveHousehold(removedBy: String?) = write(notify = false, local = false) { leaveInTransaction(removedBy) }

    private suspend fun leaveInTransaction(removedBy: String?, left: String = LEFT_TOLD) {
        for (key in listOf(Keys.HOUSEHOLD_ID, Keys.HOUSEHOLD_KEY, Keys.SYNC_PENDING_MATCH, Keys.SYNC_FAREWELL, Keys.SYNC_REMOVED_BY, Keys.SYNC_LEFT)) {
            db.settings().delete(key)
        }
        db.members().deleteAll()
        db.peers().deleteAll()
        if (removedBy == null) putSetting(Keys.SYNC_LEFT, left) else putSetting(Keys.SYNC_REMOVED_BY, removedBy)
    }

    suspend fun dismissRemovedNotice() = setSettingsQuietly(mapOf(Keys.SYNC_REMOVED_BY to null, Keys.SYNC_LEFT to null))

    /**
     * "Leave now", and a Leave that no phone answered (SPEC §9.4): this phone leaves at once, and keeps
     * the old household's id and key, with its own "left" row, for up to [FAREWELL_MS] ([Parting]). The
     * next time it hears one of that household's phones, it hands over its leaving and its own records
     * from before it left, and nothing comes back ([partingRows], [partingDone]). Until then the
     * others keep trying to reach it.
     */
    suspend fun leaveNow() {
        val me = deviceId()
        write(notify = false, local = false) { cx ->
            val id = db.settings().get(Keys.HOUSEHOLD_ID) ?: return@write
            val key = db.settings().get(Keys.HOUSEHOLD_KEY) ?: return@write leaveInTransaction(null)
            val members = members()
            val mine = members.firstOrNull { it.deviceId == me }
                ?.takeIf { Roster.leaving(members, me) }
                ?: Roster.remove(members.firstOrNull { it.deviceId == me } ?: Member(me, "", addedAt = cx.now, hlc = Roster.SEED_HLC), by = me, cx)
            val parting = HouseholdJson.partingJson(id, key, cx.now + FAREWELL_MS, mine)
            leaveInTransaction(null, left = LEFT_LATER)
            putSetting(Keys.SYNC_PARTING, parting)
        }
    }

    /** A household this database left without telling it yet, while it may still tell it ([leaveNow]). */
    data class Parting(val householdId: String, val key: ByteArray, val row: Member, val until: Long)

    suspend fun parting(): Parting? {
        val text = db.settings().get(Keys.SYNC_PARTING) ?: return null
        return runCatching {
            val kept = HouseholdJson.parting(text)
            val key = vault?.unwrap(kept.wrappedKey) ?: return null
            Parting(kept.householdId, key, kept.row, kept.until)
        }.getOrNull()?.takeIf { wallClock() < it.until }
    }

    /**
     * What a parting session hands over: this phone's own changes from before it left (rows it made,
     * stamped no later than its leaving) that the other phone lacks. Rows from other phones, and
     * anything since (in a household it joined later, too), stay here.
     */
    suspend fun partingRows(p: Parting, vector: Map<String, String>): Merge.Rows {
        val me = deviceId()
        fun mine(hlc: String) = hlc <= p.row.hlc && runCatching { HlcStamp.parse(hlc).origin == me }.getOrDefault(false)
        val rows = Sync.since(backupRows(), vector)
        return Merge.Rows(
            children = rows.children.filter { mine(it.hlc) },
            events = rows.events.filter { mine(it.hlc) },
            segments = rows.segments.filter { mine(it.hlc) },
            presets = rows.presets.filter { mine(it.hlc) },
        )
    }

    /** The old household now knows that this phone left (or that another phone removed it): the key goes. */
    suspend fun partingDone() = setSettingsQuietly(mapOf(Keys.SYNC_PARTING to null))

    /**
     * Phones of the household that left it by themselves, whose leaving this phone hasn't shown yet
     * (SPEC §9.4: "S901U1 left the household", once in Households and phones).
     */
    private suspend fun departedHere(me: String): List<Member> = members().filter { m ->
        m.deviceId != me && !m.active && m.removedBy == m.deviceId &&
            db.settings().get(Keys.DEPARTED_SEEN_PREFIX + m.deviceId) != m.removedAt.toString()
    }

    /** The owner has read that [m] left. */
    suspend fun dismissDeparted(m: Member) = setSettingsQuietly(mapOf(Keys.DEPARTED_SEEN_PREFIX + m.deviceId to m.removedAt?.toString()))

    /** How this database may join another household (SPEC §9.4 "Joining", [Joining.ways]). */
    suspend fun joinWays(): List<JoinWay> {
        val me = deviceId()
        return db.withTransaction { waysHere(me) }
    }

    /** Inside a transaction. A database with no household yet has no other phone in it. */
    private suspend fun waysHere(me: String): List<JoinWay> =
        Joining.ways(if (db.settings().get(Keys.HOUSEHOLD_ID) == null) emptyList() else members(), me)

    /** This database holds only the untouched starter child ([Merge.isBlank]): it joins without a question. */
    suspend fun holdsOnlyBlank(): Boolean {
        ensureInitialized()
        return db.withTransaction { blankHere() }
    }

    private suspend fun blankHere(): Boolean {
        val live = db.children().live()
        return live.isNotEmpty() && live.all { c ->
            Merge.isBlank(c.toModel(), db.events().countOf(c.id), db.presets().ofChild(c.id).map { it.toModel() })
        }
    }

    /**
     * This household's per-phone settings to start a new household with (SPEC §9.4): the owner's
     * choices (units, timers, day start, lock screen, theme, what's shown, the daily backup's folder),
     * without the ones that point at records or belong to this household's sync and bookkeeping.
     */
    suspend fun choicesForNewHousehold(): Map<String, String> {
        ensureInitialized()
        return db.settings().all().map { it.key to it.value }.filter { (key, _) -> isCopied(key) }.toMap()
    }

    /** A new household takes [choices] from the active one ([choicesForNewHousehold]). */
    suspend fun adoptChoices(choices: Map<String, String>) {
        write(notify = false, local = false) {
            for ((key, value) in choices) if (isCopied(key)) putSetting(key, value)
        }
    }

    /** What Settings › Sync lists: the household's phones, how sessions with them went, and a notice. */
    data class SyncPhones(
        val me: String,
        val members: List<Member>,
        val stats: Map<String, PeerEntity>,
        /** Who removed this phone, or null; shown once. */
        val removedBy: String?,
        /** This phone left its household by itself; shown once. */
        val left: Boolean,
        /** How this database may join another household ([joinWays]). */
        val ways: List<JoinWay>,
        /** It holds only the untouched starter child: joining merges without a question. */
        val blank: Boolean,
        /** [left] without reaching another phone: it tells them the next time one is near. */
        val leftLater: Boolean = false,
        /** Other phones that left the household by themselves, not shown yet; each shown once. */
        val departed: List<Member> = emptyList(),
    ) {
        val others: List<Member> get() = members.filter { it.active && it.deviceId != me }
        val active: List<Member> get() = members.filter { it.active }
        val leaving: Boolean get() = Roster.leaving(members, me)
        fun name(deviceId: String?): String? = members.firstOrNull { it.deviceId == deviceId }?.name
    }

    fun syncPhonesFlow(): Flow<SyncPhones> =
        db.invalidationTracker.createFlow("peer", "member", "setting", "child", "event", "supplement_preset").conflate().map {
            val me = deviceId()
            db.withTransaction {
                SyncPhones(
                    me,
                    if (db.settings().get(Keys.HOUSEHOLD_ID) == null) emptyList() else members(),
                    db.peers().all().associateBy { it.deviceId },
                    db.settings().get(Keys.SYNC_REMOVED_BY),
                    db.settings().get(Keys.SYNC_LEFT) != null,
                    waysHere(me),
                    blankHere(),
                    leftLater = db.settings().get(Keys.SYNC_LEFT) == LEFT_LATER,
                    departed = if (db.settings().get(Keys.HOUSEHOLD_ID) == null) emptyList() else departedHere(me),
                )
            }
        }

    /** Records how a session with [id] went: [ok], or a short reason. */
    suspend fun peerResult(id: String, ok: Boolean, reason: String? = null) = write(notify = false) {
        val row = db.peers().get(id) ?: PeerEntity(
            id, members().firstOrNull { it.deviceId == id }?.name ?: "", wallClock(), null, null,
        ).also { db.peers().upsert(it) }
        val at = if (ok) wallClock() else row.lastSyncAt
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
    private suspend fun mergeIn(incoming: Merge.Rows, cx: Cx): io.github.uirlisi.mostor.core.Change {
        val rows = incoming.notAfter(cx.now + HybridClock.MAX_AHEAD_MS)
        cx.observeNewest(rows.newestHlc())
        val change = Merge.plan(core.localRows(rows.ids()), rows).change
        core.applyMerged(change, cx)
        return change
    }

    override suspend fun applyMembers(theirs: List<Member>): Int = write(notify = false, local = false) { cx -> mergeMembers(theirs, cx) }

    /** Merges another phone's list of the household's phones inside the caller's transaction. */
    private suspend fun mergeMembers(theirs: List<Member>, cx: Cx): Int {
        cx.observeNewest(Roster.newestHlc(theirs))
        val changes = Roster.changes(members(), theirs)
        if (changes.isNotEmpty()) db.members().upsert(changes.map { it.toEntity() })
        return changes.size
    }

    /** No phone is let in while child matching is pending: until then, each is unknown here. */
    override suspend fun standing(deviceId: String): Standing =
        if (db.settings().get(Keys.SYNC_PENDING_MATCH) != null) Standing.UNKNOWN else Roster.standing(members(), deviceId)

    override suspend fun told(deviceId: String) {
        write(notify = false, local = false) {
            val farewell = db.settings().get(Keys.SYNC_FAREWELL)?.let(HouseholdJson::farewellMap) ?: return@write
            if (deviceId !in farewell) return@write
            val left = farewell - deviceId
            if (left.isEmpty()) db.settings().delete(Keys.SYNC_FAREWELL) else putSetting(Keys.SYNC_FAREWELL, HouseholdJson.farewellJson(left))
        }
    }

    companion object {
        /** How long a phone keeps listening for a phone it removed, to tell it (SPEC §9.4). */
        const val FAREWELL_MS = 30 * 24 * 3_600_000L

        /** [Keys.SYNC_LEFT]: the household was told at once, or will be when one of its phones is next near. */
        private const val LEFT_TOLD = "1"
        private const val LEFT_LATER = "later"

        /**
         * Settings a new household doesn't take from the active one ([choicesForNewHousehold]): those
         * that point at records (children, meds, amounts last used) or say a notice was read, and the
         * household's own sync and bookkeeping. The owner's choices are copied.
         */
        private val NOT_COPIED = setOf(
            Keys.ACTIVE_CHILD, Keys.TODAY_CHILD, Keys.TODAY_ALL_CHILDREN, Keys.CHART_CHILD, Keys.LOG_CHILDREN,
            Keys.PATTERNS_FOCUS, Keys.STATS_FOCUS, Keys.LAST_BOTTLE_ML, Keys.LAST_BOTTLE_CONTENT, Keys.LAST_PUMP_ML,
            Keys.STATUS_DISMISSED, Keys.SYNC_LEFT, Keys.SYNC_REMOVED_BY, Keys.SYNC_PENDING_MATCH, Keys.SYNC_FAREWELL,
            Keys.HOUSEHOLD_ID, Keys.HOUSEHOLD_KEY, Keys.INSTALL_ID, Keys.PRESETS_SEEDED,
            Keys.AUTO_EXPORT_LAST, Keys.AUTO_EXPORT_STATUS, Keys.SYNC_PARTING,
        )
        private val NOT_COPIED_PREFIXES = listOf(
            Keys.SNOOZE_PREFIX, Keys.NO_END_DISMISSED_PREFIX, Keys.CONFLICT_KEPT_PREFIX, Keys.CONFLICT_ALERT_DISMISSED_PREFIX,
            Keys.DEPARTED_SEEN_PREFIX,
        )

        /** Whether a new household takes the setting [key] from the active one ([NOT_COPIED]). */
        private fun isCopied(key: String): Boolean = key !in NOT_COPIED && NOT_COPIED_PREFIXES.none { key.startsWith(it) }
    }
}

/**
 * The farewell list ([Keys.SYNC_FAREWELL]) and a parting ([Keys.SYNC_PARTING]) as they are kept in
 * settings, as JSON.
 */
private object HouseholdJson {
    /** Phones removed here that haven't been told yet, by device id, with when each was removed. */
    fun farewellMap(json: String): Map<String, Long> = runCatching {
        val o = org.json.JSONObject(json)
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    fun farewellJson(map: Map<String, Long>): String = org.json.JSONObject(map as Map<*, *>).toString()

    /** A parting as kept: the household key still wrapped ([KeyVault.wrap]). */
    class KeptParting(val householdId: String, val wrappedKey: String, val row: Member, val until: Long)

    fun partingJson(householdId: String, wrappedKey: String, until: Long, row: Member): String = org.json.JSONObject()
        .put("id", householdId).put("key", wrappedKey).put("until", until)
        .put("row", io.github.uirlisi.mostor.sync.membersJson(listOf(row)))
        .toString()

    /** Throws when [json] isn't a parting. */
    fun parting(json: String): KeptParting {
        val o = org.json.JSONObject(json)
        val row = io.github.uirlisi.mostor.sync.membersOf(org.json.JSONObject().put("members", o.getJSONArray("row"))).single()
        return KeptParting(o.getString("id"), o.getString("key"), row, o.getLong("until"))
    }
}
