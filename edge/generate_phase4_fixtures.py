#!/usr/bin/env python3
"""Phase 4 lab edge fixtures (LAB ONLY — never production data).

Builds a fixture edge from edge/out-phase2-fixture: copies every
base file, then replaces market.json (real 164 snapshots + Phase 4
fixture snapshots + a LAB-ONLY market_alerts object) and radar.json
(graded signals per phases4-5 spec §4.5), and rewrites manifest.json
accordingly (market hash over {snapshots, formulas_active,
market_alerts}; radar hash per the Phase 2 4-field rule).

LAB-ONLY THRESHOLDS — READ THIS: the fixture market_alerts
thresholds (bubble 5.0, spread 2.0) exist ONLY in this fixture
file so P4-4/P4-6 can be phone-tested. They are NEVER defaults:
the production generator reads bot.db admin settings read-only
and ships nulls (verified: no alert_bubble/alert_spread keys
exist). A fixture threshold must never reach a distributed APK;
this fixture edge is served only for the phone test, never
pushed to the pilot Pages edge.

Usage:
    python3 edge/generate_phase4_fixtures.py [--base BASE] [target-dir]
Default base: edge/out-phase2-fixture. Default target:
edge/out-phase4-fixture (lab output; never the live edge, never
pushed to Pages).

Fixture rules (same as Phase 2, plus):
- Every fixture file is labelled "LAB FIXTURE".
- Fixture snapshot ids start "lab-p4-"; fixture signal ids
  start "lab-p4-radar-". No real channel data, no real invite
  code, no blog URL, no model probability anywhere.
- The alert sequence is dated 2026-10-25/26 Tehran, AFTER the
  frozen last snapshot (2026-10-24T09:59:13+03:30), so the
  fixture latest is a fixture snapshot by design — the phone
  test observes banners on the fixture edge, and P4-1/P4-2
  counts over the frozen 164 are verified from the real edge
  (see verify_phase4_fixtures.py), not from this fixture.
"""

import hashlib
import json
import os
import shutil
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TEH = ZoneInfo("Asia/Tehran")
HERE = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.join(HERE, "out-phase2-fixture")

# LAB-ONLY fixture thresholds (see module docstring — never defaults).
FIXTURE_BUBBLE_THRESHOLD = 5.0
FIXTURE_SPREAD_THRESHOLD = 2.0


def epoch(y, mo, d, h, mi=0):
    return int(datetime(y, mo, d, h, mi, tzinfo=TEH).timestamp())


def ts_tehran(y, mo, d, h, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=TEH).isoformat()


