#!/usr/bin/env python3
"""ECO JOURNAL Yemen edge generator — v5.2.0, LAB ONLY.

Reads the verified live-dashboard snapshot
(~/workspace/goals/eco-journal/hidden_files/yemen-dashboard-v5-20261118.json,
dashboard v5, fetched 2026-11-18) and writes edge/out/yemen.json plus
the IDENTICAL seed copy app/src/main/assets/seed/yemen.json from the
SAME run and the SAME dashboard state, so seed and edge can never
drift (audit §5 risk 6). Nothing is typed by hand: every number,
name, coordinate and link comes from the snapshot. Human-stats
values stay STRINGS and are never converted to numbers or computed
on. Frontline coordinates are approximate (+-10 km, the researcher's
own caveat) and the warning text travels verbatim into yemen.json
as frontline_warning_fa so the app can show it under the map.

Manifest (additive, v3 -> v4): adds sections.yemen =
{file, count, hash, file_sha256, schema_version, generated_at_utc,
update_cadence_hours: 24, stale_after_hours: 24}. Old clients
ignore the unknown section. The section hash is the SHA-256 of the
canonical JSON of the whole yemen.json document (same canonical
rule as edge/generate_edge.py: sorted keys, compact separators,
non-ASCII preserved) — never only over the entry list.
health.json is updated coherently in the same run (manifest_version
4, same generated_at_utc, yemen section entry, manifest.json file
SHA-256 recomputed) so the v5.1 edge-contract verifier still holds.

Usage:
  python3 edge/generate_yemen.py [--snapshot PATH] [--check-only]
--check-only prints the counts and exits without writing.

Python standard library only. No network access.
"""

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SNAPSHOT = os.path.join(
    HOME, "workspace", "goals", "eco-journal",
    "hidden_files", "yemen-dashboard-v5-20261118.json",
)
OUT_DIR = os.path.join(SCRIPT_DIR, "out")
EDGE_YEMEN = os.path.join(OUT_DIR, "yemen.json")
SEED_YEMEN = os.path.join(
    APP_ROOT, "app", "src", "main", "assets", "seed", "yemen.json",
)
MANIFEST_PATH = os.path.join(OUT_DIR, "manifest.json")
HEALTH_PATH = os.path.join(OUT_DIR, "health.json")
MANIFEST_VERSION = 4
SCHEMA_VERSION = 1
UPDATE_CADENCE_HOURS = 24
STALE_AFTER_HOURS = 24


