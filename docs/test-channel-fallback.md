# Test Telegram channel — fallback for later (NOT to be done now)

## Status (25 Oct 2026, lab)

**PREPARED, not created.** No test channel exists yet, and none is
claimed. Test coverage for the bridge write path TODAY comes from
the dry-run harness (`bridge/dryrun.py`, report in
`bridge/dryrun-report.json`) — it exercises the full write path
without sending anything anywhere.

## Why this cannot be done from the lab

A Telegram **bot cannot create a channel via the Bot API** — there
is no such method. Any plan claiming otherwise is wrong. Creating a
channel is a user action inside the Telegram app. That is the whole
reason the dry-run harness exists: it is the test coverage that
does not need a channel at all.

## The single-tap fallback (one future action, owner only)

When a real send test is wanted, in the Telegram app:

1. Create a **private channel** (any name, e.g. `EJ test`).
2. Add `@YASINECOBOT` as an **admin** of that channel.
3. Paste the channel id (it starts with `-100…`) into the bridge
   config as `TEST_CHANNEL_ID`.

That is it — one channel, one admin add, one id. Until then:

- The bridge write direction stays **dry-run only** (spec:
  `docs/telegram-bridge-spec.md`).
- The live channel stays **frozen** regardless: a test channel
  existing does NOT unlock live-channel sends. Live sends need
  Yasin's explicit reopen order, separately, per release.

## What the dry-run harness already proves (no channel needed)

- Status machine transitions, including refusal of illegal jumps
  and of unapproved items (see `bridge/dryrun-report.json`).
- Idempotency: the same article key sent twice is delivered once.
- Send lock: a concurrent sender exits cleanly instead of
  double-posting (the 2026-10-23 08:00 lesson).
- Channel formatting: exact text that WOULD be sent, validated
  against the Telegram 4096-character cap, with source attribution
  present and no placeholder text.
