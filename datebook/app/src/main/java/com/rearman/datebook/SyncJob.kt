package com.rearman.datebook

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Runs [SyncEngine] in the background.
 *
 * Two jobs start it: the hourly job (id 41) and the backup job (id 42) that runs 30 s after a
 * change, when the in-app sync could not run (offline, or the app closed first). The in-app sync
 * runs 1 s after a save and on each open. JobScheduler waits for a network before it starts either job.
 */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        SyncEngine.run(this) { _ -> jobFinished(params, false) }
        return true
    }

    // The system calls this when it stops a running job. The next scheduled run starts the sync again.
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val PERIODIC_ID = 41
        private const val SOON_ID = 42

        /** Schedules the hourly sync (job 41). Any network, persisted across reboots. */
        fun schedulePeriodic(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(ctx, SyncJob::class.java))
                    .setPeriodic(60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Schedules a sync 30 s from now (job 42). Does nothing when job 42 is already pending. */
        fun scheduleSoon(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(SOON_ID) != null) return
            js.schedule(
                JobInfo.Builder(SOON_ID, ComponentName(ctx, SyncJob::class.java))
                    .setMinimumLatency(30_000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        }

        /** Cancels the pending sync (42), after an in-app sync did its work. */
        fun cancelSoon(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).cancel(SOON_ID)
        }

        /** Cancels the hourly sync (41) and the pending sync (42). */
        fun cancelAll(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.cancel(PERIODIC_ID)
            js.cancel(SOON_ID)
        }
    }
}
