# R3 — Nightly edition dry-run (Phase 8 rehearsal, 2026-10-26)

Generator: `edge/generate_nightly.py` via `edge/generate_phase5_fixtures.py`
(re-run during this rehearsal, lab only). Output staging dir:
`/tmp/phase5-fixture-staging` (+ day-dirs `/tmp/phase5-day1`,
`/tmp/phase5-day2`). Nothing was written to live `edge/out`, nothing
committed to Pages, nothing posted.

## Generator bundle — VERIFIED in this run

- Edition v1: `edition_id` = `edition-1405-08-03` (Shamsi date),
  `version` = 1, `content_hash` present, `status` = `published`
  (staging label only — this edition was never published anywhere).
- Edition v2: same `edition_id`, `version` = 2, `content_hash`
  present and DIFFERENT from v1, `status` = `corrected` — correction
  versioning works: v1 kept as audit trail, v2 carries the correction,
  never a silent overwrite.
- 7 components present in the edition file: `articles`, `digests`,
  `market_close`, `analysis` (incl. `consensus_matrix`), `corrections`,
  `special_issues` (state: paused — frozen, as required),
  `archive`.
- Thin-item-held case: generator output line for 1405-08-03 reads
  `articles=8 held=3 dossiers=1`; the edition's `articles.held` list
  is non-empty (thin 250-word and 700-word lab items held, not
  shipped as articles).
- Search-hit check (grep, not screenshot): the dossier title
  `پروندهٔ آزمایشی آتش‌بس` is present in the v1 edition file —
  edition content is searchable text, not an opaque blob.
- Earlier edition also staged: `edition-1405-08-02` version 1
  (`articles=2 held=0 dossiers=0`) — archive depth exists.

## What is MISSING — why R3 does not pass

The spec's R3 artifact also requires render screenshots covering all
7 components and a search-hit screenshot. No phone or emulator render
exists for this edition (no v4 build, no phone pass), and this
rehearsal produced grep-based checks, not screenshots. A grep hit is
not a render screenshot, and the freshness rule forbids passing the
line on Phase 5's fixture verification alone.

## R3 verdict: OPEN — generator PASS, render proof MISSING

Unblocked by: a render of a dry-run edition on a phone (or a lab
render filed with screenshots) covering all 7 components, plus a
search-hit screenshot for edition content. Owner: phone render
(Yasin) once a v4 build exists.

## Deletion note (spec §5.3 R3, §5.6 step 9)

Temp dirs created by THIS rehearsal run were removed afterwards:

- `/tmp/phase5-fixture-staging` — DELETED
- `/tmp/phase5-day1` — DELETED
- `/tmp/phase5-day2` — DELETED

Verification: `ls /tmp | grep phase5` after deletion shows only
pre-existing entries from earlier work (`phase5-real-day`,
`phase5-real-day.txt`, `phase5-real-dryrun`, `phase5-real-report.json`),
which are NOT this run's dirs and were left untouched. No dry-run
edition remains in any temp dir created by this rehearsal.
