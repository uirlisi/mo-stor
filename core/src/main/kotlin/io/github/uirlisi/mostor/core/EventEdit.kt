package io.github.uirlisi.mostor.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One breast segment being edited. [id] is null for a segment added in this edit. */
data class SegmentDraft(val id: String?, val side: Side, val startAt: Long, val endAt: Long?)

/**
 * An editable copy of one event (SPEC §6.5 "Edit event"). For a breast feed the times live in
 * [segments]; the event's start and end are taken from them when it is saved (SPEC §4.4).
 * Editing never starts or stops a timer: [running] comes from the event being edited.
 */
data class EventDraft(
    /** Null for a record added after the fact. */
    val id: String?,
    val childId: String,
    val kind: Kind,
    val subtype: String? = null,
    val startAt: Long,
    /** Sleep, or an optional bottle end. Unused for breast feeds (see [segments]). */
    val endAt: Long? = null,
    val running: Boolean = false,
    /** Timed kinds that aren't running: the end is stored as unknown. */
    val endUnknown: Boolean = false,
    val amountMl: Double? = null,
    val supplementPresetId: String? = null,
    val supplementName: String? = null,
    val supplementAmount: Double? = null,
    val supplementUnit: String? = null,
    /** A med dose that was skipped, not given (SPEC §5.10). */
    val skipped: Boolean = false,
    val note: String? = null,
    val segments: List<SegmentDraft> = emptyList(),
) {
    /** Where the record starts: its first segment for a breast feed. */
    val start: Long get() = if (kind == Kind.BREAST) segments.firstOrNull()?.startAt ?: startAt else startAt

    /** Where it ends, or null while running or when unknown. */
    val end: Long?
        get() = when {
            kind.timed && (running || endUnknown) -> null
            kind == Kind.BREAST -> segments.lastOrNull()?.endAt
            else -> endAt
        }

    val isPaused: Boolean get() = kind == Kind.BREAST && running && segments.isNotEmpty() && segments.last().endAt != null
}

/** A time on the edit screen that can be nudged or picked. */
sealed interface TimeField {
    data object Start : TimeField
    data object End : TimeField
    data class SegmentStart(val index: Int) : TimeField
    data class SegmentEnd(val index: Int) : TimeField
}

/** Why a draft can't be saved yet. [segment] is the index of the segment concerned, if any. */
data class EditProblem(val type: Type, val segment: Int? = null) {
    enum class Type {
        NO_SEGMENTS,
        END_BEFORE_START,

        /** The segment starts before the previous one ends. */
        OVERLAP,

        /** An end is missing where one is needed. */
        NO_END,

        /** An open segment anywhere but last on a running (or end-unknown) feed. */
        OPEN_SEGMENT,
        IN_FUTURE,
        NO_AMOUNT,
        EMPTY_NOTE,
    }
}

/**
 * Editing a record (SPEC §6.5): building a draft, nudging its times within their neighbours,
 * validating it against SPEC §4.4, and turning it into the [Change] to write.
 */
object EventEdit {
    private const val MIN = 60_000L

    /** The draft for an existing event and its segments. */
    fun draftOf(e: Event, segments: List<BreastSegment> = emptyList()): EventDraft {
        val live = if (e.kind == Kind.BREAST) TimedEvent.of(e, segments).segments else emptyList()
        return EventDraft(
            id = e.id,
            childId = e.childId,
            kind = e.kind,
            subtype = e.subtype,
            startAt = e.startAt,
            endAt = e.endAt,
            running = e.status == EventStatus.RUNNING,
            endUnknown = e.status == EventStatus.END_UNKNOWN,
            amountMl = e.amountMl,
            supplementPresetId = e.supplementPresetId,
            supplementName = e.supplementName,
            supplementAmount = e.supplementAmount,
            supplementUnit = e.supplementUnit,
            skipped = e.status == EventStatus.SKIPPED,
            note = e.note,
            segments = live.map { SegmentDraft(it.id, it.side, it.startAt, it.endAt) },
        )
    }

    /**
     * A record added after the fact, ending at [now]: a 15-minute feed on [side], a one-hour sleep,
     * or a point record at [now]. The caller fills in amounts, subtypes and presets.
     */
    fun blank(kind: Kind, childId: String, now: Long, side: Side = Side.LEFT): EventDraft = when (kind) {
        Kind.BREAST -> EventDraft(
            id = null, childId = childId, kind = kind, startAt = now - 15 * MIN,
            segments = listOf(SegmentDraft(null, side, now - 15 * MIN, now)),
        )
        Kind.SLEEP -> EventDraft(id = null, childId = childId, kind = kind, startAt = now - 60 * MIN, endAt = now)
        else -> EventDraft(id = null, childId = childId, kind = kind, startAt = now)
    }

