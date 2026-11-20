package io.github.uirlisi.mostor.notify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.Visibility
import io.github.uirlisi.mostor.data.MoStorDb
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.data.Source
import io.github.uirlisi.mostor.ui.Formats
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

private const val MIN = 60_000L

/** What the status notification says, from real home states (a real repository over Room). */
@RunWith(RobolectricTestRunner::class)
class StatusContentTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 19, 3, 0).atZone(zone).toInstant().toEpochMilli()

    private lateinit var context: Context
    private lateinit var db: MoStorDb
    private lateinit var repo: Repository
    private lateinit var content: StatusContent

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MoStorDb::class.java).build()
        repo = Repository(db, {}, { now }, { zone }, { Locale.UK })
        content = StatusContent(context, Formats(context))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun status() = content.build(repo.homeState())

    private fun labels(m: StatusModel) = m.actions.map { it.label }

    @Test
    fun `with nothing recorded the title says no feeds yet and there are no buttons`() = runBlocking {
        val m = status()
        assertEquals(context.getString(R.string.notif_no_feeds), m.title.text.toString())
        assertNull(m.counterFrom)
        assertTrue(m.actions.isEmpty())
    }

    @Test
    fun `with every topic hidden and nothing running the collapsed line says tap to record`() = runBlocking {
        for (kind in listOf(Kind.BREAST, Kind.BOTTLE, Kind.SLEEP, Kind.DIAPER)) repo.setSetting(Visibility.key(kind), "1")
        val m = status()
        assertTrue(m.sections.all { it.isEmpty() })
        assertEquals(context.getString(R.string.notif_hidden_text), m.collapsed.text.toString())
        assertTrue(m.actions.isEmpty())
    }

    @Test
    fun `a running breast feed has switch side, pause and stop, and paused resume in the middle`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        now += 5 * MIN
        val running = status()
        assertEquals(listOf(R.string.action_switch_side, R.string.action_pause, R.string.action_stop), labels(running))
        assertEquals(
            listOf(ActionReceiver.ACTION_SWITCH, ActionReceiver.ACTION_PAUSE, ActionReceiver.ACTION_STOP),
            running.actions.map { it.action },
        )
        assertTrue(running.actions.all { it.eventId == id && it.undoId == null })
        assertEquals(now - 5 * MIN, running.counterFrom)

        repo.pause(id, Source.SHEET)
        now += 2 * MIN
        val paused = status()
        assertEquals(listOf(R.string.action_switch_side, R.string.action_resume, R.string.action_stop), labels(paused))
        assertEquals(ActionReceiver.ACTION_RESUME, paused.actions[1].action)
        // Paused, nothing counts.
        assertNull(paused.counterFrom)
    }

    @Test
    fun `a running sleep has woke up and cancel`() = runBlocking {
        val id = repo.startSleep(Source.SHEET)!!.info.eventId
        val m = status()
        assertEquals(listOf(R.string.action_woke_up, R.string.action_cancel), labels(m))
        assertEquals(listOf(ActionReceiver.ACTION_STOP, ActionReceiver.ACTION_CANCEL), m.actions.map { it.action })
        assertTrue(m.actions.all { it.eventId == id })
        assertEquals(now, m.counterFrom)
    }

    @Test
    fun `a notification action within the last 10 seconds puts undo first`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        now += MIN
        val done = repo.pause(id, Source.NOTIFICATION)!!
        val m = status()
        // Three buttons at most: Undo takes Switch side's place.
        assertEquals(listOf(R.string.action_undo, R.string.action_resume, R.string.action_stop), labels(m))
        assertEquals(ActionReceiver.ACTION_UNDO, m.actions[0].action)
        assertEquals(done.undoId, m.actions[0].undoId)
        assertNull(m.actions[0].eventId)
        // The latest action leads the first section, and the collapsed line.
        assertEquals(1, m.sections.first().size)
        assertTrue(m.collapsed === m.sections.first().first())

        // Still there after 9 s, gone at 10 s (SPEC §5.7).
        now += 9_000
        assertEquals(R.string.action_undo, labels(status()).first())
        now += 1_000
        assertEquals(listOf(R.string.action_switch_side, R.string.action_resume, R.string.action_stop), labels(status()))
    }

    @Test
    fun `two running sleeps after a merge ask which to keep, with no undo`() = runBlocking {
        val mine = repo.startSleep(Source.SHEET)!!.info.eventId
        // The same child's sleep started two minutes earlier on another phone, as a sync brings it.
        val other = db.events().event(mine)!!.copy(
            id = "other-phone-sleep",
            startAt = now - 2 * MIN,
            createdAt = now - 2 * MIN,
            updatedAt = now - 2 * MIN,
            hlc = HlcStamp(now - 2 * MIN, 0, "other-phone").encode(),
        )
        db.events().upsertEvents(listOf(other))
        now += MIN
        repo.recordDiaper(Diaper.WET, Source.NOTIFICATION)
        val state = repo.homeState()
        assertNotNull(state.recent)
        val k = state.conflicts.single()

        val m = content.build(state)
        assertEquals(listOf(R.string.conflict_keep_earlier, R.string.conflict_keep_later, R.string.conflict_keep_both), labels(m))
        assertEquals(
            listOf(ActionReceiver.ACTION_KEEP_EARLIER, ActionReceiver.ACTION_KEEP_LATER, ActionReceiver.ACTION_KEEP_BOTH),
            m.actions.map { it.action },
        )
        assertTrue(m.actions.all { it.eventId == k.key && it.undoId == null })
        assertEquals(content.conflictActions(k), m.actions)
        // The question is in the first section, under the latest action.
        assertTrue(m.sections.first().any { it.text.toString() == content.conflictStarts(k) })
    }
}
