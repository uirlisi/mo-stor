package io.github.uirlisi.mostor.edit

import androidx.compose.runtime.saveable.Saver
import io.github.uirlisi.mostor.core.EventDraft
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.SegmentDraft
import io.github.uirlisi.mostor.core.Side
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps an edit in progress through a recreated screen (rotation, a dark-mode switch, a font change,
 * Android ending the app in the background): the draft as JSON text, which a saved state can hold.
 */
internal object DraftSaver {
    fun encode(d: EventDraft): String = JSONObject()
        .put("id", d.id ?: JSONObject.NULL)
        .put("child", d.childId)
        .put("kind", d.kind.code)
        .put("subtype", d.subtype ?: JSONObject.NULL)
        .put("start", d.startAt)
        .put("end", d.endAt ?: JSONObject.NULL)
        .put("running", d.running)
        .put("endUnknown", d.endUnknown)
        .put("ml", d.amountMl ?: JSONObject.NULL)
        .put("preset", d.supplementPresetId ?: JSONObject.NULL)
        .put("medName", d.supplementName ?: JSONObject.NULL)
        .put("medAmount", d.supplementAmount ?: JSONObject.NULL)
        .put("medUnit", d.supplementUnit ?: JSONObject.NULL)
        .put("skipped", d.skipped)
        .put("note", d.note ?: JSONObject.NULL)
        .put(
            "segments",
            JSONArray().apply {
                for (s in d.segments) {
                    put(JSONObject().put("id", s.id ?: JSONObject.NULL).put("side", s.side.code).put("start", s.startAt).put("end", s.endAt ?: JSONObject.NULL))
                }
            },
        )
        .toString()

    /** The draft from [encode], or null if [text] isn't one (then the screen loads the record again). */
    fun decode(text: String): EventDraft? = runCatching {
        val o = JSONObject(text)
        val segs = o.getJSONArray("segments")
        EventDraft(
            id = o.str("id"),
            childId = o.getString("child"),
            kind = Kind.of(o.getString("kind")),
            subtype = o.str("subtype"),
            startAt = o.getLong("start"),
            endAt = o.long("end"),
            running = o.getBoolean("running"),
            endUnknown = o.getBoolean("endUnknown"),
            amountMl = o.double("ml"),
            supplementPresetId = o.str("preset"),
            supplementName = o.str("medName"),
            supplementAmount = o.double("medAmount"),
            supplementUnit = o.str("medUnit"),
            skipped = o.getBoolean("skipped"),
            note = o.str("note"),
            segments = (0 until segs.length()).map { i ->
                val s = segs.getJSONObject(i)
                SegmentDraft(s.str("id"), Side.of(s.getString("side")), s.getLong("start"), s.long("end"))
            },
        )
    }.getOrNull()

    /** For `rememberSaveable`: an edit's draft, or none yet. */
    val saver: Saver<EventDraft?, String> = Saver(save = { it?.let(::encode) ?: "" }, restore = { if (it.isEmpty()) null else decode(it) })

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
    private fun JSONObject.long(k: String): Long? = if (isNull(k)) null else getLong(k)
    private fun JSONObject.double(k: String): Double? = if (isNull(k)) null else getDouble(k)
}
