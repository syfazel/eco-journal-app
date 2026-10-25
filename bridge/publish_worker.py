#!/usr/bin/env python3
"""Gated publish worker — ECO JOURNAL app (LAB ONLY).

Processes queue items at status `published_app` (ready for the
channel) through the full gate set from docs/telegram-bridge-spec.md:

  1. Status must be published_app (the machine enforces this).
  2. Format must validate (title, body, attribution, 4096-char cap).
     Oversize -> flagged with a split plan, NEVER silently truncated,
     and the item stays published_app for a human decision.
  3. Idempotency key must be unused (checked against the lab sent
     table, across runs).
  4. A non-blocking file lock wraps the whole select-then-send step
     (the 2026-10-23 08:00 lesson). A concurrent worker exits with
     code 2 and sends nothing.
  5. Live-send gates (bridge_lib.check_live_gates): --live flag AND
     BRIDGE_LIVE=1 AND BRIDGE_CHANNEL_ID (test channel) AND
     ECO_BOT_TOKEN. Default is DRY-RUN: the worker logs exactly what
     WOULD be sent and records nothing as sent. live_sends stays 0.

A failed live send leaves the item at published_app for the next
pass — it is never marked published without a real message_id back
from Telegram, and never silently dropped.

Usage:
  python3 bridge/publish_worker.py --run            # dry-run (default)
  python3 bridge/publish_worker.py --run --live     # gated live path
  python3 bridge/publish_worker.py --enqueue article.json
  python3 bridge/publish_worker.py --db /tmp/q.db --run

Exit codes: 0 = ok; 1 = error; 2 = send lock held by another process.
The JSON report of the last run is written to bridge/publish-report.json
(or --report PATH). It never contains a token.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bridge_lib import (  # noqa: E402
    BRIDGE_DIR, LockHeld, already_sent, check_live_gates, format_channel,
    get_item, init_db, now_iso, queue_db_path, record_sent, send_lock,
    send_via_bot_api, set_note, split_channel_text, validate_channel_text,
)
import os  # noqa: E402

REPORT_FILE = BRIDGE_DIR / "publish-report.json"


def enqueue_article(article_file, db_path=None):
    con = init_db(queue_db_path(db_path))
    try:
        article = json.loads(Path(article_file).read_text(encoding="utf-8"))
    except Exception as e:
        print(f"bad article file: {e}", file=sys.stderr)
        return 1
    from bridge_lib import enqueue_item
    ok, msg = enqueue_item(con, f"article:{article.get('id', '')}", article)
    print(msg)
    con.close()
    return 0 if ok else 1


def run_worker(db_path=None, live=False, report_file=None,
               sender=None):
    """Run one pass. sender is injectable for tests (default: real API).

    Returns (exit_code, report_dict).
    """
    report_file = Path(report_file) if report_file else REPORT_FILE
    gates_ok, gate_reasons = check_live_gates(live)
    mode = "live" if (live and gates_ok) else "dry-run"
    if live and not gates_ok:
        print("live send REFUSED (fail closed): " + "; ".join(gate_reasons))
    con = init_db(queue_db_path(db_path))
    report = {
        "generated_at_utc": now_iso(),
        "mode": mode,
        "live_requested": live,
        "gate_reasons": gate_reasons if live else [],
        "network_used": False,
        "live_sends": 0,
        "would_send": [],
        "sent": [],
        "skipped": [],
        "blocked": [],
        "note": "Dry-run records nothing as sent. Only a returned "
                "message_id proves a posting.",
    }
    try:
        with send_lock():
            rows = con.execute(
                "SELECT key FROM items WHERE status = 'published_app'"
                " ORDER BY updated_at"
            ).fetchall()
            for (key,) in rows:
                item = get_item(con, key)
                article = json.loads(item["payload"])
                text = format_channel(article)
                valid, why = validate_channel_text(text, article)
                if not valid:
                    parts = split_channel_text(text) if len(text) > 4096 else None
                    plan = {"parts": len(parts)} if parts else None
                    con.execute(
                        "UPDATE items SET split_plan = ?, updated_at = ?"
                        " WHERE key = ?",
                        (json.dumps(plan) if plan else None, now_iso(), key),
                    )
                    con.commit()
                    set_note(con, key, f"blocked: {why}")
                    report["blocked"].append(
                        {"key": key, "reason": why, "split_plan": plan})
                    print(f"[BLOCKED] {key}: {why}")
                    continue
                if already_sent(con, key):
                    report["skipped"].append(
                        {"key": key, "reason": "idempotency key already sent"})
                    print(f"[SKIPPED] {key}: already sent")
                    continue
                if mode == "dry-run":
                    report["would_send"].append({
                        "key": key,
                        "article_id": item["article_id"],
                        "title": item["title"],
                        "chars": len(text),
                        "text_preview": text[:300],
                        "verdict": "WOULD_SEND_IF_UNFROZEN",
                    })
                    print(f"[DRY-RUN] {key}: would send ({len(text)} chars); "
                          "nothing recorded as sent")
                    continue
                # Live path — only reachable with all gates passing.
                send = sender or send_via_bot_api
                chat_id = os.environ["BRIDGE_CHANNEL_ID"]
                try:
                    message_id = send(text, chat_id)
                except Exception as e:
                    set_note(con, key, f"send failed: {e}; stays published_app")
                    report["blocked"].append(
                        {"key": key, "reason": f"send failed: {e}"})
                    print(f"[FAILED] {key}: {e}; stays published_app")
                    continue
                record_sent(con, key, [message_id])
                report["network_used"] = True
                report["live_sends"] += 1
                report["sent"].append(
                    {"key": key, "message_id": message_id})
                print(f"[SENT] {key}: message_id {message_id}")
    except LockHeld:
        print("skipped: another send is running (lock held)")
        con.close()
        return 2, report
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=1),
                           encoding="utf-8")
    con.close()
    print(f"done: mode={mode} live_sends={report['live_sends']} "
          f"would_send={len(report['would_send'])} "
          f"blocked={len(report['blocked'])} report={report_file}")
    return 0, report


def main(argv) -> int:
    db_path = None
    report_file = None
    if "--db" in argv:
        db_path = argv[argv.index("--db") + 1]
    if "--report" in argv:
        report_file = argv[argv.index("--report") + 1]
    if "--enqueue" in argv:
        return enqueue_article(argv[argv.index("--enqueue") + 1], db_path)
    if "--run" in argv:
        code, _ = run_worker(db_path=db_path, live="--live" in argv,
                             report_file=report_file)
        return code
    print("usage: publish_worker.py --run [--live] | --enqueue article.json",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
