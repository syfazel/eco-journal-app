#!/usr/bin/env python3
"""Verify a Phase 2 fixture edge dir against the spec, WITHOUT a phone.

Checks fixture-side facts only: the app's own logic is verified by
the phone test (P2-A1–P2-A13) and by code review, not by this script.
This script proves the FIXTURES cover every criterion: all formats,
night-law cases (computed from Tehran wall-clock, independent of
the Kotlin code), held-item invisibility sets, bundle member order,
Morning Bundle composition/order, radar layers/staleness, malformed
fixtures, fake-id hygiene, and the absence of blog URLs, model
probabilities, and live channel data.

Usage: python3 edge/verify_phase2_fixtures.py [fixture-dir]
Exit 0 = all checks pass; non-zero = list of failures.
"""

import hashlib
import json
import os
import re
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TEH = ZoneInfo("Asia/Tehran")
FAILURES = []


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " — " + detail))
    if not cond:
        FAILURES.append(name)


def tehran(dt_epoch):
    return datetime.fromtimestamp(dt_epoch, tz=TEH)


def is_night(ep):
    return tehran(ep).hour < 8


def night_law(it):
    fmt = it["format_type"]
    score = it.get("urgency_score")
    level = it.get("crisis_level")
    if fmt == "FLASH":
        return "instant"
    if is_night(it["published_at_epoch"]) and fmt == "WATCH" and level in ("L3", "L4"):
        return "instant"
    if is_night(it["published_at_epoch"]):
        if score is None:
            return "feed_only"
        if score >= 85:
            return "instant"
        if 70 <= score <= 84:
            return "held"
        return "feed_only"
    if score is None:
        return "feed_only"
    return "instant" if score >= 70 else "feed_only"


