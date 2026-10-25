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
  manifest.json     — version 3, per-section hashes for delta sync
                      (Phase 1, see MANIFEST V3 below)
  join_keys.json    — version 1, guid <-> app_key <-> story_id <->
                      channel_message_id mapping (Phase 1)
  GENERATION_REPORT.json — exact counts + exclusions for auditing

MANIFEST V3 (Phase 1, LAB ONLY) — section-hash definitions:
  Canonical JSON everywhere = json.dumps(obj, sort_keys=True,
  separators=(",", ":"), ensure_ascii=False); a section hash is the
  SHA-256 hex of the UTF-8 encoding of that canonical form.
  * articles / shorts: canonical JSON of the list of
    {"app_key", "content_hash", "updated_at_epoch", "status"} for
    that section's items, sorted by app_key. (The manifest's own
    items lists keep index order; only the hashed list is sorted.)
  * market: canonical JSON of {"snapshots": [...],
    "formulas_active": [...]} exactly as written to market.json.
    (market.json's extra bookkeeping keys — snapshot_count,
    first_ts, last_ts, lineage_note — are NOT part of the hash.)
  * channel_refs: canonical JSON of the refs list as written to
    channel_refs.json.
  * invites / revocations: canonical JSON of the hashes list read
    from invite_hashes.json / revocations.json. Those two files are
    OWNED by bridge/control.py (hashes only, never codes); this
    generator never rewrites them in place. When --out points at a
    different directory than the canonical edge/out, a verbatim copy
    of each file (if present) is placed in --out so the output is
    self-contained; when --out IS the canonical dir, the files are
    read as-is and left untouched.
  Sections with no published content yet (bundles, radar, nightly,
  voices) report {"status": "not_yet_published", "count": 0} and no
  hash; special_issues reports {"status": "gated_frozen", "count": 0}
  because that service stays separately frozen (2026-10-25 ~01:56).

JOIN KEYS (Phase 1) — story_id design:
  story_id = "story-" + first 20 hex of SHA-256(guid).
  Articles: guid = item_guid from data_core; ONLY if item_guid is
  empty does the generator fall back to extract_id, and every such
  fallback is counted in GENERATION_REPORT.json (never invented).
  Shorts: guid = the short id itself ("draft-N").
  channel_message_id stays NULL for all 281 entries today — honest,
  never fabricated; the bridge staging path (Phase 3) is what will
  populate it later (see docs/phase1-edge-notes.md for the
  story_key -> story_id migration design).

SHORTS CATEGORY (Phase 1 fix):
  Shorts are NO LONGER silently "general"/"عمومی". They carry the
  explicit marker category "uncategorised" / "دسته‌بندی‌نشده" in
  short_cards.json, in index entries, and in manifest shorts items,
  and their content hash is recomputed with that category (same
  9-field U+0001 join as Kotlin ContentHashes.forArticle). Consequence:
  the first v3 sync refetches all 32 shorts because their hash
  changed — that is correct, not a bug. Article categories are NOT
  changed in this pass (45 unknown-topic articles keep "general").

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
# Phase 1 shorts marker: explicit "uncategorised", never silent "general".
SHORT_CATEGORY = "uncategorised"
SHORT_CATEGORY_FA = "دسته‌بندی‌نشده"
MANIFEST_VERSION = 3
MANIFEST_GENERATOR = "edge/generate_edge.py (Phase 1, LAB ONLY)"


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


def app_key_for(external_id):
    """URL-safe surrogate key, byte-identical to Kotlin
    ArticleKeys.appKeyFor: "a-" + first 20 hex of SHA-256(id).
    The verbatim id is never used in a navigation route."""
    return "a-" + hashlib.sha256(external_id.encode("utf-8")).hexdigest()[:20]


