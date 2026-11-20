package io.github.uirlisi.mostor.data

import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.OverrunLimits
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.core.VolumeUnit
import java.util.Locale

/** The theme choice in Settings (SPEC §11, Phase 6), stored by [code]. */
enum class ThemeChoice(val code: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

/**
 * Per-phone settings (SPEC §6.6), read from the device-local `setting` table. A missing key has
 * its spec default. The settings screen writes them through `Repository.setSetting`.
 */
data class SettingsSnapshot(
    val dayStartHour: Int,
    val breastLimits: OverrunLimits,
    val sleepLimits: OverrunLimits,
    val unit: VolumeUnit,
    val lockScreenSheet: Boolean,
    val hideDetailsOnLockScreen: Boolean,
    val keepNotification: Boolean,
    /**
     * The status notification was swiped away with [keepNotification] off: it stays away until the
     * app is opened or the phone restarts (DECISIONS, 2026-11-16).
     */
    val statusDismissed: Boolean = false,
    /** How long the quick sheet stays open after the last tap; null = until closed by hand. */
    val autoCloseMs: Long?,
    /** Light or dark on this phone (Phase 6); System follows the phone's dark mode. */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val lastBottleMl: Double?,
    val lastBottleContent: BottleContent,
    val lastPumpMl: Double?,
    /** Where the bottle and pump steppers start; null = the last amount used. */
    val defaultBottleMl: Double?,
    val defaultPumpMl: Double?,
    val activeChildId: String?,
    /**
     * What Today shows (only offered with several children; read with `ChildChoice.parseToday`):
     * [Keys.ALL_CHILDREN], a comma-separated list of two or more child ids (Phase 3c), or null for
     * the active child, the one this phone records for (picked on Today or in the quick sheet).
     */
    val todayChild: String?,
    /**
     * The child Patterns and Stats show (only offered with several children); null = the active
     * child. Picking it never changes who this phone records for.
     */
    val chartChild: String?,
    /** The children the Log shows: comma-separated ids; null or empty = all of them (Phase 3c). */
    val logChildren: String?,
    /** The kind the Log shows, with its subtype after a colon ("diaper:poop"); null = every kind. */
    val logKind: String?,
    val patternsRange: ChartRange,
    val patternsFocus: ChartFocus,
    val statsRange: ChartRange,
    val statsTopic: StatsTopic,
    /** A subtype, side or preset within [statsTopic], or [ChartFocus.All]. */
    val statsFocus: ChartFocus,
    val visibility: Visibility,
    /** "Still going" snoozes: event id → nudge again at. */
    val snoozes: Map<String, Long>,
    /** Events whose "no end" notification was dismissed. */
    val noEndDismissed: Set<String>,
    /** The daily backup's folder (a tree URI); null = off. */
    val autoExportUri: String? = null,
    /** The last day (yyyy-MM-dd) the daily backup was written. */
    val autoExportLast: String? = null,
    /** Whether the last attempt failed (the folder was moved, or its access withdrawn). */
    val autoExportFailed: Boolean = false,
    /** Sets of running timers kept on purpose ("Keep both"), by key. */
    val conflictsKept: Set<String> = emptySet(),
    /** Two-timer questions whose pop-up notification was swiped away, by key. */
    val conflictAlertsDismissed: Set<String> = emptySet(),
) {
    fun limitsFor(kind: Kind): OverrunLimits = if (kind == Kind.SLEEP) sleepLimits else breastLimits

    /** The bottle stepper's first amount: the default if set, else the last one used. */
    val bottleStartMl: Double? get() = defaultBottleMl ?: lastBottleMl

    val pumpStartMl: Double? get() = defaultPumpMl ?: lastPumpMl

    companion object {
        fun from(values: Map<String, String>, locale: Locale = Locale.getDefault()): SettingsSnapshot {
            fun int(key: String) = values[key]?.toIntOrNull()
            fun bool(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default

            /** Minutes, where a negative value means "never". */
            fun limit(key: String, default: Long?): Long? {
                val m = values[key]?.toLongOrNull() ?: return default
                return if (m < 0) null else m * 60_000L
            }
            return SettingsSnapshot(
                dayStartHour = int(Keys.DAY_START_HOUR)?.takeIf { it in 0..23 } ?: 0,
                breastLimits = OverrunLimits(
                    limit(Keys.NUDGE_BREAST_MIN, OverrunLimits.BREAST_DEFAULT.nudgeAfterMs),
                    limit(Keys.GIVE_UP_BREAST_MIN, OverrunLimits.BREAST_DEFAULT.giveUpAfterMs),
                ),
                sleepLimits = OverrunLimits(
                    limit(Keys.NUDGE_SLEEP_MIN, OverrunLimits.SLEEP_DEFAULT.nudgeAfterMs),
                    limit(Keys.GIVE_UP_SLEEP_MIN, OverrunLimits.SLEEP_DEFAULT.giveUpAfterMs),
                ),
                unit = VolumeUnit.ofOrNull(values[Keys.UNIT]) ?: VolumeUnit.defaultFor(locale.country),
                theme = ThemeChoice.entries.firstOrNull { it.code == values[Keys.THEME] } ?: ThemeChoice.SYSTEM,
                lockScreenSheet = bool(Keys.LOCK_SCREEN_SHEET, true),
                hideDetailsOnLockScreen = bool(Keys.HIDE_DETAILS, false),
                keepNotification = bool(Keys.KEEP_NOTIFICATION, true),
                statusDismissed = bool(Keys.STATUS_DISMISSED, false),
                autoCloseMs = when (val sec = int(Keys.AUTO_CLOSE_S)) {
                    null -> 4_000L
                    0 -> null // "Never"
                    else -> sec.coerceIn(1, 60) * 1_000L
                },
                lastBottleMl = values[Keys.LAST_BOTTLE_ML]?.toDoubleOrNull(),
                lastBottleContent = BottleContent.ofOrNull(values[Keys.LAST_BOTTLE_CONTENT]) ?: BottleContent.BREAST_MILK,
                lastPumpMl = values[Keys.LAST_PUMP_ML]?.toDoubleOrNull(),
                defaultBottleMl = values[Keys.DEFAULT_BOTTLE_ML]?.toDoubleOrNull()?.takeIf { it > 0 },
                defaultPumpMl = values[Keys.DEFAULT_PUMP_ML]?.toDoubleOrNull()?.takeIf { it > 0 },
                activeChildId = values[Keys.ACTIVE_CHILD],
                // Before today_child, a build had an "all children" switch: read it as "all".
                todayChild = values[Keys.TODAY_CHILD] ?: Keys.ALL_CHILDREN.takeIf { bool(Keys.TODAY_ALL_CHILDREN, false) },
                chartChild = values[Keys.CHART_CHILD],
                logChildren = values[Keys.LOG_CHILDREN],
                logKind = values[Keys.LOG_KIND],
                patternsRange = ChartRange.ofOrNull(values[Keys.PATTERNS_RANGE])?.takeIf { it in ChartRange.PATTERNS } ?: ChartRange.D14,
                patternsFocus = ChartFocus.decode(values[Keys.PATTERNS_FOCUS]) ?: ChartFocus.All,
                statsRange = ChartRange.ofOrNull(values[Keys.STATS_RANGE])?.takeIf { it in ChartRange.STATS } ?: ChartRange.D14,
                statsTopic = StatsTopic.ofOrNull(values[Keys.STATS_TOPIC]) ?: StatsTopic.FEEDS,
                statsFocus = ChartFocus.decode(values[Keys.STATS_FOCUS]) ?: ChartFocus.All,
                visibility = Visibility.fromKeys(values.keys),
                snoozes = values.filterKeys { it.startsWith(Keys.SNOOZE_PREFIX) }
                    .mapNotNull { (k, v) -> v.toLongOrNull()?.let { k.removePrefix(Keys.SNOOZE_PREFIX) to it } }.toMap(),
                noEndDismissed = values.keys.filter { it.startsWith(Keys.NO_END_DISMISSED_PREFIX) }
                    .map { it.removePrefix(Keys.NO_END_DISMISSED_PREFIX) }.toSet(),
                autoExportUri = values[Keys.AUTO_EXPORT_URI],
                autoExportLast = values[Keys.AUTO_EXPORT_LAST],
                autoExportFailed = values[Keys.AUTO_EXPORT_STATUS] == "failed",
                conflictsKept = values.keys.filter { it.startsWith(Keys.CONFLICT_KEPT_PREFIX) }
                    .map { it.removePrefix(Keys.CONFLICT_KEPT_PREFIX) }.toSet(),
                conflictAlertsDismissed = values.keys.filter { it.startsWith(Keys.CONFLICT_ALERT_DISMISSED_PREFIX) }
                    .map { it.removePrefix(Keys.CONFLICT_ALERT_DISMISSED_PREFIX) }.toSet(),
            )
        }
    }
}

object Keys {
    const val DAY_START_HOUR = "day_start_hour"
    const val NUDGE_BREAST_MIN = "nudge_breast_min"
    const val GIVE_UP_BREAST_MIN = "give_up_breast_min"
    const val NUDGE_SLEEP_MIN = "nudge_sleep_min"
    const val GIVE_UP_SLEEP_MIN = "give_up_sleep_min"
    const val UNIT = "volume_unit"
    const val LOCK_SCREEN_SHEET = "lock_screen_sheet"
    const val HIDE_DETAILS = "lock_screen_hide_details"
    const val KEEP_NOTIFICATION = "keep_notification"
    const val STATUS_DISMISSED = "status_dismissed"
    const val AUTO_CLOSE_S = "sheet_auto_close_s"
    const val THEME = "theme"
    const val LAST_BOTTLE_ML = "last_bottle_ml"
    const val LAST_BOTTLE_CONTENT = "last_bottle_content"
    const val LAST_PUMP_ML = "last_pump_ml"
    const val DEFAULT_BOTTLE_ML = "default_bottle_ml"
    const val DEFAULT_PUMP_ML = "default_pump_ml"
    const val TODAY_ALL_CHILDREN = "today_all_children"
    const val TODAY_CHILD = "today_child"

    /** The [TODAY_CHILD] value for every child. */
    const val ALL_CHILDREN = io.github.uirlisi.mostor.core.ChildChoice.ALL
    const val ACTIVE_CHILD = "active_child"
    const val CHART_CHILD = "chart_child"
    const val LOG_CHILDREN = "log_children"
    const val LOG_KIND = "log_kind"
    const val PATTERNS_RANGE = "patterns_range"
    const val PATTERNS_FOCUS = "patterns_focus"
    const val STATS_RANGE = "stats_range"
    const val STATS_TOPIC = "stats_topic"
    const val STATS_FOCUS = "stats_focus"
    const val PRESETS_SEEDED = "presets_seeded"
    const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    const val SNOOZE_PREFIX = "snooze:"
    const val NO_END_DISMISSED_PREFIX = "no_end_dismissed:"

    /**
     * This install's id, also kept in a file Android never backs up. A database that arrives with
     * another install's id came from another phone (DECISIONS 2026-11-14, "A moved database").
     */
    const val INSTALL_ID = "install_id"

    /** The folder the daily backup goes to (a tree URI), and the last day it ran (yyyy-MM-dd). */
    const val AUTO_EXPORT_URI = "auto_export_uri"
    const val AUTO_EXPORT_LAST = "auto_export_last"

    /** "ok" or "failed": how the last daily backup went, for Settings. */
    const val AUTO_EXPORT_STATUS = "auto_export_status"

    /** The household this phone syncs in (SPEC §9.4): its id, and its key wrapped by the Keystore. */
    const val HOUSEHOLD_ID = "household_id"
    const val HOUSEHOLD_KEY = "household_key"

    /**
     * After joining, the other phone's children and meds (as JSON backup rows) until the owner has
     * matched children (SPEC §9.4 step 4). No session runs meanwhile.
     */
    const val SYNC_PENDING_MATCH = "sync_pending_match"

    /**
     * Phones this one removed from the household that haven't been told yet (JSON: device id → when),
     * so this phone keeps listening for them for a while (SPEC §9.4).
     */
    const val SYNC_FAREWELL = "sync_farewell"

    /** After another phone removed this one: that phone's name, shown once in Settings › Sync. */
    const val SYNC_REMOVED_BY = "sync_removed_by"

    /**
     * After this phone left its household by itself, shown once in Settings › Sync: "1" when it told
     * one of its phones, "later" when it will tell them the next time one is near ([SYNC_PARTING]).
     */
    const val SYNC_LEFT = "sync_left"

    /**
     * After leaving without reaching another phone (SPEC §9.4): the old household's id, its key
     * (wrapped by the Keystore), this phone's "left" row and until when it may still tell them (JSON).
     */
    const val SYNC_PARTING = "sync_parting"

    /** Another phone's leaving shown in Settings › Sync (by device id; the value is when it left). */
    const val DEPARTED_SEEN_PREFIX = "departed_seen:"

    /** Running timers the owner chose to keep both of (SPEC §9.5), by `TimerConflict.key`. */
    const val CONFLICT_KEPT_PREFIX = "conflict_kept:"

    /** Two-timer questions whose pop-up notification was swiped away, by `TimerConflict.key`. */
    const val CONFLICT_ALERT_DISMISSED_PREFIX = "conflict_alert_dismissed:"
}
