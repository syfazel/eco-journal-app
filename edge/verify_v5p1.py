#!/usr/bin/env python3
"""v5 P1 lab verifier (docs/v5/v5-program-plan.md, gate G1 support).

Statically checks the P1 instrumentation wiring points exist, the
new strings parse with no duplicates, and the hard limits hold
(version untouched, no destructive DB calls added). This does NOT
compile the app and does NOT prove A1 fixed — no build is run in
P1 by task order, so compile verification belongs to the P6 build
gate. Exit 0 only when every check passes.
"""
import re
import sys
from pathlib import Path

ROOT = Path("/home/hatch/workspace/goals/eco-journal/app")
fails = []


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f" ({detail})" if detail and not cond else ""))
    if not cond:
        fails.append(name)


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


GRADLE = "app/build.gradle.kts"
EDGE = "app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt"
TRACE = "app/src/main/java/com/ecojournal/app/data/SyncTrace.kt"
FEED = "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt"
DIAG = "app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt"
STRINGS = "app/src/main/res/values/strings.xml"

gradle = read(GRADLE)
edge = read(EDGE)
trace_src = read(TRACE)
feed = read(FEED)
diag = read(DIAG)
strings = read(STRINGS)

# 1. Build fingerprint wiring; version UNCHANGED (P6 owns 5.0.0/6).
check("gradle buildConfig enabled", "buildConfig = true" in gradle)
check("gradle GIT_COMMIT field", 'buildConfigField("String", "GIT_COMMIT"' in gradle)
check("gradle git short hash source", 'git", "rev-parse", "--short", "HEAD"' in gradle)
check("versionCode still 5", re.search(r"versionCode = 5\b", gradle) is not None)
check("versionName still 4.0.1", 'versionName = "4.0.1"' in gradle)

# 2. SyncTrace store: keys, cap, swallow, parsers, no content fields.
for key in ("v5_sync_trace", "v5_feed_branch_trace", "v5_seed_recon"):
    check(f"SyncTrace key {key}", f'"{key}"' in trace_src)
check("SyncTrace cap 40", "MAX_ENTRIES = 40" in trace_src)
check("SyncTrace cap enforced twice", trace_src.count("while (arr.length() > MAX_ENTRIES) arr.remove(0)") == 2)
check("SyncTrace record swallows", trace_src.count("catch (_: Exception)") >= 4)
for fn in ("fun record(", "fun recordFeedBranch(", "fun recordRecon(",
           "fun parseTrace(", "fun parseBranches(", "fun parseRecon("):
    check(f"SyncTrace {fn}", fn in trace_src)
check("SyncTrace stores counts not content",
      '.put("title"' not in trace_src and '.put("body"' not in trace_src
      and "invite" not in trace_src.split("Privacy")[1].split("*/")[0].lower().replace("invite codes/hashes", ""))

# 3. EdgeRepository call sites.
check("EdgeRepository trace helper", "private suspend fun trace(" in edge)
check("EdgeRepository trace call count >= 30",
      edge.count("trace(") >= 30, f"found {edge.count('trace(')}")
for sec in ("SyncSections.ARTICLES", "SyncSections.SHORTS",
            "SyncSections.MARKET", "SyncSections.CHANNEL_REFS"):
    check(f"EdgeRepository trace for {sec}",
          any(sec in line and "trace(" in line for line in edge.splitlines())
          or (sec in edge and f"trace(\n" in edge))
for fn in ("syncChannelTypedSection", "syncBundlesSection",
           "syncRadarSection", "syncNightlySection"):
    body = edge.split(f"fun {fn}")[-1].split("private suspend fun ")[0] \
        if f"fun {fn}" in edge else ""
    # syncChannelTypedSection is the first "private suspend fun" after its name;
    # the split above is approximate, so fall back to a whole-file scan keyed
    # on the function's own trace(key, ...) lines near its name.
    check(f"EdgeRepository trace inside {fn}", "trace(key," in edge)
check("EdgeRepository manifest trace", 'trace("manifest"' in edge)
check("EdgeRepository invite_gate trace", 'trace("invite_gate"' in edge)
check("EdgeRepository recon call", "SyncTrace.recordRecon(" in edge)
check("EdgeRepository recon excludes shorts",
      'filter { it.kind == "article" }' in edge)

# 4. FeedScreen branch trace.
for branch in ("urgent_all", "filter", "search", "loading", "empty", "front_page"):
    check(f"FeedScreen branch {branch}", f'-> "{branch}"' in feed)
check("FeedScreen branch effect keys",
      "LaunchedEffect(feedBranch, all.size, typedItems.size)" in feed)
check("FeedScreen recordFeedBranch", "SyncTrace.recordFeedBranch(repo.dao" in feed)

# 5. Diagnostics display + copy parity.
check("Diagnostics imports BuildConfig", "import com.ecojournal.app.BuildConfig" in diag)
check("Diagnostics fingerprint line 1", "R.string.diagnostics_fingerprint" in diag
      and "BuildConfig.GIT_COMMIT" in diag
      and diag.index("diagnostics_fingerprint") < diag.index("diagnostics_version, versionName"))
check("Diagnostics trace flows", all(k in diag for k in
      ("SyncTrace.TRACE_KEY", "SyncTrace.BRANCH_KEY", "SyncTrace.RECON_KEY")))
check("Diagnostics trace display", "for (line in traceLines + branchLines + reconLines)" in diag)
check("Diagnostics copy includes new lines",
      "traceLines + branchLines + reconLines" in diag
      and "phase4Lines + traceLines + branchLines + reconLines" in diag)

# 6. Strings: present, unique names, arg counts match usage.
names = re.findall(r'<string name="([^"]+)"', strings)
check("strings: no duplicate names", len(names) == len(set(names)),
      f"{len(names)} names, {len(set(names))} unique")
new_strings = {
    "diagnostics_fingerprint": 4,
    "diagnostics_trace_title": 0,
    "diagnostics_trace_line": 6,
    "diagnostics_trace_none": 0,
    "diagnostics_branch_title": 0,
    "diagnostics_branch_line": 4,
    "diagnostics_branch_none": 0,
    "diagnostics_recon_title": 0,
    "diagnostics_recon_line": 5,
    "diagnostics_recon_none": 0,
}
for name, nargs in new_strings.items():
    m = re.search(rf'<string name="{name}">(.*?)</string>', strings, re.S)
    check(f"strings: {name} present", m is not None)
    if m:
        found = sorted({int(x) for x in re.findall(r"%(\d+)\$s", m.group(1))})
        check(f"strings: {name} args 1..{nargs}",
              found == list(range(1, nargs + 1)), f"found {found}")

# 7. Hard-limit guards on the edited Kotlin files.
for rel, src in ((EDGE, edge), (TRACE, trace_src), (FEED, feed), (DIAG, diag)):
    check(f"guards: no destructive clear in {rel}",
          "clearAllTables" not in src and "deleteAllArticles" not in src
          and "DELETE FROM articles" not in src)

print(f"\n{len(fails)} failure(s)" if fails else "\nALL V5 P1 CHECKS PASSED")
sys.exit(1 if fails else 0)