def kotlin_content_hash(title, summary, body_blocks, points_compact,
                        why_matters, category, source, original_url,
                        published_at):
    """Content hash, byte-identical to Kotlin ContentHashes.forArticle:
    the nine fields joined by U+0001, SHA-256 hex. For full articles
    the app prefers the explicit hash carried in the article file,
    so file and index entry must carry this same value; for shorts
    the app recomputes it from the short object, so the generator's
    value must equal that recomputation exactly (points are "[]",
    body is the single full text, summary is the full text)."""
    canonical = "\u0001".join([
        title or "",
        summary or "",
        "\n".join(body_blocks or []),
        points_compact or "",
        why_matters or "",
        category or "",
        source or "",
        original_url or "",
        published_at or "",
    ])
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def epoch_millis(raw):
    """Epoch millis for a raw date string, or None if unparseable.
    Mirrors the Kotlin PublishedAtParser's accepted shapes (ISO-8601,
    RFC-1123 GMT / numeric offset / named US zone) closely enough
    for updated_at bookkeeping; a None here is harmless because the
    app falls back to the published epoch."""
    if not raw or not str(raw).strip():
        return None
    s = " ".join(str(raw).strip().split())
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            return None
        return int(dt.timestamp() * 1000)
    except ValueError:
        pass
    try:
        from email.utils import parsedate_to_datetime
        dt = parsedate_to_datetime(s)
        if dt.tzinfo is None:
            return None
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError):
        return None


