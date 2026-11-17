package io.github.uirlisi.mostor.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.uirlisi.mostor.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The periodic background sync (SPEC §9.6): Android's own job scheduler runs a quiet sync round
 * about every 15 minutes, as the battery allows, while this phone has peers. No permission beyond
 * the boot one the app already has (DECISIONS 2026-11-14, "Background sync with JobScheduler").
 */
class SyncJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val c = applicationContext.container
        work = c.appScope.launch {
            try {
                // Also the safety net for a background scan Android dropped (Bluetooth off and on).
                runCatching { c.sync.refreshBackground() }
                c.sync.quietRound(ms = ROUND_MS)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Stopped by the system (e.g. battery saver): try again at the next period. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }

    companion object {
        private const val JOB_ID = 4401
        private const val PERIOD_MS = 15 * 60_000L
        private const val ROUND_MS = 20_000L

        /** Schedules the job unless it already is (keeps its timing). Survives a reboot. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
