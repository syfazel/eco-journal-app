# vNext decisions — delegated by Yasin, 2026-10-25 ~20:05 Tehran

Yasin's order: decide the three open gates himself (Muse), according to
conditions, and continue to the end without asking him for permission.
These decisions are recorded here so M5/M7 do not stall on them.
No invite code, token, or secret is written in this file, ever.

## 1. Invite reconciliation — pilot file is authoritative

- The lab bridge queue database holds 20 rows in its `invites` table.
  Those rows are lab/test data, not a pilot reader list.
- The pilot invite file (`docs/pilot-invite-codes-private.md`, lab-only,
  gitignored) is the authoritative pilot list.
- M7 decision: the real invite gate will check hash-only entries derived
  from the pilot file, with an empty revocation list at launch.
  Lab queue rows that do not match a pilot hash are retired at M7,
  not promoted to real readers. No code is ever printed, logged, or
  committed — only SHA-256 hashes are stored or compared.

## 2. Edge URL — GitHub Pages, repo root

- Edge URL: `https://syfazel.github.io/eco-journal-app/`
  (Pages root, WITHOUT any `/out` suffix — per `.github/workflows/pages.yml`,
  `edge/out/index.json` is published as `<root>/index.json`).
- Rationale: zero cost, already wired in the repo (Pages workflow exists),
  no custom domain, no new service, and the edge snapshot is already
  committed in the repo. No other edge host is introduced for vNext.
- One-time prerequisite, deferred to M5: the repo's Pages source must be
  "GitHub Actions" (owner toggle in repo Settings). Until that toggle is
  set, the Pages workflow runs but serves nothing — the app must keep
  working fully offline from its bundled seed and treat sync as
  "not configured" rather than failing. Enabling that toggle is part of
  M5, not a live-platform change.

## 3. Firebase / FCM — NO for the vNext pilot

- Decision: no Firebase project, no `google-services.json`, no FCM
  activation in the vNext pilot.
- Rationale: zero-cost constraint; no Firebase project exists; the pilot
  is offline-first with a bundled seed; WorkManager polling (15-minute
  minimum) plus local notifications covers the pilot's notification needs;
  FCM code stays dormant scaffolding only, exactly as it is today.
- Revisit only after the pilot, if push latency (not polling) is proven
  to be a real pilot blocker. That revisit is a new decision, not assumed.

## What these decisions do NOT authorise

- No live-platform unfreeze, no cron/bot/channel change, no APK
  distribution beyond named pilot readers, no Firebase account creation,
  and no change to the freeze-lift rule: the freeze lifts only when the
  app is ready AND Yasin gives an explicit lift order.
