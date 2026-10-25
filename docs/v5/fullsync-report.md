# Full-sync data plane — S1–S4 report (2026-10-26, Tehran ~03:30)

> Data-plane only. No app UI code changed, no APK built, no cron
> created, no channel post sent, no content gate changed, no
> force-push. The edge changes below are COMMITTED LOCALLY ONLY —
> the parent pushes (see §7). Live Pages still serves the old
> manifest until that push; §7 gives the exact command and what
> live verification must confirm after it.

## 0. Verified baseline (re-verified, not assumed)

- Live Pages manifest at run time: generated `2026-10-25T23:14:25Z`,
  articles 249 / shorts 32 / market 164 / channel_refs 23 /
  invites 10 / revocations 4, bundles/radar/nightly/voices 0
  (`not_yet_published`), special_issues 0 (`gated_frozen`), NO
  `channel_typed` section. Local `edge/out` matched live exactly.
- Real source paths (the top-level `econ-news-blog/*.db` files are
  0-byte stubs — the live stores are under `editorial/`):
  - published posts: `econ-news-blog/editorial/redalert.db`,
    table `posted` (id, ts, day, fmt, story_key, title, source,
    message_id), 583 rows, 2026-10-22 → 2026-10-26.
  - night-held queue: same DB, table `queue`, 16 rows, all with
    `release_at 2026-10-26T08:00+03:30` — read ONLY for the leak
    check, never as generator input.
  - war events: `econ-news-blog/editorial/war_events.db`, table
    `events` (story_key, fmt, score, front, ts, status), 1092 rows.
  - market/articles: `econ-news-blog/data_core/data_core.db`
    (`prices` 164 rows, `article_extracts`, `channel_posts` 23).
- Baseline `publish_edge.py --verify-only` on the old edge: PASS.

## 1. S1 — channel_typed (PUBLISHED posts only)

Generator: `edge/generate_channel_typed.py` (+ `edge/fullsync_lib.py`).

- Input: `posted` ONLY. Golden rule enforced structurally: the
  generator never opens the `queue` table, so a held item (which
  has no `posted` row) cannot enter on any run, at any hour.
  Leak check after generation: 0 of the 16 held queue headlines
  appear in `channel_typed.json`.
- Output: `edge/out/channel_typed.json`, 560 items, 326,500 bytes.
  By format: WIRE 297, VERIFY 147, MARKET 53, WATCH 25, GEOPOL 21,
  FLASH 17. (Posted rows 583 − 23 SITREP excluded = 560; there are
  no duplicate message_ids once the NULL-mid group is handled —
  see below.)
- Identity (Phase 2/3 rule): guid `channel-<message_id>`,
  app_key `a-sha256(guid)[:20]`, story_id `story-sha256(guid)[:20]`,
  status `posted`, content_hash = sha256 over the full item minus
  itself (the rule `publish_edge.py` recomputes).
- urgency_score: latest `war_events` score for the same story_key
  (504 items covered, 56 null — null is honest: the app renders
  null-score items feed-only, except FLASH which is INSTANT by
  format under `NightLaw.kt`). No score invented.
- No chain objects: `posted` carries no staged/gate timestamps,
  and inventing them would fail audit. `publish_edge.py` treats a
  missing chain as a legitimate skip, and join_keys coverage only
  requires chain-carrying items — so no join_keys rows were added.
- Exclusions, all counted in `edge/fullsync-channel-report.json`:
  - 23 SITREP rows: excluded because the app drops an empty SITREP
    (`ChannelParsing.kt` BAD_SITREP_EMPTY) and no retrievable
    published SITREP body exists anywhere (`format_log` stores
    only message_id/date). Emitting a body would be invented
    content. This is a real coverage gap for the SITREP format,
    reported here, not hidden.
  - VERIFY items carry no verdict fields and MARKET items no
    prices_json (`posted` has neither): the app records a
    FORMAT_INCOMPLETE anomaly but still renders them.
  - 18 of the 560 items have `channel_message_id` null: the 19
    earliest `posted` rows (2026-10-22 morning) predate message-id
    tracking. They are keyed by story_key
    (`channel-nomid-<story_key>`) instead of collapsing onto one
    `channel-None` guid — the first draft of this generator made
    exactly that collapsing mistake (543 items) and it was caught
    and fixed before commit; the app parses null message_ids
    cleanly (`optLongOrNull`).
- Retractions: no retraction table exists. A post that disappears
  from `posted` disappears from the next manifest, and the app's
  delta planner tombstones it. That path is untested live (no
  post has disappeared yet).

## 2. S3 — radar (G1, plan recommendations applied)

Generator: `edge/generate_radar.py`. Output: `edge/out/radar.json`,
10 signals (cap 50, 24h window to 2026-10-26 ~03:26 Tehran).

- By layer: L1 flash 9, L2 watch 1, L3 geopol 0. By severity:
  high 9, medium 1, low 0. Window events 80; excluded: 14
  non-clash (10 market + 4 wire), 56 clash-format events whose
  story has NO posted row (unpublished — correctly gated out,
  including all 40 staged-status window events that lack a post).
