package com.ecojournal.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ecojournal.app.MainActivity
import com.ecojournal.app.R

/**
 * Local notifications only. No data leaves the device from here.
 *
 * Phase 4 (v5.3): the only notification left is the app-update
 * notice from AppUpdateWorker. All content notifications (new
 * article, urgent, market alert, nightly, morning bundle,
 * deferred summary) are gone with the sections that produced
 * them, and the deep-link extras are gone with the routes they
 * opened. The small icon is the app launcher icon, not the
 * generic info icon.
 */
object Notifications {
    private const val CHANNEL_ID = "eco_articles"
    private const val APP_UPDATE_CHANNEL_ID = "eco_app_updates"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "مقالات تازه",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "اعلان انتشار مقالهٔ تازهٔ ECO JOURNAL" }
            )
        }
    }

    /**
     * v4-I: one local notification when the periodic check finds
     * a newer app binary. It opens the app (the Settings update
     * section is where download/install happens); it never
     * downloads anything itself. New channel id (Phase 4 rule:
     * new channels get new ids) at IMPORTANCE_DEFAULT, so an
     * app update never outranks urgent news.
     */
    fun showAppUpdate(context: Context, versionName: String, mandatory: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(APP_UPDATE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        APP_UPDATE_CHANNEL_ID,
                        "به‌روزرسانی برنامه",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "اعلان نسخهٔ تازهٔ برنامهٔ ECO JOURNAL" },
                )
            }
        }
        val id = "eco_app_update".hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (mandatory) {
            "نسخهٔ تازهٔ برنامه — به‌روزرسانی ضروری"
        } else {
            "نسخهٔ تازهٔ برنامه"
        }
        val notification = NotificationCompat.Builder(context, APP_UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("نسخهٔ $versionName آماده است؛ برای دانلود و نصب به تنظیمات بروید.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }
}
