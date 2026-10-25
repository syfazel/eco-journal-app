# Phase 6 — Editorial, Voices, Analysis, Special Issues: implementation report (LAB ONLY)

Date: 2026-10-25 (Asia/Tehran). Spec:
`docs/big-update/phase6-editorial-voices-spec.md` (all sections,
criteria P6-M, P6-A, P6-V, P6-S, P6-T, P6-X). Parent plan:
`docs/big-update-plan.md` §5 Phase 6. Decisions: `docs/big-update/v4-decisions.md`
D6 (voices: gating + design + harness built, NOTHING published).

Status: IMPLEMENTED IN LAB, NOT BUILT, NOT PUSHED, NOT
DISTRIBUTED. No Gradle build and no cloud build were run in this
pass (no JDK/SDK/Gradle exists in this sandbox); no live cron,
bot, channel, or freeze state was touched; no FCM activation; no
test channel created; no special-issues publication; no voices
publication; no top-stories card shipped; no v4 components
(ticker, sync compaction, helpers, wordmark, tabs rewrite,
remote update) implemented. Work is committed LOCALLY only — the
parent owns any push/build decision. Phase 7 was NOT started.

READ THIS FIRST — the same honesty shape as Phases 2–5: every
criterion below is LAB-READY (code + fixtures exist and the
fixture side is verified) and PHONE-PENDING (no phone run
happened), except the criteria that are BLOCKED on Yasin's own
acts, which are marked BLOCKED and cannot be lab-passed at all.
"Lab-ready" is NOT a pass. A criterion passes only on Yasin's
real phone (or a phone he designates), and a gated surface
opens only when its own gate passes — never because code exists.

## 1. What was built

### 1.1 Pure domain + parsers (no Android UI dependency)

- `app/src/main/java/com/ecojournal/app/data/Phase6Domain.kt`
  (new): Method sections M1–M7 fixed order + version 1 +
  bundled-copy Shamsi date ۱۴۰۵-۰۸-۰۳ (2026-10-25, Phase 2
  Jalali anchors); analysis bands (op-ed 1000–3000, weekly
  3000–5000, monthly-ml 2000–4000 recorded but gated);
  structure order thesis → evidence → counter-argument →
  scenarios; consensus rules (label «سیگنال‌ها، نه پیش‌بینی‌ها»,
  minimum 2 outlets); top-stories rules (basis labels, §5.4
  decision flag = false); voices gate (FINALISED = false,
  spec §3.6 placeholder verbatim, draft 28 slots / 9
  source-confirmed as lab numbers only); `KeyVoiceDesign`
  (the §3.5 field design as a data class — DESIGN ONLY, zero
  rows created); special-issues gate (FROZEN = true, C1–C7
  list, 4 queued posts noted as not publishable); bar rule
  (`Phase6Bars`: format_type → RED, energy → YELLOW, red
  outranks energy — the same rule as Nightly EditionItemBar);
  pure validation (analysis / consensus / top-stories gates).
- `app/src/main/java/com/ecojournal/app/data/Phase6Parsing.kt`
  (new): org.json parsers. An item is HELD (never rendered,
  never padded, never auto-filled) on: missing structure
  section, out-of-band length, <300 words, monthly-ml type,
  probability/forecast key, non-allow-listed verbatim badge,
  non-http(s) or blog source URL, or a consensus matrix that
  fails its own gate (single outlet / missing fields / bad
  link). A top-stories card is rejected on: any vote field,
  missing week range, unknown basis, or story count outside
  1–4. The parsers create NO voice row and NO special-issue
  page — voices/special issues parse only their frozen states.

### 1.2 Screens + navigation (no DB migration — deliberate)

No Room schema change was needed: Analysis reads the nightly
editions already synced (their `analysisJson`, stored verbatim
on the edition row by Phase 5), so no migration risk was taken
in this pass and no existing table was altered.

