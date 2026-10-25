# ECO JOURNAL — Version 4: Unified Program Plan

Status: PLANNING ONLY. This document collects, re-orders, and
re-plans everything remaining and in progress into ONE program
published as VERSION 4. It implements nothing, builds nothing,
pushes nothing, and distributes nothing. The freeze stays ON.
No live cron, bot, channel, or service is touched by this plan.
Publication happens only after the plan's remaining execution
completes AND the §4 publish checklist passes in full.

Authority: Yasin's order of 2026-10-25 ~23:35 Tehran — collect
all programs (everything remaining and in progress), re-plan
them together, and publish them under version 4. This supersedes
the earlier framing that treated the big-update phases, the
post-phase items, and the v4 remote-update system as separate
programs. It does NOT change any phase's scope, schedule, or
gate, and it does NOT change the freeze (see §5).

Sources read for this consolidation: `docs/big-update-plan.md`
(965 lines, 54-service register), `docs/big-update/` specs
(phase2, phase3, phases4-5, phase6, phases7-8, dependency-risk
map, ml-track-spec, roadmap, new-requirements), `docs/phase1-
report.md`, `docs/phase2-report.md`, and the lab git log
(Phases 2, 3-Tier-A, 4 committed locally; Phase 5 in progress).

## 1. Consolidated v4 scope — every item, source, state, place

19 programme items consolidate the 54 inventoried services, the
big-update phases, the post-phase backlog, the remote-update
system, and the ML track. "Done" means implemented in lab and
committed locally (NOT phone-verified unless stated); "in
progress" means code is being written now by the executing
agent; "planned" means specified, not coded.

