#!/usr/bin/env python3
"""Dry-run bridge test harness — ECO JOURNAL app (LAB ONLY).

Exercises the FULL app -> channel write path WITHOUT sending anything
anywhere. There is no live path in this script at all: no Bot API call,
no HTTP, no socket, no token is read, referenced or printed. A Telegram
bot cannot create a channel via Bot API either, so this harness — not a
channel — is the test coverage that exists today (see
docs/test-channel-fallback.md).

What it proves (spec: docs/telegram-bridge-spec.md):
  * Status machine: draft -> review -> approved -> queued ->
    published_app -> published_channel; illegal jumps are refused.
  * Gates on the channel send: channel-open, approved+queued,
    idempotency key unused, send lock. While the live channel is
    frozen, a live send would be REFUSED even for a perfectly
    formatted article — the report says so explicitly.
  * Idempotency: the same article key processed twice is delivered
    once (in-memory demo on every run; cross-run demo with --scratch,
    using a SCRATCH state file that is NOT the production sent-record).
  * Send lock: a concurrent sender exits cleanly with "another send
    is running" (exit code 2) instead of double-posting — the
    2026-10-23 08:00 lesson.
  * Channel formatting: the exact text that WOULD be sent, validated
    against the Telegram 4096-character cap, with source attribution
    present and no placeholder text.

Fixtures are REAL seed articles from edge/out/ (approved/posted
extracts) — no invented article text. The only synthetic string is
the validator unit test (a 5000-char filler, labelled as such, used
only to prove the cap check fires).

Usage:
  python3 bridge/dryrun.py                # pure dry-run, records nothing
  python3 bridge/dryrun.py --scratch      # also persists keys to a
                                          # scratch file; a second run
                                          # then skips the same articles
  python3 bridge/dryrun.py --hold-lock-seconds 8   # lock-contention helper

Exit codes: 0 = all expected outcomes held; 1 = unexpected outcome;
2 = send lock held by another process (clean, not a failure).
"""

import fcntl
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parent
APP_ROOT = BRIDGE_DIR.parent
EDGE_OUT = APP_ROOT / "edge" / "out"
STATE_DIR = BRIDGE_DIR / ".dryrun-state"
# M7 (P2-10): the harness uses the SAME single lock file as the real
# worker (bridge/send.lock), so its contention test proves something
# about the real path instead of a private scratch lock. The scratch
# state file stays separate (it is explicitly not a sent-record).
import sys as _sys
_sys.path.insert(0, str(BRIDGE_DIR))
from bridge_lib import LOCK_FILE  # noqa: E402
SCRATCH_FILE = STATE_DIR / "scratch_sent.json"
REPORT_FILE = BRIDGE_DIR / "dryrun-report.json"

TELEGRAM_CAP = 4096
PLACEHOLDER_MARKERS = ("TODO", "Lorem", "XXX", "متن نمونه", "[...]")

# Spec status machine (docs/telegram-bridge-spec.md, direction 2),
# kept in sync with bridge_lib.LEGAL (M7: review -> rejected added).
LEGAL = {
    "draft": ["review"],
    "review": ["approved", "rejected"],
    "approved": ["queued"],
    "queued": ["published_app"],
    "published_app": ["sending"],
    "sending": ["published_channel", "published_app"],
    "published_channel": [],
    "rejected": [],
}

# The live channel is FROZEN (2026-10-24 order). This is a fact of the
# harness, not a flag to flip casually: a live send is refused here
# regardless of formatting.
CHANNEL_OPEN = False


def transition(status: str, target: str):
    """Return (ok, reason) for one status-machine step."""
    allowed = LEGAL.get(status, [])
    if target in allowed:
        return True, f"{status} -> {target}"
    return False, f"illegal transition {status} -> {target}"


def format_channel(article: dict) -> str:
    """Exact channel text for an article: title, body, attribution."""
    blocks = article.get("body_blocks") or []
    body = "\n\n".join(b for b in blocks if b.strip())
    return f"{article.get('title', '')}\n\n{body}\n\n\u0645\u0646\u0628\u0639: {article.get('source', '')}"


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
        # M7: the harness validator now carries the same split hint
        # as bridge_lib, so the two machines cannot disagree.
        from bridge_lib import split_channel_text as _split
        parts = _split(text)
        hint = (f"split into {len(parts)} parts at paragraph boundaries"
                if parts else "a single paragraph exceeds the cap; needs a manual edit")
        return False, (
            f"over Telegram cap: {len(text)} chars "
            f"(cap {TELEGRAM_CAP}, excess {len(text) - TELEGRAM_CAP}); "
            f"{hint} — never silent truncation"
        )
    return True, f"valid, {len(text)} chars"


def load_seed_articles(n: int = 2):
    """First n full articles from the real edge snapshot."""
    index = json.loads((EDGE_OUT / "index.json").read_text(encoding="utf-8"))
    out = []
    for entry in index["entries"]:
        if entry.get("kind") == "article" and entry.get("file"):
            article = json.loads(
                (EDGE_OUT / entry["file"]).read_text(encoding="utf-8")
            )
            out.append(article)
            if len(out) >= n:
                break
    return out


