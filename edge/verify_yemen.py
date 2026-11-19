#!/usr/bin/env python3
"""Yemen edge verifier — v6.1.0 data revision, LAB ONLY.

Re-checks edge/out/yemen.json (and its identical seed copy) against
the dashboard v5 snapshot it was generated from, the manifest v4
yemen section, the front registry, and the GeoJSON assets. Fails
(exit 1) on any mismatch. Read-only: never writes.

Checks (v6.1.0, post government-source removal — see
edge/SOURCING-RULES.md):
  * counts 90 places / 20 events / 8 frontlines / 8 arrows /
    10 unit positions / 7 forces / 18 sources
  * faction counts houthi 39 / govt 30 / contested 16 / aqap 3 /
    stc 1 / unknown 1
  * every place app_key == 'y-' + first 20 hex of
    SHA-256(name_fa + '|' + governorate_fa); every event app_key ==
    'y-event-' + first 20 hex of SHA-256(str(external_id))
  * no duplicate app_keys; as_of == snapshot stateUpdatedAt
  * human_stats values are strings, verbatim from the snapshot
  * Aden: 5 ring segments, verdict present, timeline 7, images 6,
    cross_check 7, channels_official 5, social_posts 9,
    amplifiers 4, sources 23
  * NO forbidden government-source string survives in the curated
    data (edge/sourcing.py MUST_NOT_SURVIVE; live feed text is
    excluded — verbatim Ansarullah posts may contain ordinary
    Arabic words such as «شفق» = dawn)
  * removal log edge/out/removal-log.json: 26 removed, 5 nulled
  * feeds: section present; every item has a https://t.me/ url, a
    parseable ISO timestamp, tier ansarullah-official, non-empty
    verbatim text, unique id/url; >=1 real post per available
    channel (a channel listed under "unavailable" is degraded,
    not a failure)
  * taxonomy: taxonomy_version == 1; every event has a valid
    event_type, confidence and front == "yemen"
  * fronts: yemen active (data_file yemen.json), ukraine
    coming_soon scaffold with no data; doc mirrors edge/fronts.json
  * GeoJSON: assets/geo/yemen-adm1.geojson exists with 22 features
    and bounds inside Yemen; yemen-outline.geojson exists
  * seed copy byte-identical to the edge file
  * manifest sections.yemen: file/count/hash/file_sha256/
    schema_version/cadence/stale match the file; manifest version 4

NOTE: the Aden verbatim-from-snapshot checks of v5.2 are GONE on
purpose — v6.1.0 deliberately deviates from the snapshot (the
government-source removal rewrites headlines, sources and several
Aden entries). The snapshot checks that remain (as_of, human_stats)
cover the parts the removal must not touch.

Usage:
  python3 edge/verify_yemen.py [--dir DIR] [--snapshot PATH]
  python3 edge/verify_yemen.py --no-snapshot [--dir DIR]

--no-snapshot (v6.0, for CI): the lab dashboard snapshot is NOT in the
repo and must never be committed, so CI cannot run the snapshot
comparisons (as_of, human_stats verbatim). This mode skips exactly
those checks and still runs every repo-self-contained check.
"""

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime

# v6.0: repo root derived from this script's location, never from a
# hard-coded lab path. The lab snapshot default below is the ONLY
# lab-only path left, and it is only used when --snapshot is given
# explicitly / without --no-snapshot.
HOME = os.path.expanduser("~")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(SCRIPT_DIR)
DEFAULT_SNAPSHOT = os.path.join(
    HOME, "workspace", "goals", "eco-journal",
    "hidden_files", "yemen-dashboard-v5-20261118.json",
)
sys.path.insert(0, SCRIPT_DIR)
import sourcing  # noqa: E402 — the v6.1.0 forbidden-string list


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


