#!/usr/bin/env python3
"""Shared bridge library — ECO JOURNAL app (LAB ONLY).

Canonical implementations for the Telegram bridge, used by
channel_reader.py, publish_worker.py and control.py so the status
machine, the channel formatter, the cap validator and the send lock
cannot drift apart. The Phase 1 harness (dryrun.py) keeps its own
copies and is intentionally NOT modified here.

Hard rules (same as the specs in docs/):
  * The live channel is FROZEN. The write direction defaults to
    dry-run. A live send requires ALL of: an explicit --live flag,
    env BRIDGE_LIVE=1, env BRIDGE_CHANNEL_ID (a TEST channel id —
    the live channel id is never hardcoded here), and env
    ECO_BOT_TOKEN. Setting BRIDGE_LIVE=1 is the operator asserting
    that Yasin's reopen order exists; without it the worker refuses.
  * The token is read ONLY from the environment, at send time, and
    is never printed, logged, or embedded anywhere.
  * Bot posts to the channel ONLY, never DMs to readers (law 14).
  * Oversize text is NEVER silently truncated: it is flagged for a
    splitting decision (split_channel_text) or a manual edit.
  * The only proof of posting is a message_id returned by Telegram,
    recorded in the lab queue DB (bridge/queue.db) — never a counter
    or a log line saying "sent".
  * No network is used by this module except send_via_bot_api, which
    is called only on the live path. All tests use mocks/fixtures.
"""

import fcntl
import hashlib
import json
import os
import sqlite3
import urllib.request
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parent
APP_ROOT = BRIDGE_DIR.parent
EDGE_OUT = APP_ROOT / "edge" / "out"
DEFAULT_QUEUE_DB = BRIDGE_DIR / "queue.db"
DEFAULT_STAGING_DIR = BRIDGE_DIR / "staging"
LOCK_FILE = BRIDGE_DIR / "send.lock"

TELEGRAM_CAP = 4096
PLACEHOLDER_MARKERS = ("TODO", "Lorem", "XXX", "متن نمونه", "[...]")

# Status machine (docs/telegram-bridge-spec.md, direction 2), plus one
# Phase 2 extension: review -> rejected. The control-plane spec
# requires a reject action; rejection is terminal for that item
# version (a corrected version re-enters as a new draft). "Hold" is
# not a state: it appends a note and the item stays in review.
LEGAL = {
    "draft": ["review"],
    "review": ["approved", "rejected"],
    "approved": ["queued"],
    "queued": ["published_app"],
    "published_app": ["published_channel"],
    "published_channel": [],
    "rejected": [],
}

STATUSES = list(LEGAL.keys())


class LockHeld(Exception):
    """Non-blocking send lock is held by another process."""


def transition(status: str, target: str):
    """Return (ok, reason) for one status-machine step."""
    allowed = LEGAL.get(status, [])
    if target in allowed:
        return True, f"{status} -> {target}"
    return False, f"illegal transition {status} -> {target}"


def format_channel(article: dict) -> str:
    """Exact channel text for an article: title, body, attribution."""
    blocks = article.get("body_blocks") or []
    body = "\n\n".join(b for b in blocks if b and b.strip())
    return f"{article.get('title', '')}\n\n{body}\n\nمنبع: {article.get('source', '')}"


def validate_channel_text(text: str, article: dict):
    """Return (ok, reason). Failures are never silently truncated."""
    if not article.get("title", "").strip():
        return False, "empty title"
    if not any((b or "").strip() for b in (article.get("body_blocks") or [])):
        return False, "empty body"
    if not article.get("source", "").strip():
        return False, "missing source attribution"
    for marker in PLACEHOLDER_MARKERS:
        if marker in text:
            return False, f"placeholder marker present: {marker}"
    if len(text) > TELEGRAM_CAP:
        parts = split_channel_text(text)
        hint = (
            f"split into {len(parts)} parts at paragraph boundaries"
            if parts
            else "a single paragraph exceeds the cap; needs a manual edit"
        )
        return False, (
            f"over Telegram cap: {len(text)} chars "
            f"(cap {TELEGRAM_CAP}, excess {len(text) - TELEGRAM_CAP}); "
            f"{hint} — never silent truncation"
        )
    return True, f"valid, {len(text)} chars"


