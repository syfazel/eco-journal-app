# Phase 3 — Near-real-time channel → app: implementation report (TIER A, LAB ONLY)

Date: 2026-10-25 (Asia/Tehran). Spec:
`docs/big-update/phase3-near-realtime-spec.md` (all sections).
Parent plan: `docs/big-update-plan.md` §5 Phase 3.

Status: IMPLEMENTED IN LAB, TIER A ONLY. NOT BUILT, NOT PUSHED,
NOT DISTRIBUTED. No Gradle build and no cloud build were run in
this pass (no JDK/SDK/Gradle exists in this sandbox); no live
cron, bot, channel, or freeze state was touched; no FCM
activation; no test channel created; no special-issues
publication; no voices publication. Work is committed LOCALLY
only — the parent owns any push/build decision.

READ THIS FIRST — what Tier A does and does not prove:
Tier A (spec §5.1) proves the chain UP TO THE PUBLISH GATE:
staging + delegated gate + merge + `publish_edge.py
--verify-only` + delta simulation. It is NOT an end-to-end
latency result and NOT a phone pass. The Pages leg in this
report is a SIMULATED local-server observation at the spec's
60-second cadence, labelled SIMULATED everywhere it appears.
The app legs (manifest seen / item applied / notified) were
NOT measured — no phone runs in Tier A. The Kotlin code that
records them is implemented here; the measurement waits for
Tier B, which is BLOCKED (§6). A Tier A pass must never be
reported as "the post arrived in the app in X minutes".

Entry gates (§0) — honest status:
- G1 (Phase 1 phone test E1–E6): STILL PENDING. Yasin's
  provisional "seems all good" is not a retro-pass, and this
  pass does not claim otherwise. Phase 3 Tier A ran in the lab
  under the task's explicit Tier A scope; the Phase 1 exit gate
  (E2 must pass on the phone before Phases 3/5 start) is still
  open and still binding on any live claim.
- G2 (shared-signature build E8): NOT RUN here (no build).
  The shared-signing workflow is unchanged from 2026-10-25.
- G3 (Phase 2 typed rendering): PRESENT IN LAB (commit
  594fc43, fixture-based, NOT phone-tested). The merged test
  item renders through the existing Phase 2 TypedCard path;
  the first real typed item still triggers re-verification of
  P2-A1–P2-A4.
- G4 (manifest v3 actually serving): NOT PROVEN — the live
  Pages URL serves the pilot edge, and Tier A never pushes to
  it. Lab manifests verify; serving is a Tier B question.
- G5 (test channel + allow-list): NOT DONE — no test channel
  exists (only Yasin can create it). Tier A uses a synthetic
  updates JSON, never a real channel.
- G6 (rehearsal-edge isolation): NOT DECIDED — see §6. Tier B
  is blocked on it.

## 1. What was built

### 1.1 Bridge staging merge (spec §1 step 4)

