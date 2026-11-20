package io.github.uirlisi.mostor.core

/**
 * Per-kind overrun settings (SPEC §5.5). Null means "never". Defaults: breast nudges after 45 min
 * and gives up after 3 h; sleep nudges after 6 h and gives up after 14 h.
 */
data class OverrunLimits(val nudgeAfterMs: Long?, val giveUpAfterMs: Long?) {
    companion object {
        private const val MIN = 60_000L
        val BREAST_DEFAULT = OverrunLimits(nudgeAfterMs = 45 * MIN, giveUpAfterMs = 3 * 60 * MIN)
        val SLEEP_DEFAULT = OverrunLimits(nudgeAfterMs = 6 * 60 * MIN, giveUpAfterMs = 14 * 60 * MIN)
    }
}

enum class OverrunState {
    /** Nothing to say yet. */
    NORMAL,

    /** "Still feeding? Started 14:02" with [Stop now] [Still going]. */
    NUDGE,

    /** Time to mark the end unknown. */
    GIVE_UP,

    /** A paused feed left paused for the nudge interval: it ends at its pause. */
    END_AT_PAUSE,
}

/**
 * The "timer ran for hours" fix (SPEC §5.5). Pure: the caller stores the snooze time ("Still going"
 * pressed) and acts on the state.
 */
object Overrun {
    /** When the nudge shows: after the snooze if "Still going" was pressed, else start + nudge interval. */
    fun nudgeAt(startAt: Long, limits: OverrunLimits, snoozedUntil: Long?): Long? =
        if (limits.nudgeAfterMs == null) null else snoozedUntil ?: (startAt + limits.nudgeAfterMs)

    /** Give-up is measured from the start ([countsFrom] for a timer); "Still going" doesn't move it. */
    fun giveUpAt(startAt: Long, limits: OverrunLimits): Long? = limits.giveUpAfterMs?.let { startAt + it }

    fun state(startAt: Long, now: Long, limits: OverrunLimits, snoozedUntil: Long?): OverrunState {
        giveUpAt(startAt, limits)?.let { if (now >= it) return OverrunState.GIVE_UP }
        nudgeAt(startAt, limits, snoozedUntil)?.let { if (now >= it) return OverrunState.NUDGE }
        return OverrunState.NORMAL
    }

    /** The next time the state can change, or null if it never will. */
    fun nextCheck(startAt: Long, now: Long, limits: OverrunLimits, snoozedUntil: Long?): Long? =
        listOfNotNull(nudgeAt(startAt, limits, snoozedUntil), giveUpAt(startAt, limits)).filter { it > now }.minOrNull()

    /** When a paused feed ends by itself: its pause + the nudge interval (null = never). */
    fun pauseEndsAt(pausedAt: Long, limits: OverrunLimits): Long? = limits.nudgeAfterMs?.let { pausedAt + it }

    /**
     * Where a running timer's nudge and give-up count from: its start, moved on by the time a feed was
     * paused, so only feeding time counts. A feed resumed after a long pause isn't asked "Still
     * feeding?" at once, and its pauses don't bring the give-up nearer (review 2026-11-19).
     */
    fun countsFrom(t: TimedEvent, now: Long): Long =
        if (t.event.kind == Kind.BREAST && t.segments.isNotEmpty()) now - Durations.breast(t.segments, now).totalMs else t.event.startAt

    /**
     * [state] for a timer: a paused feed never nudges or gives up; it ends at its pause once it
     * has been paused for the nudge interval (all its feeding time was recorded).
     */
    fun state(t: TimedEvent, now: Long, limits: OverrunLimits, snoozedUntil: Long?): OverrunState {
        val pausedAt = t.pausedAt ?: return state(countsFrom(t, now), now, limits, snoozedUntil)
        val endsAt = pauseEndsAt(pausedAt, limits)
        return if (endsAt != null && now >= endsAt) OverrunState.END_AT_PAUSE else OverrunState.NORMAL
    }

    /** [nextCheck] for a timer, paused feeds included. */
    fun nextCheck(t: TimedEvent, now: Long, limits: OverrunLimits, snoozedUntil: Long?): Long? {
        val pausedAt = t.pausedAt ?: return nextCheck(countsFrom(t, now), now, limits, snoozedUntil)
        return pauseEndsAt(pausedAt, limits)?.takeIf { it > now }
    }

    /** "Still going" waits the same interval again before nudging. */
    fun snoozeUntil(now: Long, limits: OverrunLimits): Long? = limits.nudgeAfterMs?.let { now + it }
}
