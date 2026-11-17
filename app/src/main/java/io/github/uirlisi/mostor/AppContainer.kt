package io.github.uirlisi.mostor

import android.content.Context
import io.github.uirlisi.mostor.data.MoStorDb
import io.github.uirlisi.mostor.data.AutoExport
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.sync.AndroidKeyVault
import io.github.uirlisi.mostor.sync.SyncManager
import java.io.File
import java.util.UUID
import io.github.uirlisi.mostor.notify.StatusNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection (CLAUDE.md). The Application owns the one instance. Everything is
 * lazy so a cold start (notification tap → quick sheet) does no database work on the main thread.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Work that has to outlive any one screen or receiver (writes, notification refreshes). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: MoStorDb by lazy { MoStorDb.build(appContext) }

    val notifier: StatusNotifier by lazy { StatusNotifier(appContext, { repository }, appScope) }

    /** After every write: the notification, then the daily backup if it hasn't run today. */
    val repository: Repository by lazy {
        Repository(db, { notifier.refresh(); autoExport.maybeRun() }, installId = ::installId, vault = AndroidKeyVault())
            .also { it.onLocalChange = { sync.onLocalChange() } }
    }

    /** Bluetooth pairing and sync (SPEC §9). */
    val sync: SyncManager by lazy { SyncManager(appContext, { repository }, appScope) }

    /** The daily backup to a folder (SPEC §8), run after writes and on app start. */
    val autoExport: AutoExport by lazy { AutoExport(appContext, { repository }, appScope) }

    /**
     * This install's id, kept in the one folder Android never backs up or transfers. A database
     * that arrives on another phone finds a different id there (DECISIONS 2026-11-14).
     */
    private fun installId(): String {
        val file = File(appContext.noBackupFilesDir, "install-id")
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return UUID.randomUUID().toString().also { file.writeText(it) }
    }
}

val Context.container: AppContainer get() = (applicationContext as MoStorApp).container
