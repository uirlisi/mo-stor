package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The CSV export (SPEC §8): one row per record, oldest first, for a spreadsheet. Breast segments
 * are flattened into the row ("L 09:16-09:30; R 09:31-09:40") with per-side minutes.
 *
 * Times are local to the zone each record was made in (its `tz`), which the row names, so a record
 * reads as it did on the phone. Numbers use a dot and no grouping, whatever the phone's language.
 * Deleted records, and the records of deleted children, are left out; archived children's stay.
 * The text starts with a byte order mark so spreadsheet apps read it as UTF-8 (fadas, "µg").
 */
object CsvExport {
    val HEADER = listOf(
        "child", "kind", "subtype", "status", "start", "end", "time_zone", "duration_min",
        "left_min", "right_min", "sides", "amount_ml", "med", "med_amount", "med_unit", "note", "id",
    )

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * [childName] names a child for the "child" column (the app passes "Baby" for one without a
     * name). Durations are left empty for running records and those whose end is unknown.
     */
    fun write(
        children: List<Child>,
        events: List<Event>,
        segments: List<BreastSegment>,
        childName: (Child) -> String,
    ): String {
        val liveChildren = children.filter { it.deletedAt == null }.associateBy { it.id }
        val segmentsByEvent = segments.filter { it.deletedAt == null }.groupBy { it.eventId }
        val rows = events
            .filter { it.deletedAt == null && it.childId in liveChildren }
            .sortedWith(compareBy({ it.startAt }, { it.createdAt }, { it.id }))

        val out = StringBuilder("﻿")
        line(out, HEADER)
        for (e in rows) {
            val zone = zoneOf(e.tz)
            val segs = if (e.kind == Kind.BREAST) segmentsByEvent[e.id].orEmpty().sortedWith(compareBy({ it.startAt }, { it.createdAt })) else emptyList()
            val ended = e.status == EventStatus.ENDED
            val duration = if (e.kind.timed && ended) Durations.of(TimedEvent.of(e, segs), e.endAt ?: e.startAt) else null
            val sides = if (e.kind == Kind.BREAST && ended) Durations.breast(segs, e.endAt ?: e.startAt) else null
            line(
                out,
                listOf(
                    text(childName(liveChildren.getValue(e.childId))),
                    e.kind.code,
                    e.subtype.orEmpty(),
                    e.status?.code.orEmpty(),
                    DATE_TIME.format(Instant.ofEpochMilli(e.startAt).atZone(zone)),
                    e.endAt?.let { DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty(),
                    e.tz,
                    duration?.let(::minutes).orEmpty(),
                    sides?.let { minutes(it.leftMs) }.orEmpty(),
                    sides?.let { minutes(it.rightMs) }.orEmpty(),
                    segs.joinToString("; ") { s ->
                        val from = TIME.format(Instant.ofEpochMilli(s.startAt).atZone(zone))
                        val to = s.endAt?.let { TIME.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()
                        "${s.side.code} $from-$to"
                    },
                    e.amountMl?.let(::number).orEmpty(),
                    text(e.supplementName.orEmpty()),
                    e.supplementAmount?.let(::number).orEmpty(),
                    text(e.supplementUnit.orEmpty()),
                    text(e.note.orEmpty()),
                    e.id,
                ),
            )
        }
        return out.toString()
    }

    private fun zoneOf(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrDefault(ZoneId.of("UTC"))

    private fun minutes(ms: Long): String = Math.round(ms / 60_000.0).toString()

    /** Up to two decimals, a dot, no trailing zeros: 120, 88.72, 0.5. */
    private fun number(v: Double): String =
        java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Free text. A cell starting with = + - @ would be read as a formula by a spreadsheet app, so it
     * gets a leading apostrophe (the usual guard): the cell then shows the apostrophe, but nothing
     * in it runs.
     */
    private fun text(s: String): String = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s

    /** One CSV line (RFC 4180): fields with a comma, quote or line break are quoted; CRLF ends it. */
    private fun line(out: StringBuilder, fields: List<String>) {
        fields.forEachIndexed { i, f ->
            if (i > 0) out.append(',')
            if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                out.append('"').append(f.replace("\"", "\"\"")).append('"')
            } else {
                out.append(f)
            }
        }
        out.append("\r\n")
    }
}
