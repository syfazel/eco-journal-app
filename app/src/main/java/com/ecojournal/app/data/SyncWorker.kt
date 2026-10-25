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
                if (r.newArticleIds.isNotEmpty()) {
                    val dao = repo.dao
                    for (id in r.newArticleIds.take(5)) {
                        val e = dao.byId(id) ?: continue
                        Notifications.showNewArticle(
                            applicationContext,
                            articleId = id,
                            title = e.title,
                            text = e.summary,
                        )
                    }
                    if (r.newArticleIds.size > 5) {
                        Notifications.showNewArticle(
                            applicationContext,
                            articleId = r.newArticleIds.first(),
                            title = "مقالات تازه",
                            text = "${r.newArticleIds.size} مقالهٔ تازه منتشر شد.",
                        )
                    }
                }
                Result.success()
            }
            is SyncResult.NotConfigured -> Result.success()
            is SyncResult.Failed -> Result.retry()
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
