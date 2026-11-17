package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.ChartFocus
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Metric
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.Visibility
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Patterns and Stats data (SPEC §5.8, §6.5): range, chart child, hidden types, deleted children. */
@RunWith(RobolectricTestRunner::class)
class ChartViewTest {
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = at(11, 14)

    private lateinit var db: MoStorDb
    private lateinit var repo: Repository

    private fun at(day: Int, h: Int, mi: Int = 0) = LocalDateTime.of(2026, 11, day, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MoStorDb::class.java).build()
        repo = Repository(db, { }, { now }, { zone }, { Locale.UK })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun view(range: ChartRange = ChartRange.D7, focus: ChartFocus = ChartFocus.All, showHidden: Boolean = false) =
        repo.chartView(range, focus, showHidden, withPatterns = true, withGaps = true)

    /** Records at [time], then comes back to the test's "now". */
    private suspend fun <T> whenAt(time: Long, block: suspend () -> T): T {
        val saved = now
        now = time
        return block().also { now = saved }
    }

    @Test
    fun `a range ends today and covers its days, with the sleep from the night before`() = runBlocking {
        whenAt(at(4, 22)) { repo.startSleep(Source.SHEET) }
        whenAt(at(5, 6)) { repo.stop(repo.homeState().running.single().timed.event.id, Source.SHEET) }
        val v = view()
        assertEquals(day(5), v.from)
        assertEquals(day(11), v.to)
        assertEquals(7, v.points.size)
        assertEquals(7, v.patterns.size)
        // The part after midnight on the 5th; the 4th is outside the range.
        assertEquals(6.0, Metric.SLEEP_MS.total(v.points.first()) / 3_600_000.0, 0.0)
        assertEquals(day(4), v.firstDay)
        assertEquals(listOf(0 to 360), v.patterns.first().bars.map { it.fromMin to it.toMin })
    }

    @Test
    fun `all starts at the child's first record`() = runBlocking {
        whenAt(at(8, 9)) { repo.recordDiaper(Diaper.WET, Source.SHEET) }
        val v = view(ChartRange.ALL)
        assertEquals(day(8), v.from)
        assertEquals(4, v.patterns.size)
        assertEquals(listOf(false, false, false, true), v.points.map { it.partial })
        assertEquals(listOf(false, false, false, false), v.points.map { it.beforeFirst })
    }

    @Test
    fun `hidden types are left out and counted, unless shown`() = runBlocking {
        repo.recordBottle(BottleContent.FORMULA, 90.0, Source.SHEET)
        repo.recordBottle(BottleContent.BREAST_MILK, 60.0, Source.SHEET)
        repo.setSetting(Visibility.key(Kind.BOTTLE, BottleContent.FORMULA.code), "1")
        val hidden = view()
        assertEquals(1, hidden.hiddenCount)
        assertEquals(listOf(60.0, 0.0), Metric.BOTTLE_ML.values(hidden.points.last()))
        val shown = view(showHidden = true)
        assertEquals(0, shown.hiddenCount)
        assertEquals(listOf(60.0, 90.0), Metric.BOTTLE_ML.values(shown.points.last()))
    }

    @Test
    fun `the chart child is its own choice and never changes who is recorded for`() = runBlocking {
        val first = repo.homeState().childId
        val second = repo.addChild(ChildFields(name = "Aoife"))!!.info.eventId
        repo.recordDiaper(Diaper.POOP, Source.SHEET) // for the first (active) child
        assertEquals(first, view().childId)
        assertEquals(2, view().children.size)

        repo.setSetting(Keys.CHART_CHILD, second)
        val v = view()
        assertEquals(second, v.childId)
        assertEquals(0.0, Metric.DIAPERS.total(v.points.last()), 0.0)
        assertEquals(1, v.colorIndex(second))
        assertEquals(first, repo.homeState().childId)

        // A deleted chart child falls back to the child being recorded for.
        repo.deleteChild(second)
        assertEquals(first, view().childId)
    }

    @Test
    fun `a focus narrows the counts, the side and the gaps`() = runBlocking {
        whenAt(at(11, 8)) { repo.tapSide(Side.LEFT, Source.SHEET) }
        val feed = repo.homeState().running.single().timed.event.id
        whenAt(at(11, 8, 10)) { repo.tapSide(Side.RIGHT, Source.SHEET) }
        whenAt(at(11, 8, 25)) { repo.stop(feed, Source.SHEET) }
        whenAt(at(11, 11)) { repo.recordBottle(BottleContent.BREAST_MILK, 60.0, Source.SHEET) }
        repo.recordDiaper(Diaper.MIXED, Source.SHEET)
        repo.recordDiaper(Diaper.WET, Source.SHEET)

        val poop = view(focus = ChartFocus.Subtype(Kind.DIAPER, Diaper.POOP.code))
        assertEquals(1.0, Metric.DIAPERS.total(poop.points.last()), 0.0)
        assertTrue(poop.patterns.last().bars.isEmpty())

        val left = view(focus = ChartFocus.OnSide(Side.LEFT))
        assertEquals(listOf(10.0, 0.0), Metric.BREAST_MS.values(left.points.last()).map { it / 60_000 })

        val all = view()
        assertEquals(1, all.gaps.last().count)
        assertEquals(3 * 3_600_000L, all.gaps.last().longestMs)
    }

    @Test
    fun `chart settings are read back with defaults`() = runBlocking {
        val s = repo.settings()
        assertEquals(ChartRange.D14, s.patternsRange)
        assertEquals(ChartRange.D14, s.statsRange)
        assertEquals(StatsTopic.FEEDS, s.statsTopic)
        assertEquals(ChartFocus.All, s.patternsFocus)
        repo.setSetting(Keys.PATTERNS_RANGE, ChartRange.ALL.code)
        repo.setSetting(Keys.STATS_RANGE, ChartRange.ALL.code) // not offered on Stats
        repo.setSetting(Keys.PATTERNS_FOCUS, ChartFocus.OnSide(Side.LEFT).encode())
        repo.setSetting(Keys.STATS_TOPIC, StatsTopic.SLEEP.code)
        val t = repo.settings()
        assertEquals(ChartRange.ALL, t.patternsRange)
        assertEquals(ChartRange.D14, t.statsRange)
        assertEquals(ChartFocus.OnSide(Side.LEFT), t.patternsFocus)
        assertEquals(StatsTopic.SLEEP, t.statsTopic)
    }
}
