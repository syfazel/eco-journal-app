# ECO JOURNAL — Android App (Phase 1, LAB)

**Phase 2 update (2026-10-25):** the Telegram bridge and control
plane are now BUILT in `bridge/` — `channel_reader.py`
(channel→app staging), `publish_worker.py` (gated write path,
dry-run default), `control.py` (queue + invites CLI engine) and
`bridge_lib.py` (shared machine/validator/lock). Verified: 19/19
Phase 2 tests + 13/13 dry-run harness cases, `live_sends` 0,
production DBs untouched. The bridge remains dry-run-only until
Yasin supplies a test channel id and an explicit reopen order for
the live channel. See `docs/telegram-bridge-spec.md` (Phase 2 notes
at the top) for the exact gates.

**Lab only.** This project lives under `goals/eco-journal/app/` and is
deliberately separate from the live platform. It never touches
`econ-news-blog/` or `yasineco_bot/` except read-only, never posts to
the live channel, and never starts or re-enables any live cron or
service. The live ECO JOURNAL platform is frozen until Yasin says
"open" — this app does not change that.

## What Phase 1 is

A single-module, single-activity Kotlin + Jetpack Compose app,
offline-first, Persian RTL, Vazirmatn font, with:

- **Feed** — cached articles, newest first (sync order = index order).
- **Article reader** — verbatim body, font-size slider (0.85–1.3),
  light / dark / sepia themes, bookmark toggle, "view original source"
  (opens the source URL in the user's browser, verbatim).
- **Categories** — from the edge data itself.
- **Offline search** — Room full-text-ish search over cached titles
  and summaries. No network needed once cached.
- **Bookmarks** — local only.
- **Market board** — latest snapshot + active formulas from
  `edge/out/market.json`, with its snapshot date shown (stale data is
  never presented as fresh).
- **Settings** — font scale, theme, notifications, invite state
  (invite codes are never hardcoded in the app).
- **Sync** — WorkManager every 15 minutes (the Android minimum) +
  manual sync button; fetches `index.json`, checks its hash, fetches
  only new articles, shows a local notification for ≤ 5 new articles
  (aggregated above that).
- **FCM scaffolding** — data-message contract (`article_id` → fetch
  that article). **No keys:** until Yasin supplies a Firebase project,
  `google-services.json` and the `google-services` Gradle plugin are
  TODOs, and the app must not be described as push-enabled.

## What Phase 1 is NOT

- No APK has been built on this machine (see below).
- No FCM with real keys, no invite-code server check, no review
  queue UI — the bridge code itself landed in Phase 2 (`bridge/`),
  but FCM keys and the dashboard UI are still outstanding (see
  "Still needed from Yasin" below).
- No invented article text. Seed content comes only from the real
  databases (see `edge/`).

## Project layout

```
app/                          Android project (this folder)
  settings.gradle.kts         single module ":app"
  build.gradle.kts            AGP 8.5.2, Kotlin 2.0.21
  gradle.properties
  app/build.gradle.kts        compileSdk 34, minSdk 26, targetSdk 34
  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/ecojournal/app/
    MainActivity.kt           NavHost, bottom bar, RTL, notification deep link
    EcoJournalApp.kt          Application: channel + WorkManager schedule
    data/
      Article.kt              network model (Article, MarketSnapshot, MarketFormula)
      AppDatabase.kt          Room: articles, bookmarks, meta
      SettingsStore.kt        DataStore: font scale, theme, notifications, invite flag
      EdgeRepository.kt       edge fetch + hash check + Room upsert
      SyncWorker.kt           15-min periodic worker + local notifications
    util/Notifications.kt     notification channel + article deep link
    fcm/EcoMessagingService.kt  FCM scaffold (TODO: project + google-services.json)
    ui/theme/Theme.kt         Material3 + Vazirmatn
    ui/components/ArticleCard.kt
    ui/screens/               Feed, Article, Categories(+Bookmarks),
                              Market, Settings
  app/src/main/res/
    values/strings.xml, values/themes.xml
    font/vazirmatn_regular.ttf, vazirmatn_bold.ttf  (OFL, v33.003)
    mipmap-*/ic_launcher*.png  PLACEHOLDER icons — replace before any
                              public-facing release (see below)
edge/
  generate_edge.py            stdlib + sqlite3 only, read-only (mode=ro)
  out/                        generated seed JSON (do not hand-edit)
    index.json                versioned, hashed, 281 entries
    articles/*.json           249 full articles
    short_cards.json          32 short cards («گزیده»)
    market.json               164 snapshots + 4 active formulas
    channel_refs.json         23 channel references (message_id only)
    GENERATION_REPORT.json    counts + exclusions, auditable
docs/
  control-plane-spec.md       private Muse dashboard spec
  telegram-bridge-spec.md     channel↔app bridge spec (gated, dry-run default)
  OFL-vazirmatn.txt           Vazirmatn licence
```

## Seed counts (verified, 2026-10-25)

| Output | Count |
| --- | --- |
| index.json entries | 281 |
| full articles (`articles/*.json`) | 249 |
| short cards (`short_cards.json`) | 32 |
| market snapshots (`market.json`) | 164 (2026-04-15 → 2026-10-24) |
| active formulas | 4 |
| channel references | 23 (message_id only) |
| excluded: pending extracts | 2,560 |
| excluded: rejected extracts | 277 |
| excluded: approved/posted without body | 84 |
| excluded: rejected drafts | 52 |
| excluded: channel posts without body text | 23 |

Regenerate any time (read-only on production, writes only to
`edge/out/`):

```bash
python3 edge/generate_edge.py
```

The generator deletes stale `out/articles/*.json` before writing, so
re-runs are exactly reproducible. Production database mtimes were
verified unchanged after generation.

## Building the APK — HONESTY NOTE

**A Gradle build was NOT run on this machine.** This VM has no JDK
and no Android SDK, so no APK exists from Phase 1. What was verified
here is structural only: full file tree, Gradle files, Kotlin sources
present, JSON outputs valid, no tokens/keys in the tree, production
untouched. The first real build must happen on one of the two routes
below, and its success is unverified until then.

### Route A — Android Studio (Yasin's machine)

1. Open this `app/` folder in Android Studio (it will download the
   Gradle wrapper + AGP 8.5.2 + SDK 34 on first sync; internet
   required once).
2. Let Gradle sync finish. If it asks for a JDK, use the bundled one
   (Android Studio → Settings → Build Tools → Gradle → Gradle JDK).
3. Run → Run 'app' on a device/emulator, or Build → Build App
   Bundle(s) / APK(s) → Build APK(s) for a debug APK.
4. **No Gradle wrapper is committed** (`gradlew` deliberately not
   included, because no build runs here). Android Studio generates
   the wrapper on import, or run `gradle wrapper` once with a local
   Gradle install.

### Route B — GitHub Actions cloud build (no local SDK needed)

1. Create the GitHub repo for the edge + app (Yasin's input — see
   below) and push the **contents** of this `app/` folder to the repo
   root (so `settings.gradle.kts` is at the root).
2. The workflow `.github/workflows/android.yml` (at the repo root)
   runs on push: checks out, sets up JDK 17, runs
   `gradle assembleDebug` via `gradle/actions/setup-gradle`.
3. Download the debug APK from the workflow's artifacts.
4. No secrets are needed for a debug build. **Never add a Firebase
   key, bot token, or signing key to the repo or the workflow.**
   Release signing is a Phase 2 decision (Yasin's keystore, kept
   outside the repo).

### After the first successful build

- Install the debug APK on a test device, point the app at the edge
  URL (see below), confirm the feed loads, search works offline,
  and the reader renders RTL Persian with the font slider.
- Report back: build log + device result. Until that happens, the
  app is "skeleton + verified seed", not "tested".

## Wiring the edge URL (required before the app shows content)

`data/EdgeRepository.kt` → `EdgeConfig.BASE_URL` is intentionally
empty. Set it to the published edge base URL, e.g.:

```kotlin
object EdgeConfig {
    const val BASE_URL = "https://<user>.github.io/<repo>"
}
```

The edge files (`edge/out/*`) are published to that base (GitHub
Pages, free). Until then the app shows only its empty state and the
sync button reports "edge not configured" — it never fabricates
content to fill the feed.

## Still needed from Yasin (exact inputs)

1. **Firebase project** (free tier) + `google-services.json` placed
   in `app/` + the `google-services` Gradle plugin applied — only
   then is FCM real. No key is faked in the meantime.
2. **GitHub repo** for the edge (Pages URL becomes `BASE_URL`) and
   for the cloud build (Route B).
3. **A private test Telegram channel** (Yasin creates, bot added as
   admin) for bridge dry-runs — before any live-channel work, which
   additionally needs his explicit reopen order.
4. **Final launcher icon** — the current `mipmap-*` icons are valid
   PNG placeholders (navy + gold block), not the ECO JOURNAL mark.

## Phase 3 update (2026-10-25): local build BLOCKED, wrapper done, pilot prepped

- **No APK was built locally.** Root cause, verified: JVM
  loopback TCP is intercepted in this sandbox (Java→loopback
  gets a policy denial / reset, while Python→loopback works),
  and the Gradle daemon protocol requires JVM→JVM loopback TCP.
  Every Gradle invocation fails with "Could not receive a
  message from the daemon" after the daemon starts cleanly
  (heap size and `--no-daemon`/`--foreground` do not change it).
  Full evidence + minimal reproductions:
  `docs/apk-build-phase3.md`. The remaining route to the first
  APK is the GitHub Actions cloud build (Route B above) after
  the one-time repo push — no settings change was requested.
- **Toolchain + wrapper ARE done (lab only):** JDK 17.0.21,
  SDK 34 + build-tools 34.0.0 + platform-tools under
  `.tools/` (gitignored), and a verified `gradlew` +
  `gradle/wrapper/*` (bootstrap tested: `./gradlew --version`
  prints Gradle 8.7). `local.properties` points at the lab SDK
  (gitignored).
- **Pilot prep done, pilot NOT started:** 10 invite codes in
  `docs/pilot-invite-codes-private.md` (gitignored, hashes only
  in the control plane) and `docs/pilot-test-plan.md`. No code
  has left the lab and no reader has been contacted.

## Rules that bind later phases

- Never write to production DBs; never post to the live channel
  while frozen; bot posts to channel only, never DMs (law 14).
- Never invent article text; missing body → short card or exclusion.
- No Play Store, no paid service, zero-cost stack only.
- Bot token: environment variable name only, never a value, never
  in the app or repo.
