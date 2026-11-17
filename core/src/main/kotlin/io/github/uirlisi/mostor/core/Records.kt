package io.github.uirlisi.mostor.core

/** Point records: everything that isn't a timer. Each returns a one-row [Change]. */
object Records {
    fun diaper(childId: String, type: Diaper, at: Long, cx: ChangeContext): Change =
        point(childId, Kind.DIAPER, at, cx, subtype = type.code)

    fun bottle(childId: String, content: BottleContent, amountMl: Double, at: Long, cx: ChangeContext): Change =
        point(childId, Kind.BOTTLE, at, cx, subtype = content.code, amountMl = amountMl)

    /** Copies the preset's name, amount and unit, so renaming the preset later doesn't rewrite history. */
    fun supplement(childId: String, preset: SupplementPreset, at: Long, cx: ChangeContext): Change =
        point(childId, Kind.SUPPLEMENT, at, cx) {
            it.copy(
                supplementPresetId = preset.id,
                supplementName = preset.name,
                supplementAmount = preset.amount,
                supplementUnit = preset.unit,
            )
        }

    fun pump(childId: String, amountMl: Double, at: Long, cx: ChangeContext): Change =
        point(childId, Kind.PUMP, at, cx, amountMl = amountMl)

    fun note(childId: String, text: String, at: Long, cx: ChangeContext): Change =
        point(childId, Kind.NOTE, at, cx) { it.copy(note = text) }

    private fun point(
        childId: String,
        kind: Kind,
        at: Long,
        cx: ChangeContext,
        subtype: String? = null,
        amountMl: Double? = null,
        extra: (Event) -> Event = { it },
    ): Change {
        require(!kind.timed)
        val event = Event(
            id = cx.newId(),
            childId = childId,
            kind = kind,
            subtype = subtype,
            startAt = at,
            amountMl = amountMl,
            tz = cx.tz,
            createdAt = cx.now,
            updatedAt = cx.now,
            hlc = cx.nextHlc(),
        )
        return Change(listOf(RowChange(null, extra(event))))
    }
}
