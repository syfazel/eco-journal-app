#!/usr/bin/env python3
"""S1 — channel_typed generator: published channel posts -> edge.

Reads ONLY (mode=ro):
  * editorial/redalert.db `posted` — rows for posts ACTUALLY SENT to
    the live channel (id, ts, fmt, story_key, title, source,
    message_id). The `queue` table (night-held items awaiting 08:00)
    is never opened: a held item has no `posted` row, so it cannot
    enter, on any run, at any hour.
  * editorial/war_events.db `events` — latest score per story_key,
    used ONLY as the item's urgency_score (the unified urgency).
    No score is invented: a story with no event row gets null
    (app NightLaw: null -> FEED_ONLY, except FLASH which is
    INSTANT by format).

Mapping to the app parser (ChannelParsing.kt parseChannelItem):
  * fmt lower-case -> UPPER format_type; all 7 posted formats map
    onto known app formats (FLASH/WATCH/GEOPOL/MARKET/VERIFY/
    SITREP/WIRE).
  * identity follows the Phase 2/3 rule: guid = channel-<message_id>,
    app_key = a-sha256(guid)[:20], story_id = story-sha256(guid)[:20].
  * status is always "posted"; body is "" (posted carries no body).
  * SITREP rows are EXCLUDED (23 rows): the app drops an empty
    SITREP (BAD_SITREP_EMPTY) and no retrievable published SITREP
    body exists anywhere (format_log stores only message_id/date).
    Emitting a SITREP with a made-up body would be invented content.
  * VERIFY rows carry no verdict fields (posted has none): the app
    records a FORMAT_INCOMPLETE anomaly but still renders the item.
    MARKET rows likewise carry no prices_json (anomaly only).
  * Retractions: there is no retraction table; a post that later
    disappears from `posted` disappears from the next manifest, and
    the app's delta planner tombstones it (status -> retracted).
    This generator never writes to any channel/post store.

Dedup: by channel_message_id, keeping the latest row (max ts, id).
Output: edge/out/channel_typed.json + manifest channel_typed section
(status "published") + health.json rebuild. No chain objects (see
fullsync_lib: no staged/gate timestamps exist to report honestly).

Usage: python3 edge/generate_channel_typed.py [--out DIR]
Exit 0 on success; prints a JSON report to stdout and writes it to
edge/fullsync-channel-report.json (next to this script).
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fullsync_lib as lib

FMT_MAP = {
    "flash": "FLASH", "watch": "WATCH", "geopol": "GEOPOL",
    "market": "MARKET", "verify": "VERIFY", "sitrep": "SITREP",
    "wire": "WIRE",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=lib.OUT_DIR)
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)

    red = lib.ro_connect(lib.REDALERT_DB)
    red.row_factory = __import__("sqlite3").Row
    rows = red.execute(
        "SELECT id, ts, fmt, story_key, title, source, message_id "
        "FROM posted ORDER BY ts DESC, id DESC").fetchall()
    red.close()

    ev = lib.ro_connect(lib.WAR_EVENTS_DB)
    scores = {}
    for story_key, score, ts in ev.execute(
            "SELECT story_key, score, ts FROM events ORDER BY ts ASC"):
        scores[story_key] = score  # last write wins = latest event
    ev.close()

    seen_keys = set()
    items = []
    excluded = {"sitrep_no_body": 0, "unknown_fmt": 0, "duplicate_mid": 0,
                "blank_title": 0}
    score_covered = 0
    for r in rows:
        mid = r["message_id"]
        # Dedup key: message_id when present, else story_key (the
        # 19 NULL-mid rows from 2026-10-22 morning must not collapse).
        dedup_key = ("mid", mid) if mid is not None \
            else ("story", r["story_key"])
        if dedup_key in seen_keys:
            excluded["duplicate_mid"] += 1
            continue
        seen_keys.add(dedup_key)
        fmt = (r["fmt"] or "").lower()
        if fmt == "sitrep":
            excluded["sitrep_no_body"] += 1
            continue
        if fmt not in FMT_MAP:
            excluded["unknown_fmt"] += 1
            continue
        title = (r["title"] or "").strip()
        if not title:
            excluded["blank_title"] += 1
            continue
        guid = lib.guid_for_post(mid, r["story_key"])
        app_key = lib.app_key_for(guid)
        score = scores.get(r["story_key"])
        if score is not None:
            score_covered += 1
        item = {
            "guid": guid,
            "app_key": app_key,
            "story_id": lib.story_id_for(guid),
            "channel_message_id": mid,
            "format_type": FMT_MAP[fmt],
            "headline": title,
            "body": "",
            "source_name": r["source"] or "",
            "urgency_score": score,
            "crisis_level": None,
            "published_at_epoch": lib.epoch_ms(r["ts"]),
            "updated_at_epoch": lib.epoch_ms(r["ts"]),
            "status": "posted",
        }
        item["content_hash"] = lib.content_hash(item)
        items.append(item)
    items.sort(key=lambda it: (-it["published_at_epoch"], it["app_key"]))

    doc = {"format": "eco-journal-channel-typed", "version": 1,
           "generated_at_utc": lib.now_utc_iso(), "items": items}
    lib.write_json(os.path.join(out_dir, "channel_typed.json"), doc)

    items4 = [{"app_key": it["app_key"], "content_hash": it["content_hash"],
               "updated_at_epoch": it["updated_at_epoch"],
               "status": it["status"]} for it in items]
    extras = {it["app_key"]: {
        "id": it["guid"],
        "channel_message_id": it["channel_message_id"],
        "story_id": it["story_id"]} for it in items}
    lib.update_manifest_section(out_dir, "channel_typed", items4,
                                "channel_typed.json", "published", extras)
    lib.rebuild_health(out_dir)

    by_fmt = {}
    for it in items:
        by_fmt[it["format_type"]] = by_fmt.get(it["format_type"], 0) + 1
    report = {
        "generator": "generate_channel_typed.py (S1)",
        "generated_at_utc": doc["generated_at_utc"],
        "posted_rows_read": len(rows),
        "channel_typed_items": len(items),
        "by_format": by_fmt,
        "excluded": excluded,
        "urgency_score_covered": score_covered,
        "urgency_score_null": len(items) - score_covered,
        "golden_rule": "posted table only; queue (night-held) never read",
    }
    lib.write_json(os.path.join(lib.EDGE_DIR,
                                 "fullsync-channel-report.json"), report)
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
