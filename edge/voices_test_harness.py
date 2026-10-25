#!/usr/bin/env python3
"""100-item voices test harness (spec §3.7, D6) — LAB ONLY.

Implements the test protocol, but NEVER runs it against the
draft registry: the harness REFUSES any registry whose
"finalised" flag is not true, and the draft registry v1 has no
such flag at all. Finalisation is Yasin's his-hand act (§C);
until it exists, the only runnable mode is --self-test, which
exercises the harness logic on fully synthetic data (synthetic
finalised list + synthetic 100-item sample) and proves the
checks work without touching any real name.

Checks per item (spec §3.7):
1. Author coverage: author matches EXACTLY ONE finalised
   person_id, by (name + source) — never by name alone, so a
   transliteration collision cannot force a match.
2. Attribution completeness: every matched item carries
   role_at_time_of_writing, outlet, author_date, source_url,
   text_kind, and per-fragment quote/paraphrase labels.
3. Source resolvability: recorded as "checked" only when the
   sample row carries source_resolves=true (a real run checks
   the URL live at test time; the harness never assumes it).
4. Negative control: any match to a dropped/pending/blank voice
   is a defect.
5. No-force rule: every unmatched item must be source-string
   only (voice_id null); a force-tagged item is a defect.

Pass criteria (spec §3.7 P-V1..P-V6):
- P-V1: frozen sample list stored + window + exclusions noted.
- P-V2: coverage REPORTED (matched/100 + exclusion-adjusted);
  coverage has NO pass threshold.
- P-V3: ZERO attribution errors on manual verification of EVERY
  matched item (here: completeness + resolvability flags).
- P-V4: zero matches to non-finalised voices.
- P-V5: zero force-tagged items.
- P-V6: blank slot still blank unless the final version filled it.

Exit codes: 0 = report written (pass/fail stated inside the
report — a P-V3/P-V4/P-V5 failure is a harness FAIL, not a
"low coverage" note); 2 = refused (registry not finalised);
1 = usage/io error.

Usage:
  python3 edge/voices_test_harness.py --self-test
  python3 edge/voices_test_harness.py --registry <final.json> \
      --sample <sample.json> --out <report.json>
Registry schema: {"finalised": true, "version": "...",
  "blank_slot_filled": false, "voices": [{"person_id": "...",
  "name_fa": "...", "primary_source": "..."}], "dropped": [...]}
Sample schema: {"window": "...", "items": [{"item_id": "...",
  "author_name": "...", "author_source": "...",
  "voice_id": "...|null", "role_at_time_of_writing": "...",
  "outlet": "...", "author_date": "...", "source_url": "...",
  "text_kind": "full|excerpt", "quote_labels_ok": true,
  "source_resolves": true}], "exclusions": [{"item_id": "...",
  "reason": "..."}]}
"""
import json
import sys

COMPLETENESS_FIELDS = (
    "role_at_time_of_writing", "outlet", "author_date",
    "source_url", "text_kind",
)


def run(registry, sample):
    if not registry.get("finalised"):
        return None, "refused: registry is not finalised (Yasin his-hand step §C)"
    voices = {v["person_id"]: v for v in registry.get("voices", [])}
    by_name_source = {}
    for v in registry.get("voices", []):
        by_name_source[(v["name_fa"], v["primary_source"])] = v["person_id"]
    dropped_names = set(registry.get("dropped", []))
    items = sample.get("items", [])
    exclusions = sample.get("exclusions", [])
    matched, errors, neg, forced = [], [], [], []
    for it in items:
        vid = it.get("voice_id")
        key = (it.get("author_name"), it.get("author_source"))
        expected = by_name_source.get(key)
        if it.get("author_name") in dropped_names and vid:
            neg.append(it["item_id"])
        if vid:
            if expected is None:
                forced.append(it["item_id"])
            elif vid != expected:
                errors.append((it["item_id"], "voice_id != name+source match"))
            else:
                matched.append(it["item_id"])
                missing = [f for f in COMPLETENESS_FIELDS if not it.get(f)]
                if missing:
                    errors.append((it["item_id"], "missing: " + ",".join(missing)))
                if not it.get("quote_labels_ok"):
                    errors.append((it["item_id"], "quote/paraphrase labels missing"))
                if not it.get("source_resolves"):
                    errors.append((it["item_id"], "source_url does not resolve"))
        else:
            if expected is not None:
                # Unmatched but matchable: not an error by itself
                # (coverage is reported, not thresholded) — but it
                # must stay source-string-only, which it is.
                pass
    total = len(items)
    denom = total
    report = {
        "sample_window": sample.get("window"),
        "sample_size": total,
        "exclusions": len(exclusions),
        "exclusion_reasons": [e.get("reason") for e in exclusions],
        "matched": len(matched),
        "coverage": "%d/%d" % (len(matched), total) if total else "0/0",
        "coverage_exclusion_adjusted": (
            "%d/%d" % (len(matched), denom) if denom else "0/0"),
        "attribution_errors": errors,
        "negative_control_matches": neg,
        "force_tagged": forced,
        "blank_slot_filled": registry.get("blank_slot_filled", False),
        "pass": (not errors and not neg and not forced),
    }
    return report, None


