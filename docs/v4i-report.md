# V4-I Report — Remote in-app update system (LAB ONLY)

Date: 2026-10-26 (lab run, on top of v4-H commit `022b195`).
Status: **implemented in the lab, local commit only — NOT built,
NOT distributed, NOT published to the live edge.** The v4 publish
step is a separate, later step and was deliberately NOT started
in this run.

## What it is

The app itself checks for a newer APP BINARY, downloads it in the
background with progress, verifies its SHA-256, and hands it to
the system installer for a one-tap install. Shared debug signing
(the `DEBUG_KEYSTORE_BASE64` secret, commit `cddb682`) means an
update installs OVER the old version — no uninstall — provided
the installed build already carries the shared signature.

## What it is NOT (separation, stated in code and docs)

- **Content updates need NO APK.** Articles, market, radar,
  nightly, and channel items already arrive through the
  manifest-v3 delta sync (Phase 1). V4-I touches ONLY the app
  binary. The two systems are never conflated: the update
  section in Settings says so, and the version manifest is a
  separate file (`version.json`) from the content manifest
  (`manifest.json`).
- **No silent self-install.** A sideloaded app cannot install
  itself without the user tapping install — only Play in-app
  updates do that. This limit is stated in the code comments
  (`AppUpdate.kt`, `AppUpdateRepository.kt`,
  `version_manifest.py`, `AndroidManifest.xml`) and in this
  report. The win is real but bounded: the app fetches the
  update itself, so the reader never has to go back to the
  Muse chat to download an APK.
- **No FCM, no Play.** The 24h check is a WorkManager worker,
  not a push. The dormant M5 FCM scaffolding stays dormant.
  No test channel was created, nothing live changed, the
  freeze is untouched, voices/special issues stay gated.

## Design

### Edge file: `version.json` (Pages root, next to `manifest.json`)

Produced ONLY at publish time by `edge/version_manifest.py`
from the REAL v4 APK (see "Release blocker" below):

| Field | Meaning |
|---|---|
| `version_name` / `version_code` | Read from `app/build.gradle.kts` — never typed on the command line, so a typo cannot ship a manifest the app can never match. v4: `4.0.0` / `4`. |
| `apk_url` | HTTPS download URL of the APK. |
| `apk_sha256` | SHA-256 of the APK file, computed by the script. |
| `changelog_fa` | Persian changelog (may be empty). |
| `mandatory` | `true` = no dismiss button, no stored dismissal. |
| `generated_at_utc` | Informational only. |

Until the publish step runs, `version.json` does NOT exist on
the edge. The app treats a 404 as "no update info" — never as
an error banner. (Verified: `edge/out/version.json` absent.)

### App-side flow

1. **Check.** On launch (after the invite gate, only when the
   toggle is on) and every 24h via `AppUpdateWorker`
   (NOT every 15 minutes — a binary release is never on a
   content-sync cadence). The worker only CHECKS; it never
   downloads anything itself.
2. **Offer rule** (`shouldOffer`, parser + decision in
   `AppUpdate.kt`): offer iff `manifestCode > installedCode`,
   and (for optional updates) `manifestCode != dismissedCode`.
   Mandatory updates ignore the stored dismissal. Same or
   older code never offers. An optional update dismissed at
   code 5 still offers when code 6 appears.
3. **Download.** `AppUpdateRepository.download()` streams to
   `cache/updates/app-update.apk` (never the full cache, never
   anywhere else), reports progress, and re-verifies the hash
   of the FINISHED file. Mismatch → file deleted, state
   `Failed("hash_mismatch")`, nothing installable left behind.
   The pending record (name/code/url/sha/changelog/mandatory)
   is stored in `SettingsStore` so a downloaded-but-uninstalled
   update survives an app restart and the feed banner and the
   Settings card always show the same state.
