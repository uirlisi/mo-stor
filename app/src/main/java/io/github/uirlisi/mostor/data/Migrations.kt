package io.github.uirlisi.mostor.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.HybridClock
import io.github.uirlisi.mostor.core.Presets
import java.util.UUID

/**
 * Schema 1 → 2 (Phase 3b; DECISIONS "Each child has their own meds"): every med belongs to one
 * child and gets a schedule (As needed for all, the seeds included). `supplement_preset` is rebuilt
 * (SQLite can't add a NOT NULL column without a default), and each med is split per child, covering
 * every child that isn't deleted, archived ones included, so each child's past doses stay with
 * that child's med:
 *
 * - **A med the owner made:** the oldest child keeps the row and its id; every other child gets a
 *   copy, and that child's doses move to it.
 * - **A seeded med** (the fixed-id Vit D and Vit K): each child gets its own, with an id derived
 *   from the child and the seed slot ([Presets.seedId]), and its doses move to it; the fixed-id
 *   rows are soft-deleted.
 *
 * Copies of the owner's meds get ids derived from the med and the child, so two phones that
 * migrate the same data make the same rows. Every row the migration changes gets a stamp from this
 * phone's clock (an untouched seed keeps [Presets.SEED_HLC]). The undo history is cleared: its
 * entries describe meds without a child.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val clock = db.query("SELECT device_id, hlc FROM meta WHERE id = 1").use { c ->
            if (!c.moveToFirst()) null else HybridClock(c.getString(0), c.str(1)?.let(HlcStamp::parse)) { now }
        } ?: HybridClock("migration-1-2", null) { now }

        // Children that aren't deleted, oldest first: the oldest keeps the owner's meds.
        val children = db.query("SELECT id FROM child WHERE deleted_at IS NULL ORDER BY created_at, rowid")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val anyChild = children.firstOrNull()
            ?: db.query("SELECT id FROM child ORDER BY created_at, rowid LIMIT 1").use { c -> if (c.moveToFirst()) c.getString(0) else "" }

        val old = db.query("SELECT id, name, amount, unit, sort_key, created_at, updated_at, deleted_at, hlc FROM supplement_preset")
            .use { c -> buildList { while (c.moveToNext()) add(OldPreset(c)) } }

        db.execSQL(
            "CREATE TABLE `supplement_preset_new` (`id` TEXT NOT NULL, `child_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`amount` REAL, `unit` TEXT, `sort_key` INTEGER NOT NULL, `schedule` TEXT NOT NULL DEFAULT 'as_needed', " +
                "`schedule_weekday` INTEGER, `schedule_every_days` INTEGER, `schedule_start` TEXT, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )

        fun stamp() = clock.now().encode()

        fun insert(p: OldPreset, id: String, childId: String, deletedAt: Long?, updatedAt: Long, hlc: String) {
            val v = ContentValues().apply {
                put("id", id)
                put("child_id", childId)
                put("name", p.name)
                put("amount", p.amount)
                put("unit", p.unit)
                put("sort_key", p.sortKey)
                put("schedule", "as_needed")
                put("created_at", p.createdAt)
                put("updated_at", updatedAt)
                put("deleted_at", deletedAt)
                put("hlc", hlc)
            }
            db.insert("supplement_preset_new", SQLiteDatabase.CONFLICT_ABORT, v)
        }

        /** Moves [childId]'s doses of med [from] to med [to], each a change stamped on this phone. */
        fun repoint(from: String, to: String, childId: String) {
            val ids = db.query("SELECT id FROM event WHERE supplement_preset_id = ? AND child_id = ?", arrayOf(from, childId))
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            for (id in ids) {
                db.execSQL(
                    "UPDATE event SET supplement_preset_id = ?, updated_at = ?, hlc = ? WHERE id = ?",
                    arrayOf<Any>(to, now, stamp(), id),
                )
            }
        }

        for (p in old) {
            val slot = SEED_SLOTS[p.id]
            if (slot != null) {
                val untouched = p.hlc == Presets.SEED_HLC
                for (child in children) {
                    val id = Presets.seedId(child, slot)
                    // An untouched seed stays the same row on every phone; an edited one is a change made here.
                    insert(p, id, child, p.deletedAt, if (untouched) p.updatedAt else now, if (untouched) Presets.SEED_HLC else stamp())
                    repoint(p.id, id, child)
                }
                insert(p, p.id, anyChild, p.deletedAt ?: now, now, stamp())
            } else {
                insert(p, p.id, anyChild, p.deletedAt, now, stamp())
                for (child in children.drop(1)) {
                    val id = splitId(p.id, child)
                    insert(p, id, child, p.deletedAt, now, stamp())
                    repoint(p.id, id, child)
                }
            }
        }

        db.execSQL("DROP TABLE `supplement_preset`")
        db.execSQL("ALTER TABLE `supplement_preset_new` RENAME TO `supplement_preset`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_supplement_preset_child_id` ON `supplement_preset` (`child_id`)")
        clock.last?.let { db.execSQL("UPDATE meta SET hlc = ? WHERE id = 1", arrayOf<Any>(it.encode())) }
        db.execSQL("DELETE FROM undo")
    }
}

