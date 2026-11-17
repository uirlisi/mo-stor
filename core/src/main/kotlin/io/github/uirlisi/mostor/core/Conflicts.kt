package io.github.uirlisi.mostor.core

/**
 * Two or more running timers of one kind for one child, which only a sync can make (two caregivers
 * both tapped "Fell asleep", SPEC §9.5). Never settled automatically: a rule could throw away a real
 * record. [timers] are in start order, earliest first.
 */
data class TimerConflict(val childId: String, val kind: Kind, val timers: List<TimedEvent>) {
    /** Names this set of timers, for "Keep both" (remembered on this phone). */
    val key: String get() = timers.map { it.event.id }.sorted().joinToString(",")
}

enum class Keep { EARLIER, LATER, BOTH }

object Conflicts {
    /** The conflicts among [running] timers, leaving out sets the owner chose to keep ([kept] keys). */
    fun find(running: List<TimedEvent>, kept: Set<String>): List<TimerConflict> =
        running.filter { it.event.deletedAt == null && it.event.status == EventStatus.RUNNING }
            .groupBy { it.event.childId to it.event.kind }
            .filterValues { it.size > 1 }
            .map { (k, ts) -> TimerConflict(k.first, k.second, ts.sortedWith(compareBy({ it.event.startAt }, { it.event.id }))) }
            .filter { it.key !in kept }
            .sortedBy { it.timers.first().event.startAt }

    /**
     * The change for [keep]: [Keep.EARLIER] cancels every timer but the earliest, [Keep.LATER] every
     * one but the latest (canceled timers are soft-deleted, so Undo brings them back). [Keep.BOTH]
     * changes no record; the caller remembers [TimerConflict.key].
     */
    fun resolve(c: TimerConflict, keep: Keep, cx: ChangeContext): Change {
        val cancelled = when (keep) {
            Keep.EARLIER -> c.timers.drop(1)
            Keep.LATER -> c.timers.dropLast(1)
            Keep.BOTH -> emptyList()
        }
        return cancelled.fold(Change()) { acc, t -> acc.then(Timers.cancel(t, cx)) }
    }
}
