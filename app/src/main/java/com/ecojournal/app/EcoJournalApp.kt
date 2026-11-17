package com.ecojournal.app

import android.app.Application
import com.ecojournal.app.data.AppUpdateWorker
import com.ecojournal.app.data.BundledSeed
import com.ecojournal.app.data.SyncWorker
import com.ecojournal.app.data.YemenSyncWorker
import com.ecojournal.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EcoJournalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // v5.2 Yemen-only mode (audit §4.5 option A): the 15-minute
        // periodic edge sync is gated off — SyncWorker.schedule
        // cancels it (all its sections are locked, and it must
        // never sync Yemen either, which would break the 24-hour
        // cadence). Yemen syncs on its own 24-hour worker below,
        // plus the launch one-time it enqueues.
        SyncWorker.schedule(this)
        // v5.2: 24-hour periodic Yemen sync (NOT content sync —
        // the only content section left, on its own cadence).
        YemenSyncWorker.schedule(this)
        // v4-I: 24-hour periodic app-binary check (NOT content
        // sync — a binary release is never on a 15-minute
        // cadence). The worker never downloads anything itself.
        AppUpdateWorker.schedule(this)
        // Offline-first seed (M2): resumable and verifiable. It
        // seeds only what is missing, writes its success hash only
        // on a complete transaction, and is serialised with the
        // feed's own seed call by an internal mutex, so the two
        // launch paths can no longer race. Startup must never crash
        // because a bundled file is unreadable.
        // v4.0.1 (fix plan X1): an uncaught exception in this
        // fire-and-forget launch used to crash startup. The seed
        // is retried from the feed anyway; startup must survive it.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BundledSeed.ensureSeed(this@EcoJournalApp)
            } catch (_: Exception) {
            }
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
