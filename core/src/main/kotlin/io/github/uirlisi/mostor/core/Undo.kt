package io.github.uirlisi.mostor.core

/**
 * Undo (SPEC §5.7). An undo is a new change that writes back the "before" state of every row the
 * original change touched, with fresh HLC stamps, so it syncs like any other change. A row the
 * change created is soft-deleted (there are no hard deletes).
 */
object Undo {
    /**
     * The change that reverts [change], or null if any of its rows has changed since (then the
     * undo could throw away a later record, so it is refused). The lookups find rows by id,
     * including deleted ones.
     */
    fun revert(
        change: Change,
        currentEvent: (String) -> Event?,
        currentSegment: (String) -> BreastSegment?,
        cx: ChangeContext,
        currentChild: (String) -> Child? = { null },
        currentPreset: (String) -> SupplementPreset? = { null },
    ): Change? {
        val events = revertRows(change.events, currentEvent, cx) ?: return null
        val segments = revertRows(change.segments, currentSegment, cx) ?: return null
        val children = revertRows(change.children, currentChild, cx) ?: return null
        val presets = revertRows(change.presets, currentPreset, cx) ?: return null
        return Change(events, segments, children, presets)
    }

    /**
     * What an undo writes for one row: its state before the change, or, for a row the change created,
     * the row deleted; stamped as a new change. An undo is a person's change, so an event never keeps
     * an automatic change's mark ([Event.autoBase]).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Synced> restored(cur: T, before: T?, cx: ChangeContext): T = when (val row = before ?: cur) {
        is Event -> row.copy(deletedAt = if (before == null) cx.now else row.deletedAt, updatedAt = cx.now, hlc = cx.nextHlc(), autoBase = null)
        is BreastSegment -> row.copy(deletedAt = if (before == null) cx.now else row.deletedAt, updatedAt = cx.now, hlc = cx.nextHlc())
        is Child -> row.copy(deletedAt = if (before == null) cx.now else row.deletedAt, updatedAt = cx.now, hlc = cx.nextHlc())
        is SupplementPreset -> row.copy(deletedAt = if (before == null) cx.now else row.deletedAt, updatedAt = cx.now, hlc = cx.nextHlc())
        else -> error("not a synced row: ${row::class.simpleName}")
    } as T

    private inline fun <T : Synced> revertRows(
        rows: List<RowChange<T>>,
        current: (String) -> T?,
        cx: ChangeContext,
    ): List<RowChange<T>>? = rows.map { rc ->
        val cur = current(rc.after.id) ?: return null
        if (cur.hlc != rc.after.hlc && cur.content() != rc.after.content()) return null
        RowChange(cur, restored(cur, rc.before, cx))
    }

    /**
     * A row without its stamps: a row only re-stamped since (an event row stamped by a later side
     * switch, then put back by that switch's undo) hasn't changed, and undoing loses nothing.
     */
    private fun Synced.content(): Any = when (this) {
        is Event -> copy(updatedAt = 0, hlc = "", autoBase = null)
        is BreastSegment -> copy(updatedAt = 0, hlc = "")
        is Child -> copy(updatedAt = 0, hlc = "")
        is SupplementPreset -> copy(updatedAt = 0, hlc = "")
        else -> this
    }
}
