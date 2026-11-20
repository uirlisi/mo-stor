package io.github.uirlisi.mostor.core

/**
 * One phone of the household (SPEC §9.4): every phone holds the whole list, so each sees every
 * other, who added it, and whether it was removed. Synced, but not through [Merge.Rows]: the list is
 * a few rows, sent whole in every session, so it never enters a version vector. It isn't exported.
 *
 * [addedBy] and [removedBy] are device ids (null for rows written when an existing pairing was
 * upgraded). A removed phone keeps its row, with [removedAt] set, so the removal reaches every phone.
 */
data class Member(
    val deviceId: String,
    val name: String,
    val addedBy: String? = null,
    val addedAt: Long,
    val removedAt: Long? = null,
    val removedBy: String? = null,
    val hlc: String,
) {
    val active: Boolean get() = removedAt == null
}

/**
 * Where a phone stands in this phone's list. [LEFT]: it removed itself (Leave household) and is
 * let in once more, so its last records and its leaving reach the household before it goes.
 */
enum class Standing { ACTIVE, LEFT, REMOVED, UNKNOWN }

object Roster {
    /**
     * The stamp of rows written when an existing pairing was upgraded to the shared list: lower than
     * any real stamp, so any change made since (a removal, above all) wins over them.
     */
    const val SEED_HLC = "000000000000-0000-seed"

    /**
     * Last writer wins per phone, by stamp, with two exceptions. A row from an upgrade ([SEED_HLC])
     * loses to any real change. And within one membership (the same added time), a removal beats the
     * phone still being active: a removed phone that renames itself before it hears of the removal
     * re-stamps its own row, and must not undo the removal that way; adding it again (a new added
     * time) does. Equal stamps (two phones' upgrade rows for the same phone) are settled by the row's
     * fields, so every phone picks the same one.
     */
    fun merge(mine: Collection<Member>, theirs: Collection<Member>): List<Member> {
        val out = LinkedHashMap<String, Member>()
        for (m in mine) out[m.deviceId] = newer(out[m.deviceId], m)
        for (m in theirs) out[m.deviceId] = newer(out[m.deviceId], m)
        return out.values.toList()
    }

    /** The rows of [theirs] that would change [mine] (what a merge writes). */
    fun changes(mine: Collection<Member>, theirs: Collection<Member>): List<Member> {
        val byId = mine.associateBy { it.deviceId }
        return merge(emptyList(), theirs).filter { t -> byId[t.deviceId].let { it == null || newer(it, t) !== it } }
    }

    fun standing(members: Collection<Member>, deviceId: String): Standing {
        val m = members.firstOrNull { it.deviceId == deviceId } ?: return Standing.UNKNOWN
        return when {
            m.active -> Standing.ACTIVE
            m.removedBy == deviceId -> Standing.LEFT
            else -> Standing.REMOVED
        }
    }

    /** This phone ([me]) has left the household and hasn't told another phone yet. */
    fun leaving(members: Collection<Member>, me: String): Boolean = standing(members, me) == Standing.LEFT

    fun active(members: Collection<Member>): List<Member> = members.filter { it.active }

    /** [deviceId] joins (or joins again): [by] added it. */
    fun add(deviceId: String, name: String, by: String, cx: ChangeContext): Member =
        Member(deviceId, name, addedBy = by, addedAt = cx.now, hlc = cx.nextHlc())

    /** This phone's own row under its current [name] (it can change in Android's settings). */
    fun rename(m: Member, name: String, cx: ChangeContext): Member = m.copy(name = name, hlc = cx.nextHlc())

    /** [by] removes [m] from the household, for every phone. */
    fun remove(m: Member, by: String, cx: ChangeContext): Member =
        m.copy(removedAt = cx.now, removedBy = by, hlc = cx.nextHlc())

    /** The newest stamp in [members] that came from a real change (not an upgrade row). */
    fun newestHlc(members: Collection<Member>): String? = members.map { it.hlc }.filter { it != SEED_HLC }.maxOrNull()

    private fun newer(a: Member?, b: Member): Member = when {
        a == null -> b
        (a.hlc == SEED_HLC) != (b.hlc == SEED_HLC) -> if (a.hlc == SEED_HLC) b else a
        a.addedAt == b.addedAt && a.active != b.active -> if (a.active) b else a
        b.hlc > a.hlc -> b
        b.hlc < a.hlc -> a
        tieKey(b) > tieKey(a) -> b
        else -> a
    }

    private fun tieKey(m: Member) = listOf(m.name, m.addedBy.orEmpty(), m.addedAt, m.removedAt ?: -1, m.removedBy.orEmpty()).joinToString("\u0000")
}
