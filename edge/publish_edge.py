#!/usr/bin/env python3
"""ECO JOURNAL edge publisher — LAB ONLY (Phase 1, big-update plan §3.1.8).

Pipeline (mechanical chain only — editorial approval stays human/delegated):
  1. Run edge/generate_edge.py into --out (default: edge/out).
  2. Verify manifest v3 (see verify()): recompute every section hash
     from the generated files themselves (NOT from the manifest's own
     items alone), check join_keys coverage, check counts against
     GENERATION_REPORT.json, check shorts carry the explicit
     "uncategorised" marker everywhere, and check no invite codes or
     secrets are present (hashes only).
  3. EITHER --dry-run (print the verification report, change nothing
     in git) OR --real (git add edge/out only, commit, push).

Safety:
  * Exactly one of --dry-run / --real is required; with neither, this
    script refuses to guess and exits 2. --dry-run is the only mode
    tested in the Phase 1 pass; --real has never been run by the lab.
  * --real is refused unless --out IS the canonical edge/out
    (publishing a temp directory would push a partial edge).
  * --out is refused unless it resolves inside the app root
    (same guard as generate_edge.py).
  * Real mode stages ONLY edge/out (never `git add -A`): a stray
    .env, keystore, or google-services.json elsewhere in the tree
    can never ride along with an edge publish.
  * This script never opens any .env, never prints or embeds any
    token, invite code, or secret, and never touches live crons,
    bots, channels, or freeze state.

Usage:
  python3 edge/publish_edge.py --dry-run [--out DIR]
  python3 edge/publish_edge.py --real    # canonical edge/out only
  python3 edge/publish_edge.py --verify-only --out DIR
      # Phase 3: verify an EXISTING edge dir WITHOUT regenerating
      # it. This is the only mode that may verify a merged edge
      # (merge_staging.py output): --dry-run regenerates from the
      # production DB and would wipe merged items. --verify-only
      # changes nothing in git and never pushes.

Python standard library only. No network access except `git push`
in --real mode.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

HOME = os.path.expanduser("~")
APP_ROOT = os.path.join(HOME, "workspace", "goals", "eco-journal", "app")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CANONICAL_OUT = os.path.join(SCRIPT_DIR, "out")
GENERATE_EDGE = os.path.join(SCRIPT_DIR, "generate_edge.py")

EXPECTED_SECTIONS = {
    "articles", "shorts", "market", "channel_refs", "invites",
    "revocations", "bundles", "radar", "nightly", "voices",
    "special_issues",
}
# Phase 3 merged edges carry one more section, channel_typed.
# An 11-section edge is a Phase 1 generation; a 12-section edge
# is a Phase 3 merge. Anything else is a mismatch.
PHASE3_SECTIONS = EXPECTED_SECTIONS | {"channel_typed"}
PHASE1_GENERATOR = "edge/generate_edge.py (Phase 1, LAB ONLY)"
PHASE3_GENERATOR = ("edge/merge_staging.py (Phase 3 Tier A, LAB ONLY) + "
                    "edge/generate_edge.py (Phase 1, LAB ONLY)")
GATE_PROVENANCE = "muse-editorial (delegated by Yasin 2026-10-18)"
CHAIN_FIELDS = ("channel_message_id", "channel_posted_epoch",
                "staged_epoch", "gate_decided_epoch", "gate_decision",
                "gate_provenance", "edge_generated_epoch",
                "edge_pushed_epoch")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SECRET_PATTERN = re.compile(
    r"(?i)(token|secret|password|api[_-]?key|private[_-]?key)\s*[:=]")


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def section_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def load(out_dir, fname):
    with open(os.path.join(out_dir, fname), encoding="utf-8") as f:
        return json.load(f)


def fail(checks, msg):
    checks.append(("FAIL", msg))


def ok(checks, msg):
    checks.append(("OK", msg))


def verify(out_dir):
    """Verify a generated edge directory. Returns (checks, passed)."""
    checks = []

    def need(fname):
        path = os.path.join(out_dir, fname)
        if not os.path.exists(path):
            fail(checks, "missing file: %s" % fname)
            return None
        return load(out_dir, fname)

    manifest = need("manifest.json")
    index = need("index.json")
    shorts = need("short_cards.json")
    market = need("market.json")
    refs = need("channel_refs.json")
    join_keys = need("join_keys.json")
    report = need("GENERATION_REPORT.json")
    if any(c[0] == "FAIL" for c in checks):
        return checks, False
    invite_hashes = None
    revocation_hashes = None
    for fname, label in (("invite_hashes.json", "invites"),
                         ("revocations.json", "revocations")):
        path = os.path.join(out_dir, fname)
        if os.path.exists(path):
            doc = load(out_dir, fname)
            hashes = doc.get("hashes")
            if not isinstance(hashes, list):
                fail(checks, "%s: 'hashes' is not a list" % fname)
            else:
                if label == "invites":
                    invite_hashes = hashes
                else:
                    revocation_hashes = hashes
        else:
            # Absent invite files mean an empty hashes list (see
            # generate_edge.py); the manifest hash must match that.
            if label == "invites":
                invite_hashes = []
            else:
                revocation_hashes = []
            ok(checks, "%s absent -> empty hashes list assumed" % fname)

    # ---- manifest shape (exact v3 schema)
    if manifest.get("version") != 3:
        fail(checks, "manifest version is %r, expected 3" % manifest.get("version"))
    else:
        ok(checks, "manifest version 3")
    if manifest.get("generator") not in (PHASE1_GENERATOR, PHASE3_GENERATOR):
        fail(checks, "manifest generator string mismatch: %r" % manifest.get("generator"))
    else:
        ok(checks, "manifest generator string exact")
    sections = manifest.get("sections", {})
    if set(sections) == EXPECTED_SECTIONS:
        ok(checks, "manifest has exactly the 11 Phase 1 sections")
        has_typed = False
    elif set(sections) == PHASE3_SECTIONS:
        ok(checks, "manifest has exactly the 12 Phase 3 sections (channel_typed present)")
        has_typed = True
    else:
        fail(checks, "manifest sections mismatch: missing=%s extra=%s" % (
            sorted(PHASE3_SECTIONS - set(sections)),
            sorted(set(sections) - PHASE3_SECTIONS)))
        has_typed = "channel_typed" in sections
    if manifest.get("join_keys_file") != "join_keys.json":
        fail(checks, "manifest join_keys_file is %r" % manifest.get("join_keys_file"))
    else:
        ok(checks, "manifest join_keys_file is join_keys.json")
    if index.get("version") != 2:
        fail(checks, "index version is %r, expected 2 (unchanged)" % index.get("version"))
    else:
        ok(checks, "index.json still version 2 (backward compatible)")

    entries = index.get("entries", [])
    articles = [e for e in entries if e.get("kind") == "article"]
    short_entries = [e for e in entries if e.get("kind") == "short"]

    def hashed4(entry_list):
        return sorted(
            ({"app_key": e["app_key"], "content_hash": e["content_hash"],
              "updated_at_epoch": e["updated_at_epoch"],
              "status": e["status"]} for e in entry_list),
            key=lambda d: d["app_key"])

    # ---- section hashes, recomputed from the generated files
    # Articles: hash from INDEX entries; manifest items must also
    # match the index field-by-field (so a manifest-only lie fails).
    art = sections.get("articles", {})
    if art.get("hash") != section_hash(hashed4(articles)):
        fail(checks, "articles hash mismatch (recomputed from index.json)")
    else:
        ok(checks, "articles hash recomputes identically (%d items)" % len(articles))
    if art.get("count") != len(articles):
        fail(checks, "articles count %r != index articles %d" % (art.get("count"), len(articles)))
    art_by_key = {e["app_key"]: e for e in articles}
    for item in art.get("items", []):
        src = art_by_key.get(item.get("app_key"))
        if src is None:
            fail(checks, "manifest article app_key %r not in index" % item.get("app_key"))
            continue
        for field in ("id", "content_hash", "updated_at_epoch", "status",
                      "file", "channel_message_id"):
            if item.get(field) != src.get(field):
                fail(checks, "manifest article %s: field %s %r != index %r" % (
                    item.get("app_key"), field, item.get(field), src.get(field)))
    if len(art.get("items", [])) != len(articles):
        fail(checks, "manifest articles items %d != index articles %d" % (
            len(art.get("items", [])), len(articles)))
    else:
        ok(checks, "manifest articles items match index field-by-field")

    sho = sections.get("shorts", {})
    if sho.get("hash") != section_hash(hashed4(short_entries)):
        fail(checks, "shorts hash mismatch (recomputed from index.json)")
    else:
        ok(checks, "shorts hash recomputes identically (%d items)" % len(short_entries))
    if sho.get("count") != len(short_entries):
        fail(checks, "shorts count %r != index shorts %d" % (sho.get("count"), len(short_entries)))
    # Shorts category: explicit "uncategorised" in all three places.
    bad = [e["id"] for e in short_entries
           if e.get("category") != "uncategorised"
           or e.get("category_fa") != "دسته‌بندی‌نشده"]
    if bad:
        fail(checks, "index shorts without uncategorised marker: %s" % bad[:5])
    bad = [s["id"] for s in shorts
           if s.get("category") != "uncategorised"
           or s.get("category_fa") != "دسته‌بندی‌نشده"]
    if bad:
        fail(checks, "short_cards shorts without uncategorised marker: %s" % bad[:5])
    bad = [i["id"] for i in sho.get("items", [])
           if i.get("category") != "uncategorised"
           or i.get("category_fa") != "دسته‌بندی‌نشده"]
    if bad:
        fail(checks, "manifest shorts without uncategorised marker: %s" % bad[:5])
    if not bad:
        ok(checks, "shorts category 'uncategorised' in short_cards, index, manifest")
    # Shorts hash consistency: recompute the Kotlin 9-field hash from
    # each short object and compare with the stored content_hash.
    fails_before = sum(1 for c in checks if c[0] == "FAIL")
    for s in shorts:
        canonical = "\u0001".join([
            s.get("title") or "",
            s.get("text") or "",
            s.get("text") or "",
            "[]",
            "",
            s.get("category") or "",
            s.get("source") or "",
            s.get("original_url") or "",
            s.get("decided_at") or "",
        ])
        recomputed = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        if recomputed != s.get("content_hash"):
            fail(checks, "short %s: stored hash != recomputed 9-field hash" % s.get("id"))
    if sum(1 for c in checks if c[0] == "FAIL") == fails_before:
        ok(checks, "all short content_hash values match the 9-field recomputation")
    if len(shorts) != len(short_entries):
        fail(checks, "short_cards %d != index shorts %d" % (len(shorts), len(short_entries)))
    else:
        ok(checks, "short_cards count == index shorts count")

    mar = sections.get("market", {})
    # Phase 4: the market hash covers market_alerts when the file
    # carries it (generator rule); files without the key keep the
    # two-field view, so pre-Phase-4 edges still verify.
    market_hash_view = {"snapshots": market.get("snapshots"),
                        "formulas_active": market.get("formulas_active")}
    if "market_alerts" in market:
        market_hash_view["market_alerts"] = market.get("market_alerts")
    if mar.get("hash") != section_hash(market_hash_view):
        fail(checks, "market hash mismatch (recomputed from market.json)")
    else:
        ok(checks, "market hash recomputes identically (%d snapshots)" % len(market.get("snapshots", [])))
    # Phase 4: market_alerts, when present, must be null-or-number
    # thresholds from admin settings — never a string, never a
    # help-text example smuggled in as a literal. Absent = OFF.
    alerts = market.get("market_alerts")
    if alerts is not None:
        for key in ("bubble_threshold_pct", "spread_threshold_pct"):
            value = alerts.get(key)
            if value is not None and not isinstance(value, (int, float)):
                fail(checks, "market_alerts %s is not null-or-number: %r" % (key, value))
        # "lab-fixture-only" is the explicitly labelled Phase 4
        # fixture source (generate_phase4_fixtures.py); it is
        # allowed here so the fixture edge verifies, and it is
        # never written by the production generator (which always
        # writes "admin-settings"). Any other source fails.
        if alerts.get("source") not in (None, "admin-settings", "lab-fixture-only"):
            fail(checks, "market_alerts source is not admin-settings: %r" % (alerts.get("source"),))
        if not any(c[0] == "FAIL" for c in checks):
            ok(checks, "market_alerts thresholds are null-or-number (null = OFF)")
    if mar.get("count") != len(market.get("snapshots", [])):
        fail(checks, "market count %r != snapshots %d" % (mar.get("count"), len(market.get("snapshots", []))))
    if mar.get("file") != "market.json":
        fail(checks, "market file is %r" % mar.get("file"))

    ch = sections.get("channel_refs", {})
    if ch.get("hash") != section_hash(refs):
        fail(checks, "channel_refs hash mismatch (recomputed from channel_refs.json)")
    else:
        ok(checks, "channel_refs hash recomputes identically (%d refs)" % len(refs))
    if ch.get("count") != len(refs):
        fail(checks, "channel_refs count %r != refs %d" % (ch.get("count"), len(refs)))
    if ch.get("file") != "channel_refs.json":
        fail(checks, "channel_refs file is %r" % ch.get("file"))

    inv = sections.get("invites", {})
    if inv.get("hash") != section_hash(invite_hashes):
        fail(checks, "invites hash mismatch (recomputed from invite_hashes.json)")
    else:
        ok(checks, "invites hash recomputes identically (%d hashes)" % len(invite_hashes))
    if inv.get("file") != "invite_hashes.json":
        fail(checks, "invites file is %r" % inv.get("file"))
    rev = sections.get("revocations", {})
    if rev.get("hash") != section_hash(revocation_hashes):
        fail(checks, "revocations hash mismatch (recomputed from revocations.json)")
    else:
        ok(checks, "revocations hash recomputes identically (%d hashes)" % len(revocation_hashes))
    if rev.get("file") != "revocations.json":
        fail(checks, "revocations file is %r" % rev.get("file"))

    # ---- Phase 3 sections: bundles / radar / channel_typed.
    # A section is either honestly unpublished (not_yet_published,
    # count 0, Phase 1 state), published_empty (count 0 with the
    # empty-list hash, Phase 2 spec 2.1), or published (hash
    # recomputed from its file + manifest items field-by-field).
    # Bundles/radar keep their Phase 1 zero state on unmerged
    # edges; on merged edges they are published (or empty).
    def verify_file_section(name, fname, list_key, key_field):
        """Verify one file-backed section. Returns its items or []."""
        sec = sections.get(name, {})
        status = sec.get("status")
        if status == "not_yet_published":
            if sec.get("count") != 0:
                fail(checks, "%s not_yet_published but count %r" % (name, sec.get("count")))
            else:
                ok(checks, "%s honestly not_yet_published (count 0)" % name)
            return []
        if status == "published_empty":
            empty_hash = hashlib.sha256(b"[]").hexdigest()
            if sec.get("count") != 0 or sec.get("hash") != empty_hash \
                    or sec.get("items") not in (None, []):
                fail(checks, "%s published_empty malformed: %r" % (name, sec))
            else:
                ok(checks, "%s published_empty (hash of empty list, count 0)" % name)
            return []
        if status != "published":
            fail(checks, "%s has unexpected status %r" % (name, status))
            return []
        path = os.path.join(out_dir, fname)
        if not os.path.exists(path):
            fail(checks, "%s published but file %s missing" % (name, fname))
            return []
        doc = load(out_dir, fname)
        entries = doc.get(list_key, [])
        # The 4-field view keys on app_key; bundle/radar entries
        # carry bundle_id / signal_id instead (fixture shape).
        hashed4k = sorted(
            ({"app_key": e[key_field], "content_hash": e["content_hash"],
              "updated_at_epoch": e["updated_at_epoch"],
              "status": e["status"]} for e in entries),
            key=lambda d: d["app_key"])
        if sec.get("hash") != section_hash(hashed4k):
            fail(checks, "%s hash mismatch (recomputed from %s)" % (name, fname))
        else:
            ok(checks, "%s hash recomputes identically (%d items)" % (name, len(entries)))
        if sec.get("count") != len(entries):
            fail(checks, "%s count %r != file items %d" % (name, sec.get("count"), len(entries)))
        if sec.get("file") != fname:
            fail(checks, "%s file is %r, expected %s" % (name, sec.get("file"), fname))
        by_key = {e[key_field]: e for e in entries}
        for item in sec.get("items", []):
            src = by_key.get(item.get("app_key"))
            if src is None:
                fail(checks, "manifest %s app_key %r not in file" % (name, item.get("app_key")))
                continue
            for field in ("content_hash", "updated_at_epoch", "status"):
                if item.get(field) != src.get(field):
                    fail(checks, "manifest %s %s: field %s %r != file %r" % (
                        name, item.get("app_key"), field, item.get(field), src.get(field)))
        if len(sec.get("items", [])) != len(entries):
            fail(checks, "manifest %s items %d != file items %d" % (
                name, len(sec.get("items", [])), len(entries)))
        else:
            ok(checks, "manifest %s items match file field-by-field" % name)
        # Content hashes recompute under the fixture rule (full
        # object minus content_hash itself).
        fails_before = sum(1 for c in checks if c[0] == "FAIL")
        for e in entries:
            recomputed = hashlib.sha256(canonical_json(
                {k: v for k, v in e.items() if k != "content_hash"}
            ).encode("utf-8")).hexdigest()
            if recomputed != e.get("content_hash"):
                fail(checks, "%s %s: stored content_hash != recomputed" % (
                    name, e.get(key_field)))
        if sum(1 for c in checks if c[0] == "FAIL") == fails_before:
            ok(checks, "%s content_hash values all recompute" % name)
        return entries

    bundle_entries = verify_file_section("bundles", "bundles.json", "bundles", "bundle_id")
    radar_entries = verify_file_section("radar", "radar.json", "signals", "signal_id")
    typed_entries = []
    if has_typed:
        typed_entries = verify_file_section(
            "channel_typed", "channel_typed.json", "items", "app_key")
        # Typed extras: manifest items carry channel_message_id +
        # story_id; no duplicate message_id (dedup); chain objects
        # (when present) are well-formed with the exact delegated
        # provenance and an edge_generated_epoch equal to the
        # manifest's generated_at_utc.
        sec = sections.get("channel_typed", {})
        by_key = {e["app_key"]: e for e in typed_entries}
        seen_mid = {}
        for item in sec.get("items", []):
            src = by_key.get(item.get("app_key"))
            if src is None:
                continue
            if item.get("channel_message_id") != src.get("channel_message_id"):
                fail(checks, "manifest channel_typed %s: channel_message_id %r != file %r"
                     % (item.get("app_key"), item.get("channel_message_id"),
                        src.get("channel_message_id")))
            if item.get("story_id") != src.get("story_id"):
                fail(checks, "manifest channel_typed %s: story_id mismatch" % item.get("app_key"))
        for e in typed_entries:
            mid = e.get("channel_message_id")
            if mid is not None:
                if mid in seen_mid:
                    fail(checks, "channel_typed duplicate channel_message_id %r" % mid)
                seen_mid[mid] = e["app_key"]
        try:
            from datetime import datetime as _dt
            gen_epoch = int(_dt.fromisoformat(
                manifest.get("generated_at_utc", "")).timestamp())
        except Exception:
            gen_epoch = None
        for e in typed_entries:
            chain = e.get("chain")
            if chain is None:
                continue
            missing = [f for f in CHAIN_FIELDS if f not in chain]
            if missing:
                fail(checks, "channel_typed %s: chain missing %s" % (e["app_key"], missing))
                continue
            if chain.get("gate_provenance") != GATE_PROVENANCE:
                fail(checks, "channel_typed %s: chain provenance %r" % (
                    e["app_key"], chain.get("gate_provenance")))
            if chain.get("gate_decision") != "approved":
                fail(checks, "channel_typed %s: chain gate_decision %r" % (
                    e["app_key"], chain.get("gate_decision")))
            if chain.get("channel_message_id") != e.get("channel_message_id"):
                fail(checks, "channel_typed %s: chain message_id != item" % e["app_key"])
            if gen_epoch is not None and chain.get("edge_generated_epoch") != gen_epoch:
                fail(checks, "channel_typed %s: chain edge_generated_epoch %r != "
                     "manifest generated_at_utc %r" % (
                         e["app_key"], chain.get("edge_generated_epoch"), gen_epoch))
        if not any(c[0] == "FAIL" and "channel_typed" in c[1] for c in checks):
            ok(checks, "channel_typed manifest extras + chain objects valid "
                 "(%d items, %d with non-null message_id)"
                 % (len(typed_entries), len(seen_mid)))
    for name, expect in (("nightly", "not_yet_published"),
                         ("voices", "not_yet_published")):
        sec = sections.get(name, {})
        if sec.get("status") != expect or sec.get("count") != 0:
            fail(checks, "%s section is %r, expected {%s, 0}" % (name, sec, expect))
    if sections.get("special_issues", {}).get("status") != "gated_frozen" \
            or sections.get("special_issues", {}).get("count") != 0:
        fail(checks, "special_issues section is %r, expected {gated_frozen, 0}" % (
            sections.get("special_issues"),))
    else:
        ok(checks, "unpublished/gated sections report honest zero states")

    # ---- join_keys coverage: articles + shorts on Phase 1 edges;
    # articles + shorts + channel_typed on Phase 3 merged edges
    # (one entry per app_key, no duplicates, story_ids derived).
    jk_entries = join_keys.get("entries", []) if join_keys.get("version") == 1 else []
    if join_keys.get("version") != 1:
        fail(checks, "join_keys version is %r, expected 1" % join_keys.get("version"))
    # Only CHAIN-carrying typed items are Phase 3 merges and must
    # have join entries. Pre-merge fixture typed items carry no
    # chain and are not required to be in join_keys (the Phase 1
    # coverage rule, "still = articles + shorts", still holds for
    # them -- spec step 4).
    typed_merged = [e for e in typed_entries if e.get("chain") is not None]
    expected_jk = len(entries) + len(typed_merged)
    if len(jk_entries) != expected_jk:
        fail(checks, "join_keys %d != expected %d (index %d + merged typed %d)" % (
            len(jk_entries), expected_jk, len(entries), len(typed_merged)))
    else:
        ok(checks, "join_keys coverage == articles + shorts%s (%d)" % (
            " + %d merged typed" % len(typed_merged) if typed_merged else "",
            expected_jk))
    jk_by_key = {j["app_key"]: j for j in jk_entries}
    if len(jk_by_key) != len(jk_entries):
        fail(checks, "join_keys has duplicate app_keys")
    for e in entries:
        j = jk_by_key.get(e["app_key"])
        if j is None:
            fail(checks, "index app_key %s missing from join_keys" % e["app_key"])
            continue
        if not j.get("guid"):
            fail(checks, "join_keys %s: empty guid" % e["app_key"])
        elif j["story_id"] != "story-" + hashlib.sha256(
                j["guid"].encode("utf-8")).hexdigest()[:20]:
            fail(checks, "join_keys %s: story_id != story-<sha256(guid)[:20]>" % e["app_key"])
        if j.get("channel_message_id") != e.get("channel_message_id"):
            fail(checks, "join_keys %s: channel_message_id != index" % e["app_key"])
        if e.get("kind") == "short" and j.get("guid") != e.get("id"):
            fail(checks, "join_keys short %s: guid != short id" % e["app_key"])
    # Manifest article story_ids must match join_keys.
    for item in art.get("items", []):
        j = jk_by_key.get(item.get("app_key"))
        if j is not None and item.get("story_id") != j["story_id"]:
            fail(checks, "manifest article %s: story_id != join_keys" % item.get("app_key"))
    # Phase 3: every CHAIN-carrying (merged) typed item has a join
    # entry with a derived story_id and a matching
    # channel_message_id. Fixture typed items without a chain are
    # exempt (see above).
    if has_typed:
        typed_by_key = {e["app_key"]: e for e in typed_merged}
        for key, src in typed_by_key.items():
            j = jk_by_key.get(key)
            if j is None:
                fail(checks, "typed app_key %s missing from join_keys" % key)
                continue
            if not j.get("guid"):
                fail(checks, "join_keys %s: empty guid" % key)
            elif j["story_id"] != "story-" + hashlib.sha256(
                    j["guid"].encode("utf-8")).hexdigest()[:20]:
                fail(checks, "join_keys %s: story_id != story-<sha256(guid)[:20]>" % key)
            if j.get("channel_message_id") != src.get("channel_message_id"):
                fail(checks, "join_keys %s: channel_message_id != typed item" % key)
    if not any(c[0] == "FAIL" and "join_keys" in c[1] for c in checks):
        ok(checks, "join_keys story_ids derive correctly from guids; channel ids match index")

    # ---- counts vs GENERATION_REPORT.json
    # The report describes the BASE generation: on a merged edge
    # its join_keys_entries is the base count (index entries), while
    # the merged join_keys file additionally carries typed entries
    # (checked above under join_keys coverage).
    exported = report.get("exported", {})
    expected = {
        "full_articles": len(articles),
        "short_cards": len(short_entries),
        "index_entries": len(entries),
        "market_snapshots": len(market.get("snapshots", [])),
        "channel_refs": len(refs),
        "join_keys_entries": len(entries) if has_typed else len(jk_entries),
    }
    for key, value in expected.items():
        if exported.get(key) != value:
            fail(checks, "GENERATION_REPORT %s %r != %d" % (key, exported.get(key), value))
    if not any(c[0] == "FAIL" and "GENERATION_REPORT" in c[1] for c in checks):
        ok(checks, "counts match GENERATION_REPORT.json: %s" % expected)

    # ---- no invite codes / secrets present (hashes only)
    for fname, hashes in (("invite_hashes.json", invite_hashes),
                          ("revocations.json", revocation_hashes)):
        bad_hashes = [h for h in hashes if not HEX64.match(h)]
        if bad_hashes:
            fail(checks, "%s: %d non-hex64 values (not hashes-only)" % (fname, len(bad_hashes)))
    for root, _dirs, files in os.walk(out_dir):
        for fname in files:
            if fname in ("invite_hashes.json", "revocations.json"):
                continue
            path = os.path.join(root, fname)
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
            if SECRET_PATTERN.search(text):
                fail(checks, "%s: secret-like pattern found" % os.path.relpath(path, out_dir))
    for forbidden in (".env", "google-services.json"):
        if os.path.exists(os.path.join(out_dir, forbidden)):
            fail(checks, "forbidden file present in edge output: %s" % forbidden)
    if not any(c[0] == "FAIL" and ("hashes-only" in c[1] or "secret-like" in c[1]
                                   or "forbidden" in c[1]) for c in checks):
        ok(checks, "invite/revocation files are hashes-only; no secret-like patterns in edge output")

    passed = not any(c[0] == "FAIL" for c in checks)
    return checks, passed


def run(cmd, cwd):
    print("+ " + " ".join(cmd))
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.stdout:
        print(result.stdout, end="")
    if result.stderr:
        print(result.stderr, end="", file=sys.stderr)
    return result.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--real", action="store_true")
    ap.add_argument("--verify-only", action="store_true")
    args = ap.parse_args()
    modes = sum((args.dry_run, args.real, args.verify_only))
    if modes != 1:
        print("REFUSED: pass exactly one of --dry-run, --real, or --verify-only",
              file=sys.stderr)
        return 2
    out_dir = os.path.abspath(args.out) if args.out else CANONICAL_OUT
    app_root = os.path.abspath(APP_ROOT)
    # The in-root guard applies to --dry-run/--real (which
    # regenerate and, for --real, push). --verify-only is
    # strictly read-only, so it may verify scratch copies (e.g.
    # /tmp) -- Tier A failure exercises depend on that.
    if not args.verify_only \
            and os.path.commonpath([out_dir, app_root]) != app_root:
        print("REFUSED: --out must be inside %s" % app_root, file=sys.stderr)
        return 2
    if args.real and out_dir != CANONICAL_OUT:
        print("REFUSED: --real only publishes the canonical edge/out", file=sys.stderr)
        return 2

    if not args.verify_only:
        rc = run([sys.executable, GENERATE_EDGE, "--out", out_dir], cwd=APP_ROOT)
        if rc != 0:
            print("ABORTED: generate_edge.py failed", file=sys.stderr)
            return rc

    checks, passed = verify(out_dir)
    print("=== publish_edge verification report (%s) ===" % out_dir)
    for status, msg in checks:
        print("[%s] %s" % (status, msg))
    print("=== result: %s ===" % ("PASS" if passed else "FAIL"))
    if not passed:
        print("ABORTED: verification failed; nothing staged, committed, or pushed",
              file=sys.stderr)
        return 1

    if args.verify_only:
        print("VERIFY-ONLY: verification passed; nothing regenerated, "
              "nothing changed in git, nothing pushed.")
        return 0

    if args.dry_run:
        print("DRY-RUN: verification passed; nothing changed in git.")
        return 0

    # --real: stage ONLY edge/out, commit, push.
    rel_out = os.path.relpath(out_dir, APP_ROOT)
    if run(["git", "add", rel_out], cwd=APP_ROOT) != 0:
        return 1
    if run(["git", "commit", "-m",
            "edge: regenerate manifest v3 + join_keys (Phase 1, lab publish)"],
           cwd=APP_ROOT) != 0:
        print("NOTE: commit failed (possibly nothing changed); not pushing.",
              file=sys.stderr)
        return 1
    return run(["git", "push"], cwd=APP_ROOT)


if __name__ == "__main__":
    sys.exit(main())