def _bounds_ok(doc_geo):
    xs, ys = [], []
    def walk(c):
        if isinstance(c[0], (int, float)):
            xs.append(c[0])
            ys.append(c[1])
        else:
            for x in c:
                walk(x)
    for f in doc_geo["features"]:
        walk(f["geometry"]["coordinates"])
    return (41.0 <= min(xs) and max(xs) <= 55.5
            and 11.0 <= min(ys) and max(ys) <= 20.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(SCRIPT_DIR, "out"))
    ap.add_argument("--snapshot", default=DEFAULT_SNAPSHOT)
    ap.add_argument("--no-snapshot", action="store_true",
                    help="skip snapshot comparisons (CI: snapshot is lab-only)")
    args = ap.parse_args()

    fails = []
    oks = []

    def check(cond, msg):
        if cond:
            oks.append(msg)
        else:
            fails.append(msg)

    snap = None
    if not args.no_snapshot:
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
    if snap is not None:
        check(doc.get("as_of") == snap["stateUpdatedAt"],
              "as_of == snapshot stateUpdatedAt (%s)" % snap["stateUpdatedAt"])
    else:
        oks.append("as_of check skipped (--no-snapshot)")
    counts = doc.get("counts", {})
    for key, want in (("places", 90), ("events", 20), ("frontlines", 8),
                      ("arrows", 8), ("unit_positions", 10),
                      ("forces", 7), ("sources", 18)):
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

    if snap is not None:
        snap_stats = {s["label"]: s["value"] for s in snap["humanStats"]}
        doc_stats = {s["label"]: s["value"] for s in doc.get("human_stats", [])}
        check(doc_stats == snap_stats,
              "human_stats verbatim strings from snapshot")
    else:
        oks.append("human_stats verbatim check skipped (--no-snapshot)")
    check(all(isinstance(s.get("value"), str)
              for s in doc.get("human_stats", [])),
          "human_stats values are all strings")

    aden = doc.get("aden", {})
    check(len(aden.get("ring_segments", [])) == 5,
          "aden ring_segments == 5")
    check(bool(aden.get("verdict", {}).get("title_fa")),
          "aden verdict present")
    # v6.1.0 (post-removal counts): images 7 -> 6 (Sheba map out),
    # sources 28 -> 23, timeline 7 (661 entry out).
    for key, want in (("timeline", 7), ("images", 6), ("cross_check", 7),
                      ("channels_official", 5), ("social_posts", 9),
                      ("amplifiers", 4), ("sources", 23)):
        check(len(aden.get(key, [])) == want,
              "aden.%s == %d (got %r)"
              % (key, want, len(aden.get(key, []))))
    check(bool(doc.get("frontline_warning_fa")),
          "frontline_warning_fa present")

    # ---- v6.1.0: government-source removal gate.
    bad_strings = sourcing.check_no_forbidden_strings(doc)
    check(not bad_strings,
          "no forbidden government-source string survives"
          + ("" if not bad_strings else " — found: %r" % (bad_strings,)))
    log_path = os.path.join(args.dir, "removal-log.json")
    check(os.path.exists(log_path), "removal-log.json exists: %s" % log_path)
    if os.path.exists(log_path):
        with open(log_path, encoding="utf-8") as f:
            log = json.load(f)
        n_removed = sum(1 for r in log if r.get("action") == "removed")
        n_nulled = sum(1 for r in log if r.get("action") == "source_nulled")
        check(n_removed == 26,
              "removal log: 26 items removed (got %d)" % n_removed)
        check(n_nulled == 5,
              "removal log: 5 sources nulled (got %d)" % n_nulled)
    kept = sourcing.remaining_tier_c_mentions(doc)
    check(kept.get("Sheba", 0) == 0,
          "no 'Sheba' string survives (got %d)" % kept.get("Sheba", 0))
    print("INFO: kept Tier C mentions (documented in SOURCING-RULES.md): %s"
          % json.dumps(kept, ensure_ascii=False, sort_keys=True))

    # ---- v6.1.0: feeds — real posts only, per available channel.
    feeds = doc.get("feeds")
    check(isinstance(feeds, dict) and isinstance(feeds.get("items"), list),
          "feeds section present with an items list")
    if isinstance(feeds, dict):
        items = feeds.get("items", [])
        unavailable = feeds.get("unavailable", [])
        unav_channels = {u.get("channel") for u in unavailable}
        check(all(isinstance(u.get("channel"), str)
                  and isinstance(u.get("error"), str) for u in unavailable),
              "feeds.unavailable entries carry channel + error")
        bad_feed = []
        seen_ids, seen_urls = set(), set()
        for it in items:
            ok = (
                isinstance(it.get("id"), str)
                and isinstance(it.get("channel"), str)
                and it.get("url", "").startswith("https://t.me/")
                and it.get("tier") == "ansarullah-official"
                and isinstance(it.get("text_fa"), str)
                and it["text_fa"].strip()
                and isinstance(it.get("published_at"), str)
            )
            if ok:
                try:
                    datetime.fromisoformat(it["published_at"])
                except ValueError:
                    ok = False
            if it.get("id") in seen_ids or it.get("url") in seen_urls:
                ok = False
            seen_ids.add(it.get("id"))
            seen_urls.add(it.get("url"))
            if not ok:
                bad_feed.append(it.get("id"))
        check(not bad_feed,
              "every feed item has a t.me url, ISO timestamp, "
              "ansarullah-official tier, non-empty text, unique id/url"
              + ("" if not bad_feed else " — bad: %r" % bad_feed[:5]))
        fronts_doc = doc.get("fronts", {})
        yemen_front = next(
            (fr for fr in fronts_doc.get("fronts", [])
             if fr.get("id") == "yemen"), {})
        for ch in yemen_front.get("feed_channels", []):
            if ch in unav_channels:
                oks.append("feed channel %s degraded (listed unavailable)"
                           % ch)
            else:
                n = sum(1 for it in items if it.get("channel") == ch)
                check(n >= 1,
                      "feed channel %s has >=1 real post (got %d)" % (ch, n))

    # ---- v6.1.0: taxonomy on every event.
    check(doc.get("taxonomy_version") == 1, "taxonomy_version == 1")
    bad_tax = []
    for e in doc.get("events", []):
        if (e.get("event_type") not in sourcing.EVENT_TYPES
                or e.get("confidence") not in sourcing.CONFIDENCES
                or e.get("front") != "yemen"):
            bad_tax.append(e.get("external_id"))
    check(not bad_tax, "every event has a valid event_type/confidence/front"
          + ("" if not bad_tax else " — bad: %r" % bad_tax[:5]))

    # ---- v6.1.0: front registry mirrors edge/fronts.json.
    fronts_path = os.path.join(SCRIPT_DIR, "fronts.json")
    check(os.path.exists(fronts_path), "edge/fronts.json exists")
    fronts_doc = doc.get("fronts", {})
    if os.path.exists(fronts_path):
        with open(fronts_path, encoding="utf-8") as f:
            registry = json.load(f)
        check(fronts_doc == registry,
              "yemen.json fronts mirror edge/fronts.json")
    by_id = {fr.get("id"): fr for fr in fronts_doc.get("fronts", [])}
    yemen = by_id.get("yemen", {})
    ukraine = by_id.get("ukraine", {})
    check(yemen.get("status") == "active"
          and yemen.get("data_file") == "yemen.json"
          and yemen.get("geojson") == "assets/geo/yemen-adm1.geojson"
          and yemen.get("feed_channels") == ["army21ye", "MilitaryMediaY",
                                             "Almasirah_En"],
          "fronts: yemen active with data_file, geojson, feed channels")
    check(ukraine.get("status") == "coming_soon"
          and ukraine.get("data_file") is None
          and ukraine.get("geojson") is None
          and ukraine.get("feed_channels") == [],
          "fronts: ukraine is a coming_soon scaffold with no data")

    # ---- v6.1.0: GeoJSON assets.
    adm1_path = os.path.join(APP_ROOT, "assets", "geo", "yemen-adm1.geojson")
    outline_path = os.path.join(APP_ROOT, "assets", "geo",
                                "yemen-outline.geojson")
    check(os.path.exists(adm1_path),
          "assets/geo/yemen-adm1.geojson exists")
    check(os.path.exists(outline_path),
          "assets/geo/yemen-outline.geojson exists")
    if os.path.exists(adm1_path):
        with open(adm1_path, encoding="utf-8") as f:
            adm1 = json.load(f)
        check(len(adm1.get("features", [])) == 22,
              "yemen-adm1.geojson has 22 features (got %d)"
              % len(adm1.get("features", [])))
        check(_bounds_ok(adm1), "yemen-adm1.geojson bounds within Yemen")
        names = {ft["properties"].get("adm1_pcode")
                 for ft in adm1.get("features", [])}
        check(len(names) == 22 and all(names),
              "yemen-adm1.geojson: 22 distinct adm1_pcode values")
    if os.path.exists(outline_path):
        with open(outline_path, encoding="utf-8") as f:
            outline = json.load(f)
        check(len(outline.get("features", [])) == 1,
              "yemen-outline.geojson has 1 feature")
        check(_bounds_ok(outline), "yemen-outline.geojson bounds within Yemen")

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
