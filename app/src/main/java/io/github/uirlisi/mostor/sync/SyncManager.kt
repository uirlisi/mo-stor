package io.github.uirlisi.mostor.sync

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.data.Repository
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** What Settings › Sync shows while pairing or syncing. */
sealed interface SyncUi {
    data object Idle : SyncUi
    /** "Add a phone": advertising, waiting for the other phone to join. */
    data object WaitingForJoiner : SyncUi
    /** "Join a phone": looking for a phone that is adding. */
    data object Searching : SyncUi
    data object Connecting : SyncUi
    /** Both phones show [code]; [answered] once this phone's owner has said whether it matches. */
    data class Code(val code: String, val answered: Boolean = false) : SyncUi
    /** Which of this phone's children are the other phone's (SPEC §9.4 step 4). */
    data class Matching(val peerName: String, val theirs: List<Child>, val mine: List<Child>) : SyncUi
    data object Syncing : SyncUi
    data class Paired(val peerName: String) : SyncUi
    data class Synced(val peers: List<String>, val received: Int) : SyncUi
    data class Failed(val reason: Reason) : SyncUi

    enum class Reason { NO_PHONE, CODES_DIFFER, CONNECTION, NO_ANSWER, BLUETOOTH }
}

/**
 * Runs pairing and sync sessions (SPEC §9). One session at a time on this phone. The screens follow
 * [ui]; the background receiver calls [onWoken]. Nothing here logs records, names or keys.
 */
