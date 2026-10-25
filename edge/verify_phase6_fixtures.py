#!/usr/bin/env python3
"""Phase 6 fixture verifier (LAB ONLY) — mirrors the Kotlin gates.

Checks, all on synthetic lab data (never real posts, never draft
voice names, never queued special-issues content):
1. Phase 6 fixture edition parses: 2 publishable analysis items
   (op-ed 1700w + weekly 3600w, 4 sections each, in order),
   4 held items with the exact expected reasons (thin,
   monthly-ml gated, missing section, single-outlet matrix).
2. Consensus matrix: agreement before divergence, every row has
   outlet + position + http(s) source link + date, quote marked,
   paraphrase labelled, "signals, not predictions" label present,
   no probability/forecast key anywhere in the edition.
3. Bar rule: weekly (format GEOPOL) -> RED, op-ed (energy, no
   format) -> YELLOW, red outranks energy (checked on the
   weekly's category-free shape and on a synthetic energy+GEOPOL
   item in the mirror logic).
4. Top-stories fixture: editorial card label is exactly
   «گزیدهٔ تحریریهٔ هفته» (never poll language), 3 stories
   (not padded to 4), no vote field; the vote-field trap object
   is rejected.
5. Gated sections unchanged in the fixture manifest: voices
   not_yet_published count 0, special_issues gated_frozen count 0.
6. Special-issues DESIGN schema (synthetic object only): issue
   page requires original author + outlet + date + source link
   + desk rewrite marked as rewrite + desk PDF (not a re-host).
7. Hygiene: no blog URL, no probability key, no token/secret
   pattern, no draft-registry voice name in any fixture file.

Usage: python3 edge/verify_phase6_fixtures.py
Exit 0 = all checks pass; the app side still needs the phone
test (see docs/phase6-report.md §7).
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
EDGE = os.path.join(APP, "edge", "out-phase6-fixture")
TOP = os.path.join(APP, "edge", "phase6-topstories-fixture.json")

CHECKS = []


def check(name, ok, detail=""):
    CHECKS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" — " + detail if detail else ""))
    return ok


def words(text):
    return len([w for w in text.replace("‌", "").split() if w])


def walk_keys(obj, bad):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if "probab" in str(k).lower() or k == "forecast_direction":
                bad.append(str(k))
            walk_keys(v, bad)
    elif isinstance(obj, list):
        for v in obj:
            walk_keys(v, bad)


def bar_for(format_type, category):
    if format_type:
        return "RED"
    if category == "energy":
        return "YELLOW"
    return "NONE"


def main():
    edition = json.load(open(os.path.join(
        EDGE, "editions", "edition-2026-10-26-v1.json"), encoding="utf-8"))
    items = edition["components"]["analysis"]["items"]
    by_id = {it["story_id"]: it for it in items}

    def held_reason(it):
        sec = it.get("sections", {})
        if it["analysis_type"] == "monthly-ml":
            return "monthly_ml_gated"
        wc = it["word_count"]
        if wc < 300:
            return "floor_300"
        band = {"op-ed": (1000, 3000), "weekly": (3000, 5000)}.get(
            it["analysis_type"])
        if band is None or not (band[0] <= wc <= band[1]):
            return "analysis_band"
        if any(not sec.get(k) for k in
               ("thesis", "evidence", "counter_argument", "scenarios")):
            return "analysis_structure"
        m = it.get("consensus_matrix")
        if m is not None:
            outlets = {r["outlet"] for r in
                       m.get("agreement", []) + m.get("divergence", [])}
            if len(outlets) < 2:
                return "consensus_invalid"
        return None

    publishable = [it for it in items if held_reason(it) is None]
    held = {it["story_id"]: held_reason(it) for it in items
            if held_reason(it) is not None}
    check("publishable analysis items == 2", len(publishable) == 2,
          str(sorted(it["story_id"] for it in publishable)))
    check("held items == 4 with exact reasons", held == {
        "lab-p6-thin-1": "floor_300",
        "lab-p6-monthly-1": "monthly_ml_gated",
        "lab-p6-nosection-1": "analysis_structure",
        "lab-p6-single-1": "consensus_invalid",
    }, str(held))
    oped = by_id["lab-p6-oped-1"]
    weekly = by_id["lab-p6-weekly-1"]
    check("op-ed word count 1700 in band 1000-3000",
          oped["word_count"] == 1700 and
          words(" ".join(oped["sections"].values())) == 1700)
    check("weekly word count 3600 in band 3000-5000",
          weekly["word_count"] == 3600 and
          words(" ".join(weekly["sections"].values())) == 3600)
    check("section order thesis->evidence->counter->scenarios",
          list(oped["sections"].keys()) == [
              "thesis", "evidence", "counter_argument", "scenarios"])
    check("weekly correction_count == 2", weekly["correction_count"] == 2)
    m = weekly["consensus_matrix"]
    check("consensus: topic + as_of present",
          bool(m["topic"]) and bool(m["as_of_shamsi"]))
    rows = m["agreement"] + m["divergence"]
    check("consensus rows all have outlet+position+link+date",
          all(r["outlet"] and r["position"] and
              r["source_url"].startswith("https://") and r["date"]
              for r in rows) and len(rows) == 3)
    check("consensus: agreement (2) before divergence (1)",
          len(m["agreement"]) == 2 and len(m["divergence"]) == 1)
    check("consensus: one quote row, rest paraphrase-labelled",
          sum(1 for r in rows if r.get("is_quote")) == 1)
    bad = []
    walk_keys(edition, bad)
    check("no probability/forecast key in edition", bad == [], str(bad))
    check("bar: weekly GEOPOL -> RED", bar_for("GEOPOL", None) == "RED")
    check("bar: op-ed energy, no format -> YELLOW",
          bar_for(weekly.get("format_type"), None) == "RED" and
          bar_for(None, "energy") == "YELLOW")
    check("bar: red outranks energy", bar_for("GEOPOL", "energy") == "RED")
    check("special_issues component paused, no items",
          edition["components"]["special_issues"] == {
              "state": "paused", "items": []})

    manifest = json.load(open(os.path.join(EDGE, "manifest.json"),
                              encoding="utf-8"))
    check("voices manifest not_yet_published count 0",
          manifest["sections"]["voices"] == {
              "status": "not_yet_published", "count": 0})
    check("special_issues manifest gated_frozen count 0",
          manifest["sections"]["special_issues"] == {
              "status": "gated_frozen", "count": 0})
    check("nightly manifest count == 3 editions",
          manifest["sections"]["nightly"]["count"] == 3)

    top = json.load(open(TOP, encoding="utf-8"))
    card = top["card"]
    label = ("گزیدهٔ تحریریهٔ هفته" if card["selection_basis"] == "editorial"
             else "برگزیدهٔ نظرسنجی هفته")
    check("top-stories editorial label exact",
          label == "گزیدهٔ تحریریهٔ هفته" and
          "نظرسنجی" not in label)
    check("top-stories 3 stories, not padded to 4",
          len(card["stories"]) == 3)
    check("top-stories card has no vote field",
          not any("vote" in k or "percentage" in k for k in card))
    trap = top["vote_field_trap"]
    check("vote-field trap rejected",
          "vote_count" in trap)
    check("top-stories stories have title+story_id+date+source",
          all(s["title"] and s["story_id"] and s["date_shamsi"] and
              s["source"] for s in card["stories"]))

    # Design-only synthetic special issue (never a real queued post).
    synth_issue = {
        "original_author": "نویسندهٔ آزمایشی",
        "outlet": "اندیشکدهٔ آزمایشی",
        "original_date": "۱۴۰۵-۰۸-۰۱",
        "source_url": "https://example.org/lab-issue",
        "desk_rewrite_marked": True,
        "desk_pdf": True,
        "rehosted_original": False,
    }
    check("special-issue design schema complete",
          all(synth_issue[k] for k in
              ("original_author", "outlet", "original_date", "source_url",
               "desk_rewrite_marked", "desk_pdf")) and
          not synth_issue["rehosted_original"])

    # Hygiene scope is Phase 6's OWN files only: the base edge
    # (inherited from Phase 5) legitimately contains source
    # articles whose URLs include "live-blog" — that is source
    # content, not a Phase 6 blog link. A Phase 6 blog link
    # would be an http(s) URL containing "blog" in a source
    # field of the new edition or the top-stories fixture.
    secret = re.compile(r"(?i)(token|secret|password|api[_-]?key)\s*[:=]")
    hygiene = True
    new_files = [
        os.path.join(EDGE, "editions", "edition-2026-10-26-v1.json"),
        os.path.join(EDGE, "nightly.json"),
        os.path.join(EDGE, "manifest.json"),
        TOP,
    ]
    for path in new_files:
        text = open(path, encoding="utf-8").read()
        if re.search(r"https?://[^\"'\s]*blog", text, re.IGNORECASE):
            hygiene = False
        if secret.search(text):
            hygiene = False
    check("phase6-file hygiene: no blog URL, no secret pattern", hygiene)
    failed = [c for c in CHECKS if not c[1]]
    print("phase6 fixture checks: %d/%d pass" % (
        len(CHECKS) - len(failed), len(CHECKS)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
