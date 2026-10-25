#!/usr/bin/env python3
"""Phase 6 fixtures (LAB ONLY, synthetic text, never editorial content).

Builds edge/out-phase6-fixture/ from edge/out-phase5-fixture/ by
adding ONE new nightly edition (1405-08-04) whose analysis
component exercises the Phase 6 parser gates:

- lab-p6-oped-1: op-ed, 1700 words, all 4 structure sections,
  energy category, no format type -> YELLOW bar case.
- lab-p6-weekly-1: weekly, 3600 words, all 4 sections, GEOPOL
  format type -> RED bar case, consensus matrix (2 agreement +
  1 divergence outlets), correction_count 2 -> P6-A3 case.
- lab-p6-thin-1: op-ed, 250 words -> HELD (floor_300), never padded.
- lab-p6-monthly-1: monthly-ml, 2200 words -> HELD (gated).
- lab-p6-nosection-1: op-ed, 1500 words, counter_argument
  missing -> HELD (analysis_structure).
- lab-p6-single-1: weekly, 3200 words, single-outlet matrix ->
  HELD (consensus_invalid).

All bodies are synthetic lab assemblies (one repeated 10-word
sentence), counted by whitespace split like NightlyWords.count.
No real article, no real voice name, no real special-issue post,
no model probability, no blog URL appears anywhere. The new
edition's articles/market/digests/corrections components are
none_tonight and special_issues is paused with no items, so the
Phase 5 publish verifier still passes. Manifest generator string
is deliberately UNCHANGED (Phase 5 string) because
publish_edge.py only accepts the Phase 1/3/5 strings; only the
nightly section hash/count/items are recomputed (full-contents
hash, Phase 5 R13 rule).

Also writes edge/phase6-topstories-fixture.json (standalone,
editorial-basis card + vote-field trap object) for the verifier.
The card is NOT wired into any edge edition: no real week's card
ships until the §5.4 decision is recorded.

Usage: python3 edge/generate_phase6_fixtures.py
Output: edge/out-phase6-fixture/ (served ONLY for the phone
test, never pushed to the pilot Pages edge).
"""
import hashlib
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
SRC = os.path.join(APP, "edge", "out-phase5-fixture")
DST = os.path.join(APP, "edge", "out-phase6-fixture")

SENTENCE = "این جملهٔ آزمایشی برای سنجش طول متن نوشته شده است"
assert len(SENTENCE.split()) == 10


def body(reps):
    return " ".join([SENTENCE] * reps)


def sections(thesis, evidence, counter, scenarios):
    # Fixed order thesis -> evidence -> counter_argument -> scenarios.
    out = {
        "thesis": body(thesis),
        "evidence": body(evidence),
    }
    if counter is not None:
        out["counter_argument"] = body(counter)
    out["scenarios"] = body(scenarios)
    return out


def words(sec):
    return sum(len(v.split()) for v in sec.values())


def analysis_item(story_id, kind, title, sec, extra=None):
    item = {
        "story_id": story_id,
        "title": title,
        "analysis_type": kind,
        "thesis_line": "تز آزمایشی " + story_id,
        "sections": sec,
        "word_count": words(sec),
        "published_shamsi": "۱۴۰۵-۰۸-۰۴",
        "source_links": ["https://example.org/lab-source"],
        "correction_count": 0,
    }
    if extra:
        item.update(extra)
    return item


