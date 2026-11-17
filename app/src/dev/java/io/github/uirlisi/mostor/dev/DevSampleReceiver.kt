package io.github.uirlisi.mostor.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.ChangeContext
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Records
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimedEvent
import io.github.uirlisi.mostor.core.Timers
import io.github.uirlisi.mostor.data.toEntity
import io.github.uirlisi.mostor.data.toModel
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Dev builds only: fills the last `days` days (default 21) with made-up but plausible records for
 * the active child, to look at Patterns and Stats: night sleeps across midnight, naps, feeds with
 * side switches, diapers, Vit D, some bottles and pumps, and one feed whose end is unknown. Nothing
 * runs and nothing goes past now. The rows are stamped with the origin `dev-sample`, and
 * `--ez remove true` soft-deletes exactly those rows again.
 *
 *     adb shell am broadcast -a io.github.uirlisi.mostor.dev.ADD_SAMPLES --ei days 21 \
 *         -n io.github.uirlisi.mostor.dev/io.github.uirlisi.mostor.dev.DevSampleReceiver
 */
class DevSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val days = intent.getIntExtra("days", 21).coerceIn(1, 120)
        val remove = intent.getBooleanExtra("remove", false)
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val dao = c.db.events()
                if (remove) {
                    val now = System.currentTimeMillis()
                    val mine = dao.inRange(0, Long.MAX_VALUE).filter { it.hlc.endsWith(ORIGIN) }
                    dao.upsertEvents(mine.map { it.copy(deletedAt = now, updatedAt = now) })
                } else {
                    val childId = c.repository.homeState().childId
                    val vitD = c.db.presets().live().map { it.toModel() }.firstOrNull { it.childId == childId && it.name.startsWith("Vit D") }
                    val (events, segments) = generate(childId, vitD, days)
                    dao.upsertEvents(events.map { it.toEntity() })
                    dao.upsertSegments(segments.map { it.toEntity() })
                }
                c.notifier.refresh()
            } finally {
                pending.finish()
            }
        }
    }

    private class Cx(override var now: Long, override val tz: String) : ChangeContext {
        private var n = 0
        override fun newId(): String = Ids.uuidV7(now)
        override fun nextHlc(): String = HlcStamp(System.currentTimeMillis() + n / 65_536, n++ % 65_536, ORIGIN).encode()
    }

    private fun generate(
        childId: String,
        vitD: io.github.uirlisi.mostor.core.SupplementPreset?,
        days: Int,
    ): Pair<Collection<Event>, Collection<BreastSegment>> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val cx = Cx(now, zone.id)
        val rnd = Random(days)
        val events = linkedMapOf<String, Event>()
        val segments = linkedMapOf<String, BreastSegment>()
        fun apply(ch: Change): Change {
            ch.events.forEach { events[it.after.id] = it.after }
            ch.segments.forEach { segments[it.after.id] = it.after }
            return ch
        }
        fun timed(id: String) = TimedEvent.of(events.getValue(id), segments.values.toList())
        fun at(day: LocalDate, minutes: Int): Long =
            day.atStartOfDay(zone).toLocalDateTime().plusMinutes(minutes.toLong()).atZone(zone).toInstant().toEpochMilli()
        fun jitter(m: Int) = m + rnd.nextInt(-30, 31)

        val today = LocalDate.now(zone)
        var side = Side.LEFT
        for (d in days downTo 0) {
            val day = today.minusDays(d.toLong())
            // Sleep: naps, then the night in two stretches across midnight.
            for ((from, len) in listOf(570 to 75, 780 to 90, 960 to 40, 1230 to 300, 1575 to 200)) {
                val start = at(day, jitter(from))
                val end = start + (len + rnd.nextInt(-20, 21)) * 60_000L
                if (end > now) continue
                cx.now = start
                val id = apply(Timers.start(Kind.SLEEP, childId, start, cx)).events.single().after.id
                cx.now = end
                apply(Timers.stop(timed(id), end, cx))
            }
            // Feeds about every three hours; most switch side halfway.
            for (from in listOf(105, 345, 510, 690, 870, 1050, 1200)) {
                val start = at(day, jitter(from))
                val len = rnd.nextInt(10, 26)
                val end = start + len * 60_000L
                if (end > now) continue
                cx.now = start
                val id = apply(Timers.start(Kind.BREAST, childId, start, cx, side)).events.single().after.id
                if (d == 3 && from == 870) {
                    apply(Timers.giveUp(timed(id), cx))
                    continue
                }
                if (rnd.nextInt(10) < 6) apply(Timers.switchSide(timed(id), start + len / 2 * 60_000L, cx)).also { side = side.other }
                cx.now = end
                apply(Timers.stop(timed(id), end, cx))
                side = side.other
                if (rnd.nextInt(10) < 8) {
                    val type = when (rnd.nextInt(20)) {
                        in 0..11 -> Diaper.WET
                        in 12..14 -> Diaper.POOP
                        in 15..18 -> Diaper.MIXED
                        else -> Diaper.DRY
                    }
                    apply(Records.diaper(childId, type, end + 5 * 60_000L, cx))
                }
            }
            val nine = at(day, jitter(540))
            if (vitD != null && nine <= now) apply(Records.supplement(childId, vitD, nine, cx))
            val bottle = at(day, jitter(1110))
            if (d % 2 == 0 && bottle <= now) {
                val content = if (d % 6 == 0) BottleContent.FORMULA else BottleContent.BREAST_MILK
                apply(Records.bottle(childId, content, rnd.nextInt(6, 10) * 10.0, bottle, cx))
            }
            val pump = at(day, jitter(600))
            if (d % 3 == 0 && pump <= now) apply(Records.pump(childId, rnd.nextInt(8, 15) * 10.0, pump, cx))
        }
        return events.values to segments.values
    }

    private companion object {
        const val ORIGIN = "dev-sample"
    }
}
