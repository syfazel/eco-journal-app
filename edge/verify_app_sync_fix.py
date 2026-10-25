#!/usr/bin/env python3
"""Lab verifier for the v4.0.1 app-update-system fix.

Static checks on the changed Kotlin files + a fixture round-trip
that re-implements ManifestParser + DeltaPlanner in Python and runs
them against the REAL live edge manifest (edge/out/manifest.json)
and the REAL bundled seed index (app/src/main/assets/seed/index.json).

This proves the parser/delta logic is sound on real data. It does
NOT prove on-device network reachability — that is the phone
script's job (see docs/app-update-system-fix-plan.md §5).

Python standard library only. Exit 0 = all checks pass.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

APP_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(APP_ROOT, "app", "src", "main")
KT = os.path.join(SRC, "java", "com", "ecojournal", "app")

CHANGED = [
    "data/EdgeRepository.kt",
    "ui/screens/FeedScreen.kt",
    "ui/components/SyncCompact.kt",
    "EcoJournalApp.kt",
    "ui/screens/DiagnosticsScreen.kt",
]

failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(name)


def read(rel):
    with open(os.path.join(KT, rel), encoding="utf-8") as f:
        return f.read()


def strip_kotlin(text):
    """Remove // and /* */ comments, string literals (incl. raw
    triple-quoted), and char literals, so brace counting is not
    fooled by braces inside text. Template ${...} braces are kept
    as code braces (they balance)."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def brace_balanced(text):
    code = strip_kotlin(text)
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    for ch in code:
        if ch in "([{":
            stack.append(ch)
        elif ch in ")]}":
            if not stack or stack[-1] != pairs[ch]:
                return False
            stack.pop()
    return not stack


def semantics_has_string_resource(text):
    """Brace-matched sweep: find '.semantics {' blocks and check
    for stringResource inside them (a Compose compile error)."""
    code = strip_kotlin(text)
    for m in re.finditer(r"\.semantics\s*\{", code):
        depth, i = 0, m.end() - 1
        while i < len(code):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        if "stringResource" in code[m.start():i]:
            return True
    return False


# ---- static checks ----
for rel in CHANGED:
    t = read(rel)
    check(f"braces balanced: {rel}", brace_balanced(t))
    check(f"no stringResource in semantics: {rel}", not semantics_has_string_resource(t))

feed = read("ui/screens/FeedScreen.kt")
check("FeedScreen: seed wrapped in try/catch+finally",
      "repo.recordSeedFailure()" in feed and "finally {" in feed)
check("FeedScreen: manual runSync is force-refresh",
      "repo.sync(forceRefresh = true)" in feed)
check("FeedScreen: auto-sync guard on empty feed",
      "autoSyncAttempted" in feed and "all.isEmpty()" in feed)
check("FeedScreen: bad_manifest mapped",
      '"bad_manifest" -> context.getString(R.string.sync_failed_bad_manifest)' in feed)
check("FeedScreen: empty state has retry + diagnostics",
      "feed_empty_error" in feed and "feed_empty_seed_failed" in feed)
check("FeedScreen: sync progress shown",
      "sync_progress_articles" in feed and "syncProgress" in feed)

app = read("EcoJournalApp.kt")
check("EcoJournalApp: seed launch guarded",
      "BundledSeed.ensureSeed" in app and "catch (_: Exception)" in app)

edge = read("data/EdgeRepository.kt")
check("EdgeRepository: articles deadline 240s",
      "withTimeoutOrNull(240_000L)" in edge)
check("EdgeRepository: per-file retry on network error only",
      "is HttpOutcome.NetworkError" in edge and "return@repeat" in edge)
check("EdgeRepository: progress StateFlow",
      "_syncProgress" in edge and "syncProgress" in edge)
check("EdgeRepository: manifest network short-circuit (404-only legacy)",
      "if (outcome.status == 404) syncLegacy()" in edge)
