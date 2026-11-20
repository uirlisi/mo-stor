package io.github.uirlisi.mostor.sync

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.data.HouseholdSync
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.sync.SyncRounds.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What Settings › Sync shows while pairing or syncing. */
sealed interface SyncUi {
    data object Idle : SyncUi
    /** "Invite a phone": advertising, waiting for the other phone to join. */
    data object WaitingForJoiner : SyncUi
    /** "Join a household": looking for a phone that is adding. */
    data object Searching : SyncUi
    data object Connecting : SyncUi
    /** Both phones show [code]; [answered] once this phone's owner has said whether it matches. */
    data class Code(val code: String, val answered: Boolean = false) : SyncUi
    /** Which of this phone's children are the other phone's (SPEC §9.4 step 4). */
    data class Matching(val peerName: String, val theirs: List<Child>, val mine: List<Child>) : SyncUi
    data object Syncing : SyncUi
    data class Paired(val peerName: String) : SyncUi
    /** Synced with [peers]; [missed]: the household's other phones that didn't answer. */
    data class Synced(val peers: List<String>, val received: Int, val missed: List<String> = emptyList()) : SyncUi
    data class Failed(val reason: Reason) : SyncUi
    /** Joining: this phone already holds the household, as its household named [name]. */
    data class AlreadyIn(val name: String?) : SyncUi

    enum class Reason { NO_PHONE, CODES_DIFFER, CONNECTION, NO_ANSWER, BLUETOOTH, ALREADY_THERE }
}

/**
 * Runs pairing and sync sessions (SPEC §9) for the active household (only it syncs, SPEC §9.4).
 * One session at a time on this phone. The screens follow [ui]; the background receiver calls
 * [onWoken]. Nothing here logs records, names or keys. Pairing itself is [PairingFlow]'s, rounds
 * and sessions are [SyncRounds]'s; this class holds the screen state and what starts them.
 */
