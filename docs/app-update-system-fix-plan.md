# ECO JOURNAL app — fundamental fix plan: news/article update system loads nothing

Status: PLAN + FIX (v4.0.1). Ordered by Yasin 2026-10-26 ~00:50 Tehran:
the app's news/article update system "loads nothing, very badly";
fix it fundamentally and systemically so no other challenge is
introduced, and let this fix itself be the end-to-end test of the
V4-I remote-update system (i.e. the fix must reach the phone through
the in-app update path, not only as a chat attachment).

## 0. What was verified BEFORE any fix (so the plan does not guess)

- Live Pages edge serves `manifest.json` version 3, generated
  2026-10-25T21:10 UTC, articles section count 249, items carrying
  `file: articles/<hash>.json` (checked live, 2026-10-26).
- `EdgeConfig.BASE_URL` is `https://syfazel.github.io/eco-journal-app/`
  (`EdgeRepository.kt`, `EdgeConfig` object) — the same root the live
  manifest is served from. The edge side serves data; the break is in
  the app chain or in how the phone reaches it.
- The v4 APK (4.0.0/4, shared signature) contains the bundled seed:
  256 `assets/seed/` entries, 249 article files, `seed/index.json`
  with 281 entries (verified by unzipping the built APK).
- The seed parser is compatible with the seed files:
  `ArticleParsers.entityFromArticleJson` reads exactly the fields
  the bundled article files carry (`id`, `app_key`, `title`,
  `summary`, `body_blocks`, `points`, `category`, `category_fa`,
  `source`, `published_at`, `content_hash`, `status` …).
- The manifest parser is compatible with the live manifest:
  version == 3, `sections` keyed by `SyncSections.ALL`, items with
  `id`/`app_key`/`content_hash`/`file`/`story_id`.
- Room is version 7 with migrations 1→2→…→7 registered
  (`DatabaseProvider.kt`); the feed query only filters
  `status != 'retracted'` (`AppDatabase.kt` `allFlow`).
- Ruled OUT as the blank-feed cause (with evidence, not feeling):
  edge serving nothing, APK lacking content, parser/schema mismatch,
  a missing migration, the feed query filtering everything, and the
  V4-I update check blocking the feed (it runs in a coroutine,
  never gates composition; `AppUpdateRepository.check` catches
  fetch failures into fixed codes). The invite gate is also not the
  blank-feed cause: a locked gate shows the code-entry screen, not
  an empty feed — but the phone script still checks it, because a
  fresh install that never re-entered pilot-01 looks similar from
  the outside.

Conclusion: the blank feed is produced by silent-failure paths in
the app chain, not by missing data. Every fix below removes a
silent path; none invents content.

## 1. Failure modes (each with file:line evidence)

### F1 — A seed exception strands the feed on a permanent spinner (or crashes startup)
- `FeedScreen.kt` `LaunchedEffect(Unit) { BundledSeed.ensureSeed(context); seeding = false }`:
  no try/catch. If `ensureSeed` throws (any Room/asset error), the
  coroutine dies, `seeding` stays `true` forever, and the branch
  `seeding && all.isEmpty()` shows `CircularProgressIndicator`
  forever — literally "nothing loads", with no error and no retry.
- `EcoJournalApp.kt` launches the same seed in a fire-and-forget
  `CoroutineScope(Dispatchers.IO).launch` with no try/catch: an
  uncaught exception there crashes the app on startup.
- The seed's own failure reporting is weak: a failed seed writes
  `seed_missing_count` but Diagnostics shows only `seed_version`,
  so a phone report cannot distinguish "seed empty" from "seed failed".

### F2 — No foreground sync on launch; the worker is the only automatic path
- `FeedScreen.kt` never calls `repo.sync()` on launch. The only
  automatic sync is `SyncWorker` (15 min, `NetworkType.CONNECTED`),
  scheduled once in `EcoJournalApp.onCreate`.
- A periodic worker can be deferred indefinitely by Doze, Samsung
  battery optimisation, force-stop, or a killed process. Then the
  feed sits at whatever the seed left — and if the seed failed
  (F1), at nothing — forever, with no user-visible reason.
- The manual button exists, but see F3.

### F3 — The manual sync button is NOT a force-refresh
- `FeedScreen.kt` `runSync` calls `repo.sync()` with
  `forceRefresh = false`. When section hashes report up-to-date,
  the tap does nothing visible — even if the user is staring at a
  blank feed. To the user the button is dead, and the honest
  explanation (hashes match) is nowhere on the feed.
- Diagnostics already has a per-section force-refresh
  (`syncOnly(..., forceRefresh = true)`); the main button does not.

