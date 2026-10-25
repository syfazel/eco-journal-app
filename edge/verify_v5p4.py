#!/usr/bin/env python3
"""v5 P4 lab verifier (static only, plus a live edge-contract read —
it does NOT compile anything, push, build, or change live state).

Checks the Phase P4 sections + sync/diagnostics rebuild
(docs/v5/v5-p4-report.md): the proven A2 fix (status-aware
bundles/radar sync mirroring the nightly guard), per-section
success clocks, the global clock over REQUIRED sections only,
the known-404 retry guard, the section-screen rebuilds, strings
uniqueness, and that no night-law/bar/gate/cadence behaviour
changed. The live contract section reads the public Pages
manifest (GET only); if the network is unavailable it reports
SKIP, never FAIL, and the run must say so in its report.
Run from the app root: python3 edge/verify_v5p4.py
"""
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
fails = []
skips = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        fails.append(name)


def skip(name, detail=""):
    print(f"SKIP {name}" + (f" — {detail}" if detail else ""))
    skips.append(name)


def read(rel):
    return (ROOT / rel).read_text()


REPO = "app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt"
ENGINE = "app/src/main/java/com/ecojournal/app/data/UpdateEngine.kt"
STRINGS = "app/src/main/res/values/strings.xml"
GRADLE = "app/build.gradle.kts"
SCREENS = {
    "WarRoom": "app/src/main/java/com/ecojournal/app/ui/screens/WarRoomScreen.kt",
    "Market": "app/src/main/java/com/ecojournal/app/ui/screens/MarketScreen.kt",
    "Bundles": "app/src/main/java/com/ecojournal/app/ui/screens/BundlesScreen.kt",
    "Nightly": "app/src/main/java/com/ecojournal/app/ui/screens/NightlyScreen.kt",
    "Analysis": "app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt",
    "Bookmarks": "app/src/main/java/com/ecojournal/app/ui/screens/BookmarksScreen.kt",
    "Diagnostics": "app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt",
}
MAIN = "app/src/main/java/com/ecojournal/app/MainActivity.kt"
P4_FILES = [REPO, ENGINE, MAIN, STRINGS] + list(SCREENS.values())

# 1. Strings: parse, no duplicates, P4 strings + arg counts.
sxml = read(STRINGS)
names = re.findall(r'<string name="([^"]+)"', sxml)
check("strings: no duplicate names", len(names) == len(set(names)),
      f"{len(names)} names, {len(set(names))} unique")
P4_STRINGS = {
    "v5_state_section_not_published": 1,
    "v5_bundles_404_reason": 0,
    "v5_section_recheck": 0,
    "v5_market_seed_line": 1,
    "v5_diagnostics_global_clock": 0,
    "v5_diagnostics_optional_tag": 0,
    "v5_bookmark_orphan_retracted": 0,
    "v5_bookmark_orphan_missing": 0,
}
for name, argc in P4_STRINGS.items():
    m = re.search(rf'<string name="{name}">(.*?)</string>', sxml, re.S)
    check(f"strings: {name} present", m is not None)
    if m:
        got = m.group(1).count("%")
        check(f"strings: {name} arg count {argc}", got == argc, f"found {got}")

# 2. UpdateEngine: REQUIRED set + failed-hash key.
eng = read(ENGINE)
m = re.search(r"val REQUIRED: Set<String> = setOf\((.*?)\)", eng, re.S)
check("engine: REQUIRED set present", m is not None)
if m:
    members = set(re.findall(r'"?([A-Z_]+)"?', m.group(1)))
    # Members are constant references (ARTICLES, ...), not literals.
    refs = set(re.findall(r"\b(ARTICLES|SHORTS|MARKET|CHANNEL_REFS|CHANNEL_TYPED|BUNDLES|RADAR|NIGHTLY)\b", m.group(1)))
    check("engine: REQUIRED is exactly the five plan sections",
          refs == {"ARTICLES", "SHORTS", "MARKET", "CHANNEL_REFS", "CHANNEL_TYPED"},
          f"found {sorted(refs)}")
check("engine: failedHashKey present",
      'fun failedHashKey(section: String) = "sync_section_${section}_failed_hash"' in eng)

# 3. EdgeRepository: A2 fix + clocks.
repo = read(REPO)


def fn_slice(text, start_marker, end_marker):
    a = text.index(start_marker)
    b = text.index(end_marker, a)
    return text[a:b]


bundles = fn_slice(repo, "private suspend fun syncBundlesSection",
                   "private suspend fun syncRadarSection")
radar = fn_slice(repo, "private suspend fun syncRadarSection",
                 "// ---- Phase 5 nightly section sync")
