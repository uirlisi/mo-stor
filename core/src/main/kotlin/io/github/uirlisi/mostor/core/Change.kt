package io.github.uirlisi.mostor.core

/** One row's state before (null when the row is new) and after a change. */
data class RowChange<T : Synced>(val before: T?, val after: T)

/**
 * The rows one user action writes. Keeping the "before" states makes every change undoable
 * (SPEC §5.7): undo writes them back as a new change.
 */
data class Change(
    val events: List<RowChange<Event>> = emptyList(),
    val segments: List<RowChange<BreastSegment>> = emptyList(),
    val children: List<RowChange<Child>> = emptyList(),
    val presets: List<RowChange<SupplementPreset>> = emptyList(),
) {
    val isEmpty: Boolean get() = events.isEmpty() && segments.isEmpty() && children.isEmpty() && presets.isEmpty()

    /** This change followed by [next], as one change: per row, the first "before" and the last "after". */
    fun then(next: Change): Change = Change(
        merge(events, next.events),
        merge(segments, next.segments),
        merge(children, next.children),
        merge(presets, next.presets),
    )

    private fun <T : Synced> merge(first: List<RowChange<T>>, second: List<RowChange<T>>): List<RowChange<T>> {
        val out = LinkedHashMap<String, RowChange<T>>()
        for (rc in first) out[rc.after.id] = rc
        for (rc in second) {
            val earlier = out[rc.after.id]
            out[rc.after.id] = if (earlier == null) rc else RowChange(earlier.before, rc.after)
        }
        return out.values.toList()
    }
}

/** What a change needs from the outside world: the time, the zone, new ids and HLC stamps. */
interface ChangeContext {
    /** UTC milliseconds when the change is made. */
    val now: Long

    /** IANA zone id recorded with new events. */
    val tz: String

    fun newId(): String

    /** A fresh, encoded HLC stamp, greater than every stamp handed out before. */
    fun nextHlc(): String
}

internal fun Event.touched(cx: ChangeContext): Event = copy(updatedAt = cx.now, hlc = cx.nextHlc())

internal fun BreastSegment.touched(cx: ChangeContext): BreastSegment = copy(updatedAt = cx.now, hlc = cx.nextHlc())

internal fun Child.touched(cx: ChangeContext): Child = copy(updatedAt = cx.now, hlc = cx.nextHlc())

internal fun SupplementPreset.touched(cx: ChangeContext): SupplementPreset = copy(updatedAt = cx.now, hlc = cx.nextHlc())
