package io.github.uirlisi.mostor.core

/**
 * Timer operations (SPEC §5.4, §5.5). Each returns the [Change] to write; an empty change means
 * there was nothing to do. Callers pass times already offset ("ended 10 min ago" = now − 10 min);
 * these functions clamp them so the invariants in SPEC §4.4 hold.
 */
object Timers {
    /** Starts a breast feed (with its first segment on [side]) or a sleep at [at]. */
    fun start(kind: Kind, childId: String, at: Long, cx: ChangeContext, side: Side? = null): Change {
        require(kind.timed) { "$kind is not timed" }
        require((kind == Kind.BREAST) == (side != null)) { "a side is required for breast feeds only" }
        val event = Event(
            id = cx.newId(),
            childId = childId,
            kind = kind,
            startAt = at,
            status = EventStatus.RUNNING,
            tz = cx.tz,
            createdAt = cx.now,
            updatedAt = cx.now,
            hlc = cx.nextHlc(),
        )
        val segments = if (side != null) {
            listOf(RowChange(null, newSegment(event.id, side, at, cx)))
        } else {
            emptyList()
        }
        return Change(listOf(RowChange(null, event)), segments)
    }

    /** The earliest allowed end: the event's start, or the start of its last segment. */
    fun earliestEnd(t: TimedEvent): Long = maxOf(t.event.startAt, t.segments.lastOrNull()?.startAt ?: Long.MIN_VALUE)

    /**
     * Ends a running (or end-unknown) timer at [at], clamped so it is never before the start or
     * the current segment's start. Closes the open segment at the same time. A paused feed ends
     * at its pause at the latest; an earlier [at] shortens its last segment.
     */
    fun stop(t: TimedEvent, at: Long, cx: ChangeContext): Change {
        val e = t.event
        if (e.deletedAt != null || e.status == EventStatus.ENDED) return Change()
        val pausedAt = t.pausedAt
        val end = if (pausedAt != null) at.coerceIn(earliestEnd(t), pausedAt) else maxOf(at, earliestEnd(t))
        val last = t.segments.lastOrNull()
        val segments = t.segments.filter { it.endAt == null || (pausedAt != null && it === last && end < pausedAt) }
            .map { RowChange(it, it.copy(endAt = maxOf(end, it.startAt)).touched(cx)) }
        val event = e.copy(endAt = end, status = EventStatus.ENDED).touched(cx)
        return Change(listOf(RowChange(e, event)), segments)
    }

    /**
     * Ends the current breast segment at [at] and opens one on the other side. If [at] clamps to
     * the open segment's own start, the segment just changes side (no zero-length segment).
     */
    fun switchSide(t: TimedEvent, at: Long, cx: ChangeContext): Change {
        val e = t.event
        if (e.kind != Kind.BREAST || e.status != EventStatus.RUNNING || e.deletedAt != null) return Change()
        if (t.isPaused) return resume(t, t.lastSide!!.other, at, cx)
        val open = t.openSegment ?: return Change()
        val switchAt = maxOf(at, open.startAt)
        if (switchAt == open.startAt) {
            return Change(segments = listOf(RowChange(open, open.copy(side = open.side.other).touched(cx))))
        }
        val closed = open.copy(endAt = switchAt).touched(cx)
        val next = newSegment(e.id, open.side.other, switchAt, cx)
        return Change(segments = listOf(RowChange(open, closed), RowChange(null, next)))
    }

    /** Pauses a breast feed: closes the open segment at [at] (not before its start). */
    fun pause(t: TimedEvent, at: Long, cx: ChangeContext): Change {
        val e = t.event
        if (e.kind != Kind.BREAST || e.status != EventStatus.RUNNING || e.deletedAt != null) return Change()
        val open = t.openSegment ?: return Change()
        return Change(segments = listOf(RowChange(open, open.copy(endAt = maxOf(at, open.startAt)).touched(cx))))
    }

    /** Resumes a paused feed on [side] at [at] (not before the pause). */
    fun resume(t: TimedEvent, side: Side, at: Long, cx: ChangeContext): Change {
        val pausedAt = t.pausedAt ?: return Change()
        return Change(segments = listOf(RowChange(null, newSegment(t.event.id, side, maxOf(at, pausedAt), cx))))
    }