def canonical_json(obj):
    """Canonical JSON for section hashes: sorted keys, compact
    separators, non-ASCII preserved (see MANIFEST V3 in the docstring)."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def section_hash(obj):
    """SHA-256 hex of the canonical JSON encoding of obj."""
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def story_id_for(guid):
    """guid-derived story id: 'story-' + first 20 hex of SHA-256(guid).
    This SUPERSEDES the production queue's headline-hash story_key on
    the edge/app side (see docs/phase1-edge-notes.md)."""
    return "story-" + hashlib.sha256(guid.encode("utf-8")).hexdigest()[:20]


def load_hashes_list(path):
    """Read a hashes-only file ({..., 'hashes': [hex, ...]}).
    Raises on a malformed file rather than publishing a wrong hash."""
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    hashes = doc.get("hashes")
    if not isinstance(hashes, list) or not all(isinstance(h, str) for h in hashes):
        raise ValueError("malformed hashes file: %s" % path)
    return hashes


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

    generated_at_utc = datetime.now(timezone.utc).isoformat()

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
    article_join = []  # parallel to the article entries, in index order
    guid_fallbacks = 0  # articles whose item_guid was empty -> extract_id
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
        # org.json JSONArray.toString() (what Kotlin reads back) is
        # compact: separators (",", ":") with no spaces. The hash
        # must be computed over that exact compact form.
        points_compact = json.dumps(points, ensure_ascii=False,
                                    separators=(",", ":"))
        source = r["item_source_fa"] or r["source_slug"]
        published_at = r["item_published_at"] or r["extracted_at"]
        updated_epoch = epoch_millis(r["extracted_at"]) or epoch_millis(published_at)
        content_hash = kotlin_content_hash(
            r["title"], summary, body_blocks, points_compact,
            r["why_matters"], category, source, r["url"], published_at)
        doc = {
            "id": r["extract_id"],
            "app_key": app_key_for(r["extract_id"]),
            "kind": "article",
            "title": r["title"],
            "summary": summary,
            "summary_source": summary_source,
            "body_blocks": body_blocks,
            "points": points,
            "why_matters": r["why_matters"],
            "category": category,
            "category_fa": category_fa,
            "source": source,
            "source_slug": r["source_slug"],
            "original_url": r["url"],
            "published_at": published_at,
            "extracted_at": r["extracted_at"],
            "channel_message_id": r["channel_message_id"],
            "publish_status": r["status"],
            "status": r["status"],
            "content_hash": content_hash,
            "updated_at_epoch": updated_epoch,
            "provenance": "data_core.article_extracts (status approved/posted, Phase 0 audit 2026-10-25)",
        }
        with open(os.path.join(out_dir, "articles", file_name_for(r["extract_id"])),
                  "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        guid = (r["item_guid"] or "").strip()
        if not guid:
            guid = r["extract_id"]  # fallback ONLY when item_guid empty
            guid_fallbacks += 1
        article_join.append({
            "guid": guid,
            "app_key": doc["app_key"],
            "story_id": story_id_for(guid),
            "channel_message_id": r["channel_message_id"],
        })
        entries.append({
            "id": r["extract_id"],
            "app_key": doc["app_key"],
            "kind": "article",
            "title": r["title"],
            "summary": summary,
            "category": category,
            "category_fa": category_fa,
            "source": doc["source"],
            "published_at": doc["published_at"],
            "file": "articles/%s" % file_name_for(r["extract_id"]),
            "channel_message_id": r["channel_message_id"],
            "status": r["status"],
            "content_hash": content_hash,
            "updated_at_epoch": updated_epoch,
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
    short_join = []  # parallel to the short entries, in index order
    for r in short_rows:
        sid = "draft-%s" % r["id"]
        # Phase 1: Kotlin entityFromShortJson recomputes the hash from
        # the short object itself (summary = full text, body = [text],
        # points = "[]", category = "uncategorised"), so this value
        # must equal that recomputation for sync skip-unchanged to
        # work. The category change vs v2 intentionally changes all
        # 32 short hashes (first v3 sync refetches the shorts).
        short_hash = kotlin_content_hash(
            r["title"], r["body_fa"], [r["body_fa"]], "[]", None,
            SHORT_CATEGORY, r["source_name"], r["original_url"],
            r["decided_at"])
        shorts.append({
            "id": sid,
            "app_key": app_key_for(sid),
            "kind": "short",
            "label": "گزیده",
            "title": r["title"],
            "text": r["body_fa"],
            "category": SHORT_CATEGORY,
            "category_fa": SHORT_CATEGORY_FA,
            "source": r["source_name"],
            "original_url": r["original_url"],
            "decided_at": r["decided_at"],
            "status": "approved",
            "content_hash": short_hash,
            "updated_at_epoch": epoch_millis(r["decided_at"]),
            "provenance": "yasineco_bot.drafts (status approved, body_fa verbatim, Phase 0 audit 2026-10-25)",
        })
        short_join.append({
            "guid": sid,
            "app_key": app_key_for(sid),
            "story_id": story_id_for(sid),
            "channel_message_id": None,
        })
    with open(os.path.join(out_dir, "short_cards.json"), "w", encoding="utf-8") as f:
        json.dump(shorts, f, ensure_ascii=False, indent=1)
    for s in shorts:
        entries.append({
            "id": s["id"],
            "app_key": s["app_key"],
            "kind": "short",
            "title": s["title"],
            "summary": s["text"][:280],
            "category": SHORT_CATEGORY,
            "category_fa": SHORT_CATEGORY_FA,
            "source": s["source"],
            "published_at": s["decided_at"],
            "file": None,
            "channel_message_id": None,
            "status": "approved",
            "content_hash": s["content_hash"],
            "updated_at_epoch": s["updated_at_epoch"],
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
    # ---- Phase 4: market_alerts (spec §4.1). Thresholds are read
    # READ-ONLY from the bot admin settings (alert_bubble /
    # alert_spread) at publish time. Null = OFF. If the read fails
    # or the keys do not exist (the real state, verified
    # 2026-10-25: no such keys in bot.db), the object ships as
    # all-null and Phase 4 alerts stay OFF. Thresholds are NEVER
    # hard-coded here and NEVER taken from the /setalert help-text
    # examples (bubble 15, spread 2 are EXAMPLES, not values).
    def _admin_threshold(key):
        try:
            row = bot.execute(
                "SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        except Exception:
            return None
        if row is None:
            return None
        try:
            value = float(row[0])
        except (TypeError, ValueError):
            return None
        if value != value or value in (float("inf"), float("-inf")):
            return None
        return value

    market_alerts = {
        "bubble_threshold_pct": _admin_threshold("alert_bubble"),
        "spread_threshold_pct": _admin_threshold("alert_spread"),
        "source": "admin-settings",
        "updated_at_tehran": None,
    }
    market = {
        "snapshots": snaps,
        "snapshot_count": len(snaps),
        "first_ts": snaps[0]["ts_tehran"] if snaps else None,
        "last_ts": snaps[-1]["ts_tehran"] if snaps else None,
        "formulas_active": formulas,
        "market_alerts": market_alerts,
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
    # v2 schema UNCHANGED for backward compatibility (Phase 1): the
    # only entry-level change vs the previous export is the shorts
    # category/hash fix above; the generator string stays "M3".
    canonical = json.dumps(entries, ensure_ascii=False, sort_keys=True)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    index = {
        "version": 2,
        "generated_at_utc": generated_at_utc,
        "generator": "edge/generate_edge.py (M3, LAB ONLY)",
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

    # ---- INVITES / REVOCATIONS (hashes only, owned by bridge/control.py)
    # Read from --out if present there, else from the canonical
    # edge/out next to this script. When --out differs from the
    # canonical dir, copy each file verbatim into --out so the output
    # is self-contained; never rewrite the canonical files from here.
    import shutil
    canonical_out = os.path.join(script_dir, "out")
    invite_source = None
    revocation_source = None
    invite_hashes = []
    revocation_hashes = []
    for fname, attr in (("invite_hashes.json", "invite"),
                        ("revocations.json", "revocation")):
        dest = os.path.join(out_dir, fname)
        src = os.path.join(canonical_out, fname)
        if os.path.exists(dest):
            hashes = load_hashes_list(dest)
            if attr == "invite":
                invite_hashes, invite_source = hashes, "out_dir (as-is)"
            else:
                revocation_hashes, revocation_source = hashes, "out_dir (as-is)"
        elif os.path.exists(src):
            hashes = load_hashes_list(src)
            shutil.copyfile(src, dest)
            if attr == "invite":
                invite_hashes, invite_source = hashes, "canonical edge/out (copied verbatim)"
            else:
                revocation_hashes, revocation_source = hashes, "canonical edge/out (copied verbatim)"
        else:
            if attr == "invite":
                invite_source = "absent (empty hashes list)"
            else:
                revocation_source = "absent (empty hashes list)"

    # ---- JOIN KEYS (guid <-> app_key <-> story_id <-> channel_message_id)
    join_entries = []
    article_entries = [e for e in entries if e["kind"] == "article"]
    for e, j in zip(article_entries, article_join):
        join_entries.append({
            "guid": j["guid"],
            "app_key": j["app_key"],
            "story_id": j["story_id"],
            "channel_message_id": j["channel_message_id"],
        })
    for e, j in zip([e for e in entries if e["kind"] == "short"], short_join):
        join_entries.append({
            "guid": j["guid"],
            "app_key": j["app_key"],
            "story_id": j["story_id"],
            "channel_message_id": j["channel_message_id"],
        })
    with open(os.path.join(out_dir, "join_keys.json"), "w", encoding="utf-8") as f:
        json.dump({"version": 1, "entries": join_entries},
                  f, ensure_ascii=False, indent=1)

    # ---- MANIFEST v3 (see MANIFEST V3 in the docstring)
    def section_items_4(entry_list):
        """The 4-field hashed view, sorted by app_key."""
        return sorted(
            ({"app_key": e["app_key"], "content_hash": e["content_hash"],
              "updated_at_epoch": e["updated_at_epoch"],
              "status": e["status"]} for e in entry_list),
            key=lambda d: d["app_key"])

    manifest_articles = []
    for e, j in zip(article_entries, article_join):
        manifest_articles.append({
            "id": e["id"],
            "app_key": e["app_key"],
            "content_hash": e["content_hash"],
            "updated_at_epoch": e["updated_at_epoch"],
            "status": e["status"],
            "file": e["file"],
            "channel_message_id": e["channel_message_id"],
            "story_id": j["story_id"],
        })
    short_entries = [e for e in entries if e["kind"] == "short"]
    manifest_shorts = [{
        "id": e["id"],
        "app_key": e["app_key"],
        "content_hash": e["content_hash"],
        "updated_at_epoch": e["updated_at_epoch"],
        "status": e["status"],
        "category": e["category"],
        "category_fa": e["category_fa"],
        "channel_message_id": e["channel_message_id"],
        "story_id": j["story_id"],
    } for e, j in zip(short_entries, short_join)]
    manifest = {
        "version": MANIFEST_VERSION,
        "generated_at_utc": generated_at_utc,
        "generator": MANIFEST_GENERATOR,
        "sections": {
            "articles": {
                "hash": section_hash(section_items_4(article_entries)),
                "count": len(article_entries),
                "items": manifest_articles,
            },
            "shorts": {
                "hash": section_hash(section_items_4(short_entries)),
                "count": len(short_entries),
                "items": manifest_shorts,
            },
            # Phase 4: the market hash covers market_alerts too
            # (when present), so a threshold change re-downloads
            # the market section exactly like a new snapshot does.
            # Older market.json files without the key keep hashing
            # the two-field view, so old edges still verify.
            "market": {
                "hash": section_hash({"snapshots": snaps,
                                      "formulas_active": formulas,
                                      "market_alerts": market_alerts}),
                "count": len(snaps),
                "file": "market.json",
            },
            "channel_refs": {
                "hash": section_hash(refs),
                "count": len(refs),
                "file": "channel_refs.json",
            },
            "invites": {
                "hash": section_hash(invite_hashes),
                "file": "invite_hashes.json",
            },
            "revocations": {
                "hash": section_hash(revocation_hashes),
                "file": "revocations.json",
            },
            "bundles": {"status": "not_yet_published", "count": 0},
            "radar": {"status": "not_yet_published", "count": 0},
            "nightly": {"status": "not_yet_published", "count": 0},
            "voices": {"status": "not_yet_published", "count": 0},
            "special_issues": {"status": "gated_frozen", "count": 0},
        },
        "join_keys_file": "join_keys.json",
    }
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)

    # ---- AUDIT report (exact counts + exclusions)
    def count(sql, db="core"):
        c = con if db == "core" else bot
        return c.execute(sql).fetchone()[0]

    report = {
        "generated_at_utc": generated_at_utc,
        "sources_read_only": [DATA_CORE_DB, BOT_DB],
        "exported": {
            "full_articles": len(full_rows),
            "short_cards": len(shorts),
            "index_entries": len(entries),
            "market_snapshots": len(snaps),
            "market_formulas_active": len(formulas),
            "channel_refs": len(refs),
            "join_keys_entries": len(join_entries),
            "manifest_version": MANIFEST_VERSION,
        },
        "phase1": {
            "guid_fallbacks_to_extract_id": guid_fallbacks,
            "shorts_category": SHORT_CATEGORY,
            "shorts_category_fa": SHORT_CATEGORY_FA,
            "channel_message_id_non_null": sum(
                1 for j in join_entries if j["channel_message_id"] is not None),
            "invite_hashes_source": invite_source,
            "invite_hashes_count": len(invite_hashes),
            "revocation_hashes_source": revocation_source,
            "revocation_hashes_count": len(revocation_hashes),
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
            "Nothing written outside app/edge/out/ (or the --out dir inside the app root).",
            "Shorts carry explicit category 'uncategorised'; channel_message_id is never fabricated (null until the bridge staging path populates it).",
            "Invite/revocation files carry hashes only and are owned by bridge/control.py; this generator never rewrites the canonical copies.",
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
