# ECO JOURNAL — Version 5, Phase P6 Report: publish 5.0.0 (versionCode 6)

Status: **PUBLISHED TO EDGE — phone checklist NOT passed.**
Lab publish only: no cron change, no live-channel change or
post, no special-issues/voices publication or gate change,
no FCM/Play, no Bale work, no domain steps, no schema
change (none was exposed by the build; none was improvised).
Task order: P6 publish, per `docs/v5/v5-program-plan.md` §4,
on top of P5 commit `f2d936d`, under Yasin's full go-ahead
of 2026-10-26 ~01:36 Tehran (all open decisions delegated
to Muse).

## 1. Gates carried forward — NONE newly passed by this phase

**G1 — OPEN.** Defect A1 (feed flashes content for ~1
second on refresh, then jumps back to the empty page) is
NOT identified, and nothing in this phase fixes it, works
around it, or uses it as a rationale. No device run was
available in the lab. The P1 §4 protocol (§8 below) is
still owed, on the v5 build, by Yasin's hand only.

**G2 — OPEN.** Device screenshots at 0.85×/1.0×/1.3× with
measured contrast do not exist. Lab contrast ratios from
P2/P5 do NOT pass G2.

**G4 — EDGE LEG PASS / SYNC LEG OPEN.** Unchanged from
P4: the status-aware bundles/radar guards, required-only
clock, and edge contract are lab-verified, but only a
phone sync can prove `last_sync_at` advances on device
while bundles/radar are unpublished.

**G5 — NOT MET.** The TalkBack walkthrough script is
written (P5) but the walk itself was NOT performed — no
walk log, no recording, and P6 claims none.

**G6 / PUB-6 — NOT PASSED.** The phone checklist on the
v5 build (§8) has not been run. Any report that marks
PUB-6 passed is wrong; the edge publish below is real,
the phone pass is not.

## 2. Step 1 — version bump (commit 873701f)

- `app/build.gradle.kts`: `versionCode = 5` → `6`,
  `versionName = "4.0.1"` → `"5.0.0"`. Code 6 never
  reuses 5; package `com.ecojournal.app` unchanged;
  shared debug signing unchanged, so the APK installs
  OVER 4.0.1 without uninstall and without invite
  re-entry. (A phone still on the old vNext signature
  needs one final uninstall + reinstall + pilot-01
  re-entry, exactly as before.)
- No other file touched: the `4.0.1` strings elsewhere in
  the tree are historical fix-plan comments, not version
  sources. `version_manifest.py` reads the version from
  `build.gradle.kts` only, so no second source could
  drift.
- P1's configuration-time `git rev-parse --short HEAD`
  for `BuildConfig.GIT_COMMIT` did NOT break the cloud
  build (see §3) — the first suspect is cleared by the
  green run itself.

## 3. Step 2–3 — push + cloud build GREEN, first round

- `origin/main` sat at the 4.0.1 edge publish `d827a6c`,
  which is an ancestor of P5, so the push was a clean
  fast-forward — **no rebase was needed, no force-push**
  (never used, never needed):
  `d827a6c..873701f main -> main`.
- Cloud build: GitHub Actions run **40241876398**
  ("Android debug build", head `873701f`) concluded
  **success**, first round, **zero fix rounds**. No local
  Gradle build was attempted (impossible on this VM, per
  task order). The P5 risk list (P1 git call, P5 semantics
  imports, merged switch Rows, FeedScreen scroll
  modifiers) produced no error.
- Side note, harmless: the edge push (§5) triggered a
  second Android build run (40242499502) because the
  workflow runs on any push to main. It builds the
  identical code tree (the edge commit adds no code), so
  it cannot change the distribution artifact; it was
  still in progress when live verification completed and
  is NOT the distribution build. Distribution is the
  40241876398 artifact, verified in §4.

## 4. Step 4 — APK artifact verified

