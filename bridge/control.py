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
    # M7 (P2-15, P1-42): --status is validated against the machine
    # (an invalid value used to print a silent "(empty)"), --limit
    # must be non-negative, titles are shown in full (no silent
    # 60-char cut), and a blocked reason is shown when the note
    # carries one — a reviewer must not approve blind.
    if args.status and args.status not in STATUSES:
        print(f"REFUSED: unknown status '{args.status}' "
              f"(valid: {', '.join(STATUSES)})", file=sys.stderr)
        return 1
    if args.limit < 0:
        print("REFUSED: --limit must be >= 0", file=sys.stderr)
        return 1
    where = "WHERE status = ?" if args.status else ""
    params = (args.status,) if args.status else ()
    rows = con.execute(
        f"SELECT key, title, status, updated_at, note FROM items {where} "
        f"ORDER BY updated_at DESC LIMIT ?", (*params, args.limit)
    ).fetchall()
    if not rows:
        print("(empty)")
        return 0
    for key, title, status, updated, note in rows:
        blocked = ""
        if note:
            for line in note.splitlines():
                if line.startswith("blocked:"):
                    blocked = f"  BLOCKED: {line[len('blocked: '):]}"
                    break
        print(f"{status:17} {key}  {title}  ({updated[:19]}){blocked}")
    return 0


def cmd_queue_show(con, args):
    # M7 (P1-42): the reviewer finally sees the body verbatim,
    # original URL, provenance, kind and warnings — approving from
    # the CLI is no longer blind. Payload JSON is parsed defensively;
    # a corrupt payload is reported, not a crash.
    item = get_item(con, args.key)
    if not item:
        print(f"unknown key {args.key}", file=sys.stderr)
        return 1
    try:
        payload = json.loads(item["payload"])
    except Exception:
        payload = {}
    body = "\n\n".join(b for b in (payload.get("body_blocks") or []) if b)
    if not body and payload.get("text"):
        body = payload["text"]
    warnings = []
    if not body:
        warnings.append("empty body")
    if not payload.get("original_url"):
        warnings.append("no original_url")
    if item["note"] and "blocked:" in item["note"]:
        warnings.append("has blocked notes (see below)")
    print(f"key: {item['key']}\narticle: {item['article_id']}"
          f"\ntitle: {item['title']}\nsource: {item['source']}"
          f"\nstatus: {item['status']}"
          f"\nkind: {payload.get('kind', '?')}"
          f"\noriginal_url: {payload.get('original_url', '')}"
          f"\nprovenance: {payload.get('provenance', '')}"
          f"\nchannel_message_id: {item['channel_message_id']}"
          f"\ncreated: {item['created_at']}\nupdated: {item['updated_at']}"
          f"\nsplit_plan: {item['split_plan']}"
          f"\nwarnings: {'; '.join(warnings) if warnings else 'none'}")
    print(f"body:\n{body if body else '(no body in payload)'}")
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
    # M7 (P1-40): cadence is enforced at schedule time, not just at
    # send time — a third schedule attempt on a day with 2 sends
    # already recorded fails with a named error, never a silent flip.
    from bridge_lib import cadence_ok
    ok_cad, cad_why = cadence_ok(con)
    if not ok_cad:
        print(f"REFUSED: {cad_why}", file=sys.stderr)
        return 1
    return _advance(con, args.key, "queued")