def canonical_json(obj):
    """Canonical JSON for hashes: sorted keys, compact separators,
    non-ASCII preserved — byte-identical rule to generate_edge.py."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def section_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def sha20(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:20]


def yemen_app_key(name_fa, governorate_fa):
    """Place identity (audit §4.2): 'y-' + first 20 hex of
    SHA-256(name_fa + '|' + governorate_fa). The numeric dashboard
    id is kept as external_id but never used for identity."""
    return "y-" + sha20(name_fa + "|" + governorate_fa)


def event_app_key(event_id):
    """Event identity: 'y-event-' + first 20 hex of SHA-256 of the
    dashboard event id (as a string)."""
    return "y-event-" + sha20(str(event_id))


def as_str_list(items, field):
    """Normalise a dashboard list to a list of strings: plain
    strings pass through; dicts contribute their 'body' (or
    'bodyFa') text. Raises on anything else — never invents."""
    out = []
    for it in items or []:
        if isinstance(it, str):
            out.append(it)
        elif isinstance(it, dict):
            out.append(it.get("body") or it.get("bodyFa") or "")
        else:
            raise ValueError("unexpected item type in %s: %r" % (field, it))
    return out


def as_title_body_list(items, field):
    """Normalise a dashboard list to [{title, body}] dicts."""
    out = []
    for it in items or []:
        if isinstance(it, dict):
            out.append({
                "title": it.get("title") or it.get("titleFa") or "",
                "body": it.get("body") or it.get("bodyFa") or "",
            })
        elif isinstance(it, str):
            out.append({"title": "", "body": it})
        else:
            raise ValueError("unexpected item type in %s: %r" % (field, it))
    return out


def as_label_url_list(items, field):
    out = []
    for it in items or []:
        if not isinstance(it, dict):
            raise ValueError("unexpected item type in %s: %r" % (field, it))
        out.append({"label": it.get("label", ""), "url": it.get("url", "")})
    return out


def build_yemen_doc(snap):
    """Map the dashboard v5 snapshot to the yemen.json schema
    (audit §4.2). Field-for-field copy; no invented values."""
    places = []
    for p in snap["places"]:
        places.append({
            "external_id": p["id"],
            "app_key": yemen_app_key(p["nameFa"], p["governorateFa"]),
            "name_fa": p["nameFa"],
            "name_en": p.get("nameEn", ""),
            "governorate_fa": p["governorateFa"],
            "lat": p["lat"],
            "lon": p["lon"],
            "faction": p["faction"],
            "control_label": p.get("controlLabel", ""),
            "confidence": p.get("confidence", ""),
            "note": p.get("note", ""),
            "provenance": p.get("provenance", ""),
            "source_note": p.get("sourceNote", ""),
            "updated_at": p.get("updatedAt"),
        })
    faction_counts = {}
    for p in places:
        faction_counts[p["faction"]] = faction_counts.get(p["faction"], 0) + 1

    frontlines = []
    for f in snap["frontlines"]:
        frontlines.append({
            "id": f["id"],
            "name_fa": f["nameFa"],
            "name_en": f.get("nameEn", ""),
            "status_kind": f.get("statusKind", ""),
            "status_fa": f.get("statusFa", ""),
            "trace_kind": f.get("traceKind", ""),
            "waypoints": [
                {"lat": w["lat"], "lon": w["lon"],
                 "name_fa": w.get("nameFa", ""),
                 "in_line": bool(w.get("inLine", False))}
                for w in f.get("waypoints") or []
            ],
            "side_a_fa": f.get("sideAFa", ""),
            "side_b_fa": f.get("sideBFa", ""),
            "confidence_fa": f.get("confidenceFa", ""),
            "summary_fa": f.get("summaryFa", ""),
            "governorates_fa": f.get("governoratesFa", ""),
            "movement_fa": f.get("movementFa", ""),
            "trace_note_fa": f.get("traceNoteFa", ""),
            "sources": as_label_url_list(f.get("sources") or [], "frontline.sources"),
        })

    arrows = []
    for a in snap["advanceArrows"]:
        arrows.append({
            "id": a["id"],
            "label_fa": a.get("labelFa", ""),
            "side": a.get("side", ""),
            "kind_fa": a.get("kindFa", ""),
            "from_lat": a["fromLat"],
            "from_lon": a["fromLon"],
            "to_lat": a["toLat"],
            "to_lon": a["toLon"],
            "date_fa": a.get("dateFa", ""),
            "confidence_fa": a.get("confidenceFa", ""),
            "note_fa": a.get("noteFa", ""),
            "source_fa": a.get("sourceFa", ""),
        })

    units = []
    for u in snap["unitPositions"]:
        units.append({
            "name_fa": u.get("nameFa", ""),
            "side": u.get("side", ""),
            "location_fa": u.get("locationFa", ""),
            "lat": u["lat"],
            "lon": u["lon"],
            "confidence_fa": u.get("confidenceFa", ""),
            "note_fa": u.get("noteFa", ""),
            "source_fa": u.get("sourceFa", ""),
            "uncertain": bool(u.get("uncertain", False)),
        })

    forces = []
    for fo in snap["forces"]:
        forces.append({
            "name": fo.get("name", ""),
            "faction_key": fo.get("factionKey", ""),
            "status": fo.get("status", ""),
            "body": fo.get("body", ""),
        })

    events = []
    for e in snap["events"]:
        events.append({
            "external_id": e["id"],
            "app_key": event_app_key(e["id"]),
            "event_date": e.get("eventDate", ""),
            "title_fa": e.get("titleFa", ""),
            "detail_fa": e.get("detailFa", ""),
            "origin": e.get("origin", ""),
            "corroboration": e.get("corroboration", 0),
            "families": e.get("families"),
            "source_name": e.get("sourceName", ""),
            "source_url": e.get("sourceUrl", ""),
        })

    aden = snap["aden"]
    aden_doc = {
        "verdict": {
            "title_fa": aden["verdict"].get("titleFa", ""),
            "body_fa": aden["verdict"].get("bodyFa", ""),
            "confidence_fa": aden["verdict"].get("confidenceFa", ""),
        },
        "ring_segments": [{
            "slot": s.get("slot", ""),
            "status_kind": s.get("statusKind", ""),
            "direction_fa": s.get("directionFa", ""),
            "status_fa": s.get("statusFa", ""),
            "body_fa": s.get("bodyFa", ""),
        } for s in aden["ringSegments"]],
        "timeline": [{
            "date": t.get("date", ""),
            "title_fa": t.get("titleFa", ""),
            "body_fa": t.get("bodyFa", ""),
            "layer": t.get("layer", ""),
            "source_fa": t.get("sourceFa", ""),
        } for t in aden["timeline"]],
        "key_points": as_str_list(aden["keyPoints"], "aden.keyPoints"),
        "sources": as_label_url_list(aden["sources"], "aden.sources"),
        "layers": {
            "confirmed": as_title_body_list(
                aden["layers"].get("confirmed") or [], "aden.layers.confirmed"),
            "official_claims": as_title_body_list(
                aden["layers"].get("officialClaims") or [], "aden.layers.officialClaims"),
            "rumours": as_title_body_list(
                aden["layers"].get("rumours") or [], "aden.layers.rumours"),
        },
        "contradictions": as_title_body_list(
            aden.get("contradictions") or [], "aden.contradictions"),
        "channels_method_fa": aden.get("channelsMethodFa", ""),
        "cross_check_summary_fa": aden.get("crossCheckSummaryFa", ""),
        "seen_note_fa": aden.get("seenNoteFa", ""),
        "taiz_note_fa": aden.get("taizNoteFa", ""),
        "open_questions": as_str_list(
            aden.get("openQuestions") or [], "aden.openQuestions"),
    }

    human_stats = []
    for hs in snap["humanStats"]:
        # Values stay STRINGS, verbatim from the dashboard — never
        # converted to numbers, never computed on (audit §4.1).
        if not isinstance(hs.get("value"), str):
            raise ValueError("human_stat value is not a string: %r" % hs)
        human_stats.append({
            "label": hs.get("label", ""),
            "value": hs["value"],
            "source": hs.get("source", ""),
        })

    snapshots = []
    for s in snap["snapshots"]:
        snapshots.append({
            "id": s.get("id"),
            "label": s.get("label", ""),
            "created_at": s.get("createdAt", ""),
            "places_count": s.get("placesCount", 0),
            "events_count": s.get("eventsCount", 0),
            "headline_fa": s.get("headlineFa", ""),
            "counts": [{
                "faction": c.get("faction", ""),
                "count": c.get("count", 0),
            } for c in s.get("counts") or []],
        })

    last = snap["lastRefresh"]
    doc = {
        "schema_version": SCHEMA_VERSION,
        "generated_at_utc": snap["stateUpdatedAt"],
        "as_of": snap["stateUpdatedAt"],
        "headline_fa": snap["headlineFa"],
        "last_refresh": {
            "status": last.get("status", ""),
            "started_at": last.get("startedAt", ""),
            "finished_at": last.get("finishedAt", ""),
            "radars_hit": last.get("radarsHit", 0),
            "claims_found": last.get("claimsFound", 0),
            "new_events": last.get("newEvents", 0),
            "control_changes": last.get("controlChanges", 0),
        },
        "counts": {
            "places": len(places),
            "events": len(events),
            "frontlines": len(frontlines),
            "arrows": len(arrows),
            "unit_positions": len(units),
            "forces": len(forces),
            "sources": len(snap["sources"]),
        },
        "faction_counts": faction_counts,
        "places": places,
        "frontlines": frontlines,
        "advance_arrows": arrows,
        "unit_positions": units,
        "forces": forces,
        "events": events,
        "claims": {
            "confirmed": as_title_body_list(
                snap["claims"].get("confirmed") or [], "claims.confirmed"),
            "contested": as_title_body_list(
                snap["claims"].get("contested") or [], "claims.contested"),
            "houthi_only": as_title_body_list(
                snap["claims"].get("houthiOnly") or [], "claims.houthiOnly"),
        },
        "aden": aden_doc,
        "human_stats": human_stats,
        "sources": as_label_url_list(snap["sources"], "sources"),
        "map_reading": as_title_body_list(
            snap.get("mapReading") or [], "mapReading"),
        "military_features": [{
            "title_fa": m.get("titleFa", ""),
            "kind_fa": m.get("kindFa", ""),
            "body_fa": m.get("bodyFa", ""),
            "confidence_fa": m.get("confidenceFa", ""),
            "source_fa": m.get("sourceFa", ""),
        } for m in snap.get("militaryFeatures") or []],
        "key_points": [{
            "name": k.get("name", ""),
            "status": k.get("status", ""),
            "status_kind": k.get("statusKind", ""),
            "confidence": k.get("confidence", ""),
            "basis": k.get("basis", ""),
        } for k in snap.get("keyPoints") or []],
        "open_questions": as_str_list(
            snap.get("openQuestions") or [], "openQuestions"),
        "disagreements": as_title_body_list(
            snap.get("disagreements") or [], "disagreements"),
        "snapshots": snapshots,
        "frontline_warning_fa": snap.get("frontlineWarningFa", ""),
    }
    return doc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--snapshot", default=DEFAULT_SNAPSHOT)
    ap.add_argument("--check-only", action="store_true")
    args = ap.parse_args()

    with open(args.snapshot, encoding="utf-8") as f:
        snap = json.load(f)
    doc = build_yemen_doc(snap)

    # Hard counts from the audit (§4.1) — the generator refuses
    # to write a document that disagrees with the snapshot.
    counts = doc["counts"]
    expected = {"places": 90, "events": 24, "frontlines": 8,
                "arrows": 9, "unit_positions": 12, "forces": 7,
                "sources": 25}
    for key, want in expected.items():
        if counts[key] != want:
            print("REFUSED: %s count %d != expected %d"
                  % (key, counts[key], want), file=sys.stderr)
            return 1
    want_factions = {"houthi": 39, "govt": 30, "contested": 16,
                     "aqap": 3, "stc": 1, "unknown": 1}
    if doc["faction_counts"] != want_factions:
        print("REFUSED: faction_counts %r != expected %r"
              % (doc["faction_counts"], want_factions), file=sys.stderr)
        return 1
    if len(doc["aden"]["ring_segments"]) != 5:
        print("REFUSED: aden ring segments %d != 5"
              % len(doc["aden"]["ring_segments"]), file=sys.stderr)
        return 1
    if len({p["app_key"] for p in doc["places"]}) != len(doc["places"]):
        print("REFUSED: duplicate place app_key", file=sys.stderr)
        return 1
    if len({e["app_key"] for e in doc["events"]}) != len(doc["events"]):
        print("REFUSED: duplicate event app_key", file=sys.stderr)
        return 1

    text = json.dumps(doc, ensure_ascii=False, indent=1) + "\n"
    doc_hash = section_hash(doc)
    print("yemen.json: places=%d events=%d frontlines=%d arrows=%d "
          "units=%d forces=%d sources=%d" % (
              counts["places"], counts["events"], counts["frontlines"],
              counts["arrows"], counts["unit_positions"],
              counts["forces"], counts["sources"]))
    print("faction_counts:", json.dumps(doc["faction_counts"],
                                        ensure_ascii=False, sort_keys=True))
    print("as_of:", doc["as_of"], "hash:", doc_hash)
    if args.check_only:
        print("check-only: counts verified, nothing written")
        return 0

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(EDGE_YEMEN, "w", encoding="utf-8") as f:
        f.write(text)
    os.makedirs(os.path.dirname(SEED_YEMEN), exist_ok=True)
    with open(SEED_YEMEN, "w", encoding="utf-8") as f:
        f.write(text)
    edge_sha = file_sha256(EDGE_YEMEN)
    seed_sha = file_sha256(SEED_YEMEN)
    if edge_sha != seed_sha:
        print("REFUSED: seed copy differs from edge file", file=sys.stderr)
        return 1

    # Manifest v3 -> v4 (additive): only sections.yemen is added
    # and the version number moves; every other section is carried
    # over byte-for-byte from the existing manifest.
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        manifest = json.load(f)
    if manifest.get("version") not in (3, 4):
        print("REFUSED: unexpected manifest version %r"
              % manifest.get("version"), file=sys.stderr)
        return 1
    generated_at_utc = datetime.now(timezone.utc).isoformat()
    manifest["version"] = MANIFEST_VERSION
    manifest["generated_at_utc"] = generated_at_utc
    manifest["generator"] = "edge/generate_yemen.py (v5.2, LAB ONLY)"
    manifest.setdefault("sections", {})["yemen"] = {
        "file": "yemen.json",
        "count": counts["places"],
        "hash": doc_hash,
        "file_sha256": edge_sha,
        "schema_version": SCHEMA_VERSION,
        "generated_at_utc": doc["generated_at_utc"],
        "update_cadence_hours": UPDATE_CADENCE_HOURS,
        "stale_after_hours": STALE_AFTER_HOURS,
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    manifest_sha = file_sha256(MANIFEST_PATH)

    # health.json, coherently: same manifest version and
    # generated_at_utc as the manifest just written, yemen entry,
    # manifest.json file SHA-256 recomputed.
    if os.path.exists(HEALTH_PATH):
        with open(HEALTH_PATH, encoding="utf-8") as f:
            health = json.load(f)
        health["manifest_version"] = MANIFEST_VERSION
        health["manifest_generated_at_utc"] = generated_at_utc
        health["generated_at_utc"] = generated_at_utc
        health["generator"] = "edge/generate_yemen.py (v5.2, LAB ONLY)"
        health.setdefault("sections", {})["yemen"] = {
            "count": counts["places"],
            "hash": doc_hash,
            "file": "yemen.json",
            "file_sha256": edge_sha,
        }
        health.setdefault("files", {})["manifest.json"] = manifest_sha
        health["files"]["yemen.json"] = edge_sha
        with open(HEALTH_PATH, "w", encoding="utf-8") as f:
            json.dump(health, f, ensure_ascii=False, indent=1)
        print("health.json updated (manifest_version 4)")

    print("wrote:", EDGE_YEMEN)
    print("wrote:", SEED_YEMEN)
    print("manifest.json: version 3 -> 4, sections.yemen count=%d "
          "hash=%s" % (counts["places"], doc_hash))
    return 0


if __name__ == "__main__":
    sys.exit(main())
