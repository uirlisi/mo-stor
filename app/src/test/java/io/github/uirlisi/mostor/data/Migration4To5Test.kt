package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MIGRATION_4_5: adds `event.auto_base` (null for every existing row) and touches nothing else. */
@RunWith(RobolectricTestRunner::class)
class Migration4To5Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-4-5.db"
    private var opened: MoStorDb? = null

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(name),
        driver = AndroidSQLiteDriver(),
        databaseClass = MoStorDb::class,
    )

    @Before
    fun schemasPresent() {
        // Not an assumption: a missing schema must fail the test, not skip it.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val have = assets.list(MoStorDb::class.java.name)?.toList().orEmpty()
        assertTrue("exported schemas 4 and 5 are in the dev assets, found $have", have.containsAll(listOf("4.json", "5.json")))
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `version 4 rows survive, with no automatic mark`() = runBlocking {
        helper.createDatabase(4).use { db ->
            db.execSQL("INSERT INTO meta (id, device_id, device_name, hlc) VALUES (1, 'phone-a', NULL, '000000000100-0000-phone-a')")
            db.execSQL(
                "INSERT INTO child (id, name, birth_date, sex, birth_weight_g, birth_length_mm, color, sort_key, archived_at, created_at, updated_at, deleted_at, hlc) " +
                    "VALUES ('c1', 'Aoife', NULL, NULL, NULL, NULL, NULL, 0, NULL, 10, 10, NULL, '000000000010-0000-phone-a')",
            )
            db.execSQL(
                "INSERT INTO event (id, child_id, kind, subtype, start_at, end_at, status, amount_ml, supplement_preset_id, supplement_name, " +
                    "supplement_amount, supplement_unit, note, tz, created_at, updated_at, deleted_at, hlc) " +
                    "VALUES ('e1', 'c1', 'sleep', NULL, 50, NULL, 'end_unknown', NULL, NULL, NULL, NULL, NULL, 'a note', 'Europe/Dublin', 50, 60, NULL, '000000000060-0000-phone-a')",
            )
        }
        // Validates against the exported schema 5.
        helper.runMigrationsAndValidate(5, MoStorDb.MIGRATIONS.toList()).close()

        val db = Room.databaseBuilder(context, MoStorDb::class.java, context.getDatabasePath(name).path)
            .addMigrations(*MoStorDb.MIGRATIONS).build().also { opened = it }
        val e = db.events().event("e1")!!
        assertEquals("a note", e.note)
        assertEquals("end_unknown", e.status)
        assertNull(e.autoBase)

        val repo = Repository(db, {}, { 1_000L }, { ZoneId.of("UTC") }, { Locale.UK })
        assertEquals(1, repo.backupRows().events.size)
    }
}