- `ui/screens/MethodScreen.kt` (new): all 7 sections M1–M7 in
  fixed order + version/Shamsi line, bundled copy, offline by
  construction. NO source buttons are rendered on the page, so
  no button can ever point at the blog (P6-M4). No personal
  name, no AI trace, no private-report content, no model
  probability, no internal cap number presented as a reader
  promise (P6-M3) — caps are described in plain terms only.
  Decision recorded: M2 names no person (the delegation is
  described as the desk's delegated authority, 2026-10-18);
  naming the delegator on a reader surface would sit uneasily
  with the spec's own no-personal-name rule, so the copy
  avoids it. If Yasin wants his name on M2, that is a one-line
  copy change with his explicit say-so, not a lab inference.
- `ui/screens/AnalysisScreen.kt` (new): standalone Analysis
  section + article detail. List: newest-first by edition
  order, one story = one card across editions (dedup by
  story_id, P6-X2), type badge, thesis line (omitted, never
  auto-generated, when absent), word count (Persian digits),
  «اصلاح شد» badge when correction_count > 0. Detail: the
  four structure sections in fixed order with headings,
  correction note, consensus matrix (agreement cluster
  before divergence, quote in quotation marks, paraphrase
  «به نقل از», «سیگنال‌ها، نه پیش‌بینی‌ها» label), source
  links as text lines (never blog links — the parser already
  held any blog URL). Empty state is the honest line only
  (P6-A5). Held items never render; only the honest held
  line shows, with NO held titles. Top-stories slot shows
  the honest "no card this week" line while the §5.4
  decision is unrecorded — never a fabricated top-4, never
  an editorial pick labelled as a poll result.
- `ui/screens/GatedSectionsScreen.kt` (new): `VoicesScreen`
  renders EXACTLY the §3.6 placeholder text and nothing more;
  `SpecialIssuesScreen` renders EXACTLY «ویژه‌نامه‌ها — متوقف
  است» and nothing more. Both guard with `check()` on their
  gate constants, so a gate flip without replacing the screen
  fails closed instead of silently publishing. No draft name,
  count, tier, sample card, queued title/author/PDF link,
  search entry, or notification exists on either screen.
- `MainActivity.kt`: new top-level tab «تحلیل» (Analysis is a
  standalone section, spec §2 — not a war-room tab); routes
  `analysis`, `method`, `voices`, `specialissues`. Settings
  gains Method / Voices / Special Issues buttons so the
  gated placeholders are reachable from a normal navigation
  entry (spec §3.6/§4.4: absence honest, not hidden).
- `SettingsScreen.kt`: three new buttons only. The Phase 4
  special-issues notification toggle is UNCHANGED and stays
  inert (no producer fires for it — Phase 4 decision 8);
  enabling it while frozen subscribes the reader to nothing.
- `strings.xml`: 41 new strings (342 defined total, 0
  duplicates). Method copy is bundled text, not generated
  text: every section traces to spec §1.1 (M1 doctrine, M2
  delegation, M3 rubric summary, M4 labels, M5 caps/rhythm,
  M6 night-law table + polling limit, M7 exclusions).

### 1.3 Diagnostics (no change needed — stated, not assumed)

Spec §6 requires a per-section Diagnostics line for every
Phase 6 section. That already exists: `voices`
(`not_yet_published`, count 0) and `special_issues`
(`gated_frozen`, count 0) lines come from Phase 1's section
list via `sectionStates()`; Analysis travels in the `nightly`
section, whose line exists since Phase 5; Method is bundled
and needs no line (spec §1.4). No Diagnostics code was
touched, and copy-diagnostics behaviour is unchanged.

### 1.4 Edge fixtures + verifiers (fixture-based testing only)

- `edge/generate_phase6_fixtures.py` (new): builds
  `edge/out-phase6-fixture/` from the Phase 2/4/5 fixture base
  by adding ONE nightly edition (1405-08-04) with 6 synthetic
  analysis items (all bodies are one repeated 10-word lab
  sentence, counted by whitespace split like
  NightlyWords.count — mechanics only, never editorial
  content). Manifest generator string deliberately UNCHANGED
  (Phase 5 string, the only strings `publish_edge.py`
  accepts); only the nightly section hash/count/items are
  recomputed under the Phase 5 full-contents rule (R13).
  Also writes `edge/phase6-topstories-fixture.json`
  (standalone editorial card + vote-field trap object). The
  card is NOT wired into any edge edition: no real week's
  card ships until the §5.4 decision is recorded.
- `edge/verify_phase6_fixtures.py` (new): 25 fixture-side
  checks, ALL PASSING (publishable 2 / held 4 with exact
  reasons, bands, section order, correction count, consensus
  shape + labelling, bar cases, paused special issues,
  gated manifest states, nightly count 3, top-stories label
  + no-vote + no-padding + trap rejection, special-issue
  design schema on a synthetic object only, Phase 6-file
  hygiene). This verifies the FIXTURES and the mirrored
  rules, not the app — the app side still needs the phone
  test (§7).
- `edge/voices_test_harness.py` (new): the §3.7 100-item test
  protocol as runnable code. It REFUSES any registry whose
  `finalised` flag is not true (exit 2) — the draft registry
  v1 has no such flag and is therefore un-runnable by
  construction. `--self-test` proves the logic on fully
  synthetic data: a dropped-voice trap is caught (negative
  control), a clean run passes with coverage 5/100 REPORTED
  (no pass threshold — a threshold would incentivise forced
  tagging), and every matched item is checked for
  completeness + resolvability + quote labels. Exit 3 = test
  ran but FAILED (voices stays on the placeholder); exit 0
  = pass. The harness is built and waiting; it has never
  been run against any real list, because no finalised list
  exists.

## 2. Voices gating status — NOTHING published (evidence)

- Gate: CLOSED. `VoicesGate.FINALISED = false`; no `key_voices`
  row exists anywhere (grep: no `voice_id` populated in any
  app code; `KeyVoiceDesign` is a design class with zero
  instances); no voice page, badge, card, deep-link, search
  entry, or notification exists in the app.
- Placeholder: `VoicesScreen` renders only the §3.6 text. No
  draft name, count ("28 coming"), tier label, or sample card
  appears in any new file (grep-verified: no draft-registry
  name in any Phase 6 Kotlin file, fixture edition, or
  top-stories fixture).
- Draft status, re-verified from the registry file itself
  (not from a summary): 28 slots (10 Iranian incl. 1 blank +
  10 international + 4 contrarians + 4 China incl. Caixin
  Global); 9 source-confirmed ✅ (3 Iranian, 2
  international, 4 China). The remaining 19 slots are pending
  a stable source / continuity / feed — publishing any of
  them now would violate the registry's own 5 criteria.
- What opens it (all required, none present): Yasin's edited
  registry v2+ (§3.2 item 1) + closed blank slot (item 2) +
  confirmed source per retained voice (item 3) + scope line
  (item 4) → `key_voices` built from that version only →
  100-item harness run → P-V1–P-V6 all pass → re-tagging →
  dry-run edge → delegated approval → phone test P6-V4–V7.
  A general freeze lift does NOT finalise the list.
- Harness status: BUILT + SELF-TESTED on synthetic data
  only. It has not been run on the draft (refusal is the
  correct behaviour) and must not be fed the draft as if it
  were finalised.

## 3. Special-issues C1–C7 status — NOT published (evidence)

All seven preconditions are NOT PASSED. The section renders
only «ویژه‌نامه‌ها — متوقف است» (new screen) and the nightly
edition's component 5 stays `paused` with no items (Phase 5
generator rule, re-verified: the Phase 6 fixture edition's
component 5 is `paused`, items []).