def content_hash(obj):
    canonical = json.dumps(obj, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def section_hash(obj):
    canonical = json.dumps(obj, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def section_hash_items(items4):
    ordered = sorted(items4, key=lambda d: d["app_key"])
    canonical = json.dumps(ordered, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def eval_sexpr(node, variables, formulas):
    """Mirror of MarketFormulas.kt (add/sub/mul/div, cycle-guarded)."""
    if isinstance(node, (int, float)):
        return float(node) if float(node) == float(node) else None
    if isinstance(node, str):
        if node in variables:
            return variables[node]
        if node in formulas:
            return eval_formula(node, variables, formulas, set())
        return None
    if isinstance(node, list):
        if not node:
            return None
        op = node[0]
        values = [eval_sexpr(x, variables, formulas) for x in node[1:]]
        if not values or any(v is None for v in values):
            return None
        if op == "add":
            return sum(values)
        if op == "sub":
            out = values[0]
            for v in values[1:]:
                out -= v
            return out
        if op == "mul":
            out = 1.0
            for v in values:
                out *= v
            return out
        if op == "div":
            if any(v == 0.0 for v in values[1:]):
                return None
            out = values[0]
            for v in values[1:]:
                out /= v
            return out
        return None
    return None


def eval_formula(name, variables, formulas, visiting):
    if name in visiting:
        return None
    formula = formulas[name]
    tree = json.loads(formula["definition"])
    return eval_sexpr(tree, variables, formulas)


def snapshot_variables(snap):
    return {
        "gold18_tmn": snap.get("gold_18k_gram"),
        "xau_usd": snap.get("xau_usd"),
        "usd_tmn": snap.get("usd_irt_free"),
        "market": snap.get("sekke_emami"),
        "a": snap.get("usdt_irt"),
        "b": snap.get("usd_irt_free"),
    }


def formula_values(snap, formulas):
    """Evaluate all 4 formulas on a snapshot, mirroring the app.

    bubble_pct's "intrinsic" resolves to the intrinsic_emami output
    on the same snapshot (same rule as MarketFormulas.kt).
    """
    variables = snapshot_variables(snap)
    intrinsic = None
    tree = json.loads(formulas["intrinsic_emami"]["definition"])
    intrinsic = eval_sexpr(tree, variables, formulas)
    out = {}
    for name, formula in formulas.items():
        if name == "bubble_pct":
            if snap.get("sekke_emami") is None or intrinsic is None:
                out[name] = None
            else:
                out[name] = (snap["sekke_emami"] - intrinsic) / intrinsic * 100.0
        else:
            out[name] = eval_sexpr(json.loads(formula["definition"]),
                                   variables, formulas)
    return out


def build_fixture_snapshots(formulas):
    """Fixture snapshots after the frozen history (see docstring)."""
    base = {
        "xau_usd": 4000.0, "dxy": 101.0, "usdt_usd": 1.0,
        "usd_irt_free": 272000.0, "gold_18k_gram": 26600000.0,
        "sekke_bahar": 258000000.0, "nim_sekke": 141000000.0,
        "rob_sekke": 75000000.0, "vix": 19.0, "sp500": 7600.0,
        "brent": 111.0,
    }

    def alert_snap(sid, y, mo, d, h, bubble_target, spread_target):
        snap = dict(base)
        snap["snapshot_id"] = sid
        snap["ts_tehran"] = ts_tehran(y, mo, d, h)
        snap["usdt_irt"] = 272000.0 * (1.0 + spread_target / 100.0)
        # intrinsic from the shipped formula, then sekke for target bubble.
        intrinsic = formula_values(
            dict(snap, sekke_emami=0.0, usdt_irt=272000.0), formulas
        )["intrinsic_emami"]
        snap["sekke_emami"] = intrinsic * (1.0 + bubble_target / 100.0)
        return snap

    snaps = []
    # Null-price snapshot: sekke missing -> bubble null, spread computable.
    null_price = dict(base)
    null_price.update({
        "snapshot_id": "lab-p4-nullprice",
        "ts_tehran": ts_tehran(2026, 10, 25, 10, 0),
        "sekke_emami": None, "usdt_irt": 269000.0,
    })
    snaps.append(null_price)
    # Null-timestamp snapshot: unparseable ts -> tsEpoch null in app,
    # must never become "latest" (P4-3).
    null_ts = dict(base)
    null_ts.update({
        "snapshot_id": "lab-p4-nullts",
        "ts_tehran": "not-a-date",
        "sekke_emami": 270000000.0, "usdt_irt": 269000.0,
    })
    snaps.append(null_ts)
    # Alert sequence (thresholds bubble 5.0 / spread 2.0, LAB ONLY):
    # S1 below/below; S2 bubble crossing (NOTIFY bubble);
    # S3 bubble still above (NO re-notify) + spread crossing (NOTIFY spread);
    # S4 >12h later, both still above, no fresh crossing (NO notify);
    # S5 both below; S6 bubble fresh crossing after cooldown (NOTIFY again).
    snaps.append(alert_snap("lab-p4-s1", 2026, 10, 25, 8, 4.0, 1.0))
    snaps.append(alert_snap("lab-p4-s2", 2026, 10, 25, 10, 6.0, 1.5))
    snaps.append(alert_snap("lab-p4-s3", 2026, 10, 25, 16, 6.5, 2.5))
    snaps.append(alert_snap("lab-p4-s4", 2026, 10, 26, 5, 6.2, 2.6))
    snaps.append(alert_snap("lab-p4-s5", 2026, 10, 26, 10, 3.0, 1.0))
    snaps.append(alert_snap("lab-p4-s6", 2026, 10, 26, 12, 7.0, 1.2))
    return snaps


def build_graded_radar(linked_story_id, linked_app_key):
    """Graded signals (§4.5) + one held orphan + one repeat-match row.

    The repeat-match case is a SINGLE row for signal lab-p4-radar-high
    whose last_seen advanced (dedup by signal_id in the app upsert);
    the verifier asserts one row per signal_id in the file.
    """
    signals = [
        {"signal_id": "lab-p4-radar-high",
         "story_id": linked_story_id,
         "title": "سیگنال آزمایشگاهی فاز ۴ — شدت بالا، داستان پیوندخورده",
         "source_name": "منبع رادار آزمایشگاهی",
         "severity": "high",
         "matched_topics": ["ژئوپلیتیک", "انرژی"],
         "first_seen_tehran": "2026-10-25T09:00:00+03:30",
         "last_seen_tehran": "2026-10-25T11:30:00+03:30",
         "linked_app_key": linked_app_key,
         "status": "active",
         "layer": 1, "severity_rank": 3,
         "detected_at_epoch": epoch(2026, 10, 25, 9, 0),
         "updated_at_epoch": epoch(2026, 10, 25, 11, 30)},
        {"signal_id": "lab-p4-radar-medium",
         "story_id": "story-lab-p4-unlinked",
         "title": "سیگنال آزمایشگاهی فاز ۴ — شدت متوسط، داستان هنوز در برنامه نیست",
         "source_name": "منبع رادار آزمایشگاهی",
         "severity": "medium",
         "matched_topics": ["اقتصاد"],
         "first_seen_tehran": "2026-10-25T10:00:00+03:30",
         "last_seen_tehran": "2026-10-25T10:00:00+03:30",
         "linked_app_key": None,
         "status": "active",
         "layer": 2, "severity_rank": 2,
         "detected_at_epoch": epoch(2026, 10, 25, 10, 0),
         "updated_at_epoch": epoch(2026, 10, 25, 10, 0)},
        {"signal_id": "lab-p4-radar-low",
         "story_id": linked_story_id,
         "title": "سیگنال آزمایشگاهی فاز ۴ — شدت کم، مختومه",
         "source_name": "منبع رادار آزمایشگاهی",
         "severity": "low",
         "matched_topics": [],
         "first_seen_tehran": "2026-10-24T10:00:00+03:30",
         "last_seen_tehran": "2026-10-24T12:00:00+03:30",
         "linked_app_key": linked_app_key,
         "status": "resolved",
         "layer": 3, "severity_rank": 1,
         "detected_at_epoch": epoch(2026, 10, 24, 10, 0),
         "updated_at_epoch": epoch(2026, 10, 24, 12, 0)},
        # Graded-shape orphan: NO story_id -> the app parser must HOLD
        # it (null), never publish it as an orphan row.
        {"signal_id": "lab-p4-radar-orphan",
         "title": "سیگنال آزمایشگاهی فاز ۴ — یتیم، بدون story_id",
         "source_name": "منبع رادار آزمایشگاهی",
         "severity": "low",
         "matched_topics": ["اقتصاد"],
         "first_seen_tehran": "2026-10-25T12:00:00+03:30",
         "last_seen_tehran": "2026-10-25T12:00:00+03:30",
         "linked_app_key": None,
         "status": "active",
         "layer": 2, "severity_rank": 1,
         "detected_at_epoch": epoch(2026, 10, 25, 12, 0),
         "updated_at_epoch": epoch(2026, 10, 25, 12, 0)},
    ]
    for s in signals:
        s["content_hash"] = content_hash(
            {k: v for k, v in s.items() if k != "content_hash"})
    return {
        "war_room": {
            "crisis_level": "L3",
            "why_level": "LAB FIXTURE — خط چرایی سطح بحران آزمایشگاهی.",
            "radar_interval_minutes": 60,
        },
        "signals": signals,
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--base=")]
    base = BASE_DIR
    for a in sys.argv[1:]:
        if a.startswith("--base="):
            base = a[len("--base="):]
    target = args[0] if args else os.path.join(HERE, "out-phase4-fixture")
    if not os.path.isfile(os.path.join(base, "manifest.json")):
        print("base manifest missing: %s" % os.path.join(base, "manifest.json"))
        return 1
    if os.path.abspath(target) == os.path.abspath(base):
        print("refusing to overwrite the base edge")
        return 1
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(base, target)

    # ---- market.json: real snapshots + fixture snapshots + LAB alerts.
    with open(os.path.join(target, "market.json"), encoding="utf-8") as f:
        market = json.load(f)
    real_snaps = market["snapshots"]
    formulas = {f["name"]: f for f in market["formulas_active"]}
    fixture_snaps = build_fixture_snapshots(formulas)
    market["snapshots"] = real_snaps + fixture_snaps
    market["snapshot_count"] = len(market["snapshots"])
    market["last_ts"] = fixture_snaps[-1]["ts_tehran"]
    market["market_alerts"] = {
        "bubble_threshold_pct": FIXTURE_BUBBLE_THRESHOLD,
        "spread_threshold_pct": FIXTURE_SPREAD_THRESHOLD,
        "source": "lab-fixture-only",
        "updated_at_tehran": "2026-10-25T12:00:00+03:30",
    }
    market["label"] = "LAB FIXTURE"
    with open(os.path.join(target, "market.json"), "w", encoding="utf-8") as f:
        json.dump(market, f, ensure_ascii=False, indent=1)
    # The copied GENERATION_REPORT.json describes the BASE edge
    # (164 snapshots); rewrite its market count for the fixture
    # edge (172) so publish_edge --verify-only passes on the
    # fixture exactly as it does on a production edge.
    report_path = os.path.join(target, "GENERATION_REPORT.json")
    if os.path.isfile(report_path):
        with open(report_path, encoding="utf-8") as f:
            report = json.load(f)
        report["exported"]["market_snapshots"] = len(market["snapshots"])
        report["label"] = "LAB FIXTURE"
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)

    # ---- radar.json: graded signals, linked to a Phase 2 fixture item.
    with open(os.path.join(target, "channel_typed.json"), encoding="utf-8") as f:
        typed = json.load(f)
    first_item = typed["items"][0]
    radar = build_graded_radar(first_item["story_id"], first_item["app_key"])
    with open(os.path.join(target, "radar.json"), "w", encoding="utf-8") as f:
        json.dump(dict(radar, label="LAB FIXTURE"), f,
                  ensure_ascii=False, indent=1)

    # ---- manifest: market + radar rewritten; gated sections untouched.
    manifest_path = os.path.join(target, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)

    def items4(entries, key_field):
        return [{"app_key": e[key_field],
                 "content_hash": e["content_hash"],
                 "updated_at_epoch": e["updated_at_epoch"],
                 "status": e["status"]} for e in entries]

    manifest["sections"]["market"] = {
        "hash": section_hash({
            "snapshots": market["snapshots"],
            "formulas_active": market["formulas_active"],
            "market_alerts": market["market_alerts"],
        }),
        "count": len(market["snapshots"]),
        "file": "market.json",
    }
    manifest["sections"]["radar"] = {
        "hash": section_hash_items(items4(radar["signals"], "signal_id")),
        "count": len(radar["signals"]),
        "status": "published",
        "file": "radar.json",
        "items": [{"id": s["signal_id"], "app_key": s["signal_id"],
                   "content_hash": s["content_hash"],
                   "updated_at_epoch": s["updated_at_epoch"],
                   "status": s["status"]} for s in radar["signals"]],
    }
    assert manifest["sections"]["special_issues"]["status"] == "gated_frozen"
    assert manifest["sections"]["nightly"]["status"] == "not_yet_published"
    assert manifest["sections"]["voices"]["status"] == "not_yet_published"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("fixture edge written to %s" % target)
    print("market: %d snapshots (%d real + %d fixture), radar: %d signals"
          % (len(market["snapshots"]), len(real_snaps),
             len(fixture_snaps), len(radar["signals"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
