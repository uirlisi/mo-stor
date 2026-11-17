package io.github.uirlisi.mostor.data

import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.RowChange
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.core.SupplementPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a user action did, for the confirmation strip ("Wet diaper · 14:32 [Undo]"). Stored by
 * name, so new verbs can be added without breaking older undo entries.
 */
enum class Verb {
    RECORD, START, SWITCH, STOP, CANCEL, SET_END, IMPORT, PAUSE, RESUME,

    /** Edit screen (Phase 2). ADD is a record added after the fact. */
    EDIT, ADD, DELETE,
    CHILD_ADD, CHILD_EDIT, CHILD_ARCHIVE, CHILD_UNARCHIVE, CHILD_DELETE,
    PRESET_ADD, PRESET_EDIT, PRESET_DELETE, PRESET_MOVE,

    /** A med dose turned into a skip (Phase 3b, SPEC §5.10). */
    SKIP,
}

data class ActionInfo(
    val verb: Verb,
    /** The event's kind; null for child and preset actions. */
    val kind: Kind?,
    /** The event (or, for child and preset actions, the child or preset) the action concerns. */
    val eventId: String,
    /** When it happened: the record time, the start, the switch or the end. */
    val at: Long,
    val subtype: String? = null,
    val side: Side? = null,
    val amountMl: Double? = null,
    val name: String? = null,
    /** How many events an import added. */
    val count: Int? = null,
)

data class UndoPayload(val info: ActionInfo, val change: Change)

/** Stores an undo entry as JSON (the platform's org.json; local table, never synced). */
object UndoCodec {
    private const val VERSION = 1

    fun encode(p: UndoPayload): String = JSONObject()
        .put("v", VERSION)
        .put("info", p.info.toJson())
        .put("events", JSONArray(p.change.events.map { rowJson(it.before?.toJson(), it.after.toJson()) }))
        .put("segments", JSONArray(p.change.segments.map { rowJson(it.before?.toJson(), it.after.toJson()) }))
        .put("children", JSONArray(p.change.children.map { rowJson(it.before?.toJson(), it.after.toJson()) }))
        .put("presets", JSONArray(p.change.presets.map { rowJson(it.before?.toJson(), it.after.toJson()) }))
        .toString()

    fun decode(json: String): UndoPayload {
        val o = JSONObject(json)
        val events = o.getJSONArray("events").objects().map { r ->
            RowChange(r.optObject("before")?.toEvent(), r.getJSONObject("after").toEvent())
        }
        val segments = o.getJSONArray("segments").objects().map { r ->
            RowChange(r.optObject("before")?.toSegment(), r.getJSONObject("after").toSegment())
        }
        // Entries written before Phase 2 have no "children" or "presets".
        val children = o.optJSONArray("children")?.objects().orEmpty().map { r ->
            RowChange(r.optObject("before")?.toChild(), r.getJSONObject("after").toChild())
        }
        val presets = o.optJSONArray("presets")?.objects().orEmpty().map { r ->
            RowChange(r.optObject("before")?.toPreset(), r.getJSONObject("after").toPreset())
        }
        return UndoPayload(o.getJSONObject("info").toInfo(), Change(events, segments, children, presets))
    }

    private fun rowJson(before: JSONObject?, after: JSONObject) =
        JSONObject().put("before", before ?: JSONObject.NULL).put("after", after)

    private fun ActionInfo.toJson() = JSONObject()
        .put("verb", verb.name)
        .putOpt("kind", kind?.code)
        .put("event_id", eventId)
        .put("at", at)
        .putOpt("subtype", subtype)
        .putOpt("side", side?.code)
        .putOpt("amount_ml", amountMl)
        .putOpt("name", name)
        .putOpt("count", count)

    private fun JSONObject.toInfo() = ActionInfo(
        verb = Verb.valueOf(getString("verb")),
        kind = optStr("kind")?.let(Kind::of),
        eventId = getString("event_id"),
        at = getLong("at"),
        subtype = optStr("subtype"),
        side = optStr("side")?.let(Side::of),
        amountMl = optDbl("amount_ml"),
        name = optStr("name"),
        count = if (has("count")) getInt("count") else null,
    )

