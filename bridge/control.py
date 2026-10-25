#!/usr/bin/env python3
"""Control-plane CLI engine — ECO JOURNAL app (LAB ONLY).

The engine Yasin's private dashboard calls (a future private Muse
artifact view will wrap these same commands). No server, no UI
framework, no network. All state lives in the LAB queue DB
(bridge/queue.db); production databases are never touched.

Commands:
  queue list [--status STATUS] [--limit N]
  queue show <key>
  submit <key>            draft -> review
  approve <key> [--note TEXT]   review -> approved
  reject <key> [--note TEXT]    review -> rejected (terminal)
  hold <key> --note TEXT        stays in review, note recorded
  schedule <key>          approved -> queued
  mark-app <key>          queued -> published_app (edge publish step)
  enqueue <article.json>  insert at draft
  health                  queue counts, edge hash, market freshness,
                          staging sync, lock status, live_sends
  invites generate --label LABEL
  invites list
  invites verify <CODE>
  invites revoke --label LABEL | --code CODE

Invite codes: generated with secrets, stored ONLY as SHA-256 hashes
(the code itself is printed once at generation, for Yasin to hand to
the reader out-of-band — Muse never DMs readers). A code is shown to
no one else afterwards; verification hashes the presented code and
compares. States: unused -> active (first verify) -> revoked.
Revocation is advisory on a static edge (a reader who already cached
articles keeps the cache — see docs/control-plane-spec.md).

Exit codes: 0 = ok; 1 = error (unknown key, illegal transition, ...).
"""

import argparse
import hashlib
import json
import secrets
import string
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bridge_lib import (  # noqa: E402
    EDGE_OUT, LOCK_FILE, STATUSES, enqueue_item, get_item, init_db,
    lock_status, now_iso, queue_db_path, set_note, set_status,
)

APP_ROOT = Path(__file__).resolve().parent.parent

CODE_ALPHABET = "".join(c for c in string.ascii_uppercase + "23456789"
                        if c not in "IO01")


def hash_code(code: str) -> str:
    return hashlib.sha256(code.strip().encode("utf-8")).hexdigest()


def generate_code() -> str:
    groups = ["".join(secrets.choice(CODE_ALPHABET) for _ in range(4))
              for _ in range(3)]
    return "EJ-" + "-".join(groups)


# ---------------------------------------------------------------- queue

def cmd_queue_list(con, args):
    where = "WHERE status = ?" if args.status else ""
    params = (args.status,) if args.status else ()
    rows = con.execute(
        f"SELECT key, title, status, updated_at FROM items {where} "
        f"ORDER BY updated_at DESC LIMIT ?", (*params, args.limit)
    ).fetchall()
    if not rows:
        print("(empty)")
        return 0
    for key, title, status, updated in rows:
        print(f"{status:17} {key}  {title[:60]}  ({updated[:19]})")
    return 0


def cmd_queue_show(con, args):
    item = get_item(con, args.key)
    if not item:
        print(f"unknown key {args.key}", file=sys.stderr)
        return 1
    print(f"key: {item['key']}\narticle: {item['article_id']}"
          f"\ntitle: {item['title']}\nsource: {item['source']}"
          f"\nstatus: {item['status']}"
          f"\nchannel_message_id: {item['channel_message_id']}"
          f"\ncreated: {item['created_at']}\nupdated: {item['updated_at']}"
          f"\nsplit_plan: {item['split_plan']}")
    if item["note"]:
        print(f"notes:\n{item['note']}")
    return 0


def _advance(con, key, target, note=""):
    ok, msg = set_status(con, key, target, note=note)
    print(msg if ok else f"REFUSED: {msg}", file=sys.stderr if not ok else None)
    return 0 if ok else 1


def cmd_submit(con, args):
    return _advance(con, args.key, "review")


def cmd_approve(con, args):
    return _advance(con, args.key, "approved", note=args.note or "")


def cmd_reject(con, args):
    return _advance(con, args.key, "rejected", note=args.note or "")


def cmd_hold(con, args):
    item = get_item(con, args.key)
    if not item:
        print(f"unknown key {args.key}", file=sys.stderr)
        return 1
    if item["status"] != "review":
        print(f"REFUSED: hold only applies in review (now {item['status']})",
              file=sys.stderr)
        return 1
    set_note(con, args.key, f"hold: {args.note}")
    print(f"{args.key} held in review")
    return 0


