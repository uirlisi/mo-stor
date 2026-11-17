package io.github.uirlisi.mostor.core

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pattern grid (SPEC §5.8): clock-time rows, DST days, midnight, running timers, unknown ends. */
class PatternsTest {
    private val midnight = DayClock(0, DUBLIN)
    private val cx = FakeCx(dublin(2026, 1, 1, 0))
    private val table = Table()
    private val nov10 = LocalDate.of(2026, 11, 10)
    private val nov11 = LocalDate.of(2026, 11, 11)

    private fun t(d: Int, h: Int, mi: Int = 0) = dublin(2026, 11, d, h, mi)

    private fun sleep(from: Long, to: Long?): String {
        val id = table.apply(Timers.start(Kind.SLEEP, CHILD, from, cx)).newEventId()
        if (to != null) table.apply(Timers.stop(table.timed(id), to, cx))
        return id
    }

    private fun grid(from: LocalDate, to: LocalDate, now: Long, clock: DayClock = midnight, focus: ChartFocus = ChartFocus.All) =
        Patterns.grid(
            ChartFocus.select(table.events.values.toList(), focus, Visibility(), showHidden = false),
            table.segments.values.toList(), from, to, clock, now, focus,
        )

    private fun bars(d: PatternDay) = d.bars.map { Triple(it.fromMin, it.toMin, it.side) }

    @Test
    fun `a sleep across midnight is drawn on both rows with continuation flags`() {
        val id = sleep(t(10, 21, 30), t(11, 6, 10))
        val rows = grid(nov10, nov11, t(11, 20))
        assertEquals(listOf(nov10, nov11), rows.map { it.day })
        assertEquals(listOf(PatternBar(id, Kind.SLEEP, null, 21 * 60 + 30, 1440, toNext = true)), rows[0].bars)
        assertEquals(listOf(PatternBar(id, Kind.SLEEP, null, 0, 6 * 60 + 10, fromPrevious = true)), rows[1].bars)
        assertEquals(24, rows[0].hours)
    }

    @Test
    fun `a record that started before the range still shows its part in the range`() {
        sleep(t(9, 23), t(10, 2))
        val rows = grid(nov10, nov10, t(11, 20))
        assertEquals(listOf(Triple(0, 120, null)), bars(rows.single()))
    }

    @Test
    fun `a running sleep is drawn up to now, an unknown end only as a start mark`() {
        val running = sleep(t(11, 13), null)
        val unknown = sleep(t(11, 9), null)
        table.apply(Timers.giveUp(table.timed(unknown), cx))
        val row = grid(nov11, nov11, t(11, 14, 30)).single()
        assertEquals(listOf(PatternBar(running, Kind.SLEEP, null, 780, 870, running = true)), row.bars)
        assertEquals(listOf(PatternMark(unknown, Kind.SLEEP, null, 540, unknownEnd = true)), row.marks)
    }

    @Test
    fun `a running sleep across midnight is running only on the last row`() {
        sleep(t(10, 22), null)
        val rows = grid(nov10, nov11, t(11, 1))
        assertEquals(listOf(false), rows[0].bars.map { it.running })
        assertEquals(listOf(true), rows[1].bars.map { it.running })
        assertTrue(rows[0].bars.single().toNext)
    }

    @Test
    fun `breast feeds are drawn per segment, by side, with pauses as gaps`() {
        val f = table.apply(Timers.start(Kind.BREAST, CHILD, t(11, 8), cx, Side.LEFT)).newEventId()
        table.apply(Timers.switchSide(table.timed(f), t(11, 8, 10), cx))
        table.apply(Timers.pause(table.timed(f), t(11, 8, 25), cx))
        table.apply(Timers.resume(table.timed(f), Side.LEFT, t(11, 8, 40), cx))
        val row = grid(nov11, nov11, t(11, 8, 50)).single()
        assertEquals(
            listOf(Triple(480, 490, Side.LEFT), Triple(490, 505, Side.RIGHT), Triple(520, 530, Side.LEFT)),
            bars(row),
        )
        assertEquals(listOf(false, false, true), row.bars.map { it.running })
    }