for label, body, fallback in [("bundles", bundles, '"bundles.json"'),
                              ("radar", radar, '"radar.json"')]:
    status = body.index('section.status == "not_yet_published"')
    fetch = body.index("httpGet(")
    check(f"sync: {label} status guard before first fetch", status < fetch)
    check(f"sync: {label} status guard records NOT_YET_PUBLISHED",
          "SectionStates.NOT_YET_PUBLISHED" in body[:fetch])
    blank = body.index("section.file.isNullOrBlank()")
    check(f"sync: {label} blank-file guard before fetch", blank < fetch)
    check(f"sync: {label} blank-file guard never uses default {fallback}",
          "missing_file" in body[:fetch])
    failed = body.index("failedHashKey(key)")
    check(f"sync: {label} known-404 guard before fetch", failed < fetch)
    check(f"sync: {label} known-404 trace code",
          '"http_404_unchanged"' in body)
    check(f"sync: {label} 404 pins failed hash",
          "failedHashKey(key), section.hash" in body)
    check(f"sync: {label} success clears failed hash",
          'failedHashKey(key), ""' in body)

# Up-to-date per-section success clocks: exactly the 8 sites.
clock_sites = repo.count(", 0, now, now, null,")
check("sync: 8 up-to-date success-clock sites", clock_sites == 8,
      f"found {clock_sites}")
# Unpublished/gated sections keep a null clock (never "succeeded").
check("sync: voices/special_issues keep null success clock",
      "section?.count ?: 0, 0, now, null, null" in repo)
# Nightly guard still present (P4 mirrors it, never removes it).
check("sync: nightly status guard still present",
      'syncNightlySection' in repo and
      repo.count('section.status == "not_yet_published"') >= 3)

# Required-only global clock.
no_req = repo.replace("anyRequiredIncomplete", "")
check("sync: no bare anyIncomplete remains", "anyIncomplete" not in no_req)
inv_region = repo[repo.index("Invites + revocations"):repo.index("Phase 2 sections:")]
check("sync: invites/revocations never block the clock",
      "anyRequiredIncomplete" not in inv_region)
check("sync: 4 section .also blocks gated on REQUIRED",
      repo.count("it.key in SyncSections.REQUIRED") == 4)
check("sync: clock block is required-only",
      "if (!anyRequiredIncomplete && onlySection == null)" in repo)
check("sync: whole-sync result is required-only",
      "return if (anyRequiredIncomplete)" in repo)
# No destructive DB behaviour in P4 files (deleteAllFormulas is a
# pre-existing market-formula refresh, not a clear — allow it only).
for rel in P4_FILES:
    text = read(rel)
    bad = ("clearAllTables" in text or "fallbackToDestructiveMigration" in text or
           re.search(r"deleteAll(?!Formulas)", text) is not None)
    check(f"scope: {rel.split('/')[-1]} no destructive DB call", not bad)
    check(f"scope: {rel.split('/')[-1]} braces balanced",
          text.count("{") == text.count("}") and text.count("(") == text.count(")"))
    check(f"scope: {rel.split('/')[-1]} no digit-glued token",
          not re.search(r"\dEcoDesignTokens", text))

# 4. Screens.
war = read(SCREENS["WarRoom"])
check("warroom: radar not-published reason + recheck",
      "v5_state_section_not_published" in war and
      "diagnostics_section_name_radar" in war and
      "v5_section_recheck" in war and
      "syncOnly(SyncSections.RADAR" in war)
bun = read(SCREENS["Bundles"])
check("bundles: not-published reason + recheck",
      "v5_state_section_not_published" in bun and
      "v5_section_recheck" in bun and
      "syncOnly(SyncSections.BUNDLES" in bun)
check("bundles: http_404 reason branch",
      'lastErrorCode == "http_404"' in bun and
      "v5_bundles_404_reason" in bun)
mar = read(SCREENS["Market"])
check("market: seed-only param + seed line from snapshot date",
      "marketSeedOnly" in mar and "v5_market_seed_line" in mar and
      'substringBefore("T")' in mar)
check("market: seed line is conditional on never-synced",
      "if (marketSeedOnly)" in mar)
check("main: marketSeedOnly derived from per-section clock",
      "marketSeedOnly = st?.lastSyncAt == null" in read(MAIN) and
      "marketSeedOnly = marketSeedOnly" in read(MAIN))
nig = read(SCREENS["Nightly"])
check("nightly: search results count line",
      "search_results_count" in nig and
      nig.index("search_results_count") > nig.index("if (results != null)"))
check("nightly: no invented edition (empty shell kept)",
      "nightly_empty" in nig)
ana = read(SCREENS["Analysis"])
check("analysis: token adoption present",
      "EcoDesignTokens" in ana)
boo = read(SCREENS["Bookmarks"])
check("bookmarks: orphan reasons derived from retracted lookup",
      "orphanReasons" in boo and "channelByAppKey" in boo and
      'status == "retracted"' in boo and
      "v5_bookmark_orphan_retracted" in boo and
      "v5_bookmark_orphan_missing" in boo)