Evidence gathered in this pass (read-only) from the service
itself (`~/workspace/yasineco_bot/special_issues/`):

| # | Status | Evidence |
|---|---|---|
| C1 | NOT PASSED | No dated source-page check record exists for the CAP refunds post or the Brookings Black Americans post. The DB rows for both carry `publish_date = NULL` and the Black Americans row's author field is just "بروکینگز" (an outlet, not an author) — partial verification, exactly as the spec warned. Only the source page itself can upgrade this. |
| C2 | NOT PASSED | Same check missing for the other 2 posts (Brookings tariff decision, CFR Boot/Iran oil); their DB rows also carry `publish_date = NULL`. "Fine before the freeze" is not verification. |
| C3 | NOT PASSED | Queue-location discrepancy CONFIRMED unresolved: `queue/` is empty, while 4 drafts sit in `posts/` (01–04) and 4 PDFs in `pdfs/` (01–04), and the DB `posts` table holds 4 rows with status `queued`. No single authoritative inventory file mapping each post to exactly one draft + one PDF + one status exists. |
| C4 | NOT PASSED | `articles/` is EMPTY despite the README describing full texts there. No full source text is stored for any queued post; a rewrite from a summary/snippet/memory is prohibited by the service contract. |
| C5 | NOT PASSED | No per-post gate record exists evidencing (a) source-and-numbers verification, (b) a second opposing edit, (c) a second-generation PDF with cover, for any of the 4 posts. The PDFs in `pdfs/` are not, by themselves, evidence of (a) or (b). |
| C6 | NOT PASSED | No ML-firewall audit (counts = 0) is on file for the queued posts or the service. No audit was run in this pass (it belongs with the reopen file, not as a drive-by query); until it exists, C6 is unmet. |
| C7 | NOT PASSED (design done, dry-run not run) | The issue-page DESIGN is specified (§4.2 fields) and schema-checked on a synthetic object in the Phase 6 verifier, but no lab dry-run render of an issue page + PDF viewer/download from lab data has been executed, and no dry-run record exists. The dry-run proves the renderer, not the content (C1–C5 prove content) — both are still needed. |