/** The fixed ids schema 1 gave the household's Vit D and Vit K, and their seed slots. */
internal val SEED_SLOTS = mapOf(
    "00000000-0000-7000-8000-00000000d001" to 0,
    "00000000-0000-7000-8000-00000000d002" to 1,
)

/** The id of [childId]'s copy of the owner's med [presetId]: the same on every phone. */
internal fun splitId(presetId: String, childId: String): String =
    UUID.nameUUIDFromBytes("mostor:split:$presetId:$childId".toByteArray(Charsets.UTF_8)).toString()

private class OldPreset(c: Cursor) {
    val id: String = c.getString(0)
    val name: String = c.getString(1)
    val amount: Double? = if (c.isNull(2)) null else c.getDouble(2)
    val unit: String? = c.str(3)
    val sortKey: Int = c.getInt(4)
    val createdAt: Long = c.getLong(5)
    val updatedAt: Long = c.getLong(6)
    val deletedAt: Long? = if (c.isNull(7)) null else c.getLong(7)
    val hlc: String = c.getString(8)
}

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

/**
 * Version 3 (Phase 5): the device-local `peer` table for Bluetooth sync. Adds a table and touches
 * no existing row (DECISIONS 2026-11-14, "Schema version 3").
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PEER_TABLE_SQL)
    }
}

/** Schema version 4 (Phase 5 follow-up): the household's list of phones, synced (SPEC §9.4). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MEMBER_TABLE_SQL)
    }
}

/**
 * Schema version 5 (review 2026-11-19): `event.auto_base`, the stamp an automatic change (a timer given
 * up, a forgotten pause ended) was made from, so it loses to what another phone did meanwhile
 * (`Event.autoBase`, SPEC §9.5). A nullable column; existing rows are a person's changes (null).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `event` ADD COLUMN `auto_base` TEXT")
    }
}

/** As Room's exported schema 4 has it (app/schemas/…/4.json). */
internal const val MEMBER_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `member` (`device_id` TEXT NOT NULL, `name` TEXT NOT NULL, `added_by` TEXT, `added_at` INTEGER NOT NULL, " +
        "`removed_at` INTEGER, `removed_by` TEXT, `hlc` TEXT NOT NULL, PRIMARY KEY(`device_id`))"

/** As Room's exported schema 3 has it (app/schemas/…/3.json). */
internal const val PEER_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `peer` (`device_id` TEXT NOT NULL, `name` TEXT NOT NULL, `paired_at` INTEGER NOT NULL, " +
        "`last_sync_at` INTEGER, `last_result` TEXT, PRIMARY KEY(`device_id`))"