### F4 — Failures are under-mapped and the empty state has no way forward
- `FeedScreen.kt` maps `Failed` codes `network`, `http_404/403/other`,
  `bad_index` — but `bad_manifest` (a real `SyncResult.Failed` code
  from `EdgeRepository.sync`) falls through to the generic string,
  and per-section `partial`/`failed` states never reach the feed.
- `CompactSyncRow` (`SyncCompact.kt`) defaults its status text to
  `sync_compact_up_to_date` ("به‌روز است") whenever `syncState` is
  blank — i.e. on a fresh launch where NO sync has ever run, the row
  can read "up to date" while the feed is empty. The last-sync line
  is honest ("never succeeded"), but the status line is not.
- The empty state is a bare `feed_empty` text: no error code, no
  retry, no Diagnostics link. A failed worker sync leaves
  `syncFailed = false` in a fresh composition, so even the compact
  row's retry button is absent.

### F5 — The articles fetch is unbounded in time and shows no progress
- `EdgeRepository.syncV3` articles section fetches up to 249 files
  in sequential chunks of 4, each `HttpURLConnection` with 15 s
  connect / 30 s read timeouts, one attempt per file, no overall
  deadline, no progress signal. Worst case is tens of minutes of
  "در حال همگام‌سازی…" with no count — indistinguishable from a
  hung app. One transient network blip per file = that file missing
  until the next sync pass.
- `SyncWorker` retries the whole pass on failure, but a pass that
  hangs inside the articles loop is not a failure — it is a stall.

### F6 — Manifest-fetch failure falls back to legacy and misreports
- `EdgeRepository.sync` fetches `manifest.json`; on ANY non-OK
  outcome it calls `syncLegacy()`, which fetches `index.json`.
  When the manifest is unreachable because the NETWORK is down, the
  legacy fetch fails the same way and the reported code is the
  index fetch's code (e.g. `http_404` if the index ever goes away),
  hiding the real failure. The legacy path also does not update
  per-section states, so Diagnostics goes stale while the feed
  reports a failure from a different file.
- Correct split: network error on the manifest → `Failed("network")`
  directly (a legacy attempt cannot succeed without the network);
  manifest 404 → legacy fallback (old-deploy compatibility);
  other HTTP → `Failed("http_other")`; unparsable manifest →
  `bad_manifest` (already the code; it only lacked a UI mapping).

### F7 — A stored section hash can mask missing rows
- `DeltaPlanner.isUpToDate` trusts the stored section hash alone.
  If a section hash is recorded while its rows are absent
  (partial damage, an interrupted transaction surface, a future
  bug), every later sync skips the section forever and the feed
  stays empty with an honest-looking "up to date".
- Guard (cheap, no behaviour change for healthy DBs): when the hash
  matches but the local count for that section is 0 while the
  manifest count is > 0, ignore the hash and re-plan the section.

### F8 — Reachability from the phone is unproven and untestable in-app
- The app uses raw `HttpURLConnection` with no proxy support. If
  the phone's network cannot reach `syfazel.github.io` (filtering,
  DNS, captive portal), every fetch fails with `network` — but the
  user has no in-app way to tell "edge unreachable" from "app
  broken". The fix cannot add a proxy, but it must surface the
  exact code and give the phone script a control test (open the
  edge URL in a browser).

## 2. Fixes (implemented in v4.0.1; no schema change)

All fixes use existing tables; new state travels through the
key/value `meta` table only, so NO Room migration is needed and
no data is ever cleared.