def split_channel_text(text: str):
    """Split channel text at paragraph boundaries into <=cap parts.

    Title stays on part 1, attribution on the last part (both are
    part of the formatted text already, so splitting on blank lines
    keeps them in place naturally). Returns None if any single
    paragraph exceeds the cap — that case needs a human edit, not an
    automatic cut mid-sentence.
    """
    paragraphs = [p for p in text.split("\n\n")]
    if any(len(p) > TELEGRAM_CAP for p in paragraphs):
        return None
    parts, current = [], ""
    for para in paragraphs:
        candidate = para if not current else current + "\n\n" + para
        if len(candidate) <= TELEGRAM_CAP:
            current = candidate
        else:
            parts.append(current)
            current = para
    if current:
        parts.append(current)
    return parts


@contextmanager
def send_lock():
    """Non-blocking flock around select-then-send.

    The 2026-10-23 08:00 lesson: two overlapping flushes double-posted
    17 stories because there was no lock. A concurrent sender gets
    LockHeld and exits cleanly instead of sending. flock auto-releases
    when the holding process dies, so a lock can never get "stuck"
    after a crash — the health check reports "held by a live process"
    vs "free" on that basis.
    """
    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    handle = open(LOCK_FILE, "w")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        handle.close()
        raise LockHeld("another send is running")
    try:
        yield
    finally:
        try:
            fcntl.flock(handle, fcntl.LOCK_UN)
        finally:
            handle.close()


def lock_status() -> str:
    """'free' or 'held' — probed with a non-blocking acquire."""
    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    handle = open(LOCK_FILE, "w")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(handle, fcntl.LOCK_UN)
        return "free"
    except BlockingIOError:
        return "held"
    finally:
        handle.close()


# ---------------------------------------------------------------- DB

def queue_db_path(explicit=None) -> Path:
    if explicit:
        return Path(explicit)
    env = os.environ.get("BRIDGE_QUEUE_DB")
    return Path(env) if env else DEFAULT_QUEUE_DB