    private fun Event.toJson() = JSONObject()
        .put("id", id)
        .put("child_id", childId)
        .put("kind", kind.code)
        .putOpt("subtype", subtype)
        .put("start_at", startAt)
        .putOpt("end_at", endAt)
        .putOpt("status", status?.code)
        .putOpt("amount_ml", amountMl)
        .putOpt("supplement_preset_id", supplementPresetId)
        .putOpt("supplement_name", supplementName)
        .putOpt("supplement_amount", supplementAmount)
        .putOpt("supplement_unit", supplementUnit)
        .putOpt("note", note)
        .put("tz", tz)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putOpt("deleted_at", deletedAt)
        .put("hlc", hlc)

    private fun JSONObject.toEvent() = Event(
        id = getString("id"),
        childId = getString("child_id"),
        kind = Kind.of(getString("kind")),
        subtype = optStr("subtype"),
        startAt = getLong("start_at"),
        endAt = optLng("end_at"),
        status = optStr("status")?.let(EventStatus::of),
        amountMl = optDbl("amount_ml"),
        supplementPresetId = optStr("supplement_preset_id"),
        supplementName = optStr("supplement_name"),
        supplementAmount = optDbl("supplement_amount"),
        supplementUnit = optStr("supplement_unit"),
        note = optStr("note"),
        tz = getString("tz"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = optLng("deleted_at"),
        hlc = getString("hlc"),
    )

    private fun BreastSegment.toJson() = JSONObject()
        .put("id", id)
        .put("event_id", eventId)
        .put("side", side.code)
        .put("start_at", startAt)
        .putOpt("end_at", endAt)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putOpt("deleted_at", deletedAt)
        .put("hlc", hlc)

    private fun JSONObject.toSegment() = BreastSegment(
        id = getString("id"),
        eventId = getString("event_id"),
        side = Side.of(getString("side")),
        startAt = getLong("start_at"),
        endAt = optLng("end_at"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = optLng("deleted_at"),
        hlc = getString("hlc"),
    )

    private fun Child.toJson() = JSONObject()
        .put("id", id)
        .putOpt("name", name)
        .putOpt("birth_date", birthDate)
        .putOpt("sex", sex)
        .putOpt("birth_weight_g", birthWeightG)
        .putOpt("birth_length_mm", birthLengthMm)
        .putOpt("color", color)
        .put("sort_key", sortKey)
        .putOpt("archived_at", archivedAt)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putOpt("deleted_at", deletedAt)
        .put("hlc", hlc)

    private fun JSONObject.toChild() = Child(
        id = getString("id"),
        name = optStr("name"),
        birthDate = optStr("birth_date"),
        sex = optStr("sex"),
        birthWeightG = optLng("birth_weight_g")?.toInt(),
        birthLengthMm = optLng("birth_length_mm")?.toInt(),
        color = optLng("color")?.toInt(),
        sortKey = getInt("sort_key"),
        archivedAt = optLng("archived_at"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = optLng("deleted_at"),
        hlc = getString("hlc"),
    )

    private fun SupplementPreset.toJson() = JSONObject()
        .put("id", id)
        .put("child_id", childId)
        .put("name", name)
        .putOpt("amount", amount)
        .putOpt("unit", unit)
        .put("sort_key", sortKey)
        .put("schedule", schedule.code)
        .putOpt("schedule_weekday", scheduleWeekday)
        .putOpt("schedule_every_days", scheduleEveryDays)
        .putOpt("schedule_start", scheduleStart)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .putOpt("deleted_at", deletedAt)
        .put("hlc", hlc)

    // Entries are cleared by MIGRATION_1_2, so every preset here has a child.
    private fun JSONObject.toPreset() = SupplementPreset(
        id = getString("id"),
        childId = getString("child_id"),
        name = getString("name"),
        amount = optDbl("amount"),
        unit = optStr("unit"),
        sortKey = getInt("sort_key"),
        schedule = MedSchedule.of(optStr("schedule")),
        scheduleWeekday = optIntOrNull("schedule_weekday"),
        scheduleEveryDays = optIntOrNull("schedule_every_days"),
        scheduleStart = optStr("schedule_start"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = optLng("deleted_at"),
        hlc = getString("hlc"),
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)
    private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.optLng(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.optDbl(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
}