def main():
    if not os.path.isdir(SRC):
        print("missing base fixture edge: %s" % SRC, file=sys.stderr)
        return 1
    if os.path.isdir(DST):
        shutil.rmtree(DST)
    shutil.copytree(SRC, DST)

    oped_sec = sections(15, 80, 40, 35)          # 1700 words
    weekly_sec = sections(30, 200, 70, 60)      # 3600 words
    thin_sec = sections(5, 10, 5, 5)            # 250 words
    monthly_sec = sections(20, 120, 40, 40)     # 2200 words
    nosection_sec = sections(30, 80, None, 40)  # 1500 words, no counter
    single_sec = sections(30, 170, 60, 60)      # 3200 words
    assert words(oped_sec) == 1700
    assert words(weekly_sec) == 3600
    assert words(thin_sec) == 250
    assert words(monthly_sec) == 2200
    assert words(nosection_sec) == 1500
    assert words(single_sec) == 3200

    consensus = {
        "topic": "موضوع آزمایشی همگرایی",
        "as_of_shamsi": "۱۴۰۵-۰۸-۰۴",
        "agreement": [
            {"outlet": "منبع آزمایشی یک", "position": "موضع آزمایشی یک",
             "source_url": "https://example.org/lab-a", "date": "۱۴۰۵-۰۸-۰۳",
             "is_quote": True},
            {"outlet": "منبع آزمایشی دو", "position": "موضع آزمایشی دو",
             "source_url": "https://example.org/lab-b", "date": "۱۴۰۵-۰۸-۰۳",
             "is_quote": False},
        ],
        "divergence": [
            {"outlet": "منبع آزمایشی سه", "position": "موضع آزمایشی سه",
             "source_url": "https://example.org/lab-c", "date": "۱۴۰۵-۰۸-۰۲",
             "is_quote": False},
        ],
    }
    single_consensus = {
        "topic": "موضوع آزمایشی تک‌منبع",
        "as_of_shamsi": "۱۴۰۵-۰۸-۰۴",
        "agreement": [
            {"outlet": "منبع آزمایشی یک", "position": "موضع آزمایشی یک",
             "source_url": "https://example.org/lab-a", "date": "۱۴۰۵-۰۸-۰۳",
             "is_quote": False},
        ],
        "divergence": [],
    }

    items = [
        analysis_item("lab-p6-oped-1", "op-ed", "یادداشت آزمایشی فاز ۶",
                      oped_sec, {"category": "energy"}),
        analysis_item("lab-p6-weekly-1", "weekly", "تحلیل هفتگی آزمایشی فاز ۶",
                      weekly_sec, {"format_type": "GEOPOL", "correction_count": 2,
                                   "consensus_matrix": consensus}),
        analysis_item("lab-p6-thin-1", "op-ed", "یادداشت نازک آزمایشی",
                      thin_sec),
        analysis_item("lab-p6-monthly-1", "monthly-ml", "گزارش ماهانهٔ آزمایشی",
                      monthly_sec),
        analysis_item("lab-p6-nosection-1", "op-ed", "یادداشت بی‌مخالف‌خوانی آزمایشی",
                      nosection_sec),
        analysis_item("lab-p6-single-1", "weekly", "تحلیل تک‌منبع آزمایشی",
                      single_sec, {"consensus_matrix": single_consensus}),
    ]

    edition = {
        "edition_id": "edition-1405-08-04",
        "shamsi_date": "۱۴۰۵-۰۸-۰۴",
        "tehran_date": "2026-10-26",
        "version": 1,
        "status": "published",
        "generated_at_tehran": "2026-10-27T01:30:00+03:30",
        "published_at_tehran": "2026-10-27T01:30:00+03:30",
        "cut_tehran": "2026-10-26T23:59:00+03:30",
        "components": {
            "articles": {"state": "none_tonight", "items": [], "held": []},
            "market_close": {"state": "none_tonight", "snapshot": None,
                             "stale_close": False, "alert_history": [],
                             "thresholds": None},
            "digests": {"state": "none_tonight", "bundles": [],
                        "night_desk": None, "sitrep_arc": [],
                        "crisis_path": []},
            "analysis": {"state": "present", "items": items,
                         "consensus_matrix": None},
            "special_issues": {"state": "paused", "items": []},
            "corrections": {"state": "none_tonight", "items": []},
            "archive": {"state": "present",
                        "edition_index_entry": "edition-1405-08-04"},
        },
        "dossier_links": [],
        "generator_report_ref": "lab-fixture-phase6",
        "updated_at_epoch": 1793086200000,
    }
    canon = lambda o: json.dumps(o, sort_keys=True, separators=(",", ":"),
                                 ensure_ascii=False)
    edition["content_hash"] = hashlib.sha256(
        canon({k: v for k, v in edition.items()
               if k != "content_hash"}).encode("utf-8")).hexdigest()
    efile = "editions/edition-2026-10-26-v1.json"
    with open(os.path.join(DST, efile), "w", encoding="utf-8") as f:
        json.dump(edition, f, ensure_ascii=False, indent=2)

    npath = os.path.join(DST, "nightly.json")
    nindex = json.load(open(npath, encoding="utf-8"))
    nindex["editions"].append({
        "edition_id": "edition-1405-08-04",
        "file": efile,
        "version": 1,
        "status": "published",
        "content_hash": edition["content_hash"],
    })
    with open(npath, "w", encoding="utf-8") as f:
        json.dump(nindex, f, ensure_ascii=False, indent=2)

    full = []
    for entry in nindex["editions"]:
        full.append(json.load(open(os.path.join(DST, entry["file"]),
                                   encoding="utf-8")))
    full_sorted = sorted(full, key=lambda e: e["edition_id"])
    doss_sorted = sorted(nindex.get("dossiers", []),
                         key=lambda d: d.get("dossier_id", ""))
    nhash = hashlib.sha256(
        canon({"editions": full_sorted,
               "dossiers": doss_sorted}).encode("utf-8")).hexdigest()

    mpath = os.path.join(DST, "manifest.json")
    manifest = json.load(open(mpath, encoding="utf-8"))
    nsec = manifest["sections"]["nightly"]
    nsec["hash"] = nhash
    nsec["count"] = len(nindex["editions"])
    nsec["items"] = [{
        "id": e["edition_id"],
        "app_key": e["edition_id"],
        "content_hash": next(
            x["content_hash"] for x in nindex["editions"]
            if x["edition_id"] == e["edition_id"]),
        "updated_at_epoch": e["updated_at_epoch"],
        "status": e["status"],
        "file": next(x["file"] for x in nindex["editions"]
                     if x["edition_id"] == e["edition_id"]),
    } for e in full_sorted]
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    topstories = {
        "card": {
            "week_range": "۱۴۰۵-۰۸-۰۳ تا ۱۴۰۵-۰۸-۰۹",
            "selection_basis": "editorial",
            "stories": [
                {"title": "داستان آزمایشی یک", "story_id": "lab-p6-oped-1",
                 "format_type": "GEOPOL", "date_shamsi": "۱۴۰۵-۰۸-۰۴",
                 "source": "منبع آزمایشی"},
                {"title": "داستان آزمایشی دو", "story_id": "lab-p6-weekly-1",
                 "format_type": "WIRE", "date_shamsi": "۱۴۰۵-۰۸-۰۳",
                 "source": "منبع آزمایشی"},
                {"title": "داستان آزمایشی سه", "story_id": "lab-p5-full-1",
                 "format_type": None, "date_shamsi": "۱۴۰۵-۰۸-۰۲",
                 "source": "منبع آزمایشی"},
            ],
        },
        "vote_field_trap": {
            "week_range": "۱۴۰۵-۰۸-۰۳ تا ۱۴۰۵-۰۸-۰۹",
            "selection_basis": "editorial",
            "stories": [{"title": "داستان آزمایشی یک",
                          "story_id": "lab-p6-oped-1",
                          "date_shamsi": "۱۴۰۵-۰۸-۰۴",
                          "source": "منبع آزمایشی"}],
            "vote_count": 42,
        },
    }
    with open(os.path.join(APP, "edge", "phase6-topstories-fixture.json"),
              "w", encoding="utf-8") as f:
        json.dump(topstories, f, ensure_ascii=False, indent=2)
    print("phase6 fixture edge: %s (nightly editions: %d, analysis items: %d)"
          % (DST, len(nindex["editions"]), len(items)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
