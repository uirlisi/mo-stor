package io.github.uirlisi.mostor.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reads the "daily report: all activity" PDF of the commercial app "Baby Tracker – Newborn Log"
 * (nighp.com), from the PDF's text, so a family switching to Mo Stór keeps its history.
 *
 * Known lines (the report's English, 12-hour format):
 * - "9:50 AM Feeding Nursing 15 min Start side R" (also "1 hr 5 min")
 * - "7:55 PM Diaper change Mixed" (Wet, Dirty, Mixed, Dry)
 * Every other timed line is listed as skipped, so nothing is dropped silently. Each day ends with
 * a summary ("Nursing 1 hr 46 min (L 57m R49m) 8 times", "Diaper change 2 times, …"), which is
 * used to check what was read.
 */
object BabyTracker {
    sealed interface Record {
        val at: LocalDateTime
    }

    /** A breast feed. The report gives only the start side and the total time. */
    data class Nursing(override val at: LocalDateTime, val minutes: Int, val startSide: Side) : Record

    data class DiaperChange(override val at: LocalDateTime, val type: Diaper) : Record

    sealed interface Issue {
        val date: LocalDate
    }

    /** The day's summary counts [expected] entries of [kind], but [found] were read. */
    data class CountMismatch(override val date: LocalDate, val kind: Kind, val expected: Int, val found: Int) : Issue

    /**
     * The day's left/right totals don't match the start sides: some feeds switched side, and the
     * report doesn't say when. Those feeds are imported as one side.
     */
    data class SidesUnknown(override val date: LocalDate) : Issue

    data class Report(
        val records: List<Record>,
        /** Timed entries that aren't understood, as they appear in the report (e.g. sleep, bottle). */
        val skipped: List<String>,
        val issues: List<Issue>,
    ) {
        val firstDay: LocalDate? get() = records.minOfOrNull { it.at.toLocalDate() }
        val lastDay: LocalDate? get() = records.maxOfOrNull { it.at.toLocalDate() }
    }

    private val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val marker = Regex(
        "\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*, (${months.joinToString("|")}) (\\d{1,2}), (\\d{4})" +
            "|\\b(\\d{1,2}):(\\d{2}) ?([AP]M)\\b",
    )
    private const val DURATION = "(?:(\\d+) hrs? ?)?(?:(\\d+) min)?"
    private val nursing = Regex("^Feeding Nursing $DURATION ?Start side ([LR])\\b")
    private val diaper = Regex("^Diaper change (Wet|Dirty|Mixed|Dry)\\b")
    private val nursingSummary = Regex("Nursing $DURATION ?\\(L ?([0-9hm ]*?) ?R ?([0-9hm ]*?)\\) ?(\\d+) times?")
    private val diaperSummary = Regex("Diaper change (\\d+) times?")

    /** Where a skipped entry's own text ends: the day summary or the next page's header. */
    private val tail = Regex(" (?:Nursing \\d|Diaper change \\d+ times|Slept \\d|Pumping \\d|\\S+ daily report:)")

    fun parse(text: String): Report {
        val clean = text.replace(Regex("[\\s\\u00A0]+"), " ")
        val marks = marker.findAll(clean).toList()
        val records = mutableListOf<Record>()
        val skipped = mutableListOf<String>()
        val days = linkedMapOf<LocalDate, String>()
        var date: LocalDate? = null
        var dayStart = 0

        fun closeDay(end: Int) {
            date?.let { days[it] = days.getOrDefault(it, "") + clean.substring(dayStart, end) }
        }

        marks.forEachIndexed { i, m ->
            val end = marks.getOrNull(i + 1)?.range?.first ?: clean.length
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                closeDay(m.range.first)
                date = LocalDate.of(g[3].toInt(), months.indexOf(g[1]) + 1, g[2].toInt())
                dayStart = m.range.last + 1
                return@forEachIndexed
            }
            val body = clean.substring(m.range.last + 1, end).trim()
            val day = date
            val hour = g[4].toInt() % 12 + if (g[6] == "PM") 12 else 0
            val record = day?.let { read(LocalDateTime.of(it, LocalTime.of(hour, g[5].toInt())), body) }
            if (record != null) {
                records += record
            } else {
                skipped += (m.value + " " + body.substring(0, tail.find(" $body")?.range?.first ?: body.length)).trim()
            }
        }
        closeDay(clean.length)
        return Report(records, skipped, check(records, days))
    }

    private fun read(at: LocalDateTime, body: String): Record? {
        nursing.find(body)?.let { m ->
            val (h, min, side) = m.destructured
            if (h.isEmpty() && min.isEmpty()) return null
            return Nursing(at, (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0), if (side == "L") Side.LEFT else Side.RIGHT)
        }
        diaper.find(body)?.let { m ->
            val type = when (m.groupValues[1]) {
                "Wet" -> Diaper.WET
                "Dirty" -> Diaper.POOP
                "Mixed" -> Diaper.MIXED
                else -> Diaper.DRY
            }
            return DiaperChange(at, type)
        }
        return null
    }

    /** Compares what was read with each day's summary. */
    private fun check(records: List<Record>, days: Map<LocalDate, String>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((day, text) in days) {
            val feeds = records.filterIsInstance<Nursing>().filter { it.at.toLocalDate() == day }
            val diapers = records.filterIsInstance<DiaperChange>().count { it.at.toLocalDate() == day }
            nursingSummary.find(text)?.let { m ->
                val expected = m.groupValues[5].toInt()
                if (expected != feeds.size) issues += CountMismatch(day, Kind.BREAST, expected, feeds.size)
                val left = feeds.filter { it.startSide == Side.LEFT }.sumOf { it.minutes }
                val right = feeds.filter { it.startSide == Side.RIGHT }.sumOf { it.minutes }
                if (minutes(m.groupValues[3]) != left || minutes(m.groupValues[4]) != right) issues += SidesUnknown(day)
            }
            diaperSummary.find(text)?.let { m ->
                val expected = m.groupValues[1].toInt()
                if (expected != diapers) issues += CountMismatch(day, Kind.DIAPER, expected, diapers)
            }
        }
        return issues
    }

    /** "1h9m", "57m", "2h" → minutes. */
    private fun minutes(s: String): Int? {
        val m = Regex("^(?:(\\d+)h)? ?(?:(\\d+)m)?$").find(s.trim()) ?: return null
        val (h, min) = m.destructured
        if (h.isEmpty() && min.isEmpty()) return null
        return (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
    }

    /** The outcome of [plan]: the rows to write and what was left out. */
    data class Plan(
        val change: Change,
        /** Imported before and still there. */
        val alreadyImported: Int,
        /** A live event of the same kind and subtype starts within 5 min (also recorded in Mo Stór). */
        val alreadyRecorded: Int,
        /** Imported before, then deleted (or the import was undone): left deleted. */
        val deletedSince: Int,
        /** Imported before, then deleted, and brought back because it was asked for. */
        val broughtBack: Int,
    ) {
        /** Events written, brought-back ones included. */
        val newEvents: Int get() = change.events.size
    }

    /** One record as rows, with the ids [plan] uses. */
    private class Rows(val record: Record, val event: Event, val segment: BreastSegment?)

    private fun rows(report: Report, childId: String, zone: ZoneId, cx: ChangeContext): List<Rows> {
        val seen = mutableMapOf<String, Int>()
        return report.records.sortedBy { it.at }.map { r ->
            val start = r.at.atZone(zone).toInstant().toEpochMilli()
            val kind = if (r is Nursing) Kind.BREAST else Kind.DIAPER
            val subtype = (r as? DiaperChange)?.type?.code
            val key = "babytracker|${kind.code}|${subtype.orEmpty()}|$start"
            val n = seen.merge(key, 1, Int::plus)!!
            val id = stableId(start, "$key|$n")
            val end = (r as? Nursing)?.let { start + it.minutes * 60_000L }
            val event = Event(
                id = id, childId = childId, kind = kind, subtype = subtype, startAt = start, endAt = end,
                status = if (kind.timed) EventStatus.ENDED else null, tz = zone.id,
                createdAt = cx.now, updatedAt = cx.now, hlc = "",
            )
            val segment = (r as? Nursing)?.let {
                BreastSegment(
                    id = stableId(start, "$key|$n|segment"), eventId = id, side = it.startSide, startAt = start, endAt = end,
                    createdAt = cx.now, updatedAt = cx.now, hlc = "",
                )
            }
            Rows(r, event, segment)
        }
    }

    /** The event and segment ids [plan] would use, to look up which are stored already. */
    fun ids(report: Report, zone: ZoneId): Pair<List<String>, List<String>> {
        val rows = rows(report, "", zone, object : ChangeContext {
            override val now = 0L
            override val tz = zone.id
            override fun newId() = ""
            override fun nextHlc() = ""
        })
        return rows.map { it.event.id } to rows.mapNotNull { it.segment?.id }
    }

    /**
     * Turns [report] into events for [childId], with local times read in [zone].
     *
     * Ids are derived from the record (kind, subtype, start), so importing the same report twice,
     * or an overlapping later report, or the same report on another phone, never makes copies.
     * [stored] and [storedSegments] are the rows already stored under those ids (deleted ones
     * included); [nearby] the live events of the child around the report's dates. A record that
     * was imported and then deleted stays deleted unless [bringBack] is set.
     */
    fun plan(
        report: Report,
        childId: String,
        zone: ZoneId,
        stored: Map<String, Event>,
        storedSegments: Map<String, BreastSegment>,
        nearby: List<Event>,
        cx: ChangeContext,
        bringBack: Boolean = false,
    ): Plan {
        val events = mutableListOf<RowChange<Event>>()
        val segments = mutableListOf<RowChange<BreastSegment>>()
        var alreadyImported = 0
        var alreadyRecorded = 0
        var deletedSince = 0
        var broughtBack = 0
        for (row in rows(report, childId, zone, cx)) {
            val e = row.event
            val before = stored[e.id]
            when {
                before != null && before.isLive -> alreadyImported++
                nearby.any { it.isLive && it.kind == e.kind && it.subtype == e.subtype && kotlin.math.abs(it.startAt - e.startAt) <= 5 * 60_000L } ->
                    alreadyRecorded++
                before != null && !bringBack -> deletedSince++
                else -> {
                    if (before != null) broughtBack++
                    events += RowChange(before, e.copy(createdAt = before?.createdAt ?: e.createdAt, hlc = cx.nextHlc()))
                    row.segment?.let { seg ->
                        val segBefore = storedSegments[seg.id]
                        segments += RowChange(segBefore, seg.copy(createdAt = segBefore?.createdAt ?: seg.createdAt, hlc = cx.nextHlc()))
                    }
                }
            }
        }
        return Plan(Change(events, segments), alreadyImported, alreadyRecorded, deletedSince, broughtBack)
    }

    /** A UUIDv7 whose timestamp is [ms] and whose other bits come from a hash of [key]. */
    fun stableId(ms: Long, key: String): String =
        Ids.uuidV7(ms, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
}