def cmd_mark_app(con, args):
    """Verified mark-app (M7, P1-39): the old command just flipped
    the status and certified a lie — an operator could mark an item
    readers can never see. Now the item must exist in the new edge
    index, and when both the payload and the index carry a
    content_hash they must match; otherwise the command refuses and
    the status does not move."""
    item = get_item(con, args.key)
    if not item:
        print(f"unknown key {args.key}", file=sys.stderr)
        return 1
    if item["status"] != "queued":
        print(f"REFUSED: mark-app only from queued (now {item['status']})",
              file=sys.stderr)
        return 1
    index_file = EDGE_OUT / "index.json"
    if not index_file.exists():
        print("REFUSED: edge/out/index.json missing — nothing verified",
              file=sys.stderr)
        return 1
    try:
        index = json.loads(index_file.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"REFUSED: edge index unparsable: {e}", file=sys.stderr)
        return 1
    try:
        payload = json.loads(item["payload"])
    except Exception:
        payload = {}
    article_id = payload.get("id") or item["article_id"]
    entry = None
    for e in index.get("entries", []):
        if e.get("id") == article_id or e.get("app_key") == payload.get("app_key"):
            entry = e
            break
    if entry is None:
        print(f"REFUSED: {article_id} not found in the new edge index — "
              "publish it to the edge first (control.py promote)",
              file=sys.stderr)
        return 1
    payload_hash = payload.get("content_hash")
    entry_hash = entry.get("content_hash")
    if payload_hash and entry_hash and payload_hash != entry_hash:
        print("REFUSED: content_hash mismatch between queue payload and "
              "edge index — the edge copy is stale or the payload changed",
              file=sys.stderr)
        return 1
    return _advance(con, args.key, "published_app")


def cmd_reconcile(con, args):
    """Operator reconciliation for a "sending" item (M7, P1-35).

    --action retry: the operator asserts the send did NOT land (or
    accepts the re-send risk) and returns the item to published_app.
    --action hold: the item stays in sending with a note; it is
    never auto re-sent by a later worker pass. There is no
    "confirm sent without message ids" action — a send is only
    proven by returned message ids, never by assertion.
    """
    item = get_item(con, args.key)
    if not item:
        print(f"unknown key {args.key}", file=sys.stderr)
        return 1
    if item["status"] != "sending":
        print(f"REFUSED: reconcile only applies to sending items "
              f"(now {item['status']})", file=sys.stderr)
        return 1
    if args.action == "retry":
        return _advance(con, args.key, "published_app",
                        note="operator reconciled sending -> retry")
    if args.action == "hold":
        from bridge_lib import set_note
        set_note(con, args.key, "operator held sending item for review")
        print(f"{args.key} held in sending")
        return 0
    print("REFUSED: --action must be retry or hold", file=sys.stderr)
    return 1


