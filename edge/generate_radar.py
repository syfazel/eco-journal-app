#!/usr/bin/env python3
"""S3 — radar generator (radar-program-plan.md G1, recommendations
applied as delegated, Yasin-reversible decisions):

  D1 freshness: 15-min app-poll floor + 30-min edge publish (this
     generator is run by the 30-min regeneration schedule; the app
     polls at its 15-min WorkManager floor — no faster promise).
  D2 clash = flash + watch + geopol; war-market ONLY with a strike.
     war_events carries no strike flag, so market events are
     EXCLUDED (counted, not silently re-labelled as clashes).
     Wire events are not clashes and are excluded.
  D3 automatic edge publish: yes (via regenerate_fullsync.py).
  D4 unconfirmed signals: NO — published stories only (below).
  D5 (plan §4.5, task spec): bar above ticker vs replacing it is a
     UI decision and is OUT of this data-plane task; no UI touched.

Golden rule (same as S1): a signal enters radar.json ONLY if its
story_key already has a `posted` row in editorial/redalert.db.
The signal title is the PUBLISHED channel title (latest posted row
for the story), never the raw event title. Events with status
staged are usable only as timing/score updates for an already
published story — they can never introduce an unpublished story,
because the posted-membership gate runs first.

Shape (must satisfy ChannelParsing.kt parseRadarSignal, graded
shape): signal_id = war story_key; story_id = the linked channel
item's guid-derived story_id (so WarRoomScreen openStory resolves
to the actual channel card); linked_app_key = that item's app_key;
layer 1 = flash, 2 = watch, 3 = geopol; severity from the unified
urgency of the latest window event for the story (>=85 high,
60-84 medium, <60 low); matched_topics = front labels with the
leading '#' stripped, never raw keywords; first/last_seen_tehran
from window events; a graded signal without story_id is never
emitted (none can occur: story_id is derived from the linked
posted row, and unlinked stories are gated out before emit).
Window: last 24h Tehran time, cap 50, sorted by last_seen desc.
No war_room block is emitted: the crisis level is a computed
global, not derivable from the 24h signal window alone, and a
guessed level would be invented content (the app falls back to
the latest SITREP level when war_room is absent).

Reads ONLY (mode=ro): war_events.db events, redalert.db posted.
Writes: edge/out/radar.json + manifest radar section (status
"published") + health.json rebuild.
"""

import argparse
import json
import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fullsync_lib as lib

TEHRAN = timezone(timedelta(hours=3, minutes=30))
LAYER = {"flash": 1, "watch": 2, "geopol": 3}
WINDOW_HOURS = 24
CAP = 50


def parse_ts(s):
    dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def severity(score):
    if score is None:
        return None
    if score >= 85:
        return "high"
    if score >= 60:
        return "medium"
    return "low"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=lib.OUT_DIR)
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)

    now = datetime.now(TEHRAN)
    cutoff = now - timedelta(hours=WINDOW_HOURS)

    red = lib.ro_connect(lib.REDALERT_DB)
    red.row_factory = __import__("sqlite3").Row
    posted_by_key = {}
    for r in red.execute(
            "SELECT ts, fmt, story_key, title, source, message_id "
            "FROM posted ORDER BY ts ASC, id ASC"):
        posted_by_key[r["story_key"]] = r  # last wins = latest post
    red.close()

    ev = lib.ro_connect(lib.WAR_EVENTS_DB)
    ev.row_factory = __import__("sqlite3").Row
    agg = {}
    window_total = 0
    excluded = {"non_clash_fmt": 0, "unpublished_story": 0}
    for r in ev.execute("SELECT story_key, fmt, score, front, ts, status "
                        "FROM events ORDER BY ts ASC"):
        try:
            ts = parse_ts(r["ts"])
        except ValueError:
            continue
        if ts < cutoff:
            continue
        window_total += 1
        fmt = (r["fmt"] or "").lower()
        if fmt not in LAYER:
            excluded["non_clash_fmt"] += 1
            continue
        sk = r["story_key"]
        if sk not in posted_by_key:
            excluded["unpublished_story"] += 1
            continue
        a = agg.setdefault(sk, {"events": 0, "first": r["ts"],
                                "last": r["ts"], "score": r["score"],
                                "front": r["front"], "fmt": fmt})
        a["events"] += 1
        a["last"] = r["ts"]
        a["score"] = r["score"]
        if r["front"]:
            a["front"] = r["front"]
    ev.close()

    signals = []
    for sk, a in agg.items():
        post = posted_by_key[sk]
        guid = lib.guid_for_post(post["message_id"], sk)
        sig = {
            "signal_id": sk,
            "story_id": lib.story_id_for(guid),
            "linked_story_id": lib.story_id_for(guid),
            "linked_app_key": lib.app_key_for(guid),
            "title": post["title"],
            "layer": LAYER[a["fmt"]],
            "severity": severity(a["score"]),
            "severity_rank": a["score"],
            "source_name": post["source"] or "",
            "matched_topics": ([a["front"].lstrip("#")]
                                if a["front"] else []),
            "first_seen_tehran": a["first"],
            "last_seen_tehran": a["last"],
            "detected_at_epoch": lib.epoch_ms(a["last"]),
            "updated_at_epoch": lib.epoch_ms(a["last"]),
            "status": "active",
        }
        sig["content_hash"] = lib.content_hash(sig)
        signals.append(sig)
    signals.sort(key=lambda s: (-s["updated_at_epoch"], s["signal_id"]))
    signals = signals[:CAP]

    doc = {"format": "eco-journal-radar", "version": 1,
           "generated_at_utc": lib.now_utc_iso(),
           "window_hours": WINDOW_HOURS,
           "signals": signals}
    lib.write_json(os.path.join(out_dir, "radar.json"), doc)

    items4 = [{"app_key": s["signal_id"], "content_hash": s["content_hash"],
               "updated_at_epoch": s["updated_at_epoch"],
               "status": s["status"]} for s in signals]
    extras = {s["signal_id"]: {"id": s["signal_id"]} for s in signals}
    lib.update_manifest_section(out_dir, "radar", items4,
                                "radar.json", "published", extras)
    lib.rebuild_health(out_dir)

    report = {
        "generator": "generate_radar.py (S3)",
        "generated_at_utc": doc["generated_at_utc"],
        "window": {"hours": WINDOW_HOURS,
                   "cutoff_tehran": cutoff.isoformat(),
                   "now_tehran": now.isoformat()},
        "window_events": window_total,
        "radar_signals": len(signals),
        "by_layer": {str(LAYER[f]): sum(1 for s in signals
                                         if s["layer"] == LAYER[f])
                     for f in LAYER},
        "by_severity": {sev: sum(1 for s in signals
                                 if s["severity"] == sev)
                        for sev in ("high", "medium", "low", None)},
        "excluded": excluded,
        "delegated_decisions": [
            "D1 freshness 15-min poll floor + 30-min edge publish",
            "D2 clash=flash+watch+geopol; market excluded (no strike flag)",
            "D3 automatic edge publish yes (via regenerate_fullsync.py)",
            "D4 unconfirmed signals NO (published stories only)",
        ],
        "golden_rule": "story_key must have a posted row; title from posted row",
    }
    lib.write_json(os.path.join(lib.EDGE_DIR,
                                 "fullsync-radar-report.json"), report)
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
