# Phase 1 — Update Engine: implementation report (LAB ONLY)

Date: 2026-10-25 (Asia/Tehran). Plan: `docs/big-update-plan.md` §3 + Phase 1.
Status: IMPLEMENTED IN LAB, NOT BUILT, NOT PUSHED, NOT DISTRIBUTED.
No Gradle build and no cloud build were run in this pass (stated plainly
below); no live cron, bot, channel, or freeze state was touched.

## What was built

### 1. Kotlin engine (app side)

- `app/src/main/java/com/ecojournal/app/data/UpdateEngine.kt` (new):
  manifest-v3 parser (`ManifestParser`, version must be 3, null on any
  structural problem, never throws); pure delta planner (`DeltaPlanner`,
  unchanged hash never refetched, retracted local row becomes a
  tombstone, article item without a file is corrupt, shorts plan with
  `requiresFile = false`); section-hash up-to-date rule (blank manifest
  hash never counts as up to date; force-refresh bypasses stored hashes
  but never re-downloads an unchanged body); exponential backoff shape
  (`SyncBackoff`: 15 min doubling to a 4 h cap — runtime backoff stays
  WorkManager's, this object is the single documented shape); join-key
  derivation (`JoinKeys.storyIdFor`, byte-identical to the generator).
- `app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt`:
  `sync(forceRefresh = false)` fetches `manifest.json` FIRST; parseable
  v3 takes the per-section path (`syncV3`), a missing manifest falls
  back to the legacy index path verbatim (`syncLegacy`), an unparseable
  manifest reports `bad_manifest` and touches nothing. Per-section
  transactions (articles, shorts, market each in their own transaction;
  a section hash is stored only on that section's complete success).
  Article files fetch in parallel batches of 4. A failed or corrupt
  section never empties its cache (only upserts; no deletes except the
  pre-existing formulas-replace-on-success rule). `syncOnly(section,
  forceRefresh = true)` applies one section (unknown key reports
  `unknown_section`). `sectionStates()` returns the per-section
  Diagnostics snapshot (hash present/absent only, never the value).
  New fixed codes: `bad_manifest`, `bad_market`, `invite_fetch`,
  `unknown_section` (plus the existing set). The invite re-check rule
  is unchanged: only a successfully fetched list can re-lock, cached
  rows are never deleted.
- `app/src/main/java/com/ecojournal/app/data/AppDatabase.kt` + `DatabaseProvider.kt`:
  Room version 4, `MIGRATION_3_4` (additive only: new `join_keys`
  table `appKey` / `guid` / `storyId` / `channelMessageId`; no existing
  table altered), DAO `upsertJoinKeys`, `joinKeyCount`,
  `joinKeyByAppKey`, `articleKindCount(kind)`.
- `app/src/main/java/com/ecojournal/app/data/ArticleParsing.kt`:
  shorts now carry the explicit marker category `uncategorised` /
  `دسته‌بندی‌نشده` (read from the object when shipped, otherwise the
  explicit default — never a silent `general`), and the content hash is
  recomputed over the same resolved category, so it stays byte-identical
  to the generator. Consequence, correct not a bug: the first v3 sync
  refetches all 32 shorts because their hash changed with the category.
- `app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt`
  + `app/src/main/res/values/strings.xml`: per-section Diagnostics for
  all 11 sections (state, count, last sync, last attempt, error code,
  missing count), a per-section force-refresh button (re-reads the
  manifest, re-checks only that section, never bypasses the invite
  gate), states reloaded after every sync action, and copy-diagnostics
  copies exactly the lines shown (global + per-section; no hash value,
  no content, no code, no token).
- `SyncWorker` and `FeedScreen` are untouched and keep compiling:
  `sync()` keeps its default argument, and `SyncResult.Ok` / `Partial`
  gained an optional `sections` list (default empty).

### 2. Edge side (lab generator + publish command)

- `edge/generate_edge.py`: additionally emits `manifest.json` v3 and
  `join_keys.json` v1; `index.json` v2 is still emitted unchanged for
  backward compatibility. Section hashes are SHA-256 over canonical
  JSON (sort_keys, compact separators) of the 4-field item view
  (`app_key`, `content_hash`, `updated_at_epoch`, `status`, sorted by
  app_key) for articles/shorts, and of the exact written content for
  market / channel_refs / invites / revocations. Sections `bundles`,
  `radar`, `nightly`, `voices` report `not_yet_published` (count 0) and
  `special_issues` reports `gated_frozen` (count 0) — honest zero
  states, never invented content.
- Join keys: `story_id = "story-" + first 20 hex of SHA-256(guid)`;
  articles use `item_guid` (falling back to `extract_id` only when the
  guid is empty, counted in the report); shorts use their `draft-N` id.
  `channel_message_id` is null on all 281 entries today — verified
  against the real snapshot and the production database (0 non-null in
  `article_extracts`), and it is never fabricated; only the bridge
  staging path (Phase 3) can populate it from a real channel post.
  Production-side residual, specified but NOT done here (out of lab
  scope): the production queue's `story_key` (headline hash, subject
  to transliteration drift) must be superseded by headline
  normalisation before hashing; on the edge/app side it is already
  superseded by guid-derived `story_id`.
- `edge/publish_edge.py` (new, lab only): regenerate + verify
  (recompute every section hash, join coverage, counts vs
  `GENERATION_REPORT.json`, hashes-only invite files, no secret-like
  patterns) + `--dry-run` (report only, nothing changed in git) or real
  mode (commit edge output only, push). Only `--dry-run` was run in
  this pass. No push happened.
- Full edge notes: `docs/phase1-edge-notes.md`.

### 3. Cron reconciliation

Full document: `docs/cron-reconciliation.md` (read-only pass — the
only cron tool used was the read-only `cron.list`; no cron was
enabled, disabled, added, removed, or run; live listing corroborated
by definition files and the dated freeze log, never a single status
call, per the AGENTS.md stale-snapshot lesson).

- Correction to the plan's framing: the inventory is **59 definition
  files** (46 recurring + 13 runonce). The plan's "46 vs 24" is almost
  right but miscounts by 2: at freeze time the recurring inventory
  was **44**, because the special-issues pair was created 2026-10-25,
  the day AFTER the freeze. Arithmetic that closes exactly: 44 = 22
  ACTIVE-at-freeze + 22 already-off-before-freeze; plus 2 future
  runonce = the 24 freeze actions; plus 2 post-freeze special-issues
  definitions = 46 recurring today; plus 11 past runonce = 59 files.
- Verdicts: ACTIVE-at-freeze 24 (22 recurring + 2 runonce:
  `eco-lowburn-restore` 2026-10-26 04:10 and `eco-ml-training-v0`
  2026-10-28); DISABLED-at-freeze 22 (18 war-room-only desk crons +
  2 ML briefs + blog refresh + site watchdog); SEPARATE-FREEZE 2
  (special-issues pair, own decision, never part of the 24);
  STALE 11 (past runonce, never re-enable); DUPLICATE 0 (no two ids
  are the same job); UNKNOWN 0 (every file has a firm verdict).
- Live check this pass: every goal-2 ECO JOURNAL schedule is
  currently `enabled: false`, including both special-issues crons.
  One discrepancy found by corroboration: 8 past runonce definition
  files still say `enabled: true` in frontmatter but the live
  scheduler no longer lists them (fired long ago, purged) — they are
  historical copies, not live schedules.
- Lift hazards documented: the flush/catchup overlap (A1 + A3) must
  have its flock guard re-verified on BOTH paths before joint
  re-enable (the 2026-10-23 double-post shape); the Monday 04:10
  low-burn restore stays DISABLED unless Yasin explicitly re-enables
  it; no orphan cron is re-enabled by accident; special-issues crons
  stay frozen under their separate decision.
- Feed-count reconciliation (73 vs 23) — RESOLVED: the numbers count
  different sets. 73 = the war-room source registry
  (`sources_registry.json`, 2026-10-22, array length independently
  counted). 23 = the wire-fast-poll runtime set (`23/23 feeds, 0
  failed`, last pre-freeze run 2026-10-24 11:00). There is no 96-feed
  total and no missing 50 feeds; two further sets were counted so
  they are not mistaken for either number (fetcher list 42, red-alert
  desk list 18). One explicitly open sub-item: whether the 23 wire
  ids are a subset by id of the 73 registry ids was NOT verified
  (the wire list lives in runtime code/DB state) — the app must carry
  both numbers with labels ("registry: 73" / "wire poll: 23"), never
  a merged total, until that diff is done.

## Verification performed (no build)

- Brace/paren balance: balanced in all six touched Kotlin files;
  all new symbols referenced exist; all 21 new string resources exist
  and `strings.xml` parses as XML; Python files compile
  (`py_compile` clean).
- Delta-logic simulation over the REAL edge snapshot (281 entries):
  E2 unchanged → 0 article fetches, 0 short fetches, 0 corrupt
  (manifest-only sync); E3 one changed article → exactly that one
  article fetched, shorts untouched; E5 retracted item → exactly one
  tombstone, zero fetches (bookmark row survives: only status flips).
  Section hashes recompute deterministically.
- Generator run to a temp dir (deleted afterwards): manifest v3
  parses; counts 249 / 32 / 281 / 164 / 23; articles and shorts hashes
  recompute identically; market and channel_refs hashes recompute
  identically; join coverage 281/281; manifest `story_id` equals
  `join_keys` `story_id` for all 281 items; shorts category is
  `uncategorised` in short_cards, index, and manifest; adding
  `story_id` to shorts manifest items did not change section hashes
  (the hash covers the 4-field view only). `publish_edge.py
  --dry-run`: all checks PASS, nothing changed in git.
- Short-hash model check: recomputing the old short hash (category
  `general`) reproduces the current edge index value exactly, and the
  new hash (`uncategorised`) differs — confirming the Kotlin and
  Python hash implementations agree.

## E-gate status (honest)

- E1 (airplane-mode last-good cache): IMPLEMENTED (no code path in
  syncV3 deletes cached rows; failures leave the cache untouched).
  Phone test still pending — only Yasin on a real phone can pass it.
- E2 (manifest-only sync when unchanged): IMPLEMENTED + SIMULATED
  over the real snapshot (0 fetches). Phone test pending.
- E3 (one changed article + one changed market snapshot update
  exactly those sections): IMPLEMENTED + SIMULATED for the article
  half; market half follows the same hash-gate code path. Phone test
  pending.
- E4 (edge unreachable → per-section failed, cache kept, backoff,
  manual force-refresh retries immediately): IMPLEMENTED (per-section
  failed states, WorkManager exponential backoff, Diagnostics
  force-refresh). Phone test pending.
- E5 (retracted item leaves feed/search, bookmark survives):
  IMPLEMENTED (tombstone rows, bookmark join untouched) + SIMULATED.
  Phone test pending.
- E6 (per-section Diagnostics + copy exactly the lines shown):
  IMPLEMENTED in code. Phone test pending.
- E7 (bridge-staged channel post appears in the edge index with
  non-NULL `channel_message_id` and correct `format_type`, syncs as a
  typed card): NOT PASSABLE YET. The fields and join plumbing exist,
  but the bridge staging merge is Phase 3 work and the test channel
  does not exist (only Yasin can create it; the bot cannot).
  `channel_message_id` is null on all 281 entries today.
- E8 (shared signature; engine APK updates over the previous
  shared-signed APK without uninstall): NOT RUN in this pass — no
  Gradle/cloud build was run, no APK was produced or distributed.
  The shared-signing workflow from 2026-10-25 is unchanged and must
  be exercised by the parent's cloud build before any phone test of
  this phase.

Conclusion for the exit gate: E2's manifest-only rule is implemented
and simulated, but the phase is NOT phone-verified. Per the plan, if
manifest-only sync fails on the phone, Phases 3 and 5 must NOT start.
Phase 2 may proceed in the lab; Phases 3 and 5 stay gated on the
phone test + E8 build.

## What remains for Phase 2 (and beyond)

- Cloud build of this phase (parent) + Yasin's phone test of E1–E6.
- Phase 2: typed channel surfaces (format badges/filters, War Room,
  SITREP, urgent rail, radar, bundles) on top of this engine.
- Phase 3: bridge staging merge into the publish-edge command + E7
  (needs Yasin's test channel + allow-list).
- Residual, documented not hidden: 45 full articles carry category
  `general` (unknown topic in data_core); only shorts were moved to
  the explicit `uncategorised` marker in this pass, per plan §3.1.7.
  Deciding the article-side marker is a Phase 2 editorial call.
- Residual: `SyncBackoff` is a documented shape only; runtime backoff
  stays WorkManager's (no tight loop exists anywhere in this code).

## Constraints honoured

Lab repo only. Production databases read-only (mode=ro). No .env
opened. No token, invite code, or secret printed or embedded anywhere.
No push to GitHub. No APK distributed. No FCM activation. No test
channel created. No special issues published. Freeze untouched.
Work left UNCOMMITTED for the parent to review (local commit only if
coherent — the parent owns the push decision).
