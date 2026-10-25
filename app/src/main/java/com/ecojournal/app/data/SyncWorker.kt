package com.ecojournal.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ecojournal.app.util.Notifications
import java.util.concurrent.TimeUnit

/**
 * Periodic edge sync. Android enforces a 15-minute minimum interval
 * for periodic work — that is why the package promises "at most 15
 * minutes late" for the polling fallback, not instant delivery.
 * Instant delivery is the FCM path (see fcm/EcoMessagingService.kt).
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
            Notifications.showNewArticle(
                context,
                articleId = appKeys.first(),
                title = "مقالات تازه",
                text = "${appKeys.size} مقالهٔ تازه منتشر شد.",
            )
        }
    }

    companion object {
        const val UNIQUE_NAME = "eco_edge_sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
