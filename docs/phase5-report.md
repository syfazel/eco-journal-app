# Phase 5 report — Nightly big blog update (ECO JOURNAL app lab)

**Status: LAB ONLY, not phone-tested, not built, not pushed.**
**Freeze stays ON.** Nothing in this pass touches the live platform:
no live cron/bot/channel change, no freeze change, no FCM change,
no test-channel creation, no special-issues publication, no voices
publication, no push, no cloud build, no APK distribution. This
commit is local only; the parent handles builds.

**Prior phases (lab, phone-pending):** Phase 1 `048cee5` (+fix
`e2fbbfd`), Phase 2 `594fc43`, Phase 3 Tier A `2c0a02a`, Phase 4
`a4048ec`. None phone-tested, none built by their passes.
**Phase 1 E-gates still bind:** E1–E6 implemented + simulated, NOT
phone-verified; E7/E8 outstanding. Yasin's provisional "seems all
good" on the Phase 1 install is **not** a retro-pass. Per the plan
§5 Phase 1 exit gate, if E2 (manifest-only sync when unchanged)
fails on the phone, Phases 3 and 5 do NOT start. Phase 5 lab work
does not waive this.

## 0. What Phase 5 is

A **new composite nightly generator** (no named nightly service
existed): the day's Tier-A articles + ≥70 briefs as summaries +
dossier pages, market close + formulas + pulse/watch, consolidated
digests + night-desk + SITREP arc, analysis + consensus matrix,
gated special-issues pair, corrections (visible, versioned).
Proposed window 01:30 Tehran on D+1 (see U3 — proposal, not a cron;
no cron was created in this pass). Edition schema: immutable
editions, `edition_id` = Shamsi date (e.g. `edition-1405-08-03`),
corrections = version 2, never silent overwrite; edition files
v1/v2; 30-edition archive + offline search refresh.

## 1. U4 — ENTRY GATE: BLOCKED for real data (not resolved)

U4 (nightly selection criteria unpinned) was investigated FIRST,
against edge data and production databases (read-only), before
building anything depending on it:

- **No `tier_a`, brief-score, or `ownrewrite`-score field exists**
  in current edge data (`edge/out/index.json` entries, article
  files, `short_cards.json`, Phase 2 fixture `channel_typed.json`
  — which has `urgency_score` but no tier/brief/ownrewrite field)
  or in production tables:
  - `econ-news-blog/data_core/data_core.db`: `article_extracts`
    columns are `extract_id, item_guid, url, title, source_slug,
    points_json, why_matters, origin, status, channel_message_id,
    extracted_at, decided_at, body, body_status` (3,170 rows);
    `items` has no score/tier column; `editions` table has 0 rows.
  - `yasineco_bot/bot.db`: `drafts` columns have no score column
    (status/`decided_at`/kind only).
- The keypoints rule-score in `yasineco_bot/keypoints.py`
  (`score_item`: base 50.0 + flash 40 + radar 15 + freshness bonus
  up to 10, top 16/day via `keypoints_daily_job` 07:00) is
  **computed at selection time and never stored** — it cannot be
  sourced from data, and it is NOT the brief score. The spec §5.3
  warns never to run two meanings of "≥70" at once.
- `urgency_score` is the Law 15 score for typed items only — it
  is NOT the brief score, and typed items do not exist in shipped
  live edge data (only in Phase 2/Phase 3 lab fixtures).
- The spec §5.3 proposed Tier-A pin (editorial pass flagged
  deep-analysis kind in the decision log) is also unsourced: no
  such flag exists in the decision data found.

**Resolution adopted (per spec §5.18 item 4 and the task's entry
gate): Phase 5 ships nothing gated on guessed selection.** The
generator HOLDS every Tier-A / brief / ownrewrite slot whose
selection is not supplied by an explicit lab selection file, with
reasons `tier_flag_missing` / `brief_score_missing` /
`ownrewrite_score_missing`, or `selection_missing (U4)` for an
unlabelled candidate — never a guessed score, never a silent
substitution of `urgency_score` or the keypoints rule-score.
Complete component-1 editions therefore exist **only in lab
fixtures**, where selection labels are explicit fixture data
labelled `lab-fixture-selection`. A real edition gated on guessed
selection must not ship, and during the freeze no edition ships
to any phone at all (dry-run staging only).

**Real-data proof (P5-11, §8 below):** a dry-run on real edge
articles for the most-populated real day (2026-10-20, 22
articles, no selection file) held **22/22** candidates with
`selection_missing (U4)`, published 0 articles, and changed
zero git state.

**U4 is therefore BLOCKED, not resolved.** What unblocks it:
editorial confirmation that Tier-A flags, brief scores and
ownrewrite scores are actually sourced from publisher data
(fields that exist and are populated), after which the generator
reads them instead of a lab selection file. Fixture selection is
not a substitute.

## 2. Other unresolved items (proposals, not facts)

- **U1 thresholds:** real state OFF for both metrics. Re-verified
  pattern from Phase 4: live `bot.db` settings contain only
  `breaking_count_*`, `breaking_guids_*`, `flash_count_*`,
  `flash_vix_last` — no `alert_bubble`, `alert_spread`,
  `alert_last_*`. The `/setalert` help examples (bubble 15,
  spread 2) are EXAMPLES, never defaults. Lab fixture thresholds
  (bubble 5.0, spread 2.0, source `lab-fixture-only`) must never
  reach a distributed APK or the pilot edge.
- **U2 pulse "fresh" window:** undecided; the close carries each
  pulse's snapshot timestamp so staleness stays visible.