def cmd_schedule(con, args):
    return _advance(con, args.key, "queued")


def cmd_mark_app(con, args):
    return _advance(con, args.key, "published_app")


def cmd_enqueue(con, args):
    try:
        article = json.loads(Path(args.article).read_text(encoding="utf-8"))
    except Exception as e:
        print(f"bad article file: {e}", file=sys.stderr)
        return 1
    ok, msg = enqueue_item(con, f"article:{article.get('id', '')}", article)
    print(msg)
    return 0 if ok else 1


# ---------------------------------------------------------------- health

def cmd_health(con, args):
    print("== queue ==")
    counts = dict(con.execute(
        "SELECT status, COUNT(*) FROM items GROUP BY status").fetchall())
    for s in STATUSES:
        if counts.get(s):
            print(f"  {s}: {counts[s]}")
    if not counts:
        print("  (empty)")
    sent = con.execute("SELECT COUNT(*) FROM sent").fetchone()[0]
    print(f"  live_sends recorded: {sent}")
    print("== edge ==")
    index_file = EDGE_OUT / "index.json"
    if index_file.exists():
        index = json.loads(index_file.read_text(encoding="utf-8"))
        entries = index.get("entries", [])
        canonical = json.dumps(entries, ensure_ascii=False, sort_keys=True)
        recomputed = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        declared = index.get("hash_sha256")
        missing = [e.get("file") for e in entries
                   if e.get("file") and not (EDGE_OUT / e["file"]).exists()]
        print(f"  entries: {len(entries)}; hash match: "
              f"{recomputed == declared} (declared {str(declared)[:12]}…)")
        print(f"  unresolvable entry files: {len(missing)}")
        print(f"  index generated: {index.get('generated_at_utc')}")
    else:
        print("  edge/out/index.json missing")
    print("== market ==")
    market_file = EDGE_OUT / "market.json"
    if market_file.exists():
        market = json.loads(market_file.read_text(encoding="utf-8"))
        snaps = market.get("snapshots") or market.get("prices") or []
        if snaps:
            latest = snaps[-1]
            latest_ts = latest.get("ts_tehran") or latest.get("ts") or "?"
            print(f"  snapshots: {len(snaps)}; latest: {latest_ts}")
            print("  NOTE: market data is a frozen-lab snapshot (last "
                  "2026-10-24); the board shows its date, never as fresh")
        else:
            print("  no snapshots in market.json")
    else:
        print("  market.json missing")
    print("== staging ==")
    staging_index = APP_ROOT / "bridge" / "staging" / "index.json"
    if staging_index.exists():
        manifest = json.loads(staging_index.read_text(encoding="utf-8"))
        print(f"  staged items: {manifest.get('staged_count')}; "
              f"last sync: {manifest.get('generated_at_utc')}")
    else:
        print("  no staging yet (channel reader not run)")
    print("== bridge ==")
    print(f"  send lock: {lock_status()}")
    dry = APP_ROOT / "bridge" / "dryrun-report.json"
    if dry.exists():
        rep = json.loads(dry.read_text(encoding="utf-8"))
        print(f"  dry-run report live_sends: {rep.get('live_sends')}")
    print("== invites ==")
    inv = con.execute(
        "SELECT state, COUNT(*) FROM invites GROUP BY state").fetchall()
    print("  " + (", ".join(f"{s}: {n}" for s, n in inv) or "(none)"))
    return 0


# ---------------------------------------------------------------- invites

def cmd_invite_generate(con, args):
    code = generate_code()
    digest = hash_code(code)
    if con.execute("SELECT 1 FROM invites WHERE code_hash = ?",
                   (digest,)).fetchone():
        print("collision (astronomically unlikely); re-run", file=sys.stderr)
        return 1
    con.execute(
        "INSERT INTO invites (code_hash, label, state, created_at)"
        " VALUES (?,?, 'unused', ?)",
        (digest, args.label, now_iso()))
    con.commit()
    print(f"code (show ONCE, hand to the reader out-of-band): {code}")
    print(f"label: {args.label}  state: unused")
    return 0


def cmd_invite_list(con, args):
    rows = con.execute(
        "SELECT label, state, created_at, first_seen_at, last_seen_at"
        " FROM invites ORDER BY created_at").fetchall()
    if not rows:
        print("(no invite codes)")
        return 0
    for label, state, created, first, last in rows:
        print(f"{state:8} {label}  created {created[:19]}  "
              f"first_seen {str(first)[:19]}  last_seen {str(last)[:19]}")
    return 0