Second lock (§4.6): NO separate explicit order exists naming
special issues, its scope (app/channel/both), its posts by
inventory id, or the 12:50/20:50 crons. Both crons stay
disabled; no order was sought or implied in this pass. Until
C1–C7 AND the separate order both exist, the 4 queued posts
stay queued and unpublished — they are NOT a publishable set,
and no partial opening (e.g. "publish the 2 better ones") is
available: the spec allows publication only for posts the
order names AND whose checklist passes.

ML firewall (absolute): no special-issues item enters any
ML-DB row/label/model input, ever. No such row was created
here; the Phase 5 publish verifier's probability-key ban
still applies to every nightly edition (the Phase 6 fixture
edition passes it).

## 4. Top-stories card — code built, content BLOCKED

- Built: `TopStoriesCardParsed` + parser (mandatory
  `week_range`, mandatory `selection_basis`, label derived
  from basis — poll language ONLY on a `poll` card,
  «گزیدهٔ تحریریهٔ هفته» on an `editorial` card), zero
  voting UI (any vote/percentage field rejects the card),
  1–4 stories (never padded), staleness/refresh rules
  documented in the parser contract. Fixture: editorial card
  with 3 stories + a vote-field trap object, both verified
  (label exact, no vote field, trap rejected).
- BLOCKED (§5.4): no written decision exists on whether a
  no-poll week's card is editorially selected or omitted.
  `TopStoriesRules.NO_POLL_DECISION_RECORDED = false`, and
  the Analysis screen therefore shows only "این هفته کارتی
  نیست." — no real card ships, and no card is phone-tested
  as real content. The card's code may be phone-tested with
  lab data, but P6-T5 fails until the decision is recorded.
  This is the card's single most likely failure mode
  (editorial picks labelled as poll results), and the gate
  is what prevents it.

## 5. Method page — built (bundled, offline)