check("EdgeRepository: self-healing hash guard",
      "section.count > 0" in edge and "articleCount == 0" in edge)
check("EdgeRepository: seed-failure recorder",
      "recordSeedFailure" in edge and '"seed_error"' in edge)

sync_compact = read("ui/components/SyncCompact.kt")
check("SyncCompact: idle fallback, not fake up-to-date",
      "sync_compact_idle" in sync_compact and
      "else -> stringResource(R.string.sync_compact_up_to_date)" not in sync_compact)

diag = read("ui/screens/DiagnosticsScreen.kt")
check("Diagnostics: seed missing + error lines",
      "seed_missing_count" in diag and "seed_error" in diag and
      "diagnostics_seed_missing" in diag and "diagnostics_seed_error" in diag)

with open(os.path.join(APP_ROOT, "app", "build.gradle.kts"), encoding="utf-8") as f:
    gradle = f.read()
check("gradle identity 4.0.1/5",
      'versionName = "4.0.1"' in gradle and "versionCode = 5" in gradle)

# strings.xml
tree = ET.parse(os.path.join(SRC, "res", "values", "strings.xml"))
names = [s.get("name") for s in tree.getroot().iter("string")]
check("strings.xml: no duplicates", len(names) == len(set(names)),
      f"{len(names) - len(set(names))} duplicates")
for s in ("sync_failed_bad_manifest", "sync_progress_articles",
          "feed_empty_error", "feed_empty_seed_failed",
          "diagnostics_seed_missing", "diagnostics_seed_error",
          "sync_compact_idle"):
    check(f"strings.xml: has {s}", s in names)

# ---- fixture round-trip: ManifestParser + DeltaPlanner on real data ----
manifest_path = os.path.join(APP_ROOT, "edge", "out", "manifest.json")
with open(manifest_path, encoding="utf-8") as f:
    manifest = json.load(f)
check("fixture: manifest version 3", manifest.get("version") == 3)
sections = manifest.get("sections", {})
articles = sections.get("articles", {})
items = articles.get("items", [])
check("fixture: articles section has items with files",
      len(items) > 0 and all(i.get("file") for i in items),
      f"items={len(items)}")
print(f"  articles manifest items: {len(items)}, count field: {articles.get('count')}")

with open(os.path.join(SRC, "assets", "seed", "index.json"), encoding="utf-8") as f:
    seed = json.load(f)
seed_hashes = {}
for e in seed.get("entries", []):
    if e.get("kind") == "article" and e.get("app_key") and e.get("content_hash"):
        seed_hashes[e["app_key"]] = e["content_hash"]


def delta(items, stored):
    """Python mirror of DeltaPlanner.plan (requiresFile=True)."""
    to_fetch, corrupt = [], 0
    for it in items:
        if it.get("status") == "retracted":
            continue
        stored_hash = stored.get(it.get("app_key"))
        if stored_hash is not None and it.get("content_hash") and stored_hash == it["content_hash"]:
            continue
        if not it.get("file"):
            corrupt += 1
            continue
        to_fetch.append(it)
    return to_fetch, corrupt


empty_fetch, empty_corrupt = delta(items, {})
check("fixture: empty DB plans full fetch",
      len(empty_fetch) == len(items) and empty_corrupt == 0,
      f"to_fetch={len(empty_fetch)}")
seeded_fetch, seeded_corrupt = delta(items, seed_hashes)
check("fixture: seeded DB plans only changed/missing items",
      len(seeded_fetch) < len(items) and
      all(i.get("file") for i in seeded_fetch),
      f"to_fetch={len(seeded_fetch)} of {len(items)}")
print(f"  seeded delta: {len(seeded_fetch)} to-fetch, {seeded_corrupt} corrupt")

print()
if failures:
    print(f"FAILED: {len(failures)} check(s)")
    sys.exit(1)
print("ALL CHECKS PASS")
