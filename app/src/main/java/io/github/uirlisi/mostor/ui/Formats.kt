package io.github.uirlisi.mostor.ui

import android.content.Context
import android.text.format.DateFormat
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Elapsed
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.ActionInfo
import io.github.uirlisi.mostor.data.Verb
import java.text.DecimalFormat
import java.util.Date

/** Turns model values into user-facing text. All words come from string resources. */
class Formats(private val context: Context) {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** A plural string resource chosen by [count], formatted with [args]. */
    fun plural(id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)

    /** A string resource, for messages built outside composition. */
    fun string(id: Int, vararg args: Any): String = s(id, *args)

    /** A clock time inside a template such as "%1$s (+1 day)". */
    fun timeWithDay(ms: Long, template: Int): String = s(template, time(ms))

    /** Clock time in the phone's 12/24-hour setting. */
    fun time(ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    /** "Wed, 11 Nov" in the phone's locale. */
    fun date(ms: Long): String = android.text.format.DateUtils.formatDateTime(
        context, ms,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
            android.text.format.DateUtils.FORMAT_ABBREV_ALL,
    )

    /** "Nov 14, 2026, 9:16 AM": date and time in the phone's locale and 12/24-hour setting. */
    fun dateTime(ms: Long): String = android.text.format.DateUtils.formatDateTime(
        context, ms,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR or
            android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
    )

    /** "2h 40m" or "40m". */
    fun duration(ms: Long): String {
        val e = Elapsed.of(ms)
        return if (e.hours > 0) s(R.string.duration_hm, e.hours, e.minutes) else s(R.string.duration_m, e.minutes)
    }

    /** "just now", "40m ago", "2h 10m ago", "1d 3h ago". */
    fun ago(ms: Long): String {
        val e = Elapsed.of(ms)
        return when {
            e.hours == 0L && e.minutes == 0 -> s(R.string.ago_now)
            e.hours >= 24 -> s(R.string.ago, s(R.string.duration_dh, e.hours / 24, e.hours % 24))
            else -> s(R.string.ago, duration(ms))
        }
    }

    /** "Wet · 13:40 · 45m ago" (no label when [what] is null). */
    fun since(what: String?, at: Long, now: Long): String = listOfNotNull(what, time(at), ago(now - at)).joinToString(" · ")

    /** "R · 14:02 · 2h 10m ago", or "Bottle · 14:02 · …"; null before the first feed. */
    fun sinceLastFeed(st: HomeState, now: Long): String? {
        val feed = st.lastFeed ?: return null
        val what = if (feed.kind == Kind.BREAST) st.lastSide?.let(::sideShort) else s(R.string.sheet_bottle)
        return since(what, feed.startAt, now)
    }

    /** "Sat" for a day within the next or last six days, else "Nov 25" (Meds, SPEC §5.10). */
    fun nearDay(day: java.time.LocalDate, today: java.time.LocalDate): String {
        val ms = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val near = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, day)) <= 6
        val flags = if (near) {
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY
        } else {
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH
        }
        return android.text.format.DateUtils.formatDateTime(context, ms, flags)
    }

    /** "1:02:05" or "12:05", for a live timer. */
    fun elapsed(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1_000
        val h = total / 3_600
        val m = (total % 3_600) / 60
        val sec = total % 60
        return if (h > 0) s(R.string.elapsed_hms, h, m, sec) else s(R.string.elapsed_ms, m, sec)
    }

    fun volumeNumber(ml: Double, unit: VolumeUnit): String {
        val v = Volume.snapped(ml, unit)
        return if (unit == VolumeUnit.ML) DecimalFormat("0").format(v) else DecimalFormat("0.#").format(v)
    }

    fun volume(ml: Double, unit: VolumeUnit): String =
        s(if (unit == VolumeUnit.ML) R.string.unit_ml else R.string.unit_fl_oz, volumeNumber(ml, unit))

    fun sideShort(side: Side): String = s(if (side == Side.LEFT) R.string.side_l else R.string.side_r)

    fun sideLong(side: Side): String = s(if (side == Side.LEFT) R.string.side_left else R.string.side_right)

    fun diaper(d: Diaper): String = s(
        when (d) {
            Diaper.WET -> R.string.diaper_wet
            Diaper.POOP -> R.string.diaper_poop
            Diaper.MIXED -> R.string.diaper_mixed
            Diaper.DRY -> R.string.diaper_dry
        },
    )

    fun bottleContent(c: BottleContent): String =
        s(if (c == BottleContent.FORMULA) R.string.bottle_formula else R.string.bottle_breast_milk)

    /** "Feed" or "Sleep". */
    fun timedKind(kind: Kind): String = s(if (kind == Kind.SLEEP) R.string.kind_sleep else R.string.kind_feed)

    fun supplement(name: String?, amount: Double?, unit: String?): String {
        val n = name ?: s(R.string.kind_supplement)
        if (amount == null) return n
        val a = DecimalFormat("0.##").format(amount)
        return s(R.string.label_supplement_amount, n, if (unit.isNullOrBlank()) a else "$a $unit")
    }

    /** A record's name in a sentence: "Wet diaper", "Feed", "Bottle", "Vit D". */
    fun what(kind: Kind?, subtype: String?, name: String?): String = when (kind) {
        Kind.DIAPER -> Diaper.ofOrNull(subtype)?.let { s(R.string.label_diaper, diaper(it)) } ?: s(R.string.kind_diaper)
        Kind.BOTTLE -> s(R.string.sheet_bottle)
        Kind.SUPPLEMENT -> name ?: s(R.string.kind_supplement)
        Kind.NOTE -> s(R.string.label_note)
        Kind.PUMP -> s(R.string.sheet_pump)
        Kind.BREAST -> s(R.string.kind_feed)
        Kind.SLEEP -> s(R.string.kind_sleep)
        null -> name ?: ""
    }

    /** A child's name, or "Baby" when it has none (SPEC §4.2). */
    fun childName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: s(R.string.child_unnamed)

    /** What an action did: "Wet diaper", "Feed started · Left", "Woke up"… */
    fun action(info: ActionInfo, unit: VolumeUnit): String = when (info.verb) {
        Verb.RECORD -> when (info.kind) {
            Kind.BOTTLE -> s(
                R.string.label_bottle,
                bottleContent(BottleContent.ofOrNull(info.subtype) ?: BottleContent.BREAST_MILK),
                volume(info.amountMl ?: 0.0, unit),
            )
            Kind.PUMP -> s(R.string.label_pump, volume(info.amountMl ?: 0.0, unit))
            Kind.BREAST, Kind.SLEEP -> timedKind(info.kind)
            else -> what(info.kind, info.subtype, info.name)
        }
        Verb.START ->
            if (info.kind == Kind.BREAST) s(R.string.label_feed_started, sideLong(info.side ?: Side.LEFT)) else s(R.string.label_asleep)
        Verb.SWITCH -> s(R.string.label_switched, sideLong(info.side ?: Side.LEFT))
        Verb.STOP -> s(if (info.kind == Kind.BREAST) R.string.label_feed_stopped else R.string.label_woke_up)
        Verb.CANCEL -> s(if (info.kind == Kind.BREAST) R.string.label_feed_cancelled else R.string.label_sleep_cancelled)
        Verb.SET_END -> s(if (info.kind == Kind.BREAST) R.string.label_feed_end_set else R.string.label_sleep_end_set)
        Verb.PAUSE -> s(R.string.label_feed_paused)
        Verb.RESUME -> s(R.string.label_feed_resumed, sideLong(info.side ?: Side.LEFT))
        Verb.IMPORT -> (info.count ?: 0).let { context.resources.getQuantityString(R.plurals.label_imported, it, it) }
        Verb.EDIT -> s(R.string.label_edited, what(info.kind, info.subtype, info.name))
        Verb.ADD -> s(R.string.label_added, what(info.kind, info.subtype, info.name))
        Verb.DELETE -> s(R.string.label_deleted, what(info.kind, info.subtype, info.name))
        Verb.CHILD_ADD -> s(R.string.label_added, childName(info.name))
        Verb.CHILD_EDIT, Verb.PRESET_EDIT -> s(R.string.label_saved, childOrPreset(info))
        Verb.CHILD_ARCHIVE -> s(R.string.label_archived, childName(info.name))
        Verb.CHILD_UNARCHIVE -> s(R.string.label_unarchived, childName(info.name))
        Verb.CHILD_DELETE -> s(R.string.label_deleted, childName(info.name))
        Verb.PRESET_ADD -> s(R.string.label_added, info.name.orEmpty())
        Verb.PRESET_DELETE -> s(R.string.label_deleted, info.name.orEmpty())
        Verb.PRESET_MOVE -> s(R.string.label_moved, info.name.orEmpty())
        Verb.SKIP -> s(R.string.label_skipped, info.name.orEmpty())
    }

    private fun childOrPreset(info: ActionInfo): String = if (info.verb == Verb.CHILD_EDIT) childName(info.name) else info.name.orEmpty()

    /** One line of the day log, without the time. */
    /**
     * A timeline row's parts (Phase 3b): the [title] ("Feed · L→R", "Wet diaper"), a [detail] for the
     * second line ("until 2:45 PM · L 15m · R 8m", "running", "no end"), and the [duration] shown on
     * the right for timed records.
     */
    data class LogParts(val title: String, val detail: String?, val duration: String?)

    fun logParts(e: Event, timed: TimedEvent?, now: Long, unit: VolumeUnit): LogParts {
        val state = when (e.status) {
            EventStatus.RUNNING -> s(if (timed?.isPaused == true) R.string.log_paused else R.string.log_running)
            EventStatus.END_UNKNOWN -> s(R.string.log_no_end)
            else -> null
        }
        val until = e.endAt?.takeIf { e.status == EventStatus.ENDED }?.let { s(R.string.log_until, time(it)) }
        return when (e.kind) {
            Kind.BREAST -> {
                val segs = timed?.segments.orEmpty()
                val totals = Durations.breast(segs, now)
                val order = segs.map { it.side }.fold(emptyList<Side>()) { acc, side -> if (acc.lastOrNull() == side) acc else acc + side }
                val perSide = if (segs.map { it.side }.distinct().size > 1 && e.status != EventStatus.END_UNKNOWN) {
                    listOf(
                        s(R.string.log_side_time, sideShort(Side.LEFT), duration(totals.leftMs)),
                        s(R.string.log_side_time, sideShort(Side.RIGHT), duration(totals.rightMs)),
                    )
                } else emptyList()
                LogParts(
                    s(R.string.log_feed, order.joinToString("→") { sideShort(it) }),
                    (listOfNotNull(until, state) + perSide).joinToString(" · ").ifEmpty { null },
                    if (e.status == EventStatus.END_UNKNOWN) null else duration(totals.totalMs),
                )
            }
            Kind.SLEEP -> LogParts(
                s(R.string.kind_sleep),
                listOfNotNull(until, state).joinToString(" · ").ifEmpty { null },
                timed?.takeIf { e.status != EventStatus.END_UNKNOWN }?.let { duration(Durations.of(it, now) ?: 0) },
            )
            // A skipped med dose says so under its name (SPEC §5.10).
            else -> LogParts(logLine(e, timed, now, unit), s(R.string.meds_skipped).takeIf { e.status == EventStatus.SKIPPED }, null)
        }
    }

    fun logLine(e: Event, timed: TimedEvent?, now: Long, unit: VolumeUnit): String {
        val suffix = when (e.status) {
            EventStatus.RUNNING -> " · " + s(if (timed?.isPaused == true) R.string.log_paused else R.string.log_running)
            EventStatus.END_UNKNOWN -> " · " + s(R.string.log_no_end)
            else -> ""
        }
        val main = when (e.kind) {
            Kind.BREAST -> {
                val segs = timed?.segments.orEmpty()
                val totals = Durations.breast(segs, now)
                val parts = buildList {
                    if (segs.any { it.side == Side.LEFT }) add(s(R.string.log_side_time, sideShort(Side.LEFT), duration(totals.leftMs)))
                    if (segs.any { it.side == Side.RIGHT }) add(s(R.string.log_side_time, sideShort(Side.RIGHT), duration(totals.rightMs)))
                }
                val sides = if (e.status == EventStatus.END_UNKNOWN) segs.joinToString("→") { sideShort(it.side) } else parts.joinToString(" · ")
                s(R.string.log_feed, sides)
            }
            Kind.SLEEP -> {
                val d = timed?.let { Durations.of(it, now) }
                val end = e.endAt
                when {
                    e.status == EventStatus.ENDED && end != null -> s(R.string.log_sleep_ended, time(end), duration(d ?: 0))
                    d != null -> s(R.string.log_sleep, duration(d))
                    else -> s(R.string.kind_sleep)
                }
            }
            Kind.BOTTLE -> s(
                R.string.label_bottle,
                bottleContent(BottleContent.ofOrNull(e.subtype) ?: BottleContent.BREAST_MILK),
                volume(e.amountMl ?: 0.0, unit),
            )
            Kind.SUPPLEMENT -> supplement(e.supplementName, e.supplementAmount, e.supplementUnit)
            Kind.DIAPER -> s(R.string.label_diaper, diaper(Diaper.ofOrNull(e.subtype) ?: Diaper.WET))
            Kind.PUMP -> s(R.string.label_pump, volume(e.amountMl ?: 0.0, unit))
            Kind.NOTE -> s(R.string.log_note, e.note.orEmpty())
        }
        return main + suffix
    }
}