- **U3 nightly clock:** proposal 01:30 Tehran on D+1 (after 23:45
  quality, after 00:05 night desk, before 04:00 dawn review,
  before 06:50 forecast signals, before 08:00 Morning Bundle);
  final time is a lift-order decision, never hard-coded as a cron
  in this pass.
- **U5 fresh-text method:** spec decision, 5-gram method (§5
  below); validation set of 5 pairs run in the Phase 5 verifier
  (§8) — separates re-edit from near-copy; translation pair is a
  documented method limitation (zero overlap → freshness 1.0,
  so the gate cannot catch translation copies).

## 3. Files changed (this pass)

### Kotlin — new
- `app/src/main/java/com/ecojournal/app/data/Nightly.kt` —
  pure domain: component states (`present` / `none_tonight` /
  `held_with_reasons` / `paused`); edition statuses (`draft` /
  `published` / `corrected` — app NEVER syncs `draft`); held
  reasons; word counting (spec §5.4: whitespace split over
  normalized text; ZWNJ joins a word; Persian/Arabic digits
  unified for counting only; emoji counts as a token; headlines/
  bylines/source lines/«اصلاح شد» notes never count — callers
  pass body only); freshness (spec §5.5: `1 − |shared 5-grams| /
  |article 5-grams|` over freshness-normalized text; an article
  too short for a 5-gram scores 0.0 — fails, never passes by
  default); floors (article ≥300, full article ≥800 + freshness
  ≥0.60, summary 300–500, over 500 re-classed never truncated:
  ≥800 + fresh → full article, else held for editing; held,
  never padded); keys (`edition_id = "edition-" + Shamsi day
  key`; `edition_key = edition_id + "+" + story_id`; dossier id
  shape `dossier-<shamsi-year>-W<week>-<slug>` where the W
  segment is the week-start Shamsi day key, e.g.
  `dossier-1405-W1405-08-03-ceasefire` — an unambiguous week
  identifier instead of an ordinal week number, which would need
  a second Jalali implementation); `NightlyParsing.parseEdition`
  (returns null on ANY structural problem: bad `edition_id`,
  draft status, status other than published/corrected, missing
  components, article without title/body/story_id, verbatim badge
  naming a non-allow-listed source; special-issues component must
  be `paused` with no items, else corrupt; non-http(s)
  `source_url` dropped to source-name-only, not fatal; a
  `present` articles component with zero items is corrupt — a
  zero-article edition must state `held_with_reasons`/
  `none_tonight` honestly); `parseDossier`.
- `app/src/main/java/com/ecojournal/app/ui/screens/NightlyScreen.kt`
  — Nightly section, edition page, dossier page (§6).

### Kotlin — modified
- `app/src/main/java/com/ecojournal/app/data/AppDatabase.kt` —
  new entities (spec §5.12 table names): `nightly_editions`
  (PK `editionId + version`, `isLatest` flag, `bookmarked` flag,
  component JSON stored verbatim, market-close JSON carries
  shipped Phase 4 alert rows — app never re-derives alert
  history), `nightly_items` (PK `editionKey`), `dossiers` (PK
  `dossierId`, living page: updates as its topic develops; past
  editions do not change). New `NightlyDao`: latest-editions
  flow (excludes `retracted` tombstones), edition by id,
  `retractEdition` (status → `retracted`, row + bookmark flag
  survive, items deleted — E5 orphan discipline for editions),
  `setEditionBookmarked`, `prunableEditionIds` (newest 30
  non-bookmarked latest editions kept; bookmarked never pruned),
  item insert/delete/query, offline search
  `searchItems(query, editionId?)` (title/body/source, optional
  `edition_id` facet, spec §5.13), dossier upsert/flow/by-id.
- `app/src/main/java/com/ecojournal/app/data/DatabaseProvider.kt`
  — migration chain extended.
- `app/src/main/java/com/ecojournal/app/data/UpdateEngine.kt` —
  `SyncSections.FETCHABLE` gains `NIGHTLY` (spec §5.12).
  `VOICES` stays `not_yet_published`, `SPECIAL_ISSUES` stays
  `gated_frozen`.
- `app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt`
  — `sectionStates()` gains a real count case
  (`NIGHTLY → nightlyDao.latestEditionCount()`; Diagnostics
  per-section line is therefore automatic); `syncNightlySection`
  (Phase 1 pattern: manifest-first, per-section hash, one
  transaction, hash stored only on complete success, failed/
  corrupt never empties cache; manifest `not_yet_published`/
  `gated_frozen` nightly is an honest zero state — no fetch,
  which is the live edge during the freeze; up-to-date
  short-circuits with prune; otherwise fetches `nightly.json`
  then changed edition files, manifest item `app_key` =
  `edition_id`, delta by per-edition `content_hash`; corrupt
  editions counted and skipped, rest of section still applies;
  retracted manifest items become tombstones; dossiers upserted;
  prune to 30 after every nightly sync path);
  `pruneNightlyEditions()`; `dispatchNightlyNotifications(now)`
  (§7); `syncV3` wiring (nightly block after radar; the
  not-yet-published/gated loop now lists only `VOICES`,
  `SPECIAL_ISSUES`).
- `app/src/main/java/com/ecojournal/app/util/Notifications.kt` —
  `showNightly(context, editionId, shamsiDate)` (default channel
  only, title = Shamsi date + "نسخهٔ شبانه", body counts-only,
  no headline leak, tap deep-links by
  `EXTRA_NIGHTLY_EDITION_ID`); `openNightlyEditionId(intent)`.
  Posting only — all firing/deferral/dedup decisions live in
  `EdgeRepository`.
