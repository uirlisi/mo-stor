package io.github.uirlisi.mostor.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema version 1 (SPEC §4). Synced tables carry id, created_at, updated_at, deleted_at and hlc.
 * There are no foreign keys: sync (Phase 5) may apply a segment before its event arrives.
 */

@Entity(tableName = "child")
data class ChildEntity(
    @PrimaryKey val id: String,
    val name: String?,
    @ColumnInfo(name = "birth_date") val birthDate: String?,
    val sex: String?,
    @ColumnInfo(name = "birth_weight_g") val birthWeightG: Int?,
    @ColumnInfo(name = "birth_length_mm") val birthLengthMm: Int?,
    val color: Int?,
    @ColumnInfo(name = "sort_key") val sortKey: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val hlc: String,
)

@Entity(
    tableName = "event",
    indices = [
        Index("child_id", "start_at"),
        Index("start_at"),
        Index("status"),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "child_id") val childId: String,
    val kind: String,
    val subtype: String?,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    val status: String?,
    @ColumnInfo(name = "amount_ml") val amountMl: Double?,
    @ColumnInfo(name = "supplement_preset_id") val supplementPresetId: String?,
    @ColumnInfo(name = "supplement_name") val supplementName: String?,
    @ColumnInfo(name = "supplement_amount") val supplementAmount: Double?,
    @ColumnInfo(name = "supplement_unit") val supplementUnit: String?,
    val note: String?,
    val tz: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val hlc: String,
)

@Entity(tableName = "breast_segment", indices = [Index("event_id")])
data class SegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val side: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val hlc: String,
)

/** A med (schema version 2: per child, with a schedule that is only displayed; SPEC §4.2, §5.10). */
@Entity(tableName = "supplement_preset", indices = [Index("child_id")])
data class PresetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "child_id") val childId: String,
    val name: String,
    val amount: Double?,
    val unit: String?,
    @ColumnInfo(name = "sort_key") val sortKey: Int,
    @ColumnInfo(defaultValue = "as_needed") val schedule: String,
    @ColumnInfo(name = "schedule_weekday") val scheduleWeekday: Int?,
    @ColumnInfo(name = "schedule_every_days") val scheduleEveryDays: Int?,
    @ColumnInfo(name = "schedule_start") val scheduleStart: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val hlc: String,
)

/* Device-local tables (SPEC §4.3): never synced. */

/** One row: this phone's id and name, and the last HLC stamp it handed out. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "device_name") val deviceName: String?,
    val hlc: String?,
) {
    companion object {
        const val SINGLE_ROW = 1
    }
}

@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A recent reversible action (SPEC §5.7). [payload] is the change as JSON (see UndoCodec). */
@Entity(tableName = "undo", indices = [Index("created_at")])
data class UndoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** "sheet" or "notification": where the action was made. */
    val source: String,
    val payload: String,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
)

/**
 * A phone this one syncs with (SPEC §4.3, Phase 5; schema version 3). Device-local, never synced;
 * "Forget this phone" deletes the row. The household key the phones share is in `setting`, wrapped
 * by an Android Keystore key. Version vectors aren't stored: each session computes them from the
 * rows (DECISIONS 2026-11-14, "Sync protocol").
 */
@Entity(tableName = "peer")
data class PeerEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val name: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
    /** "ok", or a short reason the last attempt failed. */
    @ColumnInfo(name = "last_result") val lastResult: String?,
)