    // ---- Times ----

    fun value(d: EventDraft, f: TimeField): Long? = when (f) {
        TimeField.Start -> d.startAt
        TimeField.End -> if (d.kind == Kind.BREAST) d.segments.lastOrNull()?.endAt else d.endAt
        is TimeField.SegmentStart -> d.segments.getOrNull(f.index)?.startAt
        is TimeField.SegmentEnd -> d.segments.getOrNull(f.index)?.endAt
    }

    /**
     * The range a nudge keeps [f] in, so the common fix never makes a record invalid: a segment
     * stays between its neighbours, an end stays after its start, and nothing passes [now].
     */
    fun bounds(d: EventDraft, f: TimeField, now: Long): LongRange = when (f) {
        TimeField.Start -> Long.MIN_VALUE..(d.endAt ?: now)
        // A breast feed's end is its last segment's: it stays after that segment's start.
        TimeField.End -> if (d.kind == Kind.BREAST && d.segments.isNotEmpty()) bounds(d, TimeField.SegmentEnd(d.segments.lastIndex), now) else d.startAt..now
        is TimeField.SegmentStart -> {
            val prev = d.segments.getOrNull(f.index - 1)
            val seg = d.segments[f.index]
            (prev?.let { it.endAt ?: it.startAt } ?: Long.MIN_VALUE)..(seg.endAt ?: now)
        }
        is TimeField.SegmentEnd -> {
            val seg = d.segments[f.index]
            seg.startAt..(d.segments.getOrNull(f.index + 1)?.startAt ?: now)
        }
    }

    /** Moves [f] by [deltaMs], kept within [bounds]. Does nothing to a time that isn't set. */
    fun nudge(d: EventDraft, f: TimeField, deltaMs: Long, now: Long): EventDraft {
        val v = value(d, f) ?: return d
        val b = bounds(d, f, now)
        val moved = v + deltaMs
        return set(d, f, if (b.first <= b.last) moved.coerceIn(b) else moved)
    }

    /** Sets [f] to [at] as given (a picked time); [problems] reports anything it breaks. */
    fun set(d: EventDraft, f: TimeField, at: Long): EventDraft = when (f) {
        TimeField.Start -> d.copy(startAt = at)
        TimeField.End ->
            if (d.kind == Kind.BREAST) {
                if (d.segments.isEmpty()) d else set(d, TimeField.SegmentEnd(d.segments.lastIndex), at)
            } else {
                d.copy(endAt = at)
            }
        is TimeField.SegmentStart -> d.withSegment(f.index) { it.copy(startAt = at) }
        is TimeField.SegmentEnd -> d.withSegment(f.index) { it.copy(endAt = at) }
    }

    /**
     * Moves the whole record to [date], keeping its local clock times (a DST change keeps the
     * clock reading, not the elapsed time).
     */
    fun moveToDate(d: EventDraft, date: LocalDate, zone: ZoneId): EventDraft {
        val from = Instant.ofEpochMilli(d.start).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(from, date)
        if (days == 0L) return d
        fun shift(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).plusDays(days).toInstant().toEpochMilli()
        return d.copy(
            startAt = shift(d.startAt),
            endAt = d.endAt?.let(::shift),
            segments = d.segments.map { s -> s.copy(startAt = shift(s.startAt), endAt = s.endAt?.let(::shift)) },
        )
    }

    // ---- Segments ----

    fun setSide(d: EventDraft, index: Int, side: Side): EventDraft = d.withSegment(index) { it.copy(side = side) }

    /**
     * Whether a segment can be added: the last one must be closed, with room after it or long
     * enough to split.
     */
    fun canAddSegment(d: EventDraft, now: Long): Boolean {
        if (d.kind != Kind.BREAST || d.endUnknown) return false
        val last = d.segments.lastOrNull() ?: return true
        val end = last.endAt ?: return false
        return now - end >= ROOM_MS || end - last.startAt >= ROOM_MS
    }