- `app/src/main/java/com/ecojournal/app/MainActivity.kt` — new
  bottom tab `Tab("nightly", "نسخهٔ شبانه",
  Icons.Filled.DarkMode)`; routes `nightly`,
  `edition/{editionId}`, `dossier/{dossierId}`; nightly
  notification deep link navigates to `edition/<id>`; `hideBar`
  extended with `edition` and `dossier` (the `nightly` tab
  itself keeps its bar).
- `app/src/main/res/values/strings.xml` — ~40 new nightly
  strings (see §9 for the resolution check).

### Edge — new
- `edge/generate_nightly.py` — nightly generator, LAB ONLY,
  dry-run staging only (§4).
- `edge/generate_phase5_fixtures.py` — fixture builder (§5).
- `edge/verify_phase5_fixtures.py` — standalone fixture
  verifier (§8).
- `edge/out-phase5-fixture/` — fixture edge: manifest,
  `nightly.json`, `editions/edition-2026-10-24-v1.json`,
  `editions/edition-2026-10-25-v1.json`,
  `editions/edition-2026-10-25-v2.json`. Served ONLY for the
  phone test, never pushed to the pilot Pages edge (its
  LAB-ONLY market thresholds would then ship to pilot readers).

### Edge — modified
- `edge/publish_edge.py` — nightly section verification (§4).
  Production `generate_edge.py` was NOT changed — the live edge
  still reports nightly `not_yet_published`.

### Recorded deviations from spec text
- **Room v6 → v7, not v5 → v6.** The spec was written against
  the Phase 1 baseline. Reality: Phase 2 moved the DB to v5,
  Phase 4 to v6, so Phase 5 is `MIGRATION_6_7`, additive only,
  no existing table altered. Same deviation pattern as Phase 4,
  recorded in code. Migration has never executed anywhere
  (phone-pending).
- **App keeps only the latest version row's full component JSON
  for display** (older rows demoted via `demoteOlderVersions`);
  the edge keeps ALL version files (v1 is the audit trail) and
  correction notes travel inside the latest version's
  component 6. The spec's "one row per edition_id+version with
  latest flag" is preserved as rows + flag, but display uses
  latest only.

## 4. Generator and publish verification

`edge/generate_nightly.py` (never writes to live `edge/out`,
never commits/pushes/posts/creates a cron; only outputs staging
dir files + generator report). Step 11 (GATE) is deliberately NOT
in this script — delegated approval + `publish_edge` real mode
after lift only, and only if U4 is resolved. CLI: `--day-dir`,
`--staging-dir`, `--shamsi-date` (Latin, e.g. `1405-08-03`),
`--tehran-date`, `--selection` (lab selection file), `--market`,
`--alert-events`, `--frozen` (default true), `--no-frozen`,
`--report`, `--generated-at-tehran` (fixtures; default now).

Day-dir inputs (all optional except `candidates.json` for a
non-empty component 1): `candidates.json`, `bundles.json`,
`night_desk.json`, `sitreps.json`, `analysis.json`,
`consensus.json`, `corrections.json`; market defaults to
day-dir `market.json`.

Pipeline: SELECT approved inputs only → DEDUP by `story_id`
(duplicate story_id keeps first, recorded) → CLASSIFY → DRAFT
re-edits (never re-posts) → FLOOR → FRESH → ASSEMBLE 7
components → HASH → REPORT → STAGE. Atomicity: a failed
REQUIRED step (e.g. unparseable `market.json`, exit 3) holds the
whole edition (no edition file written); a component with
nothing tonight is `none_tonight` with a reason, never filler.
Zero-article editions may stage (spec §5.4 short-feed rule)
only with honest components 2/3/6/7.

Classification rules implemented:
- **Tier-A:** fresh-text decided FIRST — if freshness <0.60 and
  300≤words≤500 → downgraded to `summary` (spec §5.5: stays a
  brief if it passes the brief floor); if freshness <0.60
  otherwise → held `fresh_text_lt_60`; else if words <800 →
  held `floor_full_800`; else `full_article` with freshness
  recorded. (Ordering fixed during this pass: the first version
  checked the 800 floor first, which wrongly held the 452-word
  near-copy instead of downgrading it.)
- **Brief-70:** words <300 → held `floor_summary_300`; words
  >500 and ≥800 and fresh ≥0.60 → re-classed `full_article`;
  else `summary` with freshness null (report records why,
  spec §5.12).
- **Ownrewrite:** only if words ≥800 AND fresh ≥0.60, else held
  (never downgraded to summary — spec: candidates only if they
  pass floor + fresh-text).
- **Unlabelled candidate** → held `selection_missing (U4)`,
  never promoted.
- Verbatim claimed for a non-allow-listed source → held
  `verbatim_not_allowed`.
- **Dossier:** candidates sharing `dossier_group` (shared canon
  family) with ≥2 members become ONE dossier (summary
  floor-checked like a brief, ≥300 words; if the dossier summary
  fails, the dossier is held and its members stay held as
  `dossier_held`). A group of 1 is not a duplicate. **Keyword
  overlap alone never merges** — no keyword field is read.
- **Analysis:** missing any of thesis/evidence/counter-argument/
  scenario → held `analysis_structure`; outside its band (op-ed
  1000–3000, weekly 3000–5000, monthly 2000–4000) → held
  `analysis_band` (held for editing, never padded). Consensus
  rows that are empty are dropped (a matrix cell without a cited
  claim is not published).
- **Bundles:** only known kinds (`slot_bundle`, `short_news`,
  `marginalia`, `hourly_digest`, `morning_bundle`, `night_desk`)
  for the edition's Shamsi date and status ≠ `retracted`; the
  disabled `morning_digest` and unknown kinds are excluded
  (Phase 2 parse rule carried over). Night desk with no verified
  run → `none_tonight` with state note "night desk: no verified
  run" (spec §5.3/G7 honesty, not an error). SITREP arc sorted
  by `published_at_epoch`; `crisis_path` = levels in order.
