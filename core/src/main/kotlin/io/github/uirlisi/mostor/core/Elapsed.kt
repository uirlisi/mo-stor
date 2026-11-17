package io.github.uirlisi.mostor.core

/** A non-negative elapsed time split into whole hours and minutes, for "2h 40m"-style display. */
data class Elapsed(val hours: Long, val minutes: Int) {
    companion object {
        private const val MINUTE_MS = 60_000L

        /** Splits [durationMs] into hours and minutes, rounding down. Negative input counts as zero. */
        fun of(durationMs: Long): Elapsed {
            val totalMinutes = durationMs.coerceAtLeast(0) / MINUTE_MS
            return Elapsed(hours = totalMinutes / 60, minutes = (totalMinutes % 60).toInt())
        }
    }
}
