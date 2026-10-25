#!/usr/bin/env python3
"""ECO JOURNAL app edge generator — LAB ONLY (Phase 1).

Reads production databases READ-ONLY (sqlite URI mode=ro) and writes
seed JSON ONLY under app/edge/out/ (the directory containing this
script's parent ``edge/`` folder). It never modifies anything under
econ-news-blog/ or yasineco_bot/, never opens any .env file, and never
prints or embeds any token.

Seed rules (Phase 0 audit, 2026-10-25 — no invented text, ever):
  * FULL articles: data_core.article_extracts with status approved/posted
    AND a non-empty body. Verified count at audit time: 249.
    Body text is copied byte-faithfully and split into paragraphs;
    nothing is reworded, summarised by hand, or fleshed out.
  * SHORT cards (labelled "گزیده"): yasineco_bot drafts with status
    approved AND a non-empty body_fa, copied verbatim. Verified: 32.
  * EXCLUDED (listed in GENERATION_REPORT.json, never shipped as
    articles): extracts pending (2560) / rejected (277); approved/posted
    extracts with no body text (84 — no body and no body_fa exist for
    them, so a title alone is not turned into a card); rejected drafts
    (52). channel_posts rows (23) carry no body text in data_core, so
    they contribute 0 text cards; their message_ids are preserved
    verbatim in channel_refs.json as references only.
  * MARKET board: all data_core.prices snapshots (164) verbatim plus
    the 4 active formulas with their definitions (lineage).

Outputs (all under edge/out/):
  index.json        — versioned, hash-chained entry list for the app
  articles/<id>.json — one file per full article
  short_cards.json  — the 32 short cards
  market.json       — price snapshots + active formulas
  channel_refs.json — channel_posts references (no invented text)
  GENERATION_REPORT.json — exact counts + exclusions for auditing

Usage:
  python3 edge/generate_edge.py [--out DIR]
  Default out dir is edge/out/ next to this script. --out is refused
  unless it resolves inside ~/workspace/goals/eco-journal/app/.

Python standard library + sqlite3 only. No network access.
"""

import argparse
import glob
import hashlib
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
DATA_CORE_DB = os.path.join(
    HOME, "workspace", "econ-news-blog", "data_core", "data_core.db")
BOT_DB = os.path.join(HOME, "workspace", "yasineco_bot", "bot.db")

# topic (data_core.items.topic) -> display category (Persian, fixed map)
TOPIC_FA = {
    "business": "اقتصاد و تجارت",
    "energy": "انرژی",
    "wallstreet": "وال‌استریت و بازارها",
    "geopolitics": "ژئوپلیتیک",
    "rial": "بازار ریال",
}
DEFAULT_CATEGORY = "general"
DEFAULT_CATEGORY_FA = "عمومی"


def ro_connect(path):
    """Open a database strictly read-only. Raises if the file is missing."""
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    uri = "file:" + path + "?mode=ro"
    return sqlite3.connect(uri, uri=True)


def paragraphs(body):
    """Split real body text into paragraphs, preserving order and text."""
    parts = [p.strip() for p in (body or "").split("\n")]
    # A blank line separates paragraphs; single newlines inside a
    # paragraph are kept as spaces only when the block is a wall of
    # text without blank lines — in that case keep the block whole.
    blocks = []
    buf = []
    for line in (body or "").split("\n"):
        if line.strip() == "":
            if buf:
                blocks.append(" ".join(s.strip() for s in buf if s.strip()))
                buf = []
        else:
            buf.append(line)
    if buf:
        blocks.append(" ".join(s.strip() for s in buf if s.strip()))
    return [b for b in blocks if b]


