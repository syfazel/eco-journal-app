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
    # M7: "sending" is the transient crash-recovery state. The
    # worker marks published_app -> sending BEFORE the first
    # network call, and sending -> published_channel only together
    # with a recorded sent row. A sending item is NEVER auto
    # re-sent by a later pass: it stays in sending until the
    # operator reconciles it (control.py "reconcile"), because a
    # crash between a successful send and its record leaves it
    # genuinely unknown whether Telegram already delivered it.
    "published_app": ["sending"],
    "sending": ["published_channel", "published_app"],
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
    """Exact channel text for an article: title, body, attribution.

    M7 (P2-13): the original URL travels with the post when the
    article carries one, instead of being silently dropped. It is
    appended after the attribution, so the numbered-split logic
    keeps it on the last part.
    """
    blocks = article.get("body_blocks") or []
    body = "\n\n".join(b for b in blocks if b and b.strip())
    text = f"{article.get('title', '')}\n\n{body}\n\nمنبع: {article.get('source', '')}"
    url = (article.get("original_url") or "").strip()
    if url:
        text += f"\n{url}"
    return text


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


def numbered_split_parts(text: str):
    """Split + number parts for a >4096-char item (M7, P1-34).

    Returns a list like ["(1/3) …", "(2/3) …", "(3/3) …"] with every
    part <= TELEGRAM_CAP, or None when a single paragraph exceeds
    the cap even after reserving the numbering prefix (that case
    still needs a human edit). Numbering is applied AFTER the
    paragraph split, with the split re-run at a reduced cap so the
    prefix can never push a part over 4096. Title stays on part 1
    and attribution (plus original URL) on the last part, because
    both are already part of the formatted text's first/last
    paragraphs.
    """
    if len(text) <= TELEGRAM_CAP:
        return [text]
    raw = split_channel_text(text)
    if raw is None:
        return None
    n = len(raw)
    # Worst-case prefix for this part count, e.g. "(12/12) " = 8.
    prefix_len = len(f"({n}/{n}) ") + 2
    budget = TELEGRAM_CAP - prefix_len
    if budget <= 0:
        return None
    # Re-split at the reduced budget (paragraph boundaries only).
    paragraphs = [p for p in text.split("\n\n")]
    if any(len(p) > budget for p in paragraphs):
        # A paragraph fits the raw cap but not the numbered budget:
        # it can still be sent as its own part if the prefix fits.
        if any(len(p) + prefix_len > TELEGRAM_CAP for p in paragraphs):
            return None
    parts, current = [], ""
    for para in paragraphs:
        candidate = para if not current else current + "\n\n" + para
        if len(candidate) <= budget:
            current = candidate
        else:
            parts.append(current)
            current = para
    if current:
        parts.append(current)
    total = len(parts)
    numbered = [f"({i + 1}/{total}) {p}" for i, p in enumerate(parts)]
    if any(len(p) > TELEGRAM_CAP for p in numbered):
        return None
    return numbered


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
    """'free' or 'held' — probed with a non-blocking acquire.

    M7 (P2-15): opened in append mode, never "w" — a read-only
    health probe must not truncate the lock file it is inspecting.
    """
    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    handle = open(LOCK_FILE, "a")
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
    # M7: edits received AFTER a send are never a silent overwrite
    # of the queued payload. They land here as edit records; the
    # operator decides whether a correction post is warranted.
    con.execute("""CREATE TABLE IF NOT EXISTS edits (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        key TEXT NOT NULL,
        channel_message_id INTEGER,
        payload TEXT NOT NULL,
        received_at TEXT NOT NULL
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
    # M7 (P2-11): column names come from the cursor description,
    # never from a hardcoded order — a future schema change must not
    # silently misalign fields.
    cur = con.execute("SELECT * FROM items WHERE key = ?", (key,))
    row = cur.fetchone()
    if not row:
        return None
    cols = [d[0] for d in cur.description]
    return dict(zip(cols, row))


def update_item_payload(con, key: str, article: dict, edit_note: str = ""):
    """Replace a queued item's payload with an edited version.

    Only for items not yet sent: the status is untouched, the edit
    is appended to the note (never a silent overwrite), and the
    title/source columns follow the new payload. Returns
    (ok, reason). Callers handling already-sent items must use
    record_edit() instead.
    """
    item = get_item(con, key)
    if not item:
        return False, f"unknown key {key}"
    if item["status"] in ("published_channel", "sending"):
        return False, (f"item {key} is {item['status']}; an edit to a "
                       "sent/sending item must be an edit record, not a payload overwrite")
    note = item["note"] or ""
    if edit_note:
        note = (note + "\n" + edit_note).strip()
    con.execute(
        "UPDATE items SET title = ?, source = ?, payload = ?, note = ?,"
        " updated_at = ? WHERE key = ?",
        (article.get("title", item["title"]), article.get("source", item["source"]),
         json.dumps(article, ensure_ascii=False), note, now_iso(), key),
    )
    con.commit()
    return True, f"payload updated for {key} (status {item['status']} unchanged)"


def record_edit(con, key: str, channel_message_id, article: dict):
    """Store an edit to an already-sent item as an edit record."""
    con.execute(
        "INSERT INTO edits (key, channel_message_id, payload, received_at)"
        " VALUES (?,?,?,?)",
        (key, channel_message_id, json.dumps(article, ensure_ascii=False), now_iso()),
    )
    set_note(con, key, "edit received after send — operator decides on a correction post")
    con.commit()
    return True, f"edit recorded for {key}"


def set_status(con, key: str, target: str, note: str = ""):
    """Advance one item through the machine; illegal jumps refused."""
    item = get_item(con, key)
    if not item:
        return False, f"unknown key {key}"
    ok, why = transition(item["status"], target)
    if not ok:
        return False, why
    new_note = _bounded_note(item["note"], note)
    con.execute(
        "UPDATE items SET status = ?, note = ?, updated_at = ? WHERE key = ?",
        (target, new_note, now_iso(), key),
    )
    con.commit()
    return True, why


# M7 (P2-11): notes are bounded — a blocked item re-checked on
# every worker pass used to append a "blocked: …" note forever.
MAX_NOTE_CHARS = 4000


def _bounded_note(existing: str, note: str) -> str:
    combined = (existing + "\n" + note).strip() if note else existing
    if len(combined) > MAX_NOTE_CHARS:
        return "…(earlier notes truncated)\n" + combined[-MAX_NOTE_CHARS:]
    return combined


def set_note(con, key: str, note: str):
    item = get_item(con, key)
    if not item:
        return False, f"unknown key {key}"
    new_note = _bounded_note(item["note"], note)
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
    """Record a real send, atomically with the status flip.

    M7 (P1-35): duplicate keys are a no-op with a warning, never an
    uncaught IntegrityError crashing the run and leaving the report
    unwritten. ALL message_ids are stored (split sends), not just
    the first; the item's channel_message_id is the first part's id.
    Returns (ok, reason). The caller must already hold the item in
    "sending" (or the transition is refused by set_status rules
    enforced here via a status check).
    """
    if already_sent(con, key):
        return False, f"duplicate sent record for {key} — ignored, nothing overwritten"
    item = get_item(con, key)
    if item is not None and item["status"] not in ("sending", "published_app"):
        return False, (f"record_sent refused: {key} is {item['status']}, "
                       "expected sending (or published_app on the legacy path)")
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
    return True, f"recorded {len(message_ids)} message id(s) for {key}"


# ------------------------------------------------- M7: allow-list + cadence

def channel_allowlist():
    """Allowed channel ids for live sends, from env or lab file.

    Env BRIDGE_CHANNEL_ALLOWLIST is a comma-separated list; the lab
    file bridge/channel_allowlist.json is {"channels": [...]}. An
    EMPTY allow-list means NO live channel is authorised — live
    sends fail closed (P1-36). BRIDGE_LIVE=1 alone never authorises
    a channel, and a DM id or an unlisted id is refused by name.
    """
    env = os.environ.get("BRIDGE_CHANNEL_ALLOWLIST", "").strip()
    if env:
        return [c.strip() for c in env.split(",") if c.strip()]
    allow_file = BRIDGE_DIR / "channel_allowlist.json"
    if allow_file.exists():
        try:
            data = json.loads(allow_file.read_text(encoding="utf-8"))
            return [str(c) for c in data.get("channels", [])]
        except Exception:
            return []
    return []


def check_channel_id(chat_id) -> tuple:
    """Return (ok, reason) for a live-send destination id."""
    allowed = channel_allowlist()
    if not allowed:
        return False, "no channel allow-list configured (refusing live send)"
    if str(chat_id) not in allowed:
        return False, f"channel id {chat_id} is not in the allow-list"
    return True, f"channel id {chat_id} is allow-listed"


def tehran_today() -> str:
    """Current date in Asia/Tehran, for the 2/day cadence rule."""
    try:
        from zoneinfo import ZoneInfo
        tz = ZoneInfo("Asia/Tehran")
    except Exception:
        tz = timezone.utc
    return datetime.now(tz).date().isoformat()


def sent_today_count(con) -> int:
    """Sent rows whose sent_at falls on today's Tehran date.

    sent_at is stored as UTC ISO; a row counts for the Tehran date
    it maps to, not the raw UTC date.
    """
    try:
        from zoneinfo import ZoneInfo
        tz = ZoneInfo("Asia/Tehran")
    except Exception:
        tz = timezone.utc
    today = tehran_today()
    count = 0
    for (sent_at,) in con.execute("SELECT sent_at FROM sent").fetchall():
        try:
            dt = datetime.fromisoformat(sent_at.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            if dt.astimezone(tz).date().isoformat() == today:
                count += 1
        except Exception:
            continue
    return count


def cadence_ok(con, limit: int = 2) -> tuple:
    """2/day cadence (P1-40): True while today's sent count < limit."""
    n = sent_today_count(con)
    if n >= limit:
        return False, f"cadence: {n}/{limit} sends already today (Tehran date)"
    return True, f"cadence: {n}/{limit} sends today"


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
    # M7 (P1-36): the destination must be allow-listed, not merely
    # non-empty. An unlisted id (including a DM id or the live
    # channel before any explicit live-channel decision) fails here.
    if os.environ.get("BRIDGE_CHANNEL_ID"):
        ok, why = check_channel_id(os.environ["BRIDGE_CHANNEL_ID"])
        if not ok:
            reasons.append(why)
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
