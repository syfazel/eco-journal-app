#!/usr/bin/env python3
"""Channel -> app reader — ECO JOURNAL app (LAB ONLY).

Converts Telegram channel_post updates into edge-shaped article JSON
in a LAB staging dir (bridge/staging/). It never talks to Telegram
itself in normal operation: it accepts an updates JSON file (the Bot
API getUpdates shape) as input, which makes it fully testable with
zero network calls. The live polling loop exists as CODE ONLY
(poll_loop) and is never executed by main() in this phase; it also
refuses to run unless explicitly enabled by environment.

Why a seed, not a full history pull (spec, direction 1):
  The Telegram Bot API does NOT give a bot the full history of a
  channel it administers. So the staging seed does NOT come from
  "scraping channel history" — it comes from the records that
  already exist (edge/out/channel_refs.json + the editorial
  databases + the 2026-10-24 backup). This reader handles NEW posts
  arriving after activation, plus replays of saved update files.

Deduplication (three layers, all by id — never headline hash, per
the 2026-10-23 transliteration lesson):
  1. Same run: a message_id seen twice in one input file is staged once.
  2. Staging dir: bridge/staging/channel-<message_id>.json already
     exists -> skipped (edited posts update the same file instead).
  3. Edge index: an entry whose channel_message_id matches -> skipped
     (already published to readers).

Staging items are NOT edge articles yet: they enter the review queue
at `review` by default (or `approved` with --already-reviewed, only
for posts that already passed editorial review for the channel) and
reach the edge only through the control plane + edge publish.

Skipped, never invented: media-only posts (no text) are skipped with
a reason — no text is fabricated for them. An edited post replaces
its staging file with the new text; the idempotency key is unchanged.

Usage:
  python3 bridge/channel_reader.py updates.json
  python3 bridge/channel_reader.py updates.json --enqueue
  python3 bridge/channel_reader.py updates.json --already-reviewed --enqueue
  python3 bridge/channel_reader.py updates.json --staging-dir /tmp/stage

Exit codes: 0 = ok; 1 = bad input; 2 = lock held (only with --enqueue
and a concurrent writer — not used by the reader itself).
"""

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bridge_lib import (  # noqa: E402
    DEFAULT_STAGING_DIR, EDGE_OUT, enqueue_item, init_db, now_iso,
    queue_db_path,
)


def parse_updates(data):
    """Accept a list, a getUpdates response dict, or a single update."""
    if isinstance(data, dict):
        if "result" in data and isinstance(data["result"], list):
            return data["result"]
        if "update_id" in data:
            return [data]
        raise ValueError("unrecognised updates JSON shape")
    if isinstance(data, list):
        return data
    raise ValueError("updates JSON must be a list or an object")


def channel_post_of(update):
    """Return (post, edited?) for channel posts, else (None, False)."""
    for field, edited in (("channel_post", False), ("edited_channel_post", True)):
        if field in update:
            return update[field], edited
    return None, False


def edge_channel_ids():
    """message_ids already present in the published edge index."""
    index_file = EDGE_OUT / "index.json"
    if not index_file.exists():
        return set()
    try:
        index = json.loads(index_file.read_text(encoding="utf-8"))
    except Exception:
        return set()
    return {
        e.get("channel_message_id")
        for e in index.get("entries", [])
        if e.get("channel_message_id") is not None
    }


