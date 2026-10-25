package com.ecojournal.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ecojournal.app.util.Notifications
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic edge sync. Android enforces a 15-minute minimum interval
 * for periodic work — that is why the package promises "at most 15
 * minutes late" for the polling fallback, not instant delivery.
 * Instant delivery would be the FCM path; by the vNext decision
 * (docs/vnext-decisions.md §3) FCM stays dormant for the pilot,
 * so this polling path is the only notification path, and it is
 * honest about its 15-minute granularity.
 *
 * M5 (P1-19, P1-24): the worker honours the notifications toggle
 * (no notification when the user turned notifications off), runs
 * only with network, backs off exponentially on failure, and is
 * scheduled with UPDATE — not KEEP — so a changed schedule
 * definition actually replaces the old one. When the edge is not
 * configured, schedule() cancels the periodic work instead of
 * waking every 15 minutes for a no-op.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = EdgeRepository(applicationContext)
        return when (val r = repo.sync()) {
            is SyncResult.Ok -> {
                notifyNew(applicationContext, repo, r.newArticleAppKeys)
                Result.success()
            }
            is SyncResult.Partial -> {
                // Partial sync still delivered fresh articles; tell
                // the user about those, and retry later for the rest.
                notifyNew(applicationContext, repo, r.newArticleAppKeys)
                Result.retry()
            }
            is SyncResult.NotConfigured -> Result.success()
            is SyncResult.Failed -> Result.retry()
        }
    }

    private suspend fun notifyNew(
        context: Context,
        repo: EdgeRepository,
        appKeys: List<String>,
    ) {
        if (appKeys.isEmpty()) return
        // P1-19: the toggle is honoured here, not just persisted.
        val enabled = SettingsStore(context).settings.first().notificationsEnabled
        if (!enabled) return
        val dao = repo.dao
        for (appKey in appKeys.take(5)) {
            val e = dao.byAppKey(appKey) ?: continue
            Notifications.showNewArticle(
                context,
                articleId = appKey,
                title = e.title,
                text = e.summary,
            )
        }
        if (appKeys.size > 5) {
            Notifications.showNewArticlesSummary(context, appKeys.size)
        }
    }

    companion object {
        const val UNIQUE_NAME = "eco_edge_sync"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (EdgeConfig.BASE_URL.isBlank()) {
                // NotConfigured must not schedule pointless periodic
                // work (P0-07): cancel any previously scheduled run.
                manager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
