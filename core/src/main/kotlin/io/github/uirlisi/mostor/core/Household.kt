package io.github.uirlisi.mostor.core

/** The editable fields of a child (SPEC §4.2). Every one is optional. */
data class ChildFields(
    val name: String? = null,
    val birthDate: String? = null,
    val sex: String? = null,
    val birthWeightG: Int? = null,
    val birthLengthMm: Int? = null,
    val color: Int? = null,
)

/** Children (synced, SPEC §6.6). Each operation returns the [Change] to write; empty = nothing to do. */
object Children {
    /** A new child, with their own seeded meds (Vit D and Vit K, As needed). */
    fun add(fields: ChildFields, sortKey: Int, cx: ChangeContext): Change {
        val c = Child(id = cx.newId(), sortKey = sortKey, createdAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc())
        return Change(children = listOf(RowChange(null, c.with(fields))), presets = Presets.seeds(c.id, cx.now).map { RowChange(null, it) })
    }

    fun edit(c: Child, fields: ChildFields, cx: ChangeContext): Change {
        val updated = c.with(fields)
        return if (updated == c) Change() else Change(children = listOf(RowChange(c, updated.touched(cx))))
    }

    /** Archived: off the quick sheet, still in the log and stats (SPEC §4.4). */
    fun setArchived(c: Child, archived: Boolean, cx: ChangeContext): Change {
        if ((c.archivedAt != null) == archived) return Change()
        return Change(children = listOf(RowChange(c, c.copy(archivedAt = if (archived) cx.now else null).touched(cx))))
    }

    /** Soft delete; the child's events are hidden with it (SPEC §4.4), not deleted. */
    fun delete(c: Child, cx: ChangeContext): Change =
        if (c.deletedAt != null) Change() else Change(children = listOf(RowChange(c, c.copy(deletedAt = cx.now).touched(cx))))

    private fun Child.with(f: ChildFields) = copy(
        name = f.name?.trim()?.takeIf { it.isNotEmpty() },
        birthDate = f.birthDate,
        sex = f.sex?.takeIf { it == "f" || it == "m" },
        birthWeightG = f.birthWeightG?.takeIf { it > 0 },
        birthLengthMm = f.birthLengthMm?.takeIf { it > 0 },
        color = f.color,
    )
}

/** A med's schedule as set in Settings › Meds (SPEC §5.10). */
data class MedPlan(
    val schedule: MedSchedule = MedSchedule.AS_NEEDED,
    /** Weekly: 1 = Monday … 7 = Sunday. */
    val weekday: Int? = null,
    /** Every N days: N ≥ 2. */
    val everyDays: Int? = null,
    /** The local date (yyyy-MM-dd) the schedule counts from. */
    val start: String? = null,
) {
    /** Only the fields the schedule uses; the start for every schedule but As needed. */
    fun normalized(today: String): MedPlan = when (schedule) {
        MedSchedule.AS_NEEDED -> MedPlan()
        MedSchedule.DAILY -> MedPlan(schedule, start = start ?: today)
        MedSchedule.WEEKLY -> MedPlan(schedule, weekday = (weekday ?: 1).coerceIn(1, 7), start = start ?: today)
        MedSchedule.EVERY_N_DAYS -> MedPlan(schedule, everyDays = (everyDays ?: 2).coerceAtLeast(2), start = start ?: today)
    }
}

/**
 * Meds (supplement presets; synced, SPEC §4.2, §6.6). Each belongs to one child. The app never
 * suggests an amount or a schedule: every med starts As needed.
 */
object Presets {
    /** The seeded meds' names, in order. Each child gets its own (schema version 2). */
    val SEED_NAMES = listOf("Vit D", "Vit K")

    /** Untouched seeds carry this stamp, so two phones' seeds for the same child are the same rows. */
    const val SEED_HLC = "000000000000-0000-seed"

    /**
     * A seeded med's id for [childId] and its [slot] (0 Vit D, 1 Vit K): derived, not random, so every
     * phone makes the same row for the same child. A name-based UUID (version 3).
     */
    fun seedId(childId: String, slot: Int): String =
        java.util.UUID.nameUUIDFromBytes("mostor:seed:$childId:$slot".toByteArray(Charsets.UTF_8)).toString()

    /** A child's seeded meds, As needed with no amount. */
    fun seeds(childId: String, now: Long): List<SupplementPreset> = SEED_NAMES.mapIndexed { slot, name ->
        SupplementPreset(seedId(childId, slot), childId, name, sortKey = slot, createdAt = now, updatedAt = now, hlc = SEED_HLC)
    }

    fun add(childId: String, name: String, amount: Double?, unit: String?, plan: MedPlan, sortKey: Int, today: String, cx: ChangeContext): Change {
        val p = SupplementPreset(
            id = cx.newId(), childId = childId, name = name.trim(), amount = amount, unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            sortKey = sortKey, createdAt = cx.now, updatedAt = cx.now, hlc = cx.nextHlc(),
        ).withPlan(plan.normalized(today))
        return Change(presets = listOf(RowChange(null, p)))
    }

    /**
     * Saves a med's fields. A schedule that changes counts from [today] unless [plan] has its own
     * start; an unchanged one keeps its start.
     */
    fun edit(p: SupplementPreset, name: String, amount: Double?, unit: String?, plan: MedPlan, today: String, cx: ChangeContext): Change {
        val current = p.plan()
        val wanted = plan.normalized(today)
        val samePlan = wanted.copy(start = null) == current.copy(start = null) && (plan.start == null || plan.start == current.start)
        val updated = p.copy(name = name.trim(), amount = amount, unit = unit?.trim()?.takeIf { it.isNotEmpty() })
            .withPlan(if (samePlan) current else wanted)
        return if (updated == p) Change() else Change(presets = listOf(RowChange(p, updated.touched(cx))))
    }

    fun delete(p: SupplementPreset, cx: ChangeContext): Change =
        if (p.deletedAt != null) Change() else Change(presets = listOf(RowChange(p, p.copy(deletedAt = cx.now).touched(cx))))

    /**
     * Moves the med at [from] to [to] in [ordered] (one child's live meds in their current order) and
     * renumbers the sort keys 0, 1, 2…, writing only the rows whose key changes.
     */
    fun move(ordered: List<SupplementPreset>, from: Int, to: Int, cx: ChangeContext): Change {
        if (from !in ordered.indices || to !in ordered.indices || from == to) return Change()
        val list = ordered.toMutableList().apply { add(to, removeAt(from)) }
        val rows = list.mapIndexedNotNull { i, p -> if (p.sortKey == i) null else RowChange(p, p.copy(sortKey = i).touched(cx)) }
        return Change(presets = rows)
    }

    private fun SupplementPreset.withPlan(m: MedPlan) =
        copy(schedule = m.schedule, scheduleWeekday = m.weekday, scheduleEveryDays = m.everyDays, scheduleStart = m.start)
}

/** A med's schedule fields as one value. */
fun SupplementPreset.plan(): MedPlan = MedPlan(schedule, scheduleWeekday, scheduleEveryDays, scheduleStart)
