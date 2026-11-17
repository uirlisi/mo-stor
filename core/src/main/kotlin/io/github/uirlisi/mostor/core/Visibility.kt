package io.github.uirlisi.mostor.core

/**
 * What this phone shows (SPEC §6.6 "What's shown on this phone"). Hidden kinds, subtypes and
 * supplement presets leave the quick sheet and the summaries but stay in the log and stats, behind
 * a filter. Stored as one `setting` key per hidden item (see [key]); absent means shown.
 */
data class Visibility(
    val hiddenKinds: Set<Kind> = emptySet(),
    /** (kind, subtype code) pairs, e.g. (BOTTLE, "formula"), (DIAPER, "dry"). */
    val hiddenSubtypes: Set<Pair<Kind, String>> = emptySet(),
    val hiddenPresets: Set<String> = emptySet(),
) {
    fun shows(kind: Kind): Boolean = kind !in hiddenKinds

    fun shows(kind: Kind, subtype: String): Boolean = shows(kind) && (kind to subtype) !in hiddenSubtypes

    fun showsPreset(presetId: String): Boolean = shows(Kind.SUPPLEMENT) && presetId !in hiddenPresets

    /** Whether a record shows where hidden items are left out. */
    fun shows(e: Event): Boolean {
        if (!shows(e.kind)) return false
        val sub = e.subtype
        if (sub != null && (e.kind to sub) in hiddenSubtypes) return false
        val preset = e.supplementPresetId
        return preset == null || preset !in hiddenPresets
    }

    val bottleContents: List<BottleContent>
        get() = if (!shows(Kind.BOTTLE)) emptyList() else BottleContent.entries.filter { shows(Kind.BOTTLE, it.code) }

    val diapers: List<Diaper>
        get() = if (!shows(Kind.DIAPER)) emptyList() else Diaper.entries.filter { shows(Kind.DIAPER, it.code) }

    /** Whether anything is hidden at all (the log then offers "Show hidden types"). */
    val hidesAnything: Boolean get() = hiddenKinds.isNotEmpty() || hiddenSubtypes.isNotEmpty() || hiddenPresets.isNotEmpty()

    companion object {
        const val PREFIX = "hidden:"

        fun key(kind: Kind): String = "${PREFIX}kind:${kind.code}"

        fun key(kind: Kind, subtype: String): String = "${PREFIX}subtype:${kind.code}:$subtype"

        fun presetKey(presetId: String): String = "${PREFIX}preset:$presetId"

        /** Reads the hidden items from setting keys; unknown keys are ignored. */
        fun fromKeys(keys: Collection<String>): Visibility {
            val kinds = mutableSetOf<Kind>()
            val subtypes = mutableSetOf<Pair<Kind, String>>()
            val presets = mutableSetOf<String>()
            for (k in keys) {
                if (!k.startsWith(PREFIX)) continue
                val parts = k.removePrefix(PREFIX).split(':', limit = 3)
                when (parts[0]) {
                    "kind" -> parts.getOrNull(1)?.let { code -> Kind.entries.firstOrNull { it.code == code } }?.let(kinds::add)
                    "subtype" -> {
                        val kind = parts.getOrNull(1)?.let { code -> Kind.entries.firstOrNull { it.code == code } }
                        val sub = parts.getOrNull(2)
                        if (kind != null && !sub.isNullOrEmpty()) subtypes += kind to sub
                    }
                    "preset" -> parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(presets::add)
                }
            }
            return Visibility(kinds, subtypes, presets)
        }
    }
}
