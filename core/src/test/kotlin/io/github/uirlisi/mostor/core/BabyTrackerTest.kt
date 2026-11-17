package io.github.uirlisi.mostor.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BabyTrackerTest {
    /** Made-up data in the report's layout, as PDF text extraction returns it (page break included). */
    private val report = """
        Baby daily report: all activity 3/1/26 - 3/3/26
        Created in Baby Tracker http://www.nighp.com/babytracker
        1
        Mon, March 02, 2026
        9:50 AM Feeding Nursing 15 min Start side R
        12:55 PM Feeding Nursing  1 hr 5 min Start side L
        7:55 PM Diaper change Mixed
        11:07 PM Feeding Nursing 4 min Start side R
        Nursing 1 hr 24 min (L 1h5m  R19m) 3 times, Bottle 0 oz.
        Diaper change 1 times, wet 0, dirty 0, mixed 1, dry 0
        Slept 0 times
        Pumping 0 times
        Tue, March 03, 2026
        12:45 AM Diaper change Dirty
        12:52 AM Feeding Nursing 16 min Start side R
        3:10 AM Sleep 1 hr 20 min
        Baby daily report: all activity 3/1/26 - 3/3/26
        Created in Baby Tracker http://www.nighp.com/babytracker
        2
        8:45 PM Feeding Nursing 5 min Start side L
        9:54 PM Diaper change Wet
        Nursing 21 min (L 10m  R11m) 2 times, Bottle 0 oz.
        Diaper change 3 times, wet 1, dirty 1, mixed 0, dry 0
        Slept 1 times
        Pumping 0 times
    """.trimIndent()

    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 3, d, h, m)

    @Test
    fun `reads feeds and diapers, with dates, 12-hour times and hours`() {
        val r = BabyTracker.parse(report)
        assertEquals(
            listOf(
                BabyTracker.Nursing(at(2, 9, 50), 15, Side.RIGHT),
                BabyTracker.Nursing(at(2, 12, 55), 65, Side.LEFT),
                BabyTracker.DiaperChange(at(2, 19, 55), Diaper.MIXED),
                BabyTracker.Nursing(at(2, 23, 7), 4, Side.RIGHT),
                BabyTracker.DiaperChange(at(3, 0, 45), Diaper.POOP),
                BabyTracker.Nursing(at(3, 0, 52), 16, Side.RIGHT),
                BabyTracker.Nursing(at(3, 20, 45), 5, Side.LEFT),
                BabyTracker.DiaperChange(at(3, 21, 54), Diaper.WET),
            ),
            r.records,
        )
        assertEquals(LocalDate.of(2026, 3, 2), r.firstDay)
        assertEquals(LocalDate.of(2026, 3, 3), r.lastDay)
    }

    @Test
    fun `an unknown entry is skipped and named, without the page header that follows it`() {
        assertEquals(listOf("3:10 AM Sleep 1 hr 20 min"), BabyTracker.parse(report).skipped)
    }

    @Test
    fun `the day summaries check the counts and the sides`() {
        val issues = BabyTracker.parse(report).issues
        // Day 2 matches its summary. Day 3: 2 feeds of 2, but L 10m R 11m isn't what the start
        // sides say (L 5m, R 16m), and 2 diapers were read where the summary has 3.
        assertEquals(
            listOf(
                BabyTracker.SidesUnknown(LocalDate.of(2026, 3, 3)),
                BabyTracker.CountMismatch(LocalDate.of(2026, 3, 3), Kind.DIAPER, 3, 2),
            ),
            issues,
        )
    }

    @Test
    fun `odd spacing and line breaks from the PDF don't matter`() {
        val squashed = report.replace("\n", "  ").replace(" min", " min")
        assertEquals(BabyTracker.parse(report), BabyTracker.parse(squashed))
    }

    @Test
    fun `plan writes ended feeds with one segment, and diapers, in the given zone`() {
        val zone = ZoneId.of("America/New_York")
        val cx = FakeCx(now = 0, tz = zone.id)
        val plan = BabyTracker.plan(BabyTracker.parse(report), CHILD, zone, emptyMap(), emptyMap(), emptyList(), cx)
        assertEquals(8, plan.newEvents)
        val feed = plan.change.events.first().after
        val start = at(2, 9, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(Kind.BREAST, feed.kind)
        assertEquals(start, feed.startAt)
        assertEquals(start + 15 * MIN, feed.endAt)
        assertEquals(EventStatus.ENDED, feed.status)
        assertEquals("America/New_York", feed.tz)
        assertEquals(start, Ids.timestampOf(feed.id))
        val seg = plan.change.segments.first().after
        assertEquals(feed.id, seg.eventId)
        assertEquals(Side.RIGHT, seg.side)
        assertEquals(start to start + 15 * MIN, seg.startAt to seg.endAt)
        val poop = plan.change.events.map { it.after }.first { it.subtype == Diaper.POOP.code }
        assertEquals(null, poop.status)
        assertEquals(null, poop.endAt)
        assertEquals(5, plan.change.segments.size)
    }

    @Test
    fun `the same report planned twice gets the same ids, so a second import adds nothing`() {
        val zone = ZoneId.of("America/New_York")
        val parsed = BabyTracker.parse(report)
        val first = BabyTracker.plan(parsed, CHILD, zone, emptyMap(), emptyMap(), emptyList(), FakeCx(0))
        val ids = first.change.events.map { it.after.id }.toSet()
        assertEquals(8, ids.size)
        // Another phone, another child id: same event ids.
        val elsewhere = BabyTracker.plan(parsed, "other-child", zone, emptyMap(), emptyMap(), emptyList(), FakeCx(5))
        assertEquals(ids, elsewhere.change.events.map { it.after.id }.toSet())

        val stored = first.change.events.associate { it.after.id to it.after }
        val storedSegments = first.change.segments.associate { it.after.id to it.after }
        val again = BabyTracker.plan(parsed, CHILD, zone, stored, storedSegments, emptyList(), FakeCx(10))
        assertEquals(0, again.newEvents)
        assertEquals(8, again.alreadyImported)
        assertEquals(ids.toList() to first.change.segments.map { it.after.id }, BabyTracker.ids(parsed, zone))
    }

    @Test
    fun `a deleted import stays deleted, unless bringing it back is asked for`() {
        val zone = ZoneId.of("America/New_York")
        val parsed = BabyTracker.parse(report)
        val table = Table()
        val first = table.apply(BabyTracker.plan(parsed, CHILD, zone, emptyMap(), emptyMap(), emptyList(), FakeCx(0)).change)
        // Undoing the import soft-deletes every row it added.
        table.undo(first, FakeCx(100))
        assertTrue(table.events.values.none { it.isLive })

        val left = BabyTracker.plan(parsed, CHILD, zone, table.events, table.segments, emptyList(), FakeCx(200))
        assertEquals(0, left.newEvents)
        assertEquals(8, left.deletedSince)

        val back = BabyTracker.plan(parsed, CHILD, zone, table.events, table.segments, emptyList(), FakeCx(300), bringBack = true)
        assertEquals(8, back.broughtBack)
        table.apply(back.change)
        assertTrue(table.events.values.all { it.isLive })
        assertTrue(table.segments.values.all { it.deletedAt == null })
        // The "before" states are kept, so bringing back can itself be undone.
        assertTrue(back.change.events.all { it.before?.deletedAt != null })
    }

    @Test
    fun `a record already made in Mo Stor within 5 minutes is left out`() {
        val zone = ZoneId.of("America/New_York")
        val wetAt = at(3, 21, 54).atZone(zone).toInstant().toEpochMilli()
        val mine = Event(
            id = "mine", childId = CHILD, kind = Kind.DIAPER, subtype = Diaper.WET.code, startAt = wetAt + 3 * MIN,
            tz = zone.id, createdAt = 0, updatedAt = 0, hlc = "h",
        )
        val plan = BabyTracker.plan(BabyTracker.parse(report), CHILD, zone, emptyMap(), emptyMap(), listOf(mine), FakeCx(0))
        assertEquals(7, plan.newEvents)
        assertEquals(1, plan.alreadyRecorded)
        // A deleted one, or one of another subtype, doesn't count.
        val other = BabyTracker.plan(
            BabyTracker.parse(report), CHILD, zone, emptyMap(), emptyMap(),
            listOf(mine.copy(deletedAt = 1), mine.copy(id = "m2", subtype = Diaper.POOP.code)), FakeCx(0),
        )
        assertEquals(8, other.newEvents)
    }

    @Test
    fun `two identical records at the same minute both survive`() {
        val twice = "Mon, March 02, 2026 7:55 PM Diaper change Wet 7:55 PM Diaper change Wet"
        val plan = BabyTracker.plan(BabyTracker.parse(twice), CHILD, ZoneId.of("UTC"), emptyMap(), emptyMap(), emptyList(), FakeCx(0))
        val ids = plan.change.events.map { it.after.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertTrue(ids.all { Ids.timestampOf(it) == ids.first().let(Ids::timestampOf) })
    }
}
