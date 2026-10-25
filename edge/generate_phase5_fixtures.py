#!/usr/bin/env python3
"""Phase 5 lab edge fixtures (LAB ONLY — never production data).

Builds edge/out-phase5-fixture from edge/out-phase4-fixture by
running the REAL nightly generator (edge/generate_nightly.py) on
two lab day-dirs, then rewriting the manifest nightly section
(full-content hash, spec §5.12/R13).

LAB-ONLY SELECTION — READ THIS: the selection file below is
fixture data, labelled "lab-fixture-selection". It exists ONLY
so the phone test can exercise component 1. It is NOT a
production score, NOT Yasin's editorial decision, and NOT a
resolution of U4: real Tier-A / brief / ownrewrite selection
remains UNPINNED (see docs/phase5-report.md). A fixture
selection must never reach a distributed APK or the pilot edge;
this fixture edge is served only for the phone test, never
pushed to the pilot Pages edge.

Fixture days:
- Day 1: Tehran 2026-10-25 / Shamsi 1405-08-03 (the Phase 2
  fixture day). 5 good articles (3 Tier-A full + 1 ownrewrite
  full + summaries), thin items held (250w, 700w), a near-copy
  downgraded to summary, an unlabelled U4-held item, a 3-member
  dossier + a keyword-overlapping canon-distinct item, market
  close from the Phase 4 fixture snapshots, Phase 2 bundles +
  night desk + SITREP arc, one op-ed + consensus matrix, and a
  v1 -> v2 correction pair (P5-3).
- Day 2: Tehran 2026-10-24 / Shamsi 1405-08-02. 2 summaries,
  market close on the FROZEN last snapshot (the P5-10 golden
  vector), no bundles / no analysis / no corrections — the
  none_tonight states.

Fixture bodies are SYNTHETIC lab text (assembled from distinct
lab sentences, not real journalism). They exercise floors,
fresh-text, dedup and rendering mechanics only; they must never
be read as editorial content. Every fixture id starts "lab-p5-";
no real channel data, no real invite code, no blog URL, no
model probability anywhere. Thresholds in the copied market.json
are the Phase 4 LAB-ONLY values (lab-fixture-only), never
defaults.

Usage:
    python3 edge/generate_phase5_fixtures.py [--base BASE] [target-dir]
Default base: edge/out-phase4-fixture. Default target:
edge/out-phase5-fixture (lab output; never the live edge).
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TEH = ZoneInfo("Asia/Tehran")
HERE = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.join(HERE, "out-phase4-fixture")
sys.path.insert(0, HERE)
import generate_nightly as gen  # noqa: E402  (lab mirror of the same rules)

# Distinct lab sentence templates. Template C (nightly bodies)
# and template B (channel posts) share NO 5-word run, so good
# re-edits measure freshness near 1.0; the near-copy pair shares
# its text by construction (freshness near 0.0).
TEMPLATE_C = [
    "در گزارش آزمایشی امروز، تحلیل تازه‌ای از روند بازار و سیاست ارائه می‌شود که با داده‌های روز همخوانی دارد.",
    "کارشناسان آزمایشگاهی در این نسخه به بررسی پیامدهای تصمیم‌های اخیر پرداخته‌اند و چند سناریوی محتمل را مرور کرده‌اند.",
    "این بخش از متن آزمایشی برای سنجش طول مقاله و انسجام ساختار نوشته شده و محتوای خبری واقعی ندارد.",
    "خوانندهٔ آزمایشی با مطالعهٔ این پاراگراف می‌تواند کیفیت نمایش متن طولانی در صفحهٔ نسخهٔ شبانه را ارزیابی کند.",
    "در ادامهٔ این مقالهٔ آزمایشی، نکات تکمیلی دربارهٔ موضوع روز با زبانی ساده و بدون ادعای خبری بیان می‌شود.",
    "هدف از این پاراگراف، رساندن طول متن به کف واژگانی موردنیاز است؛ نه اطلاع‌رسانی و نه تحلیل واقعی.",
]
TEMPLATE_B = [
    "خبرگزاری آزمایشی گزارش داد که رویداد تازه‌ای در بازار رخ داده و واکنش‌های اولیه نیز منتشر شده است.",
    "به گفتهٔ منابع آزمایشی، این رویداد در ساعات گذشته مورد توجه قرار گرفته و جزئیات بیشتری در راه است.",
    "گزارش کوتاه آزمایشگاهی حاکی از آن است که موضوع روز همچنان در دست بررسی قرار دارد.",
]


def words(text):
    return gen.count_words(text)


def build_body(seed, target):
    """Assemble a synthetic body of >= target words from template C
    sentences, each tagged with the seed + index so sentences vary
    across articles. Returns the body string."""
    parts = ["متن آزمایشی «%s»؛ این مقاله صرفاً برای آزمون سازوکار نسخهٔ شبانه ساخته شده است." % seed]
    i = 0
    while words(" ".join(parts)) < target:
        parts.append("%s (بخش %d از گزارش آزمایشی %s)" % (TEMPLATE_C[i % len(TEMPLATE_C)], i + 1, seed))
        i += 1
    return " ".join(parts)


def build_channel_body(seed, target=80):
    parts = ["خبر آزمایشی «%s»؛" % seed]
    i = 0
    while words(" ".join(parts)) < target:
        parts.append(TEMPLATE_B[i % len(TEMPLATE_B)])
        i += 1
    return " ".join(parts)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else BASE_DIR
    target = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "out-phase5-fixture")
    # Resolve positional args robustly (base/target may be flags order).
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) >= 1:
        base = args[0]
    if len(args) >= 2:
        target = args[1]
    if os.path.exists(target):
        shutil.rmtree(target)
    shutil.copytree(base, target)
    staging = "/tmp/phase5-fixture-staging"
    if os.path.exists(staging):
        shutil.rmtree(staging)
    os.makedirs(staging, exist_ok=True)

    typed = json.load(open(os.path.join(base, "channel_typed.json")))["items"]
    title_by_key = {t["app_key"]: t["headline"] for t in typed}
    bundles_base = json.load(open(os.path.join(base, "bundles.json")))["bundles"]
    market = json.load(open(os.path.join(base, "market.json")))

    def story(sid, seed, label, source, **kw):
        d = {"story_id": sid, "app_key": "lab-p5-%s" % sid,
             "title": label, "source_name": source}
        d.update(kw)
        return d

    # ---- Day 1 candidates ----
    cands = []
    cands.append(story("lab-p5-full-1", "full-1", "مقالهٔ آزمایشی کامل یکم",
                       "دنیای اقتصاد", nightly_body=build_body("full-1", 850),
                       channel_post_body=build_channel_body("full-1"),
                       attribution="translated", format_type="GEOPOL",
                       crisis_level="L2", category="energy", category_fa="انرژی"))
    cands.append(story("lab-p5-full-2", "full-2", "مقالهٔ آزمایشی کامل دوم",
                       "تابناک", nightly_body=build_body("full-2", 850),
                       channel_post_body=build_channel_body("full-2"),
                       attribution="verbatim"))
    cands.append(story("lab-p5-full-3", "full-3", "مقالهٔ آزمایشی کامل سوم",
                       "همشهری‌آنلاین", nightly_body=build_body("full-3", 850),
                       channel_post_body=build_channel_body("full-3"),
                       attribution="translated", category="rial", category_fa="ریال"))
    cands.append(story("lab-p5-sum-1", "sum-1", "خلاصهٔ آزمایشی یکم",
                       "دنیای اقتصاد", nightly_body=build_body("sum-1", 330),
                       channel_post_body=build_channel_body("sum-1"),
                       attribution="translated"))
    cands.append(story("lab-p5-sum-2", "sum-2", "خلاصهٔ آزمایشی دوم",
                       "ایسنا", nightly_body=build_body("sum-2", 340),
                       channel_post_body=build_channel_body("sum-2"),
                       attribution="translated"))
    cands.append(story("lab-p5-own-1", "own-1", "مقالهٔ آزمایشی بازنویسی‌شده",
                       "دنیای اقتصاد", nightly_body=build_body("own-1", 850),
                       channel_post_body=build_channel_body("own-1"),
                       attribution="translated"))
    cands.append(story("lab-p5-thin-250", "thin-250", "مقالهٔ نازک ۲۵۰ واژه‌ای",
                       "ایسنا", nightly_body=build_body("thin-250", 250),
                       channel_post_body=build_channel_body("thin-250"),
                       attribution="translated"))
    cands.append(story("lab-p5-thin-700", "thin-700", "مقالهٔ نازک ۷۰۰ واژه‌ای",
                       "ایسنا", nightly_body=build_body("thin-700", 700),
                       channel_post_body=build_channel_body("thin-700"),
                       attribution="translated"))
    # Near-copy: nightly body IS the channel body (plus one changed
    # sentence) at 450 words -> downgrades to summary, never article.
    near_channel = build_body("near", 450)
    # Near-copy shape: nightly = channel text with only the final
    # sentence changed (freshness near 0.0 -> downgrades to brief).
    near_nightly = near_channel[:near_channel.rfind(" (بخش")] + " پایان گزارش آزمایشی."
    cands.append(story("lab-p5-near", "near", "مقالهٔ نزدیک به کپی",
                       "ایسنا", nightly_body=near_nightly,
                       channel_post_body=near_channel,
                       attribution="translated"))
    cands.append(story("lab-p5-unlabelled", "unlab", "مقالهٔ بدون برچسب گزینش",
                       "ایسنا", nightly_body=build_body("unlab", 900),
                       channel_post_body=build_channel_body("unlab"),
                       attribution="translated"))
    for n in (1, 2, 3):
        cands.append(story("lab-p5-dos-%d" % n, "dos-%d" % n,
                           "خبر آزمایشی آتش‌بس %d" % n,
                           "دنیای اقتصاد",
                           nightly_body=build_body("dos-%d" % n, 330),
                           channel_post_body=build_channel_body("dos-%d" % n),
                           attribution="translated",
                           dossier_group="topic-a",
                           dossier_title_fa="پروندهٔ آزمایشی آتش‌بس",
                           dossier_topic_slug="ceasefire",
                           dossier_week_start="1405-08-03",
                           dossier_summary=build_body("dossier-a", 320),
                           first_seen_tehran="2026-10-25T08:00:00+03:30",
                           last_seen_tehran="2026-10-25T18:00:00+03:30"))
    # Keyword-overlapping but canon-distinct: shares "آتش‌بس" words,
    # has NO dossier_group, so it must NOT merge into the dossier.
    cands.append(story("lab-p5-kw-1", "kw-1", "خبر آزمایشی آتش‌بس دیگر",
                       "ایسنا", nightly_body=build_body("kw-1", 330),
                       channel_post_body=build_channel_body("kw-1"),
                       attribution="translated"))
    selection = {
        "source": "lab-fixture-selection",
        "tier_a": ["lab-p5-full-1", "lab-p5-full-2", "lab-p5-full-3",
                   "lab-p5-thin-250", "lab-p5-thin-700", "lab-p5-near"],
        "brief_70": ["lab-p5-sum-1", "lab-p5-sum-2", "lab-p5-kw-1"],
        "ownrewrite": ["lab-p5-own-1"],
    }

    day1 = "/tmp/phase5-day1"
    if os.path.exists(day1):
        shutil.rmtree(day1)
    os.makedirs(day1)
    json.dump(cands, open(os.path.join(day1, "candidates.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    json.dump(selection, open(os.path.join(day1, "selection.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    # Bundles input: Phase 2 bundles resolved to titled members,
    # minus the disabled morning digest and the unknown kind.
    bundles_in = []
    for b in bundles_base:
        if b["bundle_kind"] in ("morning_digest", "mystery_kind"):
            continue
        members = [{"app_key": k, "title": title_by_key.get(k, k)}
                   for k in b["member_app_keys"]]
        nb = dict(b)
        nb["members"] = members
        bundles_in.append(nb)
    json.dump({"bundles": bundles_in},
              open(os.path.join(day1, "bundles.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    nd = next((b for b in bundles_base if b["bundle_kind"] == "night_desk"), None)
    nd_items = [{"app_key": k, "title": title_by_key.get(k, k)}
                for k in (nd["member_app_keys"] if nd else [])]
    json.dump({"items": nd_items},
              open(os.path.join(day1, "night_desk.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    sitreps = [{"app_key": t["app_key"], "title": t["headline"],
                "crisis_level": t.get("crisis_level"),
                "published_at_epoch": t["published_at_epoch"]}
               for t in typed
               if t["format_type"] == "SITREP" and t["app_key"] in
               ("lab-sitrep-0800", "lab-sitrep-1000")]
    json.dump({"items": sitreps},
              open(os.path.join(day1, "sitreps.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    oped_body = build_body("oped-1", 1200)
    json.dump({"items": [{
        "story_id": "lab-p5-oped-1", "kind": "oped", "title": "تحلیل آزمایشی روز",
        "body": oped_body,
        "sections": {"thesis": "تز آزمایشی.", "evidence": "شواهد آزمایشی.",
                     "counter_argument": "مخالفت آزمایشی.", "scenario": "سناریوی آزمایشی."},
    }]}, open(os.path.join(day1, "analysis.json"), "w", encoding="utf-8"),
        ensure_ascii=False, indent=2)
    json.dump({"outlets": ["منبع آزمایشی الف", "منبع آزمایشی ب"],
               "agreed": ["ادعای مورد توافق آزمایشی."],
               "divergent": ["ادعای مورد اختلاف آزمایشی: الف می‌گوید X، ب می‌گوید Y."],
               "verified": ["ادعای راستی‌آزمایی‌شدهٔ آزمایشی."]},
              open(os.path.join(day1, "consensus.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    # Alert events for 2026-10-25 (Phase 4 S2/S3 shapes, LAB-ONLY
    # thresholds 5.0/2.0 from the copied market.json).
    def snap_by_id(sid):
        return next(s for s in market["snapshots"] if s["snapshot_id"] == sid)

    def formula_vals(snap):
        formulas = {f["name"]: f["definition"] for f in market["formulas_active"]}
        return {name: gen.eval_formula(name, gen.snapshot_variables(snap), formulas, set())
                for name in formulas}

    alert_events = []
    for sid, metric, notified in (("lab-p4-s2", "bubble", 1),
                                  ("lab-p4-s3", "spread", 1),
                                  ("lab-p4-s3", "bubble", 0)):
        snap = snap_by_id(sid)
        vals = formula_vals(snap)
        key = "bubble_pct" if metric == "bubble" else "spread_pct"
        alert_events.append({
            "metric": metric, "snapshotId": sid, "valuePct": vals[key],
            "thresholdPct": 5.0 if metric == "bubble" else 2.0,
            "firedAtEpoch": gen.parse_ts_epoch(snap["ts_tehran"]),
            "notified": notified,
        })
    json.dump({"events": alert_events},
              open(os.path.join(day1, "alert_events.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    # ---- Day 2 (2026-10-24): 2 summaries, golden close, rest none ----
    day2 = "/tmp/phase5-day2"
    if os.path.exists(day2):
        shutil.rmtree(day2)
    os.makedirs(day2)
    cands2 = [
        story("lab-p5-d2-sum-1", "d2s1", "خلاصهٔ آزمایشی روز پیش یکم",
              "ایسنا", nightly_body=build_body("d2s1", 330),
              channel_post_body=build_channel_body("d2s1"),
              attribution="translated"),
        story("lab-p5-d2-sum-2", "d2s2", "خلاصهٔ آزمایشی روز پیش دوم",
              "ایسنا", nightly_body=build_body("d2s2", 330),
              channel_post_body=build_channel_body("d2s2"),
              attribution="translated"),
    ]
    json.dump(cands2, open(os.path.join(day2, "candidates.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    json.dump({"source": "lab-fixture-selection",
               "tier_a": [], "brief_70": ["lab-p5-d2-sum-1", "lab-p5-d2-sum-2"],
               "ownrewrite": []},
              open(os.path.join(day2, "selection.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    json.dump({"events": []},
              open(os.path.join(day2, "alert_events.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    market_path = os.path.join(base, "market.json")

    def run_gen(day_dir, shamsi, tehran, generated_at, extra=None):
        cmd = [sys.executable, os.path.join(HERE, "generate_nightly.py"),
               "--day-dir", day_dir, "--staging-dir", staging,
               "--shamsi-date", shamsi, "--tehran-date", tehran,
               "--selection", os.path.join(day_dir, "selection.json"),
               "--market", market_path,
               "--alert-events", os.path.join(day_dir, "alert_events.json"),
               "--generated-at-tehran", generated_at]
        if extra:
            cmd += extra
        r = subprocess.run(cmd, capture_output=True, text=True)
        print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        if r.returncode != 0:
            raise SystemExit("generate_nightly failed for %s: %d" % (tehran, r.returncode))

    run_gen(day2, "1405-08-02", "2026-10-24", "2026-10-25T01:30:00+03:30")
    run_gen(day1, "1405-08-03", "2026-10-25", "2026-10-26T01:30:00+03:30")
    # v2: same day-1 inputs + one correction -> corrected edition.
    json.dump({"items": [{
        "correction_id": "lab-p5-corr-1", "edition_id": "edition-1405-08-03",
        "story_id": "lab-p5-full-1", "field": "number",
        "note_fa": "عدد آزمایشی اصلاح شد.",
        "version_before": 1, "version_after": 2,
        "corrected_at_tehran": "2026-10-26T10:00:00+03:30",
    }]}, open(os.path.join(day1, "corrections.json"), "w", encoding="utf-8"),
        ensure_ascii=False, indent=2)
    run_gen(day1, "1405-08-03", "2026-10-25", "2026-10-26T10:30:00+03:30")

    # Copy staged editions + index into the target edge.
    os.makedirs(os.path.join(target, "editions"), exist_ok=True)
    for fname in os.listdir(os.path.join(staging, "editions")):
        shutil.copy(os.path.join(staging, "editions", fname),
                    os.path.join(target, "editions", fname))
    shutil.copy(os.path.join(staging, "nightly.json"),
                os.path.join(target, "nightly.json"))

    # Rewrite the manifest nightly section (full-content hash).
    manifest = json.load(open(os.path.join(target, "manifest.json")))
    nindex = json.load(open(os.path.join(target, "nightly.json")))
    full_editions = []
    for entry in nindex["editions"]:
        full_editions.append(json.load(open(os.path.join(target, entry["file"]))))
    full_editions.sort(key=lambda e: e["edition_id"])
    dossiers_sorted = sorted(nindex.get("dossiers", []), key=lambda d: d["dossier_id"])
    sec_hash = hashlib.sha256(json.dumps(
        {"editions": full_editions, "dossiers": dossiers_sorted},
        sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()
    manifest["sections"]["nightly"] = {
        "hash": sec_hash,
        "count": len(nindex["editions"]),
        "status": "published",
        "file": "nightly.json",
        "items": [{
            "id": e["edition_id"], "app_key": e["edition_id"],
            "content_hash": e["content_hash"],
            "updated_at_epoch": e["updated_at_epoch"],
            "status": e["status"], "file": e["file"],
        } for e in nindex["editions"]],
    }
    manifest["generator"] = ("edge/generate_phase5_fixtures.py (Phase 5, LAB ONLY) + "
                             "edge/generate_edge.py (Phase 1, LAB ONLY)")
    json.dump(manifest, open(os.path.join(target, "manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print("FIXTURE EDGE: %s (%d editions, %d dossiers)"
          % (target, len(nindex["editions"]), len(dossiers_sorted)))


if __name__ == "__main__":
    main()
