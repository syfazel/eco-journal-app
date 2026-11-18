# ECO JOURNAL — Yemen only (v6.0.0 / code 13)

Flutter/Dart rebuild of the ECO JOURNAL app. **One feature: the Yemen
dashboard.** No messaging, no invite gate, no broadcast packets, no shared
password, no other sections, no FCM, no channel posts, no cron.

## What it does

Six tabs, all driven by one bundled/live JSON document (`yemen.json`,
dashboard v5 data — 90 places, 24 events, 8 frontlines, 9 arrows,
12 unit positions; Aden: 7 images / 7 cross-check / 5 channels /
9 social posts / 4 amplifiers / 28 sources):

1. **وضعیت و نقشه** — headline, counts, drawn conflict map
   (`CustomPainter`: frontlines, advance arrows with triangular heads,
   unit positions, faction colours + legend, dashed 5-slot Aden ring),
   the ±10 km coordinate warning always under the map, human stats.
2. **جبهه‌ها** — frontlines, advance arrows, unit positions.
3. **عدن** — the full Aden investigation: verdict, 5-direction ring,
   images, cross-check, official channels, social posts, amplifiers,
   seen/taiz notes, evidence layers, contradictions, 28 sources.
4. **پرونده‌ها** — forces, claims (confirmed / contested / Houthi-only),
   key points, disagreements, open questions.
5. **گاه‌شمار و منابع** — event timeline, Aden timeline, 25 sources.
6. **آرشیو** — local snapshots (plain JSON files: create / view frozen
   points / delete) + dashboard version history v1–v5.

Plus, on every tab: a full-width **«به‌روزرسانی لحظه‌ای»** button with
the last-refresh time (Jalali), a staleness label, and a Persian error
with retry on failure. Offline-first: the bundled seed renders
immediately on first launch, even with no network.

## Why Flutter (the v5.6 freeze)

The Kotlin app froze the Yemen tab because it did blocking network I/O
(`HttpURLConnection`, up to 3 retries × 2 fetches) and a ~249 KB JSON
parse on the UI thread, behind a mutex shared with a 24 h worker.
This rebuild makes that structurally impossible:

- **One immutable state object, one update path** (`lib/main.dart`).
- **All network is async** (`dart:io` HttpClient, 15 s timeout, one
  attempt) and **all JSON parsing runs in an isolate** (`compute`).
  Nothing is ever awaited on the build path.
- **No database, no workers, no mutex.** Snapshots are plain JSON
  files; the 24 h refresh is a plain `Timer` that only starts a
  background fetch.
- See `~/workspace/goals/eco-journal/files/rebuild-yemen-only.md`
  (Step 1) for the full diagnosis.

## Layout

```
lib/main.dart        app shell, single state object, refresh button
lib/yemen.dart       plain Dart models + isolate parse entry point
lib/repository.dart  seed / cache / live fetch / snapshots (plain JSON files)
lib/jalali.dart      Gregorian -> Jalali (verified vs jdatetime)
lib/map_painter.dart CustomPainter conflict map + legend
lib/sections.dart    the six tabs + snapshot viewer
assets/seed/yemen.json   bundled seed (byte-identical to edge/out/yemen.json)
assets/fonts/        Vazirmatn (SIL OFL)
edge/                Yemen data source: generate_yemen.py, verify_yemen.py,
                     version_manifest.py, out/yemen.json
```

## Version (single source of truth)

`pubspec.yaml` — `version: 6.0.0+13`. `edge/version_manifest.py`
reads it to generate `edge/out/version.json` from the real APK.
Never type the version anywhere else.

## Build (CI is authoritative)

Local `flutter build apk` is unreliable on the 6 GB lab VM (Gradle
daemon wants `-Xmx8G`). GitHub Actions is the build:

```
.github/workflows/android.yml   # push to flutter-yemen-6.0 / dispatch
```

It verifies the Yemen edge contract, runs `flutter analyze` + `flutter
test`, builds the debug APK, checks package `com.ecojournal.app` /
`6.0.0` / code `13`, and enforces the signing fingerprint
`45220b1509e14db5fd9a8e42220547ea94da48bdc4a4ccc5fc2f1bcb9968fd40`
(the shared debug keystore, restored from the
`DEBUG_KEYSTORE_BASE64` secret — the build FAILS if the secret is
absent, never silently signs with an ephemeral key). The same
fingerprint as 5.6, so the app updates over 5.6.

`.github/workflows/pages.yml` publishes `edge/out/` to GitHub Pages
(the live `yemen.json` URL the app fetches).

## Archive

v5.6 (Kotlin) is archived, not deleted from history: git tag
`archive-v5.6-pre-rebuild` + tarball in
`~/workspace/goals/eco-journal/files/`.