| # | Item | Source | State (2026-10-25) | Place in v4 order |
|---|------|--------|--------------------|-------------------|
| V1 | Update Engine — manifest v3, per-section delta, per-section diagnostics, join_keys/story_id, publish_edge (dry-run only) | big-update-plan §3, §5 Phase 1; phase1-report | DONE in lab + cloud-built + APK verified; Yasin provisionally passed phone test ("seems all good", uninstall + fresh install, pilot-01 re-entered). E1–E6 phone pass provisional, not per-item; E7 blocked (test channel); E8 build half done, phone "updates over" half still needs confirmation on the v4 build | Foundation, already shipped inside v4 line |
| V2 | Channel surfaces — typed formats (6 + WIRE), War Room, SITREP, urgent rail + channel, radar, slots, bundles, Morning Bundle, in-app night law | phase2 spec; phase2-report; commit 594fc43 | DONE in lab (local commit, not built/pushed). Fixture pass only: 48 typed items, 8 bundles, 6 radar signals, 70 checks. First real typed item must re-verify P2-A1–P2-A4 | V4 Phase A |
| V3 | Near-real-time channel → app, Tier A (lab) | phase3 spec; commit 2c0a02a | DONE in lab: bridge staging merge, 3-layer dedup by `channel:<message_id>`, delegated-gate-only promotion, per-step Diagnostics timestamps, lab 60s Pages checker, Tier A timed test PASS | V4 Phase B (Tier A) |
| V4 | Near-real-time, Tier B (rehearsal Pages + phone) | phase3 spec | BLOCKED — needs (a) Yasin's test channel (bot cannot create one; live channel never allow-listed) and (b) rehearsal-edge isolation (edge URL is hard-coded to pilot Pages; a `--real` publish would ship the TEST post to pilot readers). Until one is resolved, Tier B stays blocked and Tier A is the complete test | V4 Phase B (Tier B, gated) |
| V5 | Market history/charts, radar hardening, alerts, notification preferences | phases4-5 spec §4; commit a4048ec | DONE in lab (local commit, not built/pushed). Bubble/spread thresholds OFF (no real keys found in live bot.db; `/setalert` examples 15/2 explicitly barred as defaults) | V4 Phase C |
| V6 | Nightly big blog update — generator, edition schema, archive + search, Nightly section, nightly notification, delegated gate (dry-run during freeze) | phases4-5 spec §5; big-update-plan §4.2 | IN PROGRESS (uncommitted: `Nightly.kt`, `NightlyScreen.kt`, `generate_nightly.py`, `generate_phase5_fixtures.py`). Entry gate U4 OPEN: Tier-A flag, brief score, ownrewrite score are unpinned in current edge data — if unsourced, the generator must HOLD editions with reason, never guess a score | V4 Phase D |
| V7 | Editorial, voices, analysis, special issues (gated) | phase6 spec; big-update-plan §5 Phase 6 | PLANNED. Method page (7 fixed sections, bundled copy); Analysis (op-ed / weekly); Voices GATED — nothing publishes (no pages, badges, or "coming soon" name list) until Yasin finalises the list + `key_voices` + 100-item test with zero attribution errors; Special Issues DESIGNED-NOT-PUBLISHED — preconditions C1–C7 with dated per-post evidence AND a separate order naming posts by inventory ID (the 4 queued posts are NOT a publishable set: 2 only partially author/date-verified, full source texts missing) | V4 Phase E |
| V8 | Polish, accessibility, full pilot checklist | phases7-8 spec | PLANNED. TalkBack walkthrough on a real phone with quoted announcements + recording (open since M8, never closable by screenshot); font extremes 0.85×/1.3×; measured sepia/dark contrast; per-section empty/error/offline matrix; E1–E8 re-verification on the final build | V4 Phase F |
| V9 | Freeze-lift rehearsal (DRY-RUN ONLY) | phases7-8 spec §5; big-update-plan §7 | PLANNED. 11 rehearsal lines R1–R11, each with its exact evidence artifact; scope field left BLANK ("AWAITING YASIN'S SENTENCE — NOT A LIFT"). A rehearsal is part of v4; it is NOT a lift | V4 Phase G (last) |
| V10 | War-room/red-alert + energy category bars — RED bar for typed items, YELLOW bar for energy articles, label + bar (never colour alone), on feed cards and reader headers | new-requirements §0 (Yasin's cross-phase rule) | IMPLEMENTED in Phase 2 (reference implementation); MUST be carried into every later phase and every restyle. Dropping the bar is a regression, not a redesign. NOT a separate tab, NOT a parallel service | Cross-cutting, all v4 phases |
| V11 | CNN-style status ticker bar under the app name — moving, red, with effect, highest quality, carrying live war-room topics | new-requirements §(d) P-1; roadmap | PLANNED (backlog design, no code). Masthead addition only; does not replace the V10 bar; shares the masthead with the V14 wordmark — laid out together, not stacked independently. "Highest quality" must be defined and phone-tested (smoothness, legibility, no jank) before claiming it | V4 Phase H (after A–G) |
| V12 | Compact, non-intrusive sync section — reading view restored | new-requirements §(d) P-2; roadmap | PLANNED (backlog design, no code). Presentation only: must NOT hide failures/error codes, NOT remove per-section force-refresh or Diagnostics, NOT change sync behaviour/cadence/night-law handling | V4 Phase H (after A–G) |
| V13 | Helper module H1 — Reading Ruler + Calm Mode (reader comfort) | new-requirements §(c) | DESIGNED, not implemented. Presentation-only; no new data, no new sync, no reader-behaviour tracking. Implemented only after Phases 1–7 complete and phone-verified; its own phone test | V4 Phase H (H1 first) |
| V14 | Helper module H2 — Story Arc Strip (graphical reading aid) | new-requirements §(c) | DESIGNED, not implemented. Navigation aid over the existing timeline; no new data, no scores, no predictions; no strip for single-version stories; no node for retracted versions | V4 Phase H (H2 second) |
| V15 | Small English script ECO JOURNAL wordmark, header only, like the blog logo | new-requirements §(a) | PLANNED, LICENCE-GATED. Blog font Blacksword is PERSONAL-USE-ONLY: before any implementation, either check the exact grant, buy a licence covering app use (Yasin's decision), or use the default fallback — a freely-licensed OFL script lookalike, never named "Blacksword". Never the NYT name/logo | V4 Phase H (with ticker layout) |
| V16 | Improved tabs system (smoother than ScrollableTabRow + HorizontalPager) | new-requirements §(b) | PLANNED. Acceptance shape: no tab reordering on sync, scroll preserved per tab, swipe/tap in sync, neighbours composed; measured against a phone baseline first. Must NOT move War Room into a feed tab or split red alerts into a separate tab system | V4 Phase H (or polish pass) |
| V17 | v4 remote-update system — edge version manifest, in-app check/download, one-tap install via system installer, shared signing | roadmap §v4 | PLANNED (design recorded, no code). Manifest fields: versionName, versionCode, apk_url, apk_sha256, changelog (Persian), mandatory/optional flag. Hash verified BEFORE offering install; mismatch discards the file. HONEST LIMIT: a sideloaded app cannot silently self-install without the user tapping install (only Play in-app updates do that) — v4 means automatic check + download + one-tap install, never silent install. Content updates already need no APK (manifest v3 delta sync) | V4 Phase I (last code phase; needs its own phasing go-ahead) |
| V18 | ML track aligned to the new structure | ml-track-spec; big-update-plan §6 | PLANNED (spec complete, no training). Label migration feed item → canon story → edge article/short via guid ↔ app_key ↔ story_id; item time = edge-publish time, never sync time; versioned app-publish decisions, no-fake-−1 discipline; war-room VERIFY/publish wiring (Oct 20–24 starvation 1,595→7/day must not recur silently); story-count tension (formula coefficients open, option B recommended, not decided); exact M4 market formulas into features with NULL + missing-indicator, never silent imputation; `has_points_fa` leakage audit (6 steps); calibration gate ≥30 walk-forward days — NO probability reaches any app surface before it passes; shadow `ml_shadow_app` table; scorecard carrier undecided (interim: lab file only); v0 retired, v1 only at 7/7 gate or explicit waiver; ESS frozen at 16/15/15, 14-day pass criterion clock starts at lift, not 2026-11-07; privacy default: NO app reads/bookmarks/searches as ML input without 7 itemised approvals | Parallel track, join-first / volume-second / training-last; never blocks v4 reader release, never trains on a corrupt join |
| V19 | Fix list — errors encountered along the way, recorded but non-blocking for phase gates | new-requirements §(d); roadmap | OPEN LIST, carried forward. Bugs found during Phases 2–8 that do not block a gate are recorded here, not dropped and not allowed to expand a phase's scope mid-flight; they are fixed in the V4 Phase H/I passes | Carried into Phase H/I |

Underlying base (not a v4 item, but v4's foundation): the
54-service register (big-update-plan §1), the vNext M1–M9
baseline (invite gate hash-only, market board with M4 formulas,
Pages edge live, shared debug signing), and the Phase 1 engine
(V1). The 5 explicit exclusions from the plan still stand:
poll voting, the private 23:00 report, the private daily
letter, watchdog screens (diagnostics aggregates only), and
the disabled morning digest (stays off unless re-decided).

## 2. Re-planned v4 phase order, dependencies, gates

Order is execution order. A phase's gate is its entry or exit
condition; a failed gate stops that phase's chain, it is never
skipped silently. Yasin's blanket authorisation (2026-10-25)
covers execution through the phases without asking at each
step — it does NOT cover the freeze lift, the test-channel
creation, voices publication, special-issues publication, FCM,
or Play/release signing (all §5).

1. **V4-0 Foundation (done):** V1 Update Engine + vNext M1–M9
   baseline + shared signing. Gate: Phase 1 phone test —
   PROVISIONALLY PASSED by Yasin ("seems all good"); per-item
   E1–E6 confirmation is still owed and is re-verified on the
   final v4 build in V4-F (E8 phone half: "updates over
   without uninstall" must be confirmed on the phone, not
   assumed from build-green).
2. **V4-A Channel surfaces (V2, done in lab):** depends on V1.
   Gate (exit): P2-A1–P2-A13 fixture passes; first real typed
   item re-verifies P2-A1–P2-A4. Not phone-passed.
3. **V4-B Near-real-time (V3/V4):** depends on V1 + V2.
   Tier A done in lab (timed test PASS). Tier B gated on
   Yasin's test channel AND rehearsal-edge isolation; until
   both resolve, Tier B is BLOCKED and Tier A is the complete
   test. Gate: gate→push ≤5 min target / fail >15 min;
   polling expected ≤60 min after Pages serving / fail after
   2h without recorded Doze deferral; Pages leg has NO
   pass/fail threshold — a 4-hour window expiry is
   INCONCLUSIVE/BLOCKED, not an app failure.
4. **V4-C Market, radar, notifications (V5, done in lab):**
   depends on V1 + V2. Gate: P4-1–P4-12; bubble/spread
   thresholds stay OFF until Yasin sets real ones.
5. **V4-D Nightly big blog update (V6, in progress):**
   depends on V1 + V2. Entry gate U4: Tier-A flag, brief
   score, ownrewrite score must be pinned from real data;
   if unsourced, the generator HOLDS with reason, never
   guesses. Exit gate: P5-1–P5-11 (dry-run edition complete
   during freeze; nightly notification phone-tested on a
   fixture edition; special-issues pair PAUSED while frozen).
6. **V4-E Editorial, voices, analysis, special issues (V7,
   planned):** depends on V1 + V2 (+ V6 for edition
   placement). Gates: voices — Yasin finalises the list
   (registry is 28 slots, only 9 source-confirmed in draft);
   special issues — C1–C7 with dated per-post evidence AND a
   separate order naming posts by inventory ID. Either gate
   unmet = that sub-item ships nothing, while the rest of
   V4-E proceeds.
7. **V4-F Polish + full pilot checklist (V8, planned):**
   depends on all of A–E. Gates: TalkBack walkthrough with
   walk log + recording (a claim without the log fails);
   font extremes; measured contrast; empty/error/offline
   matrix; E1–E8 re-verified on the FINAL build.
8. **V4-G Freeze-lift rehearsal, dry-run only (V9, planned):**
   depends on V4-F. Gates: R1–R11 each with its evidence
   artifact; R11 scope left BLANK. Output: a lift-order draft
   with scope blank — never a lift.
9. **V4-H Post-phase experience (V11–V16, V19, planned):**
   depends on A–G complete and phone-verified. Order inside
   H: V12 sync-compact layout groundwork first (it changes
   the page frame), then V15 wordmark + V11 ticker laid out
   TOGETHER in the masthead, then V16 tabs (measured against
   the pre-H baseline), then V13 H1, then V14 H2, with the
   V19 fix list applied throughout. Each sub-item gets its
   own phone test; none may change sync, notification,
   night-law, bar, badge, or bundle behaviour.
10. **V4-I Remote update (V17, implemented in lab 2026-10-26,
    report docs/v4i-report.md):** depends on H complete + its
    own phasing go-ahead from Yasin. Lab done: edge version
    manifest (version.json, generate/verify tooling with a
    no-placeholder-hash release blocker), launch + 24h checks,
    background download with SHA-256 re-verification,
    one-tap system install via FileProvider, mandatory /
    optional policy, feed row + Settings card. Still open:
    cloud-build compile gate, phone gates (install-over,
    banner/card, mismatch-discard, permission path,
    mandatory/optional, 24h worker, TalkBack), and the
    publish step itself (generate + verify with the REAL v4
    APK — manifest must name 4.0.0 / 4 and the real SHA-256).
11. **V4-ML (V18, parallel, planned):** runs alongside A–I
    but never blocks the reader release. Sequence is rigid:
    join first (story_id migration + bridge staging) →
    volume second → training last. No v1 training set until
    AC-1/AC-2 pass on production-shaped data; no probability
    on any app surface until the calibration gate passes.
    The v4 release may ship with the ML track still in its
    shadow/lab stage — that is a stated state, not a gap to
    hide.

Dependency map in one line: V1 → V2 → (V3 Tier A, V5) →
V6 → V7 → V8 → V9 → V11–V16 + V19 → V17; V18 parallel;
V3 Tier B branches off V3 and waits on Yasin.

## 3. Versioning decision for the release

The stale label must go: every APK since vNext has carried
`versionName 1.0.0-phase2` with `versionCode 1`, which no
longer describes anything in the app. For v4:

- **versionName: `4.0.0`.** Major 4 = the unified program
  (big update + post-phase experience + remote update +
  ML alignment). Minor/patch stay 0 until a v4 fix release
  needs them (4.0.1, 4.1.0 by the usual rules).
- **versionCode: `4`.** Integer, monotonically increasing
  from the current 1 — Android accepts the jump, and the
  number matches the major version so build, manifest, and
  APK can be checked against each other at a glance. Later
  v4 builds increment it (5, 6, …); it is never reused and
  never goes backwards.
- **Package: unchanged** (`com.ecojournal.app`), label
  unchanged (`ECO JOURNAL`), **signing: unchanged**
  (shared debug keystore via GitHub secret, cert
  `45220b15…`) — so the v4 APK installs OVER any
  shared-signed build without uninstall and without
  re-entering the invite code. A phone still on the old
  vNext signature needs one final uninstall + reinstall +
  pilot-01 re-entry, exactly as for the Phase 1 APK.
- **APK file name (pilot/debug line):**
  `app-debug-v4.apk` in `goals/eco-journal/app/build-output/`.
  It is gitignored (never committed). A future release-signed
  build, if Yasin ever decides it, gets its own name
  (`app-release-v4.apk`) and its own decision — it is not
  implied by this plan.
- **Version manifest (V17) and app version must agree:**
  the first v4 release that ships the remote-update system
  publishes a version manifest naming `4.0.0` / code `4`
  (or the then-current code) with its APK SHA-256; a
  manifest naming a version the app cannot verify is a
  release blocker, not a footnote.
- **What the version does NOT mean:** `4.0.0` does not mean
  the freeze is lifted, FCM is on, or the app is on Play.
  Those are separate decisions (§5), and the version label
  must never be read as implying them.

## 4. v4 publish checklist — all must be true before push/build/distribute

"Publish" here means, in order: push the v4 code to
`syfazel/eco-journal-app` main, run the cloud build, verify
the APK, and distribute it to Yasin. Each item is checked,
not asserted. If any item fails, publishing stops at that
step and the failure is reported, not worked around.

- [ ] **PUB-1 Code complete:** V4-A through V4-F committed
  locally, phase-labelled (Phase 2, Phase 3 Tier A,
  Phase 4, Phase 5, Phase 6, Phase 7), working tree clean
  apart from explicitly excluded files; no debug, fixture,
  or test-channel data in the release tree.
- [ ] **PUB-2 Gates honest:** every phase gate in §2
  recorded pass / blocked / held with its artifact; no
  gate marked pass on a fixture where the spec requires a
  phone pass; Tier B, voices, special issues, and nightly
  U4 recorded in their true state (blocked/held if so).
- [ ] **PUB-3 Cloud build green:** the GitHub Actions
  workflow on the pushed commit concludes `success`; a
  red build is fixed (max two fix rounds, as in Phase 1)
  or publishing stops.
- [ ] **PUB-4 Shared signing verified:** the APK's signing
  certificate SHA-256 equals the shared debug cert
  (`45220b15…`) via apksigner; a mismatch stops
  distribution outright — never ship an APK that forces
  Yasin into an uninstall surprise.
- [ ] **PUB-5 APK identity verified with aapt:** package
  `com.ecojournal.app`, versionName `4.0.0`, versionCode
  `4` (or the then-current code, matching the manifest),
  minSdk 26, targetSdk/compileSdk 34, label `ECO JOURNAL`,
  size + SHA-256 recorded; file saved as
  `app-debug-v4.apk`.
- [ ] **PUB-6 Phone checklist passed on the v4 build:**
  V4-F evidence set complete (TalkBack walk log +
  recording, font-extreme screenshots, measured contrast,
  empty/error/offline matrix, E1–E8 re-verification
  including the phone-side "updates over" confirmation).
- [ ] **PUB-7 No live change:** no cron enabled/disabled/
  run, no bot change, no channel post, no production code
  or database change, no freeze change — verified by
  `git status` scope (lab repo only) and by the frozen
  schedules still reading `enabled: false`.
- [ ] **PUB-8 Freeze untouched in the artifact:** the v4
  APK and edge contain no lift, no live-channel
  allow-listing, no special-issues publication, no voices
  publication, no FCM activation (no Firebase project, no
  `google-services.json`, no sender).
- [ ] **PUB-9 Privacy verified:** Diagnostics copy =
  shown lines exactly; no PII, content, invite code/hash,
  token, device id, or URL-with-parameters anywhere in
  the APK strings or the diagnostics output; no app
  behaviour (reads/bookmarks/searches) sent anywhere.
- [ ] **PUB-10 Distribution note honest:** the delivery
  message states the install path (My Files → Package
  Installer, not the Muse preview), whether an uninstall
  is needed (only if the phone still runs the old vNext
  signature), and that the invite code is re-entered only
  in that case.

## 5. What v4 deliberately does NOT include + Yasin-only steps

**NOT in v4, by design:**
- The freeze lift itself. V4-G is a rehearsal with a blank
  scope field. Lifting happens only on Yasin's explicit
  sentence naming the scope ("lift the freeze for X"),
  after real connection is proven — a vague "everything"
  fails R11 outright.
- FCM / push activation. Push stays dormant for the pilot
  (vNext decision); the near-real-time path is polling +
  local notifications. Activating FCM is real work (project,
  keys, code), not a toggle, and needs its own decision.
- Play distribution / release signing. v4 ships as a
  shared-signed debug APK via direct distribution. Release
  signing and Play are separate decisions with their own
  planning.
- Test-channel creation. The bot cannot create a channel.
- Voices publication and special-issues publication (both
  gated, §2 V4-E).
- The private 23:00 report and private daily letter as app
  content; poll voting; the morning digest revival; domain
  steps (nic.ir, Cloudflare, Pages token) — all stay out
  unless separately decided.
- Any probability, score, or forecast on a reader surface
  before the ML calibration gate passes.

**Yasin-only steps that remain (nothing else can do these):**
1. Phase 1 per-item phone confirmation (E1–E6 detail) and
   the v4-build phone checklist (V4-F evidence).
2. Create a private test channel, make @YASINECOBOT admin,
   and hand over its `-100…` id for the allow-list
   (unblocks Tier B; live channel is never allow-listed
   by rehearsal).
3. Finalise the voices list (28 slots, 9 source-confirmed).
4. Set real bubble/spread alert thresholds (current state:
   OFF; never the `/setalert` examples as defaults).
5. Pin the nightly selection fields (U4) or accept the
   generator holding editions with reason.
6. Decide the Blacksword licence (buy/licence vs OFL
   lookalike) for the script wordmark.
7. Decide release signing / Play (if ever), FCM activation
  (if ever), and the v4-I remote-update phasing go-ahead.
8. The freeze-lift sentence itself, naming the scope, when
   — and only when — everything is really connected.

---

*Companion records (unchanged, still governing their areas):
`../big-update-plan.md` (register + rhythms), `big-update/`
specs (per-phase acceptance criteria), `../phase1-report.md`,
`../phase2-report.md`, `big-update/roadmap.md` (remote-update
design detail), `big-update/new-requirements.md` (cross-phase
bar rule, wordmark/tabs/helper/ticker/sync designs). Where this
plan and a companion disagree on ordering, this plan governs
the v4 order; where they disagree on a phase's acceptance
criteria, the phase spec governs.*
