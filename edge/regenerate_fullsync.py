#!/usr/bin/env python3
"""S2 — scheduled full-sync regeneration (data plane only).

Pipeline (each run, in order):
  1. Snapshot canonical edge/out to a /tmp backup (rollback on failure).
  2. Run edge/generate_edge.py into edge/out-fullsync-tmp (a temp dir
     INSIDE the app root, as generate_edge.py requires). This
     regenerates the Phase 1 core from live sources, read-only:
     articles, shorts, market, channel_refs, index, join_keys.
     Invites/revocations are copied verbatim from canonical out by
     generate_edge itself; they are re-restored from backup below
     as a belt-and-braces step, so a temp run can never empty them.
  3. Copy the core into canonical edge/out (articles dir replaced
     wholesale, so stale article files cannot linger).
  4. Run generate_channel_typed.py (S1) + generate_radar.py (S3)
     into canonical out. Both re-apply the published-only filter
     from scratch every run, so a night run is leak-safe by
     construction (queue rows are never read).
  5. Nightly stays HELD: this script never writes nightly.json and
     never changes the nightly section (U4 gate, see
     fullsync-nightly-verdict.json). Bundles/voices/special_issues
     are likewise untouched (gated/frozen, out of scope).
  6. Gate: edge/publish_edge.py --verify-only on canonical out.
     On FAIL the /tmp backup is restored and the script exits 1
     having changed nothing.
  7. Report: edge/fullsync-regenerate-report.json with per-section
     before/after counts + hashes and a `changed` flag.

What this script NEVER does:
  * It never runs publish_edge.py --real: that mode regenerates a
    Phase 1 edge and would RESET channel_typed/radar to
    not_yet_published, silently wiping S1/S3. --verify-only is the
    only publish_edge mode valid for a full-sync edge.
  * It never posts to the channel, never touches a post store for
    writing, never creates a cron, never changes a content gate.
  * By default it never commits or pushes. Pass --auto-publish to
    let the SCHEDULED run commit edge/out and push origin main
    (normal push only, never force) when -- and only when -- the
    report says something changed. Without --auto-publish the
    caller (parent/cron wrapper) owns the commit+push step.

Usage:
  python3 edge/regenerate_fullsync.py [--out DIR] [--auto-publish]
Exit 0 = regenerated + verified (changed or not). Exit 1 = verify
failed, canonical out restored from backup.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fullsync_lib as lib


def section_summary(out_dir):
    try:
        manifest = lib.load_json(os.path.join(out_dir, "manifest.json"))
    except Exception:
        return {}
    out = {}
    for name, sec in manifest.get("sections", {}).items():
        out[name] = {"count": sec.get("count"),
                     "status": sec.get("status"),
                     "hash": (sec.get("hash") or "")[:12],
                     "file": sec.get("file")}
    return out


def run(cmd, cwd):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=lib.OUT_DIR)
    ap.add_argument("--auto-publish", action="store_true")
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)
    tmp_dir = os.path.join(lib.EDGE_DIR, "out-fullsync-tmp")
    backup = tempfile.mkdtemp(prefix="fullsync-backup-")
    try:
        shutil.copytree(out_dir, os.path.join(backup, "out"))
        before = section_summary(out_dir)
        backup_manifest = lib.load_json(
            os.path.join(backup, "out", "manifest.json"))

        if os.path.exists(tmp_dir):
            shutil.rmtree(tmp_dir)
        r = run([sys.executable,
                 os.path.join(lib.EDGE_DIR, "generate_edge.py"),
                 "--out", tmp_dir], cwd=lib.APP_ROOT)
        if r.returncode != 0:
            print(r.stdout[-2000:])
            print(r.stderr[-2000:], file=sys.stderr)
            raise SystemExit("generate_edge.py failed; canonical out untouched")

        # Core copy (Phase 1 output -> canonical out).
        art_dst = os.path.join(out_dir, "articles")
        shutil.rmtree(art_dst, ignore_errors=True)
        shutil.copytree(os.path.join(tmp_dir, "articles"), art_dst)
        for fname in ("short_cards.json", "market.json",
                      "channel_refs.json", "index.json",
                      "GENERATION_REPORT.json", "join_keys.json"):
            shutil.copyfile(os.path.join(tmp_dir, fname),
                            os.path.join(out_dir, fname))
        # Invites/revocations: restore verbatim from backup (never
        # let a temp run narrow them), including manifest sections.
        for fname in ("invite_hashes.json", "revocations.json"):
            shutil.copyfile(os.path.join(backup, "out", fname),
                            os.path.join(out_dir, fname))
        manifest = lib.load_json(os.path.join(tmp_dir, "manifest.json"))
        for name in ("invites", "revocations"):
            manifest["sections"][name] = \
                backup_manifest["sections"][name]
        lib.write_json(os.path.join(out_dir, "manifest.json"), manifest)
        # S1 + S3 overlays (re-apply the published-only filter).
        for script in ("generate_channel_typed.py", "generate_radar.py"):
            r = run([sys.executable, os.path.join(lib.EDGE_DIR, script),
                     "--out", out_dir], cwd=lib.APP_ROOT)
            if r.returncode != 0:
                print(r.stdout[-2000:])
                print(r.stderr[-2000:], file=sys.stderr)
                raise SystemExit("%s failed; restoring backup" % script)
        # Amend the generation report with full-sync counts (the
        # core keys it carries are left exactly as generate_edge
        # wrote them, so publish_edge's count check still passes).
        try:
            report = lib.load_json(
                os.path.join(out_dir, "GENERATION_REPORT.json"))
            report["channel_typed"] = section_summary(out_dir).get(
                "channel_typed", {}).get("count")
            report["radar"] = section_summary(out_dir).get(
                "radar", {}).get("count")
            report["fullsync"] = ("S1+S3 overlays applied; nightly "
                                  "held (U4); see fullsync-*-report.json")
            lib.write_json(
                os.path.join(out_dir, "GENERATION_REPORT.json"), report)
        except Exception as exc:  # report amendment is non-fatal
            print("GENERATION_REPORT amend skipped: %s" % exc)

        # Gate: verify-only. On failure, restore and exit 1.
        r = run([sys.executable,
                 os.path.join(lib.EDGE_DIR, "publish_edge.py"),
                 "--verify-only", "--out", out_dir], cwd=lib.APP_ROOT)
        if r.returncode != 0 or "result: PASS" not in r.stdout:
            print(r.stdout[-3000:])
            print(r.stderr[-2000:], file=sys.stderr)
            shutil.rmtree(out_dir)
            shutil.copytree(os.path.join(backup, "out"), out_dir)
            print("VERIFY FAILED — canonical out restored from backup")
            return 1

        after = section_summary(out_dir)
        changed = before != after
        full_report = {
            "generator": "regenerate_fullsync.py (S2)",
            "generated_at_utc": lib.now_utc_iso(),
            "changed": changed,
            "before": before,
            "after": after,
            "nightly": "held (U4 gate — section untouched)",
            "gates_untouched": ["bundles", "voices", "special_issues"],
            "verify": "publish_edge --verify-only PASS",
        }
        lib.write_json(os.path.join(lib.EDGE_DIR,
                                     "fullsync-regenerate-report.json"),
                         full_report)
        print(json.dumps(full_report, ensure_ascii=False, indent=1))
        if changed and args.auto_publish:
            r = run(["git", "add", "edge/out"], cwd=lib.APP_ROOT)
            r = run(["git", "commit", "-m",
                     "edge: full-sync regeneration (S1+S3 overlays)"],
                    cwd=lib.APP_ROOT)
            if r.returncode != 0:
                print("commit skipped/failed (nothing to commit?):")
                print(r.stdout[-1000:])
                return 0
            r = run(["git", "push", "origin", "main"], cwd=lib.APP_ROOT)
            print(r.stdout[-1000:])
            print(r.stderr[-1000:], file=sys.stderr)
            if r.returncode != 0:
                print("PUSH FAILED — commit exists locally; push by hand")
                return 1
            print("auto-published: commit + normal push (never force)")
        elif changed:
            print("CHANGED — publish with: cd %s && git add edge/out && "
                  "git commit -m \"edge: full-sync regeneration\" && "
                  "git push origin main" % lib.APP_ROOT)
        else:
            print("UNCHANGED — no commit, no push (idempotent run)")
        return 0
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        shutil.rmtree(backup, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