- **Market close (spec §5.7):** latest snapshot with `tsEpoch`
  inside Tehran day D; if none on D, last available snapshot +
  `stale_close: true`; if no snapshot at all, `none_tonight`.
  Also stale when the close snapshot is >24h old at generation
  time (added during this pass — the 24h staleness line). 13
  price fields verbatim (null → "—" at render); 4 formulas
  evaluated from the edge's own S-expressions via the same rule
  as `MarketFormulas.kt` (never a second arithmetic
  implementation) — including the documented alias resolution
  that bubble_pct's `intrinsic` is the OUTPUT of
  `intrinsic_emami`, not a column (this alias was missing in the
  first generator version and produced `bubble_pct: null`;
  fixed). Change vs previous uses the previous distinct
  timestamp with the both-carry rule. Alert history ONLY from
  `--alert-events` (Phase 4 rows filtered to day D by
  `firedAtEpoch`); if the file is absent the report records
  "alert history: no input supplied" and the generator NEVER
  re-derives it from snapshots. Thresholds carried from
  `market.json`'s `market_alerts` object.
- **Corrections + versioning (spec §5.11):** scans
  `staging/editions` for an existing file for the same Tehran
  date; if found with the same `edition_id`, new version =
  old + 1, status `corrected`, corrections = old + new deduped
  by `correction_id`; the old file already IS the v<old> audit
  file and stays untouched. Otherwise version 1, status
  `published`.
- **Content hash:** `updated_at_epoch` (generation epoch ms,
  from `--generated-at-tehran` or now) is set FIRST so it is
  covered by the hash; `content_hash` = SHA-256 over canonical
  JSON of the full edition minus `content_hash` only (R13
  full-content hash). Index (`nightly.json`) merges with
  editions already staged in the same dir (same `edition_id`
  replaced, others kept; dossiers merged by `dossier_id`).
- **Frozen gate:** if `special_issues.json` exists in the
  day-dir while `--frozen`, the generator REFUSES (exit 2) — it
  does not read special-issues inputs at all while frozen;
  component 5 is always `{"state": "paused", "items": []}`.
- **Report** (machine-readable, lab-side deliverable):
  `edition_id`, `version`, `status`, `content_hash`,
  `edition_file`, `counts` (articles/full/summaries/held/
  dossiers/bundles/sitreps/corrections/market_close_present),
  `held[]` with reasons, `floor_failures[]`,
  `fresh_failures[]`, `dossier_merges[]`, `missing_inputs[]`,
  `u4_status`, `dry_run: true`, `published: false`.

`publish_edge.py` nightly verification (replacing the old
blanket "nightly must be `not_yet_published`" assertion; the old
path is preserved for non-Phase-5 edges):
`not_yet_published` + count 0 still passes; `published` triggers
full verification: `file` must be `nightly.json`; count == index
editions; manifest items == index editions count. Per edition:
file exists; `edition_id`/version/status match index;
`content_hash` recomputes (minus `content_hash` only); index
hash matches edition hash; status never `draft`. Special issues
must be `paused` with no items; any dict key containing `probab`
or `forecast_direction` fails (model-probability ban); per
article: `word_count` recomputes (same normalisation as the
generator), <300 fails, full article <800 fails, full article
without `channel_post_body` fails as freshness-unauditable
(this caught a real generator omission during this pass — the
edition item did not copy `channel_post_body` from the
candidate; fixed, fixtures rebuilt), full article fresh-text
<0.60 fails, verbatim badge outside the allow-list fails,
`source_url` containing "blog" fails. Corrected edition
(version ≥2) must have a v1 audit file (filename with
`-v<version>.json` replaced by `-v1.json`, edition_id matching,
version == 1). Dossier summaries must be ≥300 words. Section
hash must equal SHA-256 over canonical `{"editions": [full
edition objects sorted by edition_id], "dossiers": [dossiers
sorted by dossier_id]}` (R13: full contents, not entry list).
`VOICES` still asserted `not_yet_published` count 0;
`SPECIAL_ISSUES` still asserted `gated_frozen` count 0.

## 5. Fixtures (lab only, synthetic text)

Base copied from `edge/out-phase4-fixture/` (which carries the
real 164 snapshots + Phase 4 fixture snapshots + LAB-ONLY
thresholds 5.0/2.0, source `lab-fixture-only`). Fixture bodies
are SYNTHETIC lab text assembled from distinct lab sentence
templates (template C for nightly bodies, template B for channel
posts — the two share no 5-word run, so good re-edits measure
freshness near 1.0); they exercise mechanics only and must never
be read as editorial content. All fixture ids start `lab-p5-`;
no real channel data, no real invite code, no blog URL, no model
probability.

Lab selection file (`source: "lab-fixture-selection"`, fixture
data only, NOT a U4 resolution):
- `tier_a`: `lab-p5-full-1`, `lab-p5-full-2`, `lab-p5-thin-250`,
  `lab-p5-thin-700`, `lab-p5-near`
- `brief_70`: `lab-p5-sum-1`, `lab-p5-sum-2`, `lab-p5-kw-1`
- `ownrewrite`: `lab-p5-own-1`