| # | Fix | Files |
|---|-----|-------|
| X1 | Seed calls wrapped in try/catch (FeedScreen effect + Application launch); `seeding` always resolves via `finally`; a seed failure records `seed_error` in meta and shows the honest empty state with retry, never a permanent spinner or a startup crash. | `FeedScreen.kt`, `EcoJournalApp.kt` |
| X2 | Foreground auto-sync on first empty feed: after the seed, if the article table is still empty, run one full force sync automatically (once per feed lifetime, guarded by state — not on every recomposition). Non-empty feeds are untouched: periodic cadence unchanged. | `FeedScreen.kt` |
| X3 | Manual sync button AND retry run `repo.sync(forceRefresh = true)`: every section hash is re-checked on a tap, while per-item hash comparison still skips unchanged bodies (cheap, no re-download storm). | `FeedScreen.kt` |
| X4 | Honest status defaults + full code mapping: compact-row fallback becomes a new neutral string (`sync_compact_idle`, "همگام‌سازی انجام نشده") instead of "up to date"; `bad_manifest` gets its own feed string; the empty state shows the last error code + article count + retry + Diagnostics link. | `SyncCompact.kt`, `FeedScreen.kt`, `strings.xml` |
| X5 | Bounded, retried, visible articles fetch: per-file retry once on network error only (404s are not retried); a 240 s deadline on the articles loop via `withTimeoutOrNull` — on timeout the remaining items are marked missing (PARTIAL, hash not stored) instead of hanging; a `syncProgress` StateFlow (fetched/total) is shown in the sync line while syncing. | `EdgeRepository.kt`, `FeedScreen.kt`, `strings.xml` |
| X6 | Manifest-failure split (see F6): network → `Failed("network")` directly; 404 → legacy fallback; other HTTP → `http_other`; unparsable → `bad_manifest`. | `EdgeRepository.kt` |
| X7 | Self-healing hash guard for articles, shorts, market: hash match + local count 0 + manifest count > 0 → re-plan the section. | `EdgeRepository.kt` |
| X8 | Diagnostics gains the seed missing count (`seed_missing_count`) so a phone report can distinguish seed-empty from seed-failed; the phone script's control test (open the edge URL in a browser) separates "edge unreachable" from "app broken". | `DiagnosticsScreen.kt`, `strings.xml` |

## 3. What must NEVER regress (checked by the verifier + the phone script)

- Night law (held items, 08:00 release, pin expiry), bar rule (RED
  typed / YELLOW energy, label + bar, never a separate tab),
  invite gate + revocation re-check (only a successfully fetched
  list can re-lock), delegated editorial gates, per-section hash
  discipline (hash stored only on complete section success),
  tombstones (retracted rows keep bookmarks), no invented content,
  no FCM, no Play, no release signing, no special-issues or voices
  change, no cron change, no live-channel change, periodic sync
  cadence still 15 min (only the manual button and the first-empty
  foreground pass are force-refreshes).

## 4. Regression checks (lab verifier `edge/verify_app_sync_fix.py`)

- Static: brace/paren balance on every changed Kotlin file;
  `strings.xml` parses, no duplicates, all new strings present;
  no `stringResource` inside any `.semantics {}` block.
- Behavioural (static): try/catch around both seed call sites;
  `forceRefresh = true` on the manual runSync path; auto-sync
  guard present; `withTimeoutOrNull` on the articles loop;
  per-file retry on network error only; idle fallback string (not
  up-to-date) in `SyncCompact.kt`; `bad_manifest` mapped in
  `FeedScreen.kt`; manifest network-error short-circuit in
  `EdgeRepository.sync`; hash guard (count 0 + manifest count > 0)
  in `syncV3`; seed-missing line in Diagnostics.
- Fixture round-trip (real data, no device): parse the live
  `edge/out/manifest.json` with a Python re-implementation of
  `ManifestParser` + `DeltaPlanner` and the bundled seed index:
  manifest parses as v3; articles section = 249 items with files;
  delta on an empty DB = 249 to-fetch; delta on the seeded DB
  (hashes from the seed index) = 0 to-fetch for articles. This
  proves the parser/delta logic is sound on real data; it does not
  prove on-device network reachability — that is the phone script's
  job.

## 5. Release as the remote-update test (v4.0.1 / code 5)

- `versionName 4.0.1`, `versionCode 5` (monotonic, never reused).
- Push (rebase, never force-push) → cloud build green (max two fix
  rounds; local Gradle builds are impossible on this VM and are not
  attempted) → APK to `build-output/app-debug-v4.0.1.apk`, verified
  with aapt (package, 4.0.1/5, minSdk 26, targetSdk 34, label) and
  apksigner (shared cert `45220b15…`).
- The fix APK is hosted at a REAL `apk_url`: force-added to Pages
  as `edge/out/app-debug-v4.0.1.apk` (no public GitHub release —
  distribution stays direct via the version manifest).
- `version.json` regenerated with `version_manifest.py generate`
  + `verify` against the REAL fix APK: it MUST name 4.0.1/5 and the
  real SHA-256 or publishing stops. Edge published to Pages; live
  verification: `version.json` names 4.0.1/5 + real hash, and the
  `apk_url` serves the APK with matching size/hash.
- Phone test (the actual remote-update test): on the v4 phone
  (code 4), the update banner MUST appear (5 > 4) → download with
  progress → hash verification → one-tap install → feed MUST load
  with counts. If the feed is still blank, the phone script's
  Diagnostics lines (article count, seed version, seed missing
  count, last error code, per-section states) are the evidence —
  "nothing loads" is no longer an acceptable report.