- Delegated decisions (each reversible by Yasin's single word):
  - D1 freshness: 15-min app-poll floor + 30-min edge publish.
  - D2 clash = flash + watch + geopol; war-market ONLY with a
    strike — `war_events` has no strike flag, so market events are
    excluded and counted, not re-labelled.
  - D3 automatic edge publish: yes (via the S2 script).
  - D4 unconfirmed signals: NO — published stories only.
  - D5 (bar vs ticker) is UI and out of this data-plane task.
- Shape notes: `signal_id` = war story_key; `story_id` =
  the linked channel item's guid-derived story_id (so WarRoom
  tap-to-story resolves to the real card); `linked_app_key` =
  that item's app_key (verified: all 10 resolve into
  `channel_typed.json`); title = published channel title (verified:
  all 10 titles are `posted` titles, never raw event titles);
  `matched_topics` = front labels with `#` stripped, never raw
  keywords; severity from the latest window event score
  (≥85 high / 60–84 medium / <60 low). Layer comes from the event
  format, title from the post — so a flash event followed up by a
  verify post surfaces under the verify post's title at L1; that
  is the designed join, not a mismatch.
- No `war_room` block is emitted: the crisis level is a computed
  global, not derivable from the 24h signal window alone, and a
  guessed level would be invented content. The app falls back to
  the latest SITREP level when `war_room` is absent.

## 3. S2 — scheduled regeneration

Script: `edge/regenerate_fullsync.py`. Schedule spec (for the
parent to create — NOT created here):
`edge/fullsync_schedule.md`.

- Cadence chosen: every 30 min, 08:00–23:30 Asia/Tehran, plus
  hourly 00:00–07:30. Night runs are leak-safe by construction
  (published-only filter re-applied every run) and exist so night
  instant (≥85) posts reach the app; the slower night cadence
  reflects near-zero night volume. A single `*/30 * * * *` line
  is also safe if the cron tool cannot express two lines.
- Pipeline: backup → `generate_edge.py` into a temp dir →
  copy core → S1+S3 overlays → `publish_edge --verify-only` gate
  (on FAIL, canonical out is restored, exit 1, nothing changed).
  Default: no commit, no push. `--auto-publish` commits + normal
  pushes ONLY when the report says changed (never force).
- TRAP (documented in the script and the schedule): NEVER run
  `publish_edge.py --real` on a full-sync edge — it regenerates a
  Phase 1 edge and resets channel_typed/radar to
  `not_yet_published`, wiping S1/S3. `--verify-only` is the only
  valid publish_edge mode here.
- Test run (this session): exit 0, verify PASS, `changed: false`
  — the Phase 1 core regenerated IDENTICALLY (articles 249 hash
  `911464c59bbd…`, shorts 32, market 164, refs 23, invites 10,
  revocations 4, all hashes unchanged), proving both that the
  core sources are stable and that the script is idempotent.
  Report: `edge/fullsync-regenerate-report.json`.
- The two health-rebuild regressions found during that test
  (shorts file link, invites/revocations counts) were fixed in
  `fullsync_lib.py` and re-verified; health now matches the
  original values for every untouched section.

## 4. S4 — nightly: HELD (the one gated piece)

Verdict file: `edge/fullsync-nightly-verdict.json`. Section stays
`not_yet_published`, count 0, no `nightly.json` written, no
edition invented for any day, no backfill.

Why: U4 cannot be settled conservatively. The v4 nightly spec
(`docs/big-update/phases4-5-market-nightly-spec.md` §5.3) marks
all three pins as SPEC DECISIONS awaiting editorial confirmation,
and `edge/generate_nightly.py` refuses real-data selection for
exactly this reason. Verified read-only 2026-10-26: NO tier_a
column, NO brief-score field, NO ownrewrite-score field exists in
`article_extracts`, `items`, or `channel_posts`. Substituting the
Law-15 urgency_score for the brief score would be a category error
the spec explicitly forbids (never run two meanings of ≥70).
Additionally, full-article freshness (≥60% vs the channel post
body, enforced by `publish_edge.py`) is unauditable: no store
holds channel post bodies (`posted` has headlines only).

Exact decision needed (any one of these unblocks it, but the
decision must name a real source, not just confirm the spec's
proposed pins, which point at data that does not exist):
1. Tier-A: which stored label marks Tier-A, or an explicit
   per-edition editorial list (the `--selection` file the nightly
   generator expects).
2. Brief score: which stored score is the brief score, or
   editorial per-edition brief selection.
3. Ownrewrite: which stored score is the ownrewrite score, or
   editorial per-edition candidate naming.

## 5. Source-freshness gaps regeneration CANNOT fix

- MARKET (the big one): `data_core.prices` holds exactly the 164
  snapshots already on the edge; latest snapshot
  `cycle-20261024-0600`, `2026-10-24T09:59+03:30`. Regeneration
  copies the table verbatim, so the market section will stay
  ~2 days stale until the market ingest job itself is fixed.
  This is a pipeline gap, not an edge gap — do not promise fresh
  market data from the schedule alone.
