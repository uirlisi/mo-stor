package io.github.uirlisi.mostor.notify

/**
 * Notification buttons are meant for one tap: a second tap within [windowMs] of the last accepted
 * one is an accidental double tap and is dropped. Before the notification is redrawn it would repeat
 * the action ([Switch side] twice is back on the first side); after the redraw it would land on the
 * button now in that place ([Undo] takes [Switch side]'s place, [Resume] takes [Pause]'s).
 */
class ButtonGuard(private val windowMs: Long = WINDOW_MS) {
    private var last = Long.MIN_VALUE

    /** Whether a tap at [nowMs] (a monotonic clock) counts. */
    @Synchronized
    fun accept(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last in 0 until windowMs) return false
        last = nowMs
        return true
    }

    companion object {
        const val WINDOW_MS = 1_000L
    }
}
