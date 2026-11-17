package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Side
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The JSON backup: export on one phone, import into another (SPEC §8, Phase 4). */
@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 14, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private lateinit var dbA: MoStorDb
    private lateinit var dbB: MoStorDb
    private lateinit var phoneA: Repository
    private lateinit var phoneB: Repository

    @Before
    fun setUp() {
        dbA = MoStorDb.build(context, "phone-a.db")
        dbB = MoStorDb.build(context, "phone-b.db")
        phoneA = Repository(dbA, {}, { now }, { zone }, { Locale.UK })
        phoneB = Repository(dbB, {}, { now }, { zone }, { Locale.UK })
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        listOf("phone-a.db", "phone-b.db", "moved.db").forEach { context.deleteDatabase(it) }
    }

    private suspend fun export(phone: Repository): String = BackupJson.encode(phone.backupRows(), now, "test")

    private fun read(json: String): Merge.Rows = (BackupJson.decode(json) as BackupJson.Read.Ok).backup.rows

    /** A day on phone A: a feed with a side switch, a diaper, a bottle, a named child, a deleted record. */
    private suspend fun aDayOnPhoneA() {
        phoneA.ensureInitialized()
        val child = dbA.children().live().single().id
        phoneA.editChild(child, ChildFields(name = "Aoife"))
        phoneA.tapSide(Side.LEFT, Source.SHEET)
        now += 10 * 60_000
        val feed = dbA.events().running().single().id
        phoneA.switchSide(feed, Source.SHEET)
        now += 8 * 60_000
        phoneA.stop(feed, Source.SHEET)
        now += 60_000
        phoneA.recordDiaper(Diaper.WET, Source.SHEET)
        now += 60_000
        phoneA.recordBottle(BottleContent.FORMULA, 90.0, Source.SHEET)
        now += 60_000
        val oops = phoneA.recordDiaper(Diaper.POOP, Source.SHEET)!!
        phoneA.delete(oops.info.eventId)
        now += 60_000
    }

    @Test
    fun `a backup imported into a fresh install reproduces the same log`() = runBlocking {
        aDayOnPhoneA()
        val json = export(phoneA)

        // Phone B is a fresh install: its untouched "Baby" gives way to the backup's child.
        val preview = phoneB.previewBackup(read(json))
        assertEquals(4, preview.added)
        assertTrue(preview.replacesBlank)
        phoneB.importBackup(read(json))

        assertEquals(phoneA.backupRows().events.toSet(), phoneB.backupRows().events.toSet())
        assertEquals(phoneA.backupRows().segments.toSet(), phoneB.backupRows().segments.toSet())
        assertEquals(listOf("Aoife"), dbB.children().live().map { it.name })
        assertEquals(phoneA.logView(3, LogFilter()).days, phoneB.logView(3, LogFilter()).days)
        assertEquals(
            dbA.presets().live().map { it.toModel() }.toSet(),
            dbB.presets().live().map { it.toModel() }.toSet(),
        )
    }

    @Test
    fun `importing the same backup twice changes nothing`() = runBlocking {
        aDayOnPhoneA()
        val json = export(phoneA)
        phoneB.importBackup(read(json))
        val after = phoneB.backupRows()

        val again = phoneB.previewBackup(read(json))
        assertFalse(again.changesAnything)
        assertEquals(4, again.unchanged)
        phoneB.importBackup(read(json))
        assertEquals(after, phoneB.backupRows())
    }

    @Test
    fun `an import keeps newer local changes and a child with records`() = runBlocking {
        aDayOnPhoneA()
        val json = export(phoneA)
        // Phone B already has its own record: its child isn't blank, so it stays beside Aoife.
        phoneB.recordDiaper(Diaper.MIXED, Source.SHEET)
        phoneB.importBackup(read(json))
        assertEquals(2, dbB.children().live().size)

        // A later edit on B wins over the older row when the same backup comes in again.
        val wet = dbB.events().all().single { it.subtype == "wet" }
        now += 60_000
        val editing = phoneB.loadForEdit(wet.id)!!
        phoneB.saveEdit(editing, EventEdit.draftOf(editing.event).copy(subtype = "poop"))
        phoneB.importBackup(read(json))
        assertEquals("poop", dbB.events().event(wet.id)!!.subtype)
    }

    @Test
    fun `a deletion in the backup reaches a phone that still has the row`() = runBlocking {
        aDayOnPhoneA()
        phoneB.importBackup(read(export(phoneA)))
        now += 60_000
        val wet = dbA.events().all().single { it.subtype == "wet" }.id
        phoneA.delete(wet)
        phoneB.importBackup(read(export(phoneA)))
        assertTrue(dbB.events().event(wet)!!.deletedAt != null)
    }

    @Test
    fun `the phone's clock moves past the backup's newest stamp`() = runBlocking {
        aDayOnPhoneA()
        val json = export(phoneA)
        now -= 3_600_000 // Phone B's clock is an hour behind.
        phoneB.importBackup(read(json))
        phoneB.recordDiaper(Diaper.DRY, Source.SHEET)
        val newest = read(json).newestHlc()!!
        val dry = dbB.events().all().single { it.subtype == "dry" }
        assertTrue(HlcStamp.parse(dry.hlc) > HlcStamp.parse(newest))
    }

    @Test
    fun `the format round trips every column, nulls included`() = runBlocking {
        aDayOnPhoneA()
        val rows = phoneA.backupRows()
        assertEquals(rows, read(BackupJson.encode(rows, now, "0.4.0")))
        val root = JSONObject(BackupJson.encode(rows, now, "0.4.0"))
        assertEquals("mo-stor-backup", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertTrue(root.getJSONArray("events").getJSONObject(0).has("note"))
    }

    @Test
    fun `other files are refused`() = runBlocking {
        aDayOnPhoneA()
        val good = JSONObject(export(phoneA))
        assertEquals(BackupJson.Read.NotABackup, BackupJson.decode("not json"))
        assertEquals(BackupJson.Read.NotABackup, BackupJson.decode("""{"format":"something else","version":1}"""))
        assertEquals(BackupJson.Read.Newer, BackupJson.decode(JSONObject(good.toString()).put("version", 2).toString()))

        val badKind = JSONObject(good.toString())
        badKind.getJSONArray("events").getJSONObject(0).put("kind", "bath")
        assertEquals(BackupJson.Read.NotABackup, BackupJson.decode(badKind.toString()))

        val missing = JSONObject(good.toString())
        missing.getJSONArray("events").getJSONObject(0).remove("end_at")
        assertEquals(BackupJson.Read.NotABackup, BackupJson.decode(missing.toString()))

        val badStamp = JSONObject(good.toString())
        badStamp.getJSONArray("children").getJSONObject(0).put("hlc", "yesterday")
        assertEquals(BackupJson.Read.NotABackup, BackupJson.decode(badStamp.toString()))
    }

    @Test
    fun `the CSV export lists the live records`() = runBlocking {
        aDayOnPhoneA()
        val lines = phoneA.csv("Baby").removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(4, lines.size) // header, feed, wet, bottle (the deleted poop is left out)
        assertTrue(lines[1].startsWith("Aoife,breast,,ended,2026-11-14 09:00,2026-11-14 09:18,Europe/Dublin,18,10,8,L 09:00-09:10; R 09:10-09:18,"))
    }

    @Test
    fun `a database moved from another phone takes a new device id`() = runBlocking {
        // The same database file opened by two installs, as after a phone-to-phone transfer.
        val db = MoStorDb.build(context, "moved.db")
        try {
            Repository(db, {}, { now }, { zone }, { Locale.UK }, installId = { "install-old" }).recordDiaper(Diaper.WET, Source.SHEET)
            val oldId = db.meta().get()!!.deviceId

            // The same install again: nothing changes.
            Repository(db, {}, { now }, { zone }, { Locale.UK }, installId = { "install-old" }).ensureInitialized()
            assertEquals(oldId, db.meta().get()!!.deviceId)

            // Another install opens it: a new device id, records kept, and stamps keep moving on.
            val stampBefore = db.events().all().single().hlc
            val moved = Repository(db, {}, { now }, { zone }, { Locale.UK }, installId = { "install-new" })
            moved.recordDiaper(Diaper.POOP, Source.SHEET)
            val newId = db.meta().get()!!.deviceId
            assertNotEquals(oldId, newId)
            assertEquals("install-new", db.settings().get(Keys.INSTALL_ID))
            val poop = db.events().all().single { it.subtype == "poop" }
            assertTrue(poop.hlc.endsWith(newId))
            assertTrue(HlcStamp.parse(poop.hlc) > HlcStamp.parse(stampBefore))
        } finally {
            db.close()
        }
    }

    @Test
    fun `an upgrade from a version without the check keeps its device id`() = runBlocking {
        phoneA.recordDiaper(Diaper.WET, Source.SHEET)
        val id = dbA.meta().get()!!.deviceId
        Repository(dbA, {}, { now }, { zone }, { Locale.UK }, installId = { "first-run-with-check" }).ensureInitialized()
        assertEquals(id, dbA.meta().get()!!.deviceId)
        assertEquals("first-run-with-check", dbA.settings().get(Keys.INSTALL_ID))
    }
}
