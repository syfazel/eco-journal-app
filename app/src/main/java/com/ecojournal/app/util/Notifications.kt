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
 * M5 (P1-23, P2-16): notification ids are namespaced, never a bare
 * appKey hashCode — article ids use the "eco_article:" prefix and
 * the summary uses a distinct constant, so a summary can never
 * overwrite the first article's notification, and two different
 * articles cannot merge through a bare hash collision path. The
 * small icon is the app launcher icon, not the generic info icon.
 * A summary notification opens the feed (no article extra), never
 * the first article.
 */
object Notifications {
    private const val CHANNEL_ID = "eco_articles"
    // Phase 2 channels (spec 5.4, 9.5). The urgent channel is used
    // ONLY for INSTANT items; the Morning Bundle notification uses
    // the DEFAULT channel (eco_articles), never the urgent one.
    private const val URGENT_CHANNEL_ID = "urgent"
    private const val EXTRA_ARTICLE_ID = "article_id"
    private const val EXTRA_CHANNEL_ID = "channel_id"
    private const val EXTRA_BUNDLE_ID = "bundle_id"

    private fun articleNotificationId(articleId: String): Int =
        ("eco_article:$articleId").hashCode()

    private fun summaryNotificationId(): Int = "eco_summary".hashCode()

    private fun urgentNotificationId(articleId: String): Int =
        ("eco_urgent:$articleId").hashCode()

    private fun bundleNotificationId(bundleId: String): Int =
        ("eco_bundle:$bundleId").hashCode()

    fun ensureUrgentChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(URGENT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    URGENT_CHANNEL_ID,
                    "فوری",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "اعلان خبرهای فوری ECO JOURNAL"
                    enableVibration(true)
                }
            )
        }
    }

    /**
     * Urgent notification (spec 5.4): one per INSTANT item, keyed
     * by appKey. Title = headline, body = first lines of body as
     * supplied by the EDGE FETCH — never from any push payload
     * (there is no push in Phase 2). Tap deep-links to the item
     * reader; a retracted item opens the feed with an honest
     * "no longer available" line (MainActivity resolves it).
     */
    fun showUrgent(
        context: Context,
        articleId: String,
        title: String,
        text: String,
    ) {
        ensureUrgentChannel(context)
        val id = urgentNotificationId(articleId)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CHANNEL_ID, articleId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = text.lines().firstOrNull { it.isNotBlank() }
            ?: "خبر فوری تازه‌ای در ECO JOURNAL منتشر شد."
        val notification = NotificationCompat.Builder(context, URGENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "خبر فوری" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { body }))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    /**
     * Morning Bundle notification (spec 9.5): exactly ONE per
     * bundle_id (dedup lives in EdgeRepository), default channel,
     * never before 08:00 Tehran (gated there too). Body carries a
     * count only — never a headline leak.
     */
    fun showMorningBundle(
        context: Context,
        bundleId: String,
        heldCount: Int,
    ) {
        ensureChannel(context)
        val id = bundleNotificationId(bundleId)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_BUNDLE_ID, bundleId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val countFa = heldCount.toString().toPersianDigits()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("بسته صبح آماده است")
            .setContentText("$countFa خبر از شب گذشته + جامانده‌های دیشب")
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
     * articleId is an appKey (M1). Callers pass the stable surrogate,
     * never the verbatim source id, so MainActivity can route it
     * without encoding a slash, question mark or non-ASCII text.
     * Blank title/text fall back to fixed strings, never a blank
     * notification (P2-16).
     */
    fun showNewArticle(
        context: Context,
        articleId: String,
        title: String,
        text: String,
    ) {
        ensureChannel(context)
        val id = articleNotificationId(articleId)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ARTICLE_ID, articleId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "مقالهٔ تازه" })
            .setContentText(text.ifBlank { "مقالهٔ تازه‌ای در ECO JOURNAL منتشر شد." })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text.ifBlank { "مقالهٔ تازه‌ای در ECO JOURNAL منتشر شد." })
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    /**
     * Summary for a batch of new articles. It opens the feed list
     * (plain MainActivity intent, no article extra) and uses its
     * own notification id, so it never overwrites an article
     * notification (P1-23).
     */
    fun showNewArticlesSummary(context: Context, count: Int) {
        if (count <= 0) return
        ensureChannel(context)
        val id = summaryNotificationId()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "$count مقالهٔ تازه منتشر شد."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("مقالات تازه")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    fun openArticleId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ARTICLE_ID)

    fun openChannelId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_CHANNEL_ID)

    fun openBundleId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_BUNDLE_ID)
}
