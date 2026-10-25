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
        // Offline-first seed (M2): resumable and verifiable. It
        // seeds only what is missing, writes its success hash only
        // on a complete transaction, and is serialised with the
        // feed's own seed call by an internal mutex, so the two
        // launch paths can no longer race. Startup must never crash
        // because a bundled file is unreadable.
        CoroutineScope(Dispatchers.IO).launch {
            BundledSeed.ensureSeed(this@EcoJournalApp)
        }
        // M5 decision (docs/vnext-decisions.md §3): NO Firebase/FCM
        // for the vNext pilot — no project, no google-services.json,
        // no topic subscription. Polling (SyncWorker, 15 min) plus
        // local notifications is the only notification path. The
        // FCM service stays dormant scaffolding; it is revisited
        // only if push latency is proven a pilot blocker, as a new
        // decision — never assumed here.
    }
}
