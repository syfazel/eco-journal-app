# Pilot test plan — ECO JOURNAL app, 10 readers (LAB, Phase 3)

Status: PREPARED, NOT STARTED. No APK has been distributed, no reader
has been contacted, and no invite code has left the lab. Distribution
happens only when Yasin (or a later phase) explicitly authorises it.

## What the pilot tests

A debug APK built from `goals/eco-journal/app/` (see `README.md`
"Building the APK" and `docs/apk-build-phase3.md` once the Phase 3
build lands). The pilot APK is usable offline out of the box: on
first launch it seeds its cache from the bundled snapshot in
`app/src/main/assets/seed/` (249 full articles + 32 short «گزیده»
cards + market data, verbatim from the lab edge export). The edge
URL is intentionally still empty and FCM is scaffold-only, so:

- YES, tested in this pilot: install, invite-code first run,
  offline reading, search, bookmarks, font size, themes, market
  board, 15-minute poll behaviour.
- NOT tested in this pilot: push notifications (no Firebase project
  yet), live edge sync (no Pages URL yet), Telegram bridge live
  sends (dry-run only, frozen live channel).

## Before handing anything out

- [ ] Confirm the APK file (path + size + package id) from the
      Phase 3 build report; install it once on a test device first.
- [ ] One code per reader, out-of-band only (in person, or a
      private message the reader already uses — never the ECO
      JOURNAL channel, never a public post). Codes live ONLY in
      `docs/pilot-invite-codes-private.md` (gitignored, lab only).
- [ ] Tell each reader up front: this is a test build, not a store
      app; Android will ask to allow install from an unknown source
      for this one install (Settings → allow this source → install).
      That prompt is expected and is NOT a sign of malware — but
      readers should only install the file handed to them directly,
      never a forwarded copy from a stranger.
- [ ] Minimum device: Android 8.0 (API 26) or newer; ~50 MB free
      space; no account or sign-in is required inside the app.

## Reader checklist (each of the 10 readers, ~20 minutes)

1. **Install** — install the APK; confirm the app opens to the
   feed without any network setup. Report: device model + Android
   version + install result.
2. **Invite code** — on first run, enter the code handed to you
   (exactly, case-sensitive). Confirm the feed unlocks. Report:
   code accepted on first try? (Never share your code with another
   reader — each code is single-reader.)
3. **Offline reading** — turn on airplane mode, kill and reopen
   the app, open 3 articles from different categories. Confirm
   full text renders, Persian RTL is correct, and no empty feed.
4. **Reader controls** — in one article, move the font-size
   slider, switch light → dark → sepia, bookmark the article,
   reopen it from Bookmarks. Report anything that looks wrong.
5. **Search** — search for a word from an article title you saw
   (e.g. «نفت» or «طلا»); confirm results appear offline.
6. **Categories (feed tabs)** — there is NO separate Categories
   screen (deleted in M6). On the home feed, swipe/tap a category
   tab and confirm only that category's articles show, and that the
   tab list matches the categories actually present in the cache.
7. **Market board** — open the market section; confirm the
   snapshot DATE is shown and that stale data is labelled as
   stale (the bundled snapshot is dated 2026-10-24 — it is a
   snapshot, not live prices).
8. **15-minute poll** — leave the app installed for at least
   15 minutes with network on, then reopen; confirm no crash
   and no invented content appears (with no edge URL, sync must
   report "not configured", not fabricate articles).
9. **Crash / freeze report** — any crash, freeze, or blank screen:
   note what you tapped just before it and your device model.

## M7 acceptance (operator tests, lab only — not reader steps)

- Wrong code on first run: feed stays locked, fixed error only,
  no hint about any valid code.
- Revoked code re-locks on next sync: after
  `control.py invites revoke --label pilot-XX` (hash appended to
  edge revocations), a sync re-locks the feed; cached articles are
  hidden, not deleted, and re-entering a valid code restores them.
- Split send: a >4096-char queued item sends as numbered parts
  with ALL message ids recorded (mock-sender test in
  test_phase2.py TestM7); a crash-window "sending" item is never
  auto re-sent — it waits for `control.py reconcile`.
- Verified mark-app: `mark-app` refuses an item absent from the
  new edge index, accepts one present with a matching content
  hash. Staging -> edge promotion: `control.py promote
  channel-<message_id>` writes the edge entry mark-app verifies.

## Feedback to collect (one short message per reader)

- Device + Android version.
- Pass/fail per step above (1–9), one line each.
- The single most annoying thing (if any), in one sentence.
- "Would you keep using this if content refreshed daily?"
  (yes / no / only if …).

## After the pilot

- [ ] Collect the 10 feedback messages; fix crashes first,
      polish second.
- [ ] Revoke any unused or leaked code:
      `python3 bridge/control.py invites revoke --label pilot-XX`
- [ ] Delete `docs/pilot-invite-codes-private.md` once codes are
      no longer needed (it is the only plaintext copy).
- [ ] Do NOT roll pilot readers into a public release: the pilot
      APK is a debug build with placeholder launcher icons.
      Public-facing release needs the final icon, a signed
      release build, and Yasin's explicit go-ahead.

## Explicitly out of scope for this pilot

- No live-channel posting, no unfreezing of the live platform.
- No FCM/push testing, no edge-URL sync testing (those need the
  Firebase project and GitHub Pages inputs, still outstanding).
- No distribution via any store, channel, or third-party host.
