package io.github.uirlisi.mostor.core

/** Breast-feed time per side. */
data class BreastTotals(val leftMs: Long, val rightMs: Long) {
    val totalMs: Long get() = leftMs + rightMs
}

/** Durations (SPEC §5.2). */
object Durations {
    /** Sums the segments per side. An open segment counts up to [now]. */
    fun breast(segments: List<BreastSegment>, now: Long): BreastTotals {
        var left = 0L
        var right = 0L
        for (s in segments) {
            if (s.deletedAt != null) continue
            val ms = ((s.endAt ?: now) - s.startAt).coerceAtLeast(0)
            if (s.side == Side.LEFT) left += ms else right += ms
        }
        return BreastTotals(left, right)
    }

    /**
     * How long a timed event lasted (or has lasted so far, while running). Null when its end is
     * unknown: those events count as events but are left out of duration totals.
     */
    fun of(t: TimedEvent, now: Long): Long? {
        val e = t.event
        return when (e.status) {
            EventStatus.END_UNKNOWN, EventStatus.SKIPPED, null -> null
            EventStatus.RUNNING ->
                if (e.kind == Kind.BREAST) breast(t.segments, now).totalMs else (now - e.startAt).coerceAtLeast(0)
            EventStatus.ENDED ->
                if (e.kind == Kind.BREAST) breast(t.segments, now).totalMs else ((e.endAt ?: e.startAt) - e.startAt).coerceAtLeast(0)
        }
    }

    /**
     * When the baby woke: the end of the last ended sleep. Null while a sleep is running, when
     * there are no ended sleeps, or when a later sleep has an unknown end (then nobody knows).
     */
    fun awakeSince(sleeps: List<Event>): Long? {
        val live = sleeps.filter { it.kind == Kind.SLEEP && it.deletedAt == null }
        if (live.any { it.status == EventStatus.RUNNING }) return null
        val lastEnd = live.filter { it.status == EventStatus.ENDED }.mapNotNull { it.endAt }.maxOrNull() ?: return null
        if (live.any { it.status == EventStatus.END_UNKNOWN && it.startAt >= lastEnd }) return null
        return lastEnd
    }

    /** "Awake for": now − the end of the last ended sleep, when no sleep is running. */
    fun awakeFor(sleeps: List<Event>, now: Long): Long? = awakeSince(sleeps)?.let { (now - it).coerceAtLeast(0) }
}
