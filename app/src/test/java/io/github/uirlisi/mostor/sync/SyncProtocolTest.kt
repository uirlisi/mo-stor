package io.github.uirlisi.mostor.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.uirlisi.mostor.core.ChildFields
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Side
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
    private lateinit var a: Repository
    private lateinit var b: Repository

    @Before
    fun setUp() {
        dbA = MoStorDb.build(context, "sync-a.db")
        dbB = MoStorDb.build(context, "sync-b.db")
        a = Repository(dbA, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
        b = Repository(dbB, {}, { now }, { zone }, { Locale.UK }, vault = PlainVault())
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        listOf("sync-a.db", "sync-b.db").forEach { context.deleteDatabase(it) }
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

    private suspend fun me(r: Repository, name: String) = Me(r.deviceId(), name)

    /** Pairs [adder] with [joiner]; returns both codes. */
    private fun pair(adder: Repository, joiner: Repository, adderSays: Boolean = true, joinerSays: Boolean = true): Pair<String, String> = runBlocking {
        val (linkA, linkB) = links()
        var codeA = ""
        var codeB = ""
        val meA = me(adder, "Phone A")
        val meB = me(joiner, "Phone B")
        val ra = async(Dispatchers.IO) {
            Pairing.adder(linkA, meA, offer = {
                val h = adder.ensureHousehold()
                Pairing.Offer(h.id, h.key, adder.backupRows().let { r -> r.copy(events = emptyList(), segments = emptyList()) })
            }) { codeA = it; adderSays }
        }
        val rb = async(Dispatchers.IO) { Pairing.joiner(linkB, meB) { codeB = it; joinerSays } }
        val joined = ra.await()
        val household = rb.await()
        adder.addPeer(joined.peerId, joined.peerName)
        joiner.joinHousehold(household.id, household.key, household.peerId, household.peerName, household.rows)
        codeA to codeB
    }

    /** One session, [client] connecting to [server]. */
    private fun session(client: Repository, server: Repository): Pair<SyncSession.Result, SyncSession.Result> = runBlocking {
        val (linkC, linkS) = links()
        val meC = me(client, "C")
        val meS = me(server, "S")
        val keyC = client.household()!!.key
        val keyS = server.household()!!.key
        val rs = async(Dispatchers.IO) { SyncSession.server(linkS, keyS, meS, server) }
        val rc = async(Dispatchers.IO) { SyncSession.client(linkC, keyC, meC, client) }
        val results = rc.await() to rs.await()
        // As SyncManager does after a session: later sessions with this phone use vectors.
        client.peerResult(results.first.peerId, ok = true)
        server.peerResult(results.second.peerId, ok = true)
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
        assertEquals(a.household()!!.id, b.household()!!.id)
        assertTrue(b.household()!!.key.contentEquals(a.household()!!.key))
        assertEquals(listOf("Phone B"), a.peers().map { it.name })
        assertEquals(listOf("Phone A"), b.peers().map { it.name })

        // No session until B has matched children.
        try {
            session(b, a)
            fail("a session ran before matching")
        } catch (e: Exception) {
            // refused by B itself (it doesn't trust A yet)
        }
        val bChild = dbB.children().live().single().id
        b.matchChildren(mapOf(bChild to aoife))
        assertNull(b.pendingMatch())

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
        b.matchChildren(emptyMap())
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
        b.matchChildren(emptyMap())
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
        b.matchChildren(emptyMap())
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
        b.matchChildren(emptyMap())
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
            assertTrue(a.peers().isEmpty())
            assertNull(b.household())
        }
    }

    @Test
    fun `a phone without the household key, and a forgotten phone, are refused`() = runBlocking {
        pair(a, b)
        b.matchChildren(emptyMap())
        // A stranger with a different key fails at the first encrypted message.
        val (linkC, linkS) = links()
        val stranger = async(Dispatchers.IO) { runCatching { SyncSession.client(linkC, SyncCrypto.randomBytes(32), Me("x", "X"), b) } }
        val server = async(Dispatchers.IO) { runCatching { SyncSession.server(linkS, a.household()!!.key, me(a, "A"), a) } }
        assertTrue(server.await().exceptionOrNull() is ProtocolException)
        stranger.await()

        // After A forgets B, B is refused even with the key.
        a.forgetPeer(b.deviceId())
        try {
            session(b, a)
            fail("a forgotten phone synced")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("refused") || e.message!!.contains("known"))
        }
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
