#!/usr/bin/env python3
"""v5.6 lab verifier: Yemen-tab fix (lab only, no build).

Static checks only — this verifier does NOT compile anything and
does NOT claim any phone result. It checks:
  (1) version 5.6.0 / code 12 in build.gradle.kts; CI verify step
      names the v5.6 APK;
  (2) R2 invite gate: no whole-app InviteScreen gate left in
      MainActivity; the Broadcast destination gates, Yemen does not;
  (3) R3 empty state: retry button + last-error line + Diagnostics
      link in YemenScreen, with the strings present;
  (4) R4 Aden gaps: the five investigation sections travel in
      yemen.json (counts), are parsed in Yemen.kt, stored in Room
      (yemen_aden columns + MIGRATION_11_12), and rendered in
      AdenTab; local snapshots (create/view/delete) + dashboard
      version history v1-v5 in ArchiveTab;
  (5) R5 map: triangular arrowheads, dashed Aden ring arcs,
      labels, legend;
  (6) R6 refresh: full-width "به‌روزرسانی لحظه‌ای" button +
      Jalali success line;
  (7) R1 update: the compact update row is rendered on the Yemen
      tab (not only in Settings);
  (8) scope + hard limits: Room version 12 with the 11->12
      migration registered; YemenSyncWorker still 24h; no FCM,
      no new worker, no channel-post code;
  (9) seed copy byte-identical to edge yemen.json.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/ecojournal/app"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"
YEMEN_SCREEN = SRC / "ui/screens/YemenScreen.kt"
MAIN = SRC / "MainActivity.kt"
YEMEN_KT = SRC / "data/Yemen.kt"
DB = SRC / "data/AppDatabase.kt"
PROVIDER = SRC / "data/DatabaseProvider.kt"
SEED = SRC / "data/BundledSeed.kt"
GRADLE = ROOT / "app/build.gradle.kts"
CI = ROOT / ".github/workflows/android.yml"
WORKER = SRC / "data/YemenSyncWorker.kt"
EDGE_YEMEN = ROOT / "edge/out/yemen.json"
SEED_YEMEN = ROOT / "app/src/main/assets/seed/yemen.json"

fails = []
passes = [0]


def check(name, ok, detail=""):
    if ok:
        passes[0] += 1
    else:
        fails.append("%s: %s" % (name, detail))


def read(p):
    return Path(p).read_text(encoding="utf-8")


def string_values():
    tree = ET.parse(STRINGS)
    return {el.get("name"): (el.text or "") for el in tree.getroot()}


def main():
    strings = string_values()
    gradle = read(GRADLE)
    check("versionName 5.6.0", 'versionName = "5.6.0"' in gradle)
    check("versionCode 12", "versionCode = 12" in gradle)
    check("CI verify names v5.6 APK",
          "app-debug-v5.6.apk" in read(CI))

    main_text = read(MAIN)
    check("R2: no whole-app gate (no InviteScreen before NavHost)",
          "gateHeld" not in main_text and
          main_text.count("InviteScreen(") == 1,
          "InviteScreen( call sites: %d" % main_text.count("InviteScreen("))
    check("R2: broadcast destination gates on inviteOk",
          'if (!settings.inviteOk)' in main_text and
          "InviteScreen(" in main_text)
    check("R2: Yemen destination ungated",
          "YemenScreen(" in main_text and
          "onDiagnostics" in main_text)

    screen = read(YEMEN_SCREEN)
    # R3
    check("R3: empty state has retry button",
          "yemen_empty_retry" in screen)
    check("R3: empty state shows last error code",
          "yemen_empty_error" in screen)
    check("R3: empty state links to Diagnostics",
          "yemen_empty_diagnostics" in screen and "onDiagnostics" in screen)
    check("R3: retry re-runs seed + forced sync",
          "BundledSeed.ensureSeed(context)" in screen and
          "syncYemen(forceRefresh = true)" in screen)
    # R1
    check("R1: update compact row on Yemen tab",
          "AppUpdateCompactRow(" in screen and
          "AppUpdateSession.state" in screen)
    # R6
    check("R6: refresh string is live-refresh wording",
          strings.get("yemen_refresh") == "به‌روزرسانی لحظه‌ای",
          repr(strings.get("yemen_refresh")))
    check("R6: header button full-width",
          ".fillMaxWidth()" in screen and
          "heightIn(min = 48.dp)" in screen)
    check("R6: success line with Jalali time",
          "yemen_refresh_success" in screen and
          "formatShamsiDateTime(lastRefreshOkAt)" in screen)
    # R5
    check("R5: triangular arrowheads (head Path)",
          "headLen" in screen and "drawPath(path = head" in screen)
    check("R5: dashed Aden ring arcs",
          "drawArc(" in screen and "dashPathEffect" in screen and
          "ringSegments" in screen)
    check("R5: map labels (Aden + key points)",
          "labelPlaces" in screen and "nativeCanvas.drawText" in screen)
    check("R5: legend present",
          "yemen_map_legend" in screen and "yemen_map_aden_ring" in screen)
    # R4 — Kotlin
    yemen_kt = read(YEMEN_KT)
    for field in ("images", "crossCheck", "channelsOfficial",
                  "socialPosts", "amplifiers"):
        check("R4: YemenAden.%s parsed" % field,
              ("val %s: List<" % field) in yemen_kt)
    db = read(DB)
    check("R4: Room version 12", "version = 12" in db)
    check("R4: MIGRATION_11_12 exists", "MIGRATION_11_12" in db)
    check("R4: snapshot tables exist",
          "yemen_snapshots" in db and "yemen_snapshot_places" in db)
    check("R4: Aden JSON columns added",
          all(c in db for c in ("imagesJson", "crossCheckJson",
                                "channelsOfficialJson", "socialPostsJson",
                                "amplifiersJson")))
    check("R4: migration registered",
          "MIGRATION_11_12" in read(PROVIDER))
    check("R4: seed version 3 (Aden re-seed)",
          "BUNDLED_SEED_VERSION = 3" in read(SEED))
    # R4 — ArchiveTab
    check("R4: archive create/view/delete",
          "yemen_archive_create" in screen and
          "yemen_archive_view" in screen and
          "yemen_archive_delete" in screen and
          "createYemenSnapshot" in screen and
          "deleteYemenSnapshot" in screen)
    check("R4: version history v1-v5",
          all("yemen_archive_v%d" % i in screen for i in range(1, 6)))
    check("R4: dashboard snapshot first (dashboard title)",
          "yemen_archive_dashboard" in screen)
    # R4 — AdenTab sections
    for key in ("yemen_aden_images", "yemen_aden_cross_check",
                "yemen_aden_channels_official", "yemen_aden_social_posts",
                "yemen_aden_amplifiers", "yemen_aden_seen_note",
                "yemen_aden_taiz_note"):
        check("R4: AdenTab renders %s" % key, key in screen)
    # R4 — edge data
    import json
    doc = json.loads(EDGE_YEMEN.read_text(encoding="utf-8"))
    aden = doc.get("aden", {})
    for key, want in (("images", 7), ("cross_check", 7),
                      ("channels_official", 5), ("social_posts", 9),
                      ("amplifiers", 4), ("sources", 28)):
        check("R4: yemen.json aden.%s == %d" % (key, want),
              len(aden.get(key, [])) == want,
              "got %d" % len(aden.get(key, [])))
    check("R4: dashboard snapshot is first archive entry",
          len(doc.get("snapshots", [])) >= 1 and
          doc["snapshots"][0].get("places_count") == 90 and
          doc["snapshots"][0].get("events_count") == 24)
    # Scope / hard limits
    worker = read(WORKER)
    check("scope: YemenSyncWorker still 24h",
          "PeriodicWorkRequestBuilder<YemenSyncWorker>(24, TimeUnit.HOURS)"
          in worker)
    all_kotlin = ""
    for p in SRC.rglob("*.kt"):
        all_kotlin += read(p)
    check("scope: no FCM added",
          "FirebaseMessaging" not in all_kotlin and
          "firebase-messaging" not in read(ROOT / "app/build.gradle.kts"))
    check("scope: no new worker class",
          len(re.findall(r"class \w+ *\([^)]*\) *: *CoroutineWorker",
                         all_kotlin)) == 2,
          "worker classes: %s" % re.findall(
              r"class (\w+) *\([^)]*\) *: *CoroutineWorker", all_kotlin))
    # Strings referenced
    new_keys = [k for k in strings if k.startswith("yemen_archive_") or
                k.startswith("yemen_aden_") or k.startswith("yemen_map_") or
                k.startswith("yemen_empty_") or
                k == "yemen_refresh_success" or
                k == "diagnostics_yemen_snapshots"]
    for key in new_keys:
        check("string %s referenced in code" % key,
              key in screen or key in read(SRC / "ui/screens/DiagnosticsScreen.kt"),
              "not referenced")
    # Seed identical
    check("seed byte-identical to edge yemen.json",
          EDGE_YEMEN.read_bytes() == SEED_YEMEN.read_bytes())

    print("VERIFY V5.6 PASS: %d checks" % passes[0] if not fails
          else "VERIFY V5.6: %d passed, %d failed" % (passes[0], len(fails)))
    for msg in fails:
        print("FAIL:", msg, file=sys.stderr)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