Downloaded from run 40241876398 to
`build-output/app-debug-v5.apk` (gitignored, never
committed to the code tree):

- Size: **24,700,496 bytes**
- SHA-256:
  `385207599e11808a0dea592c8e54aecd61ce896f2c3da9cca06033ea4a151d9f`
- aapt badging: package `com.ecojournal.app`,
  versionCode `6`, versionName `5.0.0`, minSdk `26`,
  targetSdk `34`, compileSdk `34`, label `ECO JOURNAL`
  (all locales).
- Signing cert SHA-256: **`45220b15…` — MATCH** with the
  shared debug cert. Method note, stated honestly:
  `apksigner` itself could not run on this VM (no Java
  installed), and the v5 APK carries v2/v3 signatures
  only (no v1 META-INF block), so the cert was extracted
  from the APK Signing Block and fingerprinted with
  openssl. The method is validated, not assumed: it
  returns the identical `45220b15…` fingerprint for the
  v5 APK, the known shared APK, and the 4.0.1 APK, and
  openssl parses the extracted blob as `CN = Android
  Debug, O = Android, C = US`. A cert mismatch would
  have stopped distribution outright; there is none.

## 5. Step 5 — edge publish (commit 7cd988b)

Mirroring the 4.0.1 edge publish `d827a6c` exactly —
the edge commit touches ONLY two files:

- `edge/out/app-debug-v5.apk` (force-added; `*.apk` is
  gitignored, so `git add -f` was used)
- `edge/out/version.json` (regenerated)

`edge/version_manifest.py generate` ran on the REAL
5.0.0 APK (no placeholder hash exists anywhere), then
`verify` passed: `VERIFY PASS … matches
build-output/app-debug-v5.apk (5.0.0/6, hash OK, aapt
identity OK)`. Live `version.json`:

- `version_name: "5.0.0"`, `version_code: 6`
- `apk_url: https://syfazel.github.io/eco-journal-app/app-debug-v5.apk`
- `apk_sha256: 385207599e…51d9f` (the §4 hash)
- `mandatory: false`

Content manifest v3 was **not** regenerated from the
database in this phase (same choice as `d827a6c`):
P6 is a binary publish, not a content publish, and
regenerating content would have been a content change
outside the P6 order. The live content manifest is
therefore unchanged, and §6 verifies its counts sane.
The old `app-debug-v4.0.1.apk` stays hosted alongside
the v5 APK.

## 6. Step 6 — live verification (all PASS)

Checked against `https://syfazel.github.io/eco-journal-app/`
after the Pages deploy for commit `7cd988b` (run
40242499541, success):

- Live `version.json` names **5.0.0 / 6** with the real
  hash (matches §4 exactly).
- `apk_url` download hash **MATCH**:
  `385207599e…51d9f` = the published APK hash.
- Live manifest v3 counts sane: articles **249**,
  shorts 32, market 164, channel_refs 23, bundles **0**,
  radar **0**, nightly 0, voices 0, special_issues 0 —
  bundles/radar 0 is the expected `not_yet_published`
  state the P4 fix handles, not a regression.
- Offer rule: live code **6 > installed 4.0.1 code 5**,
  so a 4.0.1 phone MUST show the remote-update banner;
  on the v5 phone itself 6 = 6, so no banner. If the
  banner does not appear on the 4.0.1 phone, that is
  itself a defect to report, not a reason to silently
  sideload — the Muse attachment is fallback only.

## 7. Step 7 — plan updated

`docs/v5/v5-program-plan.md` §7 (appendix only — the
Phase 0 text is untouched) records what each phase
actually did vs planned, including this publish and the
open gates. This report + the appendix are committed
separately from the edge commit, so the edge commit
stays edge-only.

## 8. Phone test script for Yasin (Yasin-only device steps)

