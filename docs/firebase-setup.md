# Firebase setup — ECO JOURNAL app (FCM, free Spark plan)

## Status (25 Oct 2026, lab)

**PREPARED, not created.** There is no Firebase project yet, no
`google-services.json` anywhere in this repo (verified absent), and
no key has been invented or faked. That is deliberate: creating a
project requires the owner's logged-in Google account, and faking a
config file would only break the build later.

**The app already runs correctly without Firebase.** FCM is
optional at runtime by construction:

- `fcm/EcoMessagingService.kt` exists but registers against no
  project (there is no `google-services.json`, and the
  `google-services` Gradle plugin is NOT applied).
- The topic subscription in `EcoJournalApp.kt` is commented out
  until the config file lands.
- Notifications meanwhile come from the WorkManager 15-minute poll
  (already in the app) plus the bundled offline seed — no push
  needed for the pilot.

## The exact console checklist (owner, one session, all free)

1. Open https://console.firebase.google.com → **Add project**.
2. Project name: `eco-journal-app` (suggestion; any name works,
   it is not user-facing and not the package name).
3. **Google Analytics: OFF** (toggle off on the creation screen).
   Analytics is not needed and only adds a second consent surface.
4. Create the project. **No billing, no card**: Firebase projects
   start on the **Spark (free) plan** by default, and FCM itself is
   free with no per-message charge. Do NOT upgrade to Blaze for
   this app — 10 readers cannot hit any Spark limit that matters.
5. In the project: **Add app → Android**.
   - Android package name: `com.ecojournal.app` (must match
     `applicationId` in `app/build.gradle.kts` exactly).
   - App nickname: `ECO JOURNAL` (cosmetic).
   - No SHA-1 needed (no Google sign-in in this app).
6. Download `google-services.json` and place it in `app/`
   (the module directory, next to `app/build.gradle.kts`).
   **Never commit it** — it is already in `.gitignore`, and the
   repo must never be pushed with it present.
7. Apply the plugin — two lines, no Kotlin code change:
   - Root `build.gradle.kts`, inside `plugins { … }`:
     ```kotlin
     id("com.google.gms.google-services") version "4.4.2" apply false
     ```
   - `app/build.gradle.kts`, inside `plugins { … }`:
     ```kotlin
     id("com.google.gms.google-services")
     ```
8. In `EcoJournalApp.kt`, uncomment the one line:
   ```kotlin
   FirebaseMessaging.getInstance().subscribeToTopic("articles")
   ```
   (plus its import). That is the only code touch.
9. Build and install. FCM is live when the app subscribes to the
   `articles` topic on first run.

## How push will work once live (already coded, no further change)

- The publisher (Phase 2 control plane) sends an FCM **data
  message** with `data["article_id"]` = the edge article id.
- `EcoMessagingService` fetches that exact article from the edge
  and notifies locally. The article body is never trusted from the
  push payload itself.
- Requires the edge URL to be set (`EdgeConfig.BASE_URL`, see
  `edge/PUSH_README.md`) — push without an edge URL cannot fetch
  the article, by design.

## What to verify after setup (and what proves what)

- Build succeeds with the plugin + config file → wiring is right.
- App first-run subscribes to `articles` (check logcat for the
  subscription, or the stored token path) → registration is right.
- A test data message to the `articles` topic produces a local
  notification for the new article → end-to-end is right. (Test
  sends are a Phase 2 control-plane action, not a console hack:
  the console composer does not send raw data messages reliably.)
