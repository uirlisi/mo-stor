package io.github.uirlisi.mostor.core

/**
 * One household on this phone (SPEC §9.4): a database file of its own. [name] is the owner's name
 * for it, null for the default "Household [number]". [number] never changes and is never reused, not
 * even after the household is deleted ([HouseholdList.topNumber]), so it tells households' files
 * apart (the daily backup's names) for good.
 */
data class HouseholdSlot(val id: String, val number: Int, val file: String, val name: String? = null) {
    /** The first household: the database every install had before there were several. */
    val isFirst: Boolean get() = file == HouseholdList.FIRST_FILE
}

/**
 * The households on this phone and the active one (SPEC §9.4). Every screen, the notification and
 * sync use the active household's database; the others wait, unchanged, until switched to. Kept in
 * the phone's own store (not in any database), and never synced.
 */
data class HouseholdList(
    val slots: List<HouseholdSlot>,
    val activeId: String,
    /** The highest number a household on this phone ever had, deleted ones included. */
    val topNumber: Int = slots.maxOf { it.number },
    /**
     * Database files of deleted households not yet removed from storage ([remove], [deleted]). Never
     * adopted back: the app may stop between taking a household off the list and deleting its file.
     */
    val pendingDelete: Set<String> = emptySet(),
) {
    init {
        require(slots.isNotEmpty()) { "a phone always has a household" }
        require(slots.any { it.id == activeId }) { "the active household is one of the phone's" }
        require(slots.map { it.id }.toSet().size == slots.size) { "household ids are unique" }
    }

    val active: HouseholdSlot get() = slots.first { it.id == activeId }

    /** The other households, in the order they were made. */
    val others: List<HouseholdSlot> get() = slots.filter { it.id != activeId }

    /** Two or more: only then do the notification and the app show the household's name. */
    val several: Boolean get() = slots.size > 1

    fun slot(id: String): HouseholdSlot? = slots.firstOrNull { it.id == id }

    /** The number the next household gets ("Household 3"). */
    val nextNumber: Int get() = maxOf(topNumber, slots.maxOf { it.number }) + 1

    /** A new household with its own file ([fileFor]); not active until [activate]. */
    fun add(id: String, name: String? = null): HouseholdList {
        require(slot(id) == null) { "household $id exists" }
        val n = nextNumber
        return copy(slots = slots + HouseholdSlot(id, n, fileFor(id), cleanName(name)), topNumber = n)
    }

    fun activate(id: String): HouseholdList {
        require(slot(id) != null) { "no household $id" }
        return copy(activeId = id)
    }

    /** The owner's name for [id]; blank goes back to "Household N". */
    fun rename(id: String, name: String?): HouseholdList =
        copy(slots = slots.map { if (it.id == id) it.copy(name = cleanName(name)) else it })

    /**
     * Household [id] joined one the inviting phone calls [theirs] (SPEC §9.4 "Joining"): the name is
     * copied once, and never synced after. A household kept apart takes it; a merged one takes it
     * only while it has no name of its own. An unnamed household on the inviting phone gives none
     * (its "Household N" numbers that phone's households, not this one's).
     */
    fun joined(id: String, theirs: String?, way: JoinWay): HouseholdList {
        val name = cleanName(theirs) ?: return this
        val slot = slot(id) ?: return this
        return if (way == JoinWay.APART || slot.name == null) rename(id, name) else this
    }

    /**
     * Takes [id] off the list, its file waiting to be deleted ([pendingDelete], then [deleted]). The last
     * household can't go (a phone always has one); when [id] is the active one, the first other
     * household becomes active.
     */
    fun remove(id: String): HouseholdList {
        val gone = requireNotNull(slot(id)) { "no household $id" }
        require(several) { "the last household can't be removed" }
        val rest = slots.filter { it.id != id }
        return copy(slots = rest, activeId = if (activeId == id) rest.first().id else activeId, pendingDelete = pendingDelete + gone.file)
    }

    /** [file], of a removed household, is gone from storage. */
    fun deleted(file: String): HouseholdList = copy(pendingDelete = pendingDelete - file)

    /**
     * Database files of households missing from the list (its store was lost, while the files
     * stayed) come back as households of their own, so no records are ever hidden for good.
     */
    fun adopt(files: Collection<String>): HouseholdList {
        var out = this
        for (file in files.sorted()) {
            val id = idOfFile(file) ?: continue
            if (file in pendingDelete) continue
            if (out.slots.none { it.file == file } && out.slot(id) == null) out = out.add(id)
        }
        return out
    }

    /**
     * One line per household after a header (format, active id, [topNumber], then [pendingDelete],
     * separated by tabs); names can't hold tabs or line breaks ([cleanName]), files can't hold tabs.
     */
    fun encode(): String = buildString {
        append(FORMAT).append('\t').append(activeId).append('\t').append(topNumber)
        for (f in pendingDelete.sorted()) append('\t').append(f)
        for (s in slots) append('\n').append(s.id).append('\t').append(s.number).append('\t').append(s.file).append('\t').append(s.name.orEmpty())
    }

    companion object {
        /** The database file every install had before there were several households. */
        const val FIRST_FILE = "mostor.db"
        private const val FIRST_ID = "first"
        private const val FORMAT = "households-2"
        private const val FORMAT_1 = "households-1"
        private const val NAME_MAX = 40
        private val FILE = Regex("^household-([0-9A-Za-z-]{1,64})\\.db$")

        /** A fresh install, and an upgrade from 0.6.x: the existing database is Household 1. */
        fun first(): HouseholdList = HouseholdList(listOf(HouseholdSlot(FIRST_ID, 1, FIRST_FILE)), FIRST_ID)

        fun fileFor(id: String): String = "household-$id.db"

        private fun idOfFile(file: String): String? = FILE.matchEntire(file)?.groupValues?.get(1)

        fun cleanName(name: String?): String? =
            name?.replace(Regex("[\\t\\r\\n]+"), " ")?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }

        /** The list from [encode]; null when [text] isn't one (then the caller starts from [first]). */
        fun decode(text: String?): HouseholdList? {
            val lines = text?.split('\n') ?: return null
            val head = lines.first().split('\t')
            val v2 = head[0] == FORMAT && head.size >= 3
            if (!v2 && !(head[0] == FORMAT_1 && head.size == 2)) return null
            return runCatching {
                val slots = lines.drop(1).map { line ->
                    val f = line.split('\t')
                    require(f.size == 4)
                    HouseholdSlot(f[0], f[1].toInt(), f[2], f[3].takeIf { it.isNotEmpty() })
                }
                if (v2) {
                    HouseholdList(slots, head[1], topNumber = head[2].toInt(), pendingDelete = head.drop(3).filter { it.isNotEmpty() }.toSet())
                } else {
                    HouseholdList(slots, head[1])
                }
            }.getOrNull()
        }
    }
}

/** How a phone joins a household (SPEC §9.4 "Joining"). */
enum class JoinWay {
    /** This database joins, with child matching: "you've both been logging the same baby". */
    MERGE,

    /** A new database for the household, made active; this one stays as it was. */
    APART,
}

object Joining {
    /**
     * The ways offered to a database whose household list is [members], on the phone [me]. Merging
     * only when no other phone is active in it (and this phone isn't leaving it), so it never carries
     * one household's children into another or slips out of a household that still expects it.
     */
    fun ways(members: Collection<Member>, me: String): List<JoinWay> =
        if (mayMerge(members, me)) listOf(JoinWay.MERGE, JoinWay.APART) else listOf(JoinWay.APART)

    fun mayMerge(members: Collection<Member>, me: String): Boolean =
        !Roster.leaving(members, me) && Roster.active(members).none { it.deviceId != me }

    /**
     * Without a question: a database holding only the untouched starter child ([blank]) that may
     * merge just merges (its child gives way to the household's); otherwise the owner chooses.
     */
    fun without(members: Collection<Member>, me: String, blank: Boolean): JoinWay? =
        if (blank && mayMerge(members, me)) JoinWay.MERGE else null
}
