package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.HouseholdList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone's own store of households (SPEC §4.3, §9.4). */
@RunWith(RobolectricTestRunner::class)
class HouseholdStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.getSharedPreferences("phone", Context.MODE_PRIVATE).edit().clear().commit()
        context.databaseList().forEach { context.deleteDatabase(it) }
    }

    @After
    fun tearDown() = clean()

    @Test
    fun `an upgrade from 0_6 keeps the existing database as Household 1, untouched`() {
        // The family's database as 0.6.x left it.
        val db = MoStorDb.build(context, MoStorDb.FILE_NAME)
        db.openHelper.writableDatabase.execSQL("INSERT INTO setting (`key`, value) VALUES ('household_id', 'h1')")
        db.close()

        val store = HouseholdStore(context)
        val list = store.list()
        assertEquals(HouseholdList.first(), list)
        assertEquals(MoStorDb.FILE_NAME, list.active.file)
        val again = MoStorDb.build(context, list.active.file)
        assertEquals("h1", again.openHelper.readableDatabase.query("SELECT value FROM setting WHERE `key` = 'household_id'").use { it.moveToFirst(); it.getString(0) })
        again.close()
    }

    @Test
    fun `the list and the active household survive a restart`() {
        val store = HouseholdStore(context)
        store.update { it.add("abc").activate("abc").rename("first", "Murphy") }
        val restarted = HouseholdStore(context).list()
        assertEquals("abc", restarted.activeId)
        assertEquals("Murphy", restarted.slot("first")!!.name)
        assertTrue(restarted.several)
    }

    @Test
    fun `a household database the store doesn't know comes back on the list`() {
        // As after a lost store: a second household's file is there, the list isn't.
        MoStorDb.build(context, HouseholdList.fileFor("0193-b")).apply { openHelper.writableDatabase; close() }
        val list = HouseholdStore(context).list()
        assertEquals(listOf("first", "0193-b"), list.slots.map { it.id })
        assertEquals("first", list.activeId)
        assertFalse(list.slot("0193-b")!!.isFirst)
    }

    @Test
    fun `a household deleted just before the app stopped stays deleted, and its file goes at the next start`() {
        val file = HouseholdList.fileFor("0193-c")
        val store = HouseholdStore(context)
        store.update { it.add("0193-c") }
        MoStorDb.build(context, file).apply { openHelper.writableDatabase; close() }
        // Taken off the list; the app stops before the file is deleted.
        store.update { it.remove("0193-c") }
        assertTrue(file in context.databaseList())

        val restarted = HouseholdStore(context).list()
        assertEquals(listOf("first"), restarted.slots.map { it.id })
        assertFalse(file in context.databaseList())
        assertTrue(restarted.pendingDelete.isEmpty())
        // Household 1's database is never touched.
        assertTrue(MoStorDb.FILE_NAME !in restarted.pendingDelete)
    }
}
