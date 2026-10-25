# Control Plane — Private Muse Dashboard for Yasin (Phase 1 spec)

**Status:** SPEC ONLY in Phase 1. Nothing in this document is built yet.
The dashboard is Yasin's private control surface. It is NOT part of the
reader app, and it is never shipped to readers' phones.

## What it is

Muse (this VM) is the private control plane: it already holds the
editorial pipeline, the source databases and the review discipline.
The dashboard is a small private surface — in practice, Yasin's Muse
app itself (a dedicated view / chat surface) plus the lab scripts under
`goals/eco-journal/app/` — through which Yasin:

1. reviews items before they reach readers,
2. manages invite codes for the 10 readers,
3. sees the health of the whole chain at a glance.

No public server is involved. Readers' phones talk only to the public
edge (GitHub Pages) and to FCM. They never reach Muse, and Muse never
serves them directly (see `app-package-v2.md` for the honest reason:
this VM has no public address and Tailscale here is client-only).

## 1. Review queue

### Inputs (read-only, from production — never written by the lab)

- `econ-news-blog/data_core/data_core.db`, read-only (`mode=ro`):
  `article_extracts` rows with status `approved`/`posted` and a
  non-empty body become **full articles**; approved bot drafts with
  `body_fa` become **short cards** labelled «گزیده».
- `yasineco_bot/bot.db` drafts (approved only).
- The frozen live channel is NOT re-read for new content until Yasin
  explicitly reopens it. The seed in Phase 1 comes from the databases
  above, plus the 2026-10-24 backup for channel references only.

### Queue states (same machine as the bridge — see telegram-bridge.md)

```
draft → review → approved → queued → published_app → published_channel
```

- `draft`: produced by the pipeline, not yet seen by a human/editorial pass.
- `review`: awaiting Yasin's (or delegated Muse editorial) decision.
- `approved`: cleared for readers.
- `queued`: scheduled for the next edge publish (and, when the channel
  is open, for the channel).
- `published_app`: live on the edge (index.json + article file).
- `published_channel`: also posted to the channel (only after reopen).

### What the reviewer sees per item

- Title, category, source, original URL, body verbatim.
- Kind: full article vs «گزیده» short card.
- Provenance: which DB row, which status, when it was approved.
- Warnings: missing body (→ short card or exclusion, never
  auto-filled), untranslated English draft title (shown as-is, never
  silently translated), stale channel reference.

### Actions

- Approve / reject (rejection is final for that item version; a
  corrected version re-enters as a new draft).
- Hold (stays in `review`, with a reason note).
- Force re-publish of the edge (re-runs `edge/generate_edge.py`,
  re-hashes index.json; the app only fetches changed articles).

### Rules the dashboard must enforce

- **No invented text, ever.** The dashboard has no "generate body"
  button. An item without a body can only become a short card or be
  excluded.
- **No live-channel publish while frozen.** Until Yasin says "open",
  the `published_channel` transition is disabled and the UI says so
  plainly. Dry-run / test-channel publishing only (see bridge spec).
- **Bot posts to the channel only, never DMs** (standing law 14).
  The dashboard has no per-reader messaging action at all.

## 2. Invite-code management (10 readers)

- The reader app gates access behind an invite code (see
  `data/SettingsStore.kt`: `inviteOk` flag; no code is ever hardcoded
  in the app).
- The control plane owns the code list: 10 slots, each with a code, a
  reader label (a name Yasin recognises — stored only in the private
  control plane, never in the public edge), state
  (`unused` / `active` / `revoked`), and first/last-seen timestamps
  (seen = app checked in with that code, via the edge — see below).
- Issue: generate a code, assign a label, hand it to the reader
  out-of-band (Yasin's own message — Muse does not DM readers).
- Revoke: marks the code revoked; the next edge publish carries a
  revocation list, and the app re-checks on sync. Revocation is
  advisory in Phase 1 (the edge is static; a revoked reader who
  already cached articles keeps the cache — this limitation must be
  stated honestly to Yasin before any sensitive content is published).
- Rotation: replacing all codes is a single control-plane action that
  regenerates the edge revocation list and the app gate version.

### How the app checks a code (Phase 2 design, not built yet)

- App sends code → edge endpoint? **No.** The edge is static
  (GitHub Pages has no server). So Phase 2 uses one of:
  (a) a code-hash list published in the edge (app hashes the entered
  code locally and compares — revocation by list update), or
  (b) FCM topic subscription gated by a serverless check Yasin
  approves later.
- Until Yasin picks, the app gate stays a local flag and the control
  plane keeps the authoritative list. This gap is recorded here so it
  is not forgotten.

## 3. Health view

One screen, updated on every edge publish and every sync run:

| Signal | Source | Healthy when |
| --- | --- | --- |
| Edge index hash | `edge/out/index.json` hash field | matches recomputed hash |
| Article files resolvable | every index entry opens | 100% |
| Full / short counts | generator report | matches last publish |
| Market freshness | `market.json` latest snapshot date | ≤ 24 h old (warns otherwise; the board shows its date, never a stale-as-fresh value) |
| FCM topic | Firebase project (TODO) | project configured, topic `articles` subscribed by test device |
| Bridge send lock | bridge state file | no stuck lock > 1 h |
| Production untouched | DB mtimes | unchanged by lab runs |

### Alerts (private, to Yasin only)

- Edge publish failed or hash mismatch → do not ship; keep old edge.
- A publish would drop article count sharply vs. previous index →
  hold for review (possible bad query, not silent shrinkage).
- Send lock stuck → surface, do not auto-force (force needs Yasin's
  explicit word; a double-post is worse than a delay — see the
  2026-10-23 08:00 double-flush incident).

## 4. What is explicitly NOT in the control plane

- No reader analytics, no per-reader reading history, no tracking.
  The app reports nothing back except (Phase 2, if approved) an
  anonymous "code seen" check-in for invite management.
- No payments, no Play Store, no ads.
- No editing of production databases. The lab reads `mode=ro` and
  writes only under `goals/eco-journal/app/`.

## Phase 2 build order (when Yasin orders it)

1. Invite hash list in the edge + app gate check on sync.
2. Review queue as a private page (artifact) backed by a lab-side
   queue file (never the production DBs).
3. Health view as a one-page private artifact, refreshed by the
   publish script.
4. Bridge dry-run against the private test channel, then — only on
   Yasin's explicit reopen — live-channel publishing.
