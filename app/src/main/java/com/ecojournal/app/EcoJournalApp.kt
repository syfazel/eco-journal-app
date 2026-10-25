package com.ecojournal.app

import android.app.Application
import com.ecojournal.app.data.BundledSeed
import com.ecojournal.app.data.SyncWorker
import com.ecojournal.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EcoJournalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // 15-minute periodic edge sync (WorkManager minimum).
        SyncWorker.schedule(this)
        // Offline-first seed (setup task): first launch populates the
        // cache from the bundled snapshot in assets/seed/ (a verbatim
        // copy of edge/out/). Runs only when the cache is empty and
        // never overwrites; the app therefore works with zero network
        // and before any GitHub Pages URL exists. Failures are
        // swallowed inside seedIfEmpty — startup must never crash
        // because a bundled file is unreadable.
        CoroutineScope(Dispatchers.IO).launch {
            BundledSeed.seedIfEmpty(this@EcoJournalApp)
        }
        // TODO (Firebase, Yasin): after google-services.json lands,
        // subscribe to the "articles" topic here:
        //   FirebaseMessaging.getInstance().subscribeToTopic("articles")
        // It is deliberately NOT called until then: with no project
        // configured it would only fail, and must never be faked.
    }
}
