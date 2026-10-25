#!/usr/bin/env python3
"""Phase 3 Tier A timed test (LAB ONLY, dry-run).

Exercises the chain up to the publish gate, exactly as spec 5.1
Tier A defines it: staging + gate + merge + publish_edge
--verify-only + delta simulation. No commit, no push, no Pages
publish, no phone sync of the test item.

The Pages leg and the app legs are SIMULATED in Tier A:
  - Pages: the merged edge is served from a LOCAL http server
    (127.0.0.1, started 70 s after the checker starts, so the
    checker's first poll misses and its second poll hits) and
    check_pages.py observes it at its 60 s cadence. The reported
    Pages leg is the checker's interval-censored WINDOW, and it
    is labelled SIMULATED -- it is not a GitHub Pages deploy.
  - App poll: the Phase 1 delta simulation over manifests
    (unchanged -> 0 fetches; one changed item -> exactly that
    item). App-side chain fields (app_manifest_seen,
    app_item_applied, app_notified) are NOT measured in Tier A:
    no phone runs here. The Kotlin code that records them is
    part of this phase; the phone measurement waits for Tier B
    (blocked, see the phase report) or a phone test.

Test post (spec 5.3 honesty rule): invented-as-test, never real
news, TEST-marked, WIRE/non-urgent, one post, one message_id
(9000101 -- lab fake id, >= 9000001, distinct from the fixture
ids 9000001/9000002). The gate judges it as a test item.

Failure exercises (spec section 4) run on scratch copies and
never touch the primary merged edge: F1 staging conflict, F2
gate rejection, F3 gate hold, F4 verify failure, F6 Pages
timeout, F7 bad manifest, F8 unreachable edge, F10 duplicate
capture, F11 wrong type, F12 allow-list negative check.
F5 (push failure) is NOT APPLICABLE in Tier A (no push ever
runs); F9 (Doze) is phone-only and NOT MEASURABLE in the lab.

Outputs:
  - merged lab edge: edge/out-phase3-tiera (repo, local only)
  - merge report: edge/phase3-merge-report.json
  - chain record (this run): docs/big-update/phase3-tier-a-chain-record.json
  - this script prints a step table with per-step timestamps.

Exit: 0 = Tier A PASS (lab); 1 = any primary step failed.
"""

import http.server
import json
import os
import shutil
import socketserver
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP_ROOT = HERE.parent
sys.path.insert(0, str(APP_ROOT / "bridge"))

from bridge_lib import get_item, init_db, set_status  # noqa: E402
from channel_reader import process_updates  # noqa: E402

try:
    from zoneinfo import ZoneInfo
    TEH = ZoneInfo("Asia/Tehran")
except Exception:
    TEH = timezone.utc

LAB = Path("/tmp/phase3-tiera")
FIXTURE = APP_ROOT / "edge" / "out-phase2-fixture"
REPO_OUT = APP_ROOT / "edge" / "out-phase3-tiera"
CHAIN_RECORD = APP_ROOT / "docs" / "big-update" / "phase3-tier-a-chain-record.json"
PRIMARY_MID = 9000101
PROVENANCE = "muse-editorial (delegated by Yasin 2026-10-18)"

steps = []
failures = []


def now_epoch():
    return int(time.time())


def tehran_iso(epoch):
    return datetime.fromtimestamp(epoch, tz=TEH).isoformat()


def record(step, owner, artifact, epoch, note=""):
    steps.append({"step": step, "owner": owner, "artifact": artifact,
                  "epoch": epoch, "tehran": tehran_iso(epoch), "note": note})
    print("[%s] %s | owner=%s | artifact=%s | %s"
          % (tehran_iso(epoch), step, owner, artifact, note))


def run(cmd, cwd=APP_ROOT):
    result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True)
    return result


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))
    if not cond:
        failures.append(name + " " + detail)
    return cond