class SyncManager(
    private val context: Context,
    private val repository: () -> Repository,
    private val scope: CoroutineScope,
) {
    val transport = BleTransport(context)
    private val _ui = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val ui: StateFlow<SyncUi> = _ui.asStateFlow()

    private val sessionLock = Mutex()
    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var matched: CompletableDeferred<Map<String, String>>? = null

    /** Sessions already done (by advertisement token), so repeated scan results don't sync twice. */
    private val doneTokens = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?) = size > 32
    })

    private suspend fun me(): Me = Me(repository().deviceId(), deviceName())

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // ---- Screens' actions ----

    /** The owner's answer to "Does the other phone show the same code?" */
    fun answerCode(same: Boolean) {
        (_ui.value as? SyncUi.Code)?.let { _ui.value = it.copy(answered = true) }
        answer?.complete(same)
    }

    fun finishMatching(same: Map<String, String>) {
        matched?.complete(same)
    }

    fun cancel() {
        job?.cancel()
        answer?.complete(false)
        _ui.value = SyncUi.Idle
    }

    fun dismiss() {
        if (job?.isActive != true) _ui.value = SyncUi.Idle
    }

    /** "Add a phone" (SPEC §9.4): advertise for pairing until a phone joins, or 5 minutes. */
    fun startAdding() = launchFlow {
        _ui.value = SyncUi.WaitingForJoiner
        val repo = repository()
        val household = repo.ensureHousehold()
        val me = me()
        val done = CompletableDeferred<SyncUi>()
        val claimed = AtomicBoolean(false)
        val adv = transport.advertise(BleTransport.KIND_PAIR, tagFor = { ByteArray(4) }) { socket ->
            if (!claimed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                return@advertise
            }
            runBlocking {
                done.complete(
                    try {
                        val joined = withDeadline(socket, PAIRING_MS) {
                            Pairing.adder(link(socket), me, offer = {
                                Pairing.Offer(household.id, household.key, childrenAndMeds(repo.backupRows()))
                            }) { code -> askCode(code) }
                        }
                        repo.addPeer(joined.peerId, joined.peerName)
                        refreshBackground()
                        SyncUi.Paired(joined.peerName)
                    } catch (e: Pairing.Declined) {
                        SyncUi.Failed(SyncUi.Reason.CODES_DIFFER)
                    } catch (e: Exception) {
                        Log.w(TAG, "Pairing failed: ${e.javaClass.simpleName}", e)
                        SyncUi.Failed(SyncUi.Reason.CONNECTION)
                    } finally {
                        runCatching { socket.close() }
                    },
                )
            }
        }
        try {
            _ui.value = withTimeoutOrNull(ADDING_MS) { done.await() } ?: SyncUi.Failed(SyncUi.Reason.NO_PHONE)
        } finally {
            adv.stop()
        }
    }

    /** "Join a phone": find a phone that is adding, pair, match children, then a first sync. */
    fun startJoining() = launchFlow {
        _ui.value = SyncUi.Searching
        val repo = repository()
        val found = transport.find(BleTransport.KIND_PAIR, SEARCH_MS)
        if (found == null) {
            _ui.value = SyncUi.Failed(SyncUi.Reason.NO_PHONE)
            return@launchFlow
        }
        _ui.value = SyncUi.Connecting
        val me = me()
        val household = try {
            val socket = transport.connect(found)
            try {
                withDeadline(socket, PAIRING_MS) { Pairing.joiner(link(socket), me) { code -> askCode(code) } }
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: Pairing.Declined) {
            _ui.value = SyncUi.Failed(SyncUi.Reason.CODES_DIFFER)
            return@launchFlow
        } catch (e: Exception) {
            Log.w(TAG, "Joining failed: ${e.javaClass.simpleName}", e)
            _ui.value = SyncUi.Failed(SyncUi.Reason.CONNECTION)
            return@launchFlow
        }
        repo.joinHousehold(household.id, household.key, household.peerId, household.peerName, household.rows)

        // Child matching: only this phone's children with something in them need an answer.
        val theirs = household.rows.children.filter { it.deletedAt == null }
        val mine = repo.homeState().children.filter { c ->
            c.id !in theirs.map { it.id } &&
                !Merge.isBlank(c, repo.backupRows().events.count { it.childId == c.id }, repo.backupRows().presets.filter { it.childId == c.id })
        }
        val same = if (theirs.isEmpty() || mine.isEmpty()) {
            emptyMap()
        } else {
            matched = CompletableDeferred()
            _ui.value = SyncUi.Matching(household.peerName, theirs, mine)
            matched!!.await()
        }
        repo.matchChildren(same)
        refreshBackground()
        syncRound(listOf(household.peerName))
    }

    /** "Sync now": advertise for a sync until every peer has synced, or 30 s. */
    fun syncNow() = launchFlow {
        val names = repository().peers().map { it.name }
        if (names.isEmpty()) return@launchFlow
        syncRound(names)
    }

    /**
     * One round: advertise a sync for up to [ms] and serve every peer that connects, until all have
     * synced. A visible round ("Sync now", after pairing) shows its progress and result; a quiet one
     * (on a change, on opening, the periodic job) shows nothing. Returns how many peers synced.
     */
    private suspend fun syncRound(names: List<String>, quiet: Boolean = false, ms: Long = ROUND_MS): Int {
        if (!quiet) _ui.value = SyncUi.Syncing
        val repo = repository()
        val household = repo.household() ?: run {
            if (!quiet) _ui.value = SyncUi.Failed(SyncUi.Reason.CONNECTION)
            return 0
        }
        val pending = repo.peers().map { it.deviceId }.toMutableSet()
        if (pending.isEmpty()) return 0
        val synced = java.util.Collections.synchronizedList(ArrayList<String>())
        var received = 0
        val allDone = CompletableDeferred<Unit>()
        val me = me()
        val adv = transport.advertise(BleTransport.KIND_SYNC, tagFor = { BleTransport.syncTag(household.key, it) }) { socket ->
            runBlocking {
                val result = session(socket) { link -> SyncSession.server(link, household.key, me, repo) }
                if (result != null) {
                    synced += result.peerName
                    received += result.received
                    synchronized(pending) {
                        pending -= result.peerId
                        if (pending.isEmpty()) allDone.complete(Unit)
                    }
                }
            }
        }
        try {
            withTimeoutOrNull(ms) { allDone.await() }
        } finally {
            adv.stop()
        }
        if (!quiet) {
            _ui.value = if (synced.isEmpty()) SyncUi.Failed(SyncUi.Reason.NO_ANSWER) else SyncUi.Synced(synced.distinct(), received)
        }
        return synced.distinct().size
    }

    // ---- Automatic syncs (SPEC §9.6, checkpoint b) ----

    private val roundLock = Mutex()
    @Volatile private var again = false
    private var debounce: Job? = null
    @Volatile private var lastQuietRoundAt = 0L

    /**
     * After a change made on this phone: a quiet round 2 s later (several quick taps make one). Only
     * the wait is canceled by a newer change, never a round already running: that one finishes and
     * runs again for the newer change (a newer change cut a session short on the phones).
     */
    fun onLocalChange() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            scope.launch { quietRound() }
        }
    }

    /**
     * When the quick sheet or the app opens: registers the background scan again (Android drops it
     * when Bluetooth goes off), and a quiet round unless one ran in the last 30 s.
     */
    fun onOpen() {
        scope.launch { runCatching { refreshBackground() } }
        if (System.currentTimeMillis() - lastQuietRoundAt < OPEN_GAP_MS) return
        scope.launch { quietRound() }
    }

    /** Bluetooth came back on: the scan Android dropped is registered again, then a quiet round. */
    fun onBluetoothOn() {
        scope.launch {
            runCatching { refreshBackground() }
            delay(BT_SETTLE_MS)
            quietRound()
        }
    }

    /**
     * A quiet round if this phone has peers and Bluetooth. One at a time: a request during a round
     * runs another right after it, so a change made meanwhile isn't left behind.
     */
    suspend fun quietRound(ms: Long = ROUND_MS) {
        if (!transport.hasPermissions() || !transport.isOn) return
        if (repository().peers().isEmpty()) return
        if (!roundLock.tryLock()) {
            again = true
            return
        }
        try {
            do {
                again = false
                lastQuietRoundAt = System.currentTimeMillis()
                try {
                    syncRound(emptyList(), quiet = true, ms = ms)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Stopped (e.g. Android ended the periodic job): nothing was half-applied, as each
                    // phone merges what it gets in one transaction; the next trigger syncs again.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Quiet sync failed: ${e.javaClass.simpleName}", e)
                }
            } while (again)
        } finally {
            roundLock.unlock()
        }
    }

    // ---- Woken in the background by another phone's sync advertisement ----

    /**
     * Connects to a phone of this household that is asking for a sync, and syncs (SPEC §9.6). Called
     * from the background receiver, which allows about 10 s.
     */
    suspend fun onWoken(found: BleTransport.Found) {
        if (found.kind != BleTransport.KIND_SYNC || doneTokens.containsKey(found.token)) return
        val repo = repository()
        val household = repo.household() ?: return
        if (!BleTransport.syncTag(household.key, found.header).contentEquals(found.tag)) return
        if (sessionLock.isLocked) return
        val me = me()
        val socket = try {
            transport.connect(found)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't connect for a sync: ${e.javaClass.simpleName}", e)
            return
        }
        val result = session(socket, WOKEN_MS) { link -> SyncSession.client(link, household.key, me, repo) }
        if (result != null) doneTokens[found.token] = System.currentTimeMillis()
    }

    /**
     * Keeps Android's background scan registered and the periodic sync scheduled while this phone has
     * peers (else stops both).
     */
    suspend fun refreshBackground() {
        val repo = repository()
        val paired = repo.household() != null && repo.peers().isNotEmpty()
        if (paired) SyncJobService.schedule(context) else SyncJobService.cancel(context)
        if (!transport.hasPermissions()) return
        if (paired) transport.registerBackground() else transport.unregisterBackground()
    }

    // ---- Internals ----

    private suspend fun session(socket: BluetoothSocket, ms: Long = SESSION_MS, run: suspend (Link) -> SyncSession.Result): SyncSession.Result? {
        if (!sessionLock.tryLock()) {
            runCatching { socket.close() }
            return null
        }
        var peer: String? = null
        return try {
            withDeadline(socket, ms) { run(link(socket)) }.also {
                peer = it.peerId
                repository().peerResult(it.peerId, ok = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync session failed: ${e.javaClass.simpleName}", e)
            peer?.let { repository().peerResult(it, ok = false, reason = "failed") }
            null
        } finally {
            runCatching { socket.close() }
            sessionLock.unlock()
        }
    }

    private suspend fun askCode(code: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        answer = d
        _ui.value = SyncUi.Code(code)
        return d.await().also { if (it) _ui.value = SyncUi.Connecting }
    }

    private fun launchFlow(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        if (!transport.hasPermissions() || !transport.isOn) {
            _ui.value = SyncUi.Failed(SyncUi.Reason.BLUETOOTH)
            return
        }
        job = scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync step failed: ${e.javaClass.simpleName}", e)
                _ui.value = SyncUi.Failed(SyncUi.Reason.CONNECTION)
            }
        }
    }

    private fun link(socket: BluetoothSocket) = Link(socket.inputStream, socket.outputStream) { socket.close() }

    companion object {
        private const val TAG = "MoStor"
        private const val ADDING_MS = 5 * 60_000L
        private const val SEARCH_MS = 2 * 60_000L
        private const val PAIRING_MS = 3 * 60_000L
        private const val ROUND_MS = 30_000L
        private const val SESSION_MS = 60_000L
        private const val WOKEN_MS = 9_000L
        private const val DEBOUNCE_MS = 2_000L
        private const val OPEN_GAP_MS = 30_000L
        private const val BT_SETTLE_MS = 1_500L

        /** What the joining phone needs to match children: the adder's live children and their meds. */
        fun childrenAndMeds(rows: Merge.Rows): Merge.Rows {
            val live = rows.children.filter { it.deletedAt == null }
            val ids = live.mapTo(HashSet()) { it.id }
            return Merge.Rows(children = live, presets = rows.presets.filter { it.childId in ids && it.deletedAt == null })
        }
    }
}

/** Runs [block], closing [socket] if it takes longer than [ms] (a blocked read then fails). */
internal suspend fun <T> withDeadline(socket: Closeable, ms: Long, block: suspend () -> T): T = coroutineScope {
    val watchdog = launch(Dispatchers.Default) {
        delay(ms)
        runCatching { socket.close() }
    }
    try {
        block()
    } finally {
        watchdog.cancel()
    }
}
