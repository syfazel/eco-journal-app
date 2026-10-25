# ECO JOURNAL app — Next Big Version Plan (vNext)

**Status: LAB ONLY — plan and audit, no code changed by this document.**
**Do NOT push this file to GitHub yet. Do NOT touch the live platform.**
Live ECO JOURNAL (space-4, @YASINECOBOT, @EcoJournalFa, `econ-news-blog/`,
`yasineco_bot/`) stays **FROZEN** until Yasin explicitly says to reopen it,
after the app is ready. This plan only prepares the app in
`~/workspace/goals/eco-journal/app/`. Nothing in it flips a live cron, bot,
channel post, or unfreeze switch.

**Baseline audited:** first APK `1.0.0-phase2` (cloud build 40210356400, green,
`com.ecojournal.app`, minSdk 26, targetSdk 34, 15,233,879 bytes), built from
commit `12300b6` on `syfazel/eco-journal-app`. Kotlin 2.0.21, AGP 8.5.2,
Compose BOM 2024.09.00, Room 2.6.1, WorkManager 2.9.0, DataStore 1.1.1.
Bundled seed verified by auditors: 249 full articles + 32 short cards = 281
index entries, 164 market snapshots, 4 formulas, 23 channel refs.
Seed ↔ edge JSON are byte-identical; 249/249 article files resolve; 281/281
index ids are unique. The breakage below is therefore **wiring, not seed
corruption** — except where the generator itself hard-codes data (shorts
category, channel_message_id null, entry-only hash).

**User report that triggered this plan (2026-10-25 ~19:52 Tehran):**
ran the APK, reports many bugs, many sections unbuilt, UI not correct,
section↔article connections broken, plus many small issues too long to list.
Order: audit *everything*, even tiny, then plan a much more precise next
big version, plus a Phase 0 for all infrastructure so everything reaches
each other and stays in sync, and — only when the app is updated and by a
later explicit order — lift the whole platform freeze together. That last
clause is **not** an immediate unfreeze order.

**How to read severities:** P0 = broken/unusable in the shipped APK.
P1 = wrong/missing, user-visible. P2 = polish/consistency/risk.
Type: `bug` = code wrong, `missing` = feature absent, `design` = wrong shape.

---

## 1. Ranked bug register (deduplicated from 3 read-only audits)

Auditors worked in parallel: UI/navigation, data/sync/seed, bridge/control/FCM.
Overlapping findings are merged here under one ID; the strongest file:line
evidence is kept. Full auditor reports are the evidence base for this file;
this register is the single list to work from. **No code has been fixed yet
in this audit pass.**

### 1.1 P0 — broken / unusable

| ID | Bug | Evidence | Type |
|---|---|---|---|
| P0-01 | **123/281 article taps can never open.** Ids containing `/` (123), `?` (5), `#` (1), space (3), non-ASCII (83) are put raw into a single-segment route. | `MainActivity.kt:99,116,128` `nav.navigate("article/$id")`, route `article/{id}` at `:102`; notification deep-link `:76` same pattern; zero `Uri.encode` hits in all Kotlin sources. Example id: `newsdesk:https://www.eghtesadonline.com/fa/news/2170155/…`. `ArticleScreen` then shows "مقاله یافت نشد". | bug |
| P0-02 | **First-run feed race: empty feed despite bundled seed, never reloads.** Seed is fire-and-forget; feed reads once. | `EcoJournalApp.kt:25` unstructured `launch { BundledSeed.seedIfEmpty(...) }`, no completion signal. `FeedScreen.kt:80` `LaunchedEffect(Unit) { loadAll() }`. `AppDatabase.kt:66-67` `all()` returns `List`, not `Flow`. Same once-only pattern in Bookmarks and Market (`MainActivity.kt:86-87`). | bug/design |
| P0-03 | **Partial seed is permanent.** Any non-empty DB disables seeding forever; hash recorded even on partial load. | `BundledSeed.kt:97` `if (dao.allIds().isNotEmpty()) return 0`; `:125` per-file failures `continue` silently; `:147-149` hash written even if `fresh` is short/empty. No resume, no repair, no seed-version check. | bug |
| P0-04 | **Sync writes index hash on partial sync; missing articles are never retried.** | `EdgeRepository.kt:142-149` per-file skips via `continue`, `:154-174` short-card failure swallowed, `:175-177` hash written anyway; next run `:122` `if (dao.indexHash() == hash) return Ok(emptyList())` — declares success with zero new articles. | bug |
| P0-05 | **Sync never updates an existing article; corrections never propagate; no deletes.** | `EdgeRepository.kt:132` only `if (id !in existing)` fetches. No per-article hash/etag/`lastModified`. Hash itself (`edge/generate_edge.py:285-286`) covers entry list only (id/title/summary/category/published_at/file), not bodies, short text, or market. Reordering entries changes hash but fetches nothing. No article DAO delete at all. | bug/design |
| P0-06 | **Feed order is lexicographic string sort over 4+ date formats — hero/sections chronologically wrong.** | `AppDatabase.kt:66,74,81` `ORDER BY publishedAt DESC` on raw `String`, stored verbatim, never normalised. Formats counted in 281 entries: ISO-8601 (71), RFC-1123 GMT (64), `Sun, 18 Oct 2026 … +0330` without GMT (146, incl. 2× EST). Measured: newest full articles rank 166–169/281, newest shorts 176–207/281, top-5 are all `Wed, 21 Oct`. Hero (`isHero = index == 0`) is therefore not the newest article. | bug |
| P0-07 | **Entire online layer dead in shipped APK: `BASE_URL` empty.** | `EdgeRepository.kt:11` TODO, `:21` `BASE_URL = ""`. `sync()` → `NotConfigured` (`:109-110`); `fetchMarket()` → `(null, [], 0)` (`:216-217`); `fetchOne()` → `false` (`:183-184`). Sync button always shows the TODO leak (see P1-13). `SyncWorker` still wakes every 15 min returning success for a no-op. | missing |
| P0-08 | **Market board always empty offline despite 164 bundled snapshots in the APK.** | No assets/seed fallback anywhere: `MainActivity.kt:86` fetches only via `repo.fetchMarket()`; `BundledSeed.kt` never opens `seed/market.json` (only `SEED_INDEX`, `SEED_SHORTS`). `MarketScreen` always takes `latest == null` branch. Latest bundled snapshot `2026-10-24T09:59:13+03:30` exists but is never read. | bug/missing |
| P0-09 | **Categories orphaned; `categories` route is a fake.** | `MainActivity.kt:50-54` TABS = feed/market/bookmarks/settings only. `CategoriesScreen` referenced only at its own definition (`CategoriesScreen.kt:30`). `composable("categories")` at `MainActivity.kt:114` calls `FeedScreen` again, not `CategoriesScreen`. Pilot step 6 (open Categories, filter) is untestable as written. | missing |
| P0-10 | **Channel→app chain dead end: staging never promoted to edge.** | `bridge/channel_reader.py:134` writes `bridge/staging/channel-<id>.json`; `edge/generate_edge.py` has zero references to `staging`; `bridge/control.py` has no promote command. Spec Direction-1 step 4 (item appears in `index.json` on edge publish) has no code path. Current staging files are replay fixtures only. | missing |
| P0-11 | **Live channel forwarding does not exist: `poll_loop` is a stub.** | `bridge/channel_reader.py:195-210` `poll_loop` raises `RuntimeError("…never executed")`; `main()` never calls it. No `getUpdates` HTTP call, no offset persistence, no webhook. Direction 1 works only by a human hand-feeding an updates JSON file. | missing |
| P0-12 | **Caption posts dropped as "media-only".** | `bridge/channel_reader.py:100-104` reads only `post.get("text")`; empty text → skipped. Photo-with-caption (the most common channel format) carries text in `caption`, not `text`. Text+photo posts stage text but silently drop the photo with no note. | bug |
| P0-13 | **Invite gate open by default in shipped APK; no gate at all.** | `MainActivity` `startDestination = "feed"` unconditional; no invite-entry screen in `app/src`. `SettingsStore.setInviteOk` never called (definition only, `SettingsStore.kt:62`, default false `:30`); `SettingsScreen.kt:76` only displays "not confirmed". Lab CLI `control.py invites verify` is disconnected from the app: no hash list published to edge, no app check-in. **Distribution consequence: as shipped, the APK is effectively public.** Pilot step 2 cannot pass. | missing/design |
| P0-14 | **No notification can ever fire in shipped APK — both paths dead.** | WorkManager path: P0-07 → `SyncWorker.kt:23` takes `NotConfigured -> Result.success()` and never notifies. FCM path: `EcoJournalApp.kt:29` topic subscription commented out, no `google-services.json`, no plugin, no token ever generated, so `EcoMessagingService.onMessageReceived` can never fire (app does not crash — FCM silently does nothing). Pilot "15-min poll" tests a no-op. | missing |
| P0-15 | **Invite-count mismatch, unresolved (no codes quoted here).** | Docs say 10 pilot readers/codes; lab `queue.db` `invites` table holds 20 rows (10 `unused` + 10 `revoked`). Either a second generation exists, pilot codes were revoked, or test runs polluted the table. Must be reconciled before any pilot — without ever printing codes. Codes are plain unsalted SHA-256 (`control.py:58`), so a leaked `queue.db` exposes hashes of a 60-bit code space to offline brute force. | design/data |

### 1.2 P1 — wrong / missing

