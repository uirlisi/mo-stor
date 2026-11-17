package io.github.uirlisi.mostor.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Side
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Save a copy on one phone, restore it on another (DECISIONS 2026-11-13). */
@RunWith(RobolectricTestRunner::class)
class DataCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 13, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private lateinit var dbA: MoStorDb
    private lateinit var dbB: MoStorDb
    private lateinit var phoneA: Repository
    private lateinit var phoneB: Repository
    private val copy get() = File(context.cacheDir, "test-copy.db")

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
        listOf("phone-a.db", "phone-b.db").forEach { context.deleteDatabase(it) }
        copy.delete()
    }

    private fun events(db: MoStorDb) = DataCopy.readRows(db.openHelper.readableDatabase).tables.getValue("event")
    private fun liveIds(db: MoStorDb) = events(db).filter { it.getAsLong("deleted_at") == null }.map { it.getAsString("id") }.toSet()

    private fun readCopy(): DataCopy.Rows {
        val scratch = File(context.cacheDir, "scratch.db")
        copy.copyTo(scratch, overwrite = true)
        return (DataCopy.read(context, scratch) as DataCopy.Read.Ok).rows.also { scratch.delete() }
    }

    @Test
    fun `a copy restored on another phone replaces its records but keeps its identity and settings`() = runBlocking {
        phoneA.recordDiaper(Diaper.WET, Source.SHEET)
        now += 60_000
        phoneA.tapSide(Side.LEFT, Source.SHEET)
        now += 60_000
        DataCopy.write(dbA, copy)

        phoneB.recordDiaper(Diaper.POOP, Source.SHEET)
        phoneB.setSetting(Keys.DAY_START_HOUR, "6")
        val idB = dbB.meta().get()!!.deviceId
        val childB = dbB.children().live().single().id
        val poop = events(dbB).single().getAsString("id")

        val rows = readCopy()
        assertEquals(DataCopy.Summary(children = 1, records = 2, newestStart = now - 60_000), rows.summary())
        assertEquals(2, phoneB.restoreFrom(rows))

        // A's child and records are here, B's are soft-deleted (not hard-deleted).
        val childA = dbA.children().live().single().id
        assertEquals(listOf(childA), dbB.children().live().map { it.id })
        assertNotNull(dbB.children().child(childB)!!.deletedAt)
        assertNotNull(events(dbB).single { it.getAsString("id") == poop }.getAsLong("deleted_at"))
        assertEquals(liveIds(dbA), liveIds(dbB))
        // The running feed came across with its open segment.
        assertEquals(1, events(dbB).count { it.getAsString("status") == "running" })

        // B is still B: its device id and settings, but no undo history from before.
        assertEquals(idB, dbB.meta().get()!!.deviceId)
        assertEquals("6", dbB.settings().get(Keys.DAY_START_HOUR))
        assertNull(dbB.undo().latest())

        // B's next change sorts after everything in the copy and is stamped as B's.
        now -= 10 * 60_000 // even with B's wall clock behind
        val done = phoneB.recordDiaper(Diaper.WET, Source.SHEET)!!
        val stamp = HlcStamp.parse(events(dbB).single { it.getAsString("id") == done.info.eventId }.getAsString("hlc"))
        assertEquals(idB, stamp.origin)
        assertTrue(stamp.encode() > rows.newestHlc()!!)
    }

    @Test
    fun `a file that isn't a copy is refused`() {
        copy.writeText("not a database")
        assertEquals(DataCopy.Read.NotACopy, DataCopy.read(context, copy))

        copy.delete()
        SQLiteDatabase.openOrCreateDatabase(copy, null).use { it.execSQL("CREATE TABLE other (x INTEGER)") }
        assertEquals(DataCopy.Read.NotACopy, DataCopy.read(context, copy))
    }

    @Test
    fun `a copy from a newer schema is refused`() = runBlocking {
        phoneA.ensureInitialized()
        DataCopy.write(dbA, copy)
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = MoStorDb.LATEST_VERSION + 1 }
        assertEquals(DataCopy.Read.Newer, DataCopy.read(context, copy))
    }
}
