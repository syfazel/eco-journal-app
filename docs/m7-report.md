# M7 report — invite gate + control plane (LAB ONLY)

Base: cd96ada (M6). This pass is NOT pushed; no cloud build was run
here (stated plainly — M8/M9 will build). No live send, no live
channel/bot/cron/freeze change, no Firebase change, no edge-URL
change (Pages root, per vnext-decisions.md §2).

## A) App invite gate (real, replacing open-by-default)

- New `InviteScreen.kt`: first-run code entry; the feed stays
  locked until a valid code is entered (`MainActivity.kt` gate).
  Codes are SHA-256 hashed on the device; only hashes are
  compared/stored (new `InviteGate.kt`, `SettingsStore`
  `invite_hash` key). Mismatch shows a fixed error, never a hint.
- Edge-published hash-only lists: `control.py invites publish`
  writes `edge/out/invite_hashes.json` (10 hashes, from the pilot
  file) and creates `edge/out/revocations.json` (empty at launch).
  The same two files are copied into the bundled seed
  (`app/src/main/assets/seed/`), so first-run verification works
  fully offline. No code or hash is printed, logged, or quoted in
  any report — counts only.
- Sync-time re-check (`EdgeRepository.kt`): the sync pass fetches
  both edge lists; only a successfully fetched list can re-lock.
  A revoked or list-dropped hash clears `inviteOk`; cached rows
  are hidden, not deleted, so re-entry restores them. A fetch
  failure skips the re-check (offline never locks a reader out).
- Reconciliation (decision §1): `control.py invites retire-lab`
  retired all 20 lab queue rows (duplicate pilot labels, unused +
  revoked) to state `retired`. The pilot file is the sole
  authority; lab rows were NOT promoted to real readers.
- Settings: real confirmed text, a revocation note, and a manual
  re-lock button (`clearInvite()`).

## B) Bridge / control plane (DRY-RUN ONLY, live_sends = 0)

- Staging → edge promotion: `control.py promote
  channel-<message_id>` writes the edge article file (app_key +
  content_hash, generator-identical) and rewrites the edge index
  with its hash recomputed. Verified mark-app: `mark-app` now
  refuses an item absent from the new index or with a
  content_hash mismatch, instead of certifying a lie (P1-39).
- Split sends: `numbered_split_parts()` (title part 1,
  attribution + URL last, every part ≤ 4096); the worker sends
  all parts and `record_sent` stores ALL message ids in one
  transaction (P1-34). A single over-cap paragraph still needs a
  human edit; non-size failures still block (no laundering).
- Atomic send+record: worker marks `published_app → sending`
  before the first network call; a mid-split failure leaves the
  item in `sending` with partial ids in the note. A later pass
  NEVER auto re-sends a `sending` item — it is reported blocked
  for `control.py reconcile --action retry|hold` (P1-35). Duplicate
  `record_sent` is a no-op with a warning, never a crash.
- Captions + edits (`channel_reader.py`): caption is read when
  text is absent, with `has_media` + a media-not-republished note
  (P0-12). An edited post refreshes a queued payload (status
  untouched) or creates an `edits` record when already sent
  (P1-38). Orphan edits are flagged, not staged; `--chat-id`
  filters source chats; dates are Tehran time; the staging
  manifest is cumulative (P2-14).
- Channel allow-list + cadence: live sends require the id in
  `BRIDGE_CHANNEL_ALLOWLIST` (env or lab file); an empty list
  fails closed (P1-36). `schedule` and the worker enforce max
  2/day on the Tehran date (P1-40).
- Control CLI fixes: `queue list` validates status/limit and
  shows full titles + blocked reasons; `queue show` prints body
  verbatim, original URL, provenance, kind, warnings (P1-42);
  `health` never crashes on corrupt JSON, reports lock age
  (>1h alert, append-mode probe), market max-timestamp, both
  reports with source labels, and FCM honestly "dormant"
  (P1-41, P2-15); invite generation loops on collision and
  rejects duplicate labels; `verify` output says "operator
  verified", never "reader seen" (P1-44); id-less enqueue uses a
  payload hash instead of colliding on `article:` (P2-12); notes
  are bounded (P2-11); `get_item` uses cursor description, not a
  hardcoded column order.
- Dry-run harness: same single `bridge/send.lock` as the worker,
  LEGAL synced with bridge_lib (incl. review → rejected), split
  hint synced, report labelled with its source (P2-10).
- FCM send side: NOT built — the recorded decision is NO
  Firebase for the vNext pilot, so FCM stays receive-scaffold
  only, explicitly (P1-22/P1-45 conditional on a "yes" that was
  not given).

## Verification (this pass)

- `python3 bridge/test_phase2.py`: 29/29 OK (19 pre-existing +
  10 new TestM7: numbered split, split-send ids, caption staging,
  edited queue + edit record, duplicate record_sent, sending
  reconciliation, allow-list + cadence, verified mark-app,
  queue show + retire-lab, promote).
- `python3 bridge/dryrun.py`: 13/13 cases passed, live_sends = 0.
- `python3 bridge/publish_worker.py --run` (dry-run): live_sends = 0.
- `control.py health`: edge hash match, 0 unresolvable files,
  invites retired: 20, FCM dormant.
- Kotlin: brace/paren balance 0 in all 23 files (no local Gradle
  build exists on this machine and no cloud build was run — the
  code must still pass the M9 cloud build before any pilot).
- Secret scan: no invite-code pattern in any changed file.

## What remains for M8

- Cloud build + phone test of the gate (wrong code / valid code /
  revoked re-lock / Settings re-lock), including the known
  first-emission flash (returning readers may briefly see the
  gate before DataStore loads — a loading state is M8 polish).
- M8 scope from the plan: final icon, strings/digits polish,
  accessibility, diagnostics screen, empty/error states.
- NOT in M7 (by decision): FCM send side, test-channel creation,
  any push, any freeze lift.
