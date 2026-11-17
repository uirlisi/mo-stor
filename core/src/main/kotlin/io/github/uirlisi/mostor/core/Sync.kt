package io.github.uirlisi.mostor.core

/**
 * Bluetooth sync's data side (SPEC §9.3), independent of the transport.
 *
 * A **version vector** maps each origin (the device id at the end of a stamp, SPEC §4.1) to the
 * newest stamp from that origin a phone holds. It is computed from the rows, not stored: a session
 * applies everything it receives in one transaction, so a phone that holds a change stamped T by
 * origin O holds every change by O up to T that hasn't been overwritten since. A vector computed
 * from the rows can only be lower than what the phone has seen (an overwritten row drops out),
 * which makes a peer send a little more than needed, never less.
 *
 * A session: each phone sends its vector; each sends back the rows whose stamp is newer than the
 * other's entry for that row's origin; each merges what it gets ([Merge.plan]) and moves its clock
 * past the newest stamp it received. Rows a phone got from a third phone go on with their stamps,
 * so changes pass along A → B → C.
 *
 * Untouched seeded meds all carry the one stamp [Presets.SEED_HLC], made independently on every
 * phone for its own children, so that stamp says nothing about which seeds a phone holds: such rows
 * are always sent (a few tiny rows; the merge skips the ones already there) and the seed stamp never
 * enters a vector. Found by the convergence test.
 */
object Sync {
    /** The origin of an encoded stamp: what follows the wall time and the counter. */
    fun originOf(hlc: String): String = hlc.split('-', limit = 3).getOrElse(2) { "" }

    /** Per origin, the newest stamp in [rows]. */
    fun vector(rows: Merge.Rows): Map<String, String> {
        val v = HashMap<String, String>()
        for (row in rows.all()) {
            if (row.hlc == Presets.SEED_HLC) continue
            val origin = originOf(row.hlc)
            val seen = v[origin]
            if (seen == null || row.hlc > seen) v[origin] = row.hlc
        }
        return v
    }

    /** The rows a phone with vector [theirs] doesn't have: newer than its entry for their origin. */
    fun since(rows: Merge.Rows, theirs: Map<String, String>): Merge.Rows {
        fun <T : Synced> List<T>.newer() = filter { row ->
            row.hlc == Presets.SEED_HLC || theirs[originOf(row.hlc)].let { it == null || row.hlc > it }
        }
        return Merge.Rows(
            children = rows.children.newer(),
            events = rows.events.newer(),
            segments = rows.segments.newer(),
            presets = rows.presets.newer(),
        )
    }

    private fun Merge.Rows.all(): Sequence<Synced> = sequenceOf(children, events, segments, presets).flatten()
}

/**
 * Child matching when a phone joins a household (SPEC §9.4 step 4): this phone's child [mine] is
 * the same child as [theirs] from the phone it paired with. Runs on the joining phone, before the
 * first sync, as one local change:
 * - every one of [mine]'s events moves to [theirs];
 * - each of [mine]'s meds whose name matches one of [theirs]'s meds is tombstoned, and its doses
 *   move to that med; a med with no match moves to [theirs] as it is;
 * - [theirs] takes any field it lacks from [mine] (so a named child isn't lost to a blank one);
 * - [mine] is tombstoned.
 * [myEvents] and [myPresets] are [mine]'s rows (deleted ones may be included; they move too);
 * [theirPresets] are [theirs]'s meds.
 */
object ChildMatch {
    fun same(
        mine: Child,
        theirs: Child,
        myEvents: List<Event>,
        myPresets: List<SupplementPreset>,
        theirPresets: List<SupplementPreset>,
        cx: ChangeContext,
    ): Change {
        if (mine.id == theirs.id) return Change()
        val byName = theirPresets.filter { it.childId == theirs.id && it.deletedAt == null }
            .associateBy { key(it.name) }
        val presetChanges = ArrayList<RowChange<SupplementPreset>>()
        val movedTo = HashMap<String, String>() // my med id → their med id
        for (p in myPresets.filter { it.childId == mine.id }) {
            val match = byName[key(p.name)]
            if (match != null && p.deletedAt == null) {
                movedTo[p.id] = match.id
                presetChanges += RowChange(p, p.copy(deletedAt = cx.now).touched(cx))
            } else {
                presetChanges += RowChange(p, p.copy(childId = theirs.id).touched(cx))
            }
        }
        val eventChanges = myEvents.filter { it.childId == mine.id }.map { e ->
            val preset = e.supplementPresetId?.let { movedTo[it] } ?: e.supplementPresetId
            RowChange(e, e.copy(childId = theirs.id, supplementPresetId = preset).touched(cx))
        }
        val filled = theirs.copy(
            name = theirs.name ?: mine.name,
            birthDate = theirs.birthDate ?: mine.birthDate,
            sex = theirs.sex ?: mine.sex,
            birthWeightG = theirs.birthWeightG ?: mine.birthWeightG,
            birthLengthMm = theirs.birthLengthMm ?: mine.birthLengthMm,
            color = theirs.color ?: mine.color,
        )
        val childChanges = buildList {
            add(RowChange(mine, mine.copy(deletedAt = cx.now).touched(cx)))
            if (filled != theirs) add(RowChange(theirs, filled.touched(cx)))
        }
        return Change(events = eventChanges, children = childChanges, presets = presetChanges)
    }

    private fun key(name: String) = name.trim().lowercase()
}
