package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.OverrunState
import io.github.uirlisi.mostor.core.Side
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The repository against a real (in-memory) Room database, under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 10, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private var refreshes = 0

    private lateinit var db: MoStorDb
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MoStorDb::class.java).build()
        repo = newRepository()
    }

    private fun newRepository() = Repository(db, { refreshes++ }, { now }, { zone }, { Locale.UK })

    @After
    fun tearDown() = db.close()

    private fun advance(minutes: Int) {
        now += minutes * MIN
    }

    @Test
    fun `first use creates the device id, one child and the two presets, once`() = runBlocking {
        repo.ensureInitialized()
        val meta = db.meta().get()!!
        assertEquals(1, db.children().countLive())
        assertEquals(listOf("Vit D", "Vit K"), db.presets().live().map { it.name })
        assertTrue(db.presets().live().all { it.amount == null })

        // A second repository on the same database (a new process) reuses all of it.
        newRepository().ensureInitialized()
        assertEquals(meta.deviceId, db.meta().get()!!.deviceId)
        assertEquals(1, db.children().countLive())
        assertEquals(2, db.presets().live().size)
    }

    @Test
    fun `a swiped-away notification is remembered quietly until cleared`() = runBlocking {
        repo.ensureInitialized()
        assertFalse(repo.settings().statusDismissed)
        val before = refreshes
        repo.setStatusDismissed(true)
        assertTrue(repo.settings().statusDismissed)
        repo.setStatusDismissed(false)
        assertFalse(repo.settings().statusDismissed)
        // The notifier decides what to post; marking it doesn't trigger a refresh of its own.
        assertEquals(before, refreshes)
    }

    @Test
    fun `a deleted seed preset is not seeded again`() = runBlocking {
        repo.ensureInitialized()
        val vitK = db.presets().live().first { it.name == "Vit K" }
        db.presets().upsert(listOf(vitK.copy(deletedAt = now)))
        newRepository().ensureInitialized()
        assertEquals(listOf("Vit D"), db.presets().live().map { it.name })
    }

    @Test
    fun `left starts a feed, the same side again does nothing, the other side switches`() = runBlocking {
        val start = repo.tapSide(Side.LEFT, Source.SHEET)!!
        assertEquals(Verb.START, start.info.verb)
        advance(10)
        assertNull(repo.tapSide(Side.LEFT, Source.SHEET))
        val switch = repo.tapSide(Side.RIGHT, Source.SHEET)!!
        assertEquals(Verb.SWITCH, switch.info.verb)

        val running = repo.homeState().running.single()
        assertEquals(Side.RIGHT, running.timed.openSegment?.side)
        assertEquals(2, running.timed.segments.size)
    }

    @Test
    fun `the last feed's duration counts both sides, and only once it has ended`() = runBlocking {
        val start = repo.tapSide(Side.LEFT, Source.SHEET)!!
        advance(10)
        repo.tapSide(Side.RIGHT, Source.SHEET)
        advance(15)
        assertNull(repo.homeState().lastFeedMs)
        repo.stop(start.info.eventId, Source.SHEET, minutesAgo = 5)
        advance(60)
        val s = repo.homeState()
        assertEquals(20 * MIN, s.lastFeedMs)
        assertEquals(Side.RIGHT, s.lastSide)
    }

    @Test
    fun `stop ten minutes ago, then undo, runs again`() = runBlocking {
        val start = repo.tapSide(Side.LEFT, Source.NOTIFICATION)!!
        advance(30)
        val stop = repo.stop(start.info.eventId, Source.NOTIFICATION, minutesAgo = 10)!!
        assertEquals(now - 10 * MIN, stop.info.at)
        assertTrue(repo.homeState().running.isEmpty())

        assertTrue(repo.undo(stop.undoId))
        assertEquals(EventStatus.RUNNING, repo.homeState().running.single().timed.event.status)
        // Undoing twice does nothing.
        assertFalse(repo.undo(stop.undoId))
    }

    @Test
    fun `undo of a start is refused once the timer was stopped`() = runBlocking {
        val start = repo.startSleep(Source.SHEET)!!
        advance(60)
        repo.stop(start.info.eventId, Source.SHEET)
        assertFalse(repo.undo(start.undoId))
    }

    @Test
    fun `started ten minutes ago, then undo removes the feed and its segment`() = runBlocking {
        val start = repo.tapSide(Side.LEFT, Source.SHEET)!!
        val adjusted = repo.adjust(start.undoId, now - 10 * MIN)!!
        assertEquals(now - 10 * MIN, adjusted.info.at)
        val t = repo.homeState().running.single().timed
        assertEquals(now - 10 * MIN, t.event.startAt)
        assertEquals(now - 10 * MIN, t.segments.single().startAt)

        // A second fix replaces the first (the chips are relative to the tap, not cumulative).
        repo.adjust(start.undoId, now - 5 * MIN)
        assertEquals(now - 5 * MIN, repo.homeState().running.single().timed.event.startAt)

        assertTrue(repo.undo(start.undoId))
        assertTrue(repo.homeState().running.isEmpty())
        assertNotNull(db.events().event(start.info.eventId)!!.deletedAt)
    }

    @Test
    fun `a switch can be moved earlier`() = runBlocking {
        repo.tapSide(Side.LEFT, Source.SHEET)
        advance(15)
        val switch = repo.tapSide(Side.RIGHT, Source.SHEET)!!
        repo.adjust(switch.undoId, now - 5 * MIN)
        val segs = repo.homeState().running.single().timed.segments
        assertEquals(now - 5 * MIN, segs[0].endAt)
        assertEquals(now - 5 * MIN, segs[1].startAt)
    }

    @Test
    fun `cancel discards a sleep and undo brings it back`() = runBlocking {
        val start = repo.startSleep(Source.NOTIFICATION)!!
        val cancel = repo.cancel(start.info.eventId, Source.NOTIFICATION)!!
        assertTrue(repo.homeState().running.isEmpty())
        assertTrue(repo.undo(cancel.undoId))
        assertEquals(Kind.SLEEP, repo.homeState().running.single().timed.event.kind)
    }

    @Test
    fun `keeping one of several duplicates deletes the others with one undo`() = runBlocking {
        val a = repo.recordDiaper(Diaper.WET, Source.SHEET)!!.info.eventId
        val b = repo.recordDiaper(Diaper.WET, Source.SHEET)!!.info.eventId
        val c = repo.recordDiaper(Diaper.WET, Source.SHEET)!!.info.eventId
        val done = repo.deleteAll(listOf(b, c))!!
        assertEquals(listOf(a), db.events().all().filter { it.deletedAt == null }.map { it.id })
        assertTrue(repo.undo(done.undoId))
        assertEquals(setOf(a, b, c), db.events().all().filter { it.deletedAt == null }.map { it.id }.toSet())
    }

    @Test
    fun `undoing a stop is refused once another sleep has started, so only one runs`() = runBlocking {
        val first = repo.startSleep(Source.SHEET)!!
        advance(10)
        val stop = repo.stop(first.info.eventId, Source.SHEET)!!
        advance(5)
        repo.startSleep(Source.NOTIFICATION)!!
        assertFalse(repo.undo(stop.undoId))
        assertEquals(1, repo.homeState().running.size)
    }

    @Test
    fun `point records, today's counts, last feed and last side`() = runBlocking {
        val feed = repo.tapSide(Side.RIGHT, Source.SHEET)!!
        advance(20)
        repo.stop(feed.info.eventId, Source.SHEET)
        advance(60)
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        repo.recordDiaper(Diaper.MIXED, Source.SHEET)
        repo.recordBottle(BottleContent.FORMULA, 90.0, Source.SHEET)
        repo.recordSupplement(db.presets().live().first().id, Source.SHEET)
        repo.recordPump(120.0, Source.SHEET)
        repo.recordNote("  hiccups  ", Source.SHEET)
        assertNull(repo.recordNote("   ", Source.SHEET))

        val s = repo.homeState()
        assertEquals(2, s.today.feeds)
        assertEquals(2, s.today.wet)
        assertEquals(1, s.today.poop)
        assertEquals(Kind.BOTTLE, s.lastFeed?.kind)
        assertEquals(Side.RIGHT, s.lastSide)
        assertNull(s.lastFeedMs) // a bottle has no duration
        assertEquals(90.0, s.settings.lastBottleMl!!, 0.0)
        assertEquals(BottleContent.FORMULA, s.settings.lastBottleContent)
        assertEquals(120.0, s.settings.lastPumpMl!!, 0.0)

        val day = repo.dayView(0)
        assertEquals(7, day.items.size)
        assertEquals("hiccups", day.items.first().event.note)
        assertEquals("Vit D", day.items.first { it.event.kind == Kind.SUPPLEMENT }.event.supplementName)
    }

    @Test
    fun `awake since the last sleep ended`() = runBlocking {
        val sleep = repo.startSleep(Source.SHEET)!!
        assertNull(repo.homeState().awakeSince)
        advance(90)
        repo.stop(sleep.info.eventId, Source.SHEET, minutesAgo = 5)
        assertEquals(now - 5 * MIN, repo.homeState().awakeSince)
    }

    @Test
    fun `a notification action shows Undo for 10 seconds`() = runBlocking {
        repo.recordDiaper(Diaper.POOP, Source.NOTIFICATION)
        val recent = repo.homeState().recent
        assertNotNull(recent)
        assertEquals(now + Repository.RECENT_MS, repo.homeState().nextWakeAt)
        now += Repository.RECENT_MS
        assertNull(repo.homeState().recent)

        // Sheet actions have their own strip, not the notification's.
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        assertNull(repo.homeState().recent)
    }

    @Test
    fun `overrun - nudge, still going, then give up and set the end later`() = runBlocking {
        val start = repo.tapSide(Side.LEFT, Source.SHEET)!!
        val id = start.info.eventId
        assertEquals(now + 45 * MIN, repo.homeState().nextWakeAt)

        advance(45)
        assertEquals(OverrunState.NUDGE, repo.homeState().running.single().overrun)
        repo.stillGoing(id)
        assertEquals(OverrunState.NORMAL, repo.homeState().running.single().overrun)
        advance(45)
        assertEquals(OverrunState.NUDGE, repo.homeState().running.single().overrun)

        advance(90) // 3 h after the start
        repo.applyOverruns()
        val s = repo.homeState()
        assertTrue(s.running.isEmpty())
        assertEquals(id, s.noEnd.single().event.id)
        assertEquals(EventStatus.END_UNKNOWN, s.noEnd.single().event.status)
        assertNull(s.noEnd.single().event.endAt)

        val set = repo.stop(id, Source.SHEET, at = start.info.at + 25 * MIN)!!
        assertEquals(Verb.SET_END, set.info.verb)
        assertTrue(repo.homeState().noEnd.isEmpty())
        assertEquals(start.info.at + 25 * MIN, db.events().event(id)!!.endAt)
    }

    @Test
    fun `a dismissed unknown end stops asking`() = runBlocking {
        val start = repo.startSleep(Source.SHEET)!!
        advance(14 * 60)
        repo.applyOverruns()
        assertEquals(1, repo.homeState().noEnd.size)
        repo.dismissNoEnd(start.info.eventId)
        assertTrue(repo.homeState().noEnd.isEmpty())
        assertEquals(EventStatus.END_UNKNOWN.code, db.events().event(start.info.eventId)!!.status)
    }

    @Test
    fun `every write refreshes the notification and stamps rising HLCs`() = runBlocking {
        val before = refreshes
        val a = repo.recordDiaper(Diaper.WET, Source.SHEET)!!
        val b = repo.recordDiaper(Diaper.DRY, Source.SHEET)!!
        assertEquals(before + 2, refreshes)
        val ha = HlcStamp.parse(db.events().event(a.info.eventId)!!.hlc)
        val hb = HlcStamp.parse(db.events().event(b.info.eventId)!!.hlc)
        assertTrue(hb > ha)
        assertEquals(db.meta().get()!!.deviceId, hb.origin)
        // The clock's state survives a restart: a new process keeps counting up.
        val c = newRepository()
        val third = c.recordDiaper(Diaper.WET, Source.SHEET)!!
        assertTrue(HlcStamp.parse(db.events().event(third.info.eventId)!!.hlc) > hb)
    }

    @Test
    fun `days follow the day-start setting`() = runBlocking {
        db.settings().put(SettingEntity(Keys.DAY_START_HOUR, "6"))
        now = LocalDateTime.of(2026, 11, 10, 5, 0).atZone(zone).toInstant().toEpochMilli()
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        now = LocalDateTime.of(2026, 11, 10, 7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0, repo.homeState().today.wet)
        assertEquals(1, repo.dayView(-1).counts.wet)
        assertEquals(LocalDateTime.of(2026, 11, 11, 6, 0).atZone(zone).toInstant().toEpochMilli(), repo.homeState().nextWakeAt)
    }

    @Test
    fun `pause, resume on the other side with a tap, undo`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.NOTIFICATION)!!.info.eventId
        advance(10)
        val pause = repo.pause(id, Source.NOTIFICATION)!!
        assertEquals(Verb.PAUSE, pause.info.verb)
        assertTrue(repo.homeState().running.single().timed.isPaused)
        // Pausing twice does nothing.
        assertNull(repo.pause(id, Source.NOTIFICATION))

        advance(5)
        val resume = repo.tapSide(Side.RIGHT, Source.SHEET)!!
        assertEquals(Verb.RESUME, resume.info.verb)
        val t = repo.homeState().running.single().timed
        assertEquals(Side.RIGHT, t.openSegment?.side)
        assertEquals(now, t.openSegment?.startAt)

        assertTrue(repo.undo(resume.undoId))
        assertTrue(repo.homeState().running.single().timed.isPaused)
    }

    @Test
    fun `switch side on a paused feed resumes on the other side, resume keeps the side`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.NOTIFICATION)!!.info.eventId
        advance(10)
        repo.pause(id, Source.NOTIFICATION)
        advance(2)
        assertEquals(Verb.RESUME, repo.switchSide(id, Source.NOTIFICATION)!!.info.verb)
        assertEquals(Side.RIGHT, repo.homeState().running.single().timed.openSegment?.side)
        advance(5)
        repo.pause(id, Source.NOTIFICATION)
        advance(1)
        repo.resume(id, Source.NOTIFICATION)
        assertEquals(Side.RIGHT, repo.homeState().running.single().timed.openSegment?.side)
    }

    @Test
    fun `the pause can be moved earlier from the strip`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(20)
        val pause = repo.pause(id, Source.SHEET)!!
        val moved = repo.adjust(pause.undoId, now - 5 * MIN)!!
        assertEquals(now - 5 * MIN, moved.info.at)
        assertEquals(now - 5 * MIN, repo.homeState().running.single().timed.pausedAt)
        // One Undo takes back the pause and its fix.
        assertTrue(repo.undo(pause.undoId))
        assertEquals(null, repo.homeState().running.single().timed.openSegment?.endAt)
    }

    @Test
    fun `a forgotten pause ends the feed at the pause, said in a notification of its own`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(10)
        val pausedAt = now
        repo.pause(id, Source.SHEET)
        advance(44)
        repo.applyOverruns()
        assertEquals(1, repo.homeState().running.size)
        assertEquals(pausedAt + 45 * MIN, repo.homeState().nextWakeAt)

        advance(1)
        repo.applyOverruns()
        val s = repo.homeState()
        assertTrue(s.running.isEmpty())
        val e = db.events().event(id)!!
        assertEquals(EventStatus.ENDED.code, e.status)
        assertEquals(pausedAt, e.endAt)
        assertEquals(10 * MIN, s.lastFeedMs)
        // Not the 10 s "Recorded …" line (nobody tapped anything), but a notification that stays.
        assertNull(s.recent)
        assertTrue(s.noEnd.isEmpty())
        assertEquals(id, s.autoEnded.single().event.id)

        // "Set end…" moves the end; a person's change, so the notification goes.
        advance(5)
        val moved = repo.moveEnd(id, pausedAt + 3 * MIN, Source.SHEET)!!
        assertEquals(Verb.SET_END, moved.info.verb)
        assertEquals(pausedAt + 3 * MIN, db.events().event(id)!!.endAt)
        assertTrue(repo.homeState().autoEnded.isEmpty())
        // Undo puts the end back at the pause; a person's change too, so the notification stays gone.
        assertTrue(repo.undo(moved.undoId))
        assertEquals(pausedAt, db.events().event(id)!!.endAt)
        assertTrue(repo.homeState().autoEnded.isEmpty())
    }

    @Test
    fun `a feed stopped at its pause stops being shown when dismissed or when the next feed starts`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(10)
        repo.pause(id, Source.SHEET)
        advance(45)
        repo.applyOverruns()
        assertEquals(id, repo.homeState().autoEnded.single().event.id)

        advance(5)
        repo.recordBottle(BottleContent.FORMULA, 90.0, Source.SHEET)
        assertTrue(repo.homeState().autoEnded.isEmpty())
    }

    @Test
    fun `a dismissed automatic stop stays dismissed`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(10)
        repo.pause(id, Source.SHEET)
        advance(45)
        repo.applyOverruns()
        repo.dismissNoEnd(id)
        assertTrue(repo.homeState().autoEnded.isEmpty())
    }

    @Test
    fun `home state has the last diaper and pump, and supplements given today`() = runBlocking {
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        advance(5)
        repo.recordDiaper(Diaper.POOP, Source.SHEET)
        repo.recordPump(100.0, Source.SHEET)
        val vitD = db.presets().live().first { it.name == "Vit D" }.id
        repo.recordSupplement(vitD, Source.SHEET)
        val s = repo.homeState()
        assertEquals(Diaper.POOP.code, s.lastDiaper?.subtype)
        assertEquals(now, s.lastPump?.startAt)
        assertEquals(mapOf(vitD to now), s.supplementsToday)
    }

    private companion object {
        const val MIN = 60_000L
    }
}
