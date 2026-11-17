package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.uirlisi.mostor.core.Presets
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIGRATION_1_2 (Phase 3b): meds split per child, doses re-pointed, schema validated against the
 * exported version 2. Robolectric, like the other schema tests (DECISIONS).
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-1-2.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(name),
        driver = AndroidSQLiteDriver(),
        databaseClass = MoStorDb::class,
    )

    @Before
    fun schemasPresent() {
        // The schemas are assets of the dev flavor only; the prod variants' unit tests skip this.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue(assets.list(MoStorDb::class.java.name)?.toList()?.containsAll(listOf("1.json", "2.json")) == true)
    }

    // ---- Version 1 rows ----

    private fun androidx.sqlite.SQLiteConnection.sql(s: String) = execSQL(s)

    private fun v1(fill: androidx.sqlite.SQLiteConnection.() -> Unit) {
        helper.createDatabase(1).use { db ->
            db.sql("INSERT INTO meta (id, device_id, device_name, hlc) VALUES (1, 'phone-a', NULL, '000000000100-0000-phone-a')")
            db.fill()
        }
    }

    private fun androidx.sqlite.SQLiteConnection.child(id: String, created: Long, archived: Long? = null, deleted: Long? = null) =
        sql(
            "INSERT INTO child (id, sort_key, archived_at, created_at, updated_at, deleted_at, hlc) " +
                "VALUES ('$id', 0, ${archived ?: "NULL"}, $created, $created, ${deleted ?: "NULL"}, 'h-$id')",
        )

    private fun androidx.sqlite.SQLiteConnection.preset(id: String, name: String, amount: Double? = null, hlc: String = SEED_HLC) =
        sql(
            "INSERT INTO supplement_preset (id, name, amount, unit, sort_key, created_at, updated_at, deleted_at, hlc) " +
                "VALUES ('$id', '$name', ${amount ?: "NULL"}, NULL, 0, 1, 1, NULL, '$hlc')",
        )

    private fun androidx.sqlite.SQLiteConnection.dose(id: String, child: String, preset: String) =
        sql(
            "INSERT INTO event (id, child_id, kind, start_at, supplement_preset_id, supplement_name, tz, created_at, updated_at, hlc) " +
                "VALUES ('$id', '$child', 'supplement', 5, '$preset', 'x', 'UTC', 5, 5, '000000000005-0000-phone-a')",
        )

    private fun migrate(): SupportSQLiteDatabase {
        helper.runMigrationsAndValidate(2, MoStorDb.MIGRATIONS.toList()).close()
        val room = Room.databaseBuilder(context, MoStorDb::class.java, name).addMigrations(*MoStorDb.MIGRATIONS).build()
        opened += room
        return room.openHelper.writableDatabase
    }

    private val opened = mutableListOf<MoStorDb>()

    @org.junit.After
    fun close() = opened.forEach { it.close() }

    // ---- Reading version 2 ----

    private data class Med(val id: String, val child: String, val name: String, val amount: Double?, val schedule: String, val deleted: Boolean, val hlc: String)

    private fun SupportSQLiteDatabase.meds(): List<Med> =
        query("SELECT id, child_id, name, amount, schedule, deleted_at, hlc FROM supplement_preset ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Med(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getDouble(3), c.getString(4), !c.isNull(5), c.getString(6)))
                }
            }
        }

    private fun SupportSQLiteDatabase.doseOf(id: String): Pair<String, String> =
        query("SELECT supplement_preset_id, hlc FROM event WHERE id = ?", arrayOf(id)).use { c -> c.moveToFirst(); c.getString(0) to c.getString(1) }

    private fun SupportSQLiteDatabase.count(table: String): Int = query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    // ---- Tests ----

    @Test
    fun `one child keeps its meds, the seeds become the child's own, and doses follow`() {
        v1 {
            child("c1", created = 10)
            preset(VIT_D, "Vit D")
            preset(VIT_K, "Vit K")
            preset("iron", "Iron", amount = 1.0, hlc = "000000000050-0000-phone-a")
            dose("d1", "c1", VIT_D)
            dose("d2", "c1", "iron")
            sql("INSERT INTO undo (id, created_at, source, payload, undone_at) VALUES ('u1', 1, 'sheet', '{}', NULL)")
        }
        val db = migrate()
        val live = db.meds().filter { !it.deleted }
        assertEquals(
            setOf(Presets.seedId("c1", 0) to "Vit D", Presets.seedId("c1", 1) to "Vit K", "iron" to "Iron"),
            live.map { it.id to it.name }.toSet(),
        )
        assertTrue(live.all { it.child == "c1" && it.schedule == "as_needed" })
        // Untouched seeds keep the seed stamp, so two phones make the same rows.
        assertTrue(live.filter { it.name.startsWith("Vit") }.all { it.hlc == SEED_HLC })
        // The household's fixed-id seeds are soft-deleted, not removed.
        assertTrue(db.meds().filter { it.id == VIT_D || it.id == VIT_K }.all { it.deleted })
        // The Vit D dose moved to the child's own Vit D, as a change stamped on this phone.
        val (preset, hlc) = db.doseOf("d1")
        assertEquals(Presets.seedId("c1", 0), preset)
        assertTrue(hlc > "000000000100-0000-phone-a" && hlc.endsWith("phone-a"))
        assertEquals("iron", db.doseOf("d2").first)
        assertEquals(1.0, live.single { it.id == "iron" }.amount!!, 0.0)
        // The undo history is cleared; the clock moved on.
        assertEquals(0, db.count("undo"))
        val meta = db.query("SELECT hlc FROM meta").use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue(meta >= hlc)
    }

    @Test
    fun `two children and an archived one each get every med, and each child's doses move to their own`() {
        v1 {
            child("c1", created = 10)
            child("c2", created = 20)
            child("c3", created = 30, archived = 40)
            child("gone", created = 5, deleted = 50)
            preset(VIT_D, "Vit D", amount = 400.0, hlc = "000000000060-0000-phone-a") // edited: an amount set
            preset(VIT_K, "Vit K")
            preset("iron", "Iron", hlc = "000000000050-0000-phone-a")
            dose("d1", "c1", VIT_D)
            dose("d2", "c2", VIT_D)
            dose("d3", "c1", "iron")
            dose("d4", "c2", "iron")
            dose("d5", "c3", "iron")
            dose("d6", "gone", "iron")
        }
        val db = migrate()
        val live = db.meds().filter { !it.deleted }
        // Three meds for each child that isn't deleted, archived included; none for the deleted child.
        for (c in listOf("c1", "c2", "c3")) assertEquals(setOf("Vit D", "Vit K", "Iron"), live.filter { it.child == c }.map { it.name }.toSet())
        assertTrue(live.none { it.child == "gone" })
        // The oldest child keeps the owner's med and its id; the others get copies with derived ids.
        assertEquals("c1", live.single { it.id == "iron" }.child)
        assertEquals("iron", db.doseOf("d3").first)
        assertEquals(splitId("iron", "c2"), db.doseOf("d4").first)
        assertEquals(splitId("iron", "c3"), db.doseOf("d5").first)
        // A deleted child's doses stay as they were.
        assertEquals("iron", db.doseOf("d6").first)
        // Each child's Vit D dose moved to that child's Vit D, which keeps the edited amount.
        assertEquals(Presets.seedId("c1", 0), db.doseOf("d1").first)
        assertEquals(Presets.seedId("c2", 0), db.doseOf("d2").first)
        assertTrue(live.filter { it.name == "Vit D" }.all { it.amount == 400.0 && it.hlc != SEED_HLC })
        assertTrue(live.filter { it.name == "Vit K" }.all { it.hlc == SEED_HLC })
    }

    @Test
    fun `the app works on the migrated data`() = runBlocking {
        v1 {
            child("c1", created = 10)
            child("c2", created = 20)
            preset(VIT_D, "Vit D")
            preset(VIT_K, "Vit K")
            dose("d1", "c2", VIT_D)
        }
        migrate()
        val db = opened.single()
        val repo = Repository(db, {}, { 1_000_000L }, { ZoneId.of("UTC") }, { Locale.UK })
        val st = repo.homeState()
        assertEquals(2, st.children.size)
        // No seeding on top: each child has exactly their own two.
        assertEquals(4, st.presets.size)
        assertEquals(listOf("Vit D", "Vit K"), st.presets.filter { it.childId == "c2" }.map { it.name })
        // A new child gets their own seeds.
        val added = repo.addChild(io.github.uirlisi.mostor.core.ChildFields(name = "Cara"))!!
        val cara = added.info.eventId
        assertEquals(listOf(Presets.seedId(cara, 0), Presets.seedId(cara, 1)), repo.homeState().presets.filter { it.childId == cara }.map { it.id })
        assertNotNull(repo.recordSupplement(Presets.seedId("c2", 0), Source.SHEET))
        assertNull(repo.recordSupplement(VIT_D, Source.SHEET)) // the old household row is gone
    }

    private companion object {
        const val SEED_HLC = "000000000000-0000-seed"
        const val VIT_D = "00000000-0000-7000-8000-00000000d001"
        const val VIT_K = "00000000-0000-7000-8000-00000000d002"
    }
}
