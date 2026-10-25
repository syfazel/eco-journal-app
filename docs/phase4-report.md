# Phase 4 — Market history/charts, radar hardening, alerts, notification preferences: implementation report (LAB ONLY)

Date: 2026-10-25 (Asia/Tehran). Spec:
`docs/big-update/phases4-5-market-nightly-spec.md` §4 (Phase 4
portion, criteria P4-1–P4-12). Parent plan:
`docs/big-update-plan.md` §5 Phase 4.

Status: IMPLEMENTED IN LAB, NOT BUILT, NOT PUSHED, NOT
DISTRIBUTED. No Gradle build and no cloud build were run in this
pass (no JDK/SDK/Gradle exists in this sandbox); no live cron,
bot, channel, or freeze state was touched; no FCM activation; no
test channel created; no special-issues publication; no voices
publication. Work is committed LOCALLY only — the parent owns
any push/build decision. No Phase 5 (nightly), no post-program
items (CNN ticker, sync compaction), and no v4 remote-update
system were implemented here.

READ THIS FIRST — the single most important honesty note, in
the same shape as Phases 2/3: every criterion below is
LAB-READY (code + fixtures exist and the fixture side is
verified) and PHONE-PENDING (no phone run happened). "Lab-ready"
is NOT a pass. A criterion passes only on Yasin's real phone
(or a phone he designates), per the plan's rule that a phase is
not "done" until its acceptance criteria pass on a real phone
(or are explicitly marked lab-only). The parent's cloud build is
therefore a GATE, not a formality, before any phone test: a
Kotlin compile error that brace/string checks cannot see (e.g.
a type mismatch) would surface only there, and the Room v5→v6
migration has never executed anywhere.

## 1. What was built

### 1.1 Pure domain (no Android UI dependency)

- `app/src/main/java/com/ecojournal/app/data/MarketHistory.kt`
  (new): ranges 24H/7D/30D/ALL (spec §4.2 UI ranges); the 13
  price series + 4 derived series through the SAME
  `MarketFormulas` engine as the M4 board (never a second
  formula implementation); gap segmentation (a line connects
  two points ONLY when the gap is at most the range's nominal
  step — 24H: 1h, 7D/30D: 1d, ALL: 7d — SPEC DECISION, since
  the spec names "the range's nominal step" without defining
  it); `previousOf` (previous by tsEpoch strictly below);
  alert evaluation (SIGNED comparison, 12h cooldown,
  fresh-crossing rule, null threshold = OFF).
- `app/src/main/java/com/ecojournal/app/data/NotificationPolicy.kt`
  (new): per-service toggle model, wrap-aware quiet-hours
  test, and the §4.8 precedence table in the §4.7 check order
  (master → per-service toggle → Law 15 → quiet hours →
  cooldown). Suppressed reasons are the FIRST failing check.

### 1.2 Room schema (v5 → v6, additive only)

- `data/AppDatabase.kt`: new tables `market_alert_events`
  (eventId = `bubble|snapshotId` / `spread|snapshotId`, one row
  per metric per snapshot at most, `notified` = 1 ONLY when a
  notification was actually posted) and `notification_history`
  (local only, pruned to newest 200 rows AND 90 days, whichever
  is smaller); new `Phase4Dao` (alert events, history insert /
  prune / today counts by type and by reason); radar_signals
  gains 5 additive columns (`storyId`, `matchedTopics`,
  `firstSeenTehran`, `lastSeenTehran`, `linkedAppKey`);
  `MIGRATION_5_6` (additive only, no existing table altered in
  shape otherwise); database version 6.
- DEVIATION RECORDED (spec text vs reality): the spec says
  "Room v4 → v5" because it was written against the Phase 1
  baseline; Phase 2 already moved the database to v5, so
  Phase 4 is v5 → v6. The TABLES are exactly the spec's
  tables; only the version number differs. A v4 install still
  migrates through MIGRATION_4_5 first (chain unchanged).
