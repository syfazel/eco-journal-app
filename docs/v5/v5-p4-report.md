# ECO JOURNAL — Version 5, Phase P4 Report: sections + sync/diagnostics rebuild

Status: **SECTIONS + SYNC/DIAGNOSTICS REBUILD ONLY — no build, no
push, no publish, no live change.** Lab commit only (see §6).
Task order: P4 sections + sync/diagnostics rebuild, per
`docs/v5/v5-program-plan.md` §2–§3, on top of P3 commit
`e1a6641`, under Yasin's full go-ahead of 2026-10-26 ~01:36
Tehran (all open decisions delegated to Muse).

## 1. Gating section — P1 protocol result consumed FIRST; G1 REMAINS OPEN

**Gate G1 is still open. Defect A1 (feed flashes content for
~1 second on refresh, then jumps back to the empty page) is
NOT identified by this phase, and nothing in this phase
fixes it, works around it, or uses it as a rationale.**
No device run was available in this lab run (no phone in
the lab, no build by task order — the same position as P3),
so the P1 §4 device-reproduction protocol could not be
executed and **no row of the P1 §3 decision table matched**.
P4's scope therefore follows the plan default, not a
protocol row: the Phase 4 items P3 §8 named (status-aware
bundles/radar sync, per-section success clocks, global clock
over REQUIRED sections only, edge contract test, and the
section-screen rebuilds below). P4 does not re-litigate P3
presentation: no feed branch, feed query, reader, digest, or
filter/search-empty language was touched (FeedScreen,
ArticleScreen, ArticleCard, ChannelCard, ChannelReaderScreen,
SyncCompact are all absent from this phase's diff — the
verifier checks this).

The P1 §3 decision table and §4 protocol remain the gating
authority, carried in full in `docs/v5/v5-p1-report.md` §3–§4
and `docs/v5/v5-p3-report.md` §2. When a device run happens,
its row decides follow-up scope (recreation-safe branch,
bounded/partial-apply sync, gate messaging, or a
below-app-layer investigation) — that follow-up is Phase 5
device work, not something P4 pre-empts. The Phase 3
feed-stays gate (G3) still cannot pass without that device
evidence.

## 2. The A2 fix (proven mechanism, now fixed in code)

A2, as proven in Phase 0 and re-confirmed live in P1:
bundles/radar sections with status `not_yet_published`,
count 0, no `file` field, and blank hash were fetched as
`bundles.json` / `radar.json` → HTTP 404 → FAILED → the
global `last_sync_at` could never advance. The fix mirrors
the nightly guard that already worked, in both sections:

- **Status guard first** (`EdgeRepository.kt:1275` bundles,
  `:1404` radar, mirroring nightly at `:1541`): status
  `not_yet_published` or `gated_frozen` records
  `NOT_YET_PUBLISHED` with the manifest count and **no fetch
  is attempted**. The live states therefore can never 404
  again.
- **Blank-file guard second** (`:1282` bundles, `:1411`
  radar): a section that claims to be fetchable but carries
  no `file` field fails as `missing_file` without fetching
  a guessed default. This is the edge-contract violation
  made visible on the app side (fetchable ⇒ file exists);
  the generator side is checked by the verifier (§5).
- **Known-404 guard third** (`:1298` bundles, `:1419`
  radar): a bundles/radar 404 pins the manifest hash
  (`SyncSections.failedHashKey`, `UpdateEngine.kt:84`).
  A later sync — including a manual retry, which re-reads
  the manifest — with an UNCHANGED hash skips the fetch
  entirely: section error stays `http_404` (so section
  screens keep their 404 reason line), while the P1 trace
  code reads `http_404_unchanged`, so Diagnostics proves
  the fetch was skipped rather than repeated. A changed
  manifest hash unlocks a fresh fetch; a successful fetch
  clears the pin (`:1363` bundles, `:1502` radar).
  Honest limit, stated plainly: if the edge published the
  missing file WITHOUT changing the manifest hash, the
  guard would keep skipping until the hash changed. That
  is the task's own rule ("without a changed manifest"),
  and the Bundles reason line says exactly that (§3).

## 3. What was rebuilt

### (a) Global clock over REQUIRED sections only + per-section success clocks
- `UpdateEngine.kt:73` — `SyncSections.REQUIRED` is exactly
  the plan's five: articles, shorts, market, channel_refs,
  channel_typed. Everything else (bundles, radar, nightly,
  voices, special_issues, invites, revocations) is optional
  for the global clock.