    /**
     * Moves the pause of a paused feed to [newAt] (the "paused 5 min ago" fix), clamped between
     * the start of its last segment and now.
     */
    fun moveLastPause(t: TimedEvent, newAt: Long, cx: ChangeContext): Change {
        if (!t.isPaused) return Change()
        val last = t.segments.last()
        val at = newAt.coerceIn(last.startAt, maxOf(last.startAt, cx.now))
        if (at == last.endAt) return Change()
        return Change(segments = listOf(RowChange(last, last.copy(endAt = at).touched(cx))))
    }

    /**
     * Moves the latest resume to [newAt] (the "resumed 5 min ago" fix), clamped between the
     * previous segment's end and the resumed segment's own end.
     */
    fun moveLastResume(t: TimedEvent, newAt: Long, cx: ChangeContext): Change {
        if (t.event.deletedAt != null || t.segments.size < 2) return Change()
        val prev = t.segments[t.segments.size - 2]
        val cur = t.segments.last()
        val at = newAt.coerceIn(prev.endAt ?: prev.startAt, cur.endAt ?: Long.MAX_VALUE)
        if (at == cur.startAt) return Change()
        return Change(segments = listOf(RowChange(cur, cur.copy(startAt = at).touched(cx))))
    }

    /** Discards a timer that shouldn't count (soft delete of the event and its segments). */
    fun cancel(t: TimedEvent, cx: ChangeContext): Change {
        val e = t.event
        if (e.deletedAt != null) return Change()
        val event = e.copy(deletedAt = cx.now).touched(cx)
        val segments = t.segments.map { RowChange(it, it.copy(deletedAt = cx.now).touched(cx)) }
        return Change(listOf(RowChange(e, event)), segments)
    }

    /**
     * The give-up step of overrun handling (SPEC §5.5): the end is unknown, so it is stored as
     * unknown. An open breast segment keeps a null end too, meaning "unknown" once the event isn't
     * running.
     */
    fun giveUp(t: TimedEvent, cx: ChangeContext): Change {
        val e = t.event
        if (e.status != EventStatus.RUNNING || e.deletedAt != null) return Change()
        return Change(listOf(RowChange(e, e.copy(status = EventStatus.END_UNKNOWN, endAt = null).touched(cx))))
    }

    /**
     * Moves the start of a timer (the "started 10 min ago" fix after a start). The new start is
     * clamped so it never passes the end of the first segment or the event's end.
     */
    fun moveStart(t: TimedEvent, newStart: Long, cx: ChangeContext): Change {
        val e = t.event
        if (e.deletedAt != null) return Change()
        val first = t.segments.firstOrNull()
        val upper = first?.endAt ?: e.endAt ?: Long.MAX_VALUE
        val start = minOf(newStart, upper)
        if (start == e.startAt && (first == null || first.startAt == start)) return Change()
        val event = RowChange(e, e.copy(startAt = start).touched(cx))
        val segments = if (first != null && first.startAt != start) {
            listOf(RowChange(first, first.copy(startAt = start).touched(cx)))
        } else {
            emptyList()
        }
        return Change(listOf(event), segments)
    }

    /**
     * Moves the most recent side switch of a breast feed to [newAt] (the "switched 5 min ago" fix),
     * clamped between the start of the previous segment and the end of the current one.
     */
    fun moveLastSwitch(t: TimedEvent, newAt: Long, cx: ChangeContext): Change {
        if (t.event.deletedAt != null || t.segments.size < 2) return Change()
        val prev = t.segments[t.segments.size - 2]
        val cur = t.segments.last()
        val at = newAt.coerceIn(prev.startAt, cur.endAt ?: Long.MAX_VALUE)
        if (at == cur.startAt && at == prev.endAt) return Change()
        return Change(
            segments = listOf(
                RowChange(prev, prev.copy(endAt = at).touched(cx)),
                RowChange(cur, cur.copy(startAt = at).touched(cx)),
            ),
        )
    }

    private fun newSegment(eventId: String, side: Side, at: Long, cx: ChangeContext) = BreastSegment(
        id = cx.newId(),
        eventId = eventId,
        side = side,
        startAt = at,
        createdAt = cx.now,
        updatedAt = cx.now,
        hlc = cx.nextHlc(),
    )
}
