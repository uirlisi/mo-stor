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
        val events = revertRows(change.events, currentEvent) { cur, before ->
            before?.copy(updatedAt = cx.now, hlc = cx.nextHlc()) ?: cur.copy(deletedAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
        } ?: return null
        val segments = revertRows(change.segments, currentSegment) { cur, before ->
            before?.copy(updatedAt = cx.now, hlc = cx.nextHlc()) ?: cur.copy(deletedAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
        } ?: return null
        val children = revertRows(change.children, currentChild) { cur, before ->
            before?.copy(updatedAt = cx.now, hlc = cx.nextHlc()) ?: cur.copy(deletedAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
        } ?: return null
        val presets = revertRows(change.presets, currentPreset) { cur, before ->
            before?.copy(updatedAt = cx.now, hlc = cx.nextHlc()) ?: cur.copy(deletedAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
        } ?: return null
        return Change(events, segments, children, presets)
    }

    private inline fun <T : Synced> revertRows(
        rows: List<RowChange<T>>,
        current: (String) -> T?,
        restore: (cur: T, before: T?) -> T,
    ): List<RowChange<T>>? = rows.map { rc ->
        val cur = current(rc.after.id) ?: return null
        if (cur.hlc != rc.after.hlc) return null
        RowChange(cur, restore(cur, rc.before))
    }
}