4. **Install.** One tap opens the system installer via a
   `FileProvider` URI (`${applicationId}.fileprovider`,
   `cache/updates/` only, `grantUriPermissions` on the single
   intent). On Android 8+, if "install unknown apps" is not
   allowed for ECO JOURNAL, the app opens that exact system
   settings page for its own package and shows the
   `NeedsInstallPermission` state — it never asks for a
   broad permission and never installs anything itself.
5. **Surfaces.** Feed: one compact row directly under the
   masthead (invisible for Idle/Checking, so the feed is never
   pushed down by a non-state). Settings: full card (current
   installed version, auto-check toggle, manual re-check,
   download progress, install, dismiss, retry) + the
   notification channel `eco_app_updates` for the worker's
   "update available" note (only when notifications are on
   AND the update is new — no repeat spam for a dismissed
   optional update).

### Strict parser (fail-closed)

`parseVersionManifest` rejects: non-JSON, missing/extra-wrong
fields, non-HTTPS `apk_url`, malformed `apk_sha256` (must be
exactly 64 lowercase hex), non-string changelog, non-boolean
mandatory, `version_code <= 0`. Any rejection → `NoInfo`,
never a partial offer. The parser does NOT trust
`version_name` for the offer decision — only the code.

### Release blocker (publish-time check, enforced by tooling)

`edge/version_manifest.py generate` REQUIRES `--apk` (a real
file) and `--apk-url` (HTTPS) and `--changelog`; it REFUSES
without a real APK and writes nothing — there is no
placeholder, example, or "fill in later" hash anywhere in
this step. `verify` FAILS unless the manifest's name/code
equal `build.gradle.kts`, the hash equals the given APK
file's SHA-256, and (when aapt is available) aapt badging
agrees on package/versionName/versionCode. **The v4 publish
step must run `generate` with the REAL v4 APK and `verify`
against that same APK, and must refuse to publish on any
failure.** The manifest MUST name `4.0.0` / `4` and the v4
APK's SHA-256, or it is a release blocker — this is recorded
here and in the script header, not left to memory.

## Files changed (v4-I only)

New:
- `app/src/main/java/com/ecojournal/app/data/AppUpdate.kt` —
  domain types, strict parser, offer rule, hash, installed
  version, honest-limit comments.
- `app/src/main/java/com/ecojournal/app/data/AppUpdateRepository.kt` —
  check/download/install/dismiss + session state, pending
  restore, worker notification guard.
- `app/src/main/java/com/ecojournal/app/data/AppUpdateWorker.kt` —
  24h periodic check, no download, no repeat notification.
- `app/src/main/java/com/ecojournal/app/ui/components/AppUpdateBanner.kt` —
  full Settings card + compact feed row (48dp targets,
  Persian labels, role/description semantics).
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths,
  ONLY `cache/updates/`.
- `edge/version_manifest.py` — generate/verify (see above).
- `edge/verify_v4i.py` — verifier (see below).
- `docs/v4i-report.md` — this report.

Modified:
- `app/build.gradle.kts` — versionCode `4`, versionName
  `4.0.0` (v4 program identity; never reused, never reversed).
- `app/src/main/AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES`
  + FileProvider declaration.
- `app/src/main/java/com/ecojournal/app/EcoJournalApp.kt` —
  `AppUpdateWorker.schedule(this)` alongside the 15-minute
  content sync (separate worker, separate cadence).
- `app/src/main/java/com/ecojournal/app/MainActivity.kt` —
  launch check after the invite gate; feed banner wiring.