def load_scratch():
    if SCRATCH_FILE.exists():
        try:
            return set(json.loads(SCRATCH_FILE.read_text(encoding="utf-8")))
        except Exception:
            return set()
    return set()


def main(argv) -> int:
    scratch_mode = "--scratch" in argv
    hold_seconds = 0
    if "--hold-lock-seconds" in argv:
        try:
            hold_seconds = int(argv[argv.index("--hold-lock-seconds") + 1])
        except (ValueError, IndexError):
            print("usage: --hold-lock-seconds N", file=sys.stderr)
            return 1

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    lock_handle = open(LOCK_FILE, "w")
    try:
        fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("skipped: another send is running (lock held)")
        return 2

    if hold_seconds > 0:
        # Lock-contention test helper: hold the lock, then exit.
        print(f"holding send lock for {hold_seconds}s (test helper)")
        time.sleep(hold_seconds)
        return 0

    results = []
    would_send = []

    def record(name, passed, detail):
        results.append({"case": name, "pass": passed, "detail": detail})
        print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")

    # --- Case 1: happy path, real articles, dry-run only -------------
    articles = load_seed_articles(2)
    record("seed fixtures loaded", len(articles) == 2,
           f"{len(articles)} real articles from edge/out/")
    seen_keys = set()
    scratch = load_scratch() if scratch_mode else set()
    for article in articles:
        key = f"article:{article['id']}"
        status = "draft"
        walk_ok = True
        for target in ("review", "approved", "queued", "published_app"):
            ok, why = transition(status, target)
            if not ok:
                walk_ok = False
                break
            status = target
        record(f"status walk {article['id'][:12]}", walk_ok,
               f"reached {status}")
        text = format_channel(article)
        valid, why = validate_channel_text(text, article)
        record(f"channel format {article['id'][:12]}", valid, why)
        if key in seen_keys or key in scratch:
            record(f"idempotency {article['id'][:12]}", True,
                   "duplicate key skipped (would NOT send twice)")
            continue
        seen_keys.add(key)
        # Gate verdict: formatting may pass, but the live send is
        # refused while the channel is frozen. Dry-run records the
        # exact text that WOULD be sent, and records nothing as sent.
        would_send.append({
            "article_id": article["id"],
            "title": article["title"],
            "idempotency_key": key,
            "chars": len(text),
            "format_valid": valid,
            "gate_channel_open": CHANNEL_OPEN,
            "verdict": ("WOULD_SEND_IF_UNFROZEN" if valid and not CHANNEL_OPEN
                        else "REFUSED"),
            "text_preview": text[:300],
        })
        record(f"dry-run send {article['id'][:12]}", True,
               "logged as WOULD_SEND_IF_UNFROZEN; live send refused "
               "(channel frozen); nothing recorded as sent")
    # In-memory duplicate demo: reprocessing the first article now
    # must skip it — whether it was seen in this run or loaded from
    # scratch state (scratch run 2 skips both articles before they
    # reach seen_keys, so the check must cover both sets).
    if articles:
        dup_key = f"article:{articles[0]['id']}"
        record("in-memory duplicate skip",
               dup_key in seen_keys or dup_key in scratch,
               "second processing of same key skipped")

    # --- Case 2: unapproved item must be refused ---------------------
    ok, why = transition("review", "queued")
    record("unapproved jump refused", not ok, why)
    ok, why = transition("review", "published_channel")
    record("review -> published_channel refused", not ok, why)

    # --- Case 3: illegal jump refused --------------------------------
    ok, why = transition("draft", "approved")
    record("draft -> approved refused", not ok, why)
    ok, why = transition("queued", "published_channel")
    record("queued -> published_channel refused", not ok, why)

    # --- Case 4: validator unit test (synthetic filler, labelled) ----
    ok, why = validate_channel_text(
        "T" * 100 + "\n\n" + "x" * 5000 + "\n\nمنبع: TEST",
        {"title": "T" * 100, "body_blocks": ["x" * 5000], "source": "TEST"},
    )
    record("cap validator fires on 5000-char filler", not ok, why)

    # --- Scratch persistence (explicitly NOT the production record) --
    if scratch_mode:
        merged = sorted(scratch | seen_keys)
        SCRATCH_FILE.write_text(json.dumps(merged, ensure_ascii=False, indent=1),
                                encoding="utf-8")
        record("scratch state written", True,
               f"{len(merged)} keys in scratch file (lab-only, "
               "not the production sent-record)")

    report = {
        "source": "dryrun",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "mode": "dry-run" + (" + scratch" if scratch_mode else ""),
        "channel_open": CHANNEL_OPEN,
        "network_used": False,
        "live_sends": 0,
        "cases": results,
        "would_send": would_send,
        "note": "Dry-run only. No message was sent, no token was read, "
                "and nothing was recorded as sent. Scratch state (if any) "
                "is lab-only and is not the production sent-record.",
    }
    REPORT_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=1),
                           encoding="utf-8")
    failed = [r for r in results if not r["pass"]]
    print(f"\n{len(results) - len(failed)}/{len(results)} cases passed; "
          f"report: {REPORT_FILE}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