def to_staging_item(post, chat_title):
    """Build an edge-shaped staging item. Returns (item, skip_reason).

    M7 (P0-12): a photo-with-caption post carries its text in
    `caption`, not `text` — caption is now read, with a `has_media`
    flag and a note that the media itself is not republished
    (nothing invented). A text+photo post keeps the photo note.
    A post with neither text nor caption is still skipped, never
    fabricated. Dates are Tehran time (P2-14), and summaries cut
    at 300 chars carry an ellipsis instead of a mid-word cut.
    """
    message_id = post.get("message_id")
    # Caption is the channel's most common format; text is second.
    text = (post.get("text") or post.get("caption") or "").strip()
    has_media = any(k in post for k in ("photo", "video", "document", "animation"))
    if message_id is None:
        return None, "update without message_id"
    if not text:
        return None, f"message {message_id} has no text or caption (media-only) — skipped, nothing invented"
    # Title: first line; if the post is a single block, first 120 chars.
    first_line = text.split("\n", 1)[0].strip()
    title = first_line if first_line else text[:120]
    if len(title) > 120:
        title = title[:117] + "…"
    summary = text[:300] + ("…" if len(text) > 300 else "")
    date = post.get("date")
    if isinstance(date, (int, float)):
        try:
            from zoneinfo import ZoneInfo
            tz = ZoneInfo("Asia/Tehran")
        except Exception:
            tz = timezone.utc
        published_at = datetime.fromtimestamp(date, tz=tz).isoformat()
    else:
        published_at = now_iso()
    item = {
        "id": f"channel-{message_id}",
        "kind": "channel_post",
        "title": title,
        "summary": summary,
        "body_blocks": [text],
        "category": "channel",
        "category_fa": "کانال",
        "source": chat_title or "کانال",
        "original_url": "",
        "published_at": published_at,
        "channel_message_id": message_id,
        "publish_status": "staged",
        "provenance": "telegram channel_post (Bot API getUpdates input)",
        "idempotency_key": f"channel:{message_id}",
        "has_media": has_media,
    }
    if has_media:
        item["media_note"] = ("Media attached in the channel post; "
                              "the media itself is not republished, text only.")
    return item, ""