def cmd_invite_verify(con, args):
    digest = hash_code(args.code)
    row = con.execute(
        "SELECT state FROM invites WHERE code_hash = ?", (digest,)).fetchone()
    if not row:
        print("REFUSED: unknown code", file=sys.stderr)
        return 1
    state = row[0]
    if state == "revoked":
        print("REFUSED: code revoked", file=sys.stderr)
        return 1
    ts = now_iso()
    if state == "unused":
        con.execute("UPDATE invites SET state='active', first_seen_at=?,"
                    " last_seen_at=? WHERE code_hash = ?",
                    (ts, ts, digest))
        print("OK: code activated")
    else:
        con.execute("UPDATE invites SET last_seen_at=? WHERE code_hash = ?",
                    (ts, digest))
        print("OK: code active")
    con.commit()
    return 0


def cmd_invite_revoke(con, args):
    if args.code:
        digest = hash_code(args.code)
        row = con.execute("SELECT label FROM invites WHERE code_hash = ?",
                          (digest,)).fetchone()
        if not row:
            print("REFUSED: unknown code", file=sys.stderr)
            return 1
        con.execute("UPDATE invites SET state='revoked' WHERE code_hash = ?",
                    (digest,))
        con.commit()
        print(f"revoked code for label '{row[0]}'")
        return 0
    rows = con.execute("SELECT code_hash FROM invites WHERE label = ?",
                       (args.label,)).fetchall()
    if len(rows) != 1:
        print(f"REFUSED: label '{args.label}' matches {len(rows)} codes "
              "(use --code for an exact match)", file=sys.stderr)
        return 1
    con.execute("UPDATE invites SET state='revoked' WHERE code_hash = ?",
                (rows[0][0],))
    con.commit()
    print(f"revoked code for label '{args.label}'")
    return 0


# ---------------------------------------------------------------- main

def build_parser():
    p = argparse.ArgumentParser(description="ECO JOURNAL control plane (LAB)")
    p.add_argument("--db", default=None,
                   help="lab queue DB (default bridge/queue.db or "
                        "BRIDGE_QUEUE_DB)")
    sub = p.add_subparsers(dest="cmd", required=True)

    q = sub.add_parser("queue"); qsub = q.add_subparsers(dest="qcmd", required=True)
    l = qsub.add_parser("list"); l.add_argument("--status", default=None)
    l.add_argument("--limit", type=int, default=50)
    l.set_defaults(fn=cmd_queue_list)
    s = qsub.add_parser("show"); s.add_argument("key"); s.set_defaults(fn=cmd_queue_show)

    for name, fn, help_text in [
        ("submit", cmd_submit, "draft -> review"),
        ("approve", cmd_approve, "review -> approved"),
        ("reject", cmd_reject, "review -> rejected"),
        ("schedule", cmd_schedule, "approved -> queued"),
        ("mark-app", cmd_mark_app, "queued -> published_app"),
    ]:
        sp = sub.add_parser(name, help=help_text)
        sp.add_argument("key")
        if name in ("approve", "reject"):
            sp.add_argument("--note", default="")
        sp.set_defaults(fn=fn)
    h = sub.add_parser("hold"); h.add_argument("key"); h.add_argument("--note", required=True)
    h.set_defaults(fn=cmd_hold)
    e = sub.add_parser("enqueue"); e.add_argument("article")
    e.set_defaults(fn=cmd_enqueue)
    he = sub.add_parser("health"); he.set_defaults(fn=cmd_health)

    i = sub.add_parser("invites"); isub = i.add_subparsers(dest="icmd", required=True)
    g = isub.add_parser("generate"); g.add_argument("--label", required=True)
    g.set_defaults(fn=cmd_invite_generate)
    li = isub.add_parser("list"); li.set_defaults(fn=cmd_invite_list)
    v = isub.add_parser("verify"); v.add_argument("code"); v.set_defaults(fn=cmd_invite_verify)
    r = isub.add_parser("revoke"); r.add_argument("--label", default=None)
    r.add_argument("--code", default=None); r.set_defaults(fn=cmd_invite_revoke)
    return p


def main(argv) -> int:
    args = build_parser().parse_args(argv)
    con = init_db(queue_db_path(args.db))
    try:
        return args.fn(con, args)
    finally:
        con.close()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
