package com.ecojournal.app.fcm

import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.util.Notifications
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM SCAFFOLDING — NOT YET WIRED TO A REAL PROJECT.
 *
 * TODO (Yasin, before real push works):
 *   1. Create a Firebase project (free Spark plan) and an Android app
 *      entry for applicationId com.ecojournal.app.
 *   2. Download google-services.json and place it in app/ (module dir).
 *      NEVER commit it to a public repo, and NEVER paste any key here.
 *   3. Apply the google-services Gradle plugin in app/build.gradle.kts.
 *   4. Subscribe the app to the "articles" topic (one line, in
 *      EcoJournalApp, currently commented out for the same reason).
 *
 * Until then this service compiles against firebase-messaging but has
 * no project to register against, so onMessageReceived simply never
 * fires for real traffic. The WorkManager polling path is the only
 * notification path that works without Firebase, and it is honest
 * about its 15-minute granularity.
 *
 * Data-message contract (from the publisher, see docs/telegram-bridge-
 * spec.md and the v2 package): data["article_id"] is the edge article
 * id; the service fetches that exact article and notifies locally.
 * No article body is trusted from the push payload itself.
 */
class EcoMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val articleId = message.data["article_id"] ?: return
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val repo = EdgeRepository(applicationContext)
            if (repo.fetchOne(articleId)) {
                val entity = repo.dao.byId(articleId) ?: return@launch
                Notifications.showNewArticle(
                    applicationContext,
                    articleId = articleId,
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
