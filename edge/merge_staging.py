#!/usr/bin/env python3
"""Phase 3 (Tier A, LAB ONLY): staging -> edge merge.

Reads bridge staging files (bridge/channel_reader.py output) whose
queue rows have passed the delegated editorial gate, and merges them
into an edge directory in the Phase 2 fixture shapes
(channel_typed.json, bundles.json, radar.json + manifest v3).

This is the lab half of spec section 1 step 4. It never touches a
live channel, cron, bot, or freeze state; it never pushes, never
publishes to Pages, and never activates FCM. The merged edge is
verified by edge/publish_edge.py --verify-only (Phase 3 extension);
only a verified edge may proceed to any publish step, and Tier A
stops at verification (dry-run pattern from Phase 1).

Delegated gate (spec section 1 step 3):
  Only queue rows with status "approved" merge. The gate decision is
  recorded by bridge/control.py approve (review -> approved) under
  the delegated editorial authority; the merge carries its
  provenance verbatim into the edge item's chain object:
  "muse-editorial (delegated by Yasin 2026-10-18)".
  - review (no decision yet)  -> gate_pending: skipped (F3 hold).
  - rejected                  -> gate_rejected: skipped (F2, a
                                 legitimate editorial outcome, NOT
                                 a system failure).
  - any other status          -> gate_not_approved: skipped.
  Unapproved items NEVER reach the edge, even with --strict.

Gate annotation (the editorial typing decision, NOT inferred text):
  Next to each staging file, an optional "<stem>.gate.json" carries
  the delegated decision's typing fields. Nothing here is inferred
  from the post text: a missing annotation means the item is not
  typed yet and stays unmerged (gate_incomplete).
    {"format_type": "WIRE", "urgency_score": null,
     "crisis_level": null, "slot": null, "attribution": null,
     "source_link": null,
     "bundle_id": null, "bundle_kind": null,
     "bundle_date_shamsi": null,
     "bundle_position": null, "bundle_total": null}
  Rules: format_type must be one of the 7 Phase 2 formats;
  urgency_score null or 0..100; crisis_level null or L1..L4;
  slot null or morning/midday/evening/night; attribution null,
  "verbatim", or "translated". "verbatim" is accepted ONLY when the
  source name contains "دنیای اقتصاد" or "تابناک" (the Phase 2
  allowlist, Phase2Domain.kt); otherwise the item is rejected with
  code verbatim_not_allowed (never silently downgraded, never
  shipped as a false verbatim claim). A WIRE item with a non-null
  source_link is rejected (source name only, no link, spec 2.2).
  bundle_id requires bundle_position + bundle_total AND, when the
  bundle header does not exist yet, bundle_kind (one of the 6
  Phase 2 kinds; "morning_digest" is refused: disabled 2026-10-22,
  never rendered) + bundle_date_shamsi (edge-supplied, Latin
  YYYY-MM-DD Shamsi; never derived by this script).
  A bundle header that already exists gains the member appended in
  member_app_keys order; positions are recomputed 1..N from that
  order (Phase 2 spec 8.3: member order wins over stored values).

Dedup (3 layers + merge layer, ALL keyed by channel:<message_id>,
NEVER by headline hash -- the 2026-10-23 transliteration defect):
  L1 staging dir: filename channel-<message_id>.json is unique by
     construction; a second capture overwrites nothing and stages
     nothing new (channel_reader layer 1/2 behaviour).
  L2 queue: idempotency key channel:<message_id> is the queue PK;
     one row per post, duplicates refused.
  L3 published edge: a message_id already present in the base
     channel_typed items (or join_keys) is skipped as
     already_published -- never a second card.
  L4 merge conflict (F1, HALT): staging index <-> staging files
     disagreement, two staging files mapping to one guid with
     different bodies, or a base item carrying the same
     channel_message_id under a different app_key. Any F1 aborts
     the whole run with exit 4 and writes NOTHING to --out.

Identity (Phase 1 rules, unchanged):
  guid    = staging item "id" ("channel-<message_id>")
  app_key = "a-" + sha256(guid)[:20]
  story_id = "story-" + sha256(guid)[:20]
  channel_message_id = the REAL channel post id from staging.
     Fixture/lab ids are NULL or >= 9000001 by convention; this
     script never fabricates an id -- a staging item without a
     message_id is skipped, never assigned one.
  status  = "posted" (the only shippable status Phase 2 parses
     besides "retracted"; the gate decision "approved" lives in
     the chain object, not in status).
  content_hash = sha256 over canonical JSON of the full item
     minus content_hash itself (the Phase 2 fixture builder rule;
     the chain object is included, so a chain change is a content
     change and the delta picks it up).
  published_at_epoch / updated_at_epoch = channel_posted_epoch
     (Telegram date; falls back to parsing published_at ISO).

Section hashes (Phase 2 spec 2.1, Phase 1 pattern exactly):
  sha256 over canonical JSON of the 4-field view
  (app_key, content_hash, updated_at_epoch, status), sorted by
  app_key, for channel_typed, bundles, radar. A section with no
  items publishes hash-of-empty-list, count 0, status
  "published_empty" -- never "not_yet_published" once shipped.
  Radar is preserved verbatim from the base (radar signals come
  from the radar service, not from posts); the merge never infers
  a war_room level from a SITREP -- the War Room header falls
  back to the latest SITREP level per Phase 2 decision 3.

Manifest: generated_at_utc becomes the merge run time; the
generator string becomes the Phase 3 merge string (verify()
accepts the Phase 1 string for unmerged edges and this string
for merged edges, nothing else). All other sections are copied
verbatim from the base manifest. join_keys gains one entry per
merged item (appended, existing order kept).

Exit codes: 0 = merged (or, without --strict, approved items
  merged and the rest reported as skipped); 3 = --strict and some
  staged item is unmergeable (pending / incomplete / invalid
  annotation -- rejected and already-published items are
  legitimate skips and do NOT fail --strict); 4 = staging
  conflict (F1 halt, nothing written); 1 = other error; 2 =
  bad arguments.
"""

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)