- `EdgeRepository.kt:423` — `anyIncomplete` is now
  `anyRequiredIncomplete`, set only by required-section
  FAILED/PARTIAL outcomes: articles (`:535`), shorts
  (`:655`), market (`:702`, `:771`, `:825`), channel_refs
  (`:881`), and the four `.also` blocks gated on
  `it.key in SyncSections.REQUIRED` (`:979`, `:989`,
  `:999`, `:1014` — channel_typed counts, bundles/radar/
  nightly do not). Invites/revocations failures no longer
  set it at all (their blocks keep the failed section state
  and skip the re-check exactly as before).
- `EdgeRepository.kt:1072` — `last_sync_at` advances when
  every REQUIRED section completed in the pass, regardless
  of optional sections; `:1109` — the whole-sync result
  follows the same rule (Ok when required-complete, even
  with an optional failure). The tradeoff is explicit: an
  optional failure no longer turns a required-complete pass
  into a whole-sync Partial (and no longer triggers a
  SyncWorker retry for that reason alone); the failure
  stays visible per section (outcomes list, Diagnostics,
  the P3 digest, and its section screen). `missingTotal`
  still counts optional missing items too — a pre-existing
  shape, left alone rather than redefined silently.
- UNKNOWN never blocks, deliberately: a required section
  absent from the manifest reports UNKNOWN without setting
  the flag, matching pre-P4 `no_section` behaviour. This
  matters on the live edge, which carries NO channel_typed
  section — counting UNKNOWN would break the clock there.
- Per-section success clocks: the eight up-to-date paths now
  stamp `lastSyncAt = now` instead of null (articles `:445`,
  shorts `:564`, market `:684`, channel_refs `:854`,
  channel_typed `:1133`, bundles `:1311`, radar `:1432`,
  nightly `:1550`). "Last successful sync" for a section
  now means its last successful confirmation (fresh data or
  hash-confirmed up-to-date), not only its last data change.
  Unpublished/gated sections (voices/special_issues, and
  bundles/radar/nightly while unpublished) keep a null clock
  — they have never succeeded, and the clock must say so.
  Diagnostics already displayed the per-section clock; P4
  makes it truthful rather than adding a second display.

### (b) War Room
- `WarRoomScreen.kt:263` — a radar section in state
  `not_yet_published` now shows the fixed reason line
  (`v5_state_section_not_published`, radar name) plus
  exactly one next step: re-check from the server
  (`v5_section_recheck` → `syncOnly(RADAR)`), which with
  the P4 guard only re-records state and never fetches.
  The existing failed-state lines (typed + radar, with
  retry) are unchanged.
- Token adoption: hero 26 → `HeroHeadline`, rows 18 →
  `RowHeadline`, summaries 15 → `RowSummary`, 16 →
  `HeroSummary`, 13/12 → `Meta`; 16dp paddings →
  `PageMargin`, 8dp spacers → `ElementGap`. Left literal
  (no P2 token exists): 14sp body text, 20sp section
  titles, micro-spacers. No bar logic touched: red/yellow
  bars, labels, and the SITREP/radar/arc order are exactly
  as before.