    /**
     * Adds a segment on the other side. With at least 2 minutes before [now], it goes after the
     * last segment, starting where that ended and lasting up to 5 minutes. Without room (a feed
     * that just ended), the last segment is split in half, the second half on the other side: the
     * usual fix for "it was both sides". On a paused feed it stays paused.
     */
    fun addSegment(d: EventDraft, now: Long): EventDraft {
        if (!canAddSegment(d, now)) return d
        val last = d.segments.lastOrNull()
            ?: return d.copy(segments = listOf(SegmentDraft(null, Side.LEFT, now - 5 * MIN, now)))
        val end = last.endAt!!
        if (now - end >= ROOM_MS) {
            return d.copy(segments = d.segments + SegmentDraft(null, last.side.other, end, minOf(end + 5 * MIN, now)))
        }
        // Split at the middle, on a whole minute from the segment's start.
        val mid = last.startAt + ((end - last.startAt) / 2 / MIN).coerceAtLeast(1) * MIN
        val first = last.copy(endAt = mid)
        return d.copy(segments = d.segments.dropLast(1) + first + SegmentDraft(null, last.side.other, mid, end))
    }

    /** The room a new segment needs (after the last one, or inside it to split it). */
    private const val ROOM_MS = 2 * MIN

    /** Removes a segment; a feed keeps at least one (deleting the feed removes it). */
    fun removeSegment(d: EventDraft, index: Int): EventDraft {
        if (d.segments.size <= 1 || index !in d.segments.indices) return d
        val rest = d.segments.filterIndexed { i, _ -> i != index }
        // A feed whose end is unknown keeps its last segment open: removing the open one reopens the one
        // before, so the end stays unknown instead of quietly becoming that segment's end.
        val reopened = if (d.endUnknown && index == d.segments.lastIndex) rest.dropLast(1) + rest.last().copy(endAt = null) else rest
        return d.copy(segments = reopened)
    }

    /**
     * Marks the end unknown (it is stored as unknown, never guessed), or sets it to [end] again.
     * Not for a running timer: stopping it is done with Stop.
     */
    fun setEndUnknown(d: EventDraft, unknown: Boolean, end: Long? = null): EventDraft {
        if (!d.kind.timed || d.running) return d
        if (unknown) {
            return if (d.kind == Kind.BREAST) d.copy(endUnknown = true).withSegment(d.segments.lastIndex) { it.copy(endAt = null) }
            else d.copy(endUnknown = true, endAt = null)
        }
        requireNotNull(end) { "an end is needed" }
        return if (d.kind == Kind.BREAST) d.copy(endUnknown = false).withSegment(d.segments.lastIndex) { it.copy(endAt = end) }
        else d.copy(endUnknown = false, endAt = end)
    }

    // ---- Validation ----

    /** What stops [d] from being saved (empty = it can be saved). SPEC §4.4. */
    fun problems(d: EventDraft, now: Long): List<EditProblem> {
        val out = mutableListOf<EditProblem>()
        fun add(t: EditProblem.Type, seg: Int? = null) {
            out += EditProblem(t, seg)
        }
        when (d.kind) {
            Kind.BREAST -> {
                if (d.segments.isEmpty()) add(EditProblem.Type.NO_SEGMENTS)
                d.segments.forEachIndexed { i, s ->
                    val isLast = i == d.segments.lastIndex
                    val end = s.endAt
                    if (end == null) {
                        if (!isLast) add(EditProblem.Type.OPEN_SEGMENT, i)
                        else if (!d.running && !d.endUnknown) add(EditProblem.Type.NO_END, i)
                    } else if (end < s.startAt) {
                        add(EditProblem.Type.END_BEFORE_START, i)
                    }
                    if (i > 0) {
                        val prev = d.segments[i - 1]
                        if (s.startAt < (prev.endAt ?: Long.MAX_VALUE)) add(EditProblem.Type.OVERLAP, i)
                    }
                    if (s.startAt > now || (end != null && end > now)) add(EditProblem.Type.IN_FUTURE, i)
                }
            }
            Kind.SLEEP -> {
                val end = d.endAt
                when {
                    d.running || d.endUnknown -> Unit
                    end == null -> add(EditProblem.Type.NO_END)
                    end < d.startAt -> add(EditProblem.Type.END_BEFORE_START)
                }
                if (d.startAt > now || (end != null && end > now)) add(EditProblem.Type.IN_FUTURE)
            }
            else -> {
                val end = d.endAt
                if (end != null && end < d.startAt) add(EditProblem.Type.END_BEFORE_START)
                if (d.startAt > now || (end != null && end > now)) add(EditProblem.Type.IN_FUTURE)
                if (d.kind in AMOUNT_KINDS && (d.amountMl == null || d.amountMl <= 0.0)) add(EditProblem.Type.NO_AMOUNT)
                if (d.kind == Kind.NOTE && d.note.isNullOrBlank()) add(EditProblem.Type.EMPTY_NOTE)
            }
        }
        return out
    }

