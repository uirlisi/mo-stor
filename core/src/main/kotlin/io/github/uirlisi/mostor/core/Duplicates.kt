package io.github.uirlisi.mostor.core

/**
 * Possible duplicates (SPEC §5.6): two live events for the same child, kind and subtype, made on
 * different phones, within 5 minutes of each other. Computed, never stored.
 *
 * "Made on" is the origin of the row's `hlc`, which is the phone that changed it *last* (there is
 * no creator column). For supplements the preset (or its name) acts as the subtype, and for notes
 * the text, so Vit D on one phone and Vit K on the other aren't flagged.
 */
object Duplicates {
    const val WINDOW_MS = 5 * 60_000L

    /** The device id at the end of an encoded HLC stamp, or "" if it isn't one. */
    fun origin(hlc: String): String = hlc.split('-', limit = 3).getOrNull(2).orEmpty()

    /** For each flagged event id, the ids of the events it may duplicate. */
    fun find(events: List<Event>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        events.asSequence()
            .filter { it.deletedAt == null }
            .groupBy { Key(it.childId, it.kind, sortOf(it)) }
            .values
            .forEach { group ->
                val sorted = group.sortedBy { it.startAt }
                for (i in sorted.indices) {
                    val a = sorted[i]
                    for (j in i + 1 until sorted.size) {
                        val b = sorted[j]
                        if (b.startAt - a.startAt > WINDOW_MS) break
                        if (origin(a.hlc) != origin(b.hlc)) {
                            out.getOrPut(a.id) { mutableSetOf() } += b.id
                            out.getOrPut(b.id) { mutableSetOf() } += a.id
                        }
                    }
                }
            }
        return out
    }

    /** What tells two records of one kind apart: the subtype; a med's preset; a note's text. */
    private fun sortOf(e: Event): String? = when (e.kind) {
        Kind.SUPPLEMENT -> e.supplementPresetId ?: e.supplementName
        Kind.NOTE -> e.note?.trim()
        else -> e.subtype
    }

    private data class Key(val child: String, val kind: Kind, val sub: String?)
}
