#!/usr/bin/env python3
"""Yemen edge verifier — v5.2.0, LAB ONLY.

Re-checks edge/out/yemen.json (and its identical seed copy) against
the dashboard v5 snapshot it was generated from, and the manifest
v4 yemen section against the file itself. Fails (exit 1) on any
mismatch. Read-only: never writes.

Checks:
  * counts 90 places / 24 events / 8 frontlines / 9 arrows /
    12 unit positions / 7 forces / 25 sources
  * faction counts houthi 39 / govt 30 / contested 16 / aqap 3 /
    stc 1 / unknown 1
  * every place app_key == 'y-' + first 20 hex of
    SHA-256(name_fa + '|' + governorate_fa); every event app_key ==
    'y-event-' + first 20 hex of SHA-256(str(external_id))
  * no duplicate app_keys; as_of == snapshot stateUpdatedAt
  * human_stats values are strings, verbatim from the snapshot
  * Aden: 5 ring segments, verdict present
  * seed copy byte-identical to the edge file
  * manifest sections.yemen: file/count/hash/file_sha256/
    schema_version/cadence/stale match the file; manifest version 4

Usage:
  python3 edge/verify_yemen.py [--dir DIR] [--snapshot PATH]
"""

import argparse
import hashlib
import json
import os
import sys

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SNAPSHOT = os.path.join(
    HOME, "workspace", "goals", "eco-journal",
    "hidden_files", "yemen-dashboard-v5-20261118.json",
)


