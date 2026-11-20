package io.github.uirlisi.mostor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

private const val LIVE_CHILD = "AND child_id IN (SELECT id FROM child WHERE deleted_at IS NULL)"

@Dao
interface EventDao {
    @Upsert
    suspend fun upsertEvents(rows: List<EventEntity>)

    @Upsert
    suspend fun upsertSegments(rows: List<SegmentEntity>)

    /** By id, including deleted rows. */
    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun event(id: String): EventEntity?

    /** By id, including deleted rows. */
    @Query("SELECT * FROM breast_segment WHERE id = :id")
    suspend fun segment(id: String): SegmentEntity?

    @Query("SELECT * FROM breast_segment WHERE event_id IN (:eventIds) AND deleted_at IS NULL ORDER BY start_at, created_at")
    suspend fun liveSegments(eventIds: List<String>): List<SegmentEntity>

    // Events of a deleted child are hidden with it (SPEC §4.4): every list below leaves them out.
    @Query("SELECT * FROM event WHERE status = 'running' AND deleted_at IS NULL $LIVE_CHILD ORDER BY start_at DESC")
    suspend fun running(): List<EventEntity>

    @Query("SELECT * FROM event WHERE status = 'end_unknown' AND deleted_at IS NULL $LIVE_CHILD AND start_at >= :since ORDER BY start_at DESC")
    suspend fun endUnknownSince(since: Long): List<EventEntity>

    /** Ended automatically (a forgotten pause, SPEC §5.5) and not changed by anyone since. */
    @Query("SELECT * FROM event WHERE status = 'ended' AND auto_base IS NOT NULL AND deleted_at IS NULL $LIVE_CHILD AND end_at >= :since ORDER BY end_at DESC")
    suspend fun endedAutomaticallySince(since: Long): List<EventEntity>

    @Query(
        "SELECT * FROM event WHERE child_id = :childId AND kind IN (:kinds) AND deleted_at IS NULL " +
            "ORDER BY start_at DESC, created_at DESC LIMIT 1",
    )
    suspend fun latestOfKinds(childId: String, kinds: List<String>): EventEntity?

    @Query(
        "SELECT * FROM event WHERE child_id = :childId AND kind = :kind AND deleted_at IS NULL " +
            "ORDER BY start_at DESC LIMIT :limit",
    )
    suspend fun recentOfKind(childId: String, kind: String, limit: Int): List<EventEntity>

    @Query("SELECT * FROM event WHERE deleted_at IS NULL $LIVE_CHILD AND start_at >= :from AND start_at < :to ORDER BY start_at DESC, created_at DESC, rowid DESC")
    suspend fun inRange(from: Long, to: Long): List<EventEntity>

    /** Records of [childId] and [kind] that may overlap [from, to) (for the edit screen's highlight). */
    @Query(
        "SELECT * FROM event WHERE deleted_at IS NULL AND child_id = :childId AND kind = :kind " +
            "AND start_at < :to AND (end_at IS NULL OR end_at >= :from) AND start_at >= :from - 86400000",
    )
    suspend fun nearby(childId: String, kind: String, from: Long, to: Long): List<EventEntity>

    /** When the earliest live record starts (null when there are none). */
    /** A child's med doses before [before], given or skipped (SPEC §5.10). */
    @Query("SELECT * FROM event WHERE kind = 'supplement' AND deleted_at IS NULL AND child_id = :childId AND start_at < :before ORDER BY start_at")
    suspend fun doses(childId: String, before: Long): List<EventEntity>

    @Query("SELECT MIN(start_at) FROM event WHERE deleted_at IS NULL $LIVE_CHILD")
    suspend fun earliestStart(): Long?

    @Query("SELECT MIN(start_at) FROM event WHERE deleted_at IS NULL AND child_id = :childId")
    suspend fun earliestStartOf(childId: String): Long?

    /** The events stored under [ids], deleted ones included. */
    @Query("SELECT * FROM event WHERE id IN (:ids)")
    suspend fun eventsByIds(ids: List<String>): List<EventEntity>

    /** The segments stored under [ids], deleted ones included. */
    @Query("SELECT * FROM breast_segment WHERE id IN (:ids)")
    suspend fun segmentsByIds(ids: List<String>): List<SegmentEntity>