7 sections M1–M7 in fixed order + version line «نسخهٔ ۱ —
به‌روز شده در ۱۴۰۵-۰۸-۰۳», all copy tracing to spec §1.1.
Night-law table states all five lines (day ≥70 instant;
night ≥85 instant; 70–84 → 08:00 Morning Bundle, one bundle
notification; FLASH exempt; L3/L4 WATCH exempt at night)
plus quiet-hours and the ~15-minute polling limit with Doze
honesty. No blog button/link anywhere on the page (no
buttons at all); verbatim rule names only the two permitted
sources; no cap number, threshold, private-report content,
or model probability appears. A Method text change ships
only in a new build; if bundled copy and the published
rubric summary ever disagree, the copy is held from release
until reconciled.

## 6. Cross-phase bar rule — status (file:line)

Yasin's rule (`docs/big-update/new-requirements.md` §0): RED
bar for war-room/red-alert items, YELLOW bar for
energy-domain items, on feed cards and reader headers, inside
the same feed/tabs system. No separate service, no separate
tab for war-room/red-alert. Dropping the bar is a regression.

Phase 6 ADDS one new card type (analysis cards) and one new
detail reader (analysis detail), and both carry the bar:

- `app/src/main/java/com/ecojournal/app/data/Phase6Domain.kt`
  — `Phase6Bars.barFor()` / `labelFor()`: format_type
  present → RED, else category == "energy" → YELLOW, else
  none; red outranks energy. (Rule only; rendering reuses
  the existing strip.)
- `app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt`
  — card use (bar + label above every card title) and
  detail-header use (same bar + label above the title).
  Fixture proof: weekly (GEOPOL) → RED case; op-ed (energy,
  no format) → YELLOW case; energy + GEOPOL → RED (red
  outranks energy), all three asserted in the verifier.
- UNTOUCHED (byte-identical by `git diff` — no diff entries
  for these files): `data/Phase2Domain.kt` (DomainBars,
  RED_ARGB / YELLOW_ARGB, `forChannelItem()` → RED,
  `forArticle()` → YELLOW iff energy),
  `ui/components/ChannelCard.kt` (DomainBarStrip + typed-card
  use), `ui/components/ArticleCard.kt` (energy bar),
  `ui/screens/ArticleScreen.kt` (reader header bar),
  `ui/screens/NightlyScreen.kt` (EditionItemBar),
  `ui/screens/BundlesScreen.kt` (Special Issues line
  «ویژه‌نامه‌ها — متوقف است» unchanged).
- No separate service and no separate tab was invented for
  war-room/red-alert: Analysis is the editorial section;
  red-alert items stay inside it with bar + badge, and the
  War Room section itself is unchanged.

## 7. Per-criterion results P6-M / P6-A / P6-V / P6-S / P6-T / P6-X

Every criterion is LAB-READY (code + fixtures exist and the
fixture side is verified) and PHONE-PENDING unless marked
BLOCKED. "Lab-ready" is NOT a pass. Phone-test procedure for
all: serve `edge/out-phase6-fixture/` as the edge (mechanism:
parent's choice — local static host or a test Pages branch;
NOT the live edge, NOT pushed here), sync, and observe each
item. The fixture edge must never be pushed to the pilot
Pages edge (it inherits Phase 4/5 LAB-ONLY market thresholds
via its base).

- P6-M1 (Method offline, 7 sections in order + version
  line): LAB-READY / PHONE-PENDING. Observe in airplane mode.
- P6-M2 (night-law table, all five lines): LAB-READY /
  PHONE-PENDING. Copy checked against Law 15 in this pass.
- P6-M3 (no-leak: no personal name, AI trace, private
  report, probability, blog link, internal threshold as
  promise): LAB-READY / PHONE-PENDING (copy read-through in
  this pass found none; the phone read-through is still
  required).
- P6-M4 (source buttons → news source, never blog): PASS BY
  CONSTRUCTION in lab (no source buttons on the page at
  all); phone observation is trivial (no buttons to tap).