def self_test():
    registry = {
        "finalised": True, "version": "synthetic-test-1",
        "blank_slot_filled": False,
        "voices": [
            {"person_id": "voice-synth-a", "name_fa": "صدای آزمایشی الف",
             "primary_source": "منبع آزمایشی"},
            {"person_id": "voice-synth-b", "name_fa": "صدای آزمایشی ب",
             "primary_source": "منبع آزمایشی"},
        ],
        "dropped": ["صدای حذف‌شدهٔ آزمایشی"],
    }
    items = []
    for i in range(100):
        if i < 5:
            items.append({
                "item_id": "synth-%03d" % i, "author_name": "صدای آزمایشی الف",
                "author_source": "منبع آزمایشی", "voice_id": "voice-synth-a",
                "role_at_time_of_writing": "نقش آزمایشی",
                "outlet": "منبع آزمایشی", "author_date": "۱۴۰۵-۰۸-۰۴",
                "source_url": "https://example.org/lab-a", "text_kind": "excerpt",
                "quote_labels_ok": True, "source_resolves": True})
        elif i == 5:
            # Negative-control trap: dropped voice, tagged anyway.
            items.append({
                "item_id": "synth-005", "author_name": "صدای حذف‌شدهٔ آزمایشی",
                "author_source": "منبع آزمایشی", "voice_id": "voice-synth-a",
                "role_at_time_of_writing": "نقش آزمایشی",
                "outlet": "منبع آزمایشی", "author_date": "۱۴۰۵-۰۸-۰۴",
                "source_url": "https://example.org/lab-a", "text_kind": "excerpt",
                "quote_labels_ok": True, "source_resolves": True})
        else:
            items.append({
                "item_id": "synth-%03d" % i, "author_name": None,
                "author_source": "منبع آزمایشی", "voice_id": None})
    sample = {"window": "synthetic", "items": items, "exclusions": []}
    report, err = run(registry, sample)
    assert err is None
    # The trap must be caught: negative control non-empty -> FAIL.
    assert report["matched"] == 5, report
    assert report["negative_control_matches"] == ["synth-005"], report
    assert report["pass"] is False, report
    # Clean run (trap removed) must pass with coverage reported.
    sample["items"] = [it for it in items if it["item_id"] != "synth-005"]
    sample["items"].append({
        "item_id": "synth-005", "author_name": None,
        "author_source": "منبع آزمایشی", "voice_id": None})
    report2, _ = run(registry, sample)
    assert report2["matched"] == 5 and report2["pass"] is True, report2
    assert report2["coverage"] == "5/100", report2
    print("voices harness self-test PASS: trap caught, clean run passes, "
          "coverage 5/100 reported with no threshold")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    try:
        reg_i = argv.index("--registry")
        sam_i = argv.index("--sample")
        out_i = argv.index("--out")
        registry = json.load(open(argv[reg_i + 1], encoding="utf-8"))
        sample = json.load(open(argv[sam_i + 1], encoding="utf-8"))
    except (ValueError, IndexError, OSError) as e:
        print("usage error: %s" % e, file=sys.stderr)
        return 1
    report, err = run(registry, sample)
    if err:
        print(err, file=sys.stderr)
        return 2
    with open(argv[out_i + 1], "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("voices test report: pass=%s coverage=%s errors=%d "
          "negative=%d forced=%d" % (
              report["pass"], report["coverage"],
              len(report["attribution_errors"]),
              len(report["negative_control_matches"]),
              len(report["force_tagged"])))
    # Exit 3 = test ran but FAILED (voices stays on placeholder);
    # exit 0 = pass. The report file states the verdict either way.
    return 0 if report["pass"] else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
