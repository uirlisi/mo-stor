package io.github.uirlisi.mostor.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.core.MedPlan
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.data.DataCopy
import io.github.uirlisi.mostor.data.HouseholdSync
import io.github.uirlisi.mostor.data.MoStorDb
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.data.Source
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pairing and sync sessions between two phones over an in-memory link (SPEC §9.3, §9.4). */
@RunWith(RobolectricTestRunner::class)
class SyncProtocolTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Dublin")
    private var now = LocalDateTime.of(2026, 11, 14, 9, 0).atZone(zone).toInstant().toEpochMilli()

    /** Stores the key as it is: the Keystore isn't available on the JVM. */
    private class PlainVault : KeyVault {
        override fun wrap(key: ByteArray) = b64(key)
        override fun unwrap(wrapped: String) = runCatching { unb64(wrapped) }.getOrNull()
    }

    private lateinit var dbA: MoStorDb
    private lateinit var dbB: MoStorDb
    private lateinit var dbC: MoStorDb
    private lateinit var a: Repository
    private lateinit var b: Repository
    private lateinit var c: Repository

    /** Each phone's name, as it tells the others. */
    private val names = HashMap<Repository, String>()

    @Before
    fun setUp() {
        dbA = MoStorDb.build(context, "sync-a.db")
        dbB = MoStorDb.build(context, "sync-b.db")
        dbC = MoStorDb.build(context, "sync-c.db")
        a = Repository(dbA, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
        b = Repository(dbB, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
        c = Repository(dbC, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
        names[a] = "Phone A"
        names[b] = "Phone B"
        names[c] = "Phone C"
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        dbC.close()
        listOf("sync-a.db", "sync-b.db", "sync-c.db").forEach { context.deleteDatabase(it) }
    }

    /** A blocking in-memory pipe: what one side writes, the other reads; closed, it reads as the end. */
    private class Pipe {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false
        fun close() {
            closed = true
            queue.put(-1)
        }
        val input = object : InputStream() {
            override fun read(): Int {
                if (closed && queue.isEmpty()) return -1
                val b = queue.poll(10, TimeUnit.SECONDS) ?: throw java.io.IOException("timed out")
                if (b == -1) queue.put(-1)
                return b
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = queue.put(b and 0xFF)
        }
    }

    /** Two linked ends; closing either ends both directions, as a socket does. */
    private fun links(): Pair<Link, Link> {
        val ab = Pipe()
        val ba = Pipe()
        val close = { ab.close(); ba.close() }
        return Link(ba.input, ab.output, close) to Link(ab.input, ba.output, close)
    }

    private suspend fun me(r: Repository) = Me(r.sync.deviceId(), names.getValue(r))

    /** The household name the joiner got in the last [pair]. */
    private var nameJoined: String? = null

    /** Pairs [adder] with [joiner] as SyncManager does; returns both codes. [adderCalls]: the adder's name for the household. */
    private fun pair(
        adder: Repository,
        joiner: Repository,
        adderSays: Boolean = true,
        joinerSays: Boolean = true,
        adderCalls: String? = null,
    ): Pair<String, String> = runBlocking {
        val (linkA, linkB) = links()
        var codeA = ""
        var codeB = ""
        val meA = me(adder)
        val meB = me(joiner)
        val ra = async(Dispatchers.IO) {
            Pairing.adder(linkA, meA, offer = { who ->
                val h = adder.sync.ensureHousehold()
                adder.sync.ensureRoster(meA.name)
                Pairing.Offer(
                    h.id, h.key, adder.backupRows().let { r -> r.copy(events = emptyList(), segments = emptyList()) },
                    adder.sync.members().filter { it.deviceId != who.peerId },
                    adderCalls,
                )
            }) { codeA = it; adderSays }
        }
        val rb = async(Dispatchers.IO) { Pairing.joiner(linkB, meB) { codeB = it; joinerSays } }
        val joined = ra.await()
        val household = rb.await()
        nameJoined = household.householdName
        adder.sync.addMember(joined.peerId, joined.peerName)
        joiner.sync.joinHousehold(household.id, household.key, household.peerId, household.peerName, household.rows, household.members, meB.name)
        codeA to codeB
    }

    /** One session, [client] connecting to [server]; each side's outcome. */
    private fun trySession(client: Repository, server: Repository): Pair<Result<SyncSession.Result>, Result<SyncSession.Result>> = runBlocking {
        val (linkC, linkS) = links()
        val meC = me(client)
        val meS = me(server)
        val keyC = client.sync.household()!!.key
        val keyS = server.sync.household()!!.key
        val rs = async(Dispatchers.IO) { runCatching { SyncSession.server(linkS, keyS, meS, server.sync) }.also { linkS.close() } }
        val rc = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC, keyC, meC, client.sync) }.also { linkC.close() } }
        rc.await() to rs.await()
    }

    /** One session that must succeed. */
    private fun session(client: Repository, server: Repository): Pair<SyncSession.Result, SyncSession.Result> = runBlocking {
        val (rc, rs) = trySession(client, server)
        val results = rc.getOrThrow() to rs.getOrThrow()
        // As SyncManager does after a session: later sessions with this phone use vectors.
        client.sync.peerResult(results.first.peerId, ok = true)
        server.sync.peerResult(results.second.peerId, ok = true)
        results
    }

    @Test
    fun `pairing shows the same code, then the joiner matches its child and both converge`() = runBlocking {
        a.ensureInitialized()
        val aoife = dbA.children().live().single().id
        a.editChild(aoife, ChildFields(name = "Aoife"))
        a.recordDiaper(Diaper.WET, Source.SHEET)
        a.tapSide(Side.LEFT, Source.SHEET)
        now += 60_000
        b.recordDiaper(Diaper.POOP, Source.SHEET) // B's own child has a record: matching asks

        val (codeA, codeB) = pair(a, b)
        assertEquals(6, codeA.length)
        assertEquals(codeA, codeB)
        assertEquals(a.sync.household()!!.id, b.sync.household()!!.id)
        assertTrue(b.sync.household()!!.key.contentEquals(a.sync.household()!!.key))
        assertEquals(listOf("Phone B"), a.sync.others().map { it.name })
        assertEquals(listOf("Phone A"), b.sync.others().map { it.name })

        // No session until B has matched children: B refuses A as not known yet, and A stays put.
        val (early, earlyServer) = trySession(b, a)
        assertTrue(early.exceptionOrNull() is RefusedPeer)
        assertEquals(false, (earlyServer.exceptionOrNull() as RefusedByPeer).removed)
        val bChild = dbB.children().live().single().id
        b.sync.matchChildren(mapOf(bChild to aoife))
        assertNull(b.sync.pendingMatch())

        val (rc, rs) = session(b, a)
        assertTrue(rc.received > 0 && rs.received > 0)
        assertEquals(a.backupRows().events.toSet(), b.backupRows().events.toSet())
        assertEquals(a.backupRows().children.toSet(), b.backupRows().children.toSet())
        assertEquals(listOf("Aoife"), dbA.children().live().map { it.name })
        assertEquals(3, dbA.events().all().count { it.deletedAt == null && it.childId == aoife })

        // Another session changes nothing.
        val (again, _) = session(a, b)
        assertEquals(0, again.received)
        // A change on either side reaches the other.
        now += 60_000
        b.recordDiaper(Diaper.MIXED, Source.SHEET)
        session(a, b)
        assertTrue(dbA.events().all().any { it.subtype == "mixed" })
    }

    @Test
    fun `a fresh joining phone's blank child gives way without a question`() = runBlocking {
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        // Right after matching, before any session, B already records for A's child.
        assertEquals(dbA.children().live().single().id, dbB.children().live().single().id)
        b.recordDiaper(Diaper.DRY, Source.SHEET)
        session(b, a)
        assertEquals(1, dbB.children().live().size)
        assertEquals(dbA.children().live().single().id, dbB.children().live().single().id)
        assertEquals(1, dbA.children().live().size)
    }

    @Test
    fun `the first sync brings every record, also those older than the rows sent while pairing`() = runBlocking {
        // Found on the phones: records first, then a later edit of the child; the child came along
        // while pairing, and its newer stamp hid the older records from a vector-based first sync.
        a.ensureInitialized()
        repeat(5) {
            a.recordDiaper(Diaper.WET, Source.SHEET)
            now += 60_000
        }
        a.editChild(dbA.children().live().single().id, ChildFields(name = "Aoife"))
        now += 60_000
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(a, b)
        assertEquals(a.backupRows().events.toSet(), b.backupRows().events.toSet())
        assertEquals(5, dbB.events().all().count { it.deletedAt == null })
        // Later sessions use vectors and send only what's new.
        now += 60_000
        a.recordDiaper(Diaper.POOP, Source.SHEET)
        val (_, server) = session(b, a)
        // The new record, plus A's two untouched seeded meds, which always go along (see Sync).
        assertEquals(1 + 2, server.sent)
    }

    @Test
    fun `a change made here asks for a sync, rows received from another phone don't`() = runBlocking {
        pair(a, b)
        var localA = 0
        var localB = 0
        a.onLocalChange = { localA++ }
        b.onLocalChange = { localB++ }
        b.sync.matchChildren(emptyMap())
        assertEquals(0, localB) // matching moves rows the first sync carries anyway
        a.recordDiaper(Diaper.WET, Source.SHEET)
        assertEquals(1, localA)
        a.setSetting("day_start_hour", "6") // settings aren't synced
        assertEquals(1, localA)
        session(b, a)
        assertEquals(0, localB) // what B received isn't a change of its own: no sync back
        assertEquals(1, localA)
    }

    @Test
    fun `both phones start a sleep for one child, sync, and the owner keeps the earlier one`() = runBlocking {
        a.ensureInitialized()
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        a.startSleep(Source.SHEET)
        now += 2 * 60_000
        b.startSleep(Source.SHEET)
        session(a, b)
        val ka = a.homeState().conflicts.single()
        assertEquals(io.github.uirlisi.mostor.core.Kind.SLEEP, ka.kind)
        assertEquals(1, b.homeState().conflicts.size)

        // Keep both on B: B stops asking; A still asks until someone decides.
        b.resolveConflict(b.homeState().conflicts.single().key, io.github.uirlisi.mostor.core.Keep.BOTH, Source.SHEET)
        assertTrue(b.homeState().conflicts.isEmpty())
        assertEquals(2, b.homeState().running.size)

        // Keep earlier on A: the later sleep is canceled, and the next sync brings that to B.
        val done = a.resolveConflict(ka.key, io.github.uirlisi.mostor.core.Keep.EARLIER, Source.SHEET)!!
        assertEquals(ka.timers.first().event.id, a.homeState().running.single().timed.event.id)
        session(a, b)
        assertEquals(ka.timers.first().event.id, b.homeState().running.single().timed.event.id)
        // And Undo brings the canceled one back.
        a.undo(done.undoId)
        assertEquals(2, a.homeState().running.size)
    }

    @Test
    fun `a code declined on either phone pairs nothing`() {
        runBlocking { a.ensureInitialized(); b.ensureInitialized() }
        try {
            pair(a, b, joinerSays = false)
            fail("paired")
        } catch (e: Pairing.Declined) {
            // expected
        }
        runBlocking {
            assertTrue(a.sync.others().isEmpty())
            assertNull(b.sync.household())
        }
    }

    @Test
    fun `a phone without the household key, and a forgotten phone, are refused`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        // A stranger with a different key fails at the first encrypted message.
        val (linkC, linkS) = links()
        val stranger = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC, SyncCrypto.randomBytes(32), Me("x", "X"), b.sync) } }
        val server = async(Dispatchers.IO) { runCatching { SyncSession.server(linkS, a.sync.household()!!.key, me(a), a.sync) } }
        assertTrue(server.await().exceptionOrNull() is ProtocolException)
        stranger.await()

        // After A forgets B, B is refused even with the key, and told that it was removed.
        now += 60_000
        a.sync.removeMember(b.sync.deviceId())
        val (rb, ra) = trySession(b, a)
        val told = rb.exceptionOrNull() as RefusedByPeer
        assertTrue(told.removed)
        assertEquals("Phone A", told.by)
        assertTrue((ra.exceptionOrNull() as RefusedPeer).removed)
    }

    @Test
    fun `the joining phone gets the inviting phone's name for the household, if it has one`() {
        pair(a, b, adderCalls = "Murphy")
        assertEquals("Murphy", nameJoined)
        pair(a, c)
        assertNull(nameJoined)
    }

    /**
     * On the phones: the S22, already in the S23's household, joined it again with "Keep apart". Its
     * new database then listed its old one as a phone it could never reach.
     */
    @Test
    fun `a phone already in the household refuses to join it again, and the adder doesn't add it`() = runBlocking {
        a.ensureInitialized()
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val household = a.sync.household()!!
        val members = a.sync.members()

        // C stands for B's new database: a different device id on the phone that already holds B.
        val (linkA, linkC) = links()
        val meA = me(a)
        val meC = me(c)
        val ra = async(Dispatchers.IO) {
            runCatching {
                Pairing.adder(linkA, meA, offer = { who ->
                    Pairing.Offer(household.id, household.key, io.github.uirlisi.mostor.core.Merge.Rows(), members.filter { it.deviceId != who.peerId })
                }) { true }
            }.also { linkA.close() }
        }
        val rc = async(Dispatchers.IO) {
            runCatching { Pairing.joiner(linkC, meC, alreadyIn = { it == b.sync.household()?.id }) { true } }.also { linkC.close() }
        }
        assertTrue((ra.await().exceptionOrNull() as RefusedByPeer).already)
        assertEquals(household.id, (rc.await().exceptionOrNull() as AlreadyIn).householdId)
        assertEquals(members, a.sync.members()) // as SyncManager does: no addMember after a failed pairing
    }

    /** On the phones: B removed A, and A only heard of it minutes later, when A next asked for a sync. */
    @Test
    fun `the round after a removal waits for the removed phone, which learns it when it connects`() = runBlocking {
        a.ensureInitialized()
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        assertEquals(setOf(a.sync.deviceId()), b.sync.roundPeers())

        now += 60_000
        b.sync.removeMember(a.sync.deviceId())
        assertTrue(b.sync.others().isEmpty())
        assertEquals(setOf(a.sync.deviceId()), b.sync.roundPeers()) // B's round still advertises, for A

        // A, woken by B's round, connects to B: B refuses it as removed, and A knows.
        val (ra, rb) = trySession(a, b)
        assertTrue((ra.exceptionOrNull() as RefusedByPeer).removed)
        assertEquals(a.sync.deviceId(), (rb.exceptionOrNull() as RefusedPeer).peerId) // answers B's round
        assertTrue(b.sync.roundPeers().isEmpty()) // told: no more rounds for A
    }

    @Test
    fun `a phone that forgets the other tells it, and the forgotten phone keeps its records as a household of its own`() = runBlocking {
        // On the phones: B forgot A, then "Sync now" on A kept trying to reach B for 30 s.
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)

        now += 60_000
        b.sync.removeMember(a.sync.deviceId())
        assertTrue(b.sync.others().isEmpty())
        assertEquals(setOf(a.sync.deviceId()), b.sync.farewells()) // B keeps listening for A

        // A asks for a sync; B, woken, connects, learns of nothing new, and refuses A as removed.
        val (rb, ra) = trySession(b, a)
        assertTrue((rb.exceptionOrNull() as RefusedPeer).removed)
        val refused = ra.exceptionOrNull() as RefusedByPeer
        assertTrue(refused.removed)
        assertEquals("Phone B", refused.by)
        assertTrue(b.sync.farewells().isEmpty()) // told: B can stop listening

        // As SyncManager does: A leaves the household, keeps every record, and is a household of its own.
        val oldHousehold = a.sync.household()!!.id
        val records = dbA.events().all().size
        a.sync.leaveHousehold(refused.by!!)
        assertNull(a.sync.household())
        assertTrue(a.sync.others().isEmpty())
        assertEquals(records, dbA.events().all().size)
        assertEquals("Phone B", a.sync.syncPhonesFlow().first().removedBy)
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), a.sync.joinWays())
        // Inviting again makes a new household, which the old one's phones can't reach.
        assertNotEquals(oldHousehold, a.sync.ensureHousehold().id)
        assertTrue(trySession(b, a).let { (rb, ra) -> rb.isFailure && ra.isFailure })

        // B can invite it back, and A merges straight in: the same child, no question, no copy.
        a.sync.dismissRemovedNotice()
        pair(b, a)
        a.sync.matchChildren(emptyMap())
        session(a, b)
        assertEquals(listOf("Phone B"), a.sync.others().map { it.name })
        assertEquals(listOf("Phone A"), b.sync.others().map { it.name })
        assertNull(a.sync.syncPhonesFlow().first().removedBy)
        assertEquals(1, dbA.children().live().size)
        assertEquals(a.backupRows().events.toSet(), b.backupRows().events.toSet())
    }

    @Test
    fun `two phones that removed each other before syncing both leave, each with its records`() = runBlocking {
        a.ensureInitialized()
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        // Out of range of each other, each removes the other.
        now += 60_000
        a.sync.removeMember(b.sync.deviceId())
        now += 60_000
        b.sync.removeMember(a.sync.deviceId())

        val (ra, rb) = trySession(a, b)
        val refusedA = ra.exceptionOrNull() as RefusedByPeer
        val refusedB = rb.exceptionOrNull() as RefusedByPeer
        assertTrue(refusedA.removed && refusedB.removed)
        assertEquals("Phone B", refusedA.by)
        assertEquals("Phone A", refusedB.by)

        // As SyncManager does on both: each is a household of its own, nobody waits for anyone.
        a.sync.leaveHousehold(refusedA.by!!)
        b.sync.leaveHousehold(refusedB.by!!)
        for (p in listOf(a, b)) {
            assertNull(p.sync.household())
            assertTrue(p.sync.roundPeers().isEmpty())
        }
    }

    @Test
    fun `a phone added by another phone is seen by every phone and syncs with each directly`() = runBlocking {
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        // B adds C; A hasn't heard of C yet.
        pair(b, c)
        c.sync.matchChildren(emptyMap())
        assertEquals(setOf("Phone A", "Phone B"), c.sync.others().map { it.name }.toSet())
        assertEquals(listOf("Phone B"), a.sync.others().map { it.name })

        // C reaches A first: A learns of C from C's own list and lets it in.
        session(c, a)
        val cOnA = a.sync.others().single { it.name == "Phone C" }
        assertEquals(b.sync.deviceId(), cOnA.addedBy)
        assertEquals(a.backupRows().events.toSet(), c.backupRows().events.toSet())

        // A forgets C: after A and B sync, B refuses C too, and C leaves.
        now += 60_000
        a.sync.removeMember(c.sync.deviceId())
        session(b, a)
        assertEquals(listOf("Phone A"), b.sync.others().map { it.name })
        val (rc, _) = trySession(c, b)
        val refused = rc.exceptionOrNull() as RefusedByPeer
        assertTrue(refused.removed)
        assertEquals("Phone A", refused.by) // the phone that removed it, not the one that told it
    }

    /** A, B and C in one household (A added both), all synced. */
    private suspend fun household3() {
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        pair(a, c)
        c.sync.matchChildren(emptyMap())
        session(c, a)
        session(b, a)
    }

    @Test
    fun `a phone that leaves brings its last records to one phone, which passes its leaving on`() = runBlocking {
        household3()
        now += 60_000
        b.recordDiaper(Diaper.POOP, Source.SHEET)
        val last = dbB.events().all().single { it.subtype == "poop" }.id

        assertEquals(false, b.sync.leave())
        assertTrue(b.sync.isLeaving())
        b.sync.ensureRoster("Phone B2") // a later name change doesn't bring it back
        assertTrue(b.sync.isLeaving())
        assertTrue(b.sync.syncPhonesFlow().first().leaving)

        // Its last session: A lets it in, and gets both the record and the leaving.
        session(b, a)
        assertTrue(dbA.events().all().any { it.id == last })
        assertEquals(listOf("Phone C"), a.sync.others().map { it.name })
        // As SyncManager does after that session: B goes, keeping its records.
        b.sync.leaveHousehold(removedBy = null)
        assertNull(b.sync.household())
        assertTrue(dbB.events().all().any { it.id == last })
        assertTrue(b.sync.syncPhonesFlow().first().left)
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), b.sync.joinWays())

        // C hears of it from A.
        session(c, a)
        assertEquals(listOf("Phone A"), c.sync.others().map { it.name })
        assertTrue(dbC.events().all().any { it.id == last })
    }

    @Test
    fun `the only active phone of a household can't leave it, and may merge into another`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        assertEquals(listOf(JoinWay.APART), a.sync.joinWays()) // B is still in it
        now += 60_000
        a.sync.removeMember(b.sync.deviceId())
        val household = a.sync.household()!!.id
        assertEquals(true, a.sync.leave()) // nothing to leave: it already is a household of its own
        assertEquals(household, a.sync.household()!!.id)
        assertTrue(a.sync.syncPhonesFlow().first().let { !it.left && !it.leaving })
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), a.sync.joinWays())
    }

    @Test
    fun `a newer removal by another phone beats leaving`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        b.sync.leave()
        now += 60_000
        a.sync.removeMember(b.sync.deviceId())
        val (rb, _) = trySession(b, a)
        val refused = rb.exceptionOrNull() as RefusedByPeer
        assertTrue(refused.removed)
        assertEquals("Phone A", refused.by)
    }

    @Test
    fun `a database merges into another household only when no other phone is active in its own`() = runBlocking {
        // On its own, records of its own, even with a household it made but never shared: it may merge.
        a.recordDiaper(Diaper.WET, Source.SHEET)
        a.sync.ensureHousehold()
        a.sync.ensureRoster("Phone A")
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), a.sync.joinWays())
        assertEquals(false, a.sync.holdsOnlyBlank())
        assertEquals(true, b.sync.holdsOnlyBlank())

        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        // Both are in a household with another phone: each may only join another one apart.
        assertEquals(listOf(JoinWay.APART), a.sync.joinWays())
        assertEquals(listOf(JoinWay.APART), b.sync.joinWays())
        try {
            pair(c, b) // merging B would carry A's household's children into C's
            fail("merged a database another phone still syncs with")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(a.sync.household()!!.id, b.sync.household()!!.id) // and B is still in A's household
        assertEquals(listOf("Phone A"), b.sync.others().map { it.name })
    }

    @Test
    fun `merging deletes the joining database's marked-deleted rows for good, so they never reach the household`() = runBlocking {
        // B logged alone: a child it later deleted (with a note), and a record it deleted.
        b.ensureInitialized()
        val liam = b.addChild(ChildFields(name = "Liam"))!!.info.eventId
        b.setActiveChild(liam)
        b.recordNote("private", Source.SHEET)
        b.tapSide(Side.LEFT, Source.SHEET)
        now += 60_000
        b.setActiveChild(dbB.children().live().first { it.id != liam }.id)
        b.deleteChild(liam)
        b.recordDiaper(Diaper.WET, Source.SHEET)
        val wet = dbB.events().all().single { it.subtype == "wet" }.id
        b.delete(wet)
        b.recordDiaper(Diaper.POOP, Source.SHEET) // a live record, which stays
        val deleted = setOf(liam, wet) + dbB.events().all().filter { it.childId == liam }.map { it.id }
        assertTrue(b.backupRows().let { r -> r.children.map { it.id } + r.events.map { it.id } }.containsAll(deleted))

        a.ensureInitialized()
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val rows = b.backupRows()
        val ids = rows.children.map { it.id } + rows.events.map { it.id } + rows.segments.map { it.eventId } + rows.presets.map { it.childId }
        assertTrue(ids.none { it in deleted })
        assertTrue(rows.events.any { it.subtype == "poop" })
        assertNull(dbB.undo().latest())
        session(b, a)
        val onA = a.backupRows().let { r -> r.children.map { it.id } + r.events.map { it.id } }
        assertTrue(onA.none { it in deleted })
        assertTrue(a.backupRows().events.none { it.note == "private" })
    }

    @Test
    fun `the phones that stay show once that a phone left`() = runBlocking {
        household3()
        now += 60_000
        b.sync.leave()
        session(b, a)
        b.sync.leaveHousehold(removedBy = null)
        val onA = a.sync.syncPhonesFlow().first().departed
        assertEquals(listOf("Phone B"), onA.map { it.name })
        assertEquals(now, onA.single().removedAt)
        // C hears of it through A, and shows it too.
        session(c, a)
        assertEquals(listOf("Phone B"), c.sync.syncPhonesFlow().first().departed.map { it.name })
        // Read: gone on A, still there on C. A phone removed by another isn't "left".
        a.sync.dismissDeparted(onA.single())
        assertTrue(a.sync.syncPhonesFlow().first().departed.isEmpty())
        assertEquals(1, c.sync.syncPhonesFlow().first().departed.size)
        now += 60_000
        a.sync.removeMember(c.sync.deviceId())
        assertTrue(a.sync.syncPhonesFlow().first().departed.isEmpty())
    }

    /** A parting session: [leaver] (which left with "Leave now") meets [server], a phone of the household it left. */
    private fun part(leaver: Repository, server: Repository): Pair<Result<SyncSession.Result>, Result<SyncSession.Result>> = runBlocking {
        val p = leaver.sync.parting()!!
        val (linkC, linkS) = links()
        val meC = me(leaver)
        val meS = me(server)
        val keyS = server.sync.household()!!.key
        val rs = async(Dispatchers.IO) { runCatching { SyncSession.server(linkS, keyS, meS, server.sync) }.also { linkS.close() } }
        val rc = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC, p.key, meC, PartingStore(leaver.sync, p)) }.also { linkC.close() } }
        rc.await() to rs.await()
    }

    @Test
    fun `a phone that leaves with no other phone near tells the household later, with its records from before`() = runBlocking {
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)

        // B records something, then leaves with no phone near ("Leave household" got no answer).
        now += 60_000
        b.recordDiaper(Diaper.POOP, Source.SHEET)
        assertEquals(false, b.sync.leave())
        b.sync.leaveNow()
        assertNull(b.sync.household())
        assertTrue(b.sync.syncPhonesFlow().first().let { it.left && it.leftLater })
        assertEquals(listOf(JoinWay.MERGE, JoinWay.APART), b.sync.joinWays())
        val parting = b.sync.parting()!!
        assertEquals(b.sync.deviceId(), parting.row.removedBy)
        // After leaving, B records more: that is its own household's now, and never goes to A.
        now += 60_000
        b.recordDiaper(Diaper.DRY, Source.SHEET)
        // A, meanwhile, records something too: it must not come into B.
        a.recordDiaper(Diaper.MIXED, Source.SHEET)
        assertEquals(listOf("Phone B"), a.sync.others().map { it.name }) // A still tries to reach B
        val bBefore = b.backupRows()

        val (rb, ra) = part(b, a)
        rb.getOrThrow()
        ra.getOrThrow()
        b.sync.partingDone()
        assertNull(b.sync.parting())
        // A knows B left, has B's record from before, and nothing from after.
        assertTrue(a.sync.others().isEmpty())
        assertEquals(listOf("Phone B"), a.sync.syncPhonesFlow().first().departed.map { it.name })
        assertTrue(dbA.events().all().any { it.subtype == "poop" })
        assertTrue(dbA.events().all().none { it.subtype == "dry" })
        // B took nothing in.
        assertEquals(bBefore, b.backupRows())
        assertTrue(dbB.events().all().none { it.subtype == "mixed" })
    }

    @Test
    fun `a parting key lasts 30 days, and a newer removal ends it too`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        b.sync.leaveNow() // straight from the household: its own row is marked as it goes
        assertEquals(b.sync.deviceId(), b.sync.parting()!!.row.removedBy)

        // Meanwhile A removed B: the parting session is refused as removed, so B can forget the key.
        now += 60_000
        a.sync.removeMember(b.sync.deviceId())
        val (rb, _) = part(b, a)
        assertTrue((rb.exceptionOrNull() as RefusedByPeer).removed)

        // And after 30 days the key is no longer used.
        now += HouseholdSync.FAREWELL_MS
        assertNull(b.sync.parting())
    }

    @Test
    fun `a phone in a household joins another apart, from a new database, and the first stays as it was`() = runBlocking {
        // A and B share a household; C, another family's phone, invites B (a nanny's phone).
        a.ensureInitialized()
        a.recordDiaper(Diaper.WET, Source.SHEET)
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        c.ensureInitialized()
        c.editChild(dbC.children().live().single().id, ChildFields(name = "Ciara"))
        c.recordDiaper(Diaper.POOP, Source.SHEET)
        val first = b.backupRows()

        // As the container does for "Keep this phone's records apart": a new database with its own id.
        val dbB2 = MoStorDb.build(context, "sync-b2.db")
        try {
            val b2 = Repository(dbB2, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
            names[b2] = "Phone B"
            b2.sync.adoptChoices(b.sync.choicesForNewHousehold())
            assertNotEquals(b.sync.deviceId(), b2.sync.deviceId())
            pair(c, b2)
            b2.sync.matchChildren(emptyMap())
            session(b2, c)
            assertEquals(listOf("Ciara"), dbB2.children().live().map { it.name })
            assertEquals(c.backupRows().events.toSet(), b2.backupRows().events.toSet())
            // B's first household is untouched and still syncs with A; C never sees A's records.
            assertEquals(first, b.backupRows())
            assertEquals(listOf("Phone A"), b.sync.others().map { it.name })
            session(b, a)
            assertTrue(c.backupRows().events.none { e -> first.events.any { it.id == e.id } })
        } finally {
            dbB2.close()
            context.deleteDatabase("sync-b2.db")
        }
    }

    @Test
    fun `a phone claiming this phone's own device id is refused, in a session and when pairing`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        // A has its own row on its list: without the check, a phone saying it is A would be let in.
        val key = a.sync.household()!!.key
        val (linkC, linkS) = links()
        val impostor = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC, key, Me(a.sync.deviceId(), "Phone A"), b.sync) }.also { linkC.close() } }
        val server = async(Dispatchers.IO) { runCatching { SyncSession.server(linkS, key, me(a), a.sync) }.also { linkS.close() } }
        assertTrue(server.await().exceptionOrNull() is RefusedPeer)
        assertEquals(false, (impostor.await().exceptionOrNull() as RefusedByPeer).removed)

        // Nor one presenting the id of another of this phone's households.
        val (linkC2, linkS2) = links()
        val other = "0193-other-household"
        val impostor2 = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC2, key, Me(other, "Phone A"), b.sync) }.also { linkC2.close() } }
        val server2 = async(Dispatchers.IO) {
            runCatching { SyncSession.server(linkS2, key, Me(a.sync.deviceId(), "Phone A", otherIds = setOf(other)), a.sync) }.also { linkS2.close() }
        }
        assertTrue(server2.await().exceptionOrNull() is RefusedPeer)
        impostor2.await()

        // Pairing: a joiner with the inviting phone's id is turned away before it gets the key.
        val (pa, pb) = links()
        val adder = async(Dispatchers.IO) {
            runCatching { Pairing.adder(pa, me(a), offer = { error("offered the household") }) { true } }.also { pa.close() }
        }
        val joiner = async(Dispatchers.IO) { runCatching { Pairing.joiner(pb, Me(a.sync.deviceId(), "Phone C")) { true } }.also { pb.close() } }
        assertTrue(adder.await().exceptionOrNull() is ProtocolException)
        assertTrue(joiner.await().isFailure)
    }

    @Test
    fun `a new household takes the owner's choices, not records, sync state or bookkeeping`() = runBlocking {
        a.ensureInitialized()
        a.setSetting("day_start_hour", "6")
        a.setSetting("volume_unit", "fl_oz")
        a.setSetting("theme", "dark")
        a.setActiveChild(dbA.children().live().single().id)
        a.recordBottle(io.github.uirlisi.mostor.core.BottleContent.FORMULA, 90.0, Source.SHEET)
        a.sync.ensureHousehold()
        val choices = a.sync.choicesForNewHousehold()
        assertEquals("6", choices["day_start_hour"])
        assertEquals("fl_oz", choices["volume_unit"])
        assertEquals("dark", choices["theme"])
        for (key in listOf("household_id", "household_key", "active_child", "last_bottle_ml", "install_id")) {
            assertTrue(key, key !in choices)
        }
        c.ensureInitialized()
        c.sync.adoptChoices(choices + ("household_id" to "sneaked-in"))
        assertEquals(6, c.settings().dayStartHour)
        assertNull(c.sync.household())
    }

    @Test
    fun `after the upgrade, both phones make the same list from their pairing, and a removal still wins`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        // As after the upgrade from schema version 3: no list yet, only the paired phones.
        dbA.members().deleteAll()
        dbB.members().deleteAll()
        a.sync.ensureRoster("Phone A")
        b.sync.ensureRoster("Phone B")
        assertTrue(a.sync.members().all { it.hlc == io.github.uirlisi.mostor.core.Roster.SEED_HLC })
        session(a, b)
        assertEquals(a.sync.members().toSet(), b.sync.members().toSet())
        assertEquals(2, a.sync.members().count { it.active })

        // A renamed phone: its own row follows, and the other phone learns the new name.
        names[b] = "Phone B2"
        b.sync.ensureRoster("Phone B2")
        session(a, b)
        assertEquals(listOf("Phone B2"), a.sync.others().map { it.name })

        // A removal made on either phone beats the upgrade rows on the other.
        now += 60_000
        b.sync.removeMember(a.sync.deviceId())
        dbA.members().deleteAll()
        a.sync.ensureRoster("Phone A")
        val (_, ra) = trySession(b, a)
        assertTrue((ra.exceptionOrNull() as RefusedByPeer).removed)
    }

    @Test
    fun `a stop made on one phone survives the other phone giving up on the timer later`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val id = a.startSleep(Source.SHEET)!!.info.eventId
        session(b, a)
        now += 30 * 60_000L
        a.stop(id, Source.SHEET)
        val stoppedAt = dbA.events().event(id)!!.endAt

        // B is out of range; long past the sleep's give-up time it marks the end unknown.
        now += 15 * 60 * 60_000L
        b.applyOverruns()
        assertEquals("end_unknown", dbB.events().event(id)!!.status)

        session(b, a)
        for (db in listOf(dbA, dbB)) {
            val e = db.events().event(id)!!
            assertEquals("ended", e.status)
            assertEquals(stoppedAt, e.endAt)
        }
        assertEquals(dbA.events().event(id), dbB.events().event(id))
    }

    @Test
    fun `a stop on one phone after a switch on the other ends the feed with no segment left open`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val id = a.tapSide(Side.LEFT, Source.SHEET)!!.info.eventId
        session(b, a)
        // B, out of range, switches; a minute later A, not knowing, stops.
        now += 10 * 60_000L
        b.switchSide(id, Source.SHEET)
        now += 60_000L
        a.stop(id, Source.SHEET)

        session(b, a)
        session(b, a)
        for (db in listOf(dbA, dbB)) {
            assertEquals("ended", db.events().event(id)!!.status)
            val segments = db.events().liveSegments(listOf(id))
            assertEquals(listOf("L", "R"), segments.map { it.side })
            assertTrue(segments.all { it.endAt != null })
            assertEquals(segments[1].startAt, segments[0].endAt)
            assertEquals(db.events().event(id)!!.endAt, segments[1].endAt)
        }
        assertEquals(a.backupRows().segments.toSet(), b.backupRows().segments.toSet())
    }

    @Test
    fun `a copy restored on a paired phone brings back a seeded med as it was, on both phones`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        session(b, a)
        val file = java.io.File(context.cacheDir, "seed-copy.db").also { it.delete() }
        DataCopy.write(dbA, file)

        // After the copy, Vit D gets an amount, and both phones have it.
        now += 60_000
        val vitD = dbA.presets().live().first { it.name == "Vit D" }
        a.editPreset(vitD.id, "Vit D", 400.0, "IU", MedPlan())
        session(b, a)
        assertEquals(400.0, dbB.presets().preset(vitD.id)!!.amount)

        // A restores the copy (Vit D without an amount) and syncs: B follows.
        now += 60_000
        val scratch = java.io.File(context.cacheDir, "seed-scratch.db")
        file.copyTo(scratch, overwrite = true)
        a.restoreFrom((DataCopy.read(context, scratch) as DataCopy.Read.Ok).rows)
        session(b, a)
        for (db in listOf(dbA, dbB)) assertEquals(null, db.presets().preset(vitD.id)!!.amount)
        assertEquals(a.backupRows().presets.toSet(), b.backupRows().presets.toSet())
        listOf(file, scratch).forEach { it.delete() }
    }

    @Test
    fun `a copy restored on a paired phone reaches the other phone, and both agree`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val wet = a.recordDiaper(Diaper.WET, Source.SHEET)!!.info.eventId
        session(b, a)
        val file = java.io.File(context.cacheDir, "paired-copy.db").also { it.delete() }
        DataCopy.write(dbA, file)

        // After the copy: A changes the diaper and B records another one; both sync.
        now += 60_000
        val opened = a.loadForEdit(wet)!!
        a.saveEdit(opened, EventEdit.draftOf(opened.event).copy(subtype = "poop"))
        assertEquals("poop", dbA.events().event(wet)!!.subtype)
        now += 60_000
        val bOwn = b.recordDiaper(Diaper.MIXED, Source.SHEET)!!.info.eventId
        session(b, a)

        // A restores the copy, then syncs: B ends up with the copy's records too.
        now += 60_000
        val scratch = java.io.File(context.cacheDir, "paired-scratch.db")
        file.copyTo(scratch, overwrite = true)
        a.restoreFrom((DataCopy.read(context, scratch) as DataCopy.Read.Ok).rows)
        session(b, a)

        for (db in listOf(dbA, dbB)) {
            assertEquals("wet", db.events().event(wet)!!.subtype)
            assertTrue(db.events().event(bOwn)!!.deletedAt != null)
        }
        assertEquals(a.backupRows().events.toSet(), b.backupRows().events.toSet())
        listOf(file, scratch).forEach { it.delete() }
    }

    @Test
    fun `a refusal in the clear from a device without the key is an error, and the phone stays in its household`() = runBlocking {
        pair(a, b)
        b.sync.matchChildren(emptyMap())
        val removal = "{\"t\":\"refuse\",\"why\":\"removed\",\"by\":\"Mum\"}".toByteArray()

        // A stranger answers a phone that connects to it (A is the client).
        val (c1, s1) = links()
        val client = async(Dispatchers.IO) { runCatching { SyncSession.client(c1, a.sync.household()!!.key, me(a), a.sync) } }
        s1.receive() // A's hello
        s1.send(removal)
        assertTrue(client.await().exceptionOrNull() is ProtocolException)

        // A stranger connects to a phone that advertises (A is the server).
        val (c2, s2) = links()
        val server = async(Dispatchers.IO) { runCatching { SyncSession.server(s2, a.sync.household()!!.key, me(a), a.sync) } }
        c2.send(removal)
        assertTrue(server.await().exceptionOrNull() is ProtocolException)

        assertEquals(b.sync.household()!!.id, a.sync.household()!!.id)
        assertEquals(listOf("Phone B"), a.sync.others().map { it.name })
        val (rb, ra) = session(b, a)
        assertTrue(rb.received >= 0 && ra.received >= 0)
    }

    @Test
    fun `a changed byte in an encrypted message ends the session`() {
        val key = SyncCrypto.randomBytes(32)
        val ab = Pipe()
        val sender = SecureLink(Link(Pipe().input, ab.output), key, key)
        sender.send(SecureLink.JSON, "{\"t\":\"x\"}".toByteArray())
        // Read the frame, flip a byte, and hand it to a receiver.
        val raw = Link(ab.input, Pipe().output).receive()
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        val relay = Pipe()
        Link(Pipe().input, relay.output).send(raw)
        val receiver = SecureLink(Link(relay.input, Pipe().output), key, key)
        try {
            receiver.receive()
            fail("tampered message accepted")
        } catch (e: ProtocolException) {
            // expected
        }
    }

    @Test
    fun `codes differ when someone in the middle swaps keys`() {
        val shared1 = SyncCrypto.randomBytes(32)
        val shared2 = SyncCrypto.randomBytes(32)
        val t = SyncCrypto.randomBytes(32)
        assertNotEquals(SyncCrypto.pairingCode(shared1, t), SyncCrypto.pairingCode(shared2, t))
        assertEquals(SyncCrypto.pairingCode(shared1, t), SyncCrypto.pairingCode(shared1, t))
    }
}
