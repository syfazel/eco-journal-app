package com.ecojournal.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Periodic edge sync — GATED OFF in v5.2 (audit §4.5 option A).
 *
 * The 15-minute worker used to sync articles/shorts/market and
 * drive new-article notifications. In Yemen-only mode every one
 * of those sections is locked, so there is nothing left for it
 * to sync — and it NEVER syncs Yemen either (that would violate
 * the 24-hour Yemen cadence; Yemen syncs only through
 * YemenSyncWorker + its launch one-time + the Yemen tab's manual
 * refresh). schedule() therefore cancels any previously
 * scheduled periodic/launch work instead of (re)enqueueing it,
 * and a stale enqueued run succeeds silently. The class stays
 * so the cancelled unique names keep resolving; do not
 * re-enable it without a fresh decision. The FCM path stays
 * dormant by decision (docs/vnext-decisions.md §3).
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "eco_edge_sync"
        const val LAUNCH_UNIQUE_NAME = "eco_edge_sync_launch"

        /** v5.2: cancel the 15-minute periodic work and its
         * launch one-time. The Yemen launch sync lives on
         * YemenSyncWorker.enqueueLaunchSync, not here. */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(UNIQUE_NAME)
            manager.cancelUniqueWork(LAUNCH_UNIQUE_NAME)
        }

        /** v5.2: no launch sync through this worker any more. */
        fun enqueueLaunchSync(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(LAUNCH_UNIQUE_NAME)
        }
    }
}