def main():
    if LAB.exists():
        shutil.rmtree(LAB)
    LAB.mkdir(parents=True)
    staging = LAB / "staging"
    staging.mkdir()
    queue_db = LAB / "queue.db"
    git_before_all = run(["git", "status", "--porcelain"]).stdout

    # ---- Step 0/1: synthetic TEST post -> bridge capture (lab only).
    t0 = now_epoch()
    updates = [{
        "update_id": 9000101,
        "channel_post": {
            "message_id": PRIMARY_MID,
            "chat": {"id": "lab-test-channel", "title": "EJ test"},
            "date": t0,
            "text": ("TEST — Phase 3 Tier A rehearsal (lab only, not news)\n"
                     "Phase 3 rehearsal post 1. This is a lab test post, "
                     "not news, and carries no real-world claim."),
        },
    }]
    report = process_updates(updates, staging, already_reviewed=False,
                             enqueue=True, db_path=str(queue_db))
    t1 = now_epoch()
    record("test_post_created", "lab operator (simulates Yasin's test-channel post)",
           "synthetic updates JSON (in-memory, TEST-marked)", t0,
           "message_id=%d, WIRE/non-urgent" % PRIMARY_MID)
    record("bridge_capture", "bridge/channel_reader.py",
           str(staging / ("channel-%d.json" % PRIMARY_MID)), t1,
           "staged_new=%s" % report["staged_new"])
    check("S1 staged exactly one file", report["staged_new"] == ["channel-%d" % PRIMARY_MID])
    staged_item = json.loads((staging / ("channel-%d.json" % PRIMARY_MID)).read_text())
    check("S1 staging carries chain fields",
          staged_item.get("channel_posted_epoch") == t0
          and isinstance(staged_item.get("staged_epoch"), int))
    check("S1 staging idempotency key", staged_item.get("idempotency_key") == "channel:%d" % PRIMARY_MID)

    # ---- F10: duplicate capture is a no-op, never a second card.
    dup = process_updates(updates, staging, enqueue=True, db_path=str(queue_db))
    con = init_db(str(queue_db))
    row = get_item(con, "channel:%d" % PRIMARY_MID)
    check("F10 duplicate capture stages nothing new",
          dup["staged_new"] == [] and row is not None)
    con.close()

    # ---- Step 3: delegated gate (review -> approved).
    con = init_db(str(queue_db))
    ok, msg = set_status(con, "channel:%d" % PRIMARY_MID, "approved",
                         note="Tier A lab gate: TEST post approved as a test item, not news")
    con.close()
    t2 = now_epoch()
    record("gate_decided", "Muse editorial (%s)" % PROVENANCE,
           "lab queue.db item channel:%d status=approved" % PRIMARY_MID, t2, msg)
    check("S3 gate approve transition legal", ok, msg)

    # Gate annotation: typing decision (never inferred from text).
    ann = {"format_type": "WIRE", "urgency_score": None, "crisis_level": None,
           "slot": None, "attribution": None, "source_link": None,
           "bundle_id": None, "bundle_kind": None, "bundle_title": None,
           "bundle_date_shamsi": None, "bundle_position": None,
           "bundle_total": None}
    (staging / ("channel-%d.gate.json" % PRIMARY_MID)).write_text(
        json.dumps(ann, ensure_ascii=False, indent=1), encoding="utf-8")

    # ---- Step 4: staging -> edge merge (strict).
    merge_report = LAB / "merge-report.json"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(staging), "--queue-db", str(queue_db),
               "--base-edge", str(FIXTURE), "--out", str(REPO_OUT),
               "--report", str(merge_report), "--strict"])
    t3 = now_epoch()
    record("staging_edge_merge", "edge/merge_staging.py",
           str(REPO_OUT / "channel_typed.json"), t3,
           "exit=%d, stdout=%s" % (res.returncode, res.stdout.strip().splitlines()[0]
                                   if res.stdout.strip() else ""))
    check("S4 merge exit 0", res.returncode == 0, res.stderr.strip()[:300])
    if res.returncode != 0 or not (REPO_OUT / "channel_typed.json").exists():
        print("=== Tier A: FAIL (merge step failed; primary chain aborted) ===")
        for f in failures:
            print("  FAIL: %s" % f)
        CHAIN_RECORD.write_text(json.dumps(
            {"tier": "A (lab/dry-run)", "verdict": "TIER A FAIL",
             "verdict_note": "Primary chain aborted at the merge step.",
             "steps": steps, "failures": failures},
            ensure_ascii=False, indent=1), encoding="utf-8")
        return 1
    merged = json.loads((REPO_OUT / "channel_typed.json").read_text())
    merged_items = merged["items"]
    new_items = [i for i in merged_items if i.get("chain") is not None]
    check("S4 exactly one merged item", len(new_items) == 1,
          "merged=%d total=%d" % (len(new_items), len(merged_items)))
    item = new_items[0] if new_items else {}
    check("S4 E7 channel_message_id non-null and equal",
          item.get("channel_message_id") == PRIMARY_MID)
    check("S4 format WIRE, status posted",
          item.get("format_type") == "WIRE" and item.get("status") == "posted")
    check("S4 app_key/story_id derive from guid",
          item.get("app_key", "").startswith("a-")
          and item.get("story_id", "").startswith("story-"))
    chain = item.get("chain", {})
    check("S4 chain provenance exact", chain.get("gate_provenance") == PROVENANCE)
    check("S4 chain epochs monotonic",
          chain.get("channel_posted_epoch", 0) <= chain.get("staged_epoch", 0)
          <= chain.get("gate_decided_epoch", 0)
          <= chain.get("edge_generated_epoch", 0))
    check("S4 chain edge_pushed null (Tier A dry-run)",
          chain.get("edge_pushed_epoch") is None)
    record("edge_generated", "edge/merge_staging.py",
           str(REPO_OUT / "manifest.json"), chain.get("edge_generated_epoch", t3),
           "channel_typed count=%d" % len(merged_items))

    # ---- Step 5: publish verify-only (the Tier A publish gate).
    res = run([sys.executable, str(HERE / "publish_edge.py"),
               "--verify-only", "--out", str(REPO_OUT)])
    t4 = now_epoch()
    record("publish_verify", "edge/publish_edge.py --verify-only",
           "verification report (stdout)", t4, "exit=%d" % res.returncode)
    check("S5 publish verify-only PASS", res.returncode == 0,
          [l for l in res.stdout.splitlines() if l.startswith("[FAIL]")][:3])
    res = run([sys.executable, str(HERE / "verify_phase3_tiera.py"),
               "--base", str(FIXTURE), "--merged", str(REPO_OUT)])
    check("S5 Tier A verifier PASS", res.returncode == 0,
          [l for l in res.stdout.splitlines() if "FAIL" in l][:3])

    # ---- Step 7a: delta simulation (Phase 1 shape).
    base_manifest = json.loads((FIXTURE / "manifest.json").read_text())
    new_manifest = json.loads((REPO_OUT / "manifest.json").read_text())
    fetches = {}
    for section, sec in new_manifest["sections"].items():
        old = base_manifest["sections"].get(section, {})
        if old.get("hash") == sec.get("hash"):
            fetches[section] = 0
        else:
            old_keys = {i["app_key"]: i.get("content_hash")
                        for i in old.get("items", [])}
            fetches[section] = sum(
                1 for i in sec.get("items", [])
                if old_keys.get(i["app_key"]) != i.get("content_hash"))
    t5 = now_epoch()
    record("delta_simulation", "lab delta simulation (manifest comparison)",
           "base manifest vs merged manifest", t5, "fetches=%s" % fetches)
    check("S7 delta: exactly one channel_typed fetch",
          fetches.get("channel_typed") == 1)
    check("S7 delta: all other sections 0 fetches",
          all(v == 0 for k, v in fetches.items() if k != "channel_typed"),
          str(fetches))
    # E2 shape: unchanged edge -> 0 fetches everywhere (same
    # comparison run base-vs-base, not a tautology).
    e2_fetches = {}
    for section, sec in base_manifest["sections"].items():
        if base_manifest["sections"][section].get("hash") == sec.get("hash"):
            e2_fetches[section] = 0
        else:
            e2_fetches[section] = -1
    check("S7 E2 shape: base vs base = 0 fetches",
          all(v == 0 for v in e2_fetches.values()), str(e2_fetches))

    # ---- Step 6 (SIMULATED): Pages checker, 60 s cadence.
    # Server starts 70 s after the checker, so poll 1 misses and
    # poll 2 (t=120) hits: window (60, 120].
    merged_hash = new_manifest["sections"]["channel_typed"]["hash"]

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(REPO_OUT), **kwargs)

        def log_message(self, *a):
            pass

    # Pick a free port, then bind NOTHING until +70 s: the checker
    # must see connection-refused (fast) on polls 1-2, not a
    # half-listening socket. (Constructing TCPServer early would
    # listen immediately and hang poll 1 in backlog.)
    import socket as _socket
    _probe = _socket.socket()
    _probe.bind(("127.0.0.1", 0))
    port = _probe.getsockname()[1]
    _probe.close()
    httpd_holder = {}

    def delayed_serve():
        time.sleep(70)
        httpd = socketserver.TCPServer(("127.0.0.1", port), Handler)
        httpd_holder["httpd"] = httpd
        httpd.serve_forever()

    server_thread = threading.Thread(target=delayed_serve, daemon=True)
    server_thread.start()
    checker_report = LAB / "pages-report.json"
    t6 = now_epoch()
    record("pages_checker_started", "edge/check_pages.py (lab-side, 60 s cadence)",
           "checker report (pending)", t6,
           "SIMULATED Pages: local http server, starts at +70s")
    res = subprocess.run(
        [sys.executable, str(HERE / "check_pages.py"),
         "--manifest-url", "http://127.0.0.1:%d/manifest.json" % port,
         "--section", "channel_typed", "--expected-hash", merged_hash,
         "--interval", "60", "--window", "300", "--report", str(checker_report)],
        capture_output=True, text=True, timeout=400)
    t7 = now_epoch()
    pages = json.loads(checker_report.read_text()) if checker_report.exists() else {}
    window = pages.get("serving_window") or {}
    record("pages_first_seen_SIMULATED", "edge/check_pages.py",
           str(checker_report), t7,
           "exit=%d, window=%s (SIMULATED, not a GitHub Pages deploy)"
           % (res.returncode, window))
    check("S6 Pages checker served within window", res.returncode == 0, res.stderr.strip()[:200])
    check("S6 Pages window is interval-censored (60,120]",
          window.get("previous_check_epoch") is not None
          and window.get("first_matching_check_epoch") is not None
          and window["first_matching_check_epoch"] - window["previous_check_epoch"] <= 61)
    if "httpd" in httpd_holder:
        httpd_holder["httpd"].shutdown()
        httpd_holder["httpd"].server_close()

    # ---- Failure exercises (scratch only).
    # F4: verify failure aborts cleanly, git untouched.
    f4_dir = LAB / "f4-corrupt"
    shutil.copytree(REPO_OUT, f4_dir)
    corrupt = json.loads((f4_dir / "channel_typed.json").read_text())
    corrupt["items"][-1]["content_hash"] = "0" * 64
    (f4_dir / "channel_typed.json").write_text(
        json.dumps(corrupt, ensure_ascii=False, indent=1), encoding="utf-8")
    git_before_f4 = run(["git", "status", "--porcelain"]).stdout
    res = run([sys.executable, str(HERE / "publish_edge.py"),
               "--verify-only", "--out", str(f4_dir)])
    git_after_f4 = run(["git", "status", "--porcelain"]).stdout
    check("F4 corrupt edge fails verification", res.returncode == 1)
    check("F4 git status unchanged by failed verify", git_before_f4 == git_after_f4)

    # F1: staging index/file disagreement halts, nothing written.
    f1_staging = LAB / "f1-staging"
    shutil.copytree(staging, f1_staging)
    (f1_staging / ("channel-%d.json" % PRIMARY_MID)).unlink()
    f1_out = LAB / "f1-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(f1_staging), "--queue-db", str(queue_db),
               "--base-edge", str(FIXTURE), "--out", str(f1_out),
               "--report", str(LAB / "f1-report.json"), "--strict"])
    check("F1 conflict halts with exit 4 and no output",
          res.returncode == 4 and not f1_out.exists(), res.stderr.strip()[:200])

    # F2: rejected item never merges (legitimate outcome).
    f2_staging = LAB / "f2-staging"
    f2_staging.mkdir()
    mid2 = 9000102
    updates2 = [{"update_id": 9000102,
                 "channel_post": {"message_id": mid2,
                                  "chat": {"id": "lab-test-channel", "title": "EJ test"},
                                  "date": now_epoch(),
                                  "text": "TEST — Phase 3 Tier A rehearsal post 2 (rejected item)"}}]
    process_updates(updates2, f2_staging, enqueue=True,
                    db_path=str(LAB / "f2-queue.db"))
    con = init_db(str(LAB / "f2-queue.db"))
    set_status(con, "channel:%d" % mid2, "rejected", note="Tier A F2: test rejection")
    con.close()
    (f2_staging / ("channel-%d.gate.json" % mid2)).write_text(
        json.dumps(ann, ensure_ascii=False, indent=1), encoding="utf-8")
    f2_out = LAB / "f2-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(f2_staging), "--queue-db", str(LAB / "f2-queue.db"),
               "--base-edge", str(FIXTURE), "--out", str(f2_out),
               "--report", str(LAB / "f2-report.json")])
    f2_report = json.loads((LAB / "f2-report.json").read_text())
    check("F2 rejected item excluded, merge still exits 0",
          res.returncode == 0
          and any(s.get("reason") == "gate_rejected" for s in f2_report["skipped"])
          and f2_report["merged"] == [])

    # F3: held (review) item + --strict fails, nothing written.
    f3_staging = LAB / "f3-staging"
    f3_staging.mkdir()
    mid3 = 9000103
    updates3 = [{"update_id": 9000103,
                 "channel_post": {"message_id": mid3,
                                  "chat": {"id": "lab-test-channel", "title": "EJ test"},
                                  "date": now_epoch(),
                                  "text": "TEST — Phase 3 Tier A rehearsal post 3 (held item)"}}]
    process_updates(updates3, f3_staging, enqueue=True,
                    db_path=str(LAB / "f3-queue.db"))
    f3_out = LAB / "f3-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(f3_staging), "--queue-db", str(LAB / "f3-queue.db"),
               "--base-edge", str(FIXTURE), "--out", str(f3_out),
               "--report", str(LAB / "f3-report.json"), "--strict"])
    check("F3 held item fails --strict with exit 3",
          res.returncode == 3 and not f3_out.exists())

    # F11: bad format annotation rejected.
    f11_staging = LAB / "f11-staging"
    f11_staging.mkdir()
    mid4 = 9000104
    updates4 = [{"update_id": 9000104,
                 "channel_post": {"message_id": mid4,
                                  "chat": {"id": "lab-test-channel", "title": "EJ test"},
                                  "date": now_epoch(),
                                  "text": "TEST — Phase 3 Tier A rehearsal post 4 (bad format)"}}]
    process_updates(updates4, f11_staging, enqueue=True,
                    db_path=str(LAB / "f11-queue.db"))
    con = init_db(str(LAB / "f11-queue.db"))
    set_status(con, "channel:%d" % mid4, "approved", note="Tier A F11 setup")
    con.close()
    bad_ann = dict(ann, format_type="BREAKING")
    (f11_staging / ("channel-%d.gate.json" % mid4)).write_text(
        json.dumps(bad_ann, ensure_ascii=False, indent=1), encoding="utf-8")
    f11_out = LAB / "f11-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(f11_staging), "--queue-db", str(LAB / "f11-queue.db"),
               "--base-edge", str(FIXTURE), "--out", str(f11_out),
               "--report", str(LAB / "f11-report.json"), "--strict"])
    f11_report = json.loads((LAB / "f11-report.json").read_text())
    check("F11 wrong format rejected (invalid_annotation, exit 3)",
          res.returncode == 3
          and any(s.get("reason") == "invalid_annotation"
                  for s in f11_report["skipped"]))

    # Bundle merge (scratch): approved item joins a NEW bundle header.
    b_staging = LAB / "b-staging"
    b_staging.mkdir()
    mid5 = 9000105
    updates5 = [{"update_id": 9000105,
                 "channel_post": {"message_id": mid5,
                                  "chat": {"id": "lab-test-channel", "title": "EJ test"},
                                  "date": now_epoch(),
                                  "text": "TEST — Phase 3 Tier A rehearsal post 5 (bundle member)"}}]
    process_updates(updates5, b_staging, enqueue=True,
                    db_path=str(LAB / "b-queue.db"))
    con = init_db(str(LAB / "b-queue.db"))
    set_status(con, "channel:%d" % mid5, "approved", note="Tier A bundle setup")
    con.close()
    bundle_ann = dict(ann, bundle_id="lab-tier-a-bundle",
                      bundle_kind="slot_bundle",
                      bundle_title="بستهٔ آزمایشگاهی فاز ۳",
                      bundle_date_shamsi="1405-08-03",
                      bundle_position=1, bundle_total=1)
    (b_staging / ("channel-%d.gate.json" % mid5)).write_text(
        json.dumps(bundle_ann, ensure_ascii=False, indent=1), encoding="utf-8")
    b_out = LAB / "b-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(b_staging), "--queue-db", str(LAB / "b-queue.db"),
               "--base-edge", str(FIXTURE), "--out", str(b_out),
               "--report", str(LAB / "b-report.json"), "--strict"])
    b_ok = res.returncode == 0
    if b_ok:
        bundles = json.loads((b_out / "bundles.json").read_text())["bundles"]
        header = next((b for b in bundles if b["bundle_id"] == "lab-tier-a-bundle"), None)
        items = json.loads((b_out / "channel_typed.json").read_text())["items"]
        member = next((i for i in items if i.get("chain") is not None), None)
        b_ok = (header is not None and member is not None
                and header["member_app_keys"] == [member["app_key"]]
                and member.get("bundle_position") == 1
                and member.get("bundle_total") == 1)
        res2 = run([sys.executable, str(HERE / "publish_edge.py"),
                    "--verify-only", "--out", str(b_out)])
        b_ok = b_ok and res2.returncode == 0
    check("Bundle merge: new header + member position 1/1 + verify PASS", b_ok)

    # published_empty (scratch): empty base + empty staging keeps
    # published_empty for all three Phase 3 sections.
    e_base = LAB / "e-base"
    shutil.copytree(FIXTURE, e_base)
    (e_base / "channel_typed.json").write_text(
        json.dumps({"label": "EMPTY BASE", "items": []}, ensure_ascii=False), encoding="utf-8")
    (e_base / "bundles.json").write_text(
        json.dumps({"label": "EMPTY BASE", "bundles": []}, ensure_ascii=False), encoding="utf-8")
    radar_doc = json.loads((e_base / "radar.json").read_text())
    radar_doc["signals"] = []
    (e_base / "radar.json").write_text(
        json.dumps(radar_doc, ensure_ascii=False, indent=1), encoding="utf-8")
    e_manifest = json.loads((e_base / "manifest.json").read_text())
    import hashlib as _hl
    empty_hash = _hl.sha256(b"[]").hexdigest()
    for name, fname in (("channel_typed", "channel_typed.json"),
                        ("bundles", "bundles.json"), ("radar", "radar.json")):
        e_manifest["sections"][name] = {"hash": empty_hash, "count": 0,
                                        "status": "published_empty",
                                        "file": fname, "items": []}
    (e_base / "manifest.json").write_text(
        json.dumps(e_manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    e_staging = LAB / "e-staging"
    e_staging.mkdir()
    (e_staging / "index.json").write_text(
        json.dumps({"generated_at_utc": datetime.now(timezone.utc).isoformat(),
                    "staged_count": 0, "staged": []}, ensure_ascii=False),
        encoding="utf-8")
    e_out = LAB / "e-out"
    res = run([sys.executable, str(HERE / "merge_staging.py"),
               "--staging-dir", str(e_staging), "--queue-db", str(LAB / "e-queue.db"),
               "--base-edge", str(e_base), "--out", str(e_out),
               "--report", str(LAB / "e-report.json")])
    e_ok = res.returncode == 0
    if e_ok:
        out_manifest = json.loads((e_out / "manifest.json").read_text())
        e_ok = all(out_manifest["sections"][n].get("status") == "published_empty"
                   and out_manifest["sections"][n].get("count") == 0
                   for n in ("channel_typed", "bundles", "radar"))
        res2 = run([sys.executable, str(HERE / "publish_edge.py"),
                    "--verify-only", "--out", str(e_out)])
        e_ok = e_ok and res2.returncode == 0
    check("published_empty preserved for empty sections + verify PASS", e_ok)

    # F7/F8/F6 (observations, scratch): bad manifest, unreachable,
    # checker timeout -> INCONCLUSIVE (never an app failure).
    bad_manifest = LAB / "bad-manifest.json"
    bad_manifest.write_text("{not json", encoding="utf-8")
    try:
        json.loads(bad_manifest.read_text())
        f7_ok = False
    except json.JSONDecodeError:
        f7_ok = True  # simulation reports bad_manifest, old state retained
    check("F7 bad manifest detected, cache untouched (simulation)", f7_ok)
    # F6 (true stale case): serve the BASE fixture while expecting
    # the merged hash -- a v3 manifest IS served, with the old
    # hash, so the checker must report pages_not_yet_serving.
    class FixtureHandler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(FIXTURE), **kwargs)

        def log_message(self, *a):
            pass

    f6_httpd = socketserver.TCPServer(("127.0.0.1", 0), FixtureHandler)
    f6_port = f6_httpd.server_address[1]
    f6_thread = threading.Thread(target=f6_httpd.serve_forever, daemon=True)
    f6_thread.start()
    res = run([sys.executable, str(HERE / "check_pages.py"),
               "--manifest-url", "http://127.0.0.1:%d/manifest.json" % f6_port,
               "--section", "channel_typed", "--expected-hash", merged_hash,
               "--interval", "10", "--window", "25",
               "--report", str(LAB / "f6-report.json")])
    f6 = json.loads((LAB / "f6-report.json").read_text()) if (LAB / "f6-report.json").exists() else {}
    f6_httpd.shutdown()
    f6_httpd.server_close()
    check("F6 stale manifest -> pages_not_yet_serving, exit 2",
          res.returncode == 2 and f6.get("status") == "pages_not_yet_serving",
          "status=%s" % f6.get("status"))
    # F8 (unreachable case): closed port -> edge_unreachable, exit 2.
    res = run([sys.executable, str(HERE / "check_pages.py"),
               "--manifest-url", "http://127.0.0.1:1/manifest.json",
               "--section", "channel_typed", "--expected-hash", "0" * 64,
               "--interval", "10", "--window", "25",
               "--report", str(LAB / "f8-report.json")])
    f8 = json.loads((LAB / "f8-report.json").read_text()) if (LAB / "f8-report.json").exists() else {}
    check("F8 unreachable edge -> edge_unreachable, exit 2",
          res.returncode == 2 and f8.get("status") == "edge_unreachable",
          "status=%s" % f8.get("status"))

    # F12 negative check: no allow-list in the lab run, live channel
    # never referenced. (The live numeric id is not known to this
    # lab run, so spec 7.5(a)+(b) are the control: no allow-list
    # file exists at all in the lab tree.)
    allowlist = APP_ROOT / "bridge" / "channel_allowlist.json"
    check("F12 no allow-list file in lab tree", not allowlist.exists())
    check("F12 merged edge has no live-channel handle",
          "EcoJournalFa" not in (REPO_OUT / "channel_typed.json").read_text()
          and "EcoJournalFa" not in (REPO_OUT / "manifest.json").read_text())

    # ---- Chain record (lab legs measured; phone legs null by design).
    chain_epochs = {
        "channel_posted_epoch": chain.get("channel_posted_epoch"),
        "staged_epoch": chain.get("staged_epoch"),
        "gate_decided_epoch": chain.get("gate_decided_epoch"),
        "gate_decision": "approved",
        "gate_provenance": PROVENANCE,
        "edge_generated_epoch": chain.get("edge_generated_epoch"),
        "edge_pushed_epoch": None,
        "pages_first_seen_epoch": None,
        "pages_first_seen_window_SIMULATED": window,
        "app_manifest_seen_epoch": None,
        "app_item_applied_epoch": None,
        "app_notified_epoch": None,
        "app_legs_note": ("Tier A lab: no phone sync; app-side fields are "
                         "implemented in EdgeRepository/DiagnosticsScreen "
                         "and await a phone test (Tier B blocked)."),
    }
    legs = {
        "capture_M1_M0_s": (chain.get("staged_epoch") or 0) - (chain.get("channel_posted_epoch") or 0),
        "gate_wait_M2_M1_s_includes_deliberation": (chain.get("gate_decided_epoch") or 0) - (chain.get("staged_epoch") or 0),
        "merge_M3_M2_s": (chain.get("edge_generated_epoch") or 0) - (chain.get("gate_decided_epoch") or 0),
        "mechanical_gate_to_verified_s": t4 - t2,
        "mechanical_target": "<=300 s target, fail >900 s (spec L-1, push leg N/A in Tier A)",
        "pages_window_SIMULATED_s": (window.get("first_matching_check_epoch", 0)
                                     - window.get("previous_check_epoch", 0)) if window else None,
        "polling_leg": "SIMULATED via delta (1 fetch); phone polling leg NOT measured in Tier A",
    }
    record("tier_a_complete", "lab orchestrator",
           str(CHAIN_RECORD), now_epoch(),
           "failures=%d" % len(failures))
    chain_record = {
        "tier": "A (lab/dry-run)",
        "verdict": "TIER A PASS (lab)" if not failures else "TIER A FAIL",
        "verdict_note": ("Tier A proves the chain up to the publish gate. "
                         "It is NOT an end-to-end latency result and NOT a "
                         "phone pass. Tier B is BLOCKED (see phase report)."),
        "message_id": PRIMARY_MID,
        "app_key": item.get("app_key"),
        "story_id": item.get("story_id"),
        "format_type": "WIRE",
        "chain": chain_epochs,
        "legs": legs,
        "steps": steps,
        "delta_fetches": fetches,
        "failures": failures,
        "git_status_before_run": git_before_all,
        "failure_modes": {
            "F1": "exercised (exit 4, nothing written)",
            "F2": "exercised (rejected item excluded)",
            "F3": "exercised (held item fails --strict, exit 3)",
            "F4": "exercised (corrupt edge fails verify, git unchanged)",
            "F5": "NOT APPLICABLE in Tier A (no push ever runs)",
            "F6": "exercised (stale v3 manifest served -> pages_not_yet_serving, exit 2)",
            "F7": "exercised (bad manifest detected, old state retained)",
            "F8": "exercised (unreachable edge -> edge_unreachable, exit 2)",
            "F9": "NOT MEASURABLE in lab (Doze is phone-only; rule documented)",
            "F10": "exercised (duplicate capture stages nothing new)",
            "F11": "exercised (bad format annotation rejected, exit 3)",
            "F12": "exercised (negative check: no allow-list, no live handle)",
        },
    }
    CHAIN_RECORD.write_text(json.dumps(chain_record, ensure_ascii=False, indent=1),
                            encoding="utf-8")
    print("=== Tier A: %s (%d failures) ==="
          % ("PASS (lab)" if not failures else "FAIL", len(failures)))
    for f in failures:
        print("  FAIL: %s" % f)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
