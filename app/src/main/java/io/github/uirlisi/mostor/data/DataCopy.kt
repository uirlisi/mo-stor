package io.github.uirlisi.mostor.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * "Save a copy of the data" and "Restore from a copy" (DECISIONS 2026-11-13, pulled forward from
 * Phase 4). A copy is the whole database in one SQLite file, written with `VACUUM INTO`, so it is a
 * consistent snapshot even while the app is writing. Restoring reads only the synced tables from
 * it: the phone keeps its own device id, clock, settings and undo history (see
 * [Repository.restoreFrom]).
 */
object DataCopy {
    /** The synced tables (SPEC §4.2), the only ones a restore takes from a copy. */
    val SYNCED_TABLES = listOf("child", "event", "breast_segment", "supplement_preset")

    private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** The synced tables' rows, every column as stored (soft-deleted rows included). */
    class Rows(val tables: Map<String, List<ContentValues>>) {
        /** What these rows hold, counted the way the app shows records (deleted children's hidden). */
        fun summary(): Summary {
            val children = tables["child"].orEmpty().filter { it.getAsLong("deleted_at") == null }
            val liveChildren = children.mapTo(HashSet()) { it.getAsString("id") }
            val events = tables["event"].orEmpty()
                .filter { it.getAsLong("deleted_at") == null && it.getAsString("child_id") in liveChildren }
            return Summary(children.size, events.size, events.maxOfOrNull { it.getAsLong("start_at") })
        }

        /** The newest change stamp in these rows (stamps sort as text, SPEC §4.1). */
        fun newestHlc(): String? = tables.values.asSequence().flatten().mapNotNull { it.getAsString("hlc") }.maxOrNull()
    }

    /** Children and records that aren't deleted, and the start of the newest record. */
    data class Summary(val children: Int, val records: Int, val newestStart: Long?)

    sealed interface Read {
        data class Ok(val rows: Rows) : Read
        /** Not an SQLite file, or not one of Mo Stór's databases. */
        data object NotACopy : Read
        /** Made by a newer version of the app, with a schema this one can't read. */
        data object Newer : Read
    }

    /** Writes a copy of [db] to [target], replacing any file there. Not inside a transaction. */
    fun write(db: MoStorDb, target: File) {
        target.delete()
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any>(target.absolutePath))
    }

    /**
     * Reads a copy. [file] must be a scratch file the caller owns: an older copy is migrated in
     * place to the current schema (the same migrations as an app update) before it is read.
     */
    fun read(context: Context, file: File): Read {
        val header = file.inputStream().use { it.readNBytes(HEADER.size) }
        if (!header.contentEquals(HEADER)) return Read.NotACopy
        val (version, tables) = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val names = d.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
                    .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                d.version to names
            }
        } catch (e: Exception) {
            return Read.NotACopy
        }
        if (version > MoStorDb.LATEST_VERSION) return Read.Newer
        if (version < 1 || !tables.containsAll(SYNCED_TABLES + "room_master_table")) return Read.NotACopy
        // Room runs any migrations and checks the schema's identity hash; a mismatch throws.
        val copy = Room.databaseBuilder(context.applicationContext, MoStorDb::class.java, file.absolutePath)
            .addMigrations(*MoStorDb.MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        return try {
            Read.Ok(readRows(copy.openHelper.writableDatabase))
        } catch (e: Exception) {
            Log.w("MoStor", "Copy not readable: ${e.javaClass.simpleName}")
            Read.NotACopy
        } finally {
            copy.close()
        }
    }

    /** The synced tables of [db], every row and column. */
    fun readRows(db: SupportSQLiteDatabase): Rows = Rows(
        SYNCED_TABLES.associateWith { table ->
            db.query("SELECT * FROM `$table`").use { c -> buildList { while (c.moveToNext()) add(c.toValues()) } }
        },
    )

    private fun Cursor.toValues() = ContentValues(columnCount).also { v ->
        for (i in 0 until columnCount) {
            val name = getColumnName(i)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> v.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> v.put(name, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> v.put(name, getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> v.put(name, getBlob(i))
                else -> v.put(name, getString(i))
            }
        }
    }
}
