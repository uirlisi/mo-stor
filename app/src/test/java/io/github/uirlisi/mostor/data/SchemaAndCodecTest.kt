package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.RowChange
import io.github.uirlisi.mostor.core.Side
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema checks. Each future schema version adds a migration to MoStorDb.MIGRATIONS and a test
 * here that creates the old version from its exported JSON, fills it, migrates and validates.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "schema-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(name),
        driver = AndroidSQLiteDriver(),
        databaseClass = MoStorDb::class,
    )

    @Test
    fun `the exported version 1 schema matches what Room builds, and data written to it reads back`() {
        // The schemas are assets of the dev flavor only; the prod variants' unit tests skip this.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue(assets.list(MoStorDb::class.java.name)?.contains("1.json") == true)

        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO event (id, child_id, kind, subtype, start_at, end_at, status, amount_ml, supplement_preset_id, " +
                    "supplement_name, supplement_amount, supplement_unit, note, tz, created_at, updated_at, deleted_at, hlc) " +
                    "VALUES ('e1', 'c1', 'diaper', 'wet', 1000, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'Europe/Dublin', 1000, 1000, NULL, 'h')",
            )
        }
        // Migrates to the current version (a no-op while it is 1) and checks every table against the export.
        helper.runMigrationsAndValidate(MoStorDb.LATEST_VERSION, MoStorDb.MIGRATIONS.toList()).close()

        // Room itself opens the file (identity hash check) and reads the row back.
        val room = Room.databaseBuilder(context, MoStorDb::class.java, name).addMigrations(*MoStorDb.MIGRATIONS).build()
        try {
            assertEquals("wet", runBlocking { room.events().event("e1") }!!.subtype)
        } finally {
            room.close()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class UndoCodecTest {
    @Test
    fun `an undo payload survives a round trip, nulls included`() {
        val before = Event(
            id = "e1", childId = "c1", kind = Kind.BREAST, startAt = 1_000, status = EventStatus.RUNNING,
            tz = "Europe/Dublin", createdAt = 1_000, updatedAt = 1_000, hlc = "000000000001-0000-a",
        )
        val after = before.copy(endAt = 2_000, status = EventStatus.ENDED, updatedAt = 2_000, hlc = "000000000002-0000-a")
        val seg = BreastSegment("s1", "e1", Side.RIGHT, 1_000, null, 1_000, 1_000, null, "000000000001-0001-a")
        val supplement = Event(
            id = "e2", childId = "c1", kind = Kind.SUPPLEMENT, startAt = 3_000, supplementPresetId = "p",
            supplementName = "Vit D", supplementAmount = 0.5, supplementUnit = "ml", note = "with \"quotes\"\nand a newline",
            tz = "UTC", createdAt = 3_000, updatedAt = 3_000, hlc = "000000000003-0000-a",
        )
        val payload = UndoPayload(
            ActionInfo(Verb.STOP, Kind.BREAST, "e1", 2_000, side = Side.RIGHT, amountMl = 12.5, name = "x", count = 3),
            Change(
                events = listOf(RowChange(before, after), RowChange(null, supplement)),
                segments = listOf(RowChange(seg, seg.copy(endAt = 2_000, hlc = "000000000002-0001-a"))),
            ),
        )
        assertEquals(payload, UndoCodec.decode(UndoCodec.encode(payload)))
    }
}
