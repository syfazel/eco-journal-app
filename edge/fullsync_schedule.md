# Full-sync regeneration — schedule spec for the parent to create

> This file is the DELIVERABLE for S2's schedule. The subagent that
> built the generators did NOT create the cron (per task division:
> the parent creates it). Nothing below has been scheduled yet.

## Command (exact)

```bash
cd ~/workspace/goals/eco-journal/app && python3 edge/regenerate_fullsync.py --auto-publish
```

- Working directory matters: the script resolves the app root and
  edge dir from its own location, but the `--auto-publish` git
  commands run in the repo root, so run it from the repo root.
- The script is idempotent: when nothing changed it prints
  `UNCHANGED` and does no commit and no push. A push happens only
  on a changed, verify-PASSed edge.
- NEVER substitute `edge/publish_edge.py --real` for this command:
  `--real` regenerates a Phase 1 edge and RESETS channel_typed and
  radar to `not_yet_published`, silently wiping S1/S3. The only
  publish_edge mode valid for a full-sync edge is `--verify-only`
  (which the script runs internally as its gate).

## Cadence (chosen) — every 30 minutes, 08:00–23:30 Asia/Tehran, plus every 60 minutes 00:00–07:30

Why this shape:

- 30 minutes daytime is the radar plan's recommendation (G1/D4) and
  the task's recommendation. It matches the app's honest freshness
  ceiling: edge 30 min + app WorkManager poll floor 15 min = the
  15–60 min "channel → app" window the radar plan promises. A
  faster edge cadence would not make the app faster (the 15-min
  poll floor binds) and would only burn pushes.
- Night is NOT skipped, because night instant posts (score ≥85,
  the only night posts the channel law allows) must reach the app
  too. Night runs are leak-safe by construction: every run
  re-applies the published-only filter from the `posted` table,
  and the night-held queue is never read — so a 00:00–08:00 run
  can only surface posts the channel already sent. The night
  cadence is 60 minutes instead of 30 because night volume is
  near-zero (only ≥85 instant posts) and the channel night law,
  not the edge, is the binding freshness rule overnight.
- Suggested cron lines (timezone Asia/Tehran; the cron system must
  run them in that timezone — verify with the parent's cron tool,
  which records its own timezone handling):
  - Day: `*/30 8-23 * * *` → the script itself (the 23:30 run is
    the last daytime run; the script's own report timestamps every
    run, so a missed run is visible in
    `edge/fullsync-regenerate-report.json`).
  - Night: `0 0-7 * * *` (hourly at :00) — plus, if the parent's
    cron tool cannot express two lines for one job, a single
    `*/30 * * * *` line is also acceptable: it is safe (filter),
    just twice the night pushes on nights with instant posts.
- Daytime window edge case: a run at 07:30 (night cadence) and the
  first 08:00 daytime run overlap the 08:00 held-release moment.
  That is fine: the app releases its own held rendering at 08:00
  by its own clock; the edge only ever carries already-published
  posts, so no ordering hazard exists between the two runs.

## What regeneration cannot fix (source gaps — read before promising freshness)

- Market snapshots: regeneration copies `data_core.prices` verbatim.
  On 2026-10-26 the table holds exactly the 164 snapshots already
  on the edge, latest `2026-10-24T09:59+03:30`. If the market
  ingest pipeline is gapped, the edge market section will stay
  stale no matter how often this script runs. Fixing that needs
  the market ingest job itself, not this schedule.
- Channel posts: regeneration can only surface posts the channel
  pipeline already sent. If a redalert cycle is down, the edge
  faithfully mirrors the gap.
- Radar: the generator only converts published stories; if the
  war-room pipeline stops producing events, radar goes quiet
  honestly (count falls), it never invents signals.

## Monitoring

- After each run, `edge/fullsync-regenerate-report.json` holds
  before/after counts + hashes per section and the `changed` flag.
- A run that fails verification restores edge/out from backup and
  exits 1 with nothing changed — the next run retries from the
  same clean state. Alert on repeated exit 1, not on a single one.