### (c) Market — visible seed-date line
- `MarketScreen.kt:290` — when `marketSeedOnly` is true, a
  line under the snapshot block reads (fixed string
  `v5_market_seed_line`): the figures are the bundled
  seed, with the seed's newest snapshot date, and have not
  yet been updated by a live market sync. The date comes
  from the snapshot itself (`latest.tsTehran` date part),
  never a hard-coded string — in the shipped seed that is
  2026-10-24 (newest seed snapshot `cycle-20261024-0600`,
  verified in `app/src/main/assets/seed/market.json`).
  `MainActivity.kt:268,281,519` derives `marketSeedOnly`
  from the market section's per-section clock
  (`st?.lastSyncAt == null`); once any market sync
  succeeds, the clock is set and the line disappears.
  Thresholds stay OFF (Yasin's decision, unchanged); the
  board, formulas, history, and alerts are untouched.
- Token adoption as in (b): 16 → `HeroSummary`, 18 →
  `RowHeadline`, 13 → `Meta`, 16dp → `PageMargin`, 8dp
  spacers → `ElementGap`. Left literal: 14sp, 20sp title.

### (d) Bundles — retry that cannot re-run a known-404
- `BundlesScreen.kt:118` — `not_yet_published` shows the
  fixed reason line + re-check button (manifest re-read
  only, no fetch). The honest empty line ("no bundle
  published yet") stays as the empty statement.
- `BundlesScreen.kt:165` — a FAILED section with code
  `http_404` shows the fixed reason line
  (`v5_bundles_404_reason`: the file is not on the server;
  retrying changes nothing until the edge manifest
  changes) and the button becomes a re-check, not a
  retry — because the repo guard (§2) skips the fetch
  while the manifest hash is unchanged. Other failure
  codes keep the existing error line + retry button.
  Special issues stay frozen: the single "stopped" line,
  no content, no rows, no navigation — unchanged.
- Token adoption: 26 → `HeroHeadline`, 18 → `RowHeadline`,
  15 → `RowSummary`, 13 → `Meta`, 16dp → `PageMargin`,
  8dp spacers → `ElementGap`. Left literal: 14sp, 24sp
  bundle-page title.

### (e) Nightly — conditional, still an empty shell
- Nightly is count 0 on the live edge, so the section is
  still an empty shell until the generator ships editions.
  **No edition was invented**: the honest empty line
  (`nightly_empty`) is kept, and the failed-with-hero line
  is kept. The rebuild is token adoption (26 →
  `HeroHeadline`, 18 → `RowHeadline`, 15 → `RowSummary`,
  13/12 → `Meta`, 16dp → `PageMargin`, 8dp → `ElementGap`)
  plus one honest addition: `NightlyScreen.kt:214` — the
  search results block now states its count
  (`search_results_count`, same fixed string as the feed
  search header), so a results list is never mistaken for
  the whole archive. Left literal: 14sp, 20sp, 17sp (one
  archive line), 24sp. Edition/dossier pages and their bar
  rule (red for typed, yellow for energy) are untouched.

### (f) Analysis
- Token adoption only (16 → `HeroSummary`, 13/12 →
  `Meta`, 16dp → `PageMargin`): the section already had
  honest states (empty, nightly-failed line, held line,
  no-card line), and P4 does not redesign around content
  that does not exist — analysis travels in the nightly
  section, which is still count 0. Left literal: 14sp,
  20sp titles. No parser, gate, or bar rule touched; the
  calibration gate stays closed (no probability on any
  surface).

### (g) Bookmarks — orphan reason lines
- `BookmarksScreen.kt:102,121` — orphan reasons are
  computed in a `LaunchedEffect` over the orphan lists
  (article orphans + bundle orphans): a source row still
  present with status `retracted` reads
  (`v5_bookmark_orphan_retracted`); anything else reads
  (`v5_bookmark_orphan_missing` — gone from device
  memory). The reason renders under each orphan row
  (`:195` bundles, `:239` articles) next to the unchanged
  prune button. A reason is never guessed from the
  bookmark row alone; the lookup is the channel/bundle
  row itself. Token adoption: 13 → `Meta`, 16dp →
  `PageMargin`. Live rows, cards, and prune behaviour
  are unchanged.

### (h) Search
- Feed search was already rebuilt in P3 (own empty string,
  clear-search step, honest count header) and is untouched
  here. P4's Search item is the Nightly search (§3(e)):
  count header added, empty string kept distinct.
  No search behaviour, query, or debounce changed.

### (i) Diagnostics — the required-only clock, stated
- `DiagnosticsScreen.kt:519` — a fixed line next to the
  global clock states its scope (required sections only;
  an unpublished optional section never stops it). It is
  part of `lines`, so the copy action carries it exactly
  as shown.
- `DiagnosticsScreen.kt:163` — per-section names now carry
  a required/optional marker via one helper
  (`sectionDisplayName`), used by BOTH the display loop
  (`:582`) and the copy lines (`:178`), so copy stays
  exactly what is shown. The per-section detail line
  (last success / last attempt / error / missing) is
  unchanged in shape; with §3(a) its "last success" is
  now a real confirmation clock.
- Token adoption: 16 → `HeroSummary`, 16dp → `PageMargin`,
  8dp spacers → `ElementGap`. Left literal: 14sp, 20sp
  section title, 22sp screen title. Fingerprint line 1,
  trace/branch/recon blocks, and copy parity are untouched.

### (j) Strings (all new, no duplicates)
- `strings.xml:482–489` — 8 new strings:
  `v5_state_section_not_published` (1 arg),
  `v5_bundles_404_reason`, `v5_section_recheck`,
  `v5_market_seed_line` (1 arg),
  `v5_diagnostics_global_clock`,
  `v5_diagnostics_optional_tag`,
  `v5_bookmark_orphan_retracted`,
  `v5_bookmark_orphan_missing`. Existing strings untouched.

### (k) Lab verifier
- NEW `edge/verify_v5p4.py` — static checks (guard wiring
  and ORDER — status/blank-file/known-404 guards before the
  first fetch in both sections; exactly 8 up-to-date clock
  sites; voices/special_issues null clock kept; nightly
  guard still present; required-only clock/result; no bare
  `anyIncomplete`; invites never block; 4 REQUIRED-gated
  `.also` blocks; screen wiring for every §3 item; token
  presence + no digit-glued tokens + brace balance in all
  P4 files; scope = P4 files only; NightLaw/bar/gate/feed
  files untouched; version untouched; no FCM/Play/Bale
  additions; voices/special_issues gate loop intact;
  report honesty) plus the live edge-contract read (§5).
  Result this run: **ALL V5 P4 CHECKS PASSED (114 checks,
  0 failures, 0 skipped)**. Caveat: the verifier does NOT
  compile anything — see §4.

## 4. Caveats and limits (read before relying on this phase)

- **No build was run** (task order: no build, no push, no
  publish). Kotlin correctness was checked by reading, by
  the balance check (braces/parens balanced in all edited
  files), and by the static verifier — not by compiling.
  Real risks carried to the P6 cloud build: the
  `it.key in SyncSections.REQUIRED` `.also` blocks, the
  `failedHashKey` meta reads/writes, the
  `sectionDisplayName` lambda in Diagnostics, the
  `LaunchedEffect(shownOrphans, orphanBundles)` reason
  lookup in Bookmarks, and the `marketSeedOnly` param
  threading in MainActivity. If the build fails, these
  are the first suspects, not the token values.
- **G4 is NOT passed by this phase.** Gate G4 (a full sync
  completes with `last_sync_at` advancing while
  bundles/radar are unpublished; Bundles shows a reason,
  never `http_404` poisoning; edge contract test passes)
  needs a device run: the lab evidence (§5) proves the
  code paths and the live manifest state, but only a phone
  sync can prove the clock advances on device. The edge
  contract leg of G4 passes in the lab; the sync leg stays
  open for Phase 5 device evidence.
- **The known-404 tradeoff** (§2) and the **missingTotal
  shape** (§3(a)) are deliberate and documented, not
  oversights — reviewers should not "fix" them without
  re-reading those paragraphs.
- **A published-nightly 404 stays a plain FAILED** (its
  fetch path is per-edition files, a different shape from
  bundles/radar). It is optional, so it cannot block the
  global clock — but it does not get the known-404 pin.
  If nightly publishes and 404s, that gap is a Phase 5+
  item, not a P4 defect.
- **Leftovers, stated not hidden:** 14sp body text, 20sp /
  22sp / 24sp / 17sp titles (no P2 token exists for any of
  them), micro-spacers (3/4/6/10/12/14dp), and the P3
  leftovers (reader footer hint, 20sp timeline title) are
  still open for a token-extension pass or Phase 5 — they
  are not silently "done".
- **Digest coverage limit (from P3, unchanged):** untraced
  sync paths still produce no digest line; the sync row
  still reports those failures.

## 5. A2 fix verification (lab + live)

Lab (verifier `edge/verify_v5p4.py`, static + live read):
- Guard order proven in code: in both `syncBundlesSection`
  and `syncRadarSection`, the status guard, the blank-file
  guard, and the known-404 guard all sit textually BEFORE
  the first `httpGet(`; the nightly guard is still present
  (3 status-guard sites total).
- Clock logic proven in code: exactly 8 up-to-date
  `lastSyncAt = now` sites; voices/special_issues still
  null; no bare `anyIncomplete`; invites region sets no
  required flag; 4 REQUIRED-gated `.also` blocks; clock
  block and whole-sync return both required-only.
- Live manifest read this run (GET only, 2026-10-26):
  `bundles`, `radar`, `nightly` are all
  `status: "not_yet_published"`, count 0, NO `file` field,
  blank hash — exactly the state the guards match, so on
  the live edge both sections now record
  `NOT_YET_PUBLISHED` with no fetch and no 404.
- Live contract, fetchable ⇒ file exists: `market.json`,
  `channel_refs.json`, `invite_hashes.json`,
  `revocations.json` all return 200 from Pages; local
  `edge/out/` contains no `bundles.json`, `radar.json`,
  or `nightly.json` — the manifest and the directory agree.
- Regression runs this session: `edge/verify_v5p1.py`:
  **ALL V5 P1 CHECKS PASSED** (70 checks, 0 failures);
  `edge/verify_v5p2.py`: **ALL V5 P2 CHECKS PASSED** (no
  P2 regression); `edge/verify_v5p3.py`: 66 checks pass
  and its 2 scope checks fail ONLY because P4 (this
  authorized phase) changed EdgeRepository/Diagnostics and
  the section screens — that is the P3 verifier being
  phase-pinned, not a P3 regression (its token/state
  checks on P3 files all still pass).

## 6. Files changed, commit, and hard-limit compliance

Changed (lab commit only, NO push):
- `app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt`
  (A2 guards, required-only clock/result, 8 success clocks)
- `app/src/main/java/com/ecojournal/app/data/UpdateEngine.kt`
  (REQUIRED set, failedHashKey)
- `app/src/main/java/com/ecojournal/app/MainActivity.kt`
  (marketSeedOnly wiring only)
- `app/src/main/java/com/ecojournal/app/ui/screens/WarRoomScreen.kt`
  (radar reason + tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/MarketScreen.kt`
  (seed-date line + tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/BundlesScreen.kt`
  (unpublished/404 reasons + re-check + tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/NightlyScreen.kt`
  (search count + tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt`
  (tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/BookmarksScreen.kt`
  (orphan reasons + tokens)
- `app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt`
  (global-clock line, required/optional tags, tokens)
- `app/src/main/res/values/strings.xml` (+8 strings, no duplicates)
- NEW `edge/verify_v5p4.py` (verifier)
- NEW `docs/v5/v5-p4-report.md` (this file)

NOT touched (pre-existing dirty files left alone, not
committed): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `bridge/*`, and all other
pre-existing untracked files.

Hard limits honoured: no cron changes, no live-channel
changes or posts, no special-issues/voices publication or
gate changes (gate loop intact; hub/index exclusions
untouched), no FCM/Play, no Bale work, no Room schema
change (meta reuse only — the failed-hash key lives in
the existing meta table, no migration, no destructive
clear), no night-law/bar/gate/cadence behaviour changes
(NightLaw.kt, TypedCard bar logic, GatedSectionsScreen,
and the feed/reader files are all absent from the diff),
no versionName/versionCode change (still 4.0.1 / 5 —
P6 owns 5.0.0/6), no push, no build, no publish, no live
change of any kind.

## 7. What Phase 5 must do next (vs what stays out)

**Phase 5 (polish + accessibility + device evidence) MUST:**
1. FIRST run the P1 §4 device-reproduction protocol on an
   instrumented build and record the G1 verdict from the
   P1 §3 decision table (§1). G1 is still open, and no
   P4 change may be described as resolving A1.
2. Prove G4 on device: a full sync on Yasin's phone
   advances `last_sync_at` while bundles/radar are
   unpublished; the Bundles screen shows the reason line
   (never `http_404` poisoning); the Diagnostics copy
   shows bundles/radar `not_yet_published` with a null
   success clock and the required sections with fresh
   confirmation clocks.
3. Device-check the P4 screens: Market seed line visible
   before the first market sync and gone after it;
   WarRoom radar reason line; Bookmarks orphan reasons
   (needs a real orphan or a fixture — do not fake one
   by deleting a live bookmark); Nightly search count;
   Diagnostics required/optional tags + global-clock line.
4. The standing Phase 5 work, unchanged: TalkBack
   walkthrough PERFORMED on a phone with walk log +
   recording; font extremes (0.85×/1.0×/1.3×, including
   the five-item bar labels on one line); contrast
   MEASURED from device screenshots (G2); motion-control
   verification (ticker pause + reduced motion on device);
   per-section empty/error/offline matrix re-run on the
   final build.

**Stays OUT of Phase 5 (unchanged by P4):** the P6 publish
checklist (version bump to 5.0.0/6, cloud build,
shared-signing verification, phone checklist). The P6
build gate remains blocking for ANY device claim:
compile this tree, verify the APK's
`BuildConfig.GIT_COMMIT` equals the P4 commit, and
verify install-over behaviour before any phone run.
