#!/usr/bin/env python3
"""Phase 5 nightly-edition generator — LAB ONLY, DRY-RUN STAGING ONLY.

Builds ONE nightly edition (spec phases4-5 §5) from lab-day input
files and stages it into a staging directory. It NEVER writes to
the live edge (edge/out), NEVER commits, NEVER pushes, NEVER posts
to any channel, and NEVER creates a cron. During the freeze this
is the ONLY mode: a staged edition is a LAB FILE, not app content.
Publication to the edge happens only through the delegated
editorial gate + publish_edge (real mode) AFTER lift — and only
if the U4 entry gate is resolved (see below).

U4 — ENTRY GATE (blocking for real data): no tier_a, brief-score,
or ownrewrite field exists in current edge data or in the
production databases (verified read-only in the Phase 5 pass:
article_extracts / items / drafts carry no such column; the
keypoints rule-score in yasineco_bot/keypoints.py is computed at
selection time and never stored). This generator therefore does
NOT derive selection from any score it finds lying around:
  * urgency_score is the Law 15 score for typed items ONLY — it
    is NOT the brief score (spec §5.3 warns never to run two
    meanings of ">=70" at once);
  * the keypoints rule-score (base 50 + flash 40 + radar 15 +
    freshness) is NOT the brief score either, and it is not
    stored anywhere, so it cannot be sourced from data;
  * a candidate with no explicit label in --selection is HELD
    with reason "selection_missing (U4)", never promoted.
The ONLY selection source is an explicit LAB selection file
(--selection) whose labels are fixture/editorial data, labelled
as such in the generator report. A real edition whose component
1 depends on guessed selection must not be gated — the report
says so (u4_status = "blocked_for_real_data" whenever the
selection file is absent or is a lab fixture).

Pipeline (spec §5.2, steps 1-10; step 11 GATE is NOT in this
script — it is the delegated approval + publish_edge after lift):
  SELECT day-D approved inputs only -> DEDUP by story_id ->
  CLASSIFY (Tier-A / brief / ownrewrite / dossier / held) ->
  DRAFT re-edits (never re-posts) -> FLOOR check (300 / 800,
  held never padded) -> FRESH check (>=60% 5-gram, full
  articles only) -> ASSEMBLE 7 components -> HASH (full
  content) -> REPORT -> STAGE (staging dir only).
Atomicity (spec §5.2): a failed REQUIRED step holds the whole
edition (no edition file written); a component with nothing
tonight is "none_tonight" with a reason, never filler. An
edition with ZERO component-1 articles may still stage (spec
§5.4 short-feed rule) as long as components 2, 3, 6, 7 are
honest — but its hero must not claim articles it does not
contain (the app renders counts from the file, never a claim).

Special issues (spec §5.10): while --frozen (default), the
generator does not read special-issues inputs at all; component
5 is always "paused" with no content. Passing a special-issues
input while frozen is an ERROR, not a warning.

Market close (spec §5.7/§5.9): the close snapshot + formulas
come from the edge market.json (formulas are the edge's own
S-expressions, evaluated by the same rule as MarketFormulas.kt
— never a second arithmetic implementation). Alert history
comes ONLY from --alert-events (Phase 4 market_alert_events
rows, fired or not); if that file is absent the close records
"alert history: no input supplied" and NEVER re-derives alert
history from snapshots.

Word counting (spec §5.4): whitespace split over normalized
text; ZWNJ joins (removed, not spaced); Persian/Arabic digits
unified for counting only. Freshness (spec §5.5): 1 - shared
5-grams / article 5-grams over freshness-normalized text
(punctuation stripped, Latin lowercased); >= 0.60 passes.

Usage:
  python3 edge/generate_nightly.py --day-dir DIR --staging-dir DIR
      --shamsi-date 1405-08-03 --tehran-date 2026-10-25
      [--selection FILE] [--market FILE] [--alert-events FILE]
      [--report FILE]
Day-dir files (all optional except candidates.json for a
non-empty component 1): candidates.json, bundles.json,
night_desk.json, sitreps.json, analysis.json, consensus.json,
corrections.json. Market defaults to the day-dir market.json
when present. See edge/generate_phase5_fixtures.py for the
fixture day-dir shape.

Python standard library only.
"""

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

