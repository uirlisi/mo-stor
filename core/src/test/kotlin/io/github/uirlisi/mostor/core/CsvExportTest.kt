package io.github.uirlisi.mostor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CsvExportTest {
    private val t0 = dublin(2026, 11, 14, 9, 16)
    private val aoife = Child(id = "k1", name = "Aoife", createdAt = t0, updatedAt = t0, hlc = "h")
    private val unnamed = Child(id = "k2", createdAt = t0, updatedAt = t0, hlc = "h")

    private fun event(id: String, kind: Kind, start: Long, child: String = "k1", tz: String = "Europe/Dublin", f: (Event) -> Event = { it }) =
        f(Event(id = id, childId = child, kind = kind, startAt = start, tz = tz, createdAt = start, updatedAt = start, hlc = "h"))

    private fun seg(id: String, event: String, side: Side, from: Long, to: Long?) =
        BreastSegment(id = id, eventId = event, side = side, startAt = from, endAt = to, createdAt = from, updatedAt = from, hlc = "h")

    private fun csv(children: List<Child>, events: List<Event>, segments: List<BreastSegment> = emptyList()) =
        CsvExport.write(children, events, segments) { it.name ?: "Baby" }

    /** The data lines, split into fields (the tests use no quoted fields unless they say so). */
    private fun lines(text: String) = text.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `starts with a byte order mark and the header, CRLF line ends`() {
        val text = csv(listOf(aoife), emptyList())
        assertTrue(text.startsWith("﻿child,kind,subtype,status,start,end,time_zone,duration_min,"))
        assertEquals(CsvExport.HEADER.joinToString(",") + "\r\n", text.removePrefix("﻿"))
    }

    @Test
    fun `a breast feed flattens its segments with per-side minutes`() {
        val feed = event("e1", Kind.BREAST, t0) { it.copy(endAt = t0 + 24 * MIN, status = EventStatus.ENDED) }
        val segs = listOf(
            seg("s2", "e1", Side.RIGHT, t0 + 15 * MIN, t0 + 24 * MIN),
            seg("s1", "e1", Side.LEFT, t0, t0 + 14 * MIN),
            seg("s3", "e1", Side.LEFT, t0 + 30 * MIN, t0 + 40 * MIN).copy(deletedAt = t0),
        )
        val row = lines(csv(listOf(aoife), listOf(feed), segs))[1].split(',')
        assertEquals(
            listOf("Aoife", "breast", "", "ended", "2026-11-14 09:16", "2026-11-14 09:40", "Europe/Dublin", "23", "14", "9",
                "L 09:16-09:30; R 09:31-09:40", "", "", "", "", "", "e1"),
            row,
        )
    }

    @Test
    fun `times are local to each record's own zone`() {
        val bottle = event("e1", Kind.BOTTLE, t0, tz = "America/New_York") { it.copy(subtype = "formula", amountMl = 88.7206) }
        val row = lines(csv(listOf(aoife), listOf(bottle)))[1].split(',')
        assertEquals("2026-11-14 04:16", row[4])
        assertEquals("America/New_York", row[6])
        assertEquals("88.72", row[11])
    }

    @Test
    fun `running and no-end timers have no duration, an ended sleep has one`() {
        val running = event("e1", Kind.SLEEP, t0) { it.copy(status = EventStatus.RUNNING) }
        val noEnd = event("e2", Kind.SLEEP, t0 + MIN) { it.copy(status = EventStatus.END_UNKNOWN) }
        val ended = event("e3", Kind.SLEEP, t0 + 2 * MIN) { it.copy(status = EventStatus.ENDED, endAt = t0 + 2 * MIN + 90 * MIN) }
        val rows = lines(csv(listOf(aoife), listOf(ended, noEnd, running))).drop(1).map { it.split(',') }
        assertEquals(listOf("e1", "e2", "e3"), rows.map { it.last() })
        assertEquals(listOf("", "", "90"), rows.map { it[7] })
        assertEquals(listOf("running", "end_unknown", "ended"), rows.map { it[3] })
    }

    @Test
    fun `meds, names and deleted rows`() {
        val dose = event("e1", Kind.SUPPLEMENT, t0, child = "k2") {
            it.copy(supplementName = "Vit D", supplementAmount = 400.0, supplementUnit = "IU", status = EventStatus.SKIPPED)
        }
        val gone = event("e2", Kind.DIAPER, t0) { it.copy(subtype = "wet", deletedAt = t0) }
        val ofDeletedChild = event("e3", Kind.DIAPER, t0, child = "k3") { it.copy(subtype = "wet") }
        val deletedChild = Child(id = "k3", createdAt = t0, updatedAt = t0, deletedAt = t0, hlc = "h")
        val rows = lines(csv(listOf(aoife, unnamed, deletedChild), listOf(dose, gone, ofDeletedChild))).drop(1)
        assertEquals(1, rows.size)
        val row = rows[0].split(',')
        assertEquals(listOf("Baby", "supplement", "", "skipped"), row.take(4))
        assertEquals(listOf("Vit D", "400", "IU"), row.subList(12, 15))
    }

    @Test
    fun `notes are quoted when needed and formulas are defused`() {
        val note = event("e1", Kind.NOTE, t0) { it.copy(note = "Fussy, then \"fine\"\nslept") }
        val formula = event("e2", Kind.NOTE, t0 + MIN) { it.copy(note = "=1+1") }
        val text = csv(listOf(aoife), listOf(note, formula))
        assertTrue(text.contains(",\"Fussy, then \"\"fine\"\"\nslept\",e1\r\n"), text)
        assertTrue(text.contains(",'=1+1,e2\r\n"), text)
    }
}