    /**
     * Other records of the same child and kind whose time overlaps [d]'s. Overlaps are allowed (the
     * log isn't policed, SPEC §5.4); the edit screen only highlights them. Point records overlap when
     * they are at the same minute.
     */
    fun overlaps(d: EventDraft, others: List<Event>, now: Long): List<Event> {
        val from = d.start
        val to = d.end ?: if (d.running) now else from
        return others.filter { o ->
            if (o.id == d.id || o.deletedAt != null || o.childId != d.childId || o.kind != d.kind) return@filter false
            val oTo = o.endAt ?: if (o.status == EventStatus.RUNNING) now else o.startAt
            val aMoment = to == from
            val bMoment = oTo == o.startAt
            when {
                aMoment && bMoment -> kotlin.math.abs(from - o.startAt) < MIN
                aMoment -> from >= o.startAt && from < oTo
                bMoment -> o.startAt >= from && o.startAt < to
                else -> from < oTo && o.startAt < to
            }
        }
    }

    // ---- Saving ----

    /**
     * The change that turns [original] (null for a new record) into [d]: only the rows that differ,
     * with new segments created and removed ones soft-deleted. Empty when nothing changed. The
     * caller checks [problems] first.
     */
    fun toChange(original: Event?, originalSegments: List<BreastSegment>, d: EventDraft, cx: ChangeContext): Change {
        val status = when {
            d.kind == Kind.SUPPLEMENT && d.skipped -> EventStatus.SKIPPED
            !d.kind.timed -> null
            d.running -> EventStatus.RUNNING
            d.endUnknown -> EventStatus.END_UNKNOWN
            else -> EventStatus.ENDED
        }
        val note = d.note?.trim()?.takeIf { it.isNotEmpty() }
        val base = original ?: Event(
            id = cx.newId(), childId = d.childId, kind = d.kind, startAt = d.start, tz = cx.tz,
            createdAt = cx.now, updatedAt = cx.now, hlc = "",
        )
        val wanted = base.copy(
            childId = d.childId,
            subtype = d.subtype,
            startAt = d.start,
            endAt = d.end,
            status = status,
            amountMl = d.amountMl,
            supplementPresetId = d.supplementPresetId,
            supplementName = d.supplementName,
            supplementAmount = d.supplementAmount,
            supplementUnit = d.supplementUnit,
            note = note,
        )
        val events = when {
            original == null -> listOf(RowChange(null, wanted.copy(hlc = cx.nextHlc())))
            wanted != original -> listOf(RowChange(original, wanted.touched(cx)))
            else -> emptyList()
        }

        val segments = mutableListOf<RowChange<BreastSegment>>()
        if (d.kind == Kind.BREAST) {
            val live = originalSegments.filter { it.eventId == base.id && it.deletedAt == null }.associateBy { it.id }
            val kept = mutableSetOf<String>()
            for (s in d.segments) {
                val old = s.id?.let(live::get)
                if (old == null) {
                    segments += RowChange(
                        null,
                        BreastSegment(
                            id = cx.newId(), eventId = base.id, side = s.side, startAt = s.startAt, endAt = s.endAt,
                            createdAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc(),
                        ),
                    )
                } else {
                    kept += old.id
                    val updated = old.copy(side = s.side, startAt = s.startAt, endAt = s.endAt)
                    if (updated != old) segments += RowChange(old, updated.touched(cx))
                }
            }
            for (old in live.values) {
                if (old.id !in kept) segments += RowChange(old, old.copy(deletedAt = cx.now).touched(cx))
            }
        }
        val change = Change(events, segments)
        return if (original != null) change.touchingEvent(original, cx) else change
    }

    /** Deletes a record (soft delete of the event and its segments; SPEC §4.1). */
    fun delete(e: Event, segments: List<BreastSegment>, cx: ChangeContext): Change {
        if (e.deletedAt != null) return Change()
        val segs = segments.filter { it.eventId == e.id && it.deletedAt == null }
            .map { RowChange(it, it.copy(deletedAt = cx.now).touched(cx)) }
        return Change(listOf(RowChange(e, e.copy(deletedAt = cx.now).touched(cx))), segs)
    }

    private val AMOUNT_KINDS = setOf(Kind.BOTTLE, Kind.PUMP)

    private fun EventDraft.withSegment(index: Int, f: (SegmentDraft) -> SegmentDraft): EventDraft =
        if (index !in segments.indices) this else copy(segments = segments.mapIndexed { i, s -> if (i == index) f(s) else s })
}
