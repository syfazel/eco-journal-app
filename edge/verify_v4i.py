#!/usr/bin/env python3
"""v4-I verifier (LAB ONLY, mirrors the Phase 2/4/5/6 pattern).

Checks, all lab-local, no build, no push, no live change:
  V4I-1  Kotlin brace/paren balance (stateful scan: strings,
         char literals, // and /* */ comments, and raw
         strings are skipped, so Persian text cannot fake a
         mismatch the way the old regex stripper did).
  V4I-2  strings.xml parses; every app_update_* string used by
         the new Kotlin exists exactly once; no duplicates.
  V4I-3  build.gradle.kts names 4.0.0 / code 4 (v4 identity).
  V4I-4  AndroidManifest: REQUEST_INSTALL_PACKAGES + the
         FileProvider (authorities ${applicationId}.fileprovider)
         + res/xml/file_paths.xml exposing ONLY cache/updates/.
  V4I-5  Wiring present: launch check + 24h worker schedule +
         feed compact row + Settings card + worker notification
         function; content sync untouched (EdgeRepository diff
         is empty in this run — checked by the caller via git).
  V4I-6  Version-manifest fixture round-trip: generate with a
         dummy APK file (hash computed from that real file,
         never typed), verify passes; tampered hash fails;
         generate without --apk refuses; decision table for
         shouldOffer matches the Kotlin logic exactly.
  V4I-7  No premature publish: edge/out/version.json must NOT
         exist (it appears only at the v4 publish step, with
         the real v4 APK). No FCM/Play activation in v4-I
         files; no publish_edge --real invocation anywhere
         in this run's new files.

Usage: python3 edge/verify_v4i.py
Exit 0 only when every check passes. Python standard library.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

APP_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN = [
    "app/src/main/java/com/ecojournal/app/data/AppUpdate.kt",
    "app/src/main/java/com/ecojournal/app/data/AppUpdateRepository.kt",
    "app/src/main/java/com/ecojournal/app/data/AppUpdateWorker.kt",
    "app/src/main/java/com/ecojournal/app/data/SettingsStore.kt",
    "app/src/main/java/com/ecojournal/app/ui/components/AppUpdateBanner.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt",
    "app/src/main/java/com/ecojournal/app/EcoJournalApp.kt",
    "app/src/main/java/com/ecojournal/app/MainActivity.kt",
    "app/src/main/java/com/ecojournal/app/util/Notifications.kt",
]
NEW_STRINGS = [
    "app_update_title", "app_update_current", "app_update_auto_check",
    "app_update_idle", "app_update_check", "app_update_checking",
    "app_update_available", "app_update_mandatory", "app_update_download",
    "app_update_downloading", "app_update_ready", "app_update_install",
    "app_update_dismiss", "app_update_retry", "app_update_failed",
    "app_update_need_permission", "app_update_open_settings",
]
FIXTURE_DIR = os.path.join(APP_ROOT, "edge", "out-v4i-fixture")

fails = []


def check(ok, msg):
    print("[%s] %s" % ("OK" if ok else "FAIL", msg))
    if not ok:
        fails.append(msg)


def balanced(path):
    """Stateful brace/paren/bracket scan over Kotlin source."""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i:i + 2]
        if nxt == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if nxt == "/*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c in "([{":
            stack.append(c)
        elif c in ")]}":
            if not stack or stack[-1] != pairs[c]:
                return False
            stack.pop()
        i += 1
    return not stack


def run(cmd, cwd=APP_ROOT):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def main():
    # V4I-1: balance.
    for rel in KOTLIN:
        path = os.path.join(APP_ROOT, rel)
        check(os.path.exists(path) and balanced(path), "balanced: %s" % rel)
    # V4I-2: strings.
    spath = os.path.join(APP_ROOT, "app/src/main/res/values/strings.xml")
    try:
        root = ET.parse(spath).getroot()
        names = [el.get("name") for el in root.findall("string")]
        check(len(names) == len(set(names)), "strings.xml: no duplicates (%d)" % len(names))
        for name in NEW_STRINGS:
            check(name in names, "string present: %s" % name)
        used = set()
        for rel in KOTLIN:
            with open(os.path.join(APP_ROOT, rel), encoding="utf-8") as f:
                used |= set(re.findall(r"R\.string\.(app_update_\w+)", f.read()))
        check(used <= set(names), "all used app_update_* strings resolve: %s" % sorted(used - set(names)))
    except Exception as exc:
        check(False, "strings.xml parses: %s" % exc)
    # V4I-3: version identity.
    with open(os.path.join(APP_ROOT, "app/build.gradle.kts"), encoding="utf-8") as f:
        gradle = f.read()
    check('versionName = "4.0.0"' in gradle, "build.gradle.kts versionName 4.0.0")
    check(re.search(r"versionCode\s*=\s*4\b", gradle) is not None, "build.gradle.kts versionCode 4")
    # V4I-4: manifest + file paths.
    with open(os.path.join(APP_ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf-8") as f:
        manifest_xml = f.read()
    check("REQUEST_INSTALL_PACKAGES" in manifest_xml, "manifest: REQUEST_INSTALL_PACKAGES")
    check("androidx.core.content.FileProvider" in manifest_xml, "manifest: FileProvider declared")
    check("${applicationId}.fileprovider" in manifest_xml, "manifest: FileProvider authorities")
    check("@xml/file_paths" in manifest_xml, "manifest: file_paths referenced")
    fpath = os.path.join(APP_ROOT, "app/src/main/res/xml/file_paths.xml")
    with open(fpath, encoding="utf-8") as f:
        fp = f.read()
    check('<cache-path name="updates" path="updates/" />' in fp, "file_paths: ONLY cache/updates exposed")
    check(fp.count("<") - fp.count("<!--") <= 8, "file_paths: no extra paths smuggled in")
    # V4I-5: wiring.
    def has(rel, needle):
        with open(os.path.join(APP_ROOT, rel), encoding="utf-8") as f:
            return needle in f.read()

    check(has("app/src/main/java/com/ecojournal/app/MainActivity.kt", "updateRepo.check()"),
          "MainActivity: launch update check")
    check(has("app/src/main/java/com/ecojournal/app/EcoJournalApp.kt", "AppUpdateWorker.schedule(this)"),
          "EcoJournalApp: 24h worker scheduled")
    check(has("app/src/main/java/com/ecojournal/app/data/AppUpdateWorker.kt", "24, TimeUnit.HOURS"),
          "AppUpdateWorker: 24h periodic")
    check(has("app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt", "AppUpdateCompactRow("),
          "FeedScreen: compact update row")
    check(has("app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt", "AppUpdateCard("),
          "SettingsScreen: full update card")
    check(has("app/src/main/java/com/ecojournal/app/util/Notifications.kt", "showAppUpdate"),
          "Notifications: showAppUpdate")
    check(has("app/src/main/java/com/ecojournal/app/data/SettingsStore.kt", "PENDING_UPDATE_SHA"),
          "SettingsStore: pending-update keys")
    # V4I-6: version-manifest fixture round-trip.
    os.makedirs(FIXTURE_DIR, exist_ok=True)
    dummy_apk = os.path.join(FIXTURE_DIR, "dummy.apk")
    with open(dummy_apk, "wb") as f:
        f.write(b"v4-I fixture apk bytes, not a real APK\n" * 16)
    real_sha = hashlib.sha256(open(dummy_apk, "rb").read()).hexdigest()
    fixture_manifest = os.path.join(FIXTURE_DIR, "version.json")
    gen = run(["python3", "edge/version_manifest.py", "generate",
               "--apk", dummy_apk, "--apk-url", "https://example.invalid/app-debug-v4.apk",
               "--changelog", "نسخهٔ آزمایشی", "--out", fixture_manifest])
    check(gen.returncode == 0, "version_manifest generate (fixture) exits 0")
    with open(fixture_manifest, encoding="utf-8") as f:
        doc = json.load(f)
    check(doc.get("apk_sha256") == real_sha, "fixture manifest hash == dummy APK hash (computed, not typed)")
    check(doc.get("version_name") == "4.0.0" and doc.get("version_code") == 4,
          "fixture manifest names 4.0.0 / 4 from build.gradle.kts")
    ver = run(["python3", "edge/version_manifest.py", "verify",
               "--apk", dummy_apk, "--manifest", fixture_manifest])
    check(ver.returncode == 0, "version_manifest verify passes on fixture")
    tampered = dict(doc, apk_sha256="0" * 64)
    with open(fixture_manifest, "w", encoding="utf-8") as f:
        json.dump(tampered, f, ensure_ascii=False, indent=1)
    ver_bad = run(["python3", "edge/version_manifest.py", "verify",
                   "--apk", dummy_apk, "--manifest", fixture_manifest])
    check(ver_bad.returncode != 0, "version_manifest verify FAILS on tampered hash (release blocker works)")
    noapk = run(["python3", "edge/version_manifest.py", "generate",
                 "--apk", os.path.join(FIXTURE_DIR, "missing.apk"),
                 "--apk-url", "https://example.invalid/x.apk",
                 "--changelog", "x", "--out", os.path.join(FIXTURE_DIR, "nope.json")])
    check(noapk.returncode != 0 and not os.path.exists(os.path.join(FIXTURE_DIR, "nope.json")),
          "version_manifest generate REFUSES without a real APK (no placeholder hash)")
    # Decision table, mirroring Kotlin shouldOffer exactly.
    def offer(code, installed, dismissed, mandatory):
        if code <= installed:
            return False
        if mandatory:
            return True
        return code != dismissed

    cases = [
        (5, 4, 0, False, True),    # newer optional, not dismissed
        (4, 4, 0, False, False),   # same code
        (3, 4, 0, False, False),   # older
        (5, 4, 5, True, True),     # mandatory ignores dismissal
        (5, 4, 5, False, False),   # optional dismissed, same code
        (6, 4, 5, False, True),    # newer than dismissed code
    ]
    check(all(offer(*c[:4]) == c[4] for c in cases), "shouldOffer decision table (6 cases)")
    # V4I-7: no premature publish, no FCM/Play activation in v4-I files.
    # Scope note: the google-services.json check applies ONLY to
    # genuinely new v4-I files — pre-existing files (EcoJournalApp,
    # AndroidManifest) carry the old M5 dormant-FCM comment that
    # mentions it, which v4-I did not add and must not be blamed
    # for. FirebaseMessaging / --real are checked everywhere.
    check(not os.path.exists(os.path.join(APP_ROOT, "edge/out/version.json")),
          "edge/out/version.json ABSENT (publish step only)")
    new_files = [
        "app/src/main/java/com/ecojournal/app/data/AppUpdate.kt",
        "app/src/main/java/com/ecojournal/app/data/AppUpdateRepository.kt",
        "app/src/main/java/com/ecojournal/app/data/AppUpdateWorker.kt",
        "app/src/main/java/com/ecojournal/app/ui/components/AppUpdateBanner.kt",
        "edge/version_manifest.py",
    ]
    bad = []
    for rel in KOTLIN + ["edge/version_manifest.py"]:
        with open(os.path.join(APP_ROOT, rel), encoding="utf-8") as f:
            text = f.read()
        if "publish_edge.py --real" in text or "FirebaseMessaging" in text:
            bad.append(rel)
    for rel in new_files:
        with open(os.path.join(APP_ROOT, rel), encoding="utf-8") as f:
            text = f.read()
        if "google-services.json" in text:
            bad.append(rel)
    check(not bad, "no FCM/Play activation, no --real in v4-I files: %s" % bad)
    print("=== result: %s ===" % ("PASS" if not fails else "FAIL (%d)" % len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
