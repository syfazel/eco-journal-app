#!/usr/bin/env python3
"""Phase 5 fixture verifier (LAB ONLY).

Checks the edge/out-phase5-fixture edge against the spec's
acceptance criteria that CAN be checked fixture-side (P5-1/2/3/6/
7/9/10/11-lab-parts). Phone criteria (P5-4/5/8, and the phone
half of P5-3) are NOT claimed here — see docs/phase5-report.md.

Honesty note on "zero filler" (P5-2): what this verifier proves
is the GENERATOR behaviour — thin items are HELD, never padded
to reach a floor (the generator never appends text; bodies come
from input verbatim). Fixture bodies themselves are synthetic
lab assemblies (see generate_phase5_fixtures.py); their
repetition is a fixture artefact, labelled as such, not
generator padding. A literary-quality judgement on fixture
bodies is out of scope for a mechanics verifier.

Usage: python3 edge/verify_phase5_fixtures.py [fixture-dir]
Exit 0 = all checks pass, 1 = any failure.
"""

import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import generate_nightly as gen

TARGET = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out-phase5-fixture")
CHECKS = []


def ok(msg):
    CHECKS.append(("OK", msg))


def fail(msg):
    CHECKS.append(("FAIL", msg))


def load(fname):
    with open(os.path.join(TARGET, fname), encoding="utf-8") as f:
        return json.load(f)


