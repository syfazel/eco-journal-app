package com.ecojournal.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ecojournal.app.MainActivity

/** Local notifications only. No data leaves the device from here. */
object Notifications {
    private const val CHANNEL_ID = "eco_articles"
    private const val EXTRA_ARTICLE_ID = "article_id"

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
     * articleId is an appKey (M1). Callers pass the stable surrogate,
     * never the verbatim source id, so MainActivity can route it
     * without encoding a slash, question mark or non-ASCII text.
     */
    fun showNewArticle(
        context: Context,
        articleId: String,
        title: String,
        text: String,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ARTICLE_ID, articleId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            articleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(articleId.hashCode(), notification)
    }

    fun openArticleId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ARTICLE_ID)
}
