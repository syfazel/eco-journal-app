package com.ecojournal.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Yemen-only periodic sync (v5.2, audit §4.3).
 *
 * 24-hour cadence. Phase 4 (v5.3): the old 15-minute
 * SyncWorker is deleted with the sections it synced, so this
 * worker is the only periodic content sync left — plus the
 * launch one-time below and the Yemen tab's manual refresh
 * (same repo.syncYemen path).
 *
 * Manifest-gated: when sections.yemen.hash equals the stored
 * hash the pass fetches nothing. A failure is a fixed error code
 * in yemen_meta and a retry on the next cycle — it never touches
 * the global sync clock (P4, both directions).
 */
class YemenSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val repo = EdgeRepository(applicationContext)
            when (repo.syncYemen(forceRefresh = false)) {
                is SyncResult.Ok -> Result.success()
                is SyncResult.Partial -> Result.retry()
                is SyncResult.NotConfigured -> Result.success()
                is SyncResult.Failed -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "eco_yemen_sync"
        const val LAUNCH_UNIQUE_NAME = "eco_yemen_sync_launch"

        /** One-time launch sync (same path as the periodic
         * worker). Called from EcoJournalApp.onCreate so a fresh
         * launch re-checks Yemen once, without waiting up to 24h
         * for the periodic cycle. CONNECTED constraint, so with
         * no network it waits instead of failing. */
        fun enqueueLaunchSync(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (EdgeConfig.BASE_URL.isBlank()) {
                manager.cancelUniqueWork(LAUNCH_UNIQUE_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<YemenSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork(
                LAUNCH_UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (EdgeConfig.BASE_URL.isBlank()) {
                manager.cancelUniqueWork(UNIQUE_NAME)
                manager.cancelUniqueWork(LAUNCH_UNIQUE_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<YemenSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
            enqueueLaunchSync(context)
        }
    }
}