- `edge/merge_staging.py` (new): staging files + lab queue DB
  -> merged edge in the Phase 2 fixture shapes
  (`channel_typed.json` items, `bundles.json` headers,
  `radar.json` signals + `war_room`, manifest v3, join_keys).
  - Identity (Phase 1 rules, unchanged): guid = staging id
    (`channel-<message_id>`), app_key =
    `a-<sha256(guid)[:20]>`, story_id =
    `story-<sha256(guid)[:20]>`, `channel_message_id` = the
    REAL staged post id (never fabricated; a staging item
    without one is skipped, never assigned one), status =
    `posted` (the gate decision `approved` lives in the chain
    object, not in status — Phase 2 parses only
    `posted`/`retracted`).
  - Content hash = sha256 over canonical JSON of the full
    item minus `content_hash` itself (the Phase 2 fixture
    builder rule; the chain object is included, so a chain
    change is a content change and the delta picks it up).
  - Section hashes per spec §2.1: sha256 over canonical JSON
    of the 4-field view (`app_key`, `content_hash`,
    `updated_at_epoch`, `status`), sorted by `app_key`, for
    `channel_typed`, `bundles`, `radar`. A section with no
    items publishes hash-of-empty-list, count 0, status
    `published_empty` — never `not_yet_published` once
    shipped (verified by the empty-base exercise, §4).
  - Bundles: a gate annotation may name a bundle; an existing
    header gains the member appended in `member_app_keys`
    order, positions recomputed 1..N from that order (Phase 2
    §8.3: member order wins). A new header requires
    `bundle_kind` (one of the 6 Phase 2 kinds;
    `morning_digest` refused: disabled 2026-10-22, never
    rendered) + `bundle_date_shamsi` (edge-supplied, never
    derived by this script). Untouched bundle headers keep
    their stored `content_hash`, so the bundles section hash
    is unchanged for them and the app does not refetch them
    (a merge that rewrote every bundle hash would be a
    silent full-refetch regression — caught in review, fixed
    before the test run).
  - Radar: preserved VERBATIM from the base edge. The merge
    never infers a `war_room` level from a SITREP — the War
    Room header falls back to the latest SITREP level per
    Phase 2 decision 3. `attribution` is carried from the
    gate annotation (Phase 2 addition); `war_room` is carried
    from the base radar file (Phase 2 addition).
  - Manifest: `generated_at_utc` becomes the merge run time;
    the generator string becomes the Phase 3 merge string
    (verify accepts the Phase 1 string for unmerged edges
    and this string for merged edges, nothing else). All
    other sections are copied verbatim from the base.
    join_keys gains one entry per merged item.
  - Exit codes: 0 merged (or, without `--strict`, approved
    items merged and the rest reported as skipped); 3
    `--strict` with an unmergeable staged item (pending /
    incomplete / invalid annotation — rejected and
    already-published items are legitimate skips and do NOT
    fail `--strict`); 4 staging conflict (F1 halt, nothing
    written); 1 other error; 2 bad arguments.
  - Two real bugs found by the test run and fixed (not
    hidden): (1) the staging-file glob also matched gate
    annotations (`channel-<id>.gate.json`), causing a false
    F1 halt on the first run — fixed by matching exactly
    `channel-<digits>.json` in both the merge and
    `channel_reader.py`'s cumulative manifest; (2) the first
    run's merge failure crashed the orchestrator on a missing
    output dir — the orchestrator now aborts cleanly with a
    partial chain record instead of a traceback.

### 1.2 Dedup (3 layers + merge layer, all by id)

Keyed by `channel:<message_id>` at every layer — NEVER by
headline hash (the 2026-10-23 transliteration defect:
آتلانتیک/اتلانتیک queued 4×):
- L1 staging dir: filename `channel-<message_id>.json` is
  unique by construction; a second capture stages nothing
  new (exercised: F10, §4).
- L2 queue: idempotency key `channel:<message_id>` is the
  queue PK; duplicates refused (bridge_lib, unchanged).
- L3 published edge: a message_id already present in the
  base `channel_typed` items (or join_keys) is skipped as
  `already_published` — never a second card.
- L4 merge conflict (F1, HALT): staging index <-> staging
  files disagreement, two staging files mapping to one guid
  with different bodies, or a base item carrying the same
  `channel_message_id` under a different app_key. Any F1
  aborts the whole run with exit 4 and writes NOTHING.

### 1.3 Delegated gate (spec §1 step 3)

- Only queue rows with status `approved` merge. The decision
  is recorded by the existing `bridge/control.py` approve
  (`review -> approved`) under the delegated editorial
  authority; the merge carries its provenance verbatim into
  the edge item's chain object:
  `muse-editorial (delegated by Yasin 2026-10-18)`.
- `review` (no decision) -> `gate_pending`, skipped (F3
  hold). `rejected` -> `gate_rejected`, skipped (F2, a
  legitimate editorial outcome, NOT a system failure). Any
  other status -> `gate_not_approved`, skipped. Unapproved
  items NEVER reach the edge, even without `--strict`.
- Gate annotation (`<stem>.gate.json` next to the staging
  file) carries the typing decision — format, score, level,
  slot, attribution, link, bundle membership. NOTHING is
  inferred from the post text: a missing annotation means
  the item is not typed yet and stays unmerged
  (`gate_incomplete`). Validation: 7 formats only; score
  null or 0..100; level null or L1..L4; slot null or the 4
  desk slots; `verbatim` only when the source name contains
  «دنیای اقتصاد» or «تابناک» (the Phase 2 allowlist — a
  false verbatim claim is rejected with
  `verbatim_not_allowed`, never silently downgraded); a WIRE
  item with a non-null link is rejected (source name only,
  no link, spec §2.2).