- P6-A1 (op-ed + weekly, 4 sections in order, navigable):
  LAB-READY / PHONE-PENDING. Fixture items lab-p6-oped-1
  (1700w) + lab-p6-weekly-1 (3600w).
- P6-A2 (consensus matrix: agreement before divergence,
  outlet + position + link + date per row, quotes marked,
  paraphrases «به نقل از», signals label): LAB-READY /
  PHONE-PENDING. Fixture matrix on the weekly item.
- P6-A3 (correction badge + note): LAB-READY /
  PHONE-PENDING. Fixture weekly has correction_count 2.
- P6-A4 (thin held, not padded): LAB (verifier: thin 250w
  held `floor_300`, absent from publishable set, no padding).
  Phone observation: the thin item appears NOWHERE in the
  Analysis list on the fixture edge.
- P6-A5 (empty state, no placeholders): LAB-READY /
  PHONE-PENDING. Observe on the LIVE edge (no analysis
  component published): honest empty line only. Note: on
  the Phase 5 fixture edge the list is also empty, because
  Phase 5 analysis items lack the 4-section structure and
  are correctly HELD by the Phase 6 parser — that is the
  gate working, not a data gap to backfill.
- P6-A6 (monthly-ML absence, calibration gate closed):
  LAB (verifier: monthly-ml fixture item held
  `monthly_ml_gated`; no probability key in the edition) /
  PHONE-PENDING (sweep the Analysis section on the phone).
- P6-V1 (voices placeholder only): LAB-READY /
  PHONE-PENDING. Observe: placeholder text only, no draft
  name/count/tier/sample anywhere, Diagnostics `voices`
  `not_yet_published` count 0.
- P6-V2 (article by a draft-list person renders source
  string only): LAB-READY / PHONE-PENDING. No voice badge or
  page link exists in code to render one.
- P6-V3 (Diagnostics voices line exactly
  `not_yet_published`, count 0, no hash value): LAB-READY /
  PHONE-PENDING (line exists since Phase 1; phone read
  still required).
- P6-V4–V7 (post-finalisation voice pages, test report,
  dropped-voice absence, blank slot): BLOCKED — no
  finalisation exists (§2). These cannot pass, and must not
  be simulated, until §3.2 + §3.7 + §3.8 complete.
- P6-S1 (special-issues placeholder only): LAB-READY /
  PHONE-PENDING. Observe: single line, no queued title/
  author/PDF.
- P6-S2 (nightly slot «ویژه‌نامه‌ها — متوقف» , no stale
  content as new): LAB-READY / PHONE-PENDING (component 5
  `paused` in every fixture edition; phone render on the
  fixture edge still required).
- P6-S3 (Diagnostics `special_issues` `gated_frozen`,
  count 0): LAB-READY / PHONE-PENDING.
- P6-S4 (zero special-issues notifications, toggle on
  included): LAB-READY / PHONE-PENDING (no producer exists;
  the toggle is inert by Phase 4 design).
- P6-S5–S8 (post-open evidence + order, issue pages, ML
  firewall audit, unnamed posts stay queued): BLOCKED —
  C1–C7 unpassed and no §4.6 order exists (§3).
- P6-T1–T4 (card fields, no-voting, label = basis,
  refresh/staleness): LAB-READY via parser + fixture
  (editorial label exact, 3 stories unpadded, vote trap
  rejected); PHONE-PENDING for a lab-data card render, and
  no real card may be phone-tested as content until P6-T5.
- P6-T5 (entry criterion: §5.4 decision recorded): BLOCKED
  — no decision on file.
- P6-X1 (airplane-mode: Method, Analysis last-good, both
  placeholders, last nightly + card, honest offline lines,
  nothing empties): LAB-READY / PHONE-PENDING (Method is
  bundled; other surfaces keep last-good cache by Phase 1
  construction — never emptied by a failed sync).
