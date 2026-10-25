# R6 — ML gate status (Phase 8 rehearsal, 2026-10-26)

Read-only. No ML pipeline was run, no training was started, no
snapshot loop was resumed during this rehearsal.

## Gate status — latest available reading (quoted, dated)

Source file: `econ-news-blog/ml_pipeline/gate_status.json`,
`computed_at` = `2026-10-23T23:13:28Z` (pre-freeze; the pipeline has
been frozen since 2026-10-24 11:02 Tehran, so no fresher reading can
exist without running the pipeline — which the freeze forbids).

- Summary: passed 6 / total 7, `gate_open` = false.
- Passing: >=500 decided signals (879); >=50 negatives (378);
  reject rate 0.43 (in 0.05..0.50); Persian-feed share 0.7269;
  full-text coverage 0.8066 (>=0.70); news_features coverage
  0.9602 (>=0.90).
- FAILING, the only failing condition: >=60 distinct days of
  continuous data — value 34 days (span 977 days, raw distinct
  410, days with <10 items excluded as feed noise).

## ESS (frozen value, plan record — not a fresh measurement)

ESS at freeze is recorded in the plan as 16/15/15 (frozen, needs
14 days post-lift for the pass criterion). No fresh ESS measurement
was taken in this rehearsal: measuring it requires running the
frozen pipeline. The 14-day pass criterion clock starts at lift,
not 2026-11-07.

## Snapshot-loop resumption line (as it will appear in the lift order)

"Not in lift order — ML stays frozen." The snapshot loop cron
(`eco-ml-snapshot-hourly`, interval 1h) reads `enabled=false` in
the fresh R5 cron listing, and the lift-order draft re-enables NO
cron. Resumption must be named explicitly in Yasin's scope sentence
if it is ever wanted; it is NOT assumed by this rehearsal.

## Probability on app surfaces

- Lab code check: grep for `probability|p_up` in
  `MarketScreen.kt` returned no matches — no probability line is
  wired into the Market tab code.
- REQUIRED phone artifact MISSING: `phase7/market-no-probability.png`
  (a Market-tab screenshot showing NO probability line) does not
  exist — no v4 build, no phone pass. Code absence is not the
  screenshot the spec requires.

## v0-retired / v1-gate sentence (verbatim from the v4 plan)

"v0 retired, v1 only at 7/7 gate or explicit waiver."

## R6 verdict: OPEN — gate honestly reported, phone proof missing

The gate is reported with its dated reading and its single failing
condition named; nothing here claims 7/7. R6 cannot pass until the
Market-tab no-probability screenshot exists (phone, Yasin) and a
post-lift gate re-reading is possible. Owner: phone screenshot
(Yasin) + pipeline re-run only after a lift that names ML.
