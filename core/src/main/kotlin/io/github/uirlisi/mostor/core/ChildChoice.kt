package io.github.uirlisi.mostor.core

/**
 * Which children a multi-select child picker shows (Phase 3c: Today and the Log). At least one
 * child is always chosen; a choice is kept in the app's child order.
 */
object ChildChoice {
    /** The stored value for every child, also read from before Phase 3c. */
    const val ALL = "all"

    /**
     * Today's children from the per-phone `today_child` setting, in the order of [active] (the
     * active children, in the app's order). [raw] is [ALL]; a comma-separated list of child ids
     * (two or more when written); a single id (an older value); or null, which means just
     * [activeChild], the child this phone records for. Ids that are no longer active children
     * (deleted, archived) are dropped; when none is left, it is [activeChild] again.
     */
    fun parseToday(raw: String?, active: List<String>, activeChild: String): List<String> {
        if (active.size < 2) return listOf(activeChild)
        val value = raw?.trim().orEmpty()
        if (value == ALL) return active
        val ids = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return active.filter { it in ids }.ifEmpty { listOf(activeChild) }
    }

    /**
     * The stored value for [chosen] out of [active]: [ALL] when every active child is chosen, the
     * ids in child order when several are, and null for exactly one (that child then becomes the
     * active child, which is what null means).
     */
    fun encodeToday(chosen: Collection<String>, active: List<String>): String? {
        val ordered = active.filter { it in chosen }
        return when {
            ordered.size > 1 && ordered.size == active.size -> ALL
            ordered.size > 1 -> ordered.joinToString(",")
            else -> null
        }
    }

    /** Whether ticking [id] off is allowed: the last chosen child can't be unticked. */
    fun canUntick(chosen: Collection<String>, id: String): Boolean = id !in chosen || chosen.size > 1

    /**
     * Ticks [id] on or off, in the order of [all]. Unticking the last chosen child changes nothing.
     */
    fun toggle(chosen: Collection<String>, id: String, all: List<String>): List<String> = when {
        id !in chosen -> all.filter { it in chosen || it == id }
        canUntick(chosen, id) -> all.filter { it in chosen && it != id }
        else -> all.filter { it in chosen }
    }

    /** Whether every child is chosen ("All children" ticked; otherwise it shows a dash). */
    fun isAll(chosen: Collection<String>, all: List<String>): Boolean = all.all { it in chosen }
}
