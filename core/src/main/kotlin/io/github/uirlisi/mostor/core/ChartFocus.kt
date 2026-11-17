package io.github.uirlisi.mostor.core

/**
 * What the Patterns and Stats screens narrow to (SPEC §6.5: "poop only", "left side only"). One
 * choice at a time, like the Log's filters. A wet or poop focus includes mixed diapers, the same
 * rule as the day counts ([DaySummary.counts]).
 */
sealed interface ChartFocus {
    /** Whether [e] is drawn or counted. */
    fun matches(e: Event): Boolean

    /** Whether a breast segment of a matching feed is drawn ([OnSide] keeps one side only). */
    fun matches(s: BreastSegment): Boolean = true

    /** The kind this narrows to, or null for [All]. */
    val kind: Kind?

    /** A stable text form, for the per-phone setting that remembers it. */
    fun encode(): String = when (this) {
        All -> "all"
        is OfKind -> "kind:${kind.code}"
        is Subtype -> "sub:${kind.code}:$code"
        is OnSide -> "side:${side.code}"
        is Preset -> "preset:$presetId"
    }

    data object All : ChartFocus {
        override val kind: Kind? get() = null
        override fun matches(e: Event) = true
    }

    data class OfKind(override val kind: Kind) : ChartFocus {
        override fun matches(e: Event) = e.kind == kind
    }

    /** A subtype of a kind: a diaper type or a bottle content. */
    data class Subtype(override val kind: Kind, val code: String) : ChartFocus {
        override fun matches(e: Event): Boolean {
            if (e.kind != kind) return false
            if (kind == Kind.DIAPER && e.subtype == Diaper.MIXED.code) {
                return code == Diaper.MIXED.code || code == Diaper.WET.code || code == Diaper.POOP.code
            }
            return e.subtype == code
        }
    }

    /** Breast feeds, and only the segments on [side]. */
    data class OnSide(val side: Side) : ChartFocus {
        override val kind: Kind get() = Kind.BREAST
        override fun matches(e: Event) = e.kind == Kind.BREAST
        override fun matches(s: BreastSegment) = s.side == side
    }

    /** Supplements given from one preset. */
    data class Preset(val presetId: String) : ChartFocus {
        override val kind: Kind get() = Kind.SUPPLEMENT
        override fun matches(e: Event) = e.kind == Kind.SUPPLEMENT && e.supplementPresetId == presetId
    }

    companion object {
        /** Reads [encode]'s form back; null if it isn't one. */
        fun decode(s: String?): ChartFocus? {
            if (s == null) return null
            val p = s.split(':', limit = 3)
            fun kind(code: String?) = Kind.entries.firstOrNull { it.code == code }
            return when (p[0]) {
                "all" -> All
                "kind" -> kind(p.getOrNull(1))?.let(::OfKind)
                "sub" -> kind(p.getOrNull(1))?.let { k -> p.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Subtype(k, it) } }
                "side" -> Side.entries.firstOrNull { it.code == p.getOrNull(1) }?.let(::OnSide)
                "preset" -> p.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(::Preset)
                else -> null
            }
        }

        /**
         * The live records [focus] keeps. Records hidden on this phone are left out unless
         * [showHidden] (SPEC §6.6: hidden types stay in stats, behind a filter).
         */
        fun select(events: List<Event>, focus: ChartFocus, visibility: Visibility, showHidden: Boolean): List<Event> =
            events.filter { it.deletedAt == null && focus.matches(it) && (showHidden || visibility.shows(it)) }
    }
}
