package io.github.uirlisi.mostor.sync

import android.bluetooth.BluetoothSocket
import android.util.Log
import io.github.uirlisi.mostor.data.Repository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sync rounds and sessions (SPEC §9.6) for [SyncManager]: sessions take turns on [sessionLock], and
 * rounds, one advertisement at a time, on [roundLock]. A visible round shows its progress in [ui].
 * Nothing here logs records, names or keys.
 */
internal class SyncRounds(
    private val repository: () -> Repository,
    private val transport: BleTransport,
    private val phone: ThisPhone,
    private val ui: MutableStateFlow<SyncUi>,
    private val refreshBackground: suspend () -> Unit,
) {
    val sessionLock = Mutex()

    private val roundLock = Mutex()
    @Volatile private var again = false
    @Volatile var lastQuietRoundAt = 0L
        private set

    /**
     * A round that shows its progress, after a quiet one still running (one advertisement at a time:
     * a phone that synced through another round's advertisement wouldn't count for this one).
     */
    suspend fun visibleRound(): Int {
        ui.value = SyncUi.Syncing
        val synced = roundLock.withLock { syncRound() }
        // A quiet round asked for meanwhile (a change made during this one) runs now, not at the next trigger.
        if (again) quietRound()
        return synced
    }

    /**
     * One round: advertise a sync for up to [ms] and serve every phone of the household that
     * connects, until each has synced or refused (SPEC §9.6). A visible round ("Sync now", after
     * pairing) shows its progress and result; a quiet one (on a change, on opening, the periodic job)
     * shows nothing. Returns how many phones synced.
     */
    private suspend fun syncRound(quiet: Boolean = false, ms: Long = ROUND_MS): Int {
        if (!quiet) ui.value = SyncUi.Syncing
        val repo = repository()
        val household = repo.sync.household() ?: run {
            if (!quiet) ui.value = SyncUi.Failed(SyncUi.Reason.CONNECTION)
            return 0
        }
        val me = phone.ready()
        val others = repo.sync.others()
        // Removed phones still to tell are waited for too: one woken by this round learns it was removed.
        val pending = PendingPeers(repo.sync.roundPeers())
        if (pending.isDone) {
            if (!quiet) ui.value = SyncUi.Idle
            return 0
        }
        val synced = java.util.Collections.synchronizedList(ArrayList<String>())
        // Sessions run on the accept threads, several at once.
        val received = java.util.concurrent.atomic.AtomicInteger()
        val left = AtomicBoolean(false)
        val allDone = CompletableDeferred<Unit>()
        val adv = transport.advertise(BleTransport.KIND_SYNC, tagFor = { BleTransport.syncTag(household.key, it) }) { socket ->
            runBlocking {
                when (val outcome = session(socket, repo) { link -> SyncSession.server(link, household.key, me, repo.sync) }) {
                    is Outcome.Synced -> {
                        synced += outcome.result.peerName
                        received.addAndGet(outcome.result.received)
                        if (pending.answered(outcome.result.peerId)) allDone.complete(Unit)
                    }
                    is Outcome.Refused -> if (outcome.peerId != null && pending.answered(outcome.peerId)) allDone.complete(Unit)
                    Outcome.Left -> {
                        left.set(true)
                        allDone.complete(Unit)
                    }
                    Outcome.Failed -> Unit
                }
            }
        }
        try {
            withTimeoutOrNull(ms) { allDone.await() }
        } finally {
            adv.stop()
        }
        if (!quiet) {
            val missed = others.filter { it.deviceId in pending.left() }.map { it.name }
            ui.value = when {
                left.get() -> SyncUi.Idle // Settings shows who removed this phone
                synced.isEmpty() && others.isEmpty() -> SyncUi.Idle // only a removed phone was told
                synced.isEmpty() -> SyncUi.Failed(SyncUi.Reason.NO_ANSWER)
                else -> SyncUi.Synced(synced.distinct(), received.get(), missed)
            }
        }
        return synced.distinct().size
    }

    /**
     * A quiet round if this phone has peers and Bluetooth. One at a time: a request during a round
     * runs another right after it, so a change made meanwhile isn't left behind.
     */
    suspend fun quietRound(ms: Long = ROUND_MS) {
        if (!transport.hasPermissions() || !transport.isOn) return
        if (repository().sync.roundPeers().isEmpty()) return
        if (!roundLock.tryLock()) {
            again = true
            return
        }
        try {
            do {
                again = false
                lastQuietRoundAt = System.currentTimeMillis()
                try {
                    syncRound(quiet = true, ms = ms)
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

    /** How a session ended. */
    sealed interface Outcome {
        data class Synced(val result: SyncSession.Result) : Outcome
        /** One phone refused the other (removed, or not known yet); [peerId] is the other phone. */
        data class Refused(val peerId: String?) : Outcome
        /** The other phone said this one was removed from the household: it has left it. */
        data object Left : Outcome
        data object Failed : Outcome
    }

    /**
     * One session on [socket] for [repo], the household the round or wake-up was for: a switch made
     * meanwhile must not apply its outcome (a removal, a peer's result) to the new active household.
     * Sessions take turns; one that can't start within [LOCK_WAIT_MS] gives up.
     */
    suspend fun session(socket: BluetoothSocket, repo: Repository, ms: Long = SESSION_MS, run: suspend (Link) -> SyncSession.Result): Outcome {
        if (withTimeoutOrNull(LOCK_WAIT_MS) { sessionLock.lock() } == null) {
            runCatching { socket.close() }
            return Outcome.Failed
        }
        return try {
            val input = HeardInput(socket.inputStream)
            val link = Link(input, socket.outputStream) { socket.close() }
            val result = withDeadline(socket, ms, heard = { input.heard }) { run(link) }
            if (repo.sync.isLeaving()) {
                // The other phone now has this phone's leaving and its last records: it can go.
                Log.i(TAG, "This phone left the household")
                repo.sync.leaveHousehold(removedBy = null)
                runCatching { refreshBackground() }
                return Outcome.Left
            }
            repo.sync.peerResult(result.peerId, ok = true)
            Outcome.Synced(result)
        } catch (e: RefusedByPeer) {
            if (e.removed) {
                Log.i(TAG, "This phone was removed from the household")
                repo.sync.leaveHousehold(e.by ?: e.peerName.orEmpty())
                runCatching { refreshBackground() }
                Outcome.Left
            } else {
                e.peerId?.let { repo.sync.peerResult(it, ok = false, reason = "unknown") }
                Outcome.Refused(e.peerId)
            }
        } catch (e: RefusedPeer) {
            Outcome.Refused(e.peerId)
        } catch (e: Exception) {
            Log.w(TAG, "Sync session failed: ${e.javaClass.simpleName}", e)
            Outcome.Failed
        } finally {
            runCatching { socket.close() }
            sessionLock.unlock()
        }
    }

    companion object {
        private const val TAG = "MoStor"
        const val ROUND_MS = 30_000L
        private const val SESSION_MS = 60_000L
        private const val LOCK_WAIT_MS = 2_000L
    }
}

/**
 * The phones a round still waits for. A phone is done when it synced or refused, so a round ends as
 * soon as every phone has answered, instead of waiting out its time for a phone that won't sync.
 */
internal class PendingPeers(ids: Collection<String>) {
    private val pending = ids.toMutableSet()

    val isDone: Boolean @Synchronized get() = pending.isEmpty()

    /** [id] answered; true when that was the last phone the round waited for. */
    @Synchronized
    fun answered(id: String): Boolean = pending.remove(id) && pending.isEmpty()

    @Synchronized
    fun left(): Set<String> = pending.toSet()
}
