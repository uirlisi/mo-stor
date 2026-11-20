package io.github.uirlisi.mostor.sync

import android.bluetooth.BluetoothSocket
import android.util.Log
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.data.Repository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing (SPEC §9.4) for [SyncManager]: "Invite a phone" ([invite]), "Join a household" ([join])
 * and child matching, showing each step in [ui]. [SyncManager] runs them as its one flow at a time.
 * Nothing here logs records, names or keys.
 */
internal class PairingFlow(
    private val repository: () -> Repository,
    private val households: HouseholdHost?,
    private val transport: BleTransport,
    private val phone: ThisPhone,
    private val ui: MutableStateFlow<SyncUi>,
    private val rounds: SyncRounds,
    private val askCode: suspend (String) -> Boolean,
    private val refreshBackground: suspend () -> Unit,
) {
    private var matched: CompletableDeferred<Map<String, String>>? = null

    fun finishMatching(same: Map<String, String>) {
        matched?.complete(same)
    }

    /**
     * "Invite a phone" (SPEC §9.4): advertise for pairing until a phone joins, or 5 minutes. Not while
     * this phone is leaving its household.
     */
    suspend fun invite() {
        val repo = repository()
        if (repo.sync.isLeaving()) return
        ui.value = SyncUi.WaitingForJoiner
        val household = repo.sync.ensureHousehold()
        val me = phone.ready()
        val done = CompletableDeferred<SyncUi>()
        val claimed = AtomicBoolean(false)
        val adv = transport.advertise(BleTransport.KIND_PAIR, tagFor = { ByteArray(4) }) { socket ->
            if (!claimed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                return@advertise
            }
            // Until the codes show, a failure (a device that connected and stayed silent, a dropped
            // link) leaves the invite open for the next phone instead of ending it.
            val codesShown = AtomicBoolean(false)
            runBlocking {
                val outcome = try {
                    val input = HeardInput(socket.inputStream)
                    val joined = withDeadline(socket, PAIRING_MS, heard = { input.heard }) {
                        Pairing.adder(Link(input, socket.outputStream) { socket.close() }, me, offer = { who ->
                            Pairing.Offer(
                                household.id, household.key, childrenAndMeds(repo.backupRows()),
                                repo.sync.members().filter { it.deviceId != who.peerId },
                                households?.activeName(),
                            )
                        }) { code -> codesShown.set(true); askCode(code) }
                    }
                    repo.sync.addMember(joined.peerId, joined.peerName)
                    refreshBackground()
                    SyncUi.Paired(joined.peerName)
                } catch (e: Pairing.Declined) {
                    SyncUi.Failed(SyncUi.Reason.CODES_DIFFER)
                } catch (e: RefusedByPeer) {
                    SyncUi.Failed(if (e.already) SyncUi.Reason.ALREADY_THERE else SyncUi.Reason.CONNECTION)
                } catch (e: Exception) {
                    Log.w(TAG, "Pairing failed: ${e.javaClass.simpleName}", e)
                    SyncUi.Failed(SyncUi.Reason.CONNECTION)
                } finally {
                    runCatching { socket.close() }
                }
                if (outcome is SyncUi.Failed && !codesShown.get()) claimed.set(false) else done.complete(outcome)
            }
        }
        try {
            ui.value = withTimeoutOrNull(ADDING_MS) { done.await() } ?: SyncUi.Failed(SyncUi.Reason.NO_PHONE)
        } finally {
            adv.stop()
        }
    }

    /**
     * "Join a household" (SPEC §9.4 "Joining"): find a phone that is inviting, pair, match children,
     * then a first sync. [way] [JoinWay.MERGE] joins the active household's database, which only one
     * no other phone is active in may do; [JoinWay.APART] pairs from a new database (with its own
     * device id), which becomes the active household once joined, and goes again if the join fails.
     */
    suspend fun join(way: JoinWay) {
        val active = repository()
        if (active.sync.isLeaving() || way !in active.sync.joinWays()) return
        val host = households
        if (way == JoinWay.APART && host == null) return
        ui.value = SyncUi.Searching
        val found = transport.find(BleTransport.KIND_PAIR, SEARCH_MS)
        if (found == null) {
            ui.value = SyncUi.Failed(SyncUi.Reason.NO_PHONE)
            return
        }
        ui.value = SyncUi.Connecting
        val fresh = if (way == JoinWay.APART) host!!.create() else null
        var joined = false
        try {
            val repo = fresh?.repository ?: active
            val me = phone.me(repo)
            val household = try {
                val socket = transport.connect(found)
                try {
                    withDeadline(socket, PAIRING_MS) {
                        Pairing.joiner(link(socket), me, alreadyIn = { id -> holding(active, id) != null }) { code -> askCode(code) }
                    }
                } finally {
                    runCatching { socket.close() }
                }
            } catch (e: Pairing.Declined) {
                ui.value = SyncUi.Failed(SyncUi.Reason.CODES_DIFFER)
                return
            } catch (e: AlreadyIn) {
                ui.value = SyncUi.AlreadyIn(holding(active, e.householdId))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Joining failed: ${e.javaClass.simpleName}", e)
                ui.value = SyncUi.Failed(SyncUi.Reason.CONNECTION)
                return
            }
            repo.sync.joinHousehold(household.id, household.key, household.peerId, household.peerName, household.rows, household.members, me.name)
            joined = true
            host?.joined(fresh?.id, household.householdName, way)
            if (fresh != null) host!!.activate(fresh.id)
            finishJoining(repo, household)
        } finally {
            if (fresh != null && !joined) withContext(NonCancellable) { host!!.discard(fresh.id) }
        }
    }

    /**
     * The name of this phone's household that is household [householdId] (SPEC §9.4: a phone joins
     * a household at most once); "" when it is [active] and the phone has no host to name it.
     */
    private suspend fun holding(active: Repository, householdId: String): String? =
        households?.holding(householdId) ?: if (active.sync.household()?.id == householdId) "" else null

    /** After joining: child matching (only this database's children with something in them), then a first sync. */
    private suspend fun finishJoining(repo: Repository, household: Pairing.Household) {
        // Child matching: only this phone's children with something in them need an answer.
        val theirs = household.rows.children.filter { it.deletedAt == null }
        val rows = repo.backupRows()
        val theirIds = theirs.mapTo(HashSet()) { it.id }
        val mine = repo.homeState().children.filter { c ->
            c.id !in theirIds && !Merge.isBlank(c, rows.events.count { it.childId == c.id }, rows.presets.filter { it.childId == c.id })
        }
        val same = if (theirs.isEmpty() || mine.isEmpty()) {
            emptyMap()
        } else {
            matched = CompletableDeferred()
            ui.value = SyncUi.Matching(household.peerName, theirs, mine)
            matched!!.await()
        }
        repo.sync.matchChildren(same)
        refreshBackground()
        rounds.visibleRound()
    }

    private fun link(socket: BluetoothSocket) = Link(socket.inputStream, socket.outputStream) { socket.close() }

    companion object {
        private const val TAG = "MoStor"
        private const val ADDING_MS = 5 * 60_000L
        private const val SEARCH_MS = 2 * 60_000L
        private const val PAIRING_MS = 3 * 60_000L

        /** What the joining phone needs to match children: the adder's live children and their meds. */
        fun childrenAndMeds(rows: Merge.Rows): Merge.Rows {
            val live = rows.children.filter { it.deletedAt == null }
            val ids = live.mapTo(HashSet()) { it.id }
            return Merge.Rows(children = live, presets = rows.presets.filter { it.childId in ids && it.deletedAt == null })
        }
    }
}
