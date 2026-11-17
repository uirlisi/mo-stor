package io.github.uirlisi.mostor.core

import java.time.LocalDateTime
import java.time.ZoneId

const val MIN = 60_000L
const val HOUR = 60 * MIN
const val CHILD = "child-1"

val DUBLIN: ZoneId = ZoneId.of("Europe/Dublin")

/** Epoch ms of a local date-time in Dublin. */
fun dublin(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(DUBLIN).toInstant().toEpochMilli()

/** A deterministic [ChangeContext]: ids "id-1", "id-2"… and HLC stamps from a real [HybridClock]. */
class FakeCx(override var now: Long, override val tz: String = "Europe/Dublin") : ChangeContext {
    private var nextId = 0
    private val clock = HybridClock("device-a", null) { now }

    override fun newId(): String = "id-${++nextId}"
    override fun nextHlc(): String = clock.now().encode()
}

/** Applies a change to an in-memory table, as the repository would. */
class Table {
    val events = linkedMapOf<String, Event>()
    val segments = linkedMapOf<String, BreastSegment>()

    fun apply(change: Change): Change {
        change.events.forEach { events[it.after.id] = it.after }
        change.segments.forEach { segments[it.after.id] = it.after }
        return change
    }

    fun timed(eventId: String): TimedEvent = TimedEvent.of(events.getValue(eventId), segments.values.toList())

    fun undo(change: Change, cx: ChangeContext): Change? =
        Undo.revert(change, { events[it] }, { segments[it] }, cx)?.also { apply(it) }
}

fun Change.newEventId(): String = events.single { it.before == null }.after.id
