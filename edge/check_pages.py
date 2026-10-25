#!/usr/bin/env python3
"""Phase 3 lab-side Pages-serving checker (spec 3.3).

The app cannot measure Pages deploy time by itself: it can only
report "first manifest I saw with the new hash". This checker
separates the two legs. It polls manifest.json at the given URL
every --interval seconds (default 60, per spec; tighter polling
adds load without changing the conclusion) and records the FIRST
fetch whose section hash equals the committed hash.

pages_first_seen is INTERVAL-CENSORED: the true serving instant
lies in (previous check, first matching check]. The report states
the Pages leg as a WINDOW, never as an exact second.

Timeout: if the new hash is not served within --window seconds
(default 14400 = the spec 5.5 Tier B test window of 4 hours), the
checker stops and the outcome is INCONCLUSIVE/BLOCKED
(pages_not_yet_serving) -- NOT an app failure, and NOT a reason
to republish the same item (spec F4/F6: never blind-republish).

This checker reads the public edge only: no token, no invite
code, no channel access. It never writes to the edge.

Exit codes: 0 = served (window recorded); 2 = window expired
(INCONCLUSIVE/BLOCKED: pages_not_yet_serving when a v3 manifest
is served with the old hash, edge_unreachable when nothing ever
fetched); 1 = bad_manifest (fetches succeeded but no v3 manifest
ever parsed) or bad arguments.

Report JSON (--report): check times, first/last observation,
window, and per-check outcomes (hash match / stale / error).
"""

import argparse
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone


def iso(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat()


def fetch_manifest(url):
    req = urllib.request.Request(url, headers={"User-Agent": "eco-journal-phase3-checker"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest-url", required=True)
    ap.add_argument("--section", required=True)
    ap.add_argument("--expected-hash", required=True)
    ap.add_argument("--interval", type=float, default=60)
    ap.add_argument("--window", type=float, default=14400)
    ap.add_argument("--report", default=None)
    args = ap.parse_args()
    if args.interval <= 0 or args.window <= 0:
        print("REFUSED: --interval and --window must be positive", file=sys.stderr)
        return 1

    start = time.time()
    deadline = start + args.window
    checks = []
    previous = None
    status = "pages_not_yet_serving"
    first_seen = None
    saw_parseable = False
    saw_fetch_success = False
    while True:
        now = time.time()
        if now > deadline and previous is not None:
            break
        try:
            manifest = fetch_manifest(args.manifest_url)
            saw_fetch_success = True
            section = manifest.get("sections", {}).get(args.section, {})
            served = section.get("hash")
            ok = served == args.expected_hash
            saw_parseable = saw_parseable or manifest.get("version") == 3
            checks.append({"at_epoch": int(now), "at_utc": iso(now),
                           "served_hash_present": served is not None,
                           "match": ok})
        except Exception as e:
            checks.append({"at_epoch": int(now), "at_utc": iso(now),
                           "error": type(e).__name__})
            ok = False
        if ok:
            first_seen = now
            status = "served"
            break
        previous = now
        if now >= deadline:
            break
        time.sleep(min(args.interval, max(0.0, deadline - time.time())))

    if status != "served":
        if saw_parseable:
            # A v3 manifest IS served, but with the old hash:
            # genuine Pages delay / stale manifest (F6).
            status = "pages_not_yet_serving"
        elif saw_fetch_success:
            # Fetches succeeded but no v3 manifest ever parsed:
            # the edge itself is inconsistent (F7 family).
            status = "bad_manifest"
        else:
            # Nothing ever fetched: the edge is unreachable
            # (F8), which is not a manifest defect.
            status = "edge_unreachable"
    report = {
        "checker": "edge/check_pages.py (Phase 3, lab-side)",
        "manifest_url": args.manifest_url,
        "section": args.section,
        "expected_hash_present": True,
        "started_epoch": int(start),
        "window_seconds": args.window,
        "interval_seconds": args.interval,
        "status": status,
        "pages_first_seen_epoch": int(first_seen) if first_seen else None,
        "serving_window": (
            {"previous_check_epoch": int(previous),
             "first_matching_check_epoch": int(first_seen)}
            if first_seen is not None else None),
        "checks": checks,
    }
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
    if status == "served":
        print("SERVED between %s and %s (interval-censored window, %d checks)"
              % (iso(previous), iso(first_seen), len(checks)))
        return 0
    if status == "bad_manifest":
        print("BAD_MANIFEST: no parseable v3 manifest observed in the window",
              file=sys.stderr)
        return 1
    if status == "edge_unreachable":
        print("INCONCLUSIVE/BLOCKED: edge unreachable in the window "
              "(edge_unreachable; do NOT republish to fix this)",
              file=sys.stderr)
        return 2
    print("INCONCLUSIVE/BLOCKED: hash not served within %.0fs "
          "(pages_not_yet_serving; do NOT republish to fix this)" % args.window,
          file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
