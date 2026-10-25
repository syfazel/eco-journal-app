# Telegram Bridge Spec — Channel ↔ App (Phase 1 spec)

**Status:** SPEC ONLY in Phase 1. No bridge code is built yet, and
nothing in this document posts to any channel.

Standing law 14 applies throughout: **the bot posts to the channel
only, never DMs to readers.** The bridge has no reader-messaging path
at all, in either direction.

## Direction 1: channel → app (read)

### Why a seed, not a full history pull

The Telegram Bot API does not give a bot the full history of a
channel it administers. So the initial app content cannot be "all past
channel posts". Phase 1 seeds the app from the databases that already
hold the editorial record (see `control-plane-spec.md` §1):

- full articles: `article_extracts` approved/posted with body (249),
- short cards: approved bot drafts with `body_fa` (32),
- channel references: `channel_posts` rows (23) — message_id only,
  no invented text.

### Live forwarding (after activation)

Once the bridge is activated (Phase 2, on Yasin's order):

1. The bot, as channel admin, receives each new channel post it sends
   (it knows what it posted — it posted it).
2. The bridge converts that post into an app feed item: title/body
   verbatim, `kind` = full or short depending on whether a full body
   exists, labelled «گزیده» when short.
3. The item enters the review queue at `approved` only if it already
   passed editorial review for the channel; otherwise at `review`.
4. On edge publish, the item appears in `index.json` and FCM sends a
   data message with that article's id (see §3). One publish, two
   audiences — but the channel post and the app item are the same
   editorial decision, not two decisions.

### Deduplication

- Idempotency key: `channel:<message_id>` for channel-origin items,
  `article:<extract_id>` for database-origin items. The edge
  generator and the bridge share the key space: the same key can
  never produce two app items.
- The 2026-10-23 lesson applies: headline-hash keys drift with
  transliteration, so keys here are database/message ids, never
  headline hashes.

## Direction 2: app → channel (write, GATED)

This direction exists so an item first published in the app can later
reach the channel — but it is gated at every step, and disabled while
the channel is frozen.

### Status machine

```
draft → review → approved → queued → published_app → published_channel
```

| Transition | Who / what | Gate |
| --- | --- | --- |
| draft → review | pipeline / bridge | automatic on ingest |
| review → approved | Yasin or delegated Muse editorial | explicit decision, recorded with provenance |
| approved → queued | control plane | scheduled slot (Phase 2: max 2/day, mirroring the frozen special-issue cadence only if Yasin re-approves it) |
| queued → published_app | edge publish | index.json regenerated + hashed; app sync picks it up |
| published_app → published_channel | bridge sender | **see gates below** |

### Gates on the channel send (all must hold)

1. **Channel open.** Yasin has explicitly said to reopen the live
   channel. Until then this transition is disabled in code and in
   the control plane UI. Test channel / dry-run only.
2. **Approved + queued.** No draft or merely-reviewed item is sent.
3. **Idempotency key unused.** The bridge records every sent key in
   a state file; if the key is already recorded, the send is skipped
   (no double-post, even if two processes run — see lock below).
4. **Send lock.** A non-blocking file lock around the whole
   select-then-send step (the 2026-10-23 08:00 incident: two
   overlapping flushes double-posted 17 stories because there was
   no lock). A concurrent sender exits cleanly with "another send
   is running".
5. **Test channel first.** Every new bridge version sends to the
   private test channel first, is inspected, and only then is the
   live channel enabled — by Yasin's word, per release.
6. **Bot token hygiene.** The token is referenced by environment
   variable name only, lives in `.env` (600), and is never printed,
   logged, embedded in the app, or committed to any repo. Telegram
   from this server goes through the egress proxy only (existing
   infrastructure lesson).

### Dry-run mode

- `dry-run` logs the exact message that *would* be sent (title, body
  preview, idempotency key) and records nothing as sent.
- Dry-run is the default until Yasin orders otherwise. Any bridge run
  without an explicit `--live` flag (Phase 2 CLI) is a dry-run.

### Failure handling

- Failed send → item stays `queued` for the next pass (same as the
  existing production rule: failed channel publish → `draft` for
  automatic retry on the next pass). It is never silently dropped
  and never marked published without a real `message_id` back from
  Telegram.
- The only proof of posting is the returned `message_id` recorded in
  the bridge state file — not a counter, not a log line saying "sent".

## Direction 3: FCM push (app notification)

- Data message contract (already scaffolded in
  `fcm/EcoMessagingService.kt`): `data["article_id"]` → the app
  fetches that article from the edge. The article body is never
  trusted from the push itself.
- Topic: `articles` (subscribed only after Yasin supplies a Firebase
  project + `google-services.json`; no fake keys, no placeholder
  project).
- Fallback: WorkManager 15-minute polling + local notification
  (already in the app) covers readers until FCM is live, and covers
  FCM outages afterwards.

## What Yasin still needs to supply (bridge-specific)

1. A **private test Telegram channel** (Yasin creates it, adds the
   bot as admin) for bridge dry-runs and first live-send tests.
2. Explicit **reopen order** for the live channel before any
   `published_channel` transition is enabled.
3. Confirmation of the daily cadence for app→channel sends (default
   proposed: same 2/day cap as the frozen special-issue service, but
   Yasin decides at reopen time).
