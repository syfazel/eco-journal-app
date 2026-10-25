package com.ecojournal.app.fcm

import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.util.Notifications
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * FCM SCAFFOLDING — DORMANT BY DECISION, not by omission.
 *
 * vNext decision (docs/vnext-decisions.md §3, M5): NO Firebase
 * project, NO google-services.json, NO FCM activation for the
 * pilot. This service compiles against firebase-messaging but has
 * no project to register against, so onMessageReceived never fires
 * for real traffic. The WorkManager polling path is the only
 * notification path, and it is honest about its 15-minute
 * granularity. If push is ever enabled later (new decision), it
 * needs: a Firebase project, google-services.json (never committed
 * to a public repo, never pasted in code), the google-services
 * Gradle plugin, and a topic subscription in EcoJournalApp.
 *
 * Data-message contract (from the publisher, see docs/telegram-bridge-
 * spec.md and the v2 package): data["article_id"] is the edge article
 * id; the service fetches that exact article and notifies locally.
 * No article body is trusted from the push payload itself.
 *
 * M1: the notification carries the article's appKey, not the
 * verbatim id. fetchOne and byAppKey both accept an appKey, with a
 * fallback to the verbatim id for older payloads.
 */
class EcoMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val articleId = message.data["article_id"] ?: return
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            // M5 (P1-19): the toggle is honoured on the FCM path
            // too, so a dormant-path notification can never bypass
            // the user's setting if push is ever enabled later.
            // first() takes one emission and returns; a bare
            // collect() on a DataStore flow would never return.
            val enabled = SettingsStore(applicationContext).settings
                .first().notificationsEnabled
            if (!enabled) return@launch
            val repo = EdgeRepository(applicationContext)
            if (repo.fetchOne(articleId)) {
                val entity = repo.dao.byAppKey(articleId)
                    ?: repo.dao.byId(articleId) ?: return@launch
                Notifications.showNewArticle(
                    applicationContext,
                    articleId = entity.appKey,
                    title = entity.title,
                    text = entity.summary,
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        // Stored locally only. There is no server to upload it to yet;
        // the control plane will define token handling in Phase 2.
        CoroutineScope(Dispatchers.IO).launch {
            SettingsStore(applicationContext).setFcmToken(token)
        }
    }
}
