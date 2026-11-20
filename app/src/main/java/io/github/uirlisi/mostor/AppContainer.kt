package io.github.uirlisi.mostor

import android.content.Context
import android.util.Log
import io.github.uirlisi.mostor.core.HouseholdSlot
import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.core.Ids
import io.github.uirlisi.mostor.data.AutoExport
import io.github.uirlisi.mostor.data.HouseholdStore
import io.github.uirlisi.mostor.data.MoStorDb
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.notify.StatusNotifier
import io.github.uirlisi.mostor.sync.AndroidKeyVault
import io.github.uirlisi.mostor.sync.HouseholdHost
import io.github.uirlisi.mostor.sync.NewHousehold
import io.github.uirlisi.mostor.sync.SyncManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual dependency injection (CLAUDE.md). The Application owns the one instance. Everything is
 * lazy so a cold start (notification tap → quick sheet) does no database work on the main thread.
 *
 * Several households (SPEC §9.4): each is its own database, opened when first used and kept open.
 * [repository] and [db] are always the active household's; switching ([switchTo]) swaps them, and
 * the screens, which hold the repository they started with, are recreated.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /**
     * Work that has to outlive any one screen or receiver (writes, notification refreshes). A failure
     * in one job is logged and doesn't take the app down: a notification refresh runs on every
     * process start, so an uncaught one would crash the app on every launch.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("MoStor", "Background work failed: ${e.javaClass.simpleName}", e)
        },
    )

    /** Screens' operation states that outlive a recreated screen (ui.rememberOperationState). */
    val operationStates = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.runtime.MutableState<*>>()

    /** The phone's own store: its households and the active one. */
    val households: HouseholdStore by lazy { HouseholdStore(appContext) }

    private class Opened(val db: MoStorDb, val repository: Repository)

    private val opened = HashMap<String, Opened>()

    /** The active household's database. */
    val db: MoStorDb get() = open(households.list().active).db

    /** The active household's repository: every screen, the notification and sync use it. */
    val repository: Repository get() = open(households.list().active).repository

    val notifier: StatusNotifier by lazy { StatusNotifier(appContext, { repository }, appScope, householdName = ::activeNameIfSeveral) }

    /** Bluetooth pairing and sync (SPEC §9), for the active household only. */
    val sync: SyncManager by lazy { SyncManager(appContext, { repository }, appScope, host) }

    /** The daily backup to a folder (SPEC §8), run after writes and on app start. */
    val autoExport: AutoExport by lazy {
        AutoExport(appContext, { households.list().active.let { AutoExport.Target(open(it).repository, fileTagOf(it)) } }, appScope)
    }

    @Synchronized
    private fun open(slot: HouseholdSlot): Opened = opened.getOrPut(slot.id) {
        val db = MoStorDb.build(appContext, slot.file)
        // After every write: the notification, then the daily backup if it hasn't run today. Only for
        // the active household; a household being joined writes before it becomes active.
        val repo = Repository(
            db,
            { if (isActive(slot.id)) { notifier.refresh(); autoExport.maybeRun() } },
            installId = ::installId,
            vault = AndroidKeyVault(),
        )
        repo.onLocalChange = { if (isActive(slot.id)) sync.onLocalChange() }
        Opened(db, repo)
    }

    private fun isActive(id: String) = households.list().activeId == id

    /** A household's repository, active or not. */
    fun repositoryFor(slot: HouseholdSlot): Repository = open(slot).repository

    /** A household's display name: the owner's, or "Household 2". */
    fun nameOf(slot: HouseholdSlot): String = slot.name ?: appContext.getString(R.string.household_default, slot.number)

    /** The active household's name, only when the phone holds two or more (SPEC §6.1). */
    fun activeNameIfSeveral(): String? = households.list().takeIf { it.several }?.let { nameOf(it.active) }

    /** Tells the daily backups of households apart; the first household keeps the old names. */
    private fun fileTagOf(slot: HouseholdSlot): String? = slot.takeIf { !it.isFirst }?.let { "h${it.number}" }

    /** The owner renames a household (Settings › Households and phones, in a dialog); the notification follows. */
    fun renameHousehold(id: String, name: String) {
        // Off the main thread; the store's state changes at once, its file a moment later.
        appScope.launch {
            households.update(durable = false) { it.rename(id, name) }
            notifier.refresh()
        }
    }

    /**
     * Switches to household [id] (Settings › Households and phones): the screens are recreated on the new
     * database ([households]'s flow), the notification shows it, and it syncs at once. The caller
     * has asked to stop any running timer first (SPEC §9.4).
     */
    suspend fun switchTo(id: String) {
        if (households.list().activeId == id) return
        households.update { it.activate(id) }
        afterSwitch()
    }

    private suspend fun afterSwitch() {
        // The theme is one of the household's per-phone settings.
        io.github.uirlisi.mostor.ui.applyThemeChoice(appContext, repository.settings().theme)
        sync.onSwitched()
        notifier.refresh()
        autoExport.maybeRun()
    }

    /**
     * "Delete this household" (SPEC §9.4): only while the phone holds another one, which becomes
     * active first if [id] is; then its database is closed and deleted for good. The caller has
     * checked that no other active phone shares it, offered a backup, and asked. The database is
     * closed a few seconds after the switch, once the screens that held it have been recreated and a
     * round or backup still using it has had time to finish.
     */
    suspend fun deleteHousehold(id: String) {
        val list = households.list()
        check(list.several) { "the last household can't be deleted" }
        val slot = list.slot(id) ?: return
        val wasActive = list.activeId == id
        households.update { it.remove(id) }
        if (wasActive) {
            afterSwitch()
            delay(CLOSE_AFTER_SWITCH_MS)
        }
        withContext(Dispatchers.IO) { closeAndDelete(slot) }
    }

    /** The household is already off the list, its file marked to delete ([HouseholdList.pendingDelete]). */
    private fun closeAndDelete(slot: HouseholdSlot) {
        synchronized(this) { opened.remove(slot.id) }?.db?.close()
        if (appContext.deleteDatabase(slot.file)) households.update { it.deleted(slot.file) }
    }

    /**
     * A new household (SPEC §9.4): its own database, with a blank child, its own device id, and the
     * active household's choices (no records, no sync state). Not active yet.
     */
    private suspend fun createHousehold(name: String? = null): NewHousehold {
        val choices = repository.sync.choicesForNewHousehold()
        val id = Ids.uuidV7(System.currentTimeMillis())
        val slot = households.update { it.add(id, name) }.slot(id)!!
        val repo = open(slot).repository
        repo.ensureInitialized()
        repo.sync.adoptChoices(choices)
        return NewHousehold(id, repo)
    }

    /**
     * "Add a household" (Settings › Households and phones, SPEC §9.4): for records kept apart from any other, such
     * as a nanny caring for a family that doesn't use the app. It becomes the active household. The
     * caller has asked to stop any running timer first.
     */
    suspend fun addHousehold(name: String?) {
        val added = createHousehold(name)
        switchTo(added.id)
    }

    /** What pairing needs to join a household apart (SPEC §9.4 "Joining"). */
    private val host = object : HouseholdHost {
        override suspend fun create(): NewHousehold = createHousehold()

        // Called during the join itself, so sync isn't stopped as a switch by hand would.
        override suspend fun activate(id: String) {
            households.update { it.activate(id) }
            notifier.refresh()
        }

        override suspend fun discard(id: String) {
            val list = households.list()
            val slot = list.slot(id) ?: return
            if (list.activeId == id || !list.several) return
            households.update { it.remove(id) }
            withContext(Dispatchers.IO) { closeAndDelete(slot) }
        }

        override suspend fun deviceIds(): Set<String> =
            households.list().slots.map { open(it).repository.sync.deviceId() }.toSet()

        override suspend fun repositories(): List<Repository> = households.list().slots.map { open(it).repository }

        override fun activeName(): String? = households.list().active.name

        override suspend fun holding(householdId: String): String? =
            households.list().slots.firstOrNull { open(it).repository.sync.household()?.id == householdId }?.let(::nameOf)

        override suspend fun joined(id: String?, theirs: String?, way: JoinWay) {
            households.update { it.joined(id ?: it.activeId, theirs, way) }
            notifier.refresh()
        }
    }

    /**
     * This install's id, kept in the one folder Android never backs up or transfers. A database
     * that arrives on another phone finds a different id there (DECISIONS 2026-11-14).
     */
    @Synchronized
    private fun installId(): String {
        val file = File(appContext.noBackupFilesDir, "install-id")
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return UUID.randomUUID().toString().also { file.writeText(it) }
    }

    private companion object {
        const val CLOSE_AFTER_SWITCH_MS = 5_000L
    }
}

val Context.container: AppContainer get() = (applicationContext as MoStorApp).container