    /** Every event, deleted ones included (the JSON backup and the CSV export). */
    @Query("SELECT * FROM event ORDER BY start_at, created_at")
    suspend fun all(): List<EventEntity>

    /** Every segment, deleted ones included. */
    @Query("SELECT * FROM breast_segment ORDER BY start_at, created_at")
    suspend fun allSegments(): List<SegmentEntity>

    /** How many events a child has, deleted ones included. */
    @Query("SELECT COUNT(*) FROM event WHERE child_id = :childId")
    suspend fun countOf(childId: String): Int
}

@Dao
interface ChildDao {
    @Upsert
    suspend fun upsert(rows: List<ChildEntity>)

    @Query("SELECT * FROM child WHERE deleted_at IS NULL AND archived_at IS NULL ORDER BY sort_key, created_at LIMIT 1")
    suspend fun firstActive(): ChildEntity?

    @Query("SELECT * FROM child WHERE id = :id")
    suspend fun child(id: String): ChildEntity?

    @Query("SELECT COUNT(*) FROM child WHERE deleted_at IS NULL")
    suspend fun countLive(): Int

    /** Live children, archived ones included, in their order. */
    @Query("SELECT * FROM child WHERE deleted_at IS NULL ORDER BY sort_key, created_at")
    suspend fun live(): List<ChildEntity>

    /** Every child, deleted ones included. */
    @Query("SELECT * FROM child ORDER BY sort_key, created_at")
    suspend fun all(): List<ChildEntity>

    @Query("SELECT * FROM child WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<ChildEntity>
}

@Dao
interface PresetDao {
    @Upsert
    suspend fun upsert(rows: List<PresetEntity>)

    @Query("SELECT * FROM supplement_preset WHERE deleted_at IS NULL ORDER BY sort_key, created_at")
    suspend fun live(): List<PresetEntity>

    @Query("SELECT * FROM supplement_preset WHERE id = :id")
    suspend fun preset(id: String): PresetEntity?

    /** Every med, deleted ones included. */
    @Query("SELECT * FROM supplement_preset ORDER BY sort_key, created_at")
    suspend fun all(): List<PresetEntity>

    @Query("SELECT * FROM supplement_preset WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<PresetEntity>

    /** A child's meds, deleted ones included. */
    @Query("SELECT * FROM supplement_preset WHERE child_id = :childId")
    suspend fun ofChild(childId: String): List<PresetEntity>
}

@Dao
interface MetaDao {
    @Query("SELECT * FROM meta WHERE id = 1")
    suspend fun get(): MetaEntity?

    @Upsert
    suspend fun upsert(meta: MetaEntity)

    @Query("UPDATE meta SET hlc = :hlc WHERE id = 1")
    suspend fun setHlc(hlc: String)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM setting")
    suspend fun all(): List<SettingEntity>

    @Query("SELECT value FROM setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface UndoDao {
    @Insert
    suspend fun insert(row: UndoEntity)

    @Query("SELECT * FROM undo WHERE id = :id")
    suspend fun get(id: String): UndoEntity?

    @Query("SELECT * FROM undo ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latest(): UndoEntity?

    @Query("UPDATE undo SET payload = :payload WHERE id = :id")
    suspend fun setPayload(id: String, payload: String)

    @Query("UPDATE undo SET undone_at = :at WHERE id = :id")
    suspend fun markUndone(id: String, at: Long)

    @Query("DELETE FROM undo")
    suspend fun clear()

    @Query("DELETE FROM undo WHERE created_at < :before")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peer ORDER BY paired_at")
    suspend fun all(): List<PeerEntity>

    @Query("SELECT * FROM peer WHERE device_id = :deviceId")
    suspend fun get(deviceId: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peer SET last_sync_at = :at, last_result = :result WHERE device_id = :deviceId")
    suspend fun setResult(deviceId: String, at: Long?, result: String)

    @Query("DELETE FROM peer WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("DELETE FROM peer")
    suspend fun deleteAll()
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM member ORDER BY added_at, device_id")
    suspend fun all(): List<MemberEntity>

    @Upsert
    suspend fun upsert(rows: List<MemberEntity>)

    @Query("DELETE FROM member")
    suspend fun deleteAll()
}
