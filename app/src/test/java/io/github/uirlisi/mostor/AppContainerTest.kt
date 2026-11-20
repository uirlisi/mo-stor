package io.github.uirlisi.mostor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.data.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Adding, switching and deleting households (SPEC §9.4 "Households on one phone"). */
@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        context.getSharedPreferences("phone", Context.MODE_PRIVATE).edit().clear().commit()
        context.databaseList().forEach { context.deleteDatabase(it) }
        c = AppContainer(context)
    }

    @After
    fun tearDown() {
        context.databaseList().forEach { context.deleteDatabase(it) }
    }

    /** A blank child, the owner's choices, no records, active. (Short name: Robolectric's temp path must fit Windows.) */
    @Test
    fun `an added household starts blank`() = runBlocking {
        val first = c.repository
        first.setSetting("day_start_hour", "6")
        first.recordDiaper(Diaper.WET, Source.SHEET)
        val firstId = first.sync.deviceId()

        c.addHousehold("Murphy")
        val list = c.households.list()
        assertEquals(2, list.slots.size)
        assertEquals("Murphy", list.active.name)
        assertEquals("Murphy", c.activeNameIfSeveral())
        val added = c.repository
        assertNotEquals(firstId, added.sync.deviceId())
        assertEquals(1, added.homeState().children.size)
        assertTrue(added.sync.holdsOnlyBlank())
        assertTrue(added.backupRows().events.isEmpty())
        assertEquals(6, added.settings().dayStartHour)
        assertEquals(setOf(firstId, added.sync.deviceId()), c.households.list().slots.map { c.repositoryFor(it).sync.deviceId() }.toSet())

        // Back to the first: its records are as they were.
        c.switchTo(list.others.single().id)
        assertEquals(1, c.repository.backupRows().events.size)
        assertEquals(null, c.households.list().active.name)
    }

    @Test
    fun `deleting a household removes its database, and the last one can't go`() = runBlocking {
        c.repository.ensureInitialized()
        c.addHousehold(null)
        val added = c.households.list().active
        assertTrue(added.file in context.databaseList())
        c.deleteHousehold(added.id)
        assertFalse(c.households.list().several)
        assertFalse(added.file in context.databaseList())
        try {
            c.deleteHousehold(c.households.list().activeId)
            throw AssertionError("deleted the last household")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