**Feed / reader / search**
- P1-01 Shorts forced into «عمومی»: `short_cards.json` has no category field; generator and both parsers hard-code `category = "general"` (`BundledSeed.kt:74`, `EdgeRepository.kt:97`). «عمومی» = 45 full + 32 short = 77; shorts' real topics unrecoverable from shipped data. Type: bug/design.
- P1-02 21 headlines appear twice (42 rows): 19× full + `draft-N` short of same title, 1× two full articles. Ids distinct, so PK dedup cannot catch it; no title/content dedup anywhere. Only distinction is the «گزیده •» kicker. Type: design.
- P1-03 Bookmarks list goes stale after toggle: `CategoriesScreen.kt:91` `LaunchedEffect(Unit) { saved = repo.dao.bookmarked() }` once per composition; returning with `popUpTo("feed") { saveState = true }` + `restoreState = true` restores old composition, effect does not re-run. Toggle itself persists (`ArticleScreen.kt:119-126`). Type: bug.
- P1-04 Search is titles+summaries only, wildcard leakage, no debounce/clear/count: `AppDatabase.kt:78-83` (comment admits it), `%`/`_` passed straight into `LIKE`, `FeedScreen.kt:83-84` re-queries every keystroke, search branch replaces pager/tabs, no clear button, no result count, no IME action. Body/points/source/category unfindable. Type: bug/missing.
- P1-05 Reader drops structured content: renders only title/summary/bodyBlocks (`ArticleScreen.kt:106` loop). `points` present in 246/249 full articles, `why_matters` in 98/249 — parsed and stored, never shown. `EdgeRepository.kt:258` `entityToArticle()` (only place parsing `pointsJson` back) has zero callers; UI uses `ArticleEntity` directly. Type: missing.
- P1-06 Short-card reader duplicates its own text: generator truncates index summary at 280 chars hard cut; Kotlin parsers use 277 + "…" (`BundledSeed.kt:71`, `EdgeRepository.kt:89`); 24/32 shorts >280 chars, so stored summary differs from index summary and reader shows truncated summary (bold) then full text — first ~280 chars twice. Type: bug.
- P1-07 Reader "not found" + notification deep-link are dead ends: `ArticleScreen.kt:63-68` shows only text, no back/retry/sync action; `:57-58` never falls back to `repo.fetchOne(id)` (exists in `EdgeRepository.kt`). Combined with P0-01, slash-ID notification taps fail even earlier. Type: bug/missing.
- P1-08 No visible back affordance on article; bottom bar stays on reader: zero hits for `TopAppBar|BackHandler|navigateUp|popBackStack|IconButton` in UI code; `Scaffold(bottomBar)` unconditional, so reader shows 4 tabs with none selected (route `article` matches no tab). Only bookmark button is at the very bottom of the article, after the whole body. Type: design.
- P1-09 Feed per-page scroll position lost: each pager page is a `LazyColumn` with no section-keyed `rememberLazyListState` (`FeedScreen.kt:268`); pager keeps only `beyondViewportPageCount = 1` neighbours, so swiping two sections away resets scroll to top. Type: bug.
- P1-10 Feed section order unstable across syncs: sections built by first-appearance in `dao.all()` (publishedAt DESC); a new article can reorder tabs and change heroes while the user watches. No fixed category order, no tab/scroll preservation across `loadAll()`. Type: design.
- P1-11 Sync button double-tap → concurrent syncs; user-facing TODO leak: `FeedScreen` sync button has no disabled/loading guard, overlapping syncs race on `syncState`/`loadAll()`; `NotConfigured` message reads "…(TODO در EdgeConfig)…"; `Failed` surfaces raw exception message. Type: bug.
- P1-12 Bottom navigation is four identical bullets: `MainActivity.kt:157` `icon = { Text("•") }` for every tab; only Persian label distinguishes; no selected-icon variation, no badge, no `contentDescription`. Type: design.
- P1-13 CategoriesScreen (if restored) has broken two-LazyColumn layout: `CategoriesScreen.kt:51` chips `LazyColumn` (no height/weight) stacked above `:71` articles `LazyColumn(fillMaxSize)` in a plain `Column` — first list starves the second. Its ordering (sorted by label) disagrees with feed tabs (first-appearance). Do not restore as-is. Type: bug (latent).
- P1-14 "Open original source" can crash: `ArticleScreen.kt:143-144` `ACTION_VIEW` + `startActivity` with no try/catch, scheme check, or chooser — unhandled scheme / no browser → `ActivityNotFoundException` from a tap. Type: bug.

**Market**
- P1-15 Market shows only 5 of 15 snapshot fields, raw numbers, no stale warning, no scroll, no loading/retry: `MarketScreen.kt:44-49` only `tsTehran, brent, xauUsd, usdIrtFree, usdtIrt, sekkeEmami` as raw `Double` interpolation; dropped: `snapshot_id, dxy, usdt_usd, gold_18k_gram, sekke_bahar, nim_sekke, rob_sekke, vix, sp500`. "Latest" is last array element, not max `ts_tehran`. `MarketScreen.kt:30` plain `Column`, no scroll; same for `SettingsScreen.kt:34`. No loading flag, no error, no retry, no stale label (pilot step 7 requires labelling the 2026-10-24 snapshot stale). Sparsity: only 8/164 snapshots have all 5 displayed fields non-null (brent null 156/164, usd null 123/164, sekke null 127/164). Type: bug/missing.
- P1-16 Formulas displayed as text, never computed; screen comment false: `market.json` holds only raw snapshots + 4 formula definitions as S-expressions (`bubble_pct, implied_usd, intrinsic_emami, spread_pct`); zero Kotlin code evaluates a formula (only the data class). UI shows `name (vN)` + `descriptionFa` only. `MarketFormula.inputs` stored as unparsed string, never split. Type: missing.
- P1-17 Market never cached in Room: no market entity/table; every restart refetches; returning to tab never refreshes (`MainActivity.kt:86-87` once-only fetch). Type: design.
- P1-18 Channel refs dead data, zero linkage: `channel_refs.json` 23 refs, zero Kotlin references; 0/23 `post_id`s match article ids, 0/23 `message_id`s match index `channel_message_id` — because that field is null in all 281 index entries and all 249 article files (generator copies a DB column null throughout this export). "Posted to channel" provenance cannot be linked/displayed/deep-linked. Type: missing.

**Settings / notifications / FCM**
- P1-19 Notifications toggle dead UI: `SettingsScreen.kt:69-71` persists via `store.setNotifications(v)`, but `notificationsEnabled` is read nowhere except Settings itself; `SyncWorker` notifies unconditionally; permission requested unconditionally on launch (`MainActivity.kt:170-177`). Type: bug.
- P1-20 Font slider affects reader only; no preview/value; value can be lost: `MainActivity.kt:107` passes `fontScale` only to `ArticleScreen`; `FeedScreen` takes no `fontScale`, so masthead/hero/rows/tabs never change. `SettingsScreen.kt:41-48` slider 0.85–1.3, no numeric label/step/preview; leaving Settings mid-drag without release never fires `onValueChangeFinished` and value is lost. Pilot step 4 (move slider, see article change) requires leaving article → Settings → re-navigate → re-open. Type: bug/design.
- P1-21 Invite flow has no UI (see also P0-13): `SettingsScreen.kt:76` only displays "not confirmed"; no code-entry field anywhere. Type: missing.
- P1-22 FCM send side entirely missing: receive scaffold exists, but nothing in `bridge/`, `edge/`, or `control.py` sends an FCM data message — no topic-send script, no server-key handling, no control-plane "send push" action. Even after a Firebase project lands, no sender exists. Also: `fetchOne()` requires `file != null` — all 32 shorts have `file: null`, so a push for any short silently returns `false`, no fallback to `short_cards.json`. `onMessageReceived` drops messages without `data["article_id"]`. Type: missing.
- P1-23 SyncWorker summary notification overwrites first article's notification: `SyncWorker.kt:29-40` summary uses `articleId = r.newArticleIds.first()`; `Notifications.kt:59` uses `articleId.hashCode()` as notification id, so summary replaces first article's notification instead of appearing alongside. Type: bug.
- P1-24 WorkManager has no network constraint, no backoff, no gating: `SyncWorker.kt:58-60` 15-min periodic, no `Constraints` (runs offline → `Failed` → `Result.retry()` with no `setBackoffCriteria`), no flex; `ExistingPeriodicWorkPolicy.KEEP` means first enqueue wins forever. Type: design.
- P1-25 Sync error handling hides cause, swallows partials, sequential timeouts: `EdgeRepository.kt:30-44` non-200 → `null`, status discarded, generic "index.json not reachable" for 404/403/500/DNS alike; no User-Agent; no HTTPS enforcement on `BASE_URL`; per-article/short failures `continue`d with no count/log/UI; sequential fetch, 15s connect + 30s read per file, no overall budget/parallelism — worst case hours on flaky connection for a 249-file first sync. Type: bug/design.