    @Test
    fun `left side only keeps the left segments`() {
        val f = table.apply(Timers.start(Kind.BREAST, CHILD, t(11, 8), cx, Side.LEFT)).newEventId()
        table.apply(Timers.switchSide(table.timed(f), t(11, 8, 10), cx))
        table.apply(Timers.stop(table.timed(f), t(11, 8, 25), cx))
        sleep(t(11, 13), t(11, 14))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(11, 9), cx))
        val row = grid(nov11, nov11, t(11, 20), focus = ChartFocus.OnSide(Side.LEFT)).single()
        assertEquals(listOf(Triple(480, 490, Side.LEFT)), bars(row))
        assertTrue(row.marks.isEmpty())
    }

    @Test
    fun `a feed whose end is unknown keeps its closed segments and marks the open one`() {
        val f = table.apply(Timers.start(Kind.BREAST, CHILD, t(11, 8), cx, Side.LEFT)).newEventId()
        table.apply(Timers.switchSide(table.timed(f), t(11, 8, 10), cx))
        table.apply(Timers.giveUp(table.timed(f), cx))
        val row = grid(nov11, nov11, t(11, 20)).single()
        assertEquals(listOf(Triple(480, 490, Side.LEFT)), bars(row))
        assertEquals(listOf(PatternMark(f, Kind.BREAST, null, 490, unknownEnd = true)), row.marks)
    }

    @Test
    fun `point records are marks, notes and deleted records are not drawn`() {
        table.apply(Records.diaper(CHILD, Diaper.POOP, t(11, 9), cx))
        table.apply(Records.bottle(CHILD, BottleContent.FORMULA, 90.0, t(11, 10), cx))
        table.apply(Records.pump(CHILD, 120.0, t(11, 11), cx))
        val vitD = SupplementPreset("p-d", "c1", "Vit D", createdAt = 0, updatedAt = 0, hlc = "h")
        table.apply(Records.supplement(CHILD, vitD, t(11, 12), cx))
        table.apply(Records.note(CHILD, "hello", t(11, 13), cx))
        val gone = table.apply(Records.diaper(CHILD, Diaper.WET, t(11, 14), cx)).newEventId()
        table.events[gone] = table.events.getValue(gone).copy(deletedAt = t(11, 15))
        val row = grid(nov11, nov11, t(11, 20)).single()
        assertEquals(
            listOf(Kind.DIAPER to 540, Kind.BOTTLE to 600, Kind.PUMP to 660, Kind.SUPPLEMENT to 720),
            row.marks.map { it.kind to it.atMin },
        )
        assertEquals("poop", row.marks.first().subtype)
    }

    @Test
    fun `poop only keeps poop and mixed diapers`() {
        table.apply(Records.diaper(CHILD, Diaper.POOP, t(11, 9), cx))
        table.apply(Records.diaper(CHILD, Diaper.MIXED, t(11, 10), cx))
        table.apply(Records.diaper(CHILD, Diaper.WET, t(11, 11), cx))
        sleep(t(11, 13), t(11, 14))
        val row = grid(nov11, nov11, t(11, 20), focus = ChartFocus.Subtype(Kind.DIAPER, "poop")).single()
        assertEquals(listOf("poop", "mixed"), row.marks.map { it.subtype })
        assertTrue(row.bars.isEmpty())
    }

    @Test
    fun `empty days still get a row`() {
        val rows = grid(LocalDate.of(2026, 11, 5), nov11, t(11, 20))
        assertEquals(7, rows.size)
        assertTrue(rows.all { it.bars.isEmpty() && it.marks.isEmpty() })
    }

    // ---- DST (Europe/Dublin: 01:00 → 02:00 on 2026-03-29, 02:00 → 01:00 on 2026-10-25) ----

    @Test
    fun `spring forward - a 23 hour row with the missing hour, a sleep across it in two parts`() {
        val spring = LocalDate.of(2026, 3, 29)
        // 00:30 → 02:30 by the clock is one hour of sleep.
        val id = sleep(dublin(2026, 3, 29, 0, 30), dublin(2026, 3, 29, 2, 30))
        val row = grid(spring, spring, dublin(2026, 3, 29, 20)).single()
        assertEquals(23, row.hours)
        assertEquals(listOf(MinuteRange(60, 120)), row.missing)
        assertEquals(
            listOf(
                PatternBar(id, Kind.SLEEP, null, 30, 60),
                PatternBar(id, Kind.SLEEP, null, 120, 150),
            ),
            row.bars,
        )
        assertEquals(60 * MIN, table.events.getValue(id).let { it.endAt!! - it.startAt })
    }

    @Test
    fun `fall back - a 25 hour row, the repeated hour drawn twice in the same columns`() {
        val autumn = LocalDate.of(2026, 10, 25)
        // From 01:30 (first pass, IST) to 01:15 (second pass, GMT): 45 minutes of sleep.
        val from = dublin(2026, 10, 25, 1, 30)
        val to = dublin(2026, 10, 25, 1, 15) + HOUR
        val id = sleep(from, to)
        val row = grid(autumn, autumn, dublin(2026, 10, 25, 20)).single()
        assertEquals(25, row.hours)
        assertEquals(listOf(MinuteRange(60, 120)), row.repeated)
        assertTrue(row.missing.isEmpty())
        assertEquals(listOf(Triple(60, 75, null), Triple(90, 120, null)), bars(row))
        assertTrue(row.bars.all { it.fromMin < it.toMin })
        assertEquals(id, row.bars.first().eventId)
    }

    @Test
    fun `fall back - afternoon records keep their clock column`() {
        val autumn = LocalDate.of(2026, 10, 25)
        table.apply(Records.diaper(CHILD, Diaper.WET, dublin(2026, 10, 25, 14), cx))
        sleep(dublin(2026, 10, 25, 13), dublin(2026, 10, 25, 15))
        val row = grid(autumn, autumn, dublin(2026, 10, 25, 20)).single()
        assertEquals(840, row.marks.single().atMin)
        assertEquals(listOf(Triple(780, 900, null)), bars(row))
    }

    @Test
    fun `day start at 06 00 - rows run from 06 00, the DST gap falls inside the earlier row`() {
        val six = DayClock(6, DUBLIN)
        sleep(t(11, 5), t(11, 7))
        val rows = grid(nov10, nov11, t(11, 20), clock = six)
        assertEquals(listOf(Triple(1380, 1440, null)), bars(rows[0]))
        assertEquals(listOf(Triple(0, 60, null)), bars(rows[1]))

        val sat = grid(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29), dublin(2026, 3, 30, 12), clock = six)
        assertEquals(23, sat[0].hours)
        assertEquals(listOf(MinuteRange(19 * 60, 20 * 60)), sat[0].missing)
        assertEquals(24, sat[1].hours)
        assertTrue(sat[1].missing.isEmpty())
    }

    @Test
    fun `day start at 02 00 - the missing hour is at the end of the earlier row`() {
        val two = DayClock(2, DUBLIN)
        val rows = grid(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29), dublin(2026, 3, 30, 12), clock = two)
        assertEquals(listOf(MinuteRange(1380, 1440)), rows[0].missing)
        assertEquals(23, rows[0].hours)
        assertTrue(rows[1].missing.isEmpty())
    }

    @Test
    fun `day start at 01 00 on the spring day - the row starts after the gap`() {
        val one = DayClock(1, DUBLIN)
        val spring = LocalDate.of(2026, 3, 29)
        sleep(dublin(2026, 3, 29, 2), dublin(2026, 3, 29, 3))
        val row = grid(spring, spring, dublin(2026, 3, 30, 12), clock = one).single()
        assertEquals(listOf(MinuteRange(0, 60)), row.missing)
        assertEquals(listOf(Triple(60, 120, null)), bars(row))
    }
}
