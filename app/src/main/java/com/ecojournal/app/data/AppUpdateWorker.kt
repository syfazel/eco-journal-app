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
 * v4-I periodic update check. This is NOT the content sync:
 * SyncWorker (15 min) refreshes articles; this worker (24 h)
 * only asks "is there a newer app binary?" — checking for a
 * new binary every 15 minutes would be pure waste, and a
 * binary release is never urgent on a 15-minute cadence.
 *
 * Behaviour: run the same check the launch path runs. When
 * it finds an offered update that the user has not dismissed,
 * and notifications are ON, post ONE local notification that
 * opens the app (the Settings update section is where the
 * download/install happens). The worker never downloads an
 * APK by itself — a multi-megabyte download must never start
 * in the background without the user pressing Download.
 * A failed check retries with WorkManager backoff; a
 * NoInfo/UpToDate result succeeds silently.
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = AppUpdateRepository(applicationContext)
        return when (val r = repo.check()) {
            is CheckResult.Available -> {
                val settings = SettingsStore(applicationContext).settings.first()
                if (settings.notificationsEnabled) {
                    Notifications.showAppUpdate(
                        applicationContext,
                        versionName = r.manifest.versionName,
                        mandatory = r.manifest.mandatory,
                    )
                }
                Result.success()
            }
            is CheckResult.NoInfo -> Result.success()
            is CheckResult.UpToDate -> Result.success()
            is CheckResult.Failed -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "eco_app_update_check"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val req = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