def canonical_json(obj):
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(SCRIPT_DIR, "out"))
    ap.add_argument("--snapshot", default=DEFAULT_SNAPSHOT)
    args = ap.parse_args()

    fails = []
    oks = []

    def check(cond, msg):
        if cond:
            oks.append(msg)
        else:
            fails.append(msg)

    with open(args.snapshot, encoding="utf-8") as f:
        snap = json.load(f)
    edge_path = os.path.join(args.dir, "yemen.json")
    # v6.0 (Flutter rebuild): the bundled seed lives in assets/seed/.
    seed_path = os.path.join(APP_ROOT, "assets", "seed", "yemen.json")
    if not os.path.exists(edge_path):
        print("FAIL: edge yemen.json missing: %s" % edge_path, file=sys.stderr)
        return 1
    with open(edge_path, encoding="utf-8") as f:
        doc = json.load(f)

    check(doc.get("schema_version") == 1, "schema_version == 1")
    check(doc.get("as_of") == snap["stateUpdatedAt"],
          "as_of == snapshot stateUpdatedAt (%s)" % snap["stateUpdatedAt"])
    counts = doc.get("counts", {})
    for key, want in (("places", 90), ("events", 24), ("frontlines", 8),
                      ("arrows", 9), ("unit_positions", 12),
                      ("forces", 7), ("sources", 25)):
        check(counts.get(key) == want,
              "counts.%s == %d (got %r)" % (key, want, counts.get(key)))
        check(len(doc.get({
            "places": "places", "events": "events",
            "frontlines": "frontlines", "arrows": "advance_arrows",
            "unit_positions": "unit_positions", "forces": "forces",
            "sources": "sources",
        }[key], [])) == want, "list length %s == %d" % (key, want))
    check(doc.get("faction_counts") == {
        "houthi": 39, "govt": 30, "contested": 16,
        "aqap": 3, "stc": 1, "unknown": 1,
    }, "faction_counts == 39/30/16/3/1/1 (got %r)"
        % (doc.get("faction_counts"),))

    bad_keys = []
    seen = set()
    for p in doc.get("places", []):
        want = "y-" + sha20(p["name_fa"] + "|" + p["governorate_fa"])
        if p.get("app_key") != want:
            bad_keys.append(p.get("name_fa"))
        if p.get("app_key") in seen:
            bad_keys.append("dup:" + str(p.get("app_key")))
        seen.add(p.get("app_key"))
        # provenance + sourceNote travel from day one (P1-26 lesson)
        if "provenance" not in p or "source_note" not in p:
            bad_keys.append("no-prov:" + str(p.get("name_fa")))
    check(not bad_keys, "all place app_keys recompute, unique, provenance present"
          + ("" if not bad_keys else " — bad: %r" % bad_keys[:5]))
    bad_events = []
    seen_e = set()
    for e in doc.get("events", []):
        want = "y-event-" + sha20(str(e["external_id"]))
        if e.get("app_key") != want:
            bad_events.append(e.get("external_id"))
        if e.get("app_key") in seen_e:
            bad_events.append("dup")
        seen_e.add(e.get("app_key"))
    check(not bad_events, "all event app_keys recompute, unique"
          + ("" if not bad_events else " — bad: %r" % bad_events[:5]))

    snap_stats = {s["label"]: s["value"] for s in snap["humanStats"]}
    doc_stats = {s["label"]: s["value"] for s in doc.get("human_stats", [])}
    check(doc_stats == snap_stats,
          "human_stats verbatim strings from snapshot")
    check(all(isinstance(s.get("value"), str)
              for s in doc.get("human_stats", [])),
          "human_stats values are all strings")

    aden = doc.get("aden", {})
    check(len(aden.get("ring_segments", [])) == 5,
          "aden ring_segments == 5")
    check(bool(aden.get("verdict", {}).get("title_fa")),
          "aden verdict present")
    # v5.6 (R4): the Aden investigation sections the app used to
    # drop must travel verbatim from the snapshot.
    for key, want in (("images", 7), ("cross_check", 7),
                      ("channels_official", 5), ("social_posts", 9),
                      ("amplifiers", 4), ("sources", 28)):
        check(len(aden.get(key, [])) == want,
              "aden.%s == %d (got %r)"
              % (key, want, len(aden.get(key, []))))
    snap_aden = snap.get("aden", {})
    # Field-for-field verbatim check: snapshot camelCase -> doc
    # snake_case, same order, same values.
    _aden_maps = {
        "images": (("name", "name"), ("origin_fa", "originFa"),
                   ("shows_fa", "showsFa"), ("verdict_fa", "verdictFa"),
                   ("url", "url")),
        "cross_check": (("date_fa", "dateFa"), ("says_fa", "saysFa"),
                        ("source", "source")),
        "channels_official": (("name", "name"), ("handle", "handle"),
                              ("kind_fa", "kindFa"), ("body_fa", "bodyFa"),
                              ("verdict_fa", "verdictFa")),
        "social_posts": (("name", "name"), ("kind_fa", "kindFa"),
                         ("date_fa", "dateFa"), ("body_fa", "bodyFa"),
                         ("url", "url")),
        "amplifiers": (("name", "name"), ("date_fa", "dateFa"),
                       ("body_fa", "bodyFa"), ("url", "url")),
        "sources": (("label", "label"), ("url", "url")),
    }
    for key, snap_key in (("images", "images"),
                          ("cross_check", "crossCheck"),
                          ("channels_official", "channelsOfficial"),
                          ("social_posts", "socialPosts"),
                          ("amplifiers", "amplifiers"),
                          ("sources", "sources")):
        pairs = _aden_maps[key]
        snap_items = snap_aden.get(snap_key, [])
        doc_items = aden.get(key, [])
        verbatim = len(snap_items) == len(doc_items) and all(
            all(d.get(dk, "") == s.get(sk, "") for dk, sk in pairs)
            for d, s in zip(doc_items, snap_items)
        )
        check(verbatim, "aden.%s verbatim from snapshot %s (%d items)"
              % (key, snap_key, len(snap_items)))
    check(bool(doc.get("frontline_warning_fa")),
          "frontline_warning_fa present")

    check(os.path.exists(seed_path), "seed copy exists: %s" % seed_path)
    if os.path.exists(seed_path):
        check(file_sha256(seed_path) == file_sha256(edge_path),
              "seed copy byte-identical to edge yemen.json")

    manifest_path = os.path.join(args.dir, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    check(manifest.get("version") == 4, "manifest version == 4")
    sec = manifest.get("sections", {}).get("yemen")
    check(sec is not None, "manifest sections.yemen present")
    if sec is not None:
        check(sec.get("file") == "yemen.json", "manifest yemen.file")
        check(sec.get("count") == 90, "manifest yemen.count == 90")
        check(sec.get("hash") == section_hash(doc),
              "manifest yemen.hash == canonical hash of yemen.json")
        check(sec.get("file_sha256") == file_sha256(edge_path),
              "manifest yemen.file_sha256 == file bytes")
        check(sec.get("schema_version") == 1, "manifest yemen.schema_version == 1")
        check(sec.get("update_cadence_hours") == 24,
              "manifest yemen.update_cadence_hours == 24")
        check(sec.get("stale_after_hours") == 24,
              "manifest yemen.stale_after_hours == 24")

    for msg in oks:
        print("OK:", msg)
    if fails:
        for msg in fails:
            print("FAIL:", msg, file=sys.stderr)
        return 1
    print("VERIFY YEMEN PASS: %d checks" % len(oks))
    return 0


if __name__ == "__main__":
    sys.exit(main())
