package io.github.uirlisi.mostor.data

import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The JSON backup (SPEC §8, Phase 4): every synced row, tombstones included, so an import merges
 * deletions too (SPEC §5.9). Device-local tables (settings, undo, this phone's id) are not in it.
 *
 * ```
 * { "format": "mo-stor-backup", "version": 1, "exported_at": <UTC ms>, "app_version": "0.4.0",
 *   "children": [...], "supplement_presets": [...], "events": [...], "breast_segments": [...] }
 * ```
 * Each row's keys are the database's column names, with every column present (null when unset).
 * A reader refuses a higher `version`; a later version that changes the rows bumps it and reads the
 * older ones (DECISIONS 2026-11-14, "JSON backup format").
 */
object BackupJson {
    const val FORMAT = "mo-stor-backup"
    const val VERSION = 1

    data class Backup(val rows: Merge.Rows, val exportedAt: Long)

    sealed interface Read {
        data class Ok(val backup: Backup) : Read
        /** Not JSON, not a Mo Stór backup, or a row that doesn't make sense. */
        data object NotABackup : Read
        /** Written by a newer version of the app. */
        data object Newer : Read
    }

    fun encode(rows: Merge.Rows, exportedAt: Long, appVersion: String): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exported_at", exportedAt)
        .put("app_version", appVersion)
        .put("children", JSONArray(rows.children.map { it.toJson() }))
        .put("supplement_presets", JSONArray(rows.presets.map { it.toJson() }))
        .put("events", JSONArray(rows.events.map { it.toJson() }))
        .put("breast_segments", JSONArray(rows.segments.map { it.toJson() }))
        .toString()

    fun decode(text: String): Read {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Read.NotABackup
        }
        if (root.optString("format") != FORMAT) return Read.NotABackup
        val version = root.optInt("version", -1)
        if (version > VERSION) return Read.Newer
        if (version < 1) return Read.NotABackup
        return try {
            val rows = Merge.Rows(
                children = root.rows("children") { it.child() },
                events = root.rows("events") { it.event() },
                segments = root.rows("breast_segments") { it.segment() },
                presets = root.rows("supplement_presets") { it.preset() },
            )
            Read.Ok(Backup(rows, root.getLong("exported_at")))
        } catch (e: JSONException) {
            Read.NotABackup
        } catch (e: IllegalArgumentException) {
            Read.NotABackup
        }
    }

    // ---- Rows to JSON ----

    private fun JSONObject.common(id: String, createdAt: Long, updatedAt: Long, deletedAt: Long?, hlc: String) = this
        .put("id", id)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("deleted_at", deletedAt.orNull())
        .put("hlc", hlc)

    private fun Child.toJson() = JSONObject()
        .common(id, createdAt, updatedAt, deletedAt, hlc)
        .put("name", name.orNull())
        .put("birth_date", birthDate.orNull())
        .put("sex", sex.orNull())
        .put("birth_weight_g", birthWeightG.orNull())
        .put("birth_length_mm", birthLengthMm.orNull())
        .put("color", color.orNull())
        .put("sort_key", sortKey)
        .put("archived_at", archivedAt.orNull())

    private fun Event.toJson() = JSONObject()
        .common(id, createdAt, updatedAt, deletedAt, hlc)
        .put("child_id", childId)
        .put("kind", kind.code)
        .put("subtype", subtype.orNull())
        .put("start_at", startAt)
        .put("end_at", endAt.orNull())
        .put("status", status?.code.orNull())
        .put("amount_ml", amountMl.orNull())
        .put("supplement_preset_id", supplementPresetId.orNull())
        .put("supplement_name", supplementName.orNull())
        .put("supplement_amount", supplementAmount.orNull())
        .put("supplement_unit", supplementUnit.orNull())
        .put("note", note.orNull())
        .put("tz", tz)

    private fun BreastSegment.toJson() = JSONObject()
        .common(id, createdAt, updatedAt, deletedAt, hlc)
        .put("event_id", eventId)
        .put("side", side.code)
        .put("start_at", startAt)
        .put("end_at", endAt.orNull())

    private fun SupplementPreset.toJson() = JSONObject()
        .common(id, createdAt, updatedAt, deletedAt, hlc)
        .put("child_id", childId)
        .put("name", name)
        .put("amount", amount.orNull())
        .put("unit", unit.orNull())
        .put("sort_key", sortKey)
        .put("schedule", schedule.code)
        .put("schedule_weekday", scheduleWeekday.orNull())
        .put("schedule_every_days", scheduleEveryDays.orNull())
        .put("schedule_start", scheduleStart.orNull())

    private fun Any?.orNull(): Any = this ?: JSONObject.NULL

    // ---- JSON to rows (strict: a missing or mistyped field refuses the file) ----

    private fun <T> JSONObject.rows(key: String, read: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        return List(array.length()) { read(array.getJSONObject(it)) }
    }

    /** A nullable column: the key must be there (every column is written), its value may be null. */
    private inline fun <T : Any> JSONObject.nullable(key: String, read: JSONObject.(String) -> T): T? {
        require(has(key)) { "missing $key" }
        return if (isNull(key)) null else read(key)
    }

    private fun JSONObject.str(key: String): String? = nullable(key, JSONObject::getString)
    private fun JSONObject.long(key: String): Long? = nullable(key, JSONObject::getLong)
    private fun JSONObject.int(key: String): Int? = nullable(key, JSONObject::getInt)
    private fun JSONObject.double(key: String): Double? = nullable(key, JSONObject::getDouble)

    private fun JSONObject.id(): String = getString("id").also { require(it.isNotEmpty()) }

    /** The stamp must parse: merging compares stamps, so a broken one would win or lose at random. */
    private fun JSONObject.hlc(): String = getString("hlc").also { HlcStamp.parse(it) }

    private fun JSONObject.child() = Child(
        id = id(), name = str("name"), birthDate = str("birth_date"), sex = str("sex"),
        birthWeightG = int("birth_weight_g"), birthLengthMm = int("birth_length_mm"), color = int("color"),
        sortKey = getInt("sort_key"), archivedAt = long("archived_at"),
        createdAt = getLong("created_at"), updatedAt = getLong("updated_at"), deletedAt = long("deleted_at"), hlc = hlc(),
    )

    private fun JSONObject.event() = Event(
        id = id(), childId = getString("child_id"), kind = Kind.of(getString("kind")), subtype = str("subtype"),
        startAt = getLong("start_at"), endAt = long("end_at"), status = str("status")?.let(EventStatus::of),
        amountMl = double("amount_ml"), supplementPresetId = str("supplement_preset_id"),
        supplementName = str("supplement_name"), supplementAmount = double("supplement_amount"),
        supplementUnit = str("supplement_unit"), note = str("note"), tz = getString("tz"),
        createdAt = getLong("created_at"), updatedAt = getLong("updated_at"), deletedAt = long("deleted_at"), hlc = hlc(),
    ).also { require(it.endAt == null || it.endAt!! >= it.startAt) { "end before start" } }

    private fun JSONObject.segment() = BreastSegment(
        id = id(), eventId = getString("event_id"), side = Side.of(getString("side")),
        startAt = getLong("start_at"), endAt = long("end_at"),
        createdAt = getLong("created_at"), updatedAt = getLong("updated_at"), deletedAt = long("deleted_at"), hlc = hlc(),
    ).also { require(it.endAt == null || it.endAt!! >= it.startAt) { "end before start" } }

    private fun JSONObject.preset() = SupplementPreset(
        id = id(), childId = getString("child_id"), name = getString("name"), amount = double("amount"), unit = str("unit"),
        sortKey = getInt("sort_key"), schedule = MedSchedule.of(getString("schedule")),
        scheduleWeekday = int("schedule_weekday"), scheduleEveryDays = int("schedule_every_days"),
        scheduleStart = str("schedule_start"),
        createdAt = getLong("created_at"), updatedAt = getLong("updated_at"), deletedAt = long("deleted_at"), hlc = hlc(),
    )
}