Day 1 (Tehran 2026-10-25 / Shamsi 1405-08-03, generated
2026-10-26T01:30:00+03:30): 3 Tier-A full articles (872 words
each; full-1 is energy category + GEOPOL/L2 → RED bar case;
full-2 is Tabnak + `verbatim` → «بازنشر از تابناک» badge case;
full-3 rial category), 1 ownrewrite full (872 words),
summaries sum-1 (333 words), sum-2 (357 words), near-copy
downgraded to summary (452 words, nightly body = channel body
with only the final sentence changed), kw-1 summary (333 words,
keyword-overlapping with the dossier topic but NO
`dossier_group` — must NOT merge). Held: thin-250 (body 259
words, `floor_full_800`), thin-700 (700 words,
`floor_full_800`), unlabelled 900-word item (`selection_missing
(U4)`). Dossier: 3 members (`lab-p5-dos-1/2/3`, group `topic-a`,
title «پروندهٔ آزمایشی آتش‌بس», slug `ceasefire`, dossier
summary 320 words) → `dossier-1405-W1405-08-03-ceasefire`.
Market close: latest 2026-10-25 snapshot `lab-p4-s3` (16:00),
formulas implied 275,784.37 / intrinsic 256,043,004.81 / bubble
6.50 / spread 2.50 (fixture snapshots, NOT the golden vector).
Alert history (from Phase 4 rows, LAB-ONLY thresholds):
bubble/S2 value 6.00 notified 1, spread/S3 value 2.50 notified 1,
bubble/S3 value 6.50 notified 0 (banner-state, no fresh
crossing). Digests: Phase 2 bundles resolved to titled members
(morning-bundle, night-desk with 12 items, short-news, slot,
marginalia, hourly; morning-digest and mystery kind excluded),
SITREP arc L2 → L3 (lab-sitrep-0800, lab-sitrep-1000;
malformed lab-sitrep-bad excluded). Analysis: 1 op-ed (1,200
words, all 4 structure sections) + consensus matrix (outlets 2,
1 agreed / 1 divergent / 1 verified claim). Corrections: v1
(generated 2026-10-26T01:30) then v2 (generated
2026-10-26T10:30) with correction `lab-p5-corr-1` (story
`lab-p5-full-1`, field `number`, note «عدد آزمایشی اصلاح شد.»,
version 1 → 2, corrected 2026-10-26T10:00:00+03:30).

Day 2 (Tehran 2026-10-24 / Shamsi 1405-08-02, generated
2026-10-25T01:30:00+03:30): 2 summaries, market close on the
FROZEN last snapshot `cycle-20261024-0600`
(2026-10-24T09:59:13+03:30) — the P5-10 golden vector: implied
275,751.94 (≈275,752), intrinsic 255,704,023.28 (≈255,704,023),
bubble 5.5908 (≈+5.59%), spread −1.2063 (≈−1.21%). Digests/
analysis/corrections all `none_tonight` (no bundles/analysis for
that date in inputs).

Two generator bugs found by the fixture run and fixed (not
hidden): (1) `max(day_snaps)` on `(epoch, dict)` tuples raised
`TypeError` on tied epochs (e.g. two 2026-10-25T10:00 snapshots)
— fixed with explicit `key=lambda t: t[0]` for `max` and for the
previous-snapshot sort (None epochs map to −1); (2)
`bubble_pct` evaluated to null because the `intrinsic` alias
was missing — fixed as described in §4.

## 6. App surfaces (Nightly section, edition page, dossier page)

`NightlyScreen`: top-level Nightly section. Hero = tonight's
(latest) edition with Shamsi date, corrected-version badge
(version ≥2), last-updated stamp; honest empty state «هنوز هیچ
نسخهٔ شبانه‌ای منتشر نشده است» when no edition ever published
(never a fake sample, never a dry-run edition as published);
failed nightly sync + cached hero → honest line «نسخهٔ امشب
نرسید (خطای شبکه) — نسخهٔ دیروز نمایش داده می‌شود» (P5-4).
Below: offline edition search (query ≥2 chars, all-editions vs
scoped-to-one-edition toggle, results open their edition page),
«تازه‌ها» list (newest items across kept editions via
`searchItems("", null).take(20)` — links open in edition
context, never a second feed), dossier list, archive list
newest-first (hero excluded from the archive list by
`editions.drop(1)`).