def section_hash(items4):
    ordered = sorted(items4, key=lambda d: d["app_key"])
    canonical = json.dumps(ordered, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "out-phase2-fixture")
    manifest = json.load(open(os.path.join(target, "manifest.json"), encoding="utf-8"))
    typed = json.load(open(os.path.join(target, "channel_typed.json"), encoding="utf-8"))
    bundles_doc = json.load(open(os.path.join(target, "bundles.json"), encoding="utf-8"))
    radar_doc = json.load(open(os.path.join(target, "radar.json"), encoding="utf-8"))
    items = typed["items"]
    bundles = bundles_doc["bundles"]
    signals = radar_doc["signals"]
    by_key = {it["app_key"]: it for it in items}
    check("fixture label present", typed.get("label") == "LAB FIXTURE")

    # --- Formats (P2-A1) ---
    formats = {it["format_type"] for it in items if it["status"] == "posted"}
    for fmt in ("FLASH", "WATCH", "GEOPOL", "MARKET", "VERIFY", "SITREP", "WIRE"):
        check("format present: " + fmt, fmt in formats)
    market = [it for it in items if it["format_type"] == "MARKET" and it["status"] == "posted"]
    check("MARKET card has prices line", any(it.get("prices_json") for it in market))
    verify = [it for it in items if it["format_type"] == "VERIFY" and it["status"] == "posted"]
    check("VERIFY card has verdict line",
          any(it.get("verdict_text") and it.get("verdict_status") for it in verify))
    sitreps = [it for it in items if it["format_type"] == "SITREP" and it["status"] == "posted"]
    good_sitreps = [it for it in sitreps
                    if len(it.get("sitrep_sections_json") or []) == 5
                    and len(it.get("watch_next_json") or []) == 2]
    check("SITREP with 5 sections + 2 watch-next exists", len(good_sitreps) >= 1)
    wire_link = [it for it in items if it["format_type"] == "WIRE" and it.get("source_link")]
    check("WIRE-with-link fixture exists (P2-A13)", len(wire_link) >= 1)

    # --- Night law (P2-A3, P2-A4), computed independently ---
    decisions = {}
    for it in items:
        if it["status"] != "posted":
            continue
        if it.get("urgency_score") is not None and not (0 <= it["urgency_score"] <= 100):
            decisions[it["app_key"]] = "feed_only (out-of-range)"
        else:
            decisions[it["app_key"]] = night_law(it)
    held = [k for k, v in decisions.items() if v == "held"]
    instant = [k for k, v in decisions.items() if v == "instant"]
    check("held fixture items exist (night 70-84)", len(held) >= 2,
          "held=%s" % held)
    check("held items are night-published",
          all(is_night(by_key[k]["published_at_epoch"]) for k in held))
    check("instant fixture items exist", len(instant) >= 3, "instant=%s" % instant)
    check("FLASH night no-score is instant (exemption)",
          decisions.get("lab-flash-night-noscore") == "instant")
    check("WATCH L3 night score 50 is instant (exemption)",
          decisions.get("lab-watch-l3-night") == "instant")
    check("WATCH L2 night score 75 is held",
          decisions.get("lab-watch-l2-night-held") == "held")
    check("GEOPOL night score 80 is held",
          decisions.get("lab-geopol-night-held") == "held")
    check("held items invisible set = held keys (feed/rail/search must exclude)",
          set(held) == {"lab-watch-l2-night-held", "lab-geopol-night-held"},
          "held=%s" % held)

    # --- War Room (P2-A2) ---
    wr = radar_doc.get("war_room", {})
    check("war_room level shipped", wr.get("crisis_level") in ("L1", "L2", "L3", "L4"))
    check("war_room why line shipped", bool(wr.get("why_level")))
    check("radar interval shipped", isinstance(wr.get("radar_interval_minutes"), int))
    day_sitreps = [it for it in sitreps
                   if tehran(it["published_at_epoch"]).strftime("%Y-%m-%d") == "2026-10-25"]
    check("two SITREPs same day for arc + level change", len(day_sitreps) >= 2)
    levels = [it["crisis_level"] for it in sorted(day_sitreps, key=lambda x: x["published_at_epoch"])]
    check("arc level path changes", len(set(levels)) >= 2, "levels=%s" % levels)

    # --- Radar (P2-A8) ---
    layers = {s["layer"] for s in signals}
    check("radar layers 1/2/3 present", {1, 2, 3} <= layers, "layers=%s" % layers)
    check("radar unclassified layer present", 9 in layers)
    stale = [s for s in signals if s["signal_id"] == "lab-radar-stale"]
    check("stale signal detected previous day",
          bool(stale) and tehran(stale[0]["detected_at_epoch"]).day == 24)
    check("radar generates no notification type (no notif field)",
          all("notification" not in json.dumps(s) for s in signals))

    # --- Bundles + Morning Bundle (P2-A5, P2-A7) ---
    morning = [b for b in bundles if b["bundle_kind"] == "morning_bundle"]
    check("exactly one morning bundle", len(morning) == 1)
    mb = morning[0] if morning else None
    if mb:
        members = mb["member_app_keys"]
        check("morning bundle contains held items",
              any(k in held for k in members), "members=%s" % members)
        check("morning bundle contains a SITREP", any(
            by_key.get(k, {}).get("format_type") == "SITREP" for k in members))
        check("morning bundle member order has positions (no dupes)",
              len(members) == len(set(members)))
        # A retracted member must be present to prove the honest line.
        check("morning bundle lists a retracted member",
              any(by_key.get(k, {}).get("status") == "retracted" for k in members))
    nightdesk = [b for b in bundles if b["bundle_kind"] == "night_desk"]
    check("night desk bundle 12-30 items",
          len(nightdesk) == 1 and 12 <= len(nightdesk[0]["member_app_keys"]) <= 30)
    shortnews = [b for b in bundles if b["bundle_kind"] == "short_news"]
    check("short-news bundle >= 3 members",
          len(shortnews) == 1 and len(shortnews[0]["member_app_keys"]) >= 3)
    check("morning digest bundle present but must be blocked by app",
          any(b["bundle_kind"] == "morning_digest" for b in bundles))
    check("unknown-kind bundle present (header held by app)",
          any(b["bundle_kind"] not in (
              "slot_bundle", "short_news", "marginalia", "hourly_digest",
              "morning_bundle", "night_desk", "morning_digest") for b in bundles))
    # Bundle position integrity for well-formed bundles.
    for b in bundles:
        if b["bundle_kind"] in ("morning_digest", "mystery_kind"):
            continue
        for idx, key in enumerate(b["member_app_keys"]):
            it = by_key.get(key)
            if it and it.get("bundle_id") == b["bundle_id"]:
                check("bundle %s position %d" % (b["bundle_id"], idx + 1),
                      it.get("bundle_position") == idx + 1)

    # --- Slots (P2-A7) ---
    slots = {it.get("slot") for it in items if it["status"] == "posted"}
    check("slots morning/midday/evening/night present",
          {"morning", "midday", "evening", "night"} <= slots, "slots=%s" % slots)
    check("NULL-slot item present (renders as «بدون بازه»)",
          any(it.get("slot") is None and it["status"] == "posted" for it in items))

    # --- Story dedup (one story = one card) ---
    dup = [it for it in items if it.get("story_id") == "story-lab-dup"]
    check("story-dedup pair present", len(dup) == 2)

    # --- Integrity fixtures (P2-A13, P2-A10) ---
    bad_sitrep = [it for it in items if it["app_key"] == "lab-sitrep-bad"]
    check("malformed SITREP (3 sections, 1 watch-next)",
          bool(bad_sitrep)
          and len(bad_sitrep[0]["sitrep_sections_json"]) == 3
          and len(bad_sitrep[0]["watch_next_json"]) == 1)
    check("unknown-format item present (must be skipped)",
          any(it["format_type"] == "NOTAFORMAT" for it in items))
    check("empty-headline item present (must be skipped)",
          any(it["headline"] == "" for it in items))
    check("retracted item present (bookmark-orphan test)",
          any(it["status"] == "retracted" for it in items))
    check("out-of-range score item present",
          any(it.get("urgency_score") == 150 for it in items))

    # --- Hygiene (P2-A12) ---
    fake_ids = [it["channel_message_id"] for it in items
                if it.get("channel_message_id") is not None]
    check("all fixture channel ids are fake (>= 9000001)",
          all(i >= 9000001 for i in fake_ids), "ids=%s" % fake_ids)
    blob = ""
    for name in ("channel_typed.json", "bundles.json", "radar.json"):
        blob += open(os.path.join(target, name), encoding="utf-8").read()
    check("no blog URL in fixtures", "blog" not in blob.lower()
          or "BLOG_LIVE" in blob)
    check("no probability field in fixtures",
          "probability" not in blob.lower() and "prob_" not in blob.lower())
    check("no http source link except example.invalid",
          all("example.invalid" in u or u is None
              for u in re.findall(r"https?://[^\s\"']+", blob)))

    # --- Manifest hashes recompute (spec 2.1) ---
    for section, entries, key_field in (
            ("channel_typed", items, "app_key"),
            ("bundles", bundles, "bundle_id"),
            ("radar", signals, "signal_id")):
        view = [{"app_key": e[key_field], "content_hash": e["content_hash"],
                 "updated_at_epoch": e["updated_at_epoch"],
                 "status": e["status"]} for e in entries]
        check("manifest hash recomputes: " + section,
              manifest["sections"][section]["hash"] == section_hash(view))
        check("manifest section published: " + section,
              manifest["sections"][section]["status"] == "published")
    check("special_issues still gated_frozen",
          manifest["sections"]["special_issues"]["status"] == "gated_frozen")
    check("nightly/voices still not_yet_published",
          manifest["sections"]["nightly"]["status"] == "not_yet_published"
          and manifest["sections"]["voices"]["status"] == "not_yet_published")

    print()
    if FAILURES:
        print("FAILURES: %d" % len(FAILURES))
        return 1
    print("ALL FIXTURE CHECKS PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
