package io.github.uirlisi.mostor.data

import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.DayPoint
import io.github.uirlisi.mostor.core.GapDay
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.PatternDay
import io.github.uirlisi.mostor.core.SupplementPreset
import java.time.LocalDate

/** How many days the Patterns and Stats screens show (SPEC §6.5). [ALL] is from the first record. */
enum class ChartRange(val code: String, val days: Int?) {
    D7("7", 7),
    D14("14", 14),
    D30("30", 30),
    D90("90", 90),
    ALL("all", null),
    ;

    companion object {
        fun ofOrNull(code: String?): ChartRange? = entries.firstOrNull { it.code == code }

        val PATTERNS = listOf(D7, D14, D30, ALL)
        val STATS = listOf(D7, D14, D30, D90)
    }
}

/** What the Stats screen charts: one topic at a time, each with its own charts. */
enum class StatsTopic(val code: String, val kinds: Set<Kind>) {
    FEEDS("feeds", setOf(Kind.BREAST, Kind.BOTTLE)),
    SLEEP("sleep", setOf(Kind.SLEEP)),
    DIAPER("diaper", setOf(Kind.DIAPER)),
    BOTTLE("bottle", setOf(Kind.BOTTLE)),
    PUMP("pump", setOf(Kind.PUMP)),
    SUPPLEMENT("supplement", setOf(Kind.SUPPLEMENT)),
    ;

    companion object {
        fun ofOrNull(code: String?): StatsTopic? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the Patterns and Stats screens draw, for one child ([childId]) over [from]..[to] (oldest
 * first). [points] are the per-day series (the same day summaries as Today); [patterns] and [gaps]
 * are only filled when asked for. [children] are every live child, in order: chips show only with
 * two or more, and a child's place in the list picks its color (as `HomeState.colorIndex`).
 */
data class ChartView(
    val now: Long,
    val clock: DayClock,
    val today: LocalDate,
    val from: LocalDate,
    val to: LocalDate,
    val childId: String,
    val children: List<Child>,
    /** The day of the child's first record, or null with none. */
    val firstDay: LocalDate?,
    val points: List<DayPoint>,
    val patterns: List<PatternDay>,
    val gaps: List<GapDay>,
    /** Records in range left out because they are hidden on this phone. */
    val hiddenCount: Int,
    val presets: List<SupplementPreset>,
    val settings: SettingsSnapshot,
) {
    fun colorIndex(childId: String): Int = children.indexOfFirst { it.id == childId }
}