- P6-X2 (one story = one card, Analysis + edition resolve
  to one row): LAB-READY / PHONE-PENDING (dedup by story_id
  in AnalysisScreen; same story_id as the edition's item).
- P6-X3 (Persian digits, Shamsi + Tehran time, half-spaces
  on every Phase 6 surface): LAB-READY / PHONE-PENDING
  (strings use Persian digits; dates are Shamsi strings
  from data; the phone sweep is still required).
- P6-X4 (Phase 7 covers these surfaces; any failure returns
  to Phase 6): CARRIED — Phase 7 does not waive any criterion
  above.

## 8. Phase 1–5 regression check (explicit)

- Phase 1 paths: articles/shorts/market/channel_refs/invites
  sync blocks UNTOUCHED; manifest parser UNTOUCHED; Room v7
  chain UNTOUCHED (no migration in this pass); legacy index
  sync UNTOUCHED. E1–E8 remain PHONE-PENDING exactly as
  Phases 1–5 left them; Phase 1's E2 phone gate still binds
  Phases 3 and 5 regardless of Phase 6's state.
- Phase 2 paths: typed/bundle/radar sync, TypedCard, War Room,
  Bundles UNTOUCHED (Bundles' Special Issues line unchanged);
  fixture verifier still "ALL FIXTURE CHECKS PASS". P2-A1–P2-A4
  still await the first real typed item for re-verification.
- Phase 3 paths: merge, publish verify, chain recording
  UNTOUCHED except that publish verify now also passes on
  the Phase 6 fixture edge (nightly count 3, no nightly
  FAILs). Tier B remains BLOCKED on the test channel +
  rehearsal-edge isolation.
- Phase 4 paths: market/alerts/notifications UNTOUCHED;
  verifier 38/38 still passes. Thresholds still OFF in real
  state (no `alert_bubble`/`alert_spread` keys); fixture
  thresholds are LAB-ONLY and must never reach the pilot edge.
- Phase 5 paths: nightly sync, edition/dossier pages,
  notification producer UNTOUCHED; verifier still PASS.
  U4 remains BLOCKED — real nightly component-1 editions are
  HELD, never guessed; Phase 6 does not resolve U4 and does
  not use fixture selection as a substitute. Phase 5's
  analysis items (single-body, no 4-section structure) are
  correctly HELD by the Phase 6 parser — see P6-A5 note.
- Feed: with zero typed items, unchanged except the added
  Analysis tab (existing tabs keep their order otherwise;
  the new tab sits between Nightly and Market). No feed card
  restyled, no bar removed (§6).
- No Phase 1–5 regression was found by inspection, but E1–E8,
  P2-A1–P2-A13, P4-1–P4-12, P5-1–P5-11 remain PHONE-PENDING.
  If any gate fails on the phone, that chain stops there.

## 9. Files changed (lab repo only, local commit)

Kotlin (new): `data/Phase6Domain.kt`, `data/Phase6Parsing.kt`,
`ui/screens/MethodScreen.kt`, `ui/screens/AnalysisScreen.kt`,
`ui/screens/GatedSectionsScreen.kt`.
Kotlin (modified): `MainActivity.kt` (Analysis tab + 4 routes +
imports), `ui/screens/SettingsScreen.kt` (3 buttons),
`app/src/main/res/values/strings.xml` (41 new strings, 342
total, 0 duplicates).
Edge tooling (new): `edge/generate_phase6_fixtures.py`,
`edge/verify_phase6_fixtures.py`, `edge/voices_test_harness.py`,
`edge/out-phase6-fixture/` (generated fixture edge: Phase 5
base + 1 new edition, 3 nightly editions total),
`edge/phase6-topstories-fixture.json` (standalone card +
vote trap).
Docs (new): `docs/phase6-report.md` (this file).
NOT changed: any cron, bot, channel, freeze state, live edge
(`edge/out/` untouched), FCM config, signing config, bar
files, or the pre-existing modified files from other passes
(`docs/vnext-plan.md`, `docs/big-update/new-requirements.md`,
`edge/out/revocations.json` — left exactly as found, not
committed here).

## 10. Verification performed (no build, no phone)

- Brace/paren/bracket balance: BALANCED in all 7 touched
  Kotlin files by a stateful tokenizer (depth 0, clean end
  state). Known false positive documented, not hidden: a
  naive regex stripper reported Phase6Domain.kt −1 brace /
  +2 parens, contradicted by the stateful scan AND by naive
  counts (0/0) — the same stripper-artefact class Phase 5
  documented for Persian text, not a code imbalance.
- All `R.string.*` references in touched files resolve
  against strings.xml (342 defined, 0 missing, 0 duplicates);
  strings.xml parses as XML.
- Python files compile (`py_compile` clean).
- Fixture verifier: 25/25 PASS (§1.4).
- Voices harness self-test: PASS (trap caught, clean run
  passes, coverage 5/100 reported, no threshold).
- Publish verify: PASS on the Phase 6 fixture edge, PASS on
  the Phase 2 fixture edge, 38/38 on Phase 4, PASS on Phase 5
  (regression suite intact).
- Hygiene greps: no blog URL in any Phase 6 file (base-edge
  "live-blog" source URLs are pre-existing Phase 5 data, not
  Phase 6 links — the verifier scopes hygiene to Phase 6
  files for exactly this reason); no model probability key
  in the fixture edition; no token/code/secret in any new
  file; no draft voice name in any new file; no live channel
  id anywhere.
- NOT verified (stated plainly): no Gradle/cloud build, no
  APK, no phone run, no Compose preview. A Kotlin compile
  error that balance/string checks cannot see (e.g. a type
  mismatch, or `Icons.Filled.Article` availability) would
  surface only at the parent's cloud build. The parent's
  build is therefore a GATE, not a formality, before any
  phone test.

## 11. What Phase 7 needs from this phase

1. The Phase 6 surfaces as built here (Method bundled page,
   Analysis section + parser gates, Voices placeholder +
   harness, Special Issues placeholder, TopStories parser) —
   all lab-only, phone-pending, with the per-criterion status
   in §7 as the checklist Phase 7 must re-verify, not waive.
2. Yasin's own acts, in order of gating weight: (a) voices
   finalisation (§C — the only thing that can start P6-V4–V7
   and the real 100-item test); (b) special-issues C1–C7
   evidence + §4.6 order naming posts by inventory id (the
   only thing that can start P6-S5–S8); (c) top-stories §5.4
   decision (editorial vs omit — the only thing that can
   start P6-T5 and any real card); (d) v4 phone checklist
   (§B) covering P6-M/P6-A/P6-V1–V3/P6-S1–S4/P6-X.
3. Publisher confirmations still open from Phases 2–5
   (status vocabulary, attribution shipping, `war_room`
   object, SITREP canonical section names, WIRE cap, radar
   severity ranks and interval cadence, bundle
   `bundle_date_shamsi` format, nightly U4 selection pins,
   U3 clock, U2 pulse window) remain open; Phase 6 adds one:
   whether M2 should name the delegator (see §1.2 decision
   — default is no name; change only on his explicit say-so).
4. The phone-test serving decision (parent): how
   `edge/out-phase6-fixture/` reaches the test phone. Record
   the choice in the phone-test report; do not improvise it
   during the test. The fixture edge must never be pushed to
   the pilot Pages edge.

## 12. Explicitly NOT done in this pass

- Phase 7 (pilot checklist / freeze-lift rehearsal).
- v4 components: CNN-style ticker, sync-section compaction,
  helper modules (H1/H2), script wordmark, tabs rewrite,
  v4-I remote update. None of these were touched, designed
  further, or smuggled into Phase 6 screens.
- No voices list finalised, no `key_voices` row created, no
  100-item test run on real data, no special-issues post
  published or previewed, no top-4 selected, no poll run or
  faked, no real week's card shipped.
- No push, no cloud build, no APK, no cron, no live publish.
  Commit below is local only.