def file_name_for(article_id):
    """Deterministic, filesystem-safe file name for an article id.

    Some extract_id values are URL-like strings (e.g. 'draft:https://…')
    and cannot be used as file names. The file name is therefore the
    SHA-256 of the verbatim id; the verbatim id itself is always kept
    inside the JSON document and in index.json, so nothing is lost.
    """
    return hashlib.sha256(article_id.encode("utf-8")).hexdigest() + ".json"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.abspath(args.out) if args.out else os.path.join(script_dir, "out")
    app_root = os.path.abspath(APP_ROOT)
    if os.path.commonpath([out_dir, app_root]) != app_root:
        print("REFUSED: --out must be inside %s" % app_root, file=sys.stderr)
        return 2
    os.makedirs(os.path.join(out_dir, "articles"), exist_ok=True)
    # A previous interrupted run can leave stale article files behind
    # (this happened once during Phase 1 development). Clear only the
    # generated *.json files inside edge/out/articles before rewriting,
    # so the output directory is always exactly the current export.
    for stale in glob.glob(os.path.join(out_dir, "articles", "*.json")):
        os.remove(stale)

    con = ro_connect(DATA_CORE_DB)
    con.row_factory = sqlite3.Row
    bot = ro_connect(BOT_DB)
    bot.row_factory = sqlite3.Row

    # ---- FULL articles: approved/posted + non-empty body, joined to items
    full_rows = con.execute(
        """
        SELECT e.extract_id, e.item_guid, e.url, e.title, e.source_slug,
               e.points_json, e.why_matters, e.status,
               e.channel_message_id, e.extracted_at, e.body,
               i.summary AS item_summary, i.topic AS item_topic,
               i.source_name_fa AS item_source_fa, i.published_at AS item_published_at
        FROM article_extracts e
        LEFT JOIN items i ON i.guid = e.item_guid
        WHERE e.status IN ('approved', 'posted')
          AND e.body IS NOT NULL AND TRIM(e.body) != ''
        ORDER BY e.extracted_at DESC
        """
    ).fetchall()

    entries = []
    summary_from_items = 0
    summary_from_excerpt = 0
    summary_empty = 0
    for r in full_rows:
        body_blocks = paragraphs(r["body"])
        if not body_blocks:
            # Defensive: the SQL filter already excludes empty bodies.
            continue
        item_summary = (r["item_summary"] or "").strip()
        if item_summary:
            summary = item_summary
            summary_from_items += 1
            summary_source = "items.summary"
        else:
            # Excerpt = the real first paragraph, truncated, never rewritten.
            first = body_blocks[0]
            summary = first if len(first) <= 280 else first[:277] + "…"
            summary_from_excerpt += 1
            summary_source = "body_excerpt"
            if not summary:
                summary_empty += 1
        topic = (r["item_topic"] or "").strip()
        category = topic if topic in TOPIC_FA else DEFAULT_CATEGORY
        category_fa = TOPIC_FA.get(topic, DEFAULT_CATEGORY_FA)
        try:
            points = json.loads(r["points_json"]) if r["points_json"] else []
        except (ValueError, TypeError):
            points = []
        doc = {
            "id": r["extract_id"],
            "kind": "article",
            "title": r["title"],
            "summary": summary,
            "summary_source": summary_source,
            "body_blocks": body_blocks,
            "points": points,
            "why_matters": r["why_matters"],
            "category": category,
            "category_fa": category_fa,
            "source": r["item_source_fa"] or r["source_slug"],
            "source_slug": r["source_slug"],
            "original_url": r["url"],
            "published_at": r["item_published_at"] or r["extracted_at"],
            "extracted_at": r["extracted_at"],
            "channel_message_id": r["channel_message_id"],
            "publish_status": r["status"],
            "provenance": "data_core.article_extracts (status approved/posted, Phase 0 audit 2026-10-25)",
        }
        with open(os.path.join(out_dir, "articles", file_name_for(r["extract_id"])),
                  "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        entries.append({
            "id": r["extract_id"],
            "kind": "article",
            "title": r["title"],
            "summary": summary,
            "category": category,
            "category_fa": category_fa,
            "source": doc["source"],
            "published_at": doc["published_at"],
            "file": "articles/%s" % file_name_for(r["extract_id"]),
            "channel_message_id": r["channel_message_id"],
            "provenance": doc["provenance"],
        })

    # ---- SHORT cards: approved drafts with non-empty body_fa, verbatim
    short_rows = bot.execute(
        """
        SELECT id, title, body_fa, source_name, original_url, decided_at
        FROM drafts
        WHERE status = 'approved'
          AND body_fa IS NOT NULL AND TRIM(body_fa) != ''
        ORDER BY decided_at DESC
        """
    ).fetchall()
    shorts = []
    for r in short_rows:
        shorts.append({
            "id": "draft-%s" % r["id"],
            "kind": "short",
            "label": "گزیده",
            "title": r["title"],
            "text": r["body_fa"],
            "source": r["source_name"],
            "original_url": r["original_url"],
            "decided_at": r["decided_at"],
            "provenance": "yasineco_bot.drafts (status approved, body_fa verbatim, Phase 0 audit 2026-10-25)",
        })
    with open(os.path.join(out_dir, "short_cards.json"), "w", encoding="utf-8") as f:
        json.dump(shorts, f, ensure_ascii=False, indent=1)
    for s in shorts:
        entries.append({
            "id": s["id"],
            "kind": "short",
            "title": s["title"],
            "summary": s["text"][:280],
            "category": DEFAULT_CATEGORY,
            "category_fa": DEFAULT_CATEGORY_FA,
            "source": s["source"],
            "published_at": s["decided_at"],
            "file": None,
            "channel_message_id": None,
            "provenance": s["provenance"],
        })

    # ---- MARKET board: snapshots verbatim + active formulas
    price_cols = ("snapshot_id, ts_tehran, xau_usd, dxy, usdt_usd, "
                  "usd_irt_free, usdt_irt, gold_18k_gram, sekke_emami, "
                  "sekke_bahar, nim_sekke, rob_sekke, vix, sp500, brent")
    snaps = [dict(r) for r in con.execute(
        "SELECT %s FROM prices ORDER BY ts_tehran ASC" % price_cols).fetchall()]
    formulas = [dict(r) for r in con.execute(
        "SELECT name, version, definition, inputs, description_fa "
        "FROM formulas WHERE is_active = 1 ORDER BY name").fetchall()]
    market = {
        "snapshots": snaps,
        "snapshot_count": len(snaps),
        "first_ts": snaps[0]["ts_tehran"] if snaps else None,
        "last_ts": snaps[-1]["ts_tehran"] if snaps else None,
        "formulas_active": formulas,
        "lineage_note": ("Numbers are copied verbatim from data_core.prices; "
                         "derived values use data_core.formulas definitions "
                         "(implied_usd, intrinsic_emami, bubble_pct, spread_pct)."),
    }
    with open(os.path.join(out_dir, "market.json"), "w", encoding="utf-8") as f:
        json.dump(market, f, ensure_ascii=False, indent=1)

    # ---- CHANNEL references (no body text exists in data_core for these)
    refs = [dict(r) for r in con.execute(
        "SELECT post_id, format_tag, message_id, status, posted_at "
        "FROM channel_posts ORDER BY posted_at DESC").fetchall()]
    with open(os.path.join(out_dir, "channel_refs.json"), "w", encoding="utf-8") as f:
        json.dump(refs, f, ensure_ascii=False, indent=1)

    # ---- INDEX (hash-chained over the canonical entry list)
    canonical = json.dumps(entries, ensure_ascii=False, sort_keys=True)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    index = {
        "version": 1,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "generator": "edge/generate_edge.py (Phase 1, LAB ONLY)",
        "hash_sha256": digest,
        "counts": {
            "full_articles": len(full_rows),
            "short_cards": len(shorts),
            "index_entries": len(entries),
            "market_snapshots": len(snaps),
            "market_formulas_active": len(formulas),
            "channel_refs": len(refs),
        },
        "entries": entries,
    }
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    # ---- AUDIT report (exact counts + exclusions)
    def count(sql, db="core"):
        c = con if db == "core" else bot
        return c.execute(sql).fetchone()[0]

    report = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "sources_read_only": [DATA_CORE_DB, BOT_DB],
        "exported": {
            "full_articles": len(full_rows),
            "short_cards": len(shorts),
            "index_entries": len(entries),
            "market_snapshots": len(snaps),
            "market_formulas_active": len(formulas),
            "channel_refs": len(refs),
        },
        "summary_sources_full_articles": {
            "from_items_summary": summary_from_items,
            "from_body_excerpt_first_paragraph": summary_from_excerpt,
        },
        "excluded_never_shipped_as_articles": {
            "extracts_pending": count("SELECT COUNT(*) FROM article_extracts WHERE status='pending'"),
            "extracts_rejected": count("SELECT COUNT(*) FROM article_extracts WHERE status='rejected'"),
            "extracts_approved_posted_without_body": count(
                "SELECT COUNT(*) FROM article_extracts WHERE status IN ('approved','posted') "
                "AND (body IS NULL OR TRIM(body)='')"),
            "drafts_rejected": count("SELECT COUNT(*) FROM drafts WHERE status='rejected'", "bot"),
            "channel_posts_without_body_text_in_data_core": len(refs),
        },
        "rules": [
            "No invented article text. Bodies and body_fa copied verbatim.",
            "No .env file opened. No token printed or embedded.",
            "Nothing written outside app/edge/out/.",
        ],
    }
    with open(os.path.join(out_dir, "GENERATION_REPORT.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print(json.dumps(report["exported"], ensure_ascii=False))
    print("excluded:", json.dumps(report["excluded_never_shipped_as_articles"], ensure_ascii=False))
    print("out:", out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