FORMATS = {"FLASH", "WATCH", "GEOPOL", "MARKET", "VERIFY", "SITREP", "WIRE"}
SLOTS = {"morning", "midday", "evening", "night"}
LEVELS = {"L1", "L2", "L3", "L4"}
BUNDLE_KINDS = {"slot_bundle", "short_news", "marginalia",
                "hourly_digest", "morning_bundle", "night_desk"}
VERBATIM_SOURCES = ("دنیای اقتصاد", "تابناک")
GATE_PROVENANCE = "muse-editorial (delegated by Yasin 2026-10-18)"
GENERATOR = "edge/merge_staging.py (Phase 3 Tier A, LAB ONLY) + edge/generate_edge.py (Phase 1, LAB ONLY)"
EMPTY_HASH = hashlib.sha256(b"[]").hexdigest()


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def sha256_hex(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def section_hash(items4):
    ordered = sorted(items4, key=lambda d: d["app_key"])
    return sha256_hex(canonical_json(ordered))


def app_key_for(guid):
    return "a-" + hashlib.sha256(guid.encode("utf-8")).hexdigest()[:20]


def story_id_for(guid):
    return "story-" + hashlib.sha256(guid.encode("utf-8")).hexdigest()[:20]


def parse_iso_epoch(text):
    try:
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp())
    except Exception:
        return None


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def queue_row(db_path, key):
    """Read one queue row (read-only). Returns dict or None."""
    if not os.path.exists(db_path):
        return None
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        con.row_factory = sqlite3.Row
        row = con.execute(
            "SELECT key, status, note, updated_at FROM items WHERE key = ?",
            (key,)).fetchone()
        return dict(row) if row else None
    except sqlite3.Error:
        return None
    finally:
        con.close()