- Channel posts / radar: the edge can only mirror what the
  channel and war-room pipelines already produced. A down cycle
  shows up as an honest count drop, never as invented content.
- SITREP format: 23 published SITREPs have no retrievable body,
  so the app's channel surface will show no SITREP cards until a
  body store is wired (separate work; the app would drop empty
  SITREPs anyway).

## 6. App code change needed? NO — data-only publication suffices

Verified against the 5.1 app code (no app files touched):

- `UpdateEngine.kt` `SyncSections.ALL` + `FETCHABLE` already list
  `channel_typed` and `radar`; `ManifestParser.parse` (lines
  ~152–204) reads every section in ALL generically, and skips
  manifest items with a blank `id` — the new manifest items all
  carry `id` (guid / signal_id), so they parse.
- `EdgeRepository.kt` `syncChannelTypedSection` (~1440): returns
  UNKNOWN only if the section is absent; with the section present
  it fetches `section.file` (here `channel_typed.json`), parses
  via `parseChannelItem`, upserts into `channelDao`, and releases
  held items. It has NO `not_yet_published` guard to trip over —
  the old absence was the only blocker, and it is now gone.
  Note: `channel_typed` is in `REQUIRED` (UpdateEngine.kt), so
  once present it defines the global sync clock — intended, and
  safe because the section verifies and the file exists.
- `EdgeRepository.kt` radar sync (~1712): the v5 P4 guard
  (unpublished/gated → no fetch; published + file → fetch;
  known-404 pin) is satisfied by status `published` + file
  `radar.json`. A published section with no file would fail as
  `missing_file` — not our case.
- `ChannelParsing.kt` `parseChannelItem` (~79): all 7 posted
  formats are known (`Phase2Domain.kt` ~82–88); headline,
  status `posted`, and `published_at_epoch > 0` hold for all 560
  items; SITREP-empty is the only drop rule and SITREPs were
  excluded at generation. `parseRadarSignal` (~296): graded-shape
  signals without `story_id` are dropped — all 10 signals carry
  `story_id` + `linked_app_key`.
- `AppDatabase.kt` `visibleChannelFlow` (~606–613): excludes
  `retracted` rows and unreleased held rows by construction, so
  the new items render under the existing night-law rules
  (`NightLaw.kt`: FLASH instant regardless of score; night ≥85
  instant, 70–84 held until 08:00; null score → feed-only).
- One deliberate tooling edit (NOT app code): `publish_edge.py`
  now also accepts the full-sync generator string
  `edge/fullsync.py (S1/S2/S3, 2026-10-26)`, so verification
  fails closed on unattributed edges instead of blessing them.
  Without this, verify would reject the new manifest's generator
  field; the alternative (lying with the Phase 1 string) was
  rejected as dishonest.

## 7. Before / after + publish step (parent)

| Section | Before (live + local) | After (local, committed, NOT pushed) |
|---|---|---|
| articles | 249, `911464c59bbd…` | 249, unchanged |
| shorts | 32, `f82217071091…` | 32, unchanged |
| market | 164, `01f08b3914ad…` | 164, unchanged (stale source, §5) |
| channel_refs | 23, `b3141cc140a5…` | 23, unchanged |
| invites / revocations | 10 / 4 | 10 / 4, unchanged |
| channel_typed | ABSENT | 560, `published`, `channel_typed.json`, hash `5d94462af28a…`, file sha `1a70e871…` |
| radar | 0, `not_yet_published` | 10, `published`, `radar.json`, hash `3c913fbe014e…`, file sha `a67b8414…` |
| bundles / nightly / voices | 0, `not_yet_published` | 0, unchanged |
| special_issues | 0, `gated_frozen` | 0, unchanged |

Local gate: `publish_edge.py --verify-only --out edge/out` → PASS
(full output: channel_typed + radar hashes recompute, content
hashes recompute, join_keys 281, report counts match).

Publish (parent, one step — NOT done here per the task division):
```bash
cd ~/workspace/goals/eco-journal/app && git push origin main
```
After the push, live verification MUST confirm (Pages deploy lag
applies): `https://syfazel.github.io/eco-journal-app/manifest.json`
shows `channel_typed` count 560 + `radar` count 10 with the hashes
above; `channel_typed.json` and `radar.json` fetch with file shas
`1a70e871…` / `a67b8414…`; `health.json` no longer lists
`radar.json` in `expected_absent_files`. Until then, live = old
manifest (verified at session start).

## 8. What was NOT done (scope guards honoured)

No app UI/sync code touched; no APK built; no cron created; no
channel post sent or edited; no post store written to (all DB
access mode=ro); voices + special issues untouched; no night-law,
bar, gate, or cadence behaviour changed in the app; no FCM/Play;
no domain steps; no force-push (and no push at all — parent's
step); no Bale work; no invented content (SITREP bodies, scores,
war_room level, nightly edition all withheld rather than faked);
no schema changes (no migration needed — the new files use the
existing v3 shapes).
