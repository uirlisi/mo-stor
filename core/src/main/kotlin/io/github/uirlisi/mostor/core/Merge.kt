package io.github.uirlisi.mostor.core

/**
 * Merging synced rows from elsewhere (SPEC §5.9): per row, the last writer wins on `hlc`. Used by
 * the JSON import (Phase 4) and, later, by Bluetooth sync (Phase 5).
 *
 * A row from elsewhere replaces the local one only when it is [newer]: its stamp is greater, except
 * that an automatic change sorts just after the version it was made from ([Event.autoBase]). That is
 * a total order, so applying the same rows again changes nothing, and the order rows arrive in doesn't
 * matter: re-applying old data can never move a row backwards. Tombstones (`deleted_at`) are rows
 * like any other.
 */
object Merge {
    /** All synced rows of one data set (a backup file, or later a sync payload). */
    data class Rows(
        val children: List<Child> = emptyList(),
        val events: List<Event> = emptyList(),
        val segments: List<BreastSegment> = emptyList(),
        val presets: List<SupplementPreset> = emptyList(),
    ) {
        val isEmpty: Boolean get() = children.isEmpty() && events.isEmpty() && segments.isEmpty() && presets.isEmpty()

        /** The newest stamp in these rows (stamps sort as text, SPEC §4.1), or null when empty. */
        fun newestHlc(): String? =
            sequenceOf(children, events, segments, presets).flatten().map { it.hlc }.maxOrNull()

        /**
         * These rows without those stamped after [limitMs] (wall time): rows from a phone whose date is
         * far ahead. Merged now, they would sort after every change this phone makes to them for days,
         * so a person's edit here would lose (and, with version vectors, the phones could disagree for
         * good). Left out, they stay out of this phone's vector and come again once the clocks agree.
         */
        fun notAfter(limitMs: Long): Rows {
            fun <T : Synced> List<T>.ok() = filter { r -> runCatching { HlcStamp.parse(r.hlc).wallMs <= limitMs }.getOrDefault(true) }
            return Rows(children.ok(), events.ok(), segments.ok(), presets.ok())
        }

        /** Every row's id, for loading the local rows they meet. */
        fun ids(): Ids = Ids(
            children.map { it.id },
            events.map { it.id },
            segments.map { it.id },
            presets.map { it.id },
        )
    }

    data class Ids(val children: List<String>, val events: List<String>, val segments: List<String>, val presets: List<String>)

    /** What merging would do: the rows to write, and how many incoming rows were new, newer or not. */
    data class Plan(val change: Change, val added: Int, val updated: Int, val unchanged: Int)

    /**
     * Merges [incoming] into [local], which holds the local rows with the same ids (others may be
     * left out). The change's rows keep the incoming stamps: a merged row is the other phone's
     * change, not a new one. Duplicate ids in [incoming] are settled the same way, so the newest wins.
     */
    fun plan(local: Rows, incoming: Rows): Plan {
        var added = 0
        var updated = 0
        var unchanged = 0

        fun <T : Synced> table(mine: List<T>, theirs: List<T>): List<RowChange<T>> {
            val byId = mine.associateBy { it.id }
            val newest = LinkedHashMap<String, T>()
            for (row in theirs) {
                val seen = newest[row.id]
                if (seen == null || newer(row, seen)) newest[row.id] = row
            }
            unchanged += theirs.size - newest.size
            return newest.values.mapNotNull { row ->
                val here = byId[row.id]
                when {
                    here == null -> { added++; RowChange(null, row) }
                    newer(row, here) -> { updated++; RowChange(here, row) }
                    else -> { unchanged++; null }
                }
            }
        }

        val change = Change(
            events = table(local.events, incoming.events),
            segments = table(local.segments, incoming.segments),
            children = table(local.children, incoming.children),
            presets = table(local.presets, incoming.presets),
        )
        return Plan(change, added, updated, unchanged)
    }

    /**
     * Whether version [a] of a row beats version [b]: the greater [Synced.mergeRank], then the greater
     * stamp. A total order, so phones converge whatever order versions arrive in.
     */
    fun newer(a: Synced, b: Synced): Boolean =
        a.mergeRank > b.mergeRank || (a.mergeRank == b.mergeRank && a.hlc > b.hlc)

    /**
     * Whether [child] is the untouched child a fresh install makes (DECISIONS 2026-11-14): live,
     * never edited, with no records at all ([eventCount] counts deleted ones too), and with only its
     * untouched seeded meds ([presets] are this child's meds, deleted ones included). An import
     * replaces such a child rather than adding a second one beside it.
     */
    fun isBlank(child: Child, eventCount: Int, presets: List<SupplementPreset>): Boolean =
        child.deletedAt == null && child.archivedAt == null && child.updatedAt == child.createdAt &&
            child.name == null && child.birthDate == null && child.sex == null && child.birthWeightG == null &&
            child.birthLengthMm == null && child.color == null &&
            eventCount == 0 &&
            presets.all { it.childId == child.id && it.hlc == Presets.SEED_HLC && it.deletedAt == null }

    /**
     * Soft-deletes a blank [child] and its seeded [presets], as a new local change. Call only after
     * [isBlank], and only when the import brings at least one live child of its own.
     */
    fun replaceBlank(child: Child, presets: List<SupplementPreset>, cx: ChangeContext): Change = Change(
        children = listOf(RowChange(child, child.copy(deletedAt = cx.now).touched(cx))),
        presets = presets.map { RowChange(it, it.copy(deletedAt = cx.now).touched(cx)) },
    )
}