### 1.4 Publish verification, extended for Phase 3

- `edge/publish_edge.py`: new `--verify-only` mode — verify
  an EXISTING edge dir WITHOUT regenerating it. This is the
  only mode that may verify a merged edge: `--dry-run`
  regenerates from the production DB and would wipe merged
  items. `--verify-only` changes nothing in git and never
  pushes. The in-root `--out` guard now applies only to
  `--dry-run`/`--real` (which regenerate/push);
  `--verify-only` is strictly read-only, so it may verify
  scratch copies (e.g. /tmp) — Tier A failure exercises
  depend on that (found by the test run: the first run's
  F4/bundle/empty exercises failed with exit 2 "REFUSED"
  before this fix, because their scratch dirs are in /tmp).
- `verify()` now accepts 11-section (Phase 1) or 12-section
  (Phase 3, `channel_typed` added) manifests, and verifies
  file-backed `bundles`/`radar`/`channel_typed` sections:
  hash recomputed from the file, manifest items
  field-by-field, content hashes recomputed under the
  fixture rule, `published_empty` shape enforced, and chain
  objects validated (all 8 fields present, exact delegated
  provenance, `gate_decision` = `approved`, chain
  message_id = item message_id, `edge_generated_epoch` =
  manifest `generated_at_utc` epoch). Join coverage: index
  entries + one entry per CHAIN-carrying (merged) typed
  item — pre-merge fixture typed items without a chain are
  exempt, preserving the Phase 1 "coverage still = articles
  + shorts" rule for them (this distinction was found by
  running verify on the unmerged fixture edge, which
  initially failed on 48 fixture items that have no join
  entries by design).
- Regression proven: `--verify-only` PASSES on the unmerged
  Phase 2 fixture edge (30 OK, 0 FAIL) AND on a synthetic
  11-section Phase 1 edge built from it (PASS). Phase 1
  verification behaviour is unchanged for unmerged edges.

### 1.5 Pages checker (spec §3.3, lab-side)

- `edge/check_pages.py` (new): polls `manifest.json` at the
  given URL every `--interval` seconds (default 60, per
  spec) and records the FIRST fetch whose section hash
  equals the committed hash. `pages_first_seen` is
  INTERVAL-CENSORED: the report states the Pages leg as a
  WINDOW (previous check, first matching check], never as
  an exact second.
- Timeout semantics refined by the test run (the first run
  mislabelled an unreachable edge as `bad_manifest`):
  served-with-old-hash -> `pages_not_yet_serving` (F6,
  exit 2, INCONCLUSIVE/BLOCKED); fetches succeeded but no v3
  manifest ever parsed -> `bad_manifest` (F7 family, exit
  1); nothing ever fetched -> `edge_unreachable` (F8, exit
  2). All three timeout outcomes are INCONCLUSIVE or
  defect reports — never an app failure, never a reason to
  republish.
- The checker reads the public edge only: no token, no
  invite code, no channel access. It never writes to the
  edge.

### 1.6 Diagnostics chain (spec §3.2, app side)

- `app/src/main/java/com/ecojournal/app/data/ChainRecord.kt`
  (new): pure parser/formatter for the spec §3.1 chain
  object (edge fields, UTC epoch seconds) + app-side fields
  recorded locally at sync. No Room schema change: the
  latest chain lives in the existing meta table
  (`p3_latest_chain`), so no migration is needed.
- `EdgeRepository.kt`: `syncChannelTypedSection` records the
  latest chain-carrying item applied by a sync
  (`app_manifest_seen_epoch` approximated by sync start —
  the manifest is fetched first, at sync start — documented
  in code; `app_item_applied_epoch` = post-transaction
  instant; a re-sync keeps an already-recorded notification
  time). `dispatchPhase2Notifications` stamps
  `app_notified_epoch` ONLY when the urgent notification
  actually fires (an item arriving while the toggle is off
  is marked notified without firing, so its chain keeps
  `app_notified_epoch` null — a valid value, not a missing
  measurement, per spec §3.1). `sync()`/`syncOnly()` thread
  the poll path (`manual` vs `periodic_or_foreground`).