def init_db(path) -> sqlite3.Connection:
    """Create the LAB queue DB (never a production DB)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(path))
    con.execute("PRAGMA busy_timeout=30000")
    con.execute("""CREATE TABLE IF NOT EXISTS items (
        key TEXT PRIMARY KEY,
        article_id TEXT NOT NULL,
        title TEXT NOT NULL,
        source TEXT NOT NULL,
        status TEXT NOT NULL,
        payload TEXT NOT NULL,
        note TEXT DEFAULT '',
        split_plan TEXT,
        channel_message_id INTEGER,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS sent (
        key TEXT PRIMARY KEY,
        message_ids TEXT NOT NULL,
        channel_configured INTEGER NOT NULL DEFAULT 0,
        sent_at TEXT NOT NULL
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS invites (
        code_hash TEXT PRIMARY KEY,
        label TEXT NOT NULL,
        state TEXT NOT NULL,
        created_at TEXT NOT NULL,
        first_seen_at TEXT,
        last_seen_at TEXT,
        note TEXT DEFAULT ''
    )""")
    con.commit()
    return con


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def enqueue_item(con, key: str, article: dict, status: str = "draft"):
    """Insert an item; duplicate keys are refused (idempotency)."""
    ts = now_iso()
    try:
        con.execute(
            "INSERT INTO items (key, article_id, title, source, status,"
            " payload, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
            (key, article.get("id", ""), article.get("title", ""),
             article.get("source", ""), status,
             json.dumps(article, ensure_ascii=False), ts, ts),
        )
        con.commit()
    except sqlite3.IntegrityError:
        return False, f"duplicate key {key} — refused, nothing overwritten"
    return True, f"enqueued {key} at {status}"


def get_item(con, key: str):
    row = con.execute("SELECT * FROM items WHERE key = ?", (key,)).fetchone()
    if not row:
        return None
    # Fixed column order from the schema above.
    cols = ["key", "article_id", "title", "source", "status", "payload",
            "note", "split_plan", "channel_message_id", "created_at",
            "updated_at"]
    return dict(zip(cols, row))


def set_status(con, key: str, target: str, note: str = ""):
    """Advance one item through the machine; illegal jumps refused."""
    item = get_item(con, key)
    if not item:
        return False, f"unknown key {key}"
    ok, why = transition(item["status"], target)
    if not ok:
        return False, why
    new_note = (item["note"] + "\n" + note).strip() if note else item["note"]
    con.execute(
        "UPDATE items SET status = ?, note = ?, updated_at = ? WHERE key = ?",
        (target, new_note, now_iso(), key),
    )
    con.commit()
    return True, why


def set_note(con, key: str, note: str):
    item = get_item(con, key)
    if not item:
        return False, f"unknown key {key}"
    new_note = (item["note"] + "\n" + note).strip() if note else item["note"]
    con.execute(
        "UPDATE items SET note = ?, updated_at = ? WHERE key = ?",
        (new_note, now_iso(), key),
    )
    con.commit()
    return True, "note recorded"


def already_sent(con, key: str) -> bool:
    return con.execute(
        "SELECT 1 FROM sent WHERE key = ?", (key,)).fetchone() is not None


def record_sent(con, key: str, message_ids):
    """Record a real send. Only called with message_ids from Telegram."""
    con.execute(
        "INSERT INTO sent (key, message_ids, channel_configured, sent_at)"
        " VALUES (?,?,1,?)",
        (key, json.dumps(message_ids), now_iso()),
    )
    con.execute(
        "UPDATE items SET status = 'published_channel',"
        " channel_message_id = ?, updated_at = ? WHERE key = ?",
        (message_ids[0] if message_ids else None, now_iso(), key),
    )
    con.commit()


# ------------------------------------------------------- live gating

def check_live_gates(live_flag: bool):
    """All live-send gates. Returns (ok, [reasons]).

    Refuses unless: --live was passed, BRIDGE_LIVE=1,
    BRIDGE_CHANNEL_ID is set (test channel), and ECO_BOT_TOKEN is
    present. The token VALUE is never returned or logged — only its
    presence is reported.
    """
    reasons = []
    if not live_flag:
        reasons.append("no --live flag (dry-run is the default)")
    if os.environ.get("BRIDGE_LIVE") != "1":
        reasons.append("BRIDGE_LIVE != 1")
    if not os.environ.get("BRIDGE_CHANNEL_ID"):
        reasons.append("BRIDGE_CHANNEL_ID not set (test channel required)")
    if not os.environ.get("ECO_BOT_TOKEN"):
        reasons.append("ECO_BOT_TOKEN not set (fail closed)")
    return (not reasons), reasons


def send_via_bot_api(text: str, chat_id: str):
    """Real Bot API send. Called ONLY on the fully-gated live path.

    Uses urllib (proxy from environment, as Telegram from this server
    goes through the egress proxy). Raises on any non-ok response.
    The token never appears in any log line here — it is used only
    inside the request URL.
    """
    import urllib.error
    token = os.environ.get("ECO_BOT_TOKEN", "")
    if not token:
        raise RuntimeError("ECO_BOT_TOKEN not set (fail closed)")
    body = json.dumps({
        "chat_id": chat_id,
        "text": text,
        "disable_web_page_preview": True,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=body, headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Telegram HTTP {e.code}") from e
    if not data.get("ok"):
        raise RuntimeError(f"Telegram error: {data.get('description', '?')}")
    return data["result"]["message_id"]