Do NOT mark any item passed without the evidence named.
Install path: let the in-app banner deliver the update
(6 > 5); if it does not appear, report that first. If
sideloading from the Muse attachment instead: download
in Muse, open from Samsung My Files → Downloads →
Package Installer (the Muse preview cannot install).
No uninstall is needed from 4.0.1 (shared signature);
no invite re-entry is needed (only the old-vNext-
signature phone needs uninstall + pilot-01 re-entry).

1. **Banner (offer rule):** on the 4.0.1 phone, open the
   app and confirm the update banner appears (5.0.0
   offered, 6 > 5). On the v5 phone afterwards, confirm
   NO banner (6 = 6).
2. **Install-over:** install over 4.0.1 without
   uninstall; confirm the app opens signed in (no invite
   re-entry) and Diagnostics line 1 shows the v5
   fingerprint (version 5.0.0, code 6, git short hash).
3. **Feed STAYS (G1/G3 protocol):** open Diagnostics
   FIRST and copy the fingerprint line verbatim. Tap
   refresh ONCE while screen-recording. Immediately
   after any flash, copy the Diagnostics report verbatim
   (fingerprint + sync trace + feed-branch trace +
   reconciliation). Then repeat the recording across:
   manual refresh, auto-sync, rotation,
   background/foreground, and process kill/restart —
   the feed must STAY populated in every case. Report
   Wi-Fi/mobile and whether the phone's browser opens
   the edge manifest URL. If the branch trace shows
   `front_page → empty`, repeat with rotation and
   background/foreground and say so explicitly.
4. **Bundles reason line (G4 sync leg):** open Bundles
   and confirm it shows a reason (unpublished), never
   `http_404`; then run a full sync and confirm
   `last_sync_at` advances while bundles/radar are
   unpublished. Copy the per-section Diagnostics lines.
5. **Diagnostics copy (G1 evidence):** copy the full
   Diagnostics report (fingerprint + sync trace +
   branch trace) into the chat — the P1 §3 decision
   table is read from exactly this copy.
6. **TalkBack walkthrough (G5):** perform the P5 script
   (`docs/v5/talkback-walkthrough.md`) end to end with
   TalkBack on: keep a walk log (every step pass/fail,
   especially the five merged Settings switches) + a
   recording. A claim without the log fails G5.
7. **Font extremes + contrast (G2):** screenshots at
   0.85×, 1.0×, 1.3× font scales showing masthead, hero,
   rows, reader, and the five-item bottom bar with
   labels fitting on one line; contrast measured from
   those screenshots (body ≥ 4.5:1, large ≥ 3:1).
8. **Sections spot-check:** War Room, Market (seed-date
   line), Nightly, Analysis, Bookmarks (orphan reasons),
   gated voices/special-issues screens (gated reason,
   never a "coming soon" name list, never an error for
   a gated section).

## 9. Delegated decisions (recorded, not re-asked)

- Masthead: 32sp bold navy newspaper masthead (P2).
- APK hosting: Pages force-add (this phase, as in
  4.0.1) — NO public GitHub release.
- Bottom bar: five primaries (feed, warroom, market,
  bookmarks, settings) + sections hub (P2).
- Content gates: unchanged — voices and special issues
  stay gated; special-issues crons stay disabled.
- Bale: still pending and separate; nothing in v5
  depends on it, v5 does not start it.

## 10. Commits, files, hard-limit compliance

- `873701f` — v5 P6 version bump (gradle only).
- `7cd988b` — edge publish (edge/out only: APK +
  version.json).
- Docs commit — this report + plan §7 appendix.
- `build-output/app-debug-v5.apk` (local only,
  gitignored): 24,700,496 bytes, SHA-256 §4, cert
  `45220b15…`.
- Hard limits kept: no cron touched, no bot change, no
  channel post, no production database change, no
  schema change, no FCM/Play, no Bale work, no domain
  steps, no night-law/bar/gate/cadence behaviour
  change. The APK is NOT sent to Yasin by this phase —
  delivery is the parent's.