class SyncManager(
    private val context: Context,
    private val repository: () -> Repository,
    private val scope: CoroutineScope,
    private val households: HouseholdHost? = null,
) {
    val transport = BleTransport(context)
    private val _ui = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val ui: StateFlow<SyncUi> = _ui.asStateFlow()

    private val phone = ThisPhone(context, repository, households)
    private val rounds = SyncRounds(repository, transport, phone, _ui, refreshBackground = { refreshBackground() })
    private val pairing = PairingFlow(
        repository, households, transport, phone, _ui, rounds,
        askCode = { askCode(it) },
        refreshBackground = { refreshBackground() },
    )
    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null

    /** Sessions already done (by advertisement token), so repeated scan results don't sync twice. */
    private val doneTokens = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?) = size > 32
    })

    // ---- Screens' actions ----

    /** The owner's answer to "Does the other phone show the same code?" */
    fun answerCode(same: Boolean) {
        (_ui.value as? SyncUi.Code)?.let { _ui.value = it.copy(answered = true) }
        answer?.complete(same)
    }

    fun finishMatching(same: Map<String, String>) = pairing.finishMatching(same)

    fun cancel() {
        job?.cancel()
        answer?.complete(false)
        _ui.value = SyncUi.Idle
    }

    fun dismiss() {
        if (job?.isActive != true) _ui.value = SyncUi.Idle
    }

    /** "Invite a phone" ([PairingFlow.invite]). */
    fun startAdding() = launchFlow { pairing.invite() }

    /** "Join a household" ([PairingFlow.join]). */
    fun startJoining(way: JoinWay) = launchFlow { pairing.join(way) }

    /**
     * "Leave household" (SPEC §9.4): this phone says so in its own row and syncs once. The phone that
     * answers gets its leaving and its last records, and it leaves. If none answers (or Bluetooth is
     * off), it leaves anyway, and tells them the next time one of them is near ([leaveNow]). The only
     * active phone of a household has no Leave.
     */
    fun leave() {
        scope.launch {
            val repo = repository()
            if (repo.sync.leave()) return@launch
            if (!transport.hasPermissions() || !transport.isOn) return@launch leaveNow()
            runCatching { refreshBackground() }
            // A "Sync now" still running would make launchFlow skip this, leaving the phone half left.
            job?.cancelAndJoin()
            launchFlow {
                rounds.visibleRound()
                if (repo.sync.isLeaving()) {
                    repo.sync.leaveNow()
                    _ui.value = SyncUi.Idle // Settings says it left and will tell them
                    runCatching { refreshBackground() }
                }
            }
        }
    }

    /**
     * "Leave now": this phone leaves at once and keeps the old household's key for up to 30 days, to
     * tell the first of its phones it hears that it left ([partWith]).
     */
    fun leaveNow() {
        scope.launch {
            repository().sync.leaveNow()
            runCatching { refreshBackground() }
        }
    }

    /** "Sync now": advertise for a sync until every other phone of the household has answered, or 30 s. */
    fun syncNow() = launchFlow {
        phone.ready()
        if (repository().sync.roundPeers().isEmpty()) return@launchFlow
        rounds.visibleRound()
    }

    // ---- Automatic syncs (SPEC §9.6, checkpoint b) ----

    private var debounce: Job? = null

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
        if (System.currentTimeMillis() - rounds.lastQuietRoundAt < OPEN_GAP_MS) return
        scope.launch { quietRound() }
    }

    /**
     * The active household changed (SPEC §9.4): a pairing or "Sync now" of the old one stops, the
     * background scan and the periodic job follow the new one, and it syncs at once. A quiet round
     * already under way finishes with the old household's database, then runs again for the new.
     */
    suspend fun onSwitched() {
        cancel()
        doneTokens.clear()
        runCatching { refreshBackground() }
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

    /** A quiet round ([SyncRounds.quietRound]): the triggers' and the periodic job's. */
    suspend fun quietRound(ms: Long = SyncRounds.ROUND_MS) = rounds.quietRound(ms)

    // ---- Woken in the background by another phone's sync advertisement ----

    /**
     * Connects to a phone of this household that is asking for a sync, and syncs (SPEC §9.6). Called
     * from the background receiver, which allows about 10 s.
     */
    suspend fun onWoken(found: BleTransport.Found) {
        if (found.kind != BleTransport.KIND_SYNC || doneTokens.containsKey(found.token)) return
        val repo = repository()
        val household = repo.sync.household()
        if (household == null || !BleTransport.syncTag(household.key, found.header).contentEquals(found.tag)) return partWith(found)
        if (rounds.sessionLock.isLocked) return
        val me = phone.ready()
        val socket = try {
            transport.connect(found)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't connect for a sync: ${e.javaClass.simpleName}", e)
            return
        }
        when (rounds.session(socket, repo, WOKEN_MS) { link -> SyncSession.client(link, household.key, me, repo.sync) }) {
            is Outcome.Synced, is Outcome.Refused, Outcome.Left -> doneTokens[found.token] = System.currentTimeMillis()
            Outcome.Failed -> Unit
        }
    }

    /**
     * A phone of a household one of this phone's databases left without telling it is asking for a
     * sync ([HouseholdSync.leaveNow], SPEC §9.4): this phone connects once, hands over its leaving and its
     * own records from before it left, takes nothing back, and forgets that household's key. Any of
     * the phone's households may part this way, not only the active one: it carries no records in.
     */
    private suspend fun partWith(found: BleTransport.Found) {
        for (repo in households?.repositories() ?: listOf(repository())) {
            val parting = repo.sync.parting() ?: continue
            if (!BleTransport.syncTag(parting.key, found.header).contentEquals(found.tag)) continue
            if (!rounds.sessionLock.tryLock()) return
            try {
                val socket = transport.connect(found)
                try {
                    withDeadline(socket, WOKEN_MS) { SyncSession.client(link(socket), parting.key, phone.me(repo), PartingStore(repo.sync, parting)) }
                    Log.i(TAG, "Told the household this phone left")
                    repo.sync.partingDone()
                } catch (e: RefusedByPeer) {
                    // Removed there meanwhile: nothing left to tell. Not known there: another phone may be.
                    if (e.removed) repo.sync.partingDone()
                } finally {
                    runCatching { socket.close() }
                }
                doneTokens[found.token] = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't tell the household this phone left: ${e.javaClass.simpleName}", e)
            } finally {
                rounds.sessionLock.unlock()
            }
            runCatching { refreshBackground() }
            return
        }
    }

    /**
     * Keeps Android's background scan registered and the periodic sync scheduled while this phone has
     * other phones in its household, a removed phone still to tell, or a household it left still to
     * tell (else stops both).
     */
    suspend fun refreshBackground() {
        val repo = repository()
        if (repo.sync.household() != null) repo.sync.ensureRoster(phone.deviceName())
        val parting = (households?.repositories() ?: listOf(repo)).any { it.sync.parting() != null }
        val paired = parting || repo.sync.household() != null && (repo.sync.others().isNotEmpty() || repo.sync.farewells().isNotEmpty())
        if (paired) SyncJobService.schedule(context) else SyncJobService.cancel(context)
        if (!transport.hasPermissions()) return
        if (paired) transport.registerBackground() else transport.unregisterBackground()
    }

    // ---- Internals ----

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
        private const val WOKEN_MS = 9_000L
        private const val DEBOUNCE_MS = 2_000L
        private const val OPEN_GAP_MS = 30_000L
        private const val BT_SETTLE_MS = 1_500L
    }
}