dia = read(SCREENS["Diagnostics"])
check("diagnostics: global-clock line in lines list",
      "v5_diagnostics_global_clock" in dia)
check("diagnostics: required/optional tags via one helper, both surfaces",
      "sectionDisplayName" in dia and
      dia.count("sectionDisplayName(s.section)") == 2 and
      "SyncSections.REQUIRED" in dia)
for label, rel in SCREENS.items():
    check(f"tokens: {label} uses EcoDesignTokens", "EcoDesignTokens" in read(rel))

# 5. Scope + hard limits.
diff = subprocess.run(["git", "diff", "--name-only", "HEAD"],
                      cwd=ROOT, capture_output=True, text=True).stdout.split()
allowed = set(P4_FILES + ["edge/verify_v5p4.py", "docs/v5/v5-p4-report.md"])
PRE_EXISTING_DIRTY = {
    "docs/big-update/new-requirements.md",
    "docs/vnext-plan.md",
}
extra = [f for f in diff if f not in allowed and f not in PRE_EXISTING_DIRTY]
check("scope: only P4 files changed vs HEAD", not extra, str(extra))
check("scope: NightLaw untouched", "data/NightLaw.kt" not in " ".join(diff))
check("scope: bar/gate files untouched",
      not any(x in diff for x in [
          "app/src/main/java/com/ecojournal/app/ui/components/TypedCard.kt",
          "app/src/main/java/com/ecojournal/app/ui/screens/GatedSectionsScreen.kt",
          "app/src/main/java/com/ecojournal/app/data/NightLaw.kt",
          "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt",
      ]))
gradle = read(GRADLE)
check("version: untouched (still 4.0.1 / 5)",
      'versionCode = 5' in gradle and 'versionName = "4.0.1"' in gradle)
new_diff = subprocess.run(["git", "diff", "HEAD", "--", *P4_FILES],
                          cwd=ROOT, capture_output=True, text=True).stdout
check("limits: no FCM/Play/Bale additions",
      not re.search(r"(?i)\bfcm\b|play store|\bbale\b", new_diff))
check("limits: voices/special_issues gate loop intact",
      "SyncSections.VOICES, SyncSections.SPECIAL_ISSUES" in repo)

# 6. Report honesty.
report = ROOT / "docs/v5/v5-p4-report.md"
check("report: v5-p4-report.md exists", report.exists())
if report.exists():
    rtext = report.read_text()
    check("report: G1 still open",
          re.search(r"G1.*OPEN", rtext) is not None)
    check("report: no A1-fixed / G1-passed claim",
          not re.search(r"(?i)G1\s+(passed|closed)|A1\s+(fixed|resolved)", rtext))
    check("report: Phase 5 next steps present",
          "Phase 5" in rtext)

# 7. Live edge contract (GET only; SKIP on network failure).
BASE = "https://syfazel.github.io/eco-journal-app"
try:
    with urllib.request.urlopen(f"{BASE}/manifest.json", timeout=30) as resp:
        manifest = json.loads(resp.read().decode("utf-8"))
    sections = manifest.get("sections", {})
    for key in ("bundles", "radar", "nightly"):
        s = sections.get(key, {})
        check(f"edge: {key} not_yet_published, count 0, no file, blank hash",
              s.get("status") == "not_yet_published" and s.get("count") == 0 and
              "file" not in s and not s.get("hash"))
    for key in ("market", "channel_refs", "invites", "revocations"):
        s = sections.get(key, {})
        fname = s.get("file")
        if not fname:
            check(f"edge: {key} has a file field", False, "no file field")
            continue
        try:
            with urllib.request.urlopen(f"{BASE}/{fname}", timeout=30) as resp:
                ok = resp.status == 200
        except Exception as e:  # noqa: BLE001 — contract read only
            ok = False
            print(f"  ({key} file fetch failed: {e})")
        check(f"edge: {key} file {fname} fetchable", ok)
    out = ROOT / "edge/out"
    if out.is_dir():
        names = {p.name for p in out.iterdir()}
        check("edge: local out has no bundles/radar/nightly json",
              not ({"bundles.json", "radar.json", "nightly.json"} & names))
    else:
        skip("edge: local out listing", "edge/out missing")
except Exception as e:  # noqa: BLE001 — network is best-effort here
    skip("edge: live manifest contract", f"network unavailable ({e})")

print()
if fails:
    print(f"V5 P4 CHECKS FAILED: {len(fails)} failure(s): {fails}")
    sys.exit(1)
if skips:
    print(f"ALL V5 P4 CHECKS PASSED ({len(skips)} skipped: {skips})")
else:
    print("ALL V5 P4 CHECKS PASSED")
