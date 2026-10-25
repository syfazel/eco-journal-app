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
    if manifest.get("generator") != "edge/generate_edge.py (Phase 1, LAB ONLY)":
        fail(checks, "manifest generator string mismatch: %r" % manifest.get("generator"))
    else:
        ok(checks, "manifest generator string exact")
    sections = manifest.get("sections", {})
    if set(sections) != EXPECTED_SECTIONS:
        fail(checks, "manifest sections mismatch: missing=%s extra=%s" % (
            sorted(EXPECTED_SECTIONS - set(sections)),
            sorted(set(sections) - EXPECTED_SECTIONS)))
    else:
        ok(checks, "manifest has exactly the 11 expected sections")
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
    if mar.get("hash") != section_hash({"snapshots": market.get("snapshots"),
                                         "formulas_active": market.get("formulas_active")}):
        fail(checks, "market hash mismatch (recomputed from market.json)")
    else:
        ok(checks, "market hash recomputes identically (%d snapshots)" % len(market.get("snapshots", [])))
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

    for name, expect in (("bundles", "not_yet_published"),
                         ("radar", "not_yet_published"),
                         ("nightly", "not_yet_published"),
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

    # ---- join_keys coverage: exactly articles + shorts, one per app_key
    jk_entries = join_keys.get("entries", []) if join_keys.get("version") == 1 else []
    if join_keys.get("version") != 1:
        fail(checks, "join_keys version is %r, expected 1" % join_keys.get("version"))
    if len(jk_entries) != len(entries):
        fail(checks, "join_keys %d != index entries %d" % (len(jk_entries), len(entries)))
    else:
        ok(checks, "join_keys coverage == articles + shorts (%d)" % len(entries))
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
    if not any(c[0] == "FAIL" and "join_keys" in c[1] for c in checks):
        ok(checks, "join_keys story_ids derive correctly from guids; channel ids match index")

    # ---- counts vs GENERATION_REPORT.json
    exported = report.get("exported", {})
    expected = {
        "full_articles": len(articles),
        "short_cards": len(short_entries),
        "index_entries": len(entries),
        "market_snapshots": len(market.get("snapshots", [])),
        "channel_refs": len(refs),
        "join_keys_entries": len(jk_entries),
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
    args = ap.parse_args()
    if args.dry_run == args.real:
        print("REFUSED: pass exactly one of --dry-run or --real", file=sys.stderr)
        return 2
    out_dir = os.path.abspath(args.out) if args.out else CANONICAL_OUT
    app_root = os.path.abspath(APP_ROOT)
    if os.path.commonpath([out_dir, app_root]) != app_root:
        print("REFUSED: --out must be inside %s" % app_root, file=sys.stderr)
        return 2
    if args.real and out_dir != CANONICAL_OUT:
        print("REFUSED: --real only publishes the canonical edge/out", file=sys.stderr)
        return 2

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
