package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.MedPlan
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.core.Presets
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Change
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EditProblem
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.RowChange
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.TimeField
import io.github.uirlisi.mostor.core.Visibility
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

private const val MIN = 60_000L

/** Phase 2 repository functions: edit, delete, children, presets, settings, the day view and the log. */
@RunWith(RobolectricTestRunner::class)
class EditAndHouseholdTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 11, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private var refreshes = 0

    private lateinit var db: MoStorDb
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MoStorDb::class.java).build()
        repo = Repository(db, { refreshes++ }, { now }, { zone }, { Locale.UK })
    }

    @After
    fun tearDown() = db.close()

    private fun advance(minutes: Int) {
        now += minutes * MIN
    }

    /** A feed L 14:00–14:10, R 14:10–14:25 (now is then 14:25). */
    private suspend fun feed(): String {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(10)
        repo.tapSide(Side.RIGHT, Source.SHEET)
        advance(15)
        repo.stop(id, Source.SHEET)
        return id
    }

    @Test
    fun `an edit of a feed's segments is saved as one action and undone in one step`() = runBlocking {
        val id = feed()
        advance(5)
        val original = repo.loadForEdit(id)!!
        assertEquals(2, original.segments.size)
        var d = EventEdit.draftOf(original.event, original.segments)
        d = EventEdit.removeSegment(d, 1)
        d = EventEdit.nudge(d, TimeField.SegmentEnd(0), 5 * MIN, now)
        val before = refreshes
        val saved = repo.saveEdit(original, d) as SaveResult.Saved
        assertEquals(Verb.EDIT, saved.done.info.verb)
        assertTrue(refreshes > before)

        val after = repo.loadForEdit(id)!!
        assertEquals(1, after.segments.size)
        assertEquals(now - 15 * MIN, after.event.endAt)

        assertTrue(repo.undo(saved.done.undoId))
        val back = repo.loadForEdit(id)!!
        assertEquals(listOf(Side.LEFT, Side.RIGHT), back.segments.map { it.side })
        assertEquals(original.event.endAt, back.event.endAt)
    }

    @Test
    fun `an edit is refused when the record changed since it was opened`() = runBlocking {
        val id = repo.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        advance(10)
        val opened = repo.loadForEdit(id)!!
        // Meanwhile, the feed is stopped from the notification.
        repo.stop(id, Source.NOTIFICATION)
        val d = EventEdit.nudge(EventEdit.draftOf(opened.event, opened.segments), TimeField.SegmentStart(0), -5 * MIN, now)
        assertEquals(SaveResult.Stale, repo.saveEdit(opened, d))
        assertEquals(EventStatus.ENDED, repo.loadForEdit(id)!!.event.status)
    }

    @Test
    fun `invalid and unchanged edits write nothing`() = runBlocking {
        val id = feed()
        val opened = repo.loadForEdit(id)!!
        val d = EventEdit.draftOf(opened.event, opened.segments)
        assertEquals(SaveResult.Unchanged, repo.saveEdit(opened, d))
        val bad = EventEdit.set(d, TimeField.SegmentStart(1), now - 30 * MIN)
        val r = repo.saveEdit(opened, bad) as SaveResult.Invalid
        assertEquals(listOf(EditProblem(EditProblem.Type.OVERLAP, 1)), r.problems)
    }

    @Test
    fun `a record added after the fact, and a delete with undo`() = runBlocking {
        repo.ensureInitialized()
        val child = repo.homeState().childId
        val draft = EventEdit.blank(Kind.DIAPER, child, now).copy(subtype = Diaper.POOP.code, startAt = now - 3 * 60 * MIN)
        val saved = repo.saveEdit(null, draft) as SaveResult.Saved
        assertEquals(Verb.ADD, saved.done.info.verb)
        assertEquals(1, repo.homeState().today.poop)

        val del = repo.delete(saved.done.info.eventId)!!
        assertEquals(0, repo.homeState().today.poop)
        assertTrue(repo.undo(del.undoId))
        assertEquals(1, repo.homeState().today.poop)
    }

    @Test
    fun `overlaps are found for the edit screen`() = runBlocking {
        val sleep = repo.startSleep(Source.SHEET)!!.info.eventId
        advance(60)
        repo.stop(sleep, Source.SHEET)
        val child = repo.homeState().childId
        val draft = EventEdit.blank(Kind.SLEEP, child, now).copy(startAt = now - 30 * MIN)
        assertEquals(listOf(sleep), repo.overlapsFor(draft).map { it.id })
    }

    @Test
    fun `a second child records separately and the first stays out of the way`() = runBlocking {
        repo.ensureInitialized()
        assertFalse(repo.homeState().severalChildren)
        val first = repo.homeState().childId
        val added = repo.addChild(ChildFields(name = "Aoife"))!!
        val second = added.info.eventId
        val st = repo.homeState()
        assertTrue(st.severalChildren)
        assertEquals(first, st.childId)
        assertEquals("Aoife", st.childOf(second)?.name)

        repo.recordDiaper(Diaper.WET, Source.SHEET)
        repo.setActiveChild(second)
        repo.recordDiaper(Diaper.POOP, Source.SHEET)
        val s2 = repo.homeState()
        assertEquals(second, s2.childId)
        assertEquals(1, s2.today.poop)
        assertEquals(0, s2.today.wet)
        assertEquals(1, s2.others.single().today.wet)
    }

    @Test
    fun `deleting a child hides its records, undo brings them back, the last child stays`() = runBlocking {
        repo.ensureInitialized()
        val first = repo.homeState().childId
        assertNull(repo.deleteChild(first))
        val second = repo.addChild(ChildFields(name = "Aoife"))!!.info.eventId
        repo.setActiveChild(second)
        repo.startSleep(Source.SHEET)
        assertEquals(1, repo.homeState().running.size)

        val del = repo.deleteChild(second)!!
        val st = repo.homeState()
        assertEquals(first, st.childId)
        assertTrue(st.running.isEmpty())
        assertFalse(st.severalChildren)

        assertTrue(repo.undo(del.undoId))
        assertEquals(1, repo.homeState().running.size)
    }

    @Test
    fun `archiving keeps a child's records but takes it off the sheet, never the last one`() = runBlocking {
        repo.ensureInitialized()
        val first = repo.homeState().childId
        assertNull(repo.setChildArchived(first, true))
        val second = repo.addChild(ChildFields())!!.info.eventId
        repo.setActiveChild(second)
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        repo.setChildArchived(second, true)
        val st = repo.homeState()
        assertEquals(first, st.childId)
        assertFalse(st.severalChildren)
        // Still in the log.
        assertEquals(1, repo.logView(7, LogFilter()).days.single().items.size)
        assertNotNull(repo.setChildArchived(second, false))
        assertTrue(repo.homeState().severalChildren)
    }

    @Test
    fun `presets are added, renamed, moved and deleted, each undoable`() = runBlocking {
        repo.ensureInitialized()
        val child = repo.homeState().childId
        val add = repo.addPreset(child, "Iron", 1.0, "ml", MedPlan())!!
        val iron = add.info.eventId
        assertEquals(listOf("Vit D", "Vit K", "Iron"), repo.homeState().presets.map { it.name })
        repo.movePreset(iron, -1)
        repo.movePreset(iron, -1)
        assertEquals(listOf("Iron", "Vit D", "Vit K"), repo.homeState().presets.map { it.name })
        val vitD = repo.homeState().presets[1]
        val edit = repo.editPreset(vitD.id, "Vit D", 400.0, "IU", MedPlan())!!
        assertEquals(400.0, repo.homeState().presets[1].amount!!, 0.0)
        // The edited seed row now has a real stamp, so it wins over the seed when synced.
        assertTrue(repo.homeState().presets[1].hlc > Presets.SEED_HLC)
        assertTrue(repo.undo(edit.undoId))
        assertNull(repo.homeState().presets[1].amount)
        val del = repo.deletePreset(iron)!!
        assertEquals(listOf("Vit D", "Vit K"), repo.homeState().presets.map { it.name })
        assertTrue(repo.undo(del.undoId))
        assertEquals(3, repo.homeState().presets.size)
        assertNull(repo.addPreset(child, "  ", null, null, MedPlan()))
    }

    @Test
    fun `hidden kinds leave the summary but stay in the log behind a filter`() = runBlocking {
        repo.recordBottle(BottleContent.FORMULA, 90.0, Source.SHEET)
        repo.recordDiaper(Diaper.DRY, Source.SHEET)
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        assertEquals(1, repo.homeState().today.feeds)
        repo.setSetting(Visibility.key(Kind.BOTTLE, BottleContent.FORMULA.code), "1")
        repo.setSetting(Visibility.key(Kind.DIAPER, Diaper.DRY.code), "1")

        val st = repo.homeState()
        assertEquals(0, st.today.feeds)
        assertEquals(1, st.today.diapers)
        assertEquals(listOf(BottleContent.BREAST_MILK), st.settings.visibility.bottleContents)

        val log = repo.logView(7, LogFilter())
        assertEquals(1, log.days.single().items.size)
        assertEquals(2, log.hiddenCount)
        assertEquals(3, repo.logView(7, LogFilter(showHidden = true)).days.single().items.size)
        val today = repo.dayView(0)
        assertEquals(1, today.items.size)
        assertEquals(2, today.hiddenCount)

        repo.setSetting(Visibility.key(Kind.BOTTLE, BottleContent.FORMULA.code), null)
        assertEquals(1, repo.homeState().today.feeds)
    }

    @Test
    fun `the log filters by kind and subtype and pages by days`() = runBlocking {
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        advance(-3 * 24 * 60)
        repo.recordDiaper(Diaper.POOP, Source.SHEET)
        advance(3 * 24 * 60)
        repo.recordPump(100.0, Source.SHEET)

        val week = repo.logView(7, LogFilter())
        assertEquals(2, week.days.size)
        assertFalse(week.hasOlder)
        val twoDays = repo.logView(2, LogFilter())
        assertEquals(1, twoDays.days.size)
        assertTrue(twoDays.hasOlder)
        assertEquals(2, repo.logView(7, LogFilter(kind = Kind.DIAPER)).days.sumOf { it.items.size })
        assertEquals(1, repo.logView(7, LogFilter(kind = Kind.DIAPER, subtype = "poop")).days.sumOf { it.items.size })
    }

    @Test
    fun `possible duplicates from another phone are flagged and one is kept`() = runBlocking {
        val mine = repo.recordDiaper(Diaper.WET, Source.SHEET)!!.info.eventId
        val child = repo.homeState().childId
        // A record made on another phone 2 minutes later (as sync will bring in Phase 5).
        val other = db.events().event(mine)!!.copy(
            id = "other-phone-record",
            startAt = now + 2 * MIN,
            hlc = HlcStamp(now + 2 * MIN, 0, "other-phone").encode(),
            childId = child,
        )
        db.events().upsertEvents(listOf(other))
        val items = repo.dayView(0).items
        assertEquals(setOf("other-phone-record"), items.first { it.event.id == mine }.duplicates)
        assertEquals(setOf(mine), items.first { it.event.id == "other-phone-record" }.duplicates)

        val drop = repo.delete("other-phone-record")!!
        assertEquals(Verb.DELETE, drop.info.verb)
        assertTrue(repo.dayView(0).items.single().duplicates.isEmpty())
    }

    @Test
    fun `today's totals per child, and all children when asked`() = runBlocking {
        feed()
        val one = repo.dayView(0)
        assertNull(one.totals.single().child)
        assertEquals(10 * MIN, one.totals.single().totals.breastLeftMs)
        val second = repo.addChild(ChildFields(name = "Aoife"))!!.info.eventId
        repo.setSetting(Keys.TODAY_CHILD, Keys.ALL_CHILDREN)
        val all = repo.dayView(0)
        assertEquals(2, all.totals.size)
        assertEquals(second, all.totals[1].child?.id)
        assertTrue(all.several)

        // Picking a child (on Today or in the quick sheet) is one shared choice: Today shows that
        // child, and the next record is for it.
        repo.setActiveChild(second)
        val hers = repo.dayView(0)
        assertEquals(listOf(second), hers.childIds)
        assertTrue(hers.items.isEmpty())
        assertEquals(second, repo.homeState().childId)
        assertNull(repo.settings().todayChild)
    }

    @Test
    fun `today shows several children, and choosing one records for it`() = runBlocking {
        val first = repo.homeState().childId
        feed()
        val second = repo.addChild(ChildFields(name = "Aoife"))!!.info.eventId
        val third = repo.addChild(ChildFields(name = "Niamh"))!!.info.eventId

        // Two of three, given in any order: stored in child order, shown with their cards.
        repo.setTodayChildren(listOf(third, first))
        assertEquals("$first,$third", repo.settings().todayChild)
        val two = repo.dayView(0)
        assertEquals(listOf(first, third), two.childIds)
        assertEquals(listOf(first, third), two.totals.map { it.child?.id })
        assertEquals(1, two.items.size)
        assertEquals(first, repo.homeState().childId)

        // Everyone is stored as "all", so a child added later shows too.
        repo.setTodayChildren(listOf(first, second, third))
        assertEquals(Keys.ALL_CHILDREN, repo.settings().todayChild)
        assertEquals(3, repo.dayView(0).totals.size)

        // An archived child drops out of a stored list.
        repo.setTodayChildren(listOf(second, third))
        repo.setChildArchived(third, true)
        assertEquals(listOf(second), repo.dayView(0).childIds)
        repo.setChildArchived(third, false)

        // Exactly one: that child becomes the one this phone records for, and Today follows it.
        repo.setTodayChildren(listOf(third))
        assertNull(repo.settings().todayChild)
        assertEquals(third, repo.homeState().childId)
        assertEquals(listOf(third), repo.dayView(0).childIds)
        assertFalse(repo.dayView(0).several)

        // Switching child on the sheet clears a choice of several.
        repo.setTodayChildren(listOf(first, second))
        repo.setActiveChild(third)
        assertEquals(listOf(third), repo.dayView(0).childIds)
    }

    @Test
    fun `the log filters by several children`() = runBlocking {
        val first = repo.homeState().childId
        repo.recordDiaper(Diaper.WET, Source.SHEET)
        val second = repo.addChild(ChildFields(name = "Aoife"))!!.info.eventId
        val third = repo.addChild(ChildFields(name = "Niamh"))!!.info.eventId
        for (c in listOf(second, third)) {
            repo.setActiveChild(c)
            repo.recordDiaper(Diaper.POOP, Source.SHEET)
        }
        fun count(ids: Set<String>) = runBlocking { repo.logView(7, LogFilter(childIds = ids)).days.sumOf { it.items.size } }
        assertEquals(3, count(emptySet()))
        assertEquals(2, count(setOf(first, third)))
        assertEquals(1, count(setOf(second)))
    }

    @Test
    fun `default amounts win over the last used`() = runBlocking {
        repo.recordBottle(BottleContent.BREAST_MILK, 90.0, Source.SHEET)
        assertEquals(90.0, repo.settings().bottleStartMl!!, 0.0)
        repo.setSetting(Keys.DEFAULT_BOTTLE_ML, "120")
        assertEquals(120.0, repo.settings().bottleStartMl!!, 0.0)
    }
}

