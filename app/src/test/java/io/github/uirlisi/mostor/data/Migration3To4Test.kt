package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Roster
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIGRATION_3_4: adds the `member` table (the household's list of phones) and touches nothing else.
 * A pairing made before it keeps working: the list is made from the paired phones.
 */
@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-3-4.db"
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
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue(assets.list(MoStorDb::class.java.name)?.toList()?.containsAll(listOf("3.json", "4.json")) == true)
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `version 3 data and the pairing survive, and the list of phones is made from it`() = runBlocking {
        helper.createDatabase(3).use { db ->
            db.execSQL("INSERT INTO meta (id, device_id, device_name, hlc) VALUES (1, 'phone-a', NULL, '000000000100-0000-phone-a')")
            db.execSQL(
                "INSERT INTO child (id, name, birth_date, sex, birth_weight_g, birth_length_mm, color, sort_key, archived_at, created_at, updated_at, deleted_at, hlc) " +
                    "VALUES ('c1', 'Aoife', NULL, NULL, NULL, NULL, NULL, 0, NULL, 10, 10, NULL, '000000000010-0000-phone-a')",
            )
            db.execSQL(
                "INSERT INTO event (id, child_id, kind, subtype, start_at, end_at, status, amount_ml, supplement_preset_id, supplement_name, " +
                    "supplement_amount, supplement_unit, note, tz, created_at, updated_at, deleted_at, hlc) " +
                    "VALUES ('e1', 'c1', 'diaper', 'wet', 50, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'a note', 'Europe/Dublin', 50, 50, NULL, '000000000050-0000-phone-a')",
            )
            db.execSQL("INSERT INTO setting (`key`, value) VALUES ('household_id', 'h1')")
            db.execSQL("INSERT INTO peer (device_id, name, paired_at, last_sync_at, last_result) VALUES ('phone-b', 'Galaxy S23', 100, 200, 'ok')")
        }
        // Validates against the exported schema 4.
        helper.runMigrationsAndValidate(4, MoStorDb.MIGRATIONS.toList()).close()

        val db = Room.databaseBuilder(context, MoStorDb::class.java, context.getDatabasePath(name).path)
            .addMigrations(*MoStorDb.MIGRATIONS).build().also { opened = it }
        assertEquals("Aoife", db.children().child("c1")?.name)
        assertEquals("a note", db.events().event("e1")?.note)
        assertEquals(200L, db.peers().get("phone-b")?.lastSyncAt)
        assertTrue(db.members().all().isEmpty())

        val repo = Repository(db, {}, { 1_000L }, { ZoneId.of("UTC") }, { Locale.UK })
        repo.sync.ensureRoster("Galaxy S22")
        val members = repo.sync.members().associateBy { it.deviceId }
        assertEquals(setOf("phone-a", "phone-b"), members.keys)
        assertEquals("Galaxy S23", members.getValue("phone-b").name)
        assertEquals("Galaxy S22", members.getValue("phone-a").name)
        assertTrue(members.values.all { it.active && it.hlc == Roster.SEED_HLC })
        assertEquals(listOf("Galaxy S23"), repo.sync.others().map { it.name })

        assertNotNull(repo.recordDiaper(Diaper.POOP, Source.SHEET))
        assertEquals(2, db.events().all().size)
    }
}
