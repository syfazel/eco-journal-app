#!/usr/bin/env python3
"""Full-sync shared helpers (S1/S2/S3, 2026-10-26).

Read-only on every production store (sqlite URI mode=ro). Writes ONLY
into the edge directory given (default: edge/out next to this file).

Published-only golden rule (non-negotiable, applied by every generator
that uses this module): an item enters the edge ONLY if it is already
published to the live channel. Sources of truth:
  * channel posts: editorial/redalert.db `posted` table (one row per
    post actually sent; the `queue` table — night-held items with a
    future release_at — is NEVER read by these generators).
  * radar signals: war_events rows whose story_key appears in `posted`.
A run at night is therefore safe by construction: held queue items
cannot leak, because they are not in `posted` and no generator reads
the queue.

Hash rules (must match edge/publish_edge.py verify, which recomputes
them from the files themselves):
  * section hash: sha256 over canonical JSON of the 4-field view
    [{app_key, content_hash, updated_at_epoch, status}] sorted by
    app_key. For radar the key field is signal_id (used as app_key
    in the manifest items).
  * item content_hash: sha256 over canonical JSON of the full item
    minus content_hash itself.
  * NO chain objects are emitted: the posted table carries no
    staged_epoch / gate_decided_epoch, and inventing those timestamps
    would fail audit. publish_edge.py treats a missing chain as a
    legitimate skip (chain checks run only when chain is present).
    join_keys coverage likewise only requires CHAIN-carrying typed
    items, so no join_keys rows are added for these items.
"""

import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
EDGE_DIR = os.path.join(APP_ROOT, "edge")
OUT_DIR = os.path.join(EDGE_DIR, "out")
BLOG_ROOT = os.path.join(HOME, "workspace", "econ-news-blog")
REDALERT_DB = os.path.join(BLOG_ROOT, "editorial", "redalert.db")
WAR_EVENTS_DB = os.path.join(BLOG_ROOT, "editorial", "war_events.db")
DATA_CORE_DB = os.path.join(BLOG_ROOT, "data_core", "data_core.db")

FULLSYNC_GENERATOR = "edge/fullsync.py (S1/S2/S3, 2026-10-26)"


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def sha(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def section_hash_from_items(items4):
    return sha(sorted(items4, key=lambda d: d["app_key"]))


def content_hash(item):
    return sha({k: v for k, v in item.items() if k != "content_hash"})


def ro_connect(path):
    if not os.path.exists(path):
        raise SystemExit("missing read-only source DB: %s" % path)
    return sqlite3.connect("file:%s?mode=ro" % path, uri=True)


def epoch_ms(iso_ts):
    """ISO timestamp with +03:30 (or Z) -> epoch millis."""
    dt = datetime.fromisoformat(iso_ts.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def now_utc_iso():
    return datetime.now(timezone.utc).isoformat()


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


def guid_for(message_id):
    return "channel-%s" % message_id


def guid_for_post(message_id, story_key):
    """Guid for a posted row. The 19 earliest posted rows (2026-10-22
    morning) carry message_id NULL (pre-tracking era); collapsing
    them onto one "channel-None" guid would silently drop 18
    published posts. Null-mid rows therefore key on story_key,
    which is unique across those rows. Dedup in the channel
    generator must use the same rule: mid when present, else
    story_key."""
    if message_id is not None:
        return "channel-%s" % message_id
    return "channel-nomid-%s" % story_key


def app_key_for(guid):
    return "a-" + hashlib.sha256(guid.encode("utf-8")).hexdigest()[:20]


def story_id_for(guid):
    return "story-" + hashlib.sha256(guid.encode("utf-8")).hexdigest()[:20]


def update_manifest_section(out_dir, name, items4, file_name, status,
                            extra_by_key=None):
    """Rewrite one manifest section + generated_at/generator; returns manifest."""
    manifest = load_json(os.path.join(out_dir, "manifest.json"))
    items = []
    for it in sorted(items4, key=lambda d: d["app_key"]):
        entry = dict(it)
        if extra_by_key and it["app_key"] in extra_by_key:
            entry.update(extra_by_key[it["app_key"]])
        items.append(entry)
    manifest["sections"][name] = {
        "hash": section_hash_from_items(items4),
        "count": len(items4),
        "status": status,
        "file": file_name,
        "items": items,
    }
    manifest["generated_at_utc"] = now_utc_iso()
    manifest["generator"] = FULLSYNC_GENERATOR
    write_json(os.path.join(out_dir, "manifest.json"), manifest)
    return manifest


def rebuild_health(out_dir):
    """Rebuild health.json from the manifest + files on disk.

    File-backed sections report file_sha256 recomputed from disk;
    unpublished/gated sections keep their honest zero state. The
    expected_absent_files list drops any file that is now published
    (radar.json / channel_typed.json once shipped).
    """
    manifest = load_json(os.path.join(out_dir, "manifest.json"))
    sections = {}
    for name, sec in manifest["sections"].items():
        status = sec.get("status")
        if status in ("not_yet_published", "gated_frozen"):
            sections[name] = {"status": status, "count": 0, "file": None}
            continue
        entry = {"count": sec.get("count"), "hash": sec.get("hash")}
        fname = sec.get("file")
        if name == "articles":
            entry["file_pattern"] = "articles/*.json"
            entry["files_listed_in"] = "manifest.json"
        elif name == "shorts":
            # The manifest shorts section carries no file field, but
            # health has always linked short_cards.json + its sha.
            fname = "short_cards.json"
            entry["file"] = fname
            fpath = os.path.join(out_dir, fname)
            if os.path.exists(fpath):
                entry["file_sha256"] = file_sha256(fpath)
        elif name in ("invites", "revocations"):
            # The manifest invites/revocations sections carry no
            # count; health counts the hashes in the file itself.
            fname = sec.get("file") or (
                "invite_hashes.json" if name == "invites"
                else "revocations.json")
            entry["file"] = fname
            fpath = os.path.join(out_dir, fname)
            if os.path.exists(fpath):
                entry["file_sha256"] = file_sha256(fpath)
                try:
                    entry["count"] = len(load_json(fpath).get("hashes", []))
                except Exception:
                    pass
        elif fname:
            entry["file"] = fname
            fpath = os.path.join(out_dir, fname)
            if os.path.exists(fpath):
                entry["file_sha256"] = file_sha256(fpath)
        sections[name] = entry
    files = {}
    for fname in ("manifest.json", "index.json", "join_keys.json"):
        fpath = os.path.join(out_dir, fname)
        if os.path.exists(fpath):
            files[fname] = file_sha256(fpath)
    absent = ["articles.json"]
    for name in ("bundles", "nightly", "voices"):
        if manifest["sections"].get(name, {}).get("status") in (
                "not_yet_published", "gated_frozen"):
            absent.append("%s.json" % name)
    health = {
        "format": "eco-journal-edge-health",
        "version": 1,
        "manifest_version": 3,
        "manifest_generated_at_utc": manifest.get("generated_at_utc"),
        "generated_at_utc": now_utc_iso(),
        "generator": FULLSYNC_GENERATOR,
        "sections": sections,
        "files": files,
        "expected_absent_files": absent,
    }
    write_json(os.path.join(out_dir, "health.json"), health)
    return health
