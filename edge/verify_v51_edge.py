#!/usr/bin/env python3
"""V5.1 shockproof edge-contract test — LAB ONLY.

Fetches (live mode) or reads (local mode) the ECO JOURNAL app edge
and verifies the WHOLE contract, not a sample:

  * manifest.json is version 3 and parses.
  * EVERY article file listed in manifest.sections.articles.items
    exists, returns HTTP 200 (live) / is present on disk (local),
    and its content_hash + app_key match the manifest item exactly.
  * Article file count on disk / fetched == manifest articles count
    (no orphans locally; no missing items live).
  * Section hashes recomputed from the files themselves (not from
    the manifest's own items alone) match the manifest hashes:
    articles + shorts via the 4-field {app_key, content_hash,
    updated_at_epoch, status} view sorted by app_key (from
    index.json entries, cross-checked against the article files
    and short_cards.json); market via {snapshots, formulas_active,
    market_alerts} from market.json; channel_refs via the refs
    list; invites / revocations via their hashes lists.
  * index.json hash recomputes from its entries; join_keys.json
    covers exactly the index app_keys; index entry count ==
    articles + shorts.
  * health.json (v5.1) matches the manifest: same manifest version,
    manifest_generated_at_utc == generated_at_utc ==
    manifest.generated_at_utc, per-section count + hash equal to
    the manifest, single-file sections' file_sha256 equal to the
    actual file bytes, and gated/unpublished sections carry no file.
  * Files that must NOT exist yet (health.expected_absent_files:
    articles.json, bundles.json, radar.json, nightly.json,
    voices.json — their sections are not_yet_published /
    gated_frozen) return 404 live / are absent locally. A 404 on
    them is EXPECTED, not a sync failure; a 200 would contradict
    the manifest and fails the run.
  * version.json, if present, must parse (reported, not failed on
    absence: it is release metadata, not part of the v3 contract).

Modes (default: both, so one run proves local == live):
  --dir DIR        local edge directory (default: edge/out)
  --base-url URL   live edge root (default: the GitHub Pages root)
  --local-only / --live-only   run a single leg.

Read-only: never writes, never opens any .env, never prints a
token or invite code (hashes only). Exits 0 only when every check
passes; exits 1 on any FAIL. A pre-publish live run is EXPECTED
to fail on health.json 404 until the parent publishes the new
edge — that failure names the exact missing file, it does not
mean the rest of the edge is broken.

Python standard library only.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.path.join(SCRIPT_DIR, "out")
DEFAULT_URL = "https://syfazel.github.io/eco-journal-app/"

EXPECTED_ABSENT = ["articles.json", "bundles.json", "radar.json",
                   "nightly.json", "voices.json"]


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def section_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def file_sha256(data):
    return hashlib.sha256(data).hexdigest()


class Checker:
    def __init__(self):
        self.passes = 0
        self.failures = []
        self.notes = []

    def ok(self, msg):
        self.passes += 1
        print("PASS  %s" % msg, flush=True)

    def fail(self, msg):
        self.failures.append(msg)
        print("FAIL  %s" % msg, flush=True)

    def note(self, msg):
        self.notes.append(msg)
        print("INFO  %s" % msg, flush=True)


def fetch_live(base_url, path):
    """Returns (status, body_bytes_or_None). 404 body is discarded."""
    url = base_url.rstrip("/") + "/" + path
    req = urllib.request.Request(
        url, headers={"User-Agent": "eco-journal-v51-edge-verify"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:
        return "ERR:%s" % e, None


def check_edge(label, get_json, get_bytes, exists, list_article_files):
    """get_json(path)->parsed JSON or raises; get_bytes(path)->bytes;
    exists(path)->bool; list_article_files()->[filenames in articles/].
    Returns a Checker with the results for one leg."""
    c = Checker()
    print("== %s ==" % label, flush=True)

    try:
        manifest = get_json("manifest.json")
    except Exception as e:
        c.fail("manifest.json unreadable: %s" % e)
        return c
    if manifest.get("version") != 3:
        c.fail("manifest version is %r, expected 3" % manifest.get("version"))
        return c
    c.ok("manifest.json parses, version 3, generated_at %s"
         % manifest.get("generated_at_utc"))
    sections = manifest.get("sections", {})

    def need_json(path):
        try:
            return get_json(path)
        except Exception as e:
            c.fail("%s unreadable: %s" % (path, e))
            return None

    index = need_json("index.json")
    shorts_file = need_json("short_cards.json")
    market = need_json("market.json")
    refs = need_json("channel_refs.json")
    join_keys = need_json("join_keys.json")
    invites = need_json("invite_hashes.json")
    revocations = need_json("revocations.json")
    if None in (index, shorts_file, market, refs, join_keys,
                invites, revocations):
        return c

    entries = index.get("entries", [])
    art_entries = [e for e in entries if e.get("kind") == "article"]
    short_entries = [e for e in entries if e.get("kind") == "short"]

    # ---- index hash + counts (generator hashes json.dumps(entries,
    # sort_keys=True) with DEFAULT separators, not the compact form)
    alt = hashlib.sha256(
        json.dumps(entries, ensure_ascii=False, sort_keys=True)
        .encode("utf-8")).hexdigest()
    if alt != index.get("hash_sha256"):
        c.fail("index.json hash_sha256 does not recompute")
    else:
        c.ok("index.json hash recomputes (%d entries)" % len(entries))
    if len(entries) != sections["articles"]["count"] + sections["shorts"]["count"]:
        c.fail("index entries %d != articles %d + shorts %d"
               % (len(entries), sections["articles"]["count"],
                  sections["shorts"]["count"]))
    else:
        c.ok("index entries %d == articles + shorts" % len(entries))

    # ---- join keys coverage
    jk_keys = {j.get("app_key") for j in join_keys.get("entries", [])}
    idx_keys = {e.get("app_key") for e in entries}
    if jk_keys != idx_keys:
        c.fail("join_keys app_keys != index app_keys "
               "(join %d, index %d, diff %d)"
               % (len(jk_keys), len(idx_keys), len(jk_keys ^ idx_keys)))
    else:
        c.ok("join_keys covers exactly the %d index app_keys" % len(idx_keys))

    # ---- EVERY article file
    art_items = sections["articles"].get("items", [])
    if len(art_items) != sections["articles"]["count"]:
        c.fail("manifest articles items %d != count %d"
               % (len(art_items), sections["articles"]["count"]))
    disk_files = set(list_article_files())
    listed_files = {it.get("file", "").split("/", 1)[-1]
                    for it in art_items}
    if disk_files and disk_files != listed_files:
        c.fail("articles on disk/list %d != manifest-listed %d "
               "(orphans %d, missing %d)"
               % (len(disk_files), len(listed_files),
                  len(disk_files - listed_files),
                  len(listed_files - disk_files)))
    elif disk_files:
        c.ok("article file set on disk == manifest list (%d files)"
             % len(disk_files))
    bad = 0
    for it in art_items:
        path = it.get("file")
        if not path:
            c.fail("manifest article item without file: %s" % it.get("app_key"))
            bad += 1
            continue
        try:
            doc = get_json(path)
        except Exception as e:
            c.fail("%s unreadable: %s" % (path, e))
            bad += 1
            continue
        if doc.get("content_hash") != it.get("content_hash"):
            c.fail("%s content_hash != manifest" % path)
            bad += 1
        if doc.get("app_key") != it.get("app_key"):
            c.fail("%s app_key != manifest" % path)
            bad += 1
    if bad == 0:
        c.ok("all %d article files fetched/read, "
             "content_hash + app_key match manifest" % len(art_items))

    # ---- section hashes recomputed from the files
    def four(e):
        return {"app_key": e.get("app_key"),
                "content_hash": e.get("content_hash"),
                "updated_at_epoch": e.get("updated_at_epoch"),
                "status": e.get("status")}

    if section_hash(sorted((four(e) for e in art_entries),
                           key=lambda d: d["app_key"])) != sections["articles"]["hash"]:
        c.fail("articles section hash does not recompute from index")
    else:
        c.ok("articles section hash recomputes (count %d)"
             % sections["articles"]["count"])
    if section_hash(sorted((four(e) for e in short_entries),
                           key=lambda d: d["app_key"])) != sections["shorts"]["hash"]:
        c.fail("shorts section hash does not recompute from index")
    else:
        c.ok("shorts section hash recomputes (count %d)"
             % sections["shorts"]["count"])
    # shorts cross-check: short_cards items vs manifest shorts items
    man_shorts = {it.get("app_key"): it
                  for it in sections["shorts"].get("items", [])}
    short_bad = 0
    for s in shorts_file:
        m = man_shorts.get(s.get("app_key"))
        if m is None or m.get("content_hash") != s.get("content_hash"):
            short_bad += 1
    if short_bad or len(shorts_file) != sections["shorts"]["count"]:
        c.fail("short_cards.json does not match manifest shorts "
               "(%d file vs %d manifest, %d mismatches)"
               % (len(shorts_file), sections["shorts"]["count"], short_bad))
    else:
        c.ok("short_cards.json matches manifest shorts (%d)"
             % len(shorts_file))
    if section_hash({"snapshots": market.get("snapshots"),
                     "formulas_active": market.get("formulas_active"),
                     "market_alerts": market.get("market_alerts")}) != sections["market"]["hash"]:
        c.fail("market section hash does not recompute from market.json")
    else:
        c.ok("market section hash recomputes (snapshots %d)"
             % sections["market"]["count"])
    if market.get("snapshot_count") != len(market.get("snapshots", [])):
        c.fail("market snapshot_count != len(snapshots)")
    if section_hash(refs) != sections["channel_refs"]["hash"]:
        c.fail("channel_refs section hash does not recompute")
    else:
        c.ok("channel_refs section hash recomputes (count %d)"
             % sections["channel_refs"]["count"])
    if section_hash(invites.get("hashes")) != sections["invites"]["hash"]:
        c.fail("invites section hash does not recompute")
    else:
        c.ok("invites section hash recomputes (%d hashes)"
             % len(invites.get("hashes", [])))
    if section_hash(revocations.get("hashes")) != sections["revocations"]["hash"]:
        c.fail("revocations section hash does not recompute")
    else:
        c.ok("revocations section hash recomputes (%d hashes)"
             % len(revocations.get("hashes", [])))

    # ---- unpublished / gated sections carry no file
    for name in ("bundles", "radar", "nightly", "voices", "special_issues"):
        sec = sections.get(name, {})
        if sec.get("file"):
            c.fail("section %s has status %s but carries a file"
                   % (name, sec.get("status")))
    c.ok("unpublished/gated sections carry no file")

    # ---- health.json vs manifest
    try:
        health = get_json("health.json")
    except Exception as e:
        c.fail("health.json unreadable: %s" % e)
        health = None
    if health is not None:
        if health.get("manifest_version") != manifest.get("version"):
            c.fail("health manifest_version %r != manifest version %r"
                   % (health.get("manifest_version"), manifest.get("version")))
        if health.get("manifest_generated_at_utc") != manifest.get("generated_at_utc"):
            c.fail("health manifest_generated_at_utc != manifest generated_at_utc")
        if health.get("generated_at_utc") != manifest.get("generated_at_utc"):
            c.fail("health generated_at_utc != manifest generated_at_utc "
                   "(health must be produced in the same generator run)")
        hsec = health.get("sections", {})
        for name in ("articles", "shorts", "market", "channel_refs",
                     "invites", "revocations"):
            hs, ms = hsec.get(name, {}), sections.get(name, {})
            if hs.get("count") != ms.get("count") and name not in ("invites", "revocations"):
                c.fail("health %s count %r != manifest %r"
                       % (name, hs.get("count"), ms.get("count")))
            if hs.get("hash") != ms.get("hash"):
                c.fail("health %s hash != manifest hash" % name)
            fname = hs.get("file")
            if fname:
                try:
                    raw = get_bytes(fname)
                except Exception as e:
                    c.fail("health %s file %s unreadable: %s"
                           % (name, fname, e))
                    continue
                if file_sha256(raw) != hs.get("file_sha256"):
                    c.fail("health %s file_sha256 != actual %s bytes"
                           % (name, fname))
        for fname, want in health.get("files", {}).items():
            try:
                raw = get_bytes(fname)
            except Exception as e:
                c.fail("health files entry %s unreadable: %s" % (fname, e))
                continue
            if file_sha256(raw) != want:
                c.fail("health files %s sha256 mismatch" % fname)
        if health.get("expected_absent_files") != EXPECTED_ABSENT:
            c.note("health expected_absent_files differs from verifier "
                   "list: %r" % health.get("expected_absent_files"))
        if not any(m.startswith("health") for m in c.failures):
            c.ok("health.json matches manifest (version, timestamps, "
                 "per-section count/hash, file SHA-256s)")

    # ---- expected-absent files
    for fname in EXPECTED_ABSENT:
        if exists(fname):
            c.fail("expected-absent file IS present: %s" % fname)
    c.ok("expected-absent files checked (%s)" % ", ".join(EXPECTED_ABSENT))

    # ---- version.json: informational, parse-only
    try:
        ver = get_json("version.json")
        c.note("version.json present: %s / code %s"
               % (ver.get("version_name"), ver.get("version_code")))
    except Exception:
        c.note("version.json absent (release metadata, not v3 contract)")

    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=DEFAULT_DIR)
    ap.add_argument("--base-url", default=DEFAULT_URL)
    ap.add_argument("--local-only", action="store_true")
    ap.add_argument("--live-only", action="store_true")
    args = ap.parse_args()

    total_fail = 0
    if not args.live_only:
        out_dir = os.path.abspath(args.dir)

        def local_json(path):
            with open(os.path.join(out_dir, path), encoding="utf-8") as f:
                return json.load(f)

        def local_bytes(path):
            with open(os.path.join(out_dir, path), "rb") as f:
                return f.read()

        def local_exists(path):
            return os.path.exists(os.path.join(out_dir, path))

        def local_articles():
            adir = os.path.join(out_dir, "articles")
            return os.listdir(adir) if os.path.isdir(adir) else []

        c = check_edge("LOCAL %s" % out_dir, local_json, local_bytes,
                       local_exists, local_articles)
        print("LOCAL: %d passed, %d failed" % (c.passes, len(c.failures)))
        total_fail += len(c.failures)
    if not args.local_only:
        base = args.base_url

        def live_json(path):
            status, body = fetch_live(base, path)
            if status != 200 or body is None:
                raise IOError("HTTP %s for %s" % (status, path))
            return json.loads(body.decode("utf-8"))

        def live_bytes(path):
            status, body = fetch_live(base, path)
            if status != 200 or body is None:
                raise IOError("HTTP %s for %s" % (status, path))
            return body

        def live_exists(path):
            status, _ = fetch_live(base, path)
            return status == 200

        c = check_edge("LIVE %s" % base, live_json, live_bytes,
                       live_exists, lambda: [])
        print("LIVE: %d passed, %d failed" % (c.passes, len(c.failures)))
        total_fail += len(c.failures)
    if total_fail:
        print("RESULT: FAIL (%d failing checks)" % total_fail)
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