TEH = ZoneInfo("Asia/Tehran")
HERE = os.path.dirname(os.path.abspath(__file__))
ARABIC_DIGITS = str.maketrans(
    "۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
PERSIAN_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def sha(obj):
    return hashlib.sha256(canonical(obj).encode("utf-8")).hexdigest()


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def count_words(text):
    # ZWNJ joins a word (removed); digits unified for counting only.
    norm = (text or "").replace("\u200c", "").translate(ARABIC_DIGITS)
    return len([w for w in re.split(r"\s+", norm.strip()) if w])


def freshness_words(text):
    norm = (text or "").replace("\u200c", "").translate(ARABIC_DIGITS)
    stripped = re.sub(r"[^\w\s]", " ", norm, flags=re.UNICODE)
    return [w.lower() for w in re.split(r"\s+", stripped.strip()) if w]


def freshness(article_body, channel_body):
    aw = freshness_words(article_body)
    if len(aw) < 5:
        return 0.0
    agrams = {" ".join(aw[i:i + 5]) for i in range(len(aw) - 4)}
    pw = freshness_words(channel_body)
    pgrams = {" ".join(pw[i:i + 5]) for i in range(len(pw) - 4)}
    if not agrams:
        return 0.0
    return 1.0 - len(agrams & pgrams) / len(agrams)


def eval_sexpr(node, variables, formulas):
    """Mirror of MarketFormulas.kt over the edge's S-expressions."""
    if isinstance(node, (int, float)):
        return float(node) if node == node else None
    if isinstance(node, str):
        if node in variables:
            return variables[node]
        # MarketFormulas.kt's documented resolution: bubble_pct's
        # "intrinsic" is the OUTPUT of intrinsic_emami, not a
        # column (its description says so explicitly).
        if node == "intrinsic":
            return eval_formula("intrinsic_emami", variables, formulas, set())
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


def eval_formula(name, variables, formulas, seen):
    if name in seen:
        return None
    seen = seen | {name}
    definition = formulas.get(name)
    if definition is None:
        return None
    try:
        node = json.loads(definition)
    except Exception:
        return None
    return eval_sexpr(node, variables, formulas)


def snapshot_variables(snap):
    return {
        "gold18_tmn": snap.get("gold_18k_gram"),
        "xau_usd": snap.get("xau_usd"),
        "usd_tmn": snap.get("usd_irt_free"),
        "market": snap.get("sekke_emami"),
        "a": snap.get("usdt_irt"),
        "b": snap.get("usd_irt_free"),
    }


PRICE_FIELDS = ["xau_usd", "dxy", "usdt_usd", "usd_irt_free", "usdt_irt",
                "gold_18k_gram", "sekke_emami", "sekke_bahar", "nim_sekke",
                "rob_sekke", "vix", "sp500", "brent"]


def parse_ts_epoch(ts_tehran):
    try:
        dt = datetime.fromisoformat(ts_tehran)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=TEH)
        return int(dt.timestamp())
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--day-dir", required=True)
    ap.add_argument("--staging-dir", required=True)
    ap.add_argument("--shamsi-date", required=True,
                    help="Latin digits, e.g. 1405-08-03")
    ap.add_argument("--tehran-date", required=True,
                    help="Gregorian, e.g. 2026-10-25")
    ap.add_argument("--selection", default=None,
                    help="LAB selection file (tier_a/brief_70/ownrewrite lists)")
    ap.add_argument("--market", default=None)
    ap.add_argument("--alert-events", default=None)
    ap.add_argument("--frozen", action="store_true", default=True,
                    help="Special issues stay paused (default: frozen)")
    ap.add_argument("--no-frozen", dest="frozen", action="store_false")
    ap.add_argument("--report", default=None)
    ap.add_argument("--generated-at-tehran", default=None,
                    help="ISO Tehran timestamp for generated_at (fixtures); default: now")
    args = ap.parse_args()

    day_dir = args.day_dir
    staging = args.staging_dir
    edition_id = "edition-%s" % args.shamsi_date
    # Generation instant (fixture-supplied or now), used for the
    # market-close 24h staleness rule (spec §5.7) as well as for
    # generated_at_tehran / updated_at_epoch below.
    gen_tehran = args.generated_at_tehran or datetime.now(TEH).isoformat()
    try:
        gen_epoch_ms = int(datetime.fromisoformat(gen_tehran).timestamp() * 1000)
    except Exception:
        gen_epoch_ms = 0
    report = {"edition_id": edition_id, "held": [], "missing_inputs": [],
              "dossier_merges": [], "floor_failures": [],
              "fresh_failures": [], "counts": {}, "u4_status": None,
              "dry_run": True, "published": False}

    def opt(name):
        path = os.path.join(day_dir, name)
        if not os.path.exists(path):
            report["missing_inputs"].append(name)
            return None
        try:
            return load_json(path)
        except Exception as exc:
            report["missing_inputs"].append("%s (unparseable: %s)" % (name, exc))
            return None

    # Frozen gate: special-issues inputs are never read while frozen.
    if args.frozen and os.path.exists(os.path.join(day_dir, "special_issues.json")):
        print("REFUSED: special_issues.json present while frozen; "
              "the generator does not read special-issues inputs.",
              file=sys.stderr)
        return 2

    selection = None
    if args.selection:
        try:
            selection = load_json(args.selection)
        except Exception as exc:
            print("REFUSED: selection file unparseable: %s" % exc, file=sys.stderr)
            return 2
    if selection is None:
        report["u4_status"] = "blocked_for_real_data (no selection file)"
        sel_source = None
    else:
        sel_source = selection.get("source", "lab-selection-file")
        if "lab" in str(sel_source).lower() or "fixture" in str(sel_source).lower():
            report["u4_status"] = "blocked_for_real_data (lab fixture selection only)"
        else:
            report["u4_status"] = "editorial selection file: %s" % sel_source
    tier_a = set((selection or {}).get("tier_a", []))
    brief_70 = set((selection or {}).get("brief_70", []))
    ownrewrite = set((selection or {}).get("ownrewrite", []))

    candidates = opt("candidates.json")
    if candidates is None:
        candidates = []
    if isinstance(candidates, dict):
        candidates = candidates.get("candidates", candidates.get("items", []))

    # DEDUP by story_id (Phase 1 join keys, never headline hash).
    seen_stories = {}
    for cand in candidates:
        sid = cand.get("story_id")
        if not sid:
            report["held"].append({"story_id": None, "reason": "no_story_id"})
            continue
        if sid in seen_stories:
            report["dossier_merges"].append(
                {"story_id": sid, "note": "duplicate story_id, kept first"})
            continue
        seen_stories[sid] = cand
    candidates = list(seen_stories.values())

    # Dossier grouping: shared dossier_group = shared canon family.
    # Keyword overlap alone never merges (no keyword field is read).
    groups = {}
    for cand in candidates:
        grp = cand.get("dossier_group")
        if grp:
            groups.setdefault(grp, []).append(cand)
    dossier_members = set()
    dossiers = []
    for grp, members in groups.items():
        if len(members) < 2:
            continue
        summary = members[0].get("dossier_summary", "")
        wc = count_words(summary)
        if wc < 300:
            report["held"].append(
                {"dossier_group": grp, "reason": "dossier_summary_lt_300",
                 "word_count": wc})
            for m in members:
                dossier_members.add(m["story_id"])
                report["held"].append(
                    {"story_id": m["story_id"], "reason": "dossier_held"})
            continue
        title = members[0].get("dossier_title_fa", grp)
        slug = members[0].get("dossier_topic_slug", "topic")
        week_start = members[0].get("dossier_week_start", args.shamsi_date)
        dossiers.append({
            "dossier_id": "dossier-%s-W%s-%s" % (
                args.shamsi_date[:4], week_start, slug),
            "topic_title_fa": title,
            "story_ids": [m["story_id"] for m in members],
            "first_seen_tehran": members[0].get("first_seen_tehran", ""),
            "last_updated_tehran": members[-1].get("last_seen_tehran", ""),
            "summary": summary,
            "edition_ids": [edition_id],
        })
        report["dossier_merges"].append(
            {"dossier_group": grp, "members": len(members),
             "dossier_id": dossiers[-1]["dossier_id"]})
        for m in members:
            dossier_members.add(m["story_id"])

    articles = []
    for cand in candidates:
        sid = cand["story_id"]
        if sid in dossier_members:
            continue
        body = cand.get("nightly_body") or ""
        wc = count_words(body)
        fresh = freshness(body, cand.get("channel_post_body", "")) if body else 0.0
        # Verbatim allow-list (S30): only Donya-e Eqtesad + Tabnak.
        if cand.get("attribution") == "verbatim":
            src = cand.get("source_name", "")
            if "دنیای اقتصاد" not in src and "تابناک" not in src:
                report["held"].append({"story_id": sid, "reason": "verbatim_not_allowed"})
                continue
            badge = "بازنشر از %s" % src
        else:
            badge = None
        base = {
            "story_id": sid,
            "app_key": cand.get("app_key"),
            "edition_key": "%s+%s" % (edition_id, sid),
            "channel_message_id": cand.get("channel_message_id"),
            "title": cand.get("title", ""),
            "body": body,
            # Audit-only field (never reader content; the app
            # never renders it): lets publish_edge recompute the
            # fresh-text gate for full articles. Without it a
            # full article is unauditable and verify FAILS.
            "channel_post_body": cand.get("channel_post_body", ""),
            "word_count": wc,
            "source_name": cand.get("source_name", ""),
            "source_url": cand.get("source_url"),
            "verbatim_badge": badge,
            "format_type": cand.get("format_type"),
            "crisis_level": cand.get("crisis_level"),
            "category": cand.get("category"),
            "category_fa": cand.get("category_fa"),
            "slot": cand.get("slot"),
            "bundle_id": cand.get("bundle_id"),
            "position": cand.get("position"),
        }
        if not body:
            report["held"].append({"story_id": sid, "reason": "no_nightly_body"})
            continue
        if sid in tier_a:
            # Spec §5.5 order: the fresh-text failure is decided
            # FIRST — a fresh failure stays a brief if it passes
            # the brief floor (even though it also fails the full
            # floor), else it is held. Only a fresh-passing item
            # reaches the 800 floor check.
            if fresh < 0.60:
                report["fresh_failures"].append({"story_id": sid, "freshness": fresh})
                if 300 <= wc <= 500:
                    articles.append(dict(base, kind="summary", freshness=None))
                else:
                    report["held"].append({"story_id": sid, "reason": "fresh_text_lt_60",
                                           "freshness": fresh})
                continue
            if wc < 800:
                report["floor_failures"].append(
                    {"story_id": sid, "word_count": wc, "floor": 800})
                report["held"].append({"story_id": sid, "reason": "floor_full_800",
                                       "word_count": wc})
                continue
            articles.append(dict(base, kind="full_article", freshness=fresh))
        elif sid in brief_70:
            if wc < 300:
                report["floor_failures"].append(
                    {"story_id": sid, "word_count": wc, "floor": 300})
                report["held"].append({"story_id": sid, "reason": "floor_summary_300",
                                       "word_count": wc})
                continue
            if wc > 500 and wc >= 800 and fresh >= 0.60:
                articles.append(dict(base, kind="full_article", freshness=fresh))
            else:
                articles.append(dict(base, kind="summary", freshness=None))
        elif sid in ownrewrite:
            if wc < 800 or fresh < 0.60:
                report["held"].append(
                    {"story_id": sid, "reason": "floor_full_800" if wc < 800 else "fresh_text_lt_60",
                     "word_count": wc, "freshness": fresh})
                continue
            articles.append(dict(base, kind="full_article", freshness=fresh))
        else:
            # U4: no selection label -> HELD, never promoted on a
            # guessed score. Tier-A / brief / ownrewrite slots each
            # carry their own missing reason in the spec; an
            # unlabelled candidate cannot be assigned to a slot.
            report["held"].append({"story_id": sid, "reason": "selection_missing (U4)"})
    if articles:
        articles_state = "present"
    elif candidates:
        articles_state = "held_with_reasons"
    else:
        articles_state = "none_tonight"

    # ---- Market close (spec §5.7) ----
    market_path = args.market or os.path.join(day_dir, "market.json")
    market_close = {"state": "none_tonight"}
    if not os.path.exists(market_path):
        report["missing_inputs"].append("market.json")
    else:
        try:
            market = load_json(market_path)
        except Exception as exc:
            print("HELD EDITION: market.json unparseable: %s" % exc, file=sys.stderr)
            report["held_edition"] = "market_unparseable"
            if args.report:
                with open(args.report, "w", encoding="utf-8") as f:
                    json.dump(report, f, ensure_ascii=False, indent=2)
            return 3
        snaps = market.get("snapshots", [])
        day_start = datetime.fromisoformat(args.tehran_date).replace(tzinfo=TEH)
        day_end = day_start + timedelta(days=1)
        day_snaps = []
        for s in snaps:
            ep = parse_ts_epoch(s.get("ts_tehran", ""))
            if ep is not None and day_start.timestamp() <= ep < day_end.timestamp():
                day_snaps.append((ep, s))
        stale = False
        if day_snaps:
            close_ep, close = max(day_snaps, key=lambda t: t[0])
        elif snaps:
            with_ep = [(parse_ts_epoch(s.get("ts_tehran", "")), s) for s in snaps]
            with_ep = [(ep, s) for ep, s in with_ep if ep is not None]
            close_ep, close = max(with_ep, key=lambda t: t[0]) if with_ep else (None, None)
            stale = True
        else:
            close_ep, close = None, None
        if close is None:
            market_close = {"state": "none_tonight"}
        else:
            formulas = {}
            for f in market.get("formulas_active", []):
                formulas[f["name"]] = eval_formula(
                    f["name"], snapshot_variables(close),
                    {g["name"]: g["definition"] for g in market.get("formulas_active", [])},
                    set())
            # Previous distinct timestamp, both-carry rule for change.
            prev = None
            for ep, s in sorted(
                    [(parse_ts_epoch(x.get("ts_tehran", "")), x) for x in snaps],
                    key=lambda t: (t[0] if t[0] is not None else -1)):
                if ep is not None and ep < close_ep:
                    prev = s
            change = {}
            if prev is not None:
                for fld in PRICE_FIELDS:
                    if close.get(fld) is not None and prev.get(fld) is not None:
                        change[fld] = close[fld] - prev[fld]
                    else:
                        change[fld] = None
            alert_history = []
            if args.alert_events:
                try:
                    events = load_json(args.alert_events)
                    if isinstance(events, dict):
                        events = events.get("events", events.get("rows", []))
                    for ev in events:
                        ep = ev.get("firedAtEpoch") or ev.get("fired_at_epoch")
                        if ep is not None and day_start.timestamp() <= ep < day_end.timestamp():
                            alert_history.append({
                                "metric": ev.get("metric"),
                                "snapshot_id": ev.get("snapshotId") or ev.get("snapshot_id"),
                                "value_pct": ev.get("valuePct") if ev.get("valuePct") is not None else ev.get("value_pct"),
                                "threshold_pct": ev.get("thresholdPct") if ev.get("thresholdPct") is not None else ev.get("threshold_pct"),
                                "notified": ev.get("notified", 0),
                            })
                except Exception as exc:
                    report["missing_inputs"].append("alert-events unparseable: %s" % exc)
            else:
                report["missing_inputs"].append(
                    "alert-events (no input supplied; alert history NOT re-derived from snapshots)")
            # Spec §5.7 staleness line: a close snapshot >24h old
            # at generation time is stale even when it IS the
            # day's latest (in addition to the fallback case).
            if close_ep is not None and gen_epoch_ms and \
                    gen_epoch_ms // 1000 - close_ep > 86400:
                stale = True
            market_close = {
                "state": "present",
                "snapshot": {
                    "close_snapshot_id": close.get("snapshot_id"),
                    "close_ts_tehran": close.get("ts_tehran"),
                    "close_ts_epoch": close_ep,
                    "prices": {fld: close.get(fld) for fld in PRICE_FIELDS},
                    "formulas": formulas,
                    "change_vs_previous": change,
                },
                "stale_close": stale,
                "alert_history": alert_history,
                "thresholds": market.get("market_alerts"),
            }
            report["counts"]["market_close_formulas"] = formulas

    # ---- Digests (spec §5.6 component 3) ----
    bundles_doc = opt("bundles.json")
    bundles = []
    if isinstance(bundles_doc, dict):
        bundles = bundles_doc.get("bundles", [])
    # The disabled morning digest (2026-10-22) renders nowhere,
    # and unknown bundle kinds are held at parse (Phase 2 rule) —
    # neither enters an edition as a digest.
    KNOWN_BUNDLE_KINDS = {"slot_bundle", "short_news", "marginalia",
                          "hourly_digest", "morning_bundle", "night_desk"}
    day_bundles = [b for b in bundles
                   if b.get("bundle_date_shamsi") == args.shamsi_date
                   and b.get("status") != "retracted"
                   and b.get("bundle_kind") in KNOWN_BUNDLE_KINDS]
    night_desk_doc = opt("night_desk.json")
    night_desk = {"state": "none_tonight", "items": [], "state_note": ""}
    if isinstance(night_desk_doc, dict):
        nd_items = night_desk_doc.get("items", [])
        if nd_items:
            night_desk = {"state": "present", "items": nd_items, "state_note": ""}
        else:
            night_desk = {"state": "none_tonight", "items": [],
                          "state_note": "night desk: no verified run"}
    else:
        night_desk = {"state": "none_tonight", "items": [],
                      "state_note": "night desk: no verified run"}
    sitreps_doc = opt("sitreps.json")
    sitreps = []
    if isinstance(sitreps_doc, dict):
        sitreps = sitreps_doc.get("items", sitreps_doc.get("sitreps", []))
    elif isinstance(sitreps_doc, list):
        sitreps = sitreps_doc
    sitreps = sorted(sitreps, key=lambda s: s.get("published_at_epoch", 0))
    crisis_path = [s.get("crisis_level") for s in sitreps if s.get("crisis_level")]
    digests_state = "present" if (day_bundles or night_desk["items"] or sitreps) else "none_tonight"
    digests = {"state": digests_state, "bundles": day_bundles,
               "night_desk": night_desk, "sitrep_arc": sitreps,
               "crisis_path": crisis_path}

    # ---- Analysis + consensus (spec §5.9) ----
    analysis_doc = opt("analysis.json")
    consensus_doc = opt("consensus.json")
    analysis_items = []
    if isinstance(analysis_doc, dict):
        analysis_items = analysis_doc.get("items", [])
    bands = {"oped": (1000, 3000), "weekly": (3000, 5000), "monthly": (2000, 4000)}
    kept_analysis = []
    for a in analysis_items:
        sections = a.get("sections", {})
        missing = [k for k in ("thesis", "evidence", "counter_argument", "scenario")
                   if not sections.get(k)]
        wc = count_words(a.get("body", ""))
        lo, hi = bands.get(a.get("kind", "oped"), (1000, 3000))
        if missing:
            report["held"].append({"story_id": a.get("story_id"),
                                   "reason": "analysis_structure"})
            continue
        if not (lo <= wc <= hi):
            report["held"].append({"story_id": a.get("story_id"),
                                   "reason": "analysis_band", "word_count": wc})
            continue
        kept_analysis.append(a)
    matrix = {"outlets": [], "agreed": [], "divergent": [], "verified": []}
    if isinstance(consensus_doc, dict):
        matrix = {
            "outlets": consensus_doc.get("outlets", []),
            "agreed": [c for c in consensus_doc.get("agreed", []) if c],
            "divergent": [c for c in consensus_doc.get("divergent", []) if c],
            "verified": [c for c in consensus_doc.get("verified", []) if c],
        }
    analysis_state = "present" if (kept_analysis or matrix["agreed"] or matrix["divergent"]) else "none_tonight"
    analysis = {"state": analysis_state, "items": kept_analysis,
                "consensus_matrix": matrix}

    # ---- Corrections + versioning (spec §5.11) ----
    corrections_doc = opt("corrections.json")
    new_corrections = []
    if isinstance(corrections_doc, dict):
        new_corrections = corrections_doc.get("items", corrections_doc.get("corrections", []))
    elif isinstance(corrections_doc, list):
        new_corrections = corrections_doc
    os.makedirs(os.path.join(staging, "editions"), exist_ok=True)
    existing = None
    for fname in sorted(os.listdir(os.path.join(staging, "editions"))):
        if fname.startswith("edition-%s-v" % args.tehran_date) and fname.endswith(".json"):
            try:
                existing = load_json(os.path.join(staging, "editions", fname))
            except Exception:
                existing = None
    if existing is not None and existing.get("edition_id") == edition_id:
        version = int(existing.get("version", 1)) + 1
        status = "corrected"
        old_corrections = existing.get("components", {}).get("corrections", {}).get("items", [])
        seen_corr = {c.get("correction_id") for c in old_corrections}
        corrections = old_corrections + [c for c in new_corrections
                                         if c.get("correction_id") not in seen_corr]
        # Old file already IS the v<old> audit file (its name
        # carries its version); it stays untouched.
    else:
        version = 1
        status = "published"
        corrections = new_corrections
    corrections_state = "present" if corrections else "none_tonight"

    cut_tehran = "%sT23:59:59+03:30" % args.tehran_date
    edition = {
        "edition_id": edition_id,
        "shamsi_date": args.shamsi_date.replace("-", "/").translate(PERSIAN_DIGITS),
        "tehran_date": args.tehran_date,
        "version": version,
        "status": status,
        "generated_at_tehran": gen_tehran,
        "published_at_tehran": None,
        "cut_tehran": cut_tehran,
        "components": {
            "articles": {"state": articles_state, "items": articles,
                         "held": [h for h in report["held"]]},
            "market_close": market_close,
            "digests": digests,
            "analysis": analysis,
            "special_issues": {"state": "paused", "items": []},
            "corrections": {"state": corrections_state, "items": corrections},
            "archive": {"state": "present",
                        "edition_index_entry": {"edition_id": edition_id,
                                                "version": version}},
        },
        "dossier_links": [{"dossier_id": d["dossier_id"],
                           "topic_title_fa": d["topic_title_fa"]} for d in dossiers],
        "generator_report_ref": "lab-nightly-report:%s" % edition_id,
    }
    # Content hash over FULL edition content (R13), minus the hash
    # itself. updated_at_epoch is set FIRST so it is covered by
    # the hash (it is manifest metadata derived from generation,
    # not reader content, but it must still be auditable).
    edition["updated_at_epoch"] = gen_epoch_ms
    edition["content_hash"] = sha({k: v for k, v in edition.items()
                                   if k != "content_hash"})

    # ---- Stage (staging dir ONLY; never the live edge) ----
    edition_fname = "editions/edition-%s-v%d.json" % (args.tehran_date, version)
    edition_path = os.path.join(staging, edition_fname)
    with open(edition_path, "w", encoding="utf-8") as f:
        json.dump(edition, f, ensure_ascii=False, indent=2)
    # Index: merge with any editions already staged in this dir.
    index_path = os.path.join(staging, "nightly.json")
    index = {"editions": [], "dossiers": []}
    if os.path.exists(index_path):
        try:
            index = load_json(index_path)
        except Exception:
            index = {"editions": [], "dossiers": []}
    index["editions"] = [e for e in index.get("editions", [])
                         if e.get("edition_id") != edition_id]
    index["editions"].append({
        "edition_id": edition_id, "version": version, "status": status,
        "file": edition_fname, "content_hash": edition["content_hash"],
        "updated_at_epoch": gen_epoch_ms, "shamsi_date": edition["shamsi_date"],
        "tehran_date": args.tehran_date,
    })
    index["editions"].sort(key=lambda e: e["edition_id"])
    by_dossier = {d["dossier_id"]: d for d in index.get("dossiers", [])}
    for d in dossiers:
        by_dossier[d["dossier_id"]] = d
    index["dossiers"] = sorted(by_dossier.values(), key=lambda d: d["dossier_id"])
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    report["counts"] = {
        "articles": len(articles),
        "full_articles": len([a for a in articles if a["kind"] == "full_article"]),
        "summaries": len([a for a in articles if a["kind"] == "summary"]),
        "held": len(report["held"]),
        "dossiers": len(dossiers),
        "bundles": len(day_bundles),
        "sitreps": len(sitreps),
        "corrections": len(corrections),
        "market_close_present": market_close.get("state") == "present",
    }
    report["edition_file"] = edition_fname
    report["version"] = version
    report["status"] = status
    report["content_hash"] = edition["content_hash"]
    report["selection_source"] = sel_source
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
    print("STAGED (DRY-RUN ONLY, NOT PUBLISHED): %s version %d "
          "articles=%d held=%d dossiers=%d" % (
              edition_id, version, len(articles), len(report["held"]),
              len(dossiers)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