- `DiagnosticsScreen.kt` + `strings.xml` (20 new strings):
  "Near-real-time chain — latest item" section, one line
  per step in order with its Tehran time and gap from the
  previous step; a missing step shows «ثبت نشده است» and
  the header shows «زنجیره ناقص است — آزمون قبول نشده
  است» (never zero, never the previous step's time); the
  Pages line always carries the lab-observation footnote
  (the phone cannot measure Pages deploy time); the
  clock-skew honesty line appears when an app-side time
  precedes edge times by >60 s (skew is never silently
  corrected); end-to-end (post -> app) and poll-path lines
  when known. Copy-diagnostics copies exactly the displayed
  lines (chain lines included in the copy list, like the
  Phase 2 lines). The chain view shows the latest item
  only; full history lives in the lab test record.
- App-side chain fields are PHONE-PENDING: implemented but
  never executed on a phone in this pass (no build, no
  install). The Tier A chain record marks them null by
  design, not by omission.

### 1.7 Tier A timed test

- `edge/run_phase3_tier_a.py` (new): the full Tier A
  protocol with per-step timestamps, owner, and artifact
  per step (§2), plus all failure exercises on scratch
  copies (§4). Lab dirs live in `/tmp/phase3-tiera`
  (staging + queue copies only — the real
  `bridge/staging/` and `bridge/queue.db` are untouched);
  the merged lab edge is `edge/out-phase3-tiera/` (repo,
  local only).
- `edge/verify_phase3_tiera.py` (new): standalone Tier A
  gate — runs `publish_edge.verify()` plus T1 dedup,
  T2 chain monotonicity + Tier A `edge_pushed_epoch` =
  null, T3 `war_room` preserved verbatim, T4 gated sections
  unchanged, T5 hygiene (no live-channel handle, no
  secret-like pattern, no fixture-id collision), T6 join
  entries for chain-carrying items.

## 2. Per-step timing results (Tier A run, 2026-10-25)

Test post: TEST-marked, invented-as-test (never real news),
WIRE/non-urgent, message_id 9000101 (lab fake id, >= 9000001,
distinct from fixture ids 9000001/9000002), app_key
`a-65637ec0033151d514d6`, story_id
`story-65637ec0033151d514d6`. Full machine-readable record:
`docs/big-update/phase3-tier-a-chain-record.json`.

| Step | Owner | Artifact | Tehran time |
|---|---|---|---|
| Test post created (M0) | Lab operator (simulates Yasin's test-channel post) | Synthetic updates JSON (in-memory, TEST-marked) | 23:01:02 |
| Bridge capture (M1) | `bridge/channel_reader.py` | Lab staging `channel-9000101.json` (+ `.gate.json` annotation) | 23:01:02 |
| Gate decided (M2) | Muse editorial, delegated 2026-10-18 | Lab queue.db `channel:9000101` status `approved` | 23:01:02 |
| Staging -> edge merge (M3) | `edge/merge_staging.py` | `edge/out-phase3-tiera/channel_typed.json` (49 items: 48 fixture + 1 merged) | 23:01:03 |
| Publish verify (M4 analogue) | `edge/publish_edge.py --verify-only` | Verification report (stdout), Tier A verifier | 23:01:03 |
| Delta simulation | Lab manifest comparison | Base vs merged manifest | 23:01:03 |
| Pages checker started | `edge/check_pages.py` (60 s cadence) | Checker report (pending) | 23:01:03 |
| Pages first seen (SIMULATED) | `edge/check_pages.py` | `/tmp` checker report, window (see below) | 23:03:03 |
| App legs | — (no phone) | — | NOT MEASURED |

Derived legs (spec §5.4 naming):
- Capture M1−M0: 0 s (same-second lab capture).
- Staging -> gate wait M2−M1: 0 s IN THE LAB (gate
  decision taken immediately after staging). Real gate
  deliberation is UNBOUNDED BY DESIGN and excluded from
  every mechanical total — a 0 s lab wait says nothing
  about real deliberation time and must not be quoted as
  "gate time".
- Merge M3−M2: 0 s at epoch-second granularity.
- Mechanical chain, gate decision -> merged edge verified:
  1 s. Against spec L-1 (target <=5 min, fail >15 min for
  gate decision -> push): the Tier A mechanical leg to the
  PUBLISH GATE is 1 s, inside target — but the push leg
  itself is N/A in Tier A (dry-run: nothing pushed), so
  L-1 is met only up to the gate, not end-to-end.
- Pages leg (SIMULATED): served between check epochs
  1792956723 and 1792956783 — a 60 s interval-censored
  window, by construction (server started 70 s after the
  checker, so poll 1 missed and poll 2 hit). This is a
  demonstration of the windowing, NOT a GitHub Pages
  measurement: real Pages is typically 1–3 min with NO
  guaranteed bound (spec §2, L-2: no pass/fail threshold).
- Polling leg: SIMULATED via delta only — exactly 1
  channel_typed fetch, 0 fetches for all 11 other sections
  (articles, shorts, market, channel_refs, invites,
  revocations, bundles, radar, nightly, voices,
  special_issues); E2 shape (base vs base) = 0 fetches
  everywhere. Phone polling (spec L-4: expected <=60 min,
  fail after 2 h without recorded Doze deferral) was NOT
  measured — no phone, no Doze state, no poll grid.
- Overall end-to-end M7−M0: NOT CLAIMED. No app-side
  timestamp exists in Tier A, so no end-to-end figure is
  reported. The plan's "typically ~20–35 min polling, no
  guaranteed bound" remains the only claim the app may
  make, and Tier A neither confirms nor refutes it.

Verdict for the Tier A run: TIER A PASS (lab) — 34/34
checks pass, 0 failures. This verdict covers the chain up
to the publish gate only.

Timing honesty note: the lab legs are 0–1 s because the
lab runs capture -> gate -> merge back-to-back in one
process. They prove the MECHANISM (each step's artifact
exists and verifies, timestamps monotonic, dedup holds),
not real-world latency. Any report quoting "1 second"
without the lab context would be a misrepresentation.

## 3. Failure-mode coverage (spec §4, F1–F12)

All exercised on scratch copies except where marked
N/A/phone-only. "Exercised" means the failure was
triggered and the specified recovery/state observed, not
merely reasoned about.

| ID | Result |
|---|---|
| F1 staging merge conflict | EXERCISED. Staging index/file disagreement (file deleted, index kept) -> merge exit 4, no output dir written, report records `staging_conflict`. (Also caught the real `.gate.json` glob false alarm during development — fixed, §1.1.) |
| F2 gate rejection | EXERCISED. Post 9000102 staged, gate `rejected` -> merge exit 0, item excluded, report reason `gate_rejected`, merged list empty. Correct: rejection is an editorial outcome, not a failure. |
| F3 gate hold / deliberation | EXERCISED. Post 9000103 left in `review` -> merge `--strict` exit 3, no output written. Deliberation time excluded from latency totals. |
| F4 publish verification failure | EXERCISED. Corrupted merged copy (one `content_hash` zeroed) -> `--verify-only` exit 1, nothing staged/committed/pushed; `git status` identical before/after. |
| F5 publish commit/push failure | NOT APPLICABLE in Tier A — no push ever runs. The recovery rule (failed commit aborts before push; never push a different commit to "catch up") is implemented in `publish_edge.py` from Phase 1 and unchanged. |
| F6 Pages delay / stale manifest | EXERCISED (true stale case). Base fixture served while expecting the merged hash -> checker exit 2, status `pages_not_yet_serving`, INCONCLUSIVE/BLOCKED. No republish. |
| F7 manifest mismatch | EXERCISED (simulation). Unparseable manifest -> `bad_manifest`, old state retained, nothing applied. |
| F8 poll failure / unreachable edge | EXERCISED. Closed port -> checker exit 2, status `edge_unreachable` (distinct from `bad_manifest` after the §1.5 fix). App-side per-section `failed (network)` + backoff behaviour is Phase 1 code (E4), phone-pending. |
| F9 Doze / battery deferral | NOT MEASURABLE in lab — phone-only. Rule documented and carried: deferral is expected platform behaviour, reported as "deferred by Doze, re-measured on wake", never an app defect, never "fixed" by changing battery settings as a test precondition. |
| F10 duplicate card | EXERCISED. Same updates file processed twice -> 0 new staged files, 1 queue row, 1 merged card (49 typed items, 1 chain-carrying). Dedup verified again by T1 (no duplicate `channel_message_id` in the merged edge). |
| F11 wrong type / wrong urgency | EXERCISED. Annotation with `format_type` = `BREAKING` -> merge rejects with `invalid_annotation`, `--strict` exit 3, nothing written. (NULL/wrong `channel_message_id` is covered by the S4 E7 checks, which pass.) |
| F12 allow-list breach | EXERCISED (negative check). No `bridge/channel_allowlist.json` exists in the lab tree (empty allow-list = no live channel authorised, per bridge_lib); the merged edge contains no live-channel handle; the lab queue/staging contain no allow-list entry. The live numeric id is not known to this lab run, so spec §7.5(a)+(b) are the control, as the spec allows — no id was invented to compare against. |

New fixed error codes used (spec §4, Phase 3 additions):
`staging_conflict`, `gate_rejected` (editorial outcome, not
an error), `pages_not_yet_serving`, `chain_incomplete`
(Diagnostics header when a step is missing — implemented,
not triggered in Tier A because the lab chain is complete
up to the gate). Existing codes (`bad_manifest`,
`bad_market`, `invite_fetch`, `unknown_section`, per-section
network codes) are unchanged. Merge-side skip reasons
(`gate_pending`, `gate_incomplete`, `invalid_annotation`,
`verbatim_not_allowed`, `already_published`,
`no_message_id`) are reported in the merge report, not
surfaced as app error codes.

## 4. What Phase 4 needs from this phase

1. The production edge generator must emit `channel_typed`,
   `bundles`, and `radar` sections in the fixture shapes —
   today ONLY the lab fixture builder and this merge emit
   them. The merge is a lab post-step on a fixture base; it
   is not a production generator. (Phase 2 §7 item 1,
   still open.)
2. Publisher confirmations (first-real-item re-verification
   for P2-A1–P2-A4): status vocabulary (`posted` only, or
   also `approved`? — this merge ships `posted` and carries
   `approved` in the chain only); `attribution` shipping;
   `war_room` object shipping (or the SITREP fallback
   confirmed); SITREP canonical section names (O-2); WIRE
   cap/budget (O-1); radar severity ranks and interval
   cadence per crisis level; bundle `bundle_date_shamsi`
   format (Latin `YYYY-MM-DD` assumed here).
3. Night Desk live verification (O-4): fixtures + this
   merge prove rendering/shape only; the live 00:05 run
   never ran before the freeze and remains unverified
   until lift.
4. Tier B prerequisites (§6) before any phone latency
   measurement: test channel (Yasin-only), single-entry
   allow-list verified in-session, rehearsal-edge isolation
   decision (Option A or B, in writing), Phase 1 E1–E6
   phone pass, Phase 2 phone pass, shared-signature build.
5. App-side chain fields need a phone test: the Diagnostics
   chain view renders from meta, but no phone has ever
   synced a chain-carrying item. The Tier B phone test must
   assert the full §3.2 screen (all steps, gaps, Pages
   footnote, incomplete header on a chain missing a step).
6. The `poll_loop` wiring (spec §0 item 1) is still
   code-only: `bridge/channel_reader.py::poll_loop` raises
   unless enabled and is never executed by `main()`. Tier A
   feeds it synthetic updates JSON instead of live polling.
   Wiring it to the staging path with the allow-listed test
   channel only is Phase 3 implementation work that remains
   — it must NOT be wired to any live channel id.
7. Market alert thresholds (O-5) remain unrecovered — never
   guessed here; Phase 4 work.

## 5. Cross-phase bar rule — status (UNTOUCHED, no regression)

Yasin's rule (`docs/big-update/new-requirements.md` §0):
war-room/red-alert items AND energy-domain items keep their
distinct category treatment — a red or yellow bar/strip on
feed cards and reader headers, inside the same feed/tabs
system. No separate service, no separate tab for
war-room/red-alert. Dropping the bar is a regression.

Phase 3 adds NO new card type and restyles NO card:
- The merged typed item renders through the existing Phase 2
  `TypedCard`, which renders the RED bar for every typed
  item (`app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt:60-84`
  `DomainBarStrip`; use at lines 240–242 with
  `DomainBars.forChannelItem()` = RED).
- Energy articles keep the YELLOW bar
  (`ui/components/ArticleCard.kt:111,162-163,224-225`;
  `ui/screens/ArticleScreen.kt:219-221`).
- Bar definitions unchanged:
  `data/Phase2Domain.kt:24-37` (`DomainBars`, RED_ARGB line
  28, YELLOW_ARGB line 29, red-alert-outranks-energy rule
  lines 32–36).
- The only new app surface is Diagnostics (chain view) —
  not a card, not a feed, no bar applies, and no bar was
  removed from any surface. `git diff` confirms
  `ChannelCard.kt`, `ArticleCard.kt`, `ArticleScreen.kt`,
  and `Phase2Domain.kt` are byte-identical to Phase 2.
- War Room stays a top-level section (not a feed tab);
  red-alert items stay in the feed with bar + badge;
  energy items stay in their category tab with the yellow
  bar. Special Issues still renders only
  «ویژه‌نامه‌ها — متوقف است»; the disabled morning digest
  renders nowhere (merge refuses `morning_digest` bundle
  kind).

## 6. Tier B blockers (EXPLICIT, do not work around)

Tier B (rehearsal Pages + phone, spec §5.1) is BLOCKED.
Neither blocker was worked around, and no test content was
shipped to the pilot edge.

Blocker 1 — no test channel, no allow-list change.
No test channel exists, and only Yasin can create one (a
bot cannot create a channel via the Bot API — there is no
such method). The live channel (@EcoJournalFa) was NEVER
allow-listed: no `bridge/channel_allowlist.json` exists in
the lab tree at all, and no allow-list change was made.
Tier A used a synthetic updates JSON, never a real
channel. Until Yasin completes spec §7 (create the private
test channel, make @YASINECOBOT admin, obtain the -100… id
from a real `channel_post` update, allow-list exactly that
one id), there is no channel for any rehearsal to read
from. The allow-list variable question (§7.4) is RESOLVED
for the send direction from the wired code: the bridge
reads `BRIDGE_CHANNEL_ID` (test channel) for live-send
gating (`bridge/bridge_lib.py`, `publish_worker.py`); the
fallback doc's `TEST_CHANNEL_ID` is not read by the wired
code and was NOT set blindly alongside it.

Blocker 2 — rehearsal-edge isolation does not exist.
The app's edge URL is hard-coded to the pilot Pages URL
(`EdgeConfig.BASE_URL =
"https://syfazel.github.io/eco-journal-app/"`,
`EdgeRepository.kt`); Phase 1 built no override, and
Phase 3 Tier A built none either (a debug-only override
is Tier B implementation work, not a Tier A deliverable).
Consequence, stated plainly: any `publish_edge.py --real`
run would ship the TEST post to pilot readers. Tier B
therefore waits on ONE of: (A) a debug-only rehearsal
build with an edge-URL override pointing at a separate
rehearsal Pages deployment (preferred), or (B) Yasin's
explicit single-run order naming the pilot edge for that
TEST post (recorded verbatim, never a precedent, never
extended to real content or the live channel). Default is
(C) Tier A only — a complete, honest outcome, not a
failure, but also not a latency claim.

## 7. Files changed (lab repo only, local commit)

Python (new): `edge/merge_staging.py`,
`edge/check_pages.py`, `edge/verify_phase3_tiera.py`,
`edge/run_phase3_tier_a.py`.
Python (modified): `edge/publish_edge.py`
(`--verify-only`, 12-section verification, chain
validation, join coverage for merged items, read-only
`/tmp` verification), `bridge/channel_reader.py`
(additive `channel_posted_epoch` + `staged_epoch` on
staging items; cumulative manifest excludes `.gate.json`
files).
Kotlin (new): `data/ChainRecord.kt`.
Kotlin (modified): `data/EdgeRepository.kt` (chain
recording, notified stamping, poll-path threading),
`ui/screens/DiagnosticsScreen.kt` (chain section, copy
includes chain lines), `app/src/main/res/values/strings.xml`
(20 new chain strings, 0 duplicates).
Edge (generated, local only): `edge/out-phase3-tiera/`
(merged lab edge: 49 typed items, 8 bundles, 6 radar
signals; TEST-marked item only, fake id 9000101).
Docs (new): `docs/phase3-report.md` (this file),
`docs/big-update/phase3-tier-a-chain-record.json`
(machine-readable Tier A chain record).
NOT changed: any cron, bot, channel, freeze state, live
edge (`edge/out/` untouched), FCM config, signing config,
`bridge/staging/`, `bridge/queue.db`, or the pre-existing
modified files from other passes (`docs/vnext-plan.md`,
`docs/big-update/new-requirements.md`,
`edge/out/revocations.json` — left exactly as found, not
committed here).

## 8. Verification performed (no build, no phone)

- `py_compile` clean on all new/modified Python files.
- Brace/paren/bracket balance: BALANCED in all 3 touched
  Kotlin files; all 69 `R.string.*` references in touched
  files resolve against `strings.xml` (200 defined, 0
  missing, 0 duplicates); `strings.xml` parses as XML.
- Bridge test suite: 29/29 OK (`bridge/test_phase2.py`,
  unaffected by the additive staging fields).
- Tier A orchestrator: 34/34 checks PASS (see §2/§3).
- Publish verify: PASS on the merged edge, PASS on the
  unmerged fixture edge (30 OK), PASS on a synthetic
  11-section Phase 1 edge.
- Hygiene greps: no blog button/link added, no model
  probability on any surface, no FCM use, no token/code/
  secret in any new file, no live channel id in any
  fixture, staging, queue, or merged file (all test ids
  NULL or >= 9000001; test chat id is the non-numeric
  string `lab-test-channel`, never a real -100… id).
- NOT verified (stated plainly): no Gradle/cloud build,
  no APK, no phone run, no Room migration run (none
  needed — no schema change), no Compose preview. A Kotlin
  compile error that balance/string checks cannot see
  (e.g. a type mismatch) would surface only at the
  parent's cloud build. The parent's build is therefore a
  GATE, not a formality, before any phone test.

## 9. Constraints honoured

Lab repo only. No push, no cloud build, no APK
distribution. No live cron/bot/channel change. Freeze
untouched (all ECO JOURNAL schedules remain disabled;
nothing was re-enabled). No FCM activation. No test
channel created (Yasin-only). No special-issues
publication (frozen line only, merge refuses
`morning_digest` and ships no special-issues content). No
voices publication (registry still gated). No tokens,
invite codes, or secrets anywhere. No model probability
on any surface. No private report / daily-letter content.
No blog button/link anywhere. Persian digits, Shamsi date
+ Tehran time in bylines, one story = one card (dedup by
`story_id`/`channel_message_id`, never headline hash).
If any Tier A gate had failed, that chain would have
stopped there — the two genuine failures found in run 2
were fixed at their owning layer (merge glob,
verify-only guard, checker status taxonomy) and the full
run re-executed to PASS, not waived.

## 10. Out-of-scope notes (recorded, not implemented)

- The two post-phase items recorded 2026-10-25 ~22:51
  (CNN-style animated war-room ticker under the app name;
  compacting the feed sync section) are explicitly NOT in
  phases 2–8 and were NOT implemented here. Phase 3 adds
  no ticker and does not restyle the feed sync section.
- The v4 remote-update item recorded 2026-10-25 ~22:57
  (edge version manifest + background download + one-tap
  install scheduling) is a v4 program, NOT Phase 3, and
  was NOT implemented here. Phase 3's manifest work
  (content delta sync) is unrelated to APK update
  delivery and must not be conflated with it.