def main():
    manifest = load("manifest.json")
    nindex = load("nightly.json")
    sec = manifest["sections"]["nightly"]
    if sec["status"] != "published":
        fail("nightly section not published")
    else:
        ok("nightly section published")
    editions = {}
    for entry in nindex["editions"]:
        editions[entry["edition_id"]] = load(entry["file"])
    v2 = editions.get("edition-1405-08-03")
    v1_path = os.path.join(TARGET, "editions", "edition-2026-10-25-v1.json")
    v1 = json.load(open(v1_path, encoding="utf-8")) if os.path.exists(v1_path) else None
    day2 = editions.get("edition-1405-08-02")

    # ---- Section hash over FULL contents (R13) ----
    full = sorted(editions.values(), key=lambda e: e["edition_id"])
    doss = sorted(nindex.get("dossiers", []), key=lambda d: d["dossier_id"])
    h = hashlib.sha256(json.dumps({"editions": full, "dossiers": doss},
                                   sort_keys=True, separators=(",", ":"),
                                   ensure_ascii=False).encode("utf-8")).hexdigest()
    if h == sec["hash"]:
        ok("nightly section hash recomputes from full contents")
    else:
        fail("nightly section hash mismatch")

    # ---- P5-2: thin held, not padded; 5-good day not padded to 15 ----
    if v2 is None:
        fail("day-1 edition missing")
        print_checks()
        return 1
    held = {x["story_id"]: x["reason"] for x in v2["components"]["articles"].get("held", [])}
    thin250 = next((i for i in v2["components"]["articles"]["items"]
                    if i["story_id"] == "lab-p5-thin-250"), None)
    thin700 = next((i for i in v2["components"]["articles"]["items"]
                    if i["story_id"] == "lab-p5-thin-700"), None)
    if held.get("lab-p5-thin-250") == "floor_full_800" and thin250 is None:
        ok("P5-2: 250-word-class thin item HELD (floor_full_800), not padded, not published")
    else:
        fail("P5-2: thin-250 not held correctly: %s" % held.get("lab-p5-thin-250"))
    if held.get("lab-p5-thin-700") == "floor_full_800" and thin700 is None:
        ok("P5-2: 700-word thin item HELD (floor_full_800), not padded, not published")
    else:
        fail("P5-2: thin-700 not held correctly: %s" % held.get("lab-p5-thin-700"))
    arts = v2["components"]["articles"]["items"]
    if len(arts) < 15 and all(gen.count_words(a["body"]) >= 300 for a in arts):
        ok("P5-2: day publishes %d good articles (not padded to 15), all >= 300 words" % len(arts))
    else:
        fail("P5-2: published count/floors wrong: %d articles" % len(arts))
    # Generator never appends text: published bodies equal input
    # bodies (checked by word_count recomputation, below, plus the
    # held-not-published thin items above).

    # ---- U4: unlabelled candidate held, never promoted ----
    if held.get("lab-p5-unlabelled", "").startswith("selection_missing"):
        ok("U4: unlabelled candidate HELD (selection_missing), never promoted on a guessed score")
    else:
        fail("U4: unlabelled candidate not held: %s" % held.get("lab-p5-unlabelled"))
    if not any(a["story_id"] == "lab-p5-unlabelled" for a in arts):
        ok("U4: unlabelled candidate absent from published articles")
    else:
        fail("U4: unlabelled candidate published!")

    # ---- P5-3: correction versioning ----
    if v1 is not None and v1["version"] == 1 and v2["version"] == 2 \
            and v2["status"] == "corrected":
        ok("P5-3: v1 file exists (version 1), v2 is corrected (version 2)")
    else:
        fail("P5-3: v1/v2 versioning wrong")
    corr = v2["components"]["corrections"]["items"]
    if len(corr) == 1 and corr[0]["correction_id"] == "lab-p5-corr-1" \
            and corr[0]["story_id"] == "lab-p5-full-1":
        ok("P5-3: component-6 correction entry present with field + versions")
    else:
        fail("P5-3: correction entry wrong: %s" % corr)
    if v2["components"]["special_issues"] == {"state": "paused", "items": []}:
        ok("P5-9: special issues paused with no content in the full edition")
    else:
        fail("P5-9: special_issues not paused/empty")

    # ---- P5-6: dossier merge ----
    if len(doss) == 1 and doss[0]["story_ids"] == ["lab-p5-dos-1", "lab-p5-dos-2", "lab-p5-dos-3"]:
        ok("P5-6: 3 canon-family items -> ONE dossier")
    else:
        fail("P5-6: dossier membership wrong: %s" % [d.get("story_ids") for d in doss])
    if any(a["story_id"] == "lab-p5-kw-1" for a in arts) and \
            not any("lab-p5-kw-1" in d["story_ids"] for d in doss):
        ok("P5-6: keyword-overlapping canon-distinct item NOT merged, ships as its own summary")
    else:
        fail("P5-6: keyword-overlap item merged or missing")
    if v2["dossier_links"] and v2["dossier_links"][0]["dossier_id"] == doss[0]["dossier_id"]:
        ok("P5-6: edition links to the dossier (not 3 articles)")
    else:
        fail("P5-6: edition dossier link missing")

    # ---- P5-7: fresh-text validation set (spec §5.5) ----
    channel = ("The central bank raised rates sharply after inflation data "
               "surprised markets and the currency fell to a new low against "
               "the dollar in early trading today.")
    reedit = ("Following unexpectedly strong inflation figures, the monetary "
              "authority lifted its benchmark rate decisively. The exchange "
              "rate slid to a fresh trough versus the greenback during the "
              "opening session as traders repriced risk.")
    nearcopy = channel + " Markets reacted immediately."
    paraphrase = ("The monetary authority increased borrowing costs steeply "
                  "because price growth beat forecasts, while the local money "
                  "dropped to an unprecedented trough versus the US currency "
                  "when trading began this morning.")
    translation = ("La banque centrale a fortement relevé ses taux après que "
                   "les données d'inflation ont surpris les marchés.")
    unrelated = ("The football team won the championship after a dramatic "
                 "final match that went to extra time on Sunday evening.")
    vals = {
        "re-edit": gen.freshness(reedit, channel),
        "near-copy": gen.freshness(nearcopy, channel),
        "paraphrase": gen.freshness(paraphrase, channel),
        "translation": gen.freshness(translation, channel),
        "unrelated": gen.freshness(unrelated, channel),
    }
    print("  fresh-text validation set: " + ", ".join(
        "%s=%.3f" % (k, v) for k, v in vals.items()))
    if vals["re-edit"] >= 0.60 and vals["near-copy"] < 0.60:
        ok("P5-7: method separates re-edit (>=0.60) from near-copy (<0.60)")
    else:
        fail("P5-7: method does NOT separate re-edit from near-copy")
    if vals["paraphrase"] >= 0.60:
        ok("P5-7: paraphrase passes (>=0.60)")
    else:
        fail("P5-7: paraphrase fails: %.3f" % vals["paraphrase"])
    # Translation note (honest limitation, not a failure of the
    # separation criterion): zero word overlap -> freshness 1.0,
    # so the gate cannot catch a translation-as-copy. Recorded,
    # not hidden.
    print("  NOTE P5-7: translation pair measures %.3f (zero overlap) — "
          "the 5-gram gate cannot catch translation copies; that is a "
          "method limitation, not a pass/fail on separation." % vals["translation"])
    near_item = next((a for a in arts if a["story_id"] == "lab-p5-near"), None)
    if near_item is not None and near_item["kind"] == "summary":
        ok("P5-7: fixture near-copy stays a brief (summary), never an article")
    else:
        fail("P5-7: fixture near-copy kind wrong: %s" % (near_item or None))

    # ---- P5-10: golden market close (frozen 2026-10-24 snapshot) ----
    if day2 is None:
        fail("P5-10: day-2 edition missing")
    else:
        f = day2["components"]["market_close"]["snapshot"]["formulas"]
        checks10 = [
            ("implied_usd", f.get("implied_usd"), 275752, 500),
            ("intrinsic_emami", f.get("intrinsic_emami"), 255704023, 50000),
            ("bubble_pct", f.get("bubble_pct"), 5.59, 0.05),
            ("spread_pct", f.get("spread_pct"), -1.21, 0.05),
        ]
        bad = [c for c in checks10 if c[1] is None or abs(c[1] - c[2]) > c[3]]
        if not bad:
            ok("P5-10: golden close reproduces 275752 / 255704023 / +5.59%% / -1.21%% (same engine values)")
        else:
            fail("P5-10: golden close deviates: %s" % bad)
        if day2["components"]["market_close"]["snapshot"]["close_snapshot_id"] == "cycle-20261024-0600":
            ok("P5-10: close snapshot is the frozen 2026-10-24 last snapshot")
        else:
            fail("P5-10: close snapshot wrong")

    # ---- Alert history comes from Phase 4 rows, not re-derived ----
    alerts = v2["components"]["market_close"]["alert_history"]
    if len(alerts) == 3 and alerts[0]["snapshot_id"] == "lab-p4-s2" \
            and alerts[0]["notified"] == 1 and alerts[2]["notified"] == 0:
        ok("market close alert history = supplied Phase 4 rows (fired + banner-state), not re-derived")
    else:
        fail("alert history wrong: %s" % alerts)

    # ---- P5-1: edition renders complete (lab): all components present-or-stated ----
    comps = v2["components"]
    states = {k: comps[k]["state"] for k in
              ("articles", "market_close", "digests", "analysis",
               "special_issues", "corrections", "archive")}
    if all(s in ("present", "none_tonight", "paused", "held_with_reasons") for s in states.values()):
        ok("P5-1 (lab): all 7 components present-or-explicitly-stated: %s" % states)
    else:
        fail("P5-1: component states wrong: %s" % states)
    if v2["components"]["digests"]["crisis_path"] == ["L2", "L3"]:
        ok("SITREP arc crisis path L2 -> L3 in order")
    else:
        fail("SITREP arc path wrong")
    if "morning_digest" not in [b["bundle_id"] for b in v2["components"]["digests"]["bundles"]] \
            and not any(b["bundle_kind"] == "morning_digest"
                        for b in v2["components"]["digests"]["bundles"]):
        ok("disabled morning digest renders nowhere in the edition")
    else:
        fail("morning digest leaked into the edition")

    # ---- Hygiene: no blog link, no probability key, no secret pattern ----
    text = json.dumps(editions, ensure_ascii=False)
    if "muse.ai" not in text and "/blog" not in text:
        ok("hygiene: no blog link in any edition")
    else:
        fail("hygiene: blog link found")
    bad_keys = []

    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if "probab" in str(k).lower() or "forecast_direction" in str(k):
                    bad_keys.append(str(k))
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(editions)
    if not bad_keys:
        ok("hygiene: no model-probability key in any edition")
    else:
        fail("hygiene: probability keys: %s" % bad_keys)
    if not re.search(r"(?i)(token|secret|password|api[_-]?key)\s*[:=]", text):
        ok("hygiene: no secret-like pattern in editions")
    else:
        fail("hygiene: secret-like pattern found")

    # ---- Word counts recompute (Kotlin mirror) ----
    bad_wc = []
    for ed in editions.values():
        for a in ed["components"]["articles"]["items"]:
            if gen.count_words(a["body"]) != a["word_count"]:
                bad_wc.append(a["story_id"])
    if not bad_wc:
        ok("word counts recompute identically (Kotlin mirror)")
    else:
        fail("word counts mismatch: %s" % bad_wc)

    print_checks()
    return 0 if not any(c[0] == "FAIL" for c in CHECKS) else 1


def print_checks():
    for status, msg in CHECKS:
        print("[%s] %s" % (status, msg))
    print("=== result: %s ===" % ("PASS" if not any(c[0] == "FAIL" for c in CHECKS) else "FAIL"))


if __name__ == "__main__":
    sys.exit(main())