`EditionPageScreen`: single scrollable page in component order
1→7 (spec §5.14), NOT a pile of feed rows. Component 1 articles
with kind label (full/summary/brief), word count (Persian
digits), source, verbatim badge, tap-to-expand body; inline
«اصلاح شد» notes on originals (from corrections map by
`story_id`) AND the component-6 list. Component 2 market close
verbatim from the edition (snapshot timestamp, stale-close line
when `stale_close`, price rows with "—" for null, formula rows,
alert-history rows with metric/value/threshold/notified; empty
alert history distinguishes "no alerts today" (thresholds set)
from "threshold not set — alerts off" (thresholds null)).
Component 3 digests (bundles with titled members, night-desk
card, SITREP arc + crisis path strip). Component 4 analysis +
consensus matrix (agreed/divergent/verified claim rows; claim
rows without a cited claim are dropped by the generator, not
published). Component 5: ONLY the line «ویژه‌نامه‌ها — متوقف
است» (no content, no count). Component 6 corrections list (or
«امروز اصلاحیه‌ای نیست»). Component 7 archive-entry line +
dossier links (with "this dossier has been updated since this
edition" line when `dossier.lastUpdatedTehran >
edition.cutTehran`, string comparison on ISO Tehran timestamps).
Edition bookmark button toggles `bookmarked` (pins against
prune). Deep link to a pruned, non-bookmarked old edition shows
«این نسخه به‌صورت محلی نگه داشته نشده است؛ برای دریافت آن وصل
شوید» — never a crash, never a fake empty edition.

`DossierPageScreen`: dossier by `dossier_id` (title, id,
summary, first/last seen via entity fields, edition-id links
back to edition pages).

Held-state article lists show NO held titles in the app (held
items are generator-report content, lab-side, not published
reader content).

## 7. Nightly notification (producer wired only through Phase 4)

`dispatchNightlyNotifications(now)` — the Phase 5 notification
producer, wired only through `NotificationPolicy.decide` for
NIGHTLY, never re-implementing timing:
- Guard: returns early if nightly section state is FAILED, so no
  notification can announce an edition the app does not have
  (P5-4).
- Fires once, on first successful nightly sync after
  publication, default channel (`eco_articles`), never urgent.
- Night (00:00–08:00) or quiet-hours suppression → pending set
  (`p5_pending_nightly`, `p5_pending_since` in meta); flushed on
  the first later sync where `decide` fires (polling reality,
  same as Phase 4 deferred summary — the app cannot wake itself
  exactly at 08:00/quiet-end). Note: `decide()` returns
  `deferToSummary=false` for night-suppressed non-urgent types;
  the producer still defers via its own pending set because
  §5.15 requires a deferred nightly notification, not a dropped
  one — timing rule honoured, flag is not the mechanism.
- Toggle/master suppression → marked notified without firing
  (no retro burst, Phase 4 rule), recorded in
  `notification_history` with first-failing-check reason.
- **Corrected version 2 does NOT re-notify as new:** the
  notified set is keyed by `edition_id` (not
  `edition_id+version`); the correction is visible on the edition
  page itself. If v1 was pending and v2 arrives first, the
  pending flush fires once for the latest version.
- Called for full syncs AND nightly-only syncs
  (`syncOnly("nightly")`); the edition itself is always in-app
  on sync regardless of notification timing — only the
  interruption is deferred.

## 8. Verification (all lab; no phone test in this pass)

- `py_compile` clean for `generate_nightly.py`,
  `generate_phase5_fixtures.py`, `verify_phase5_fixtures.py`,
  `publish_edge.py` (after the `channel_post_body` fix).
- Fixture builder runs end-to-end: day 2 stages 2 articles /
  0 held / 0 dossiers; day 1 v1 and v2 each stage 8 articles /
  3 held / 1 dossier.
- `publish_edge.py --verify-only` on `edge/out-phase5-fixture`:
  **PASS** (nightly section hash recomputes from full contents,
  2 editions, 1 dossier; floors, fresh-text, paused special
  issues, v1 audit files verified). On
  `edge/out-phase4-fixture`: **PASS**. On
  `edge/out-phase2-fixture`: **PASS**.
- `edge/verify_phase5_fixtures.py`: **PASS (26 checks)** —
  nightly section hash (full contents); P5-2 thin held, not
  padded, 8 good articles (not padded to 15); U4 unlabelled held
  + absent from published articles; P5-3 v1/v2 versioning +
  component-6 entry; P5-9 special issues paused; P5-6 dossier
  merge (3 → 1) + keyword-overlap item NOT merged; P5-7
  fresh-text validation set (re-edit 1.000, near-copy 0.120,
  paraphrase 1.000, translation 1.000, unrelated 1.000 — method
  separates re-edit ≥0.60 from near-copy <0.60; translation
  limitation recorded, §2 U5); fixture near-copy stays a brief;
  P5-10 golden vector within tolerances (implied ±500,
  intrinsic ±50,000, bubble/spread ±0.05); alert history =
  supplied Phase 4 rows; P5-1 lab completeness (all 7 components
  present-or-stated, SITREP path L2 → L3, disabled morning
  digest nowhere); hygiene (no blog link, no probability key,
  no secret-like pattern); word counts recompute identically
  (Kotlin mirror).
- `edge/verify_phase2_fixtures.py`: **PASS** (unchanged).
  `edge/verify_phase4_fixtures.py`: **38/38 PASS** (unchanged).
- **Real-data dry-run (P5-11):** generator on real edge articles
  (2026-10-20, 22 candidates, NO selection file, market =
  live `edge/out/market.json`, no alert-events input) → staged
  only under `/tmp/phase5-real-dryrun`, report at
  `/tmp/phase5-real-report.json`: edition `edition-1405-07-29`
  v1, articles `held_with_reasons` (22/22
  `selection_missing (U4)`), market close `present` (close
  snapshot `snap-2026-10-20-2328`, not stale), digests/
  analysis/corrections `none_tonight`, special issues `paused`,
  archive `present`; missing inputs recorded (alert-events not
  supplied and NOT re-derived; bundles/night_desk/sitreps/
  analysis/consensus/corrections absent from live edge). `git
  status` byte-identical before/after the dry-run — zero git
  changes, zero edge commits, zero pushes; an approval rejection
  holds the whole edition (no partial edition artefact).
  Caveat, stated plainly: dry-run story_ids were appKey-derived
  stand-ins (real guids live in the publisher join-keys, not in
  `edge/out/`); the dry-run proves selection behaviour, not
  join-key mapping (Phase 1 already proved join keys).

## 9. Kotlin / string verification (no JDK/SDK/Gradle in sandbox)

- Brace/paren/bracket balance on touched Kotlin files verified
  by a stateful tokenizer (final depth 0 for every touched file,
  every object/function closing cleanly). Two known false
  positives documented, not hidden: (a) a naive regex stripper
  reports Nightly.kt −2 braces — contradicted by two stateful
  scans (depth 0) and traced to its mishandling of the Persian
  char-literal runs; (b) the same stateful scanner reports one
  net-open brace in `EdgeRepository.kt` — but the HEAD (Phase 4)
  version shows the identical artefact, and naive counts are
  balanced for both (HEAD 382/382 → working 426/426;
  Notifications 28/28 → 30/30), so it is a pre-existing scanner
  artefact, not a Phase 5 regression.
- `strings.xml` parses as XML; 303 strings defined; all 51
  `R.string.*` references in touched Kotlin files resolve, 0
  missing, 0 duplicate names.
- **A Kotlin compile error invisible to balance/string checks
  (e.g. a type mismatch) would surface only at the parent's
  cloud build — the build remains a GATE, not a formality,
  before any phone test.** Two risky call forms found during
  this pass were fixed to the standard dot form
  (`latestEditionsFlow().first()`, `dossiersFlow().first()` with
  an explicit `import kotlinx.coroutines.flow.first`).

## 10. Bar status (cross-phase rule, §0 of new-requirements)

**Honoured, not restyled, not removed.** `EditionItemBar` in
`NightlyScreen.kt` uses the SAME rule as feed cards via the
existing `DomainBarStrip` (`ui/components/ChannelCard.kt`, strip
at lines ~60–84, typed-card use at lines ~240–242):
`formatType != null` → RED + `Formats.badgeText(formatType)`;
`category == "energy"` → YELLOW + «انرژی»; else no bar.
Bar definitions (`data/Phase2Domain.kt` lines ~24–37:
`DomainBars`, RED_ARGB line 28, YELLOW_ARGB line 29,
`forChannelItem()` → RED line 32, `forArticle()` → YELLOW iff
`category == "energy"` lines 35–36) and feed/reader bar uses
(`ui/components/ArticleCard.kt` lines 111, 162–163, 224–225;
`ui/screens/ArticleScreen.kt` lines 219–221) are **byte-identical
to Phase 4** (confirmed by `git diff` — empty for all four
files). No separate service and no separate tab was invented
for war-room/red-alert: the Nightly tab is the edition section,
and red-alert items stay inside it with bar + badge.
Colours fixed in Phase 2: RED `#C62828` for war-room/red-alert
items (every typed channel item, any format — red-alert
treatment outranks domain colour); YELLOW `#F9A825` for
energy-domain articles. Labels are distinct, never colour
alone: format badge text for typed items, «انرژی» for energy
articles. Fixture proof: day-1 full-1 (energy + GEOPOL/L2) is
the RED bar case; energy articles generally are the YELLOW
case (an energy article with no format type shows YELLOW).

## 11. Per-criterion results P5-1–P5-11

LAB-READY is **not** a pass — a criterion passes only on Yasin's
real phone or is explicitly recorded as lab-only with his
acknowledgement. Phone criteria below are therefore
PHONE-PENDING, with the exact fixture/procedure for the test.

- **P5-1 Dry-run edition renders complete — LAB.** Dry-run
  edition + generator report match item-for-item, all 7
  components present or explicitly `none_tonight`/`paused`
  (verifier §8). Phone render of the edition page waits for the
  phone test (fixture edge, §13).
- **P5-2 Thin-item held, not padded — LAB.** Thin corpus
  (250-word-class + 700-word) held with reasons; fixture day
  publishes 8 articles total (4 full + 4 summaries), not padded
  to 15; zero generator padding (generator never appends text —
  bodies come from input verbatim; fixture bodies are synthetic
  lab assemblies, labelled as such, not editorial content).
  "5 good, not 15 thin" is the doctrine; the fixture count is 8
  because the fixture corpus has 8 good items — the check is
  "no filler", not "exactly 5".
- **P5-3 Correction versioning — LAB + phone-after-lift.** v2 +
  visible «اصلاح شد» note on the original + component-6 entry;
  v1 file still on edge; no silent overwrite (verifier §8).
  Phone verification of a real v2 waits for lift.
- **P5-4 Failed nightly sync — PHONE (yesterday's-edition
  fixture).** Edge unreachable → Nightly keeps yesterday's
  edition + honest failure line; no blank section; no nightly
  notification for an edition the app does not have (guard in
  §7). Not testable in this sandbox.
- **P5-5 Archive + search — PHONE (local editions).** Archive
  newest-first with corrected-version badges; search finds
  edition content offline and scopes by `edition_id`; a
  bookmarked edition survives pruning. Implemented (§6); not
  phone-verified.
- **P5-6 Dossier merge — LAB.** 3 sharing one canon story →
  ONE dossier + edition links (not 3 articles);
  keyword-overlapping canon-distinct item does NOT merge
  (verifier §8).
- **P5-7 Fresh-text gate — LAB.** 5-pair validation set
  separates correctly (re-edit 1.000 vs near-copy 0.120);
  near-copy fixture stays a brief, never an article. Translation
  limitation recorded (§2 U5).
- **P5-8 Nightly notification timing — PHONE (fixture
  edition).** Edition first synced at night → NO night
  notification, ONE deferred notification at max(08:00,
  quiet-hours end); corrected v2 does NOT re-notify as new.
  Tester must record the timing path used (fixture windows vs
  re-stamped fixture day); do NOT fake it with a phone-clock
  change.
- **P5-9 Special-issues paused — PHONE.** Edition component 5
  and Special Issues section both render "paused" with no
  content, no stale posts, no count — including in an edition
  whose other components are full. Lab side verified (component
  5 `paused`, generator refuses special-issues input while
  frozen); phone render pending.
- **P5-10 Market close correctness — LAB (golden vector).**
  Day-2 close reproduces implied ≈275,752, intrinsic
  ≈255,704,023, bubble ≈+5.59%, spread ≈−1.21% — the same engine
  values the app shows; any deviation would be a train/serve
  skew bug, not a rounding note (verifier tolerances: implied
  ±500, intrinsic ±50,000, bubble/spread ±0.05).
- **P5-11 Gate honesty — LAB.** Dry-run report complete +
  `git status` before/after identical; approval rejection holds
  the whole edition (§8 real-data dry-run).

## 12. Regressions / standing gates (unchanged by Phase 5)

- Phase 2 fixture-only until a real typed item triggers
  P2-A1–P2-A4 re-verification. Phase 3 Tier B blocked on test
  channel + rehearsal-edge isolation (Phase 3 report §6).
  Phase 3 Tier A proved the chain only up to the publish gate.
- Phase 2 and Phase 4 fixture verifiers still pass (§8); bar
  files byte-identical (§10); live edge `edge/out/` untouched
  (only pre-existing `revocations.json` modification, not from
  this pass); no cron/bot/channel/freeze change; no FCM change.
- Special issues separately frozen 2026-10-25 ~01:56, own
  decision, 2 publication crons disabled, 4 posts queued, 0
  sent. Preconditions before any reopen (all blocking, plus
  Yasin's own explicit order): re-verify the 2 partially-verified
  posts (CAP refunds, Brookings Black Americans) from the source
  page; resolve the queue-location discrepancy; recover missing
  full texts; confirm the ML firewall. Lifting the general freeze
  does NOT open this section.
- Voices registry is a draft awaiting Yasin's edit — no voice
  page is published until he finalises it; the 1 blank slot
  stays blank.
- **ML firewall (absolute):** no special-issues item enters any
  ML-DB row/label/model input, ever, including via a nightly
  edition. No model probability appears in any edition until the
  Phase-3 calibration gate passes (≥30 walk-forward days, master
  plan §6 ML-D). Deterministic forecast SIGNALS (S40) may appear
  only labelled as signals, never as predictions. Publish
  verification enforces the probability-key ban.
- **BLOG_LIVE=False:** no blog button/link in channel posts or
  in-app edition items; source buttons go to the news source,
  never to a blog link. Verbatim republication ONLY from
  Donya-e Eqtesad (دنیای اقتصاد) + Tabnak (تابناک), with
  «بازنشر از {source}» badge; all other Persian sources render
  as brief/summary only.
- **Law 14 / Law 15:** bot posts to channel ONLY, no personal
  DMs; app local notifications are not DMs. Night law: day
  score ≥70 → instant; night 00:00–08:00 only ≥85 instant,
  70–84 → 08:00 Morning Bundle; FLASH exempt; WATCH exempt at
  night at L3/L4. Nightly notification is non-urgent: never at
  night, never inside quiet hours; deferred to max(08:00,
  quiet-hours end).
- Persian rendering (S27) on all screens: Persian digits, Shamsi
  date + Tehran time in bylines, half-spaces, one story = one
  card (dedup by `story_id`, never headline hash — the
  2026-10-23 transliteration defect, آتلانتیک/اتلانتیک queued
  4×, is the reason).

## 13. Phone-test procedure (for the parent, not improvised here)

- Serve `edge/out-phase5-fixture/` as the edge by the parent's
  choice — local static host or test Pages branch — NOT the live
  edge, NOT pushed here. **The fixture edge must never be pushed
  to the pilot Pages edge** because its LAB-ONLY market
  thresholds would then ship to pilot readers.
- P5-4: point the test app at an unreachable edge with a cached
  yesterday edition; expect the honest failure line, cached
  hero, and no notification.
- P5-5: with the fixture edge synced, check archive order,
  corrected-version badge on the 1405-08-03 edition, offline
  search (all-editions and scoped), bookmark-then-prune survival
  (prune only bites past 30 editions — a long-horizon check).
- P5-8: record which timing path is used (fixture windows vs
  re-stamped fixture day); do NOT fake it with a phone-clock
  change. Expect: no night notification, one deferred
  notification at max(08:00, quiet-hours end), no re-notify on
  v2.
- P5-9: open the full day-1 edition; component 5 must read only
  «ویژه‌نامه‌ها — متوقف است», and the Special Issues section
  must also read paused with no content/count.
- **Build is a GATE:** no Gradle/JDK/SDK exists in this sandbox,
  so no compile beyond §9 was possible. If the cloud build
  fails on Phase 5 code, that is a Phase 5 failure to fix, not a
  phone-test detail.

## 14. What Phase 6 needs from Phase 5

- Nightly edition sync + Nightly section + edition/dossier
  pages + nightly notification producer are built (lab,
  phone-pending); Phase 6 (editorial/voices, see
  `docs/big-update/phase6-editorial-voices-spec.md`, not read in
  detail in this pass) does not need to rebuild them.
- Phase 6 still blocked on its own gates, unchanged by Phase 5:
  voices registry finalisation by Yasin (no voice page until
  then); special-issues reopen needs its own explicit order +
  the 4 preconditions in §12; Phase 1 E-gates (especially E2)
  and Phases 2–5 phone passes must land before any live nightly
  edition can be gated; U4 must be resolved with editorial
  confirmation (Tier-A flag, brief-score, ownrewrite-score
  fields actually sourced from publisher data) before any REAL
  edition's component 1 can be gated — fixture selection is not
  a substitute.
- Publisher confirmations still open from Phases 2–4 (status
  vocabulary, attribution shipping, `war_room` object, SITREP
  canonical section names, WIRE cap, radar severity ranks and
  interval cadence per crisis level, bundle `bundle_date_shamsi`
  format) remain open; Phase 5 adds: nightly selection pins
  (U4), nightly generation clock time against the live lift
  order (U3), and the pulse fresh window (U2).

## 15. Explicitly NOT done in this pass

- Phase 6 (editorial/voices). Post-program items: CNN ticker,
  sync compaction. The v4 remote-update system (separate
  backlog; Yasin's 2026-10-25 ~23:35 consolidation order puts
  v4 items under version 4, not under Phase 5).
- No push, no cloud build, no APK, no cron, no live publish.
  Commit below is local only.