@RunWith(RobolectricTestRunner::class)
class UndoCodecPhase2Test {
    @Test
    fun `children and presets survive a round trip, and old payloads still read`() {
        val child = Child("c1", name = "Aoife", birthDate = "2026-10-30", sex = "f", birthWeightG = 3400, color = -0x10000,
            createdAt = 1, updatedAt = 1, hlc = "000000000001-0000-a")
        val preset = SupplementPreset(
            "p1", "c1", "Vit D", amount = 400.0, unit = "IU", sortKey = 2, schedule = MedSchedule.WEEKLY, scheduleWeekday = 6,
            scheduleStart = "2026-11-10", createdAt = 1, updatedAt = 1, hlc = "h",
        )
        val payload = UndoPayload(
            ActionInfo(Verb.CHILD_EDIT, null, "c1", 5, name = "Aoife"),
            Change(children = listOf(RowChange(child, child.copy(name = "Aoife B"))), presets = listOf(RowChange(null, preset))),
        )
        assertEquals(payload, UndoCodec.decode(UndoCodec.encode(payload)))

        // An entry written by Phase 1 (no children, no presets, kind always set).
        val old = """{"v":1,"info":{"verb":"RECORD","kind":"diaper","event_id":"e1","at":5,"subtype":"wet"},"events":[],"segments":[]}"""
        val decoded = UndoCodec.decode(old)
        assertEquals(Kind.DIAPER, decoded.info.kind)
        assertTrue(decoded.change.children.isEmpty())
        assertTrue(decoded.change.presets.isEmpty())
    }
}
