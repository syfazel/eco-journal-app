#!/usr/bin/env python3
"""ECO JOURNAL app version manifest — v4-I remote update (LAB ONLY).

The version manifest is the edge file the app checks for a NEWER
APP BINARY (see app/.../data/AppUpdate.kt). It is NOT content:
content updates need no APK (manifest-v3 delta sync). This file
is only about updating the binary itself.

Manifest shape (served at the Pages root as version.json, next
to manifest.json — pages.yml publishes edge/out/ as the root):
  version_name:  "4.0.0"      (from app/build.gradle.kts, never typed)
  version_code:  4            (from app/build.gradle.kts, never typed)
  apk_url:       "https://…"  (HTTPS only; the APK download URL)
  apk_sha256:    64 lowercase hex, computed from the REAL APK file
  changelog_fa:  Persian changelog text (may be "")
  mandatory:     true/false  (mandatory = no dismiss button)
  generated_at_utc: ISO-8601 (informational)

RELEASE BLOCKER, enforced here, not by convention:
  * generate REQUIRES --apk (a real file). There is no
    placeholder, example, or "fill in later" hash: without a
    real APK the script refuses and writes nothing.
  * generate READS version_name/version_code from
    app/build.gradle.kts (single source of truth) — the values
    are never typed on the command line, so a typo cannot ship
    a manifest the app can never match.
  * verify FAILS (exit 1) unless: manifest name/code equal
    build.gradle.kts, apk_sha256 equals the SHA-256 of the
    given APK file, apk_url is HTTPS, and (when aapt is
    available) aapt badging agrees on package/versionName/
    versionCode. A failed verify blocks the publish step.
  * Until the v4 publish step runs this with the REAL v4 APK,
    version.json must NOT exist in edge/out: the app treats a
    404 as "no update info", never as an error banner.

HONEST ANDROID LIMIT (also in AppUpdate.kt): a sideloaded app
cannot silently self-install; the manifest only enables
check + download + one-tap system install. No flag here can
change that, and no flag here enables FCM or Play.

Usage:
  python3 edge/version_manifest.py generate --apk FILE --apk-url URL
      --changelog TEXT [--mandatory] [--out FILE]
  python3 edge/version_manifest.py verify --apk FILE
      [--manifest FILE] [--expect-name NAME] [--expect-code N]
Default --out/--manifest is edge/out/version.json. --out must
resolve inside ~/workspace/goals/eco-journal/app/.

Python standard library only. No network access.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MANIFEST = os.path.join(SCRIPT_DIR, "out", "version.json")
BUILD_GRADLE = os.path.join(APP_ROOT, "app", "build.gradle.kts")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
AAPT_CANDIDATES = [
    os.path.join(APP_ROOT, ".tools", "android-sdk", "build-tools", "34.0.0", "aapt"),
    os.path.join(APP_ROOT, ".tools", "android-sdk", "build-tools", "34.0.0", "aapt2"),
]


def app_version():
    """Read (versionName, versionCode) from build.gradle.kts."""
    with open(BUILD_GRADLE, encoding="utf-8") as f:
        text = f.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        raise SystemExit("REFUSED: cannot parse version from %s" % BUILD_GRADLE)
    return name.group(1), int(code.group(1))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def aapt_info(apk):
    """aapt badging identity, or None when aapt is unavailable."""
    for candidate in AAPT_CANDIDATES:
        if not os.path.exists(candidate):
            continue
        try:
            out = subprocess.run(
                [candidate, "dump", "badging", apk],
                capture_output=True, text=True, timeout=60,
            ).stdout
        except Exception:
            continue
        pkg = re.search(r"package: name='([^']+)'", out)
        code = re.search(r"versionCode='(\d+)'", out)
        name = re.search(r"versionName='([^']*)'", out)
        if pkg and code and name:
            return pkg.group(1), name.group(1), int(code.group(1))
    return None


def guard_out(path):
    out = os.path.abspath(path)
    if os.path.commonpath([out, os.path.abspath(APP_ROOT)]) != os.path.abspath(APP_ROOT):
        print("REFUSED: --out/--manifest must be inside %s" % APP_ROOT, file=sys.stderr)
        return None
    return out


def cmd_generate(args):
    if not os.path.exists(args.apk):
        print("REFUSED: --apk file does not exist: %s" % args.apk, file=sys.stderr)
        return 2
    if not args.apk_url.startswith("https://"):
        print("REFUSED: --apk-url must be HTTPS: %s" % args.apk_url, file=sys.stderr)
        return 2
    if args.changelog is None:
        print("REFUSED: --changelog is required (empty string allowed)", file=sys.stderr)
        return 2
    out = guard_out(args.out or DEFAULT_MANIFEST)
    if out is None:
        return 2
    name, code = app_version()
    manifest = {
        "version_name": name,
        "version_code": code,
        "apk_url": args.apk_url,
        "apk_sha256": sha256_of(args.apk),
        "changelog_fa": args.changelog,
        "mandatory": bool(args.mandatory),
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(json.dumps(manifest, ensure_ascii=False, indent=1))
    print("wrote:", out)
    return 0


def cmd_verify(args):
    manifest_path = guard_out(args.manifest or DEFAULT_MANIFEST)
    if manifest_path is None:
        return 2
    if not os.path.exists(manifest_path):
        print("FAIL: manifest does not exist: %s" % manifest_path, file=sys.stderr)
        return 1
    if not os.path.exists(args.apk):
        print("FAIL: --apk file does not exist: %s" % args.apk, file=sys.stderr)
        return 1
    fails = []
    try:
        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)
    except Exception as exc:
        print("FAIL: manifest unparsable: %s" % exc, file=sys.stderr)
        return 1
    gradle_name, gradle_code = app_version()
    expect_name = args.expect_name or gradle_name
    expect_code = args.expect_code if args.expect_code is not None else gradle_code
    if manifest.get("version_name") != expect_name:
        fails.append("version_name %r != expected %r" % (manifest.get("version_name"), expect_name))
    if manifest.get("version_code") != expect_code:
        fails.append("version_code %r != expected %r" % (manifest.get("version_code"), expect_code))
    if manifest.get("version_name") != gradle_name or manifest.get("version_code") != gradle_code:
        fails.append("manifest disagrees with build.gradle.kts (%s/%s)" % (gradle_name, gradle_code))
    if not isinstance(manifest.get("apk_url"), str) or not manifest["apk_url"].startswith("https://"):
        fails.append("apk_url is not HTTPS: %r" % manifest.get("apk_url"))
    sha = manifest.get("apk_sha256")
    if not isinstance(sha, str) or not HEX64.match(sha):
        fails.append("apk_sha256 is not 64 lowercase hex")
    elif sha != sha256_of(args.apk):
        fails.append("apk_sha256 != SHA-256 of the given APK file")
    if not isinstance(manifest.get("changelog_fa"), str):
        fails.append("changelog_fa is not a string")
    if not isinstance(manifest.get("mandatory"), bool):
        fails.append("mandatory is not a boolean")
    info = aapt_info(args.apk)
    if info is None:
        print("WARN: aapt unavailable; identity check skipped (hash check still enforced)")
    else:
        pkg, aname, acode = info
        if pkg != "com.ecojournal.app":
            fails.append("aapt package %r != com.ecojournal.app" % pkg)
        if aname != expect_name:
            fails.append("aapt versionName %r != expected %r" % (aname, expect_name))
        if acode != expect_code:
            fails.append("aapt versionCode %r != expected %r" % (acode, expect_code))
    if fails:
        for msg in fails:
            print("FAIL:", msg, file=sys.stderr)
        return 1
    print("VERIFY PASS: %s matches %s (%s/%s, hash OK%s)" % (
        manifest_path, args.apk, expect_name, expect_code,
        ", aapt identity OK" if info is not None else ", aapt skipped"))
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    gen = sub.add_parser("generate")
    gen.add_argument("--apk", required=True)
    gen.add_argument("--apk-url", required=True)
    gen.add_argument("--changelog", required=True)
    gen.add_argument("--mandatory", action="store_true")
    gen.add_argument("--out", default=None)
    ver = sub.add_parser("verify")
    ver.add_argument("--apk", required=True)
    ver.add_argument("--manifest", default=None)
    ver.add_argument("--expect-name", default=None)
    ver.add_argument("--expect-code", type=int, default=None)
    args = ap.parse_args()
    if args.cmd == "generate":
        return cmd_generate(args)
    return cmd_verify(args)


if __name__ == "__main__":
    sys.exit(main())