**Data integrity / Room**
- P1-26 Four article fields dropped at Room boundary: article files contain `source_slug` (203/249), `provenance` (249/249), `publish_status` (249/249: approved 236, posted 13), `extracted_at` (249/249). `Article.kt:9-24` declares `sourceSlug, publishStatus, provenance, file`, but `ArticleEntity` (`AppDatabase.kt:17-30`) has none; `entityToArticle()` does not populate them (default null). No UI shows provenance or approved-vs-posted status. Type: bug.
- P1-27 Multiple Room instances, never closed (except seed's): `Room.databaseBuilder(…, "eco.db")` in `EdgeRepository.kt:56` (per repository — MainActivity, every SyncWorker run, every FCM message), `BundledSeed.kt:90` (closed in `finally`, the only close). No singleton, no close on repository instances. `EdgeRepository` also holds an unread `SettingsStore` field. Seed-vs-sync concurrent writes through different instances, no cross-instance transaction/lock. Type: design.
- P1-28 No migration path; backup can restore stale DB+hash: `@Database(version = 1, exportSchema = false)`, no `Migration`, no `fallbackToDestructiveMigration` — any future entity change crashes existing installs on open. `allowBackup=true` restores old `eco.db` (old `index_hash` + bookmarks) onto a fresh install, so new seed/sync can be skipped (P0-03/P0-04). Type: design.
- P1-29 Bookmarks have no integrity: `BookmarkEntity` (`AppDatabase.kt:34-37`) no FK to articles, no cascade; `bookmarked()` uses `id IN (SELECT articleId FROM bookmarks)` — orphan bookmark simply vanishes, no error/count/cleanup DAO. `addBookmark` `IGNORE` is benign; no orphan-prune or count API. `cachedAt` written on every row, never read — no TTL/eviction. `meta` holds exactly one key (`index_hash`) — no last-sync time/error, so no UI can ever say "last updated …". Type: design.
- P1-30 Malformed index entries can crash sync/seed: index loops call `getJSONObject(i)` outside any try (`EdgeRepository.kt:130-131`, `BundledSeed`); one non-object entry throws `JSONException` out of `sync()` / `seedIfEmpty()`. Per-file corruption handled (`continue`); per-entry not. (Per-short exceptions caught only because whole short loop sits in one try — one bad short silently aborts all remaining shorts.) Type: bug.
- P1-31 Duplicated parsers already drifting: `BundledSeed` and `EdgeRepository` each implement `entityFromArticleJson`/`entityFromShortJson` ("intentionally mirrored") — P1-06 truncation gap is the drift; any fix must be applied twice unless parsers are unified. `EdgeIndex`/`IndexEntry` (`Article.kt:31-46`) are dead models — parsers use raw `JSONObject`, so index `version`/`counts` never validated. Type: design.
- P1-32 SettingsStore fragility: DataStore flow (`SettingsStore.kt:44-50`) has no `.catch` for `IOException` — corrupted preferences fail settings collection instead of falling back to defaults. `setTheme` accepts any string, no validation against `system|light|dark|sepia`; unknown values silently fall back to system. Type: bug.
- P1-33 No Room indices on `category`/`publishedAt` (full-table scans — trivial at 281 rows, but LIKE search can never use an index anyway); `byCategory` ordering duplicates the broken string sort; `byCategory` itself has zero callers (with `CategoriesScreen` dead). Type: design.

**Bridge / control plane (lab CLI, not the app UI)**
- P1-34 Oversize items permanently stuck: `publish_worker.py` computes split plans but never sends parts; no control command to approve/view/send parts; `send_via_bot_api` sends exactly one message; `record_sent` stores only `message_ids[0]`. Article >4096 chars can never reach the channel through this bridge. Split parts also have no numbering/continuation marker or repeated title. Type: missing.
- P1-35 Duplicate-send window: send (~`publish_worker.py:135`) and `record_sent` (`:144`) not atomic, no reconciliation against channel (Bot API gives no history). Crash in window → item stays `published_app` with no `sent` row → next pass posts twice. `record_sent` (`bridge_lib.py:292`) bare `INSERT INTO sent` — duplicate key raises uncaught `IntegrityError`, crashing run, leaving `publish-report.json` unwritten; it also bypasses `transition()`, enforcing no status precondition. Type: design.
- P1-36 `BRIDGE_CHANNEL_ID` never validated — law 14 (channel-only) procedural only: `bridge_lib.py:309-323` checks only non-empty. Any chat id accepted, including a DM id or the live channel id. Nothing distinguishes test vs live channel; `BRIDGE_LIVE=1` is the operator's bare assertion, with no record of any reopen order. Type: design.
- P1-37 "Three-layer dedup" collapses to one layer in practice: layer 1 (`seen_this_run`) real; layer 2 overwrites staging file and counts as `updated` (re-run re-stages everything, plus spurious `queue: duplicate key` with `--enqueue`); layer 3 vacuous — `edge_channel_ids()` filters non-null, but all 281 index entries have `channel_message_id: null`, and 23 refs live in separate `channel_refs.json`, not the index. Type: spec-vs-code divergence.
- P1-38 Edited posts never update review queue: reader overwrites staging file on `edited_channel_post`, but `enqueue_item` (`bridge_lib.py:229`) refuses duplicate keys with no update path, so queued payload never refreshes. If already edge-published, layer-3 dedup skips the edit entirely — corrections never propagate. Type: bug.
- P1-39 `mark-app` certifies a lie: `control.py:137` `cmd_mark_app` just flips status to `published_app`. Spec defines that transition as "edge publish: index.json regenerated + hashed". CLI runs no generator, verifies no index entry, checks no hash — an operator can mark an item readers can never see, and the worker will then send it to the channel. Type: bug/spec divergence.
- P1-40 2/day cadence not enforced anywhere: spec gates `approved → queued` at max 2/day; `control.py:133` `cmd_schedule` is an unconditional status flip — no per-day counter, date check, or slot concept. Type: spec divergence.
- P1-41 Health view implements a fraction of spec §3: `control.py:154` checks edge hash + file resolvability, but market freshness prints timestamp with no ≤24h comparison/warning; shrinkage alert, FCM-topic check, "production DB mtimes unchanged" check, "lock stuck >1h" alert all absent (`lock_status()` returns only free/held, no timestamp). Health reads `dryrun-report.json` only, ignoring `publish-report.json`. Type: missing.
- P1-42 Reviewer cannot actually review: `control.py:85` `cmd_queue_show` prints key/title/source/status/message_id/dates/split_plan/notes — not body, original_url, provenance, or full payload. Spec's "what reviewer sees" (title, category, source, original URL, body verbatim, kind, provenance, warnings) not met; approving/rejecting from CLI is blind unless operator opens JSON by hand. `queue list` truncates titles at 60 chars, shows no blocked reason. Type: missing.
- P1-43 No dashboard: control-plane spec header says "SPEC ONLY in Phase 1. Nothing in this document is built yet"; the "private Muse dashboard / artifact view" still does not exist. What exists is `control.py` on this VM. Any report implying Yasin has a dashboard UI overstates the state. Type: missing.
- P1-44 Invite revocation not even advisory in app; "seen" timestamps are operator fiction: spec says revocation publishes a list to edge and app re-checks on sync. No revocation list generated by `edge/generate_edge.py`; app never fetches one — revoked reader keeps cached articles indefinitely (spec admits this, but the mitigating list was never built). `control.py invites verify` timestamps record when the *operator* ran verify, not any reader check-in, since no app check-in path exists. Type: missing/design.
- P1-45 FCM send side missing (see P1-22): no topic-send script, server-key handling, or control-plane "send push" action. Type: missing.

### 1.3 P2 — polish / consistency / risk (grouped, each verified)

**UI polish**
- P2-01 All UI strings hard-coded; `strings.xml` has exactly one entry (`app_name`); ~50 hard-coded Persian literals in UI code; no `stringResource`/`getString` in UI. Mixed-language leak: `MarketScreen.kt:54` `Text("فرمول‌های فعال (lineage)")`. Formula `definition` (Lisp-like) stored, never shown, so "lineage" heading over-promises (only `name (vN)` + `descriptionFa` shown).
- P2-02 Theme incomplete: `Theme.kt:82-83` uses `typography = MaterialTheme.typography` default; schemes define only primary/background/surface/on*/outline* — secondary/tertiary/surfaceContainer/error fall back to M3 purple-tinted defaults. `NavigationBar` sets no `containerColor` (default tonal surface, not paper white / sepia `#FBF3DF`). `themes.xml` is bare `Theme.Material3.DayNight.NoActionBar`, no window/status-bar sync, no edge-to-edge/insets handling — system bars do not follow sepia/dark paper colour.
- P2-03 Vazirmatn is not theme typography: every `Text` without explicit `fontFamily = EcoFontFamily` falls back to system font — notably `FeedScreen` `OutlinedTextField` has no `textStyle`, so typed search text is default font while its label is Vazirmatn. Only Regular + Bold weights exist (`Theme.kt:19-20`); Medium/SemiBold would be synthesised/fallback.
- P2-04 Accessibility: zero `contentDescription`/`semantics`/`Icon` hits in UI + MainActivity (no Icon composables at all). Clickable rows carry no role/description; bookmark toggle announces label change, not state; sync status has no live-region. Buttons use M3 default 40dp height (under 48dp guidance); bullet "icons" give no visual target beyond label.
- P2-05 Placeholder launcher icon still shipped: `mipmap-xxxhdpi/ic_launcher.png` 192×192, only 2 distinct RGBA colours (navy `#0F2A44` corners, one gold centre value); no `mipmap-anydpi-v26` adaptive-icon XML. Pilot plan itself acknowledges placeholder icons.
- P2-06 Dates raw, mixed-format in byline: `ArticleCard.kt:NewspaperByline` `"${source} • $date"` with `publishedAt` verbatim — 146 RFC-style, 103 ISO in seed — inconsistent, unlocalised timestamps, no Persian date conversion. Short cards show `decided_at` ISO in same slot.
- P2-07 No Persian digits/number formatting anywhere; Latin digits inside RTL: market raw `Double` interpolation, sync counts, snapshot counts all Latin digits; global RTL (`MainActivity.kt:68`) + Latin fragments (masthead, formula names, URLs, timestamps) create bidi edge cases at punctuation.
- P2-08 Fixed feed header eats ~180dp permanently: masthead + rule + search + sync row sit outside pager `LazyColumn`s, so only article list scrolls. Spec says search/sync "always reachable" — implemented literally, at cost of cramped list on small phones; collapsing/scrolling header would preserve reachability without permanent cost.
- P2-09 Rendering nits: hero summary untruncated vs rows capped 3 lines (long hero can dominate a page); screen titles no hierarchy (`MarketScreen.kt:35` "بورد بازار" default 16sp vs 32sp masthead); empty-section text near-dead code (`FeedScreen.kt:263` can only appear if `all` changes between section derivation and page composition); no images at all — `coil-compose:2.7.0` declared but zero `AsyncImage`/`coil`/`Image` usage, seed carries no image fields (matches text-only spec; users expecting thumbnails will perceive "unbuilt"); no pull-to-refresh, share, reading progress, or related-articles (not in spec/checklist — prioritise separately, not spec violations); search field label "جست‌وجو در مقالات ذخیره‌شده" over-promises (titles/summaries only, P1-04).

**Bridge polish (verified, grouped)**
- P2-10 `publish-report.json` not rewritten on lock contention (worker returns before report write; stale report masquerades as current). Two different lock files: real sends `bridge/send.lock`, dryrun `bridge/.dryrun-state/send.lock` — harness contention test proves nothing about real path. Harness drift: `dryrun.py` `LEGAL` lacks `review → rejected`, validator lacks split hint — two machines can disagree.
- P2-11 Notes unbounded: blocked items append a `blocked: …` note on every worker pass (`set_note` never truncates). `get_item` (`bridge_lib.py:246`) `SELECT *` zipped against hardcoded column order — future schema change silently misaligns fields; `init_db` `CREATE TABLE IF NOT EXISTS`, no migration, old `queue.db` keeps old schema. `sent.channel_configured` hardcoded `1` — dead column.
- P2-12 Enqueue key collision: `f"article:{article.get('id', '')}"` — id-less articles collide on `article:` and later ones refused. `send_via_bot_api` (`bridge_lib.py:329-357`): on `HTTPError` only status code, Telegram description discarded; `URLError` propagates unlabelled; no retry/backoff/count (one attempt per pass; next pass retries — tolerable, not fatal).
- P2-13 Channel text drops `original_url` (`format_channel`, `bridge_lib.py:79`: title + body + source name only), and `disable_web_page_preview=True` would suppress previews anyway. `validate_channel_text` substring-matches `TODO`/`Lorem`/`XXX`/`[...]` — legitimate article quoting `[...]` elision is blocked. Cap measured with Python `len()` (code points); Telegram counts emoji/combining differently — off-by-a-few risk at boundary. Spec wording stale: spec says failed send leaves item at `queued`; worker (correctly per machine) leaves it at `published_app`. `publish-report.json` (`would_send`: 1) vs `dryrun-report.json` (`would_send`: 2) disagree (queue DB vs seed articles). `enqueue_article` calls `init_db` before validating article file; `control.py main` creates DB even for read-only commands.
- P2-14 Channel reader nits: title truncated 120 chars, no ellipsis; summary cut 300 chars mid-word, no ellipsis; no `--chat-id` filter (any `channel_post` staged regardless of source channel; missing `chat.title` degrades source to generic «کانال»); `staging/index.json` overwritten per run with only that run's items, so `staged_count` reflects last run, not cumulative staging; `edited_channel_post` for never-seen message creates fresh staged item instead of orphan-edit flag; `message_id=None` mislabelled ("duplicate in this input file" instead of "no message_id"); `--already-reviewed` enqueues straight to `approved` on operator's bare assertion; no cap/format validation on ingest (a >4096-char post stages fine, fails later at worker); dates rendered UTC, not Tehran time.
- P2-15 Control-plane nits: `health` `lock_status()` opens `send.lock` with `"w"` — nominally read-only check creates/truncates lock file. `health` `json.loads` on edge/market files with no try — corrupt file crashes health instead of reporting unhealthy. Health reports only count of unresolvable entry files, not which. Market "latest" takes `snaps[-1]`, assuming chronological order. `queue list --status` accepts any string (invalid → `(empty)`); `--limit` unvalidated (negative silently accepted by SQLite).
- P2-16 App notification/FCM nits: permission requested immediately on first launch with no rationale, result callback empty comment — on denial, Android 13+ silently drops notifications with no in-app explanation/re-request guidance (mitigated: never crashes; `manager.notify` is silent no-op). Notification small icon generic `android.R.drawable.ic_dialog_info` (`Notifications.kt`), not app icon. Notification id + PendingIntent request code both `articleId.hashCode()` — collisions merge/overwrite distinct articles. `EcoMessagingService` unstructured `CoroutineScope(Dispatchers.IO)` — process death mid-fetch loses push, no retry; `fetchOne` false → no fallback, no log. FCM text is `entity.summary`, may be empty → blank notification. `SyncWorker.schedule` `ExistingPeriodicWorkPolicy.KEEP` — schedule definition changes never apply. `Failed -> Result.retry()` default backoff, no cap discussion; noisy on permanently dead edge URL. `RECEIVE_BOOT_COMPLETED` declared, no boot receiver exists (harmless — WorkManager reschedules itself — dead weight). `allowBackup=true` backs up DataStore including `invite_ok` + FCM token — restore-to-another-device semantics for a gated pilot never considered.
- P2-17 Test gaps: `test_phase2.py` passes its stated scope (19 methods) and dryrun 13/13, but no test covers split-part sending, caption posts, edited-post queue staleness, `record_sent` duplicate-key crash, app invite gate, permission-denied notification behaviour, FCM token handling, or `mark-app` edge verification. The 19/19 badge does not cover any P0/P1 above except lock, gates, and basic dedup.

**What is correct — do not "fix":** masthead 32sp bold navy, double rule 2dp/2dp/1dp, hero 26/34sp, row 18/26sp + summary 15/22sp max 3 lines, reader 28sp headline / 18sp body × fontScale with 1.35/1.75 line heights, theme hexes (light `#0F2A44`/`#121212`, dark `#9DB8D6`/`#121212`, sepia `#5C4321`/`#FBF3DF`), forced RTL, Vazirmatn files wired, tab↔pager core mechanism, `flock` send lock, fail-closed live gates, 4096 boundary tests, hash-only invite storage, FCM-scaffold-does-not-crash reasoning, seed↔edge byte identity, category-key↔Fa-label 1:1 mapping (6 keys), and pilot step 8 behaviour (sync returns `NotConfigured`, no invented articles).


---

## 2. Phase 0 — infrastructure design (DESIGN ONLY, no live activation)

Phase 0 builds, in the lab, the contracts and code paths that will let app,
channel, data_core, edge, and bridge reach each other and stay in sync
**after** the freeze lifts. Phase 0 itself changes no live cron, bot, channel
post, or freeze state. If a Phase 0 item needs a live decision or a live
secret, it stops at a written decision gate (see §2.8) and waits for Yasin.

### 2.1 Single source of truth and versioning

- **Truth:** the editorial export produced from data_core (the generator in
  `edge/generate_edge.py`, lab copy) is the only truth for *content*.
  Room on the phone is a **cache, never truth**. The channel is a
  **projection** of the same truth, not a second source. The bridge queue is
  a **workflow state machine**, not a content store.
- **Identity (fixes P0-01, P1-18, P1-37):**
  - Keep the verbatim source id as `external_id` (never edited, never used
    in a navigation route).
  - Add `app_key`: a URL-safe surrogate, e.g. `a-` + first 20 hex chars of
    `sha256(external_id)`. Navigation, bookmarks, notifications, and FCM
    `article_id` all use `app_key`. The index carries both fields, so any
    `app_key` resolves to exactly one item and any `external_id` maps to
    exactly one `app_key`.
  - Add `channel_message_id` populated at publish time (currently null in
    all 281 entries) so channel refs and articles can finally link.
  - Short cards get the same treatment: `app_key` from their `draft-*` id.
- **Versioning (fixes P0-04, P0-05, P1-31):**
  - `manifest.json` (new, alongside `index.json`): `schema_version` (start
    at 2), `generated_at` (Tehran ISO + UTC), `content_hash` computed over
    the **canonical full payload** — every article body, points,
    why-matters, short text, market snapshot, and channel ref — never over
    the entry list alone.
  - Per-item: `content_hash` (sha256 of that item's canonical body),
    `updated_at` (epoch millis), `status`: `approved | posted | retracted`.
    A changed body changes the item hash even if title/category are
    unchanged; a retracted item becomes a **tombstone** (id + status +
    `retracted_at`), never a silent disappearance.
  - `index.json` keeps its current shape plus `app_key`, `content_hash`,
    `updated_at_epoch`, `status` per entry, and a top-level `manifest_hash`.
    Old clients (phase2 APK) ignore unknown fields — the new schema is
    additive, not a breaking rename.
  - Seed versioning: bundled seed carries `seed_version` (integer, bumped
    on every regeneration). Room `meta` stores `seed_version` +
    `index_hash` + `last_sync_at` + `last_sync_error_code`. Seeding is
    resumable: seed only if stored `seed_version < bundled`, per-file
    transaction, and a failed file is retried next launch, never marked done.

### 2.2 Edge publishing contract (fixes P0-03, P0-04, P0-10, P1-39)

- Generator (`edge/generate_edge.py` v2, lab only) outputs, in one atomic
  publish: `manifest.json`, `index.json`, `articles/*.json`,
  `short_cards.json`, `market.json`, `channel_refs.json`,
  `revocations.json` (invite revocation list, hashes only),
  `invite_hashes.json` (pilot invite hashes only, no codes).
- **Atomicity:** write to a temp directory, compute all hashes, then rename
  into place and write `manifest.json` last. A publish is either fully
  visible with a matching manifest, or not visible at all. Never write
  `index_hash`/`manifest_hash` before the payload it describes.
- **Promotion path (new, closes P0-10):** `bridge/staging/` →
  `control.py promote <staging-id>` → generator input → edge publish →
  `control.py mark-app` now **verifies** the item exists in the new index
  with a matching `content_hash` before flipping status to `published_app`.
  `mark-app` without verification is removed.
- **Categories:** generator must stop hard-coding shorts to `general`.
  Shorts carry a real category (from the source decision) or are explicitly
  marked `uncategorised` and shown only under «همه», never dumped into
  «عمومی». (Fixes P1-01 at the source, not by a UI hack.)
- **Dates:** generator normalises every `publishedAt` to epoch millis
  (`published_at_epoch`) at export time; the app sorts and displays from
  epoch, never from raw strings. Raw strings are kept only for debugging.
  (Fixes P0-06 at the source; the app also normalises defensively on parse.)

### 2.3 Sync protocol — app ↔ edge (fixes P0-02, P0-04, P0-05, P0-07, P1-12, P1-17, P1-25)

- **Poll (primary, works with static Pages hosting):**
  1. Fetch `manifest.json`. If `manifest_hash` == stored hash → stop, report
     "up to date" (no article fetches at all).
  2. Else fetch `index.json`. For each entry: if `app_key` absent → fetch
     article file; if present and `content_hash` differs → fetch and
     replace; if `status == retracted` → apply tombstone (remove from feed,
     keep bookmark row flagged as retracted, never silently delete a saved
     article's bookmark record).
  3. Apply the whole batch in **one Room transaction**. Only on transaction
     success, store the new `manifest_hash` + `last_sync_at`. On any failure,
     store `last_sync_error_code` (never the raw exception) and retry nothing
     permanently — the next sync re-attempts exactly the failed items.
  4. Market + shorts + channel refs + revocations + invite hashes sync in
     the same manifest-gated pass, with the same transaction rule.
- **Push (secondary, only after Firebase decision):** FCM data messages
  carry only `app_key` (+ `content_hash` hint). The app, on receipt, fetches
  that one item via the same poll path (`fetchOne(app_key)`), never trusting
  the push payload as content. A push without `article_id` is still dropped
  (by design), but now logged as a diagnostic code, not silently swallowed.
  Short cards are fetchable via the same path (fixes P1-22 short-push gap).
- **Retry / conflict / offline:**
  - Retry: exponential backoff, capped, with a total budget per sync run;
    no infinite sequential 15s+30s per-file waits. Parallel fetch with a
    small concurrency cap (e.g. 4) instead of one-by-one.
  - Conflict: edge always wins for content. Local state that is *not*
    content — bookmarks, font scale, theme, notifications setting — is
    never overwritten by sync.
  - Offline-first: Room is the render source always. Sync failure never
    clears the cache, never shows an empty feed, and never fabricates
    articles. Airplane mode = cached feed + honest "offline, showing cached
    articles" state (not "sync failed" panic, not fake freshness).
  - Errors: user-visible messages are fixed strings by error code
    (`not_configured`, `offline`, `server_error`, `partial` with a count of
    retried items). Raw exceptions, URLs, and internal TODOs never reach UI.
- **Edge URL decision gate:** `EdgeConfig.BASE_URL` stays empty in the lab
  until Yasin picks the real edge URL (GitHub Pages URL after the Pages
  workflow is enabled, or another host). Setting it is a build-time
  configuration change (BuildConfig field / documented constant), never a
  secret, and it is the **only** switch that turns poll from `NotConfigured`
  into live — it does not by itself unfreeze anything else.

### 2.4 Bridge queue + publish gate + flock (fixes P1-34–P1-40, P2-10–P2-15)

- **Queue as outbox, not truth:** `bridge/queue.db` keeps workflow state
  only. States (unchanged, now actually enforced): `review → approved →
  queued → published_app → sent`, plus `rejected` from review, plus
  `sending` (new, transient, for crash recovery).
- **Publish gate:** a send pass: (1) acquire non-blocking `flock` on the
  **single** lock file `bridge/send.lock` (dryrun uses the same path, or its
  contention test is labelled as not proving the real path); (2) load items
  in `published_app`; (3) for each, check `sent` table **before** sending
  (idempotency); (4) send via Bot API; (5) record `sent` in the same
  transaction as the status flip to `sent`, or mark `sending` first and
  reconcile `sending` items on next pass (never blindly re-send, never
  crash on duplicate key — duplicate `sent` insert becomes a no-op with a
  warning). `publish-report.json` is always rewritten, including on lock
  contention (with a `skipped: lock held` status, not a stale report).
- **Split sending (closes P1-34):** items >4096 chars are sent as numbered
  parts (`1/N … N/N`, title repeated on part 1, attribution on last part),
  approved as a whole; `record_sent` stores **all** `message_ids`, not just
  the first. No part is sent without the whole item being approved.
- **Caption + edits (closes P0-12, P1-38):** reader reads `caption` when
  `text` is absent (photo-with-caption stages with a `has_media` flag and a
  note that media itself is not republished — nothing invented); text+photo
  keeps the photo note. `edited_channel_post` updates the queued payload
  if not yet sent, and creates an `edit` record (never a silent overwrite)
  if already sent — the operator decides whether to post a correction.
- **Dedup, real this time:** layer 1 in-run set; layer 2 staging keyed by
  `channel_message_id` (update, not overwrite-and-count-as-new); layer 3
  edge `channel_message_id` — which only works once §2.1 populates it.
  Until then, layer 3 is documented as inactive, not claimed.
- **Channel-id safety (closes P1-36):** `BRIDGE_CHANNEL_ID` must match an
  allow-list recorded in the lab config (test channel id, and later, only
  by Yasin's explicit live-channel decision, the live id). A DM id or an
  unlisted id fails the gate with a named error. `BRIDGE_LIVE=1` alone never
  authorises the live channel.
- **Cadence (closes P1-40):** `cmd_schedule` enforces max 2/day from the
  `sent` table (Tehran date), with named slots (e.g. midday/evening); a
  third schedule attempt fails with a named error, not a silent flip.
- **Reviewer tooling (closes P1-42):** `queue show` prints body verbatim,
  original_url, provenance, kind, warnings, and blocked reason; `queue list`
  shows full titles (or a clearly marked truncation) and blocked reasons.
- **Health (closes P1-41):** health checks edge manifest hash + file
  resolvability **by name**, market age (≤24h warning, not just a
  timestamp), shrinkage vs previous manifest, lock age (>1h alert, read
  without truncating the lock file), FCM-topic readiness flag, and
  `publish-report.json` (not only dryrun). Corrupt JSON reports unhealthy,
  never crashes health.

### 2.5 Invite / control plane (fixes P0-13, P0-15, P1-21, P1-44, P1-43)

- **App gate (real, for the first time):** first-run screen (before feed)
  with a code entry field. The app fetches `invite_hashes.json` from the
  edge on sync (or from the bundled seed for the very first run), hashes
  the entered code with SHA-256, and compares hashes only — codes never
  leave the device, never appear in logs or telemetry. On match, store
  `invite_ok = true` locally and show the feed. On mismatch, show a fixed
  error, never a hint about which code is valid. The gate is **not** a
  security boundary against a determined APK holder (debug APKs can be
  decompiled) — it is a pilot-distribution control, and the plan says so
  honestly.
- **Revocation:** `revocations.json` (hashes only) is published with the
  edge manifest. On each sync, the app re-checks its stored invite hash
  against it; on revocation, the feed locks again with an honest message
  ("access revoked, cached articles hidden" — cached rows are hidden, not
  deleted, so a re-grant restores them without re-download). This is the
  mitigation the spec promised but never built.
- **Reconciliation gate (before any pilot):** reconcile the 20 `invites`
  rows in lab `queue.db` against the 10-code pilot file (which 10, if any,
  are live; why 10 are revoked; whether a second generation exists).
  Until reconciled, no invite list is published and no pilot starts.
  Invite generation loops on collision, labels are unique, and `revoke
  --label` refuses ambiguous labels — all fixed in the control CLI.
- **Control plane:** the lab CLI (`control.py`) stays the operator tool in
  Phase 0. The "private Muse dashboard / artifact view" is a **Phase 1
  follow-up**, not claimed as built. What Phase 0 adds to the CLI: promote,
  verified mark-app, queue show with body, cadence enforcement, split-send
  execution, caption/edit handling, and the health checks in §2.4.
  `invites verify` is renamed in output to "operator verified" — it must
  never be reported as "reader seen" (there is no app check-in path yet;
  adding one is a separate privacy decision for Yasin, see §2.8).

### 2.6 Telemetry / crash reporting without PII

- **Principle:** no article content, no invite codes, no tokens, no device
  identifiers, no user text, no URLs with parameters. Ever.
- **What is collected (local first):** an in-app Diagnostics screen showing
  sync state (last sync time, manifest hash prefix, item counts, last error
  *code*), seed version, app version, and a "copy diagnostics" action that
  copies only those counts/codes — the user chooses to share it.
- **Crash reporting (opt-in, Phase 1, not Phase 0 default):** if Yasin
  approves a crash reporter later, it must be configured to strip PII,
  exclude DataStore/ Room contents, and be disableable from Settings. Until
  then, crashes are reported by the pilot checklist (user sends the
  diagnostics copy + what they tapped). No analytics SDK, no advertising
  id, no location, no contact/calendar access is added for telemetry.
- **Logs:** bridge/worker logs keep the current fail-closed, no-secret
  practice; `publish-report.json` / `dryrun-report.json` disagreement
  (different sources) is fixed by labelling each report's source in the
  file itself.

### 2.7 What Phase 0 does NOT do

- No live cron enabled, disabled, or changed. No bot code touched. No
  channel post, delete, or edit. No test channel created (a bot cannot
  create a channel via Bot API — that step, if needed, is Yasin's manual
  step in Telegram, listed in §2.8, not an automated action).
- No Firebase project created, no `google-services.json` added, no FCM
  token generated, no push sent.
- No GitHub push of this plan or of any fix (plan file is lab-only until
  Yasin approves implementation). No APK distributed beyond the pilot
  readers Yasin names, and only after the invite reconciliation gate.
- No unfreezing of the 24 ECO JOURNAL crons, the war room, ML, the
  23:00 report, the daily letter, or the special-issues service. Those are
  separate freeze-lift decisions (see §2.8), not side effects of app work.
- No change to `~/workspace/econ-news-blog/` production code or to
  `~/workspace/yasineco_bot/` live code. The generator changes in §2.2 are
  made to the **lab copy** (`app/edge/generate_edge.py`) and only merged
  into production tooling by a separate, explicit decision.

### 2.8 Freeze-lift checklist — decisions Yasin must make BEFORE any live switch

These are questions, not actions. Nothing below is flipped by this plan.

1. **Edge URL:** approve the public edge host (GitHub Pages URL from
   `syfazel/eco-journal-app` Pages workflow, or another host) and enable
   Pages. Until then `BASE_URL` stays empty and sync stays `NotConfigured`
   by design.
2. **Firebase:** create (or decline) a Firebase project (`eco-journal-app`
   proposal, Spark plan, package `com.ecojournal.app`), supply
   `google-services.json` through the secure flow, and approve the FCM
   topic name. Declining FCM is a valid outcome — WorkManager polling +
   local notifications then remain the only push path, with honest limits.
3. **Test channel:** manually create a private Telegram test channel (bot
   cannot create one), add @YASINECOBOT as admin, and record its id in the
   lab allow-list (§2.4). Live channel id is added to the allow-list only
   by a separate explicit order.
4. **Invite reconciliation:** confirm which 10 pilot codes (if any) are
   live (P0-15), who the pilot readers are, and whether revoked readers
   should lose cached access (revocation semantics, §2.5).
5. **Cadence + editorial gates:** confirm 2/day publishing cadence, who
   reviews the bridge queue, and whether the special-issues service (frozen
   separately) resumes alongside the app or stays frozen.
6. **Platform unfreeze scope:** list exactly which of the 24 frozen crons,
   the war room, ML training (v0, 28 Oct), the 23:00 report, and the daily
   letter resume, and in what order — app readiness does **not** auto-resume
   any of them. The Monday 04:10 low-burn restore job stays disabled unless
   explicitly re-enabled.
7. **Distribution:** debug APK via direct download (current path) vs a
   release-signed build vs Play — signing keys are a separate secure-flow
   decision, never committed to the repo.
8. **Privacy:** approve the telemetry rules in §2.6 and the app check-in
   question (whether the app ever reports back that a code was used — if
   yes, what exactly, and with what consent screen).
9. **Icon + branding:** approve the final launcher icon (current one is a
   2-colour placeholder) and confirm no NYT name/logo is used anywhere
   (style only, already verified clean by grep).
10. **Go / no-go:** after the pilot checklist in §5 passes on a real phone,
    Yasin gives a single explicit "lift the freeze for X" order naming the
    scope from (6). Until that sentence, everything stays frozen.

---

## 3. Next big version — scope and milestones (in order)

**Version target:** `1.0.0` (from `1.0.0-phase2` debug). No scope creep
inside a milestone: each milestone ends with a cloud build + a phone test
against its acceptance criteria before the next milestone starts. If a
milestone's acceptance fails, fix inside that milestone — do not carry a
known P0 into the next milestone and do not start a new feature on top of
a broken base (this is the lesson of builds 1–5, which each revealed the
next hidden error).

### M0 — Freeze the baseline (this plan; no code)
- Deliverables: this file, the §1 register, and a lab-only branch state
  with **zero** production changes.
- Acceptance: plan file exists in lab, not pushed; `git status` in lab
  shows only the plan file as new; no live cron/bot/channel state changed.

### M1 — Navigation and identity (fixes P0-01, P1-07, P1-08, P1-12)
- Introduce `app_key` (§2.1) in index/seed parsers and Room (new column,
  indexed, unique). Navigation, bookmarks, notifications, and FCM all use
  `app_key`; verbatim id kept as `external_id` for display/provenance only.
  (Fallback if surrogate migration is judged too big for M1: `Uri.encode`
  on navigate + `Uri.decode` on read — but the surrogate is preferred
  because it also fixes FCM/notification identity and hash collisions.)
- Article not-found screen: back button, retry, "open feed" action, and a
  `fetchOne(app_key)` fallback when edge is configured.
- Reader top bar: back arrow, title, bookmark toggle at top (not only at
  article end); bottom bar hidden on the article screen (or reader shown
  as a full-screen destination with its own top bar).
- Bottom nav: real icons (Material icons, not `•`), selected-state
  variation, content descriptions, saved-count badge (optional, if cheap).
- Acceptance (phone): open 10 articles chosen to include slash-ids,
  `?`-ids, `#`-ids, and short cards — all 10 open to the correct article;
  tap a notification deep-link to a slash-id article — opens correctly;
  open an unknown id — shows not-found screen with working back; reader
  has a visible back arrow; bottom bar does not show on reader.

### M2 — Data correctness (fixes P0-02–P0-06, P1-26, P1-27, P1-28, P1-31, P1-32)
- Single Room singleton (one instance, closed on process death path only
  where appropriate); single parser module shared by seed and sync
  (delete the mirrored parsers); `EdgeIndex`/`IndexEntry` models actually
  used for validation (schema version + counts checked, mismatch = sync
  error code, not silent accept).
- Date normalisation: parse every `publishedAt` variant to epoch millis at
  import; store `published_at_epoch`; sort/display from epoch; display
  formatted Persian date (not raw string). Fix feed order and hero.
- Seed: versioned, resumable, transactional per file; `seed_version` in
  `meta`; feed observes Room via `Flow` (not once-only `List`) so the feed
  updates when seeding completes; same reactive pattern for bookmarks and
  market.
- Sync: manifest-gated (§2.3), per-item `content_hash` updates, tombstone
  deletes, hash written only after a successful transaction, per-entry
  corruption skipped with a count (not a crash), per-file failures counted
  and surfaced as `partial` with retry count.
- Migration: Room version 2 with a real `Migration` (or, if Yasin accepts
  data loss for a debug pilot, a documented destructive fallback **only**
  for debug builds — never silent for release); `exportSchema = true`.
  Backup: exclude `eco.db` hash/bookmark state from restore confusion or
  document the restore behaviour (seed-version check must run after restore).
- Acceptance (phone): fresh install shows feed without tapping Sync;
  kill the app mid-first-run, relaunch — feed completes (no permanent
  partial state); feed hero is the newest article in each section by date;
  edit an article body on the edge (lab), re-sync — body updates in app;
  retract an item on the edge (lab), re-sync — item leaves feed, bookmark
  (if any) shows "retracted" instead of vanishing silently.

### M3 — Feed, reader, search, bookmarks UI (fixes P1-01–P1-06, P1-09, P1-10, P2-01–P2-09)
- Sections: fixed, stable category order (not first-appearance); shorts get
  real categories from the generator (§2.2) or are shown only under «همه»
  with a clear «گزیده» kicker; duplicate headlines grouped or flagged
  (same title + different kind shown with kind label, never as two
  identical rows without distinction).
- Scroll preservation: section-keyed `LazyListState`; pager keeps scroll
  per section; tab switch does not reset other sections.
- Bookmarks: re-query on resume (or `Flow`); bookmark toggle at top of
  reader; orphan bookmarks shown with a "no longer available" row + prune
  action, never silently vanished.
- Search: body + points + source search (FTS or LIKE over a normalised
  text column), escaped `%`/`_` (ESCAPE clause), 300ms debounce, result
  count, clear (×) button, IME search action, and tabs restored exactly
  (same page) when query is cleared. Label fixed to what it actually
  searches.
- Reader: render `points` as a key-points block and `why_matters` as a
  "why it matters" block when present; short cards show summary **or**
  body, never both duplicated; source button guarded (try/catch + scheme
  check + chooser); source/provenance line shown when present.
- Typography/themes: Vazirmatn as theme typography (not per-Text only);
  font scale applied to feed, market, settings, and reader, with an
  in-reader font control **and** the Settings slider (with numeric value,
  preview paragraph, and save on change, not only on release); complete
  colour schemes (secondary/tertiary/surfaceContainer/error) for
  light/dark/sepia; bottom bar + status bar follow paper colour; system
  bars handled (edge-to-edge/insets).
- Strings: move all UI strings to `strings.xml` (or a string resource
  module); remove the English "lineage" leak; Persian digits + thousands
  separators for all numbers; Persian date formatting.
- Accessibility: content descriptions, roles, and 48dp touch targets for
  rows, tabs, bookmark toggle, and sync button; TalkBack walkthrough of
  feed → article → back.
- Acceptance (phone): the full §5 checklist items F1–F12 pass, including
  search for a body-only word, bookmark toggle → Bookmarks list updates
  without restart, font slider changes feed text immediately, and a
  TalkBack pass reads each row as an article with an open action.

### M4 — Market board (fixes P0-08, P1-15–P1-17, P2-07)
- Seed market from `assets/seed/market.json` on first run (same versioned
  pattern as articles); cache market in Room (snapshot entity + formula
  entity); sync updates market in the same manifest-gated pass.
- Show all available fields (not just 5), with missing fields shown as
  "—" (never silently omitted); "latest" = max `ts_tehran`; formulas
  **computed** from snapshots (bubble %, implied USD, intrinsic coin,
  USDT spread) with inputs shown, not just definitions as text.
- Formatting: Persian digits, thousands separators, units, change vs
  previous snapshot where computable; staleness label ("last update:
  <Persian date>, X days ago — stale" when older than the freshness
  threshold Yasin sets, default 24h for a live board, labelled honestly
  for a bundled snapshot).
- Layout: scrollable (`LazyColumn`); loading state, error state with
  retry, offline state ("showing cached snapshot from <date>").
- Acceptance (phone): airplane mode → market shows bundled snapshot with
  stale label and all fields (or "—" for nulls); formulas show computed
  values, not just names; scroll reaches the bottom; a newer snapshot on
  the edge (lab) updates the board after sync.

### M5 — Offline, sync, notifications, settings (fixes P0-07, P0-14, P1-19, P1-23–P1-25, P2-16)
- Edge URL: set `BASE_URL` only via the §2.8 decision (1). Until then,
  sync states stay honest: `NotConfigured` message without TODO text,
  without raw exceptions, with a "what this means" line.
- Sync UX: disabled-while-syncing button, progress indicator, fixed-string
  error codes, `partial` count, last-sync time displayed (from `meta`).
- WorkManager: network constraint, backoff policy, flex interval, and a
  policy that updates the schedule definition (not `KEEP` forever);
  `NotConfigured` does not schedule pointless periodic work.
- Notifications: toggle actually honoured everywhere (SyncWorker +
  FCM path); permission requested with rationale, with in-app guidance
  on denial; notification icon = app icon; notification ids unique per
  article (not `hashCode()` alone — use `app_key` hash + a stable
  per-type prefix, and a distinct summary id that never overwrites an
  article notification); summary notification opens a list, not the first
  article.
- Settings: DataStore `.catch` fallback to defaults; theme value validated;
  settings that do nothing are either wired or removed (no dead switches
  shipped).
- Acceptance (phone): airplane mode → sync reports offline, feed stays
  cached, no crash, no fake articles; notifications OFF → no notification
  from a sync that finds new items (lab edge); notifications ON → new
  articles notify with app icon and correct deep-links; deny permission →
  in-app guidance appears, app keeps working.

### M6 — Categories decision (fixes P0-09, P1-13)
- **M6 IMPLEMENTED — option (B) chosen by parent decision, not
  re-opened:** `CategoriesScreen.kt` deleted, the fake `categories`
  route deleted, dead `byCategory()` DAO method deleted.
  `BookmarksScreen` was moved verbatim from that file into its own
  file (body verified identical) and stays on the bookmarks
  bottom-nav tab. Pilot checklist step 6 and the UI spec now say
  categories = feed tabs, no separate screen. Pilot item F4 is
  therefore the option-B branch: categories provably absent, no UI
  text promises a categories screen.
- **Decision first, code second (original plan text):** either (A) wire `CategoriesScreen`
  properly — fix its two-LazyColumn layout (chips row with bounded height
  + article list with weight), use the same fixed section order as the
  feed, and add a bottom-nav or feed-header entry that actually navigates
  to it; or (B) delete `CategoriesScreen.kt` + the fake `categories`
  route + `byCategory()` dead DAO method, and update the pilot plan to
  remove step 6 (categories live only as feed tabs).
- Do not ship both a dead screen and a fake route again. Whichever option
  is chosen, the pilot checklist is updated to match reality.
- Acceptance (phone): either categories open from a visible entry point
  and filter correctly, or the file/route/method are gone and no UI text
  promises a categories screen.

### M7 — Invite, control plane, bridge directions (fixes P0-10–P0-13, P0-15, P1-34–P1-45)
- App invite gate UI (§2.5) + edge hash list + revocation list + sync-time
  re-check; invite reconciliation gate (§2.5) completed before any pilot.
- Bridge: staging→edge promotion (§2.2), verified `mark-app`, caption
  handling, edited-post update path, split-send execution with all
  `message_ids` recorded, atomic send+record with `sending` reconciliation,
  channel-id allow-list, 2/day cadence enforcement, `queue show` with body,
  health checks (§2.4).
- FCM send side (only if §2.8 decision (2) is "yes"): topic-send script +
  control-plane action + server-key handling through the secure flow;
  otherwise, FCM stays receive-scaffold-only and the plan records that
  decision explicitly.
- Control CLI: collision-looping invite generation, unique labels,
  `revoke --label` ambiguity handling, `health` without lock truncation
  and without crashing on corrupt JSON, `queue list` validation, single
  lock file, always-rewritten reports, bounded notes, and tests covering
  split sending, captions, edited posts, duplicate-key `record_sent`,
  invite gate, and `mark-app` verification (closing P2-17 test gaps).
- Acceptance (lab + phone): a captioned test-channel post (lab) stages
  with caption + media note; an edited post updates the queued payload;
  a >4096-char item sends as numbered parts with all message ids recorded;
  a crash-window simulation does not double-send; invite code entry
  unlocks feed, wrong code does not, revoked code (lab list) re-locks feed
  on next sync; `queue show` shows body + URL + provenance.

### M8 — Polish, icon, accessibility, diagnostics (fixes P2-01–P2-09, P2-16)
- Final launcher icon (adaptive icon XML + all densities) approved by
  Yasin; no NYT name/logo anywhere (re-grep before release).
- Strings fully resourced; Persian digits/dates everywhere; hero summary
  capped consistently with rows (or an explicit design decision recorded);
  screen title hierarchy (masthead 32sp → screen titles 22–24sp bold →
  body 16–18sp); feed header behaviour decided (fixed vs collapsing) and
  implemented consistently.
- Accessibility pass (TalkBack, touch targets, contrast spot-checks);
  empty/error/offline states for every screen (feed, article, market,
  bookmarks, search with zero results, settings); pull-to-refresh on feed
  (if Yasin wants it — otherwise explicitly deferred, not half-built);
  share action (if Yasin wants it — otherwise deferred).
- Diagnostics screen (§2.6): sync state, counts, last error code, seed
  version, app version, copy-diagnostics action. No PII in diagnostics.
- Acceptance (phone): §5 polish checklist passes; icon is final, not
  placeholder; TalkBack pass completes; every screen has a designed empty
  state (not a blank screen or a raw error).

### M9 — Pilot build and freeze-lift rehearsal (no live flip)
- Cloud build green on the release candidate branch/commit; APK downloaded
  and verified with `aapt` (package, versionCode/versionName, minSdk,
  targetSdk, label, permissions) + SHA-256 recorded; APK size noted
  (phase2 was 15.2 MB — the NYT-style UI constraint from 2026-10-25
  ("close to NYT app, smooth tabs, heaviness acceptable") still applies:
  no artificial <12 MB budget, but size growth must be explained).
- Full §5 pilot checklist executed on a real phone (Yasin's S24 FE or
  equivalent), results recorded per item (pass/fail + evidence).
- Freeze-lift **rehearsal** (dry-run only): walk the §2.8 checklist item
  by item, record Yasin's decision for each, and produce a one-page
  "lift order" draft naming exactly what would be flipped, in what order.
  The draft is **not** executed. Execution waits for Yasin's explicit
  "lift the freeze for X" sentence naming the scope.
- Acceptance: pilot checklist ≥ all P0/P1 items pass; §2.8 decisions
  recorded; lift-order draft written; zero live changes made by M9 itself.

---

## 4. UI spec refinements (newspaper style, kept + fixed)

Keep from `docs/nyt-ui-spec.md` (verified faithful in the shipped APK):
masthead centred `ECO JOURNAL` 32sp bold navy + double rule (2dp / 2dp gap /
1dp); `ScrollableTabRow` + `HorizontalPager` with tabs derived from cached
categories (no hard-coded tabs); hero first story (kicker, 26sp/34sp title,
16sp summary, byline source•date, double rule); following stories as flat
text rows with 1dp separators (no cards/shadows); reader kicker, 28sp
×fontScale title, byline block, double rule, 18sp ×fontScale body at 1.75
line height; offline search replacing pager with a flat list while searching;
sync button + status kept. Palettes unchanged: light paper white / ink
`#121212` / navy `#0F2A44`; sepia paper `#FBF3DF` / ink `#2B2118` /
`#5C4321`; dark paper `#121212` / ink `#F2F2F2` / `#9DB8D6`. Vazirmatn
everywhere, RTL everywhere. No NYT logo/name/content in code.

Refinements for vNext (each maps to a register item):
- **Tabs:** fixed category order (M3); tab labels from cached categories +
  «همه»; tab click and swipe stay in sync; per-section scroll preserved
  (P1-09, P1-10). Tab bar scrolls horizontally when categories overflow.
- **Hero:** hero is the newest article in the section by epoch date
  (P0-06); hero summary capped (decide: same 3-line cap as rows, or a
  longer explicit cap — record the decision, do not leave it uncapped).
- **Rows:** kicker shows category + «گزیده» for shorts; byline shows
  source • Persian-formatted date (not raw string); duplicate headlines
  distinguished by kind label (P1-02, P2-06).
- **Reader:** top bar with back + bookmark + share (share only if M8
  approves it); points block and why-matters block when present (P1-05);
  short cards show summary **or** body, not both (P1-06); source button
  guarded (P1-14); not-found screen with back/retry/feed actions (P1-07);
  font control in reader + Settings slider (P1-20).
- **Market:** scrollable board; all fields with "—" for nulls; computed
  formula values; stale label; loading/error/retry/offline states (M4).
- **Saved:** list re-queries on resume; retracted bookmarks flagged, not
  vanished; orphan rows with prune action (P1-03, P1-29).
- **Search:** full-text (title + summary + body + points + source),
  escaped wildcards, debounce, count, clear, IME action; tabs restored
  exactly on clear (P1-04).
- **Settings:** every switch wired or removed; font slider with value +
  preview + save-on-change; theme picker (system/light/dark/sepia) with
  immediate preview; notifications toggle with rationale + denial guidance;
  invite status line that reflects the real gate state (locked/unlocked/
  revoked), never a permanent "not confirmed" dead label; diagnostics
  section (§2.6).
- **Navigation:** real icons, selected states, content descriptions;
  bottom bar hidden on reader; categories either properly wired or deleted
  (M6); deep-links (notification, FCM) resolve by `app_key` and fall back
  to the not-found screen, never to a dead route.
- **Empty/error/offline states (designed, not blank):** feed empty
  (first run, seeding in progress — with progress, not a dead message);
  feed offline (cached, with "showing cached" label); search zero results
  (with the query echoed and a clear action); market empty (bundled
  snapshot vs truly no data, distinguished); bookmarks empty (with a hint
  to save from a reader); article not found (P1-07); sync not configured
  (honest, no TODO, with what-to-do-next text).

---

## 5. Pilot test checklist (exact, phone-testable)

Run on a real phone (Yasin's S24 FE or equivalent), on the M9 release
candidate. Record pass/fail + evidence (screenshot or note) per item.
"Pass" means the stated behaviour, not "looks fine".

**Install & first run**
- T1 Install from APK (via My Files → Downloads, Package Installer — not
  via Muse attachment preview, which cannot install APKs). App launches,
  no crash, launcher icon is final (not placeholder).
- T2 Fresh install, airplane mode ON: feed opens from bundled seed without
  tapping Sync; 281 items present (or the current seed count, recorded);
  no empty-feed dead end; a "seeding…" progress state appears only if
  seeding is genuinely still running, then resolves by itself.
- T3 Kill app mid-first-run, relaunch: feed completes; no permanent
  partial state; counts match T2.
- T4 Invite gate: fresh install shows code entry before feed; correct
  pilot code unlocks feed; wrong code shows a fixed error (no hint);
  after unlock, relaunch stays unlocked; revoked code (lab list) re-locks
  on next sync with an honest message (only after M7; before M7, record
  gate as "not yet implemented" — do not fake a pass).

**Feed, tabs, articles**
- F1 Swipe tabs left/right: smooth, no jank that blocks reading; tab tap
  animates to the page; tab selection follows swipe.
- F2 Open 10 articles including: a slash-id article, a `?`-id article, a
  `#`-id article, a short card, and the newest article in a section —
  all open to the correct article, hero is newest by date in each section.
- F3 Section order stable: sync (lab edge with a new article) does not
  reorder tabs or change heroes while watching; per-section scroll
  position survives swiping two sections away and back.
- F4 Categories: either opens from a visible entry and filters correctly
  (M6 option A) or is provably absent with no UI promising it (option B).
- F5 Reader: points + why-matters shown when present; short card shows
  summary or body, not both duplicated; back arrow works; bookmark toggle
  at top works; source button opens browser/chooser and never crashes on
  a malformed URL (test with a lab item carrying an unhandled scheme).
- F6 Bookmarks: save from reader top → Bookmarks list shows it without
  restart; remove → list updates without restart; orphan/retracted
  bookmark shows flagged row, not silent disappearance.
- F7 Search: search a word that appears only in a body (not title/summary)
  → found; search `%` → does not return everything; search with zero
  results → designed empty state with query echoed + clear; clear query →
  tabs return to the exact previous page; typing does not fire a query
  per keystroke visibly (debounce).
- F8 Font/theme: Settings slider changes feed text immediately (not only
  reader); reader font control works; light → dark → sepia all render
  paper colours correctly (including bottom bar + status bar); slider
  value + preview visible; leaving Settings mid-drag does not lose the
  value.
- F9 RTL + Persian: feed, reader, market, and settings all render RTL
  correctly; dates are Persian-formatted (not raw mixed strings); numbers
  are Persian digits with separators.

**Market**
- K1 Airplane mode: market shows bundled snapshot with stale label
  (date + age), all fields or "—" for nulls, computed formula values.
- K2 Scroll reaches the bottom of the market screen; formulas show
  computed values, not just names/definitions.
- K3 Lab edge with a newer snapshot → sync → board updates; latest =
  max timestamp, not last array element.

**Sync, notifications, offline**
- S1 Airplane mode, tap Sync: reports offline honestly, feed stays cached,
  no crash, no fake articles.
- S2 Edge not configured (lab build with empty BASE_URL): sync reports
  not-configured honestly, no TODO text, no raw exception, feed stays cached.
- S3 Edge configured (lab): sync fetches only changed items (manifest
  hash unchanged → zero article fetches, reported as "up to date");
  edited body on edge → updated in app after sync; retracted item →
  leaves feed, bookmark flagged.
- S4 Notifications OFF: sync finding new items (lab) produces no
  notification; permission denied → in-app guidance appears, app keeps
  working. Notifications ON: new items notify with app icon, correct
  deep-links (including slash-id items), summary notification does not
  overwrite an article notification.
- S5 15-minute poll: with edge configured, a new lab item appears via
  background sync + notification (or an honest "poll ran, nothing new"
  diagnostics entry); with edge not configured, no pointless periodic
  work is observable beyond the scheduled worker itself.
- S6 FCM (only if §2.8 decision (2) is "yes"): data message with
  `app_key` → notification → tap opens correct article, including for a
  short card. If decision is "no", record FCM as "scaffold only, by
  decision" — do not fake a pass.

**Bridge / control (lab, not phone)**
- B1 Captioned test post stages with caption + media note; text+photo
  keeps photo note; media-only post still skipped honestly.
- B2 Edited post updates queued payload if unsent; if sent, creates an
  edit record for operator decision (no silent overwrite, no silent drop).
- B3 >4096-char item sends as numbered parts with all message ids recorded.
- B4 Crash-window simulation (send recorded as `sending`, process killed,
  next pass) does not double-send.
- B5 `queue show` shows body + URL + provenance; `health` reports market
  age, shrinkage, lock age, and publish-report (not only dryrun); corrupt
  edge JSON reports unhealthy, never crashes health.
- B6 Cadence: third schedule attempt on the same Tehran date fails with a
  named error; channel-id allow-list rejects a DM/unlisted id.

**Polish / accessibility**
- P-A TalkBack walkthrough: feed row announces as article with open
  action; bookmark toggle announces state; sync status announced.
- P-B Every screen has a designed empty state (feed, search zero results,
  bookmarks empty, market empty, article not found) — no blank screens.
- P-C No NYT name/logo anywhere (re-grep); no TODO/placeholder text
  visible to users; no raw exceptions in UI; launcher icon final.

**Freeze-lift rehearsal (M9, dry-run only)**
- R1 §2.8 checklist walked item by item; Yasin's decision recorded per
  item; lift-order draft written naming exact scope and order.
- R2 Zero live changes verified after M9: no cron/bot/channel state
  changed by the pilot or the rehearsal itself.

---

## 6. What this plan deliberately does NOT promise

- No release date is promised here: milestones are ordered, not dated.
  Dates are set by Yasin after M1–M2 land, when the remaining unknowns
  (edge URL, Firebase decision, invite reconciliation) are answered.
- No Play Store release is promised: distribution stays direct APK until
  Yasin decides on signing/Play (§2.8 item 7).
- No live unfreeze is promised by app readiness alone: §2.8 requires a
  separate explicit order naming scope.
- No new feature beyond this register is added in vNext without Yasin's
  approval: pull-to-refresh, share, related articles, images/thumbnails,
  and app check-in telemetry are explicitly deferred or decision-gated
  above, not silently built.

---

*End of plan. Lab only. Next step on Yasin's approval: implement M1, then
re-test on a phone against M1 acceptance before starting M2.*