def process_updates(updates, staging_dir, already_reviewed=False,
                    enqueue=False, db_path=None, chat_id_filter=None):
    """Process updates. Returns a report dict (no network, no sends).

    M7 (P1-38, P2-14): an edited post updates the queued payload
    when the item is not yet sent (via update_item_payload), and
    creates an edit record when it is already sent — never a silent
    overwrite, never a stale queue. An edited post for a
    never-seen message is flagged as an orphan edit, not staged as
    a fresh item. The staging manifest is cumulative (all staging
    files on disk), not just this run's items. A --chat-id filter
    (main()) skips posts from other chats instead of staging any
    channel_post regardless of source.
    """
    from bridge_lib import get_item, record_edit, update_item_payload
    staging_dir = Path(staging_dir)
    staging_dir.mkdir(parents=True, exist_ok=True)
    published_ids = edge_channel_ids()
    seen_this_run = set()
    staged, updated, skipped = [], [], []
    con = init_db(queue_db_path(db_path)) if enqueue else None
    for update in updates:
        post, edited = channel_post_of(update)
        if post is None:
            skipped.append({"update_id": update.get("update_id"),
                            "reason": "not a channel post"})
            continue
        message_id = post.get("message_id")
        if message_id is None:
            # M7 (P2-14): label it for what it is, not "duplicate".
            skipped.append({"update_id": update.get("update_id"),
                            "reason": "no message_id"})
            continue
        if message_id in seen_this_run:
            skipped.append({"message_id": message_id,
                            "reason": "duplicate in this input file"})
            continue
        seen_this_run.add(message_id)
        chat = post.get("chat") or {}
        if chat_id_filter is not None and str(chat.get("id")) != str(chat_id_filter):
            skipped.append({"message_id": message_id,
                            "reason": f"chat id {chat.get('id')} does not match filter {chat_id_filter}"})
            continue
        if message_id in published_ids:
            skipped.append({"message_id": message_id,
                            "reason": "already in edge index"})
            continue
        item, why = to_staging_item(post, chat.get("title"))
        if item is None:
            skipped.append({"message_id": message_id, "reason": why})
            continue
        target = staging_dir / f"channel-{message_id}.json"
        existed = target.exists()
        if edited and not existed and not (enqueue and get_item(con, item["idempotency_key"])):
            # Orphan edit: no staging file and no queue row to update.
            skipped.append({"message_id": message_id,
                            "reason": "orphan edit: edited post for a never-seen message"})
            continue
        target.write_text(json.dumps(item, ensure_ascii=False, indent=1),
                          encoding="utf-8")
        (updated if existed else staged).append(item["id"])
        if enqueue:
            existing = get_item(con, item["idempotency_key"])
            if existing is None:
                status = "approved" if already_reviewed else "review"
                ok, msg = enqueue_item(con, item["idempotency_key"], item,
                                       status=status)
                if not ok:
                    skipped.append({"message_id": message_id,
                                    "reason": f"queue: {msg}"})
            elif edited:
                if existing["status"] in ("published_channel", "sending"):
                    record_edit(con, item["idempotency_key"], message_id, item)
                else:
                    ok, msg = update_item_payload(
                        con, item["idempotency_key"], item,
                        edit_note="edited_channel_post: payload refreshed")
                    if not ok:
                        skipped.append({"message_id": message_id,
                                        "reason": f"queue edit: {msg}"})
            # A non-edited re-run of an already-queued post is a
            # dedup hit, not an overwrite (layer 2, P1-37).
    # Cumulative manifest: every staging file on disk, not this run.
    all_staged = sorted(
        p.stem for p in staging_dir.glob("channel-*.json"))
    manifest = {
        "generated_at_utc": now_iso(),
        "staged_count": len(all_staged),
        "staged": all_staged,
    }
    (staging_dir / "index.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    if con:
        con.close()
    return {
        "staged_new": staged,
        "staged_updated": updated,
        "skipped": skipped,
        "staging_dir": str(staging_dir),
        "note": "Bot API gives no full channel history; the staging seed "
                "comes from existing records, not history scraping.",
    }


def poll_loop(get_updates_fn=None):
    """CODE ONLY — live getUpdates polling. Never called by main().

    A future operator enables it only with BRIDGE_POLL_ENABLED=1 and
    ECO_BOT_TOKEN set; it then calls get_updates_fn(offset) which
    must return Bot API update dicts, and feeds them to
    process_updates. It is present here so the Phase 2 design is
    complete and reviewable, not so it runs now.
    """
    if os.environ.get("BRIDGE_POLL_ENABLED") != "1":
        raise RuntimeError("poll_loop disabled: BRIDGE_POLL_ENABLED != 1")
    if not os.environ.get("ECO_BOT_TOKEN"):
        raise RuntimeError("poll_loop disabled: ECO_BOT_TOKEN not set")
    if get_updates_fn is None:
        raise RuntimeError("poll_loop needs a get_updates_fn (no default network call)")
    raise RuntimeError("poll_loop is code-only in this phase and is never executed")


def main(argv) -> int:
    if not argv or argv[0].startswith("-"):
        print("usage: channel_reader.py updates.json [--enqueue] "
              "[--already-reviewed] [--staging-dir DIR] [--db PATH]",
              file=sys.stderr)
        return 1
    input_file = Path(argv[0])
    already_reviewed = "--already-reviewed" in argv
    enqueue = "--enqueue" in argv
    staging_dir = DEFAULT_STAGING_DIR
    db_path = None
    chat_id_filter = None
    if "--staging-dir" in argv:
        staging_dir = Path(argv[argv.index("--staging-dir") + 1])
    if "--db" in argv:
        db_path = argv[argv.index("--db") + 1]
    if "--chat-id" in argv:
        chat_id_filter = argv[argv.index("--chat-id") + 1]
    try:
        data = json.loads(input_file.read_text(encoding="utf-8"))
        updates = parse_updates(data)
    except Exception as e:
        print(f"bad input: {e}", file=sys.stderr)
        return 1
    report = process_updates(updates, staging_dir,
                             already_reviewed=already_reviewed,
                             enqueue=enqueue, db_path=db_path,
                             chat_id_filter=chat_id_filter)
    print(f"staged new: {len(report['staged_new'])}; "
          f"updated: {len(report['staged_updated'])}; "
          f"skipped: {len(report['skipped'])}")
    for s in report["skipped"]:
        print(f"  skipped: {s}")
    print(f"staging dir: {report['staging_dir']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