- `app/src/main/java/com/ecojournal/app/data/SettingsStore.kt` —
  `updateCheckEnabled` (default on) + pending-update record.
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt` —
  compact row under the masthead.
- `app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt` —
  update section + full card.
- `app/src/main/java/com/ecojournal/app/util/Notifications.kt` —
  `showAppUpdate` + `eco_app_updates` channel.
- `app/src/main/res/values/strings.xml` — 17 `app_update_*`
  Persian strings.
- `docs/v4/v4-program-plan.md` — V4-I marked implemented in
  lab (this step); phone + publish gates still open.

NOT touched by this step (deliberately left out of the
commit): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `edge/out/revocations.json` (pre-existing
uncommitted changes from earlier work), and all other
untracked lab files. `edge/out-v4i-fixture/` (verifier
fixture: dummy APK + tampered manifest) is regenerated by
every verifier run and was removed after verification.

## Verification (lab)

`python3 edge/verify_v4i.py` — **PASS, all checks**
(10 Kotlin files brace/paren-balanced with a stateful scan;
strings.xml parses, 403 strings, no duplicates, all 17 new
strings present and every used one resolves; gradle identity
`4.0.0`/`4`; manifest permission + FileProvider + paths;
all six wiring points present; version-manifest fixture
round-trip: generate with a dummy APK computes the hash from
that file, verify passes, tampered hash FAILS, generate
without a real APK REFUSES and writes nothing; `shouldOffer`
decision table 6/6; `edge/out/version.json` absent; no FCM /
Play activation and no `--real` in v4-I files).

NOT verified here (cannot be verified in the lab, by design):
- No cloud build was run in this step (task constraint), so
  the Kotlin has not been compiled. The first v4 cloud build
  is the compile gate; the verifier's balance/string/wiring
  checks are the lab substitute, not a replacement.
- Nothing was tested on a phone (see below).

## What still needs the phone

1. **Install-over test (E8 remainder):** install the v4 APK
   over the current shared-signature build on the phone and
   confirm it installs WITHOUT uninstall (this is the
   shared-signing proof for the v4 binary itself; the Phase 1
   proof was for the Phase 1 binary).
2. **Banner + card on device:** with a real `version.json`
   on the edge (publish step), confirm the feed row appears
   under the masthead, the Settings card shows the current
   version, download progress moves, and the install tap
   opens the system installer.
3. **Hash-mismatch path on device:** point the manifest at a
   wrong hash (staging edge only) and confirm the file is
   deleted and the banner shows the failure code, with no
   installable file left behind.
4. **Permission path on device:** with "install unknown apps"
   off for ECO JOURNAL, confirm the settings page opens for
   this package only and the state explains it in Persian.
5. **Mandatory vs optional on device:** dismiss an optional
   update (banner gone, no repeat notification), then confirm
   a mandatory update cannot be dismissed.
6. **24h worker:** confirm the worker fires once a day and
   does NOT download anything by itself, and does NOT
   re-notify for a dismissed optional update.
7. **TalkBack / font scales:** the banner rows carry role +
   Persian descriptions and 48dp targets, but acceptance is
   on-device only (same rule as Phase 7).

## What still needs the publish step (NOT done here)

1. Cloud build green for the full v4 code (all phases +
   V4-H + V4-I) — the compile gate.
2. `apksigner` shared-signature verification + `aapt`
   identity check on the built APK (PUB-4/PUB-5).
3. `edge/version_manifest.py generate --apk <real v4 APK>
   --apk-url <HTTPS URL of the v4 APK> --changelog "<Persian
   changelog>"` — then `verify --apk <same APK>` against the
   generated manifest. **If verify fails, the publish stops.**
   The manifest must name `4.0.0` / `4` and the real v4 APK
   SHA-256.
4. Publish `version.json` to the Pages root together with the
   v4 edge content (publish_edge path, with the v4 rebase
   note from the v3 publish: local main is ahead of the edge
   commit — rebase cleanly first, resolve the old uncommitted
   `edge/out/revocations.json` change).
5. Phone checklist on the final v4 APK (above) + Yasin's own
   hand steps (test-channel Tier B, voice-list finalisation,
   U4 pins, Blacksword licence decision) + his explicit
   freeze-lift sentence with scope — none of which this step
   pre-empts or replaces.