def validate_annotation(ann, source_name):
    """Return (clean_dict, error_code_or_None)."""
    if not isinstance(ann, dict):
        return None, "gate_incomplete"
    fmt = ann.get("format_type")
    if fmt not in FORMATS:
        return None, "invalid_annotation"
    score = ann.get("urgency_score")
    if score is not None and (not isinstance(score, int) or isinstance(score, bool)
                              or score < 0 or score > 100):
        return None, "invalid_annotation"
    level = ann.get("crisis_level")
    if level is not None and level not in LEVELS:
        return None, "invalid_annotation"
    slot = ann.get("slot")
    if slot is not None and slot not in SLOTS:
        return None, "invalid_annotation"
    attribution = ann.get("attribution")
    if attribution is not None and attribution not in ("verbatim", "translated"):
        return None, "invalid_annotation"
    if attribution == "verbatim" and not any(
            s in (source_name or "") for s in VERBATIM_SOURCES):
        return None, "verbatim_not_allowed"
    link = ann.get("source_link")
    if fmt == "WIRE" and link is not None:
        return None, "invalid_annotation"
    bundle_id = ann.get("bundle_id")
    if bundle_id is not None:
        if ann.get("bundle_position") is None or ann.get("bundle_total") is None:
            return None, "invalid_annotation"
    return {
        "format_type": fmt,
        "urgency_score": score,
        "crisis_level": level,
        "slot": slot,
        "attribution": attribution,
        "source_link": link,
        "bundle_id": bundle_id,
        "bundle_kind": ann.get("bundle_kind"),
        "bundle_title": ann.get("bundle_title"),
        "bundle_date_shamsi": ann.get("bundle_date_shamsi"),
    }, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--staging-dir", required=True)
    ap.add_argument("--queue-db", required=True)
    ap.add_argument("--base-edge", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--report", default=None)
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    staging_dir = os.path.abspath(args.staging_dir)
    base_edge = os.path.abspath(args.base_edge)
    out_dir = os.path.abspath(args.out)
    for path, label in ((staging_dir, "staging dir"), (base_edge, "base edge")):
        if os.path.commonpath([path, APP_ROOT]) != APP_ROOT and not path.startswith("/tmp/"):
            print("REFUSED: %s must be inside the app root or /tmp" % label,
                  file=sys.stderr)
            return 2
    if os.path.commonpath([out_dir, APP_ROOT]) != APP_ROOT and not out_dir.startswith("/tmp/"):
        print("REFUSED: --out must be inside the app root or /tmp", file=sys.stderr)
        return 2
    if out_dir == base_edge:
        print("REFUSED: --out must differ from --base-edge", file=sys.stderr)
        return 2

    report = {
        "merged": [], "skipped": [], "conflicts": [],
        "base_counts": {}, "out_counts": {},
        "generator": GENERATOR, "gate_provenance": GATE_PROVENANCE,
    }

    def abort_conflict(msg):
        report["conflicts"].append(msg)
        write_report()
        print("STAGING CONFLICT (F1, halted, nothing written): %s" % msg,
              file=sys.stderr)
        return 4

    def write_report():
        path = args.report or os.path.join(HERE, "phase3-merge-report.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
        report["_path"] = path

    # ---- load base edge
    try:
        base_manifest = load_json(os.path.join(base_edge, "manifest.json"))
        base_join = load_json(os.path.join(base_edge, "join_keys.json"))
    except Exception as e:
        print("ABORTED: cannot load base edge: %s" % e, file=sys.stderr)
        return 1
    typed_path = os.path.join(base_edge, "channel_typed.json")
    base_typed = load_json(typed_path)["items"] if os.path.exists(typed_path) else []
    bundles_path = os.path.join(base_edge, "bundles.json")
    base_bundles = load_json(bundles_path)["bundles"] if os.path.exists(bundles_path) else []
    radar_path = os.path.join(base_edge, "radar.json")
    base_radar = load_json(radar_path) if os.path.exists(radar_path) else None
    report["base_counts"] = {
        "channel_typed": len(base_typed), "bundles": len(base_bundles),
        "radar": len(base_radar["signals"]) if base_radar else 0,
    }

    # ---- load staging + F1 index/file agreement
    index_path = os.path.join(staging_dir, "index.json")
    if not os.path.exists(index_path):
        print("ABORTED: staging index missing: %s" % index_path, file=sys.stderr)
        return 1
    staged_stems = load_json(index_path).get("staged", [])
    # Staging files are exactly channel-<numeric-id>.json. Gate
    # annotations (channel-<id>.gate.json) live next to them and
    # must NOT be mistaken for staging files (F1 false alarm).
    import re as _re
    files = sorted(p for p in os.listdir(staging_dir)
                   if _re.fullmatch(r"channel-\d+\.json", p))
    file_stems = sorted(p[:-5] for p in files)
    if sorted(staged_stems) != file_stems:
        return abort_conflict(
            "staging index %s != staging files %s" % (sorted(staged_stems), file_stems))

    base_by_mid = {}
    for it in base_typed:
        mid = it.get("channel_message_id")
        if mid is not None:
            if mid in base_by_mid and base_by_mid[mid]["app_key"] != it["app_key"]:
                return abort_conflict(
                    "base edge carries message_id %r under two app_keys" % mid)
            base_by_mid[mid] = it
    base_by_key = {it["app_key"]: it for it in base_typed}
    base_guids = {it.get("guid"): it for it in base_typed}

    merged_items = []
    seen_guids = {}
    strict_fail = False
    run_epoch = int(datetime.now(timezone.utc).timestamp())
    generated_at = datetime.now(timezone.utc).isoformat()

    for stem in file_stems:
        item = load_json(os.path.join(staging_dir, stem + ".json"))
        mid = item.get("channel_message_id")
        if not isinstance(mid, int) or isinstance(mid, bool):
            report["skipped"].append({"stem": stem, "reason": "no_message_id"})
            strict_fail = True
            continue
        key = "channel:%d" % mid
        guid = item.get("id", "")
        if guid in seen_guids and seen_guids[guid] != item.get("body_blocks"):
            return abort_conflict("two staging files map to guid %r" % guid)
        seen_guids[guid] = item.get("body_blocks")
        if mid in base_by_mid:
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "already_published"})
            continue
        row = queue_row(args.queue_db, key)
        if row is None:
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "gate_no_row"})
            strict_fail = True
            continue
        status = row["status"]
        if status == "rejected":
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "gate_rejected"})
            continue
        if status == "review":
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "gate_pending"})
            strict_fail = True
            continue
        if status != "approved":
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "gate_not_approved",
                                     "status": status})
            strict_fail = True
            continue
        gate_epoch = parse_iso_epoch(row["updated_at"]) or run_epoch
        ann_path = os.path.join(staging_dir, stem + ".gate.json")
        if not os.path.exists(ann_path):
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "gate_incomplete"})
            strict_fail = True
            continue
        ann, err = validate_annotation(load_json(ann_path), item.get("source", ""))
        if err:
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": err})
            strict_fail = True
            continue
        if guid in base_guids and base_guids[guid].get("channel_message_id") != mid:
            return abort_conflict(
                "guid %r collides with a base item of another message_id" % guid)
        body_blocks = item.get("body_blocks") or []
        body = "\n".join(body_blocks) if body_blocks else item.get("summary", "")
        posted_epoch = item.get("channel_posted_epoch")
        if not isinstance(posted_epoch, int):
            posted_epoch = parse_iso_epoch(item.get("published_at", "")) or run_epoch
        staged_epoch = item.get("staged_epoch")
        if not isinstance(staged_epoch, int):
            staged_epoch = int(os.path.getmtime(
                os.path.join(staging_dir, stem + ".json")))
        app_key = app_key_for(guid)
        if app_key in base_by_key:
            report["skipped"].append({"stem": stem, "message_id": mid,
                                     "reason": "already_published"})
            continue
        merged = {
            "app_key": app_key,
            "guid": guid,
            "story_id": story_id_for(guid),
            "channel_message_id": mid,
            "format_type": ann["format_type"],
            "urgency_score": ann["urgency_score"],
            "crisis_level": ann["crisis_level"],
            "headline": item.get("title", ""),
            "body": body,
            "published_at_epoch": posted_epoch,
            "slot": ann["slot"],
            "bundle_id": ann["bundle_id"],
            "bundle_position": None,
            "bundle_total": None,
            "source_name": item.get("source", ""),
            "source_link": ann["source_link"],
            "attribution": ann["attribution"],
            "verdict_status": None,
            "verdict_text": None,
            "prices_json": None,
            "sitrep_sections_json": None,
            "watch_next_json": None,
            "status": "posted",
            "updated_at_epoch": posted_epoch,
            "chain": {
                "channel_message_id": mid,
                "channel_posted_epoch": posted_epoch,
                "staged_epoch": staged_epoch,
                "gate_decided_epoch": gate_epoch,
                "gate_decision": "approved",
                "gate_provenance": GATE_PROVENANCE,
                "edge_generated_epoch": run_epoch,
                "edge_pushed_epoch": None,
            },
            "_bundle_kind": ann["bundle_kind"],
            "_bundle_title": ann["bundle_title"],
            "_bundle_date_shamsi": ann["bundle_date_shamsi"],
        }
        merged_items.append(merged)
        report["merged"].append({"stem": stem, "message_id": mid,
                                 "app_key": app_key,
                                 "story_id": merged["story_id"],
                                 "format_type": ann["format_type"],
                                 "gate_decided_epoch": gate_epoch})

    if args.strict and strict_fail:
        write_report()
        print("STRICT: %d staged item(s) unmergeable; nothing written"
              % len([s for s in report["skipped"]
                     if s["reason"] not in ("gate_rejected", "already_published")]),
              file=sys.stderr)
        return 3

    # ---- bundles: append members, recompute positions from member order
    out_bundles = [dict(b) for b in base_bundles]
    by_bundle = {b["bundle_id"]: b for b in out_bundles}
    touched_bundles = set()
    for merged in merged_items:
        bid = merged["bundle_id"]
        if bid is None:
            continue
        header = by_bundle.get(bid)
        if header is None:
            kind = merged.pop("_bundle_kind")
            title = merged.pop("_bundle_title") or bid
            date_shamsi = merged.pop("_bundle_date_shamsi")
            if kind not in BUNDLE_KINDS or not date_shamsi:
                write_report()
                print("ABORTED: new bundle %r needs a valid bundle_kind + "
                      "bundle_date_shamsi in its gate annotation" % bid,
                      file=sys.stderr)
                return 1
            header = {"bundle_id": bid, "bundle_kind": kind,
                      "title": title, "member_app_keys": [],
                      "bundle_date_shamsi": date_shamsi,
                      "status": "posted",
                      "updated_at_epoch": merged["published_at_epoch"]}
            out_bundles.append(header)
            by_bundle[bid] = header
        else:
            merged.pop("_bundle_kind", None)
            merged.pop("_bundle_title", None)
            merged.pop("_bundle_date_shamsi", None)
        if merged["app_key"] not in header["member_app_keys"]:
            header["member_app_keys"].append(merged["app_key"])
            touched_bundles.add(bid)
        pos = header["member_app_keys"].index(merged["app_key"]) + 1
        merged["bundle_position"] = pos
        merged["bundle_total"] = len(header["member_app_keys"])
        header["updated_at_epoch"] = max(header["updated_at_epoch"],
                                         merged["published_at_epoch"])
    for merged in merged_items:
        merged.pop("_bundle_kind", None)
        merged.pop("_bundle_title", None)
        merged.pop("_bundle_date_shamsi", None)
    # Recompute content_hash ONLY for touched/new headers (fixture
    # rule: hash of the full header minus itself). Untouched headers
    # keep their stored hash, so the bundles section hash is
    # unchanged for them and the app does not refetch them.
    for header in out_bundles:
        if header["bundle_id"] in touched_bundles or header.get("content_hash") is None:
            header["content_hash"] = sha256_hex(canonical_json(
                {k: v for k, v in header.items() if k != "content_hash"}))

    # ---- content hashes (fixture rule: hash of the full item minus itself)
    for merged in merged_items:
        merged["content_hash"] = sha256_hex(canonical_json(
            {k: v for k, v in merged.items() if k != "content_hash"}))

    out_typed = base_typed + merged_items
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    shutil.copytree(base_edge, out_dir)
    with open(os.path.join(out_dir, "channel_typed.json"), "w", encoding="utf-8") as f:
        json.dump({"label": "PHASE 3 TIER A MERGE", "items": out_typed}, f,
                  ensure_ascii=False, indent=1)
    with open(os.path.join(out_dir, "bundles.json"), "w", encoding="utf-8") as f:
        json.dump({"label": "PHASE 3 TIER A MERGE", "bundles": out_bundles}, f,
                  ensure_ascii=False, indent=1)
    # Radar preserved verbatim (copytree already carried it over).

    def items4(entries, key_field):
        return [{"app_key": e[key_field], "content_hash": e["content_hash"],
                 "updated_at_epoch": e["updated_at_epoch"],
                 "status": e["status"]} for e in entries]

    manifest = dict(base_manifest)
    manifest["generated_at_utc"] = generated_at
    manifest["generator"] = GENERATOR
    sections = dict(manifest.get("sections", {}))
    if out_typed:
        sections["channel_typed"] = {
            "hash": section_hash(items4(out_typed, "app_key")),
            "count": len(out_typed), "status": "published",
            "file": "channel_typed.json",
            "items": [{"id": it["app_key"], "app_key": it["app_key"],
                       "content_hash": it["content_hash"],
                       "updated_at_epoch": it["updated_at_epoch"],
                       "status": it["status"],
                       "channel_message_id": it["channel_message_id"],
                       "story_id": it["story_id"]} for it in out_typed],
        }
    else:
        sections["channel_typed"] = {
            "hash": EMPTY_HASH, "count": 0, "status": "published_empty",
            "file": "channel_typed.json", "items": [],
        }
    if out_bundles:
        sections["bundles"] = {
            "hash": section_hash(items4(out_bundles, "bundle_id")),
            "count": len(out_bundles), "status": "published",
            "file": "bundles.json",
            "items": [{"id": b["bundle_id"], "app_key": b["bundle_id"],
                       "content_hash": b["content_hash"],
                       "updated_at_epoch": b["updated_at_epoch"],
                       "status": b["status"]} for b in out_bundles],
        }
    else:
        sections["bundles"] = {
            "hash": EMPTY_HASH, "count": 0, "status": "published_empty",
            "file": "bundles.json", "items": [],
        }
    if base_radar is not None:
        signals = base_radar.get("signals", [])
        if signals:
            sections["radar"] = {
                "hash": section_hash(items4(signals, "signal_id")),
                "count": len(signals), "status": "published",
                "file": "radar.json",
                "items": [{"id": s["signal_id"], "app_key": s["signal_id"],
                           "content_hash": s["content_hash"],
                           "updated_at_epoch": s["updated_at_epoch"],
                           "status": s["status"]} for s in signals],
            }
        else:
            sections["radar"] = {
                "hash": EMPTY_HASH, "count": 0, "status": "published_empty",
                "file": "radar.json", "items": [],
            }
    manifest["sections"] = sections
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)

    out_join = {"version": 1, "entries": list(base_join.get("entries", []))}
    for merged in merged_items:
        out_join["entries"].append({
            "guid": merged["guid"], "app_key": merged["app_key"],
            "story_id": merged["story_id"],
            "channel_message_id": merged["channel_message_id"]})
    with open(os.path.join(out_dir, "join_keys.json"), "w", encoding="utf-8") as f:
        json.dump(out_join, f, ensure_ascii=False, indent=1)

    report["out_counts"] = {
        "channel_typed": len(out_typed), "bundles": len(out_bundles),
        "radar": len(base_radar["signals"]) if base_radar else 0,
        "join_keys": len(out_join["entries"]),
        "edge_generated_epoch": run_epoch,
    }
    write_report()
    print("merged %d item(s) into %s (skipped %d)"
          % (len(merged_items), out_dir, len(report["skipped"])))
    for s in report["skipped"]:
        print("  skipped: %s" % s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
