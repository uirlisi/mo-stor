package io.github.uirlisi.mostor.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup file names and writing, shared by the manual export and the daily backup (SPEC §8).
 * The dev build's files say "dev", so the two apps never mix up (or prune) each other's.
 */
object BackupFiles {
    const val JSON_MIME = "application/json"
    const val CSV_MIME = "text/csv"

    private fun prefix(context: Context) = if (context.packageName.endsWith(".dev")) "mostor-dev" else "mostor"

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    /** mostor-backup-2026-11-14-0916.json: a manual export, never pruned. */
    fun manualJson(context: Context) = "${prefix(context)}-backup-${STAMP.format(LocalDateTime.now())}.json"

    /** mostor-2026-11-14-0916.csv */
    fun csv(context: Context) = "${prefix(context)}-${STAMP.format(LocalDateTime.now())}.csv"

    /** mostor-daily-2026-11-14.json: the daily backup, of which the newest [AutoExport.KEEP] are kept. */
    fun daily(context: Context, day: LocalDate) = "${prefix(context)}-daily-$day.json"

    fun dailyPattern(context: Context) = Regex("^${Regex.escape(prefix(context))}-daily-\\d{4}-\\d{2}-\\d{2}\\.json$")

    /** The app's version for the backup's `app_version` field. */
    fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Writes [text] as UTF-8 to [uri], replacing what was there. */
    fun write(context: Context, uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

/**
 * The optional daily backup (SPEC §8): once a day, a JSON backup into a folder the owner picked
 * (a persisted storage-access grant, no permission), keeping the newest [KEEP] daily files. It runs
 * the first time the app writes or starts on a new day (DECISIONS 2026-11-14, "Daily backup"), so
 * it needs no alarm and no background work: a phone in use writes many times a day. A run that the
 * system cuts short is simply tried again on the next write.
 */
class AutoExport(
    private val context: Context,
    private val repository: () -> Repository,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)

    /** A failed attempt isn't retried within this long, so a broken folder doesn't retry on every write. */
    @Volatile
    private var lastFailureAt = 0L

    /** Starts a run in the background if the daily backup is on and hasn't run today. Returns at once. */
    fun maybeRun() {
        scope.launch { runIfDue(force = false) }
    }

    sealed interface Result {
        data object Off : Result
        data object NotDue : Result
        data object Written : Result
        data object Failed : Result
    }

    /** Runs now if due (or, with [force], anyway: "Back up now"). */
    suspend fun runIfDue(force: Boolean): Result {
        val repo = repository()
        val settings = repo.settings()
        val folder = settings.autoExportUri ?: return Result.Off
        val today = LocalDate.now()
        if (!force && settings.autoExportLast == today.toString()) return Result.NotDue
        if (!force && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_MS) return Result.NotDue
        if (!running.compareAndSet(false, true)) return Result.NotDue
        return try {
            withContext(Dispatchers.IO) { writeDaily(Uri.parse(folder), today, repo) }
            repo.setSettingsQuietly(mapOf(Keys.AUTO_EXPORT_LAST to today.toString(), Keys.AUTO_EXPORT_STATUS to "ok"))
            Result.Written
        } catch (e: Exception) {
            Log.w("MoStor", "Daily backup failed: ${e.javaClass.simpleName}")
            lastFailureAt = System.currentTimeMillis()
            repo.setSettingsQuietly(mapOf(Keys.AUTO_EXPORT_STATUS to "failed"))
            Result.Failed
        } finally {
            running.set(false)
        }
    }

    private suspend fun writeDaily(tree: Uri, day: LocalDate, repo: Repository) {
        val resolver = context.contentResolver
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = BackupFiles.daily(context, day)
        val existing = children(tree).firstOrNull { it.second == name }?.first
        val target = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(resolver, folder, BackupFiles.JSON_MIME, name)
            ?: error("couldn't create the file")
        val json = BackupJson.encode(repo.backupRows(), System.currentTimeMillis(), BackupFiles.appVersion(context))
        BackupFiles.write(context, target, json)

        // Keep the newest daily files (by the date in their names); only this app's daily files go.
        val pattern = BackupFiles.dailyPattern(context)
        children(tree).filter { pattern.matches(it.second) }
            .sortedByDescending { it.second }
            .drop(KEEP)
            .forEach { (id, _) -> DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
    }

    /** The folder's files: (document id, display name). */
    private fun children(tree: Uri): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }.orEmpty()
    }

    companion object {
        const val KEEP = 14
        private const val RETRY_AFTER_MS = 3_600_000L
    }
}