- `marketLatest` / `marketPrevious` now filter
  `tsEpoch IS NOT NULL` explicitly. NOTE on the spec's §4.3
  parenthetical ("SQLite ORDER BY tsEpoch DESC puts NULLs
  first"): that is WRONG for SQLite — verified in the lab
  against SQLite itself, DESC puts NULLs LAST (NULL sorts
  smallest). The existing queries were therefore already safe;
  the explicit filter is belt-and-braces so the rule holds
  regardless of engine nuance, and the fixture test proves it
  literally (a null-ts row never becomes latest).

### 1.3 Settings + notification channels

- `data/SettingsStore.kt`: per-service keys `notify_urgent`
  (ON), `notify_market_alerts` (ON), `notify_bundles` (ON),
  `notify_sitrep` (ON), `notify_nightly` (ON),
  `notify_special_issues` (OFF), `notify_radar` (OFF);
  quiet-hours keys (`quiet_hours_enabled` default false,
  `quiet_start_min` / `quiet_end_min` minutes after midnight
  Tehran, may wrap midnight, `quiet_mute_urgent` default
  false); market-alert cooldown state
  (`market_alert_last_notified_bubble/spread`, written ONLY
  after a notification actually posts). The master switch
  remains the kill switch.
- `util/Notifications.kt`: NEW channels `eco_urgent`
  (IMPORTANCE_HIGH, sound+vibration) and `eco_market_alerts`
  (IMPORTANCE_DEFAULT — deliberately NOT high: market alerts
  must not outrank urgent news). DEVIATION RECORDED: the spec's
  R11 baseline ("one channel today") predates Phase 2, which
  already created a channel id `urgent` in the lab. Phase 4
  adopts the spec's `eco_urgent` id; the Phase 2 lab id never
  shipped in a phone build (Phases 1–3 had no phone builds by
  their passes, and the Phase 1 phone install predates Phase
  2), so no phone has the old channel to migrate. The existing
  `eco_articles` channel is NOT recreated and its importance
  is NOT changed. New: `showMarketAlert` (tap → Market tab),
  `showDeferredSummary` (ONE summary for quiet-deferred
  bundle/nightly items, posted on the first sync after quiet
  end — polling reality: the app cannot wake itself exactly
  at quiet-hours end).
- `data/EdgeRepository.kt`: market sync parses the edge
  `market_alerts` object into meta (`p4_market_alerts_bubble/
  spread`, "" = null = OFF) on every successful market sync;
  `evaluateMarketAlerts` runs on every market-section sync
  completion (including manual market refresh via syncOnly)
  using latest + previous Room snapshots and the stored
  formulas — no separate alert poll, no push, so alert latency
  = sync latency (15-min poll grid + Doze), stated in the UI;
  the dispatcher is rewritten as `dispatchPhase4Notifications`
  (full syncs only, as before) with the §4.7/§4.8 rules,
  notification-history recording for every fired/suppressed
  candidate, deferred-summary flush, and the Phase 2 no-retro
  rule preserved (an item arriving while its toggle is off is
  marked notified WITHOUT firing).

### 1.4 Screens

- `ui/screens/MarketScreen.kt`: the M4 board is UNTOUCHED
  above; below it (never replacing it) come (a) alert banners
  — current STATE from the latest snapshot + edge thresholds,
  value to 1 decimal + threshold exactly as configured +
  snapshot timestamp, dismissible per metric per snapshot,
  and the honest OFF line ("آستانه‌ای تنظیم نشده است —
  هشدارها خاموش‌اند") when no threshold is set, never a fake
  "all clear"; (b) history — series picker (13 prices + 4
  derived, one at a time; multi-series overlay is NOT a Phase
  4 promise), range picker (24H/7D/30D/ALL), coverage header
  (plottable point count, first/last Shamsi timestamps,
  24h staleness line when the range includes the latest
  snapshot), gap-broken Canvas chart (tap selects the nearest
  point; prev/next buttons walk points), and a selected
  snapshot card (all 15 fields, formulas for that snapshot,
  change vs previous history snapshot where computable, "—"
  with no arrow otherwise, snapshotId + Shamsi timestamp).
  History state (`remember`) is hoisted above the screen's
  early returns so no remember sits behind a conditional
  return. A market refresh button runs a per-section market
  sync (which also re-evaluates alerts).
- `ui/screens/WarRoomScreen.kt`: RadarBlock gains a severity
  filter (all/high/medium/low — a grade filter shows ONLY
  signals carrying exactly that grade; legacy Phase 2 rows
  with opaque severity strings are ungraded and hidden by a
  grade filter, never re-graded); RadarRow gains the graded
  badge (fixed strings for high/medium/low, legacy strings
  verbatim), first/last-seen line, matched-topics line, and
  the "not yet in app" marker (graded signal with story_id
  but null linked_app_key: marker, no dead affordance).
  Staleness still comes ONLY from the edge-supplied interval
  (the caller partitions fresh/stale); nothing here guesses.
- `ui/screens/SettingsScreen.kt`: per-service toggle rows,
  quiet-hours enable + HH:MM start/end fields (Persian/Arabic
  digits accepted, invalid input shows an error and writes
  nothing) + save button, the separate `quiet_mute_urgent`
  toggle with its own confirmation wording, threshold
  PRESENCE line (set / not set — never the value; the value
  belongs on the Market banner), and a system-settings deep
  link with the honest note that an in-app toggle cannot
  override a system-level channel denial.
- `ui/screens/DiagnosticsScreen.kt`: Phase 4 block, built once
  and used for BOTH display and copy (like Phase 2/3 lines):
  market line (count, latest timestamp, threshold presence
  per metric — never values), radar line (total/active/
  resolved), notification lines (today's fired total +
  suppressed total, per-type fired counts, per-reason
  suppressed counts), last market-alert event per metric
  (timestamp ONLY, per spec §4.9). Copy-diagnostics copies
  exactly the displayed lines including this block.
- `MainActivity.kt`: market route passes history snapshots
  (ordered flow), formula definitions, thresholds (meta
  flows), banner dismissals, dismiss handler, and market
  refresh; settings route passes threshold presence;
  market-alert taps navigate to the Market tab.

### 1.5 Edge side (lab generator + publish verify)

- `edge/generate_edge.py`: emits the `market_alerts` object
  (§4.1) — thresholds read READ-ONLY from bot.db admin
  settings (`alert_bubble` / `alert_spread`) at publish time,
  null on missing/unparseable keys, source `admin-settings`,
  never hard-coded, never from help-text examples. The market
  section hash now covers `market_alerts` when present (a
  threshold change re-downloads the market section exactly
  like a new snapshot); files without the key keep the
  two-field hash so pre-Phase-4 edges still verify.
- `edge/publish_edge.py` `--verify-only`: recomputes the
  market hash with the same rule; asserts `market_alerts`
  thresholds are null-or-number and source is
  `admin-settings` or the explicitly labelled
  `lab-fixture-only` (Phase 4 fixture source — the production
  generator never writes it, so a production edge carrying it
  would still be visible as such).
- Pre-existing note (NOT a Phase 4 regression): the committed
  `edge/out/` snapshot has no `manifest.json`/`join_keys.json`
  (Phase 1 generated those to a temp dir, not into `out/`),
  so `--verify-only` on `edge/out/` fails on missing files
  before reaching any market check. The Phase 2/Phase 4
  fixture edges verify fully.

### 1.6 Edge fixtures + verifier (fixture-based testing only)

- `edge/generate_phase4_fixtures.py` (new): builds
  `edge/out-phase4-fixture/` from the Phase 2 fixture edge:
  real 164 snapshots + 8 fixture snapshots (null-price,
  null-ts, and the 6-step alert sequence S1–S6 dated
  2026-10-25/26, after the frozen last snapshot) + a LAB-ONLY
  `market_alerts` object (bubble 5.0, spread 2.0, source
  `lab-fixture-only`) + 4 graded radar signals (high linked
  to a Phase 2 fixture typed item by story_id, medium
  unlinked, low resolved, one graded-shape orphan with NO
  story_id that the parser must hold). Manifest market hash
  (incl. market_alerts) and radar hash (4-field view)
  rewritten; gated sections untouched; the copied
  GENERATION_REPORT market count rewritten to 172 so the
  fixture edge verifies exactly like a production edge.
- `edge/verify_phase4_fixtures.py` (new): 38 fixture-side
  checks, ALL PASSING (ranges over frozen data, null handling,
  the alert-sequence simulation mirroring `MarketAlerts.kt`,
  signed comparison on the frozen −1.21% spread, threshold-off
  real state from a read-only bot.db read, no-default greps,
  radar grades/dedup/join/marker/orphan, manifest hash
  recomputation, gated sections, hygiene). This verifies the
  FIXTURES and the mirrored pure logic, not the app — the app
  side still needs the phone test (§5).

## 2. Bubble/spread threshold status — OFF, with evidence

- REAL STATE: OFF for both metrics. Evidence, re-verified
  read-only in this pass (2026-10-25, bot.db settings table,
  mode=ro): settings keys are only `breaking_count_*`,
  `breaking_guids_*`, `flash_count_*`, `flash_vix_last` (10
  keys total) — NO `alert_bubble`, NO `alert_spread`, NO
  `alert_last_*` keys at all. Effective channel behaviour is
  therefore threshold = 0 = OFF for both metrics (spec R6/R7).
- The production generator run in this pass (scratch dir,
  deleted afterwards) emitted `market_alerts` =
  `{bubble_threshold_pct: null, spread_threshold_pct: null,
  source: "admin-settings", updated_at_tehran: null}` and
  `publish_edge.py --verify-only` PASSED on it. The live
  `edge/out/` was NOT regenerated by this pass.
- The `/setalert` help examples (bubble 15, spread 2) appear
  NOWHERE as values: not in Kotlin code, not in the generator
  (the generator's warning comment names them only to forbid
  them), not in Settings, not in Diagnostics (presence only).
  The only 5.0/2.0 thresholds in the repo are the LAB-ONLY
  fixture values in `edge/out-phase4-fixture/market.json` and
  `edge/generate_phase4_fixtures.py`, labelled
  `lab-fixture-only` — they must never reach a distributed
  APK; the fixture edge is served only for the phone test,
  never pushed to the pilot Pages edge.
- With thresholds null (the real state): no banner claims
  monitoring, no alert notification fires, and the Market
  Diagnostics line reads "threshold not set — alerts off"
  (P4-5). Alert evaluation short-circuits before any Room
  write when no threshold is set.

## 3. Cross-phase bar rule — status (UNTOUCHED, no regression)

Yasin's rule (`docs/big-update/new-requirements.md` §0): RED
bar for war-room/red-alert items, YELLOW bar for
energy-domain items, on feed cards and reader headers, inside
the same feed/tabs system. No separate service, no separate
tab for war-room/red-alert. Dropping the bar is a regression.

Phase 4 restyles NO feed card and NO reader, and removes NO
bar. Evidence (`git diff --name-only` on the bar files is
empty — they are byte-identical to Phase 2):

- `app/src/main/java/com/ecojournal/app/data/Phase2Domain.kt:25-36`
  — `DomainBar` enum, `DomainBars` (RED_ARGB line 28,
  YELLOW_ARGB line 29, `forChannelItem()` → RED line 32,
  `forArticle()` → YELLOW iff category == "energy" lines
  35–36).
- `app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt:60`
  (`DomainBarStrip`) and line 240 (every typed card renders
  it with `forChannelItem()` = RED + format badge label).
- `app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt:111`
  (bar computed per article), lines 162–163 (YELLOW bar +
  «انرژی» label).
- `app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt`
  (reader header YELLOW bar + «انرژی» label — Phase 2 lines
  219–221, file untouched).
- New surfaces and the bar: the Market history snapshot card
  is market data, not a feed card, and carries no category —
  no bar applies to it and none was invented for it; War Room
  radar rows are signals, not cards, and their Phase 4
  additions (severity badge/filter, seen/topics lines,
  "not yet in app" marker) touch no bar; Diagnostics and
  Settings are not card surfaces. War Room stays a top-level
  section (not a feed tab); red-alert items stay in the feed
  with bar + badge; energy items stay in their category tab
  with the yellow bar. Special Issues still renders only
  «ویژه‌نامه‌ها — متوقف است» (Phase 2 line, untouched).

## 4. Decisions taken where the spec left a gap

All are recorded here so a reviewer can challenge each in one
place. None invents content, a threshold, or a section name.

1. Room version is v5 → v6, not v4 → v5 (spec baseline
   predates Phase 2; §1.2).
2. Urgent channel id is `eco_urgent` per spec §4.7, not the
   Phase 2 lab id `urgent` (which never shipped on a phone;
   §1.3).
3. Nominal chart steps: 24H = 1h, 7D/30D = 1d, ALL = 7d
   (§1.1). The spec names "the range's nominal step" without
   defining it.
4. Radar orphan rule scoped to graded-shape signals only, so
   Phase 2 legacy layer signals (no story id at all) keep
   parsing and listing exactly as before (P2-A8); a graded
   signal without story_id is held, never an orphan row.
   Graded-signal story link rule: story_id present +
   linked_app_key null → "not yet in app" marker, no button;
   linked_app_key present (or legacy linkedStoryId) → story
   button via openStory's story resolution.
5. Deferred summary fires on the FIRST SYNC after quiet-hours
   end (polling reality), not exactly at quiet-hours end;
   market-alert suppressions are never re-presented as
   notifications (the banner still shows the state).
6. Night-suppressed Morning Bundles stay PENDING (not marked
   notified) so they can fire at 08:00; recorded once per
   bundle, not every night sync. Quiet-deferred and
   toggle-suppressed bundles ARE marked notified (no retro
   burst when toggled back on).
7. Market-alert event rows are written whenever the banner is
   ON (value + threshold recorded, notified = 0 unless a
   notification actually posted) — this is what makes the
   nightly close's "fired or not" alert history (§5.9 input)
   possible from the same rows the app saw. A merely-above
   snapshot with no fresh crossing records no suppressed
   notification candidate (it is not a candidate).
8. Producer coverage, stated plainly (see §5 P4-7 note):
   urgent, Morning Bundle, and market alerts are the wired
   producers. SITREP / nightly / special-issues / radar
   toggles are stored and shown in Settings, but NO per-item
   producer fires for them in Phase 4 — Phase 2 deliberately
   fires no radar notification (P2-A8), SITREP cards arrive
   via the typed feed without a per-card notification, and
   the nightly edition is Phase 5. Wiring a producer for any
   of them is that phase's work, not a silent addition here.
9. Spec §4.2's "24H usually contains 0–1 points" sketch does
   NOT match the frozen data: the frozen tail is dense (11
   snapshots in the last 24h of frozen data; 7D = 25, 30D =
   48, ALL = 164), while the whole history averages ~1.2
   days/point with gaps up to 4 days. The verifier therefore
   checks the nominal-step break rule itself and the phone
   reality (24H relative to a post-freeze "now" over frozen
   data is EMPTY — that empty state with its coverage note
   IS the P4-2 pass on the phone), not the spec's sketch.
10. `publish_edge.py --verify-only` accepts the explicitly
    labelled `lab-fixture-only` market_alerts source so the
    fixture edge verifies; the production generator never
    writes it (always `admin-settings`).

## 5. Per-criterion results P4-1–P4-12

Every criterion is LAB-READY (code + fixtures exist and the
fixture side is verified) and PHONE-PENDING (no phone run
happened). "Lab-ready" is NOT a pass. Phone-test procedure
for all: serve `edge/out-phase4-fixture/` as the edge
(mechanism: parent's choice — local static host or a test
Pages branch; NOT the live edge, NOT pushed here), sync, and
observe each item below. P4-1/P4-2 counts over the frozen 164
are observed on the REAL edge first (fixture latest is a
fixture snapshot by design); alert/radar items are observed
on the fixture edge. No phone-clock change is needed for any
criterion (fixture times are Tehran wall-clock values in the
data, and quiet-hours/night cases use the fixture's own
windows where noted — the tester records the path used).

- P4-1 (History renders): LAB-READY / PHONE-PENDING.
  Observe on the real edge: Market tab history in ALL range
  shows 164 snapshots with correct point count, first
  (2026-04-15) / last (2026-10-24) Shamsi timestamps, and
  visible line breaks at gaps above the ALL step (7d).
  Fixture side: 38/38 verifier checks pass (real 164 intact,
  gap rule, manifest hash).
- P4-2 (Ranges behave): LAB-READY / PHONE-PENDING. Observe:
  24H/7D/30D/ALL show honest counts (over frozen data
  relative to last snapshot: 24H = 11, 7D = 25, 30D = 48,
  ALL = 164 — see §4 decision 9 for why these differ from
  the spec sketch); on the phone at post-freeze "now", 24H
  over frozen data is EMPTY with its coverage note + switch
  to ALL — that empty state IS the pass, not a failure.
- P4-3 (Missing-field honesty): LAB-READY / PHONE-PENDING.
  Observe on the fixture edge: the null-price snapshot
  renders "—" for سکهٔ امامی, breaks the bubble series, and
  shows "—" (no arrow) for its change cell; the null-ts
  snapshot never becomes "latest" (latest is the S6 fixture
  snapshot, not `lab-p4-nullts`). Fixture side verified
  (bubble null, spread computable, latest != null-ts row).
- P4-4 (Alert banner + 12h cooldown, simulated): LAB-READY /
  PHONE-PENDING. Observe on the fixture edge (LAB-ONLY
  thresholds 5.0/2.0): S2 banners bubble AND notifies once;
  S3 banners bubble (state still true) but does NOT
  re-notify bubble, while spread notifies once on its fresh
  crossing; S4 (both still above, >12h after S3, no fresh
  crossing) notifies NEITHER; S6 notifies bubble again on
  its fresh crossing after cooldown. The phone test must
  also confirm the notification drawer (not just the
  banner) for each expected fire, and silence for each
  expected non-fire. Fixture side: simulated notify set is
  exactly [(S2, bubble), (S3, spread), (S6, bubble)].
- P4-5 (Threshold-off honesty): LAB-READY / PHONE-PENDING.
  Observe on the REAL edge (thresholds null): no banner
  claims monitoring, no alert notification fires, the Market
  tab shows "آستانه‌ای تنظیم نشده است — هشدارها خاموش‌اند",
  and Diagnostics reads "threshold not set — alerts off"
  for both metrics. The help-text examples (15 / 2) appear
  nowhere as values (grep-verified in production code).
- P4-6 (Signed comparison): LAB-READY / PHONE-PENDING.
  Observe: the frozen spread −1.21% with a positive spread
  threshold does NOT fire; a simulated bubble below
  threshold does NOT fire. Guards against an `abs()`
  implementation (no `abs()` call exists in
  `MarketHistory.kt` code — only its own comment mentions
  it; the verifier strips comment lines for this check).
- P4-7 (Per-service toggles): LAB-READY / PHONE-PENDING.
  Observe: turning OFF `notify_bundles` silences bundle
  notifications while an urgent notification still fires;
  turning the master switch OFF silences everything;
  turning it back ON restores per-service behaviour without
  re-firing suppressed items as a burst. NOTE (§4 decision
  8): SITREP/nightly/special-issues/radar toggles are stored
  and shown, but no per-item producer fires for them in
  Phase 4 — the tester must NOT expect a SITREP or radar
  notification in this phase; that wiring belongs to their
  content phases.
- P4-8 (Quiet hours): LAB-READY / PHONE-PENDING. Observe
  inside a test quiet-hours window: a bundle notification is
  suppressed (recorded in history with reason
  `quiet_hours`) and re-presented ONCE as a deferred summary
  on the first sync after quiet-hours end; an urgent
  (≥85-equivalent fixture) notification still fires during
  quiet hours by default, and stops firing only after
  `quiet_mute_urgent` is explicitly enabled. NOTE: the
  deferred summary timing is "first sync after quiet end",
  not exactly at quiet end (polling reality, §4 decision 5)
  — the tester records the sync that delivered it.
- P4-9 (Night law in-app): LAB-READY / PHONE-PENDING.
  Observe: a simulated night-held item (70–84 at night)
  produces NO instant notification and appears in the
  Morning Bundle; a simulated ≥85 night item notifies on
  the urgent channel (`eco_urgent`) and deep-links to the
  item (Phase 2 behaviour, re-verified here because Phase 4
  touches the dispatcher — the dispatcher now routes urgent
  through `NotificationPolicy.decide`, and the urgent
  channel id changed from Phase 2's lab id `urgent` to the
  spec's `eco_urgent`, so the tester must confirm the
  notification arrives on the NEW channel).
- P4-10 (Radar list): LAB-READY / PHONE-PENDING. Observe on
  the fixture edge: graded signals render with severity
  badge, source name, linked story (high/low signals open
  their story) or "not yet in app" (medium signal, no dead
  affordance); severity filters show only that grade;
  repeat matches do not duplicate a signal (only
  `last_seen_tehran` advances — the fixture ships one row
  per signal_id with an advanced last_seen for the high
  signal); the graded-shape orphan (no story_id) renders
  NOWHERE (parser holds it). Legacy Phase 2 radar rows
  still render (fixture verifier for Phase 2 still passes).
- P4-11 (Offline + failed sync): LAB-READY / PHONE-PENDING.
  Observe: airplane-mode launch keeps history/radar/banner
  (with staleness lines); an unreachable edge reports
  per-section failed, keeps all cached content, and retries
  with backoff (Phase 1 E1/E4 pattern, re-verified for the
  new sections — the market section's failed state leaves
  history untouched by construction: only upserts, no
  deletes, and the banner derives from last-good Room rows).
- P4-12 (Diagnostics honesty): LAB-READY / PHONE-PENDING.
  Observe: Diagnostics shows the Phase 4 block (market +
  radar per-section lines, notification counts/suppressions,
  last alert event per metric as timestamp only); copy
  copies exactly the lines shown, with no content, codes,
  tokens, or threshold VALUES in the copied text beyond
  what is shown (threshold presence only). The copy list
  and the display list are the same object in code, but
  the phone comparison is still required.

Exit gate: Phase 4 is DONE only when P4-1–P4-12 all pass on
a real phone, or a criterion is explicitly recorded as
lab-only with Yasin's acknowledgement. Phase 4 completion
does NOT open Phase 5's live nightly generation (dry-run
only during the freeze) and does NOT change the freeze.

## 6. Phase 1/2/3 regression check (explicit)

- Phase 1 paths: articles/shorts/market/channel_refs/invites
  sync blocks UNTOUCHED except the market block's additive
  threshold parse + post-transaction alert evaluation (no
  change to hashes, transactions, or the upsert-only rule);
  legacy index sync UNTOUCHED (alert evaluation is a v3-path
  behaviour; a legacy edge has no market_alerts object, so
  thresholds stay null = OFF there). Manifest parser
  UNTOUCHED. Room v4 → v5 chain UNTOUCHED; v5 → v6 is
  additive (new tables + nullable/defaulted columns).
- Phase 2 paths: typed/bundle/radar sync blocks UNTOUCHED
  except the radar parser's additive graded fields (legacy
  Phase 2 fixture signals still parse — Phase 2 fixture
  verifier still passes: "ALL FIXTURE CHECKS PASS") and the
  dispatcher rewrite (§1.3). INTENDED behaviour changes in
  the dispatcher, flagged not hidden: (a) urgent
  notifications now route through the §4.7 check order and
  the `eco_urgent` channel id (see §4 decision 2); with
  defaults on, an instant item still fires exactly once;
  (b) a past-day Morning Bundle surfacing at night is now
  suppressed under the night law (Phase 2 notified it at
  night) — this is the spec §4.8 table ("non-urgent NEVER
  notifies at night"), applied deliberately; (c) every
  fired/suppressed candidate now writes a notification_history
  row (pruned, local only).
- Phase 3 paths: chain recording, merge, publish verify
  UNTOUCHED except publish verify's additive market_alerts
  checks (Phase 2/Phase 3 fixture edges still verify PASS;
  the Tier A chain record format is unchanged). E1–E8 remain
  PHONE-PENDING exactly as Phases 1–3 left them; Phase 1's
  E2 phone gate still binds Phases 3 and 5 regardless of
  Phase 4's state. Tier B remains BLOCKED on the test
  channel + rehearsal-edge isolation (Phase 3 §6).
- Feed: with zero typed items, unchanged except the Phase 4
  Market tab additions (below the board) and War Room radar
  filter/badge additions; no feed card restyled, no bar
  removed (§3).
- No Phase 1/2/3 regression was found by inspection, but
  E1–E8 and P2-A1–P2-A13 remain PHONE-PENDING. If any gate
  fails on the phone, that chain stops there.

## 7. What Phase 5 needs from this phase

1. The production edge generator must emit `radar.json` in
   the graded shape (spec §4.5) — today ONLY the lab fixture
   builder emits it; the production generator still reports
   radar `not_yet_published`. Until then, Phase 5's nightly
   market-close alert history (§5.9 input) has no production
   `market_alert_events` rows to read — the app-side rows
   exist, but only from fixture/test syncs.
2. The nightly notification (§5.16) depends on Phase 4's
   toggles: `notify_nightly` is stored and shown in Settings
   now, but its producer is Phase 5 (first successful nightly
   sync after publication, default channel, deferred to
   max(08:00, quiet-hours end) if it lands at night or inside
   quiet hours — the §4.8 rule is already implemented in
   `NotificationPolicy.decide` for NIGHTLY, so Phase 5 only
   wires the producer through it, never re-implements the
   timing).
3. The nightly market-close section (§5.9) reads the day's
   `market_alert_events` rows (fired or not) — Phase 4 writes
   them on banner-ON snapshots (notified = 1 only when
   posted). Phase 5 must NOT re-derive alert history from
   snapshots; it reads these rows.
4. Phase 5's edition sync extends the same per-section
   Diagnostics + force-refresh pattern to `nightly` (still
   `not_yet_published` after Phase 4 — that is correct, not
   a Phase 4 gap; `nightly` becomes FETCHABLE only in Phase
   5). `bundles` belongs to Phase 2 (already fetchable);
   `voices`/`special_issues` stay gated.
5. Publisher confirmations still open from Phases 2–3
   (status vocabulary, attribution shipping, war_room object,
   SITREP canonical section names, WIRE cap, radar severity
   ranks and interval cadence per crisis level, bundle
   `bundle_date_shamsi` format) — Phase 4's graded radar
   derivation (flash-keyword OR FLASH/WATCH at L3/L4 →
   high; keypoints-selected OR ≥2 topic groups → medium;
   else low) is a SPEC DECISION awaiting editorial
   confirmation, and if its inputs cannot be sourced, the
   generator must emit `low` and report which inputs were
   missing, never silently upgrade.
6. Phone-test serving decision (parent): how
   `edge/out-phase4-fixture/` reaches the test phone, and
   which P4-8/P4-9 timing path is used (fixture windows vs
   re-stamped fixture day). Record the choice in the
   phone-test report; do not improvise it during the test.
   The fixture edge must never be pushed to the pilot Pages
   edge (its LAB-ONLY thresholds would then ship to pilot
   readers — the one Phase 4 footgun, guarded by the
   `lab-fixture-only` source label and by this note).

## 8. Files changed (lab repo only, local commit)

Kotlin (new): `data/MarketHistory.kt`,
`data/NotificationPolicy.kt`.
Kotlin (modified): `data/AppDatabase.kt`,
`data/DatabaseProvider.kt`, `data/SettingsStore.kt`,
`data/ChannelParsing.kt`, `data/EdgeRepository.kt`,
`util/Notifications.kt`, `ui/screens/MarketScreen.kt`,
`ui/screens/WarRoomScreen.kt`,
`ui/screens/SettingsScreen.kt`,
`ui/screens/DiagnosticsScreen.kt`, `MainActivity.kt`,
`app/src/main/res/values/strings.xml` (59 new strings, 259
defined total, 0 duplicates).
Edge tooling (modified): `edge/generate_edge.py`
(market_alerts object + market hash rule),
`edge/publish_edge.py` (market hash recomputation +
market_alerts checks).
Edge tooling (new): `edge/generate_phase4_fixtures.py`,
`edge/verify_phase4_fixtures.py`,
`edge/out-phase4-fixture/` (generated fixture edge: 172
market snapshots, 4 graded radar signals, LAB-ONLY
thresholds, rewritten manifest).
Docs (new): `docs/phase4-report.md` (this file).
NOT changed: any cron, bot, channel, freeze state, live edge
(`edge/out/` untouched — the production generator was run
only to a scratch dir, deleted afterwards), FCM config,
signing config, Phase 2/Phase 3 bar files, or the
pre-existing modified files from other passes
(`docs/vnext-plan.md`, `docs/big-update/new-requirements.md`,
`edge/out/revocations.json` — left exactly as found, not
committed here).

## 9. Verification performed (no build, no phone)

- Brace/paren/bracket balance: BALANCED in all 13 touched
  Kotlin files (script logic, re-run after every edit).
- All 185 `R.string.*` references in touched files resolve
  against strings.xml (259 defined, 0 missing, 0 duplicates);
  strings.xml parses as XML.
- Fixture verifier: 38/38 PASS (see §1.6).
- Publish verify: PASS on the fixture edge, PASS on the
  Phase 2 fixture edge, PASS on a fresh production-generator
  scratch run (market_alerts all-null). FAIL on committed
  `edge/out/` is pre-existing (missing manifest/join_keys,
  §1.5) and unrelated to Phase 4.
- Phase 2 fixture verifier: still "ALL FIXTURE CHECKS PASS"
  (parser back-compat, §6).
- Python files compile (`py_compile` clean).
- Hygiene greps: no blog button/link added, no model
  probability on any surface, no FCM use, no token/code/
  secret in any new file, no live channel id in any fixture
  (all fixture ids are `lab-p4-*` / `lab-p4-radar-*`; the
  linked story join uses a Phase 2 fixture item's story_id),
  no threshold literal in production code (the only 5.0/2.0
  thresholds are the labelled fixture values).
- NOT verified (stated plainly): no Gradle/cloud build, no
  APK, no phone run, no Room migration run (v5→v6 never
  executed), no Compose preview, no notification-drawer
  observation, no Doze behaviour. A Kotlin compile error
  that balance/string checks cannot see (e.g. a type
  mismatch) would surface only at the parent's cloud build.
  The parent's build is therefore a GATE, not a formality,
  before any phone test.

## 10. Constraints honoured

Lab repo only. No push, no cloud build, no APK distribution.
No live cron/bot/channel change. Freeze untouched (all ECO
JOURNAL schedules remain disabled; nothing was re-enabled).
No FCM activation. No test channel created (Yasin-only). No
special-issues publication (frozen line only, section still
`gated_frozen`). No voices publication (registry still
gated, section still `not_yet_published`). No nightly
edition work (Phase 5 scope, section still
`not_yet_published`). No post-program items (no ticker, no
sync-section compaction). No v4 remote-update work. No
tokens, invite codes, or secrets anywhere. No model
probability on any surface. No private report / daily-letter
content. No blog button/link anywhere. Persian digits,
Shamsi date + Tehran time in history labels, one story = one
card (radar join by story_id, never headline hash). If any
P4 gate fails on the phone, that chain stops there — nothing
in this report waives it.