def cmd_promote(con, args):
    """Staging -> edge promotion (M7, P0-10), lab only.

    Reads bridge/staging/<staging-id>.json, writes it as an edge
    article file (app_key + content_hash computed exactly like the
    generator), and rewrites edge/out/index.json with the entry
    added and the index hash recomputed over the new entry list.
    The app's next sync then fetches it, and mark-app can verify
    it. Nothing is pushed and nothing live changes: the edge
    snapshot only reaches readers when it is committed and pushed
    (a separate, later step — never done by this command).
    """
    import hashlib as _hashlib
    staging_file = APP_ROOT / "bridge" / "staging" / f"{args.staging_id}.json"
    if not staging_file.exists():
        print(f"REFUSED: no staging file {staging_file}", file=sys.stderr)
        return 1
    try:
        item = json.loads(staging_file.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"REFUSED: staging file unparsable: {e}", file=sys.stderr)
        return 1
    if not item.get("id") or not item.get("title"):
        print("REFUSED: staging item needs id and title", file=sys.stderr)
        return 1
    app_key = "a-" + _hashlib.sha256(item["id"].encode("utf-8")).hexdigest()[:20]
    body_blocks = item.get("body_blocks") or ([item["text"]] if item.get("text") else [])
    canonical = "\u0001".join([
        item.get("title", ""), item.get("summary", ""),
        "\n".join(body_blocks), "[]", item.get("why_matters") or "",
        item.get("category", ""), item.get("source", ""),
        item.get("original_url", ""), item.get("published_at", ""),
    ])
    content_hash = _hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    file_name = _hashlib.sha256(item["id"].encode("utf-8")).hexdigest() + ".json"
    doc = dict(item)
    doc.update({"app_key": app_key, "content_hash": content_hash,
                "body_blocks": body_blocks, "status": "approved"})
    articles_dir = EDGE_OUT / "articles"
    articles_dir.mkdir(parents=True, exist_ok=True)
    (articles_dir / file_name).write_text(
        json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    index_file = EDGE_OUT / "index.json"
    index = json.loads(index_file.read_text(encoding="utf-8")) if index_file.exists() else {
        "version": 2, "entries": []}
    entries = [e for e in index.get("entries", []) if e.get("id") != item["id"]]
    entries.append({
        "id": item["id"], "app_key": app_key, "kind": item.get("kind", "channel_post"),
        "title": item["title"], "summary": item.get("summary", ""),
        "category": item.get("category", "channel"),
        "category_fa": item.get("category_fa", "کانال"),
        "source": item.get("source", ""),
        "published_at": item.get("published_at", ""),
        "file": f"articles/{file_name}",
        "channel_message_id": item.get("channel_message_id"),
        "status": "approved", "content_hash": content_hash,
        "updated_at_epoch": None,
        "provenance": item.get("provenance", ""),
    })
    canonical_index = json.dumps(entries, ensure_ascii=False, sort_keys=True)
    index["entries"] = entries
    index["hash_sha256"] = _hashlib.sha256(canonical_index.encode("utf-8")).hexdigest()
    index_file.write_text(json.dumps(index, ensure_ascii=False, indent=1),
                          encoding="utf-8")
    print(f"promoted {item['id']} -> edge index ({len(entries)} entries, "
          f"app_key {app_key}); mark-app can now verify it")
    return 0


def cmd_enqueue(con, args):
    try:
        article = json.loads(Path(args.article).read_text(encoding="utf-8"))
    except Exception as e:
        print(f"bad article file: {e}", file=sys.stderr)
        return 1
    # M7 (P2-12): an id-less article used to collide on "article:"
    # and every later id-less article was refused. Fall back to a
    # hash of the payload so distinct articles get distinct keys.
    article_id = article.get("id", "")
    if not article_id:
        article_id = "untitled-" + hashlib.sha256(
            json.dumps(article, ensure_ascii=False, sort_keys=True
                       ).encode("utf-8")).hexdigest()[:16]
    ok, msg = enqueue_item(con, f"article:{article_id}", article)
    print(msg)
    return 0 if ok else 1


# ---------------------------------------------------------------- health

def _read_json_safe(path):
    """Return (data, error): corrupt JSON is unhealthy, never a crash
    (M7, P2-15)."""
    try:
        return json.loads(Path(path).read_text(encoding="utf-8")), None
    except Exception as e:
        return None, str(e)


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
        index, err = _read_json_safe(index_file)
        if err:
            print(f"  UNHEALTHY: index.json corrupt: {err}")
        else:
            entries = index.get("entries", [])
            canonical = json.dumps(entries, ensure_ascii=False, sort_keys=True)
            recomputed = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
            declared = index.get("hash_sha256")
            missing = [e.get("file") for e in entries
                       if e.get("file") and not (EDGE_OUT / e["file"]).exists()]
            print(f"  entries: {len(entries)}; hash match: "
                  f"{recomputed == declared} (declared {str(declared)[:12]}…)")
            print(f"  unresolvable entry files: {len(missing)}")
            if missing:
                print("  unresolvable: " + ", ".join(missing[:10]))
            print(f"  index generated: {index.get('generated_at_utc')}")
    else:
        print("  edge/out/index.json missing")
    print("== market ==")
    market_file = EDGE_OUT / "market.json"
    if market_file.exists():
        market, err = _read_json_safe(market_file)
        if err:
            print(f"  UNHEALTHY: market.json corrupt: {err}")
        else:
            snaps = market.get("snapshots") or market.get("prices") or []
            if snaps:
                # Latest = max timestamp, not last array element (P2-15).
                def _ts(s):
                    return s.get("ts_tehran") or s.get("ts") or ""
                latest = max(snaps, key=_ts)
                latest_ts = _ts(latest)
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
        manifest, err = _read_json_safe(staging_index)
        if err:
            print(f"  UNHEALTHY: staging index corrupt: {err}")
        else:
            print(f"  staged items: {manifest.get('staged_count')}; "
                  f"last sync: {manifest.get('generated_at_utc')}")
    else:
        print("  no staging yet (channel reader not run)")
    print("== bridge ==")
    # Lock age (P1-41): a lock held >1h is an alert; the probe reads
    # without truncating (bridge_lib.lock_status opens append-only).
    print(f"  send lock: {lock_status()}")
    try:
        age = (datetime.now(timezone.utc).timestamp()
               - LOCK_FILE.stat().st_mtime)
        if lock_status() == "held" and age > 3600:
            print(f"  ALERT: send lock held for {age / 3600:.1f}h")
    except Exception:
        pass
    for name in ("publish-report.json", "dryrun-report.json"):
        rep_file = APP_ROOT / "bridge" / name
        if rep_file.exists():
            rep, err = _read_json_safe(rep_file)
            if err:
                print(f"  UNHEALTHY: {name} corrupt: {err}")
            else:
                print(f"  {name} live_sends: {rep.get('live_sends')} "
                      f"(source: {rep.get('source', 'legacy')})")
    # FCM-topic readiness flag (M7): dormant by the recorded
    # decision — no Firebase for the vNext pilot, so the flag is
    # honestly "dormant", not "ready".
    print("  FCM topic: dormant (decision: no Firebase for vNext pilot)")
    print("== invites ==")
    inv = con.execute(
        "SELECT state, COUNT(*) FROM invites GROUP BY state").fetchall()
    print("  " + (", ".join(f"{s}: {n}" for s, n in inv) or "(none)"))
    return 0


# ---------------------------------------------------------------- invites

def cmd_invite_generate(con, args):
    # M7: generation loops on collision (never "re-run" as an
    # error), and labels are unique — a duplicate label used to
    # make `revoke --label` ambiguous later (P2-15 / §2.5).
    if con.execute("SELECT 1 FROM invites WHERE label = ?",
                   (args.label,)).fetchone():
        print(f"REFUSED: label '{args.label}' already exists", file=sys.stderr)
        return 1
    for _ in range(100):
        code = generate_code()
        digest = hash_code(code)
        if not con.execute("SELECT 1 FROM invites WHERE code_hash = ?",
                           (digest,)).fetchone():
            break
    else:
        print("REFUSED: could not generate a unique code", file=sys.stderr)
        return 1
    con.execute(
        "INSERT INTO invites (code_hash, label, state, created_at)"
        " VALUES (?,?, 'unused', ?)",
        (digest, args.label, now_iso()))
    con.commit()
    print(f"code (show ONCE, hand to the reader out-of-band): {code}")
    print(f"label: {args.label}  state: unused")
    return 0


# --- M7: edge invite publishing + lab retirement (hashes only) ---

PILOT_FILE = APP_ROOT / "docs" / "pilot-invite-codes-private.md"
EDGE_INVITES_FILE = EDGE_OUT / "invite_hashes.json"
EDGE_REVOCATIONS_FILE = EDGE_OUT / "revocations.json"


def _pilot_hashes():
    """Hashes of the authoritative pilot file, or (ok, reason).

    The file itself is lab-only and gitignored; this function reads
    it only to hash its codes and never prints a code or a hash.
    Returns (hashes, None) or ([], reason)."""
    if not PILOT_FILE.exists():
        return [], "pilot invite file missing (lab-only, gitignored)"
    import re as _re
    text = PILOT_FILE.read_text(encoding="utf-8")
    codes = _re.findall(r"EJ-[A-Z0-9-]+", text)
    unique = sorted(set(codes))
    if not unique:
        return [], "no codes found in pilot file"
    return sorted(hash_code(c) for c in unique), None


def cmd_invite_publish(con, args):
    """Publish the edge invite list from the pilot file (M7).

    Writes edge/out/invite_hashes.json (hashes only, sorted) and
    creates edge/out/revocations.json if missing (empty list at
    launch, per the recorded decision). It never overwrites an
    existing revocations file — revocations are appended by
    `invites revoke`, not regenerated from the lab queue. The
    queue DB is NOT the source: its rows are lab/test data.
    """
    hashes, err = _pilot_hashes()
    if err:
        print(f"REFUSED: {err}", file=sys.stderr)
        return 1
    EDGE_INVITES_FILE.write_text(json.dumps(
        {"version": 1, "generated_at_utc": now_iso(),
         "hashes": hashes}, ensure_ascii=False, indent=1), encoding="utf-8")
    if not EDGE_REVOCATIONS_FILE.exists():
        EDGE_REVOCATIONS_FILE.write_text(json.dumps(
            {"version": 1, "generated_at_utc": now_iso(),
             "hashes": []}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"published {len(hashes)} invite hashes to edge/out/invite_hashes.json "
          "(hashes only; no code printed, logged, or committed)")
    return 0


def cmd_invite_retire_lab(con, args):
    """Retire all lab queue invite rows (M7 reconciliation).

    The 20 lab rows (duplicate pilot labels, unused + revoked) are
    lab/test data, not a reader list. They are marked 'retired'
    with a note — not deleted, so the retirement is auditable —
    and the pilot file becomes the sole authority. This command
    touches only the lab queue DB.
    """
    n = con.execute("SELECT COUNT(*) FROM invites WHERE state != 'retired'"
                    ).fetchone()[0]
    con.execute("UPDATE invites SET state = 'retired',"
                " note = 'retired at M7: lab/test data, pilot file authoritative'"
                " WHERE state != 'retired'")
    con.commit()
    print(f"retired {n} lab invite rows (pilot file is authoritative)")
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
        print("OK: operator verified (this is NOT a reader check-in — "
              "no app check-in path exists)")
    else:
        con.execute("UPDATE invites SET last_seen_at=? WHERE code_hash = ?",
                    (ts, digest))
        print("OK: operator verified (this is NOT a reader check-in)")
    con.commit()
    return 0


def _append_revocation(digest):
    """Append a hash to the edge revocations file (hashes only)."""
    revocations = []
    if EDGE_REVOCATIONS_FILE.exists():
        try:
            revocations = json.loads(
                EDGE_REVOCATIONS_FILE.read_text(encoding="utf-8")).get("hashes", [])
        except Exception:
            revocations = []
    if digest not in revocations:
        revocations.append(digest)
        EDGE_REVOCATIONS_FILE.write_text(json.dumps(
            {"version": 1, "generated_at_utc": now_iso(),
             "hashes": sorted(revocations)}, ensure_ascii=False, indent=1),
            encoding="utf-8")


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
        _append_revocation(digest)
        print(f"revoked code for label '{row[0]}' "
              "(hash appended to edge revocations)")
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
    # M7: revocation is published to the edge (hash only) so the
    # app's sync-time re-check can re-lock the feed. A revoke that
    # only touched the lab queue would never reach a reader.
    _append_revocation(rows[0][0])
    print(f"revoked code for label '{args.label}' "
          "(hash appended to edge revocations)")
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
    rc = sub.add_parser("reconcile"); rc.add_argument("key")
    rc.add_argument("--action", required=True, choices=["retry", "hold"])
    rc.set_defaults(fn=cmd_reconcile)
    pr = sub.add_parser("promote"); pr.add_argument("staging_id")
    pr.set_defaults(fn=cmd_promote)

    i = sub.add_parser("invites"); isub = i.add_subparsers(dest="icmd", required=True)
    g = isub.add_parser("generate"); g.add_argument("--label", required=True)
    g.set_defaults(fn=cmd_invite_generate)
    pub = isub.add_parser("publish"); pub.set_defaults(fn=cmd_invite_publish)
    ret = isub.add_parser("retire-lab"); ret.set_defaults(fn=cmd_invite_retire_lab)
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
