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
    private const val BROADCAST_CHANNEL_ID = "eco_broadcast"

    /**
     * Phase 5 (v5.4): the broadcast channel. New id (Phase 4 rule:
     * new channels get new ids), IMPORTANCE_DEFAULT — a broadcast
     * never outranks an app update, and even a >= 85 night
     * broadcast arrives with a normal sound, never an alarm.
     */
    fun ensureBroadcastChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(BROADCAST_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BROADCAST_CHANNEL_ID,
                    "پیام‌رسانی",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "اعلان پیام‌های پخش (قانون ۱۵)" },
            )
        }
    }

    /**
     * Phase 5: one local notification for a broadcast message that
     * Law 15 says to notify NOW. Local only — it is fired from a
     * message already stored on this device, never by a server.
     * The body is NOT put in the notification text: a notification
     * can sit on a lock screen, and message content stays inside
     * the app (G-DIAG-2). The user taps to open the broadcast tab.
     */
    fun showBroadcast(context: Context, senderName: String, urgent: Boolean) {
        ensureBroadcastChannel(context)
        val id = "eco_broadcast".hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_tab", "broadcast")
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (urgent) "پیامِ فوری از $senderName" else "پیام تازه از $senderName"
        val notification = NotificationCompat.Builder(context, BROADCAST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("یک پیام پخش رسیده است؛ برای خواندن، تب پیام‌رسانی را باز کنید.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    /** Phase 5: one bundled notification for the night-held
     * (70-84) messages released after 08:00 Tehran. Count only,
     * never content. */
    fun showMorningBundle(context: Context, count: Int) {
        if (count <= 0) return
        ensureBroadcastChannel(context)
        val id = "eco_broadcast_morning".hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_tab", "broadcast")
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, BROADCAST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("بستهٔ صبح — پیام‌رسانی")
            .setContentText("$count پیامِ نگه‌داشته‌شدهٔ شب گذشته آماده است.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

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
