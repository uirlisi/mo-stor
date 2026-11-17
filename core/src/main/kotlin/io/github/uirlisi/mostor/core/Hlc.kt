package io.github.uirlisi.mostor.core

/**
 * A hybrid logical clock stamp (SPEC §4.1, §5.9). Encoded as `wall-counter-origin`, with the wall
 * time as 12 hex digits and the counter as 4, so encoded stamps sort lexicographically in the same
 * order as [compareTo]. The origin is the id of the device that made the change.
 */
data class HlcStamp(val wallMs: Long, val counter: Int, val origin: String) : Comparable<HlcStamp> {
    init {
        require(wallMs in 0..MAX_WALL) { "wall time out of range" }
        require(counter in 0..MAX_COUNTER) { "counter out of range" }
        require(origin.isNotEmpty())
    }

    fun encode(): String =
        wallMs.toString(16).padStart(WALL_DIGITS, '0') + "-" +
            counter.toString(16).padStart(COUNTER_DIGITS, '0') + "-" + origin

    override fun compareTo(other: HlcStamp): Int =
        compareValuesBy(this, other, { it.wallMs }, { it.counter }, { it.origin })

    override fun toString(): String = encode()

    companion object {
        const val MAX_COUNTER = 0xFFFF
        private const val WALL_DIGITS = 12
        private const val COUNTER_DIGITS = 4
        private const val MAX_WALL = 0xFFFF_FFFF_FFFFL

        fun parse(encoded: String): HlcStamp {
            val parts = encoded.split('-', limit = 3)
            require(parts.size == 3 && parts[0].length == WALL_DIGITS && parts[1].length == COUNTER_DIGITS) {
                "not an HLC stamp"
            }
            return HlcStamp(parts[0].toLong(16), parts[1].toInt(16), parts[2])
        }
    }
}

/**
 * The device's hybrid logical clock. [now] stamps a local change; [receive] (Phase 5) folds in a
 * stamp from another phone so later local stamps sort after it. Thread-safe.
 */
class HybridClock(
    val origin: String,
    last: HlcStamp?,
    private val wallClock: () -> Long,
) {
    var last: HlcStamp? = last
        private set

    @Synchronized
    fun now(): HlcStamp {
        val pt = wallClock()
        val prev = last
        val next = when {
            prev == null || pt > prev.wallMs -> HlcStamp(pt, 0, origin)
            else -> bump(prev.wallMs, prev.counter)
        }
        last = next
        return next
    }

    @Synchronized
    fun receive(remote: HlcStamp): HlcStamp {
        val pt = wallClock()
        val prev = last
        val prevWall = prev?.wallMs ?: Long.MIN_VALUE
        val wall = maxOf(prevWall, remote.wallMs, pt)
        val next = when {
            wall == prevWall && wall == remote.wallMs -> bump(wall, maxOf(prev!!.counter, remote.counter))
            wall == prevWall -> bump(wall, prev!!.counter)
            wall == remote.wallMs -> bump(wall, remote.counter)
            else -> HlcStamp(wall, 0, origin)
        }
        last = next
        return next
    }

    /** The stamp after (wall, counter). If the counter would overflow, the wall time moves on 1 ms. */
    private fun bump(wall: Long, counter: Int): HlcStamp =
        if (counter < HlcStamp.MAX_COUNTER) HlcStamp(wall, counter + 1, origin) else HlcStamp(wall + 1, 0, origin)
}
