#!/usr/bin/env python3
"""v5 P3 lab verifier (static only — does NOT compile anything).

Checks the Phase P3 feed + reader rebuild (docs/v5/v5-p3-report.md):
token adoption on feed/reader, v5_state_* wiring, strings
uniqueness, and that no feed-branch, feed-query, sync, or Room
schema behaviour changed beyond what P3 declares (none declared:
P3 is presentation-only). Also checks the P1 preconditions P3 was
required to verify (fingerprint line, branch trace) and that the
P3 report keeps G1 open and does not present the rebuild as an
A1 fix. Run from the app root: python3 edge/verify_v5p3.py
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
fails = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        fails.append(name)


def read(rel):
    return (ROOT / rel).read_text()


STRINGS = "app/src/main/res/values/strings.xml"
SCOPE = [
    "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/ChannelReaderScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/components/SyncCompact.kt",
]

# 1. Strings: parse, no duplicates, P3 strings + arg counts.
sxml = read(STRINGS)
names = re.findall(r'<string name="([^"]+)"', sxml)
check("strings: no duplicate names", len(names) == len(set(names)),
      f"{len(names)} names, {len(set(names))} unique")
P3_STRINGS = {
    "v5_feed_digest_failed": 2,
    "v5_feed_digest_manifest": 1,
    "v5_feed_filter_empty": 0,
    "v5_feed_filter_clear": 0,
    "v5_feed_search_clear": 0,
    "v5_feed_section_index_title": 0,
    "v5_feed_section_index_names": 0,
    "v5_feed_section_index_open": 0,
    "v5_reader_body_empty": 0,
}
for name, argc in P3_STRINGS.items():
    m = re.search(rf'<string name="{name}">(.*?)</string>', sxml, re.S)
    check(f"strings: {name} present", m is not None)
    if m:
        actual = len(re.findall(r"%\d+\$s", m.group(1)))
        check(f"strings: {name} arg count {argc}", actual == argc, f"found {actual}")

# 2. Token adoption: no literal type sizes / page spacings left in
# scope files, except the documented 20sp timeline title (no P2
# token exists for 20sp) in ChannelReaderScreen.
for rel in SCOPE:
    text = read(rel)
    bad_sp = []
    bad_dp = []
    for line in text.splitlines():
        if "import " in line or "EcoDesignTokens" in line:
            continue
        for m in re.finditer(r"\b(\d+)\.sp\b", line):
            if rel.endswith("ChannelReaderScreen.kt") and m.group(1) == "20":
                continue
            bad_sp.append(line.strip()[:80])
        for m in re.finditer(r"\b(16|8)\.dp\b", line):
            bad_dp.append(line.strip()[:80])
    check(f"tokens: {rel.split('/')[-1]} no literal .sp", not bad_sp, str(bad_sp[:3]))
    check(f"tokens: {rel.split('/')[-1]} no literal 16/8dp", not bad_dp, str(bad_dp[:3]))
    check(f"tokens: {rel.split('/')[-1]} uses EcoDesignTokens",
          "EcoDesignTokens" in text)
    # Regression guard for a real P3 incident: a naive "8.dp"
    # replace once rewrote "48.dp" as "4EcoDesignTokens…",
    # which does not compile. Any digit glued to a token is
    # always corruption, never valid Kotlin.
    check(f"tokens: {rel.split('/')[-1]} no digit-glued token",
          not re.search(r"\dEcoDesignTokens", text))

# Token values actually used (not just imported).
feed = read(SCOPE[0])
check("tokens: feed uses RowHeadline/SectionHeader/Meta",
      "Type.RowHeadline" in feed and "Type.SectionHeader" in feed
      and "Type.Meta" in feed)
article = read(SCOPE[2])
check("tokens: reader uses ReaderHeadline/ReaderBody/Calm",
      "Type.ReaderHeadline" in article and "Type.ReaderBody" in article
      and "LineHeight.ReaderBodyCalm" in article)
check("tokens: reader headline line is token, not product",
      "LineHeight.ReaderHeadline" in article and "headlineSize * 1.35f" not in article)

# 3. State-string wiring.
check("wiring: feed empty-no-sync + offline + section-failed",
      "v5_state_empty_no_sync" in feed and "v5_state_offline" in feed
      and "v5_state_section_failed" in feed)
check("wiring: feed digest + filter/search empties + section index",
      "feedDigest" in feed and "SyncTrace.TRACE_KEY" in feed
      and "v5_feed_digest_failed" in feed and "v5_feed_digest_manifest" in feed
      and "v5_feed_filter_empty" in feed and "v5_feed_filter_clear" in feed
      and "v5_feed_search_clear" in feed
      and "v5_feed_section_index_title" in feed
      and "v5_feed_section_index_open" in feed)
check("wiring: feed section index opens hub",
      "onOpenSections" in feed and "onOpenSections" in read(
          "app/src/main/java/com/ecojournal/app/MainActivity.kt")
      and 'nav.navigate("sections")' in read(
          "app/src/main/java/com/ecojournal/app/MainActivity.kt"))
check("wiring: reader body-empty (article + channel)",
      "v5_reader_body_empty" in article
      and "v5_reader_body_empty" in read(SCOPE[4]))
check("wiring: section-empty has sync retry",
      "section_empty" in feed and "onSync" in feed)
sync_row = read(SCOPE[5])
check("wiring: sync row two lines on failure",
      "if (syncFailed) 2 else 1" in sync_row)

# 4. No behaviour change beyond presentation.
diff = subprocess.run(["git", "diff", "--name-only", "HEAD"],
                       cwd=ROOT, capture_output=True, text=True).stdout.split()
allowed = set(SCOPE + [
    "app/src/main/java/com/ecojournal/app/MainActivity.kt",
    STRINGS,
    "edge/verify_v5p3.py",
    "docs/v5/v5-p3-report.md",
])
# Pre-existing dirt, documented as untouched in the P1 and P2
# reports (dirty before P3 started, never committed by P3):
# they appear in `git diff` but are not P3 changes.
PRE_EXISTING_DIRTY = {
    "docs/big-update/new-requirements.md",
    "docs/vnext-plan.md",
}
extra = [f for f in diff if f not in allowed and f not in PRE_EXISTING_DIRTY]
check("scope: only P3 files changed vs HEAD", not extra, str(extra))
forbidden_files = ["app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt",
                   "app/src/main/java/com/ecojournal/app/data/AppDatabase.kt",
                   "app/src/main/java/com/ecojournal/app/data/SyncTrace.kt",
                   "app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt",
                   "app/src/main/java/com/ecojournal/app/data/SettingsStore.kt",
                   "app/build.gradle.kts"]
check("scope: sync/db/diagnostics/store/gradle untouched",
      not any(f in diff for f in forbidden_files),
      str([f for f in forbidden_files if f in diff]))
for rel in SCOPE + ["app/src/main/java/com/ecojournal/app/MainActivity.kt"]:
    text = read(rel)
    check(f"scope: {rel.split('/')[-1]} no destructive DB call",
          "deleteAll" not in text and "clearAllTables" not in text
          and "fallbackToDestructiveMigration" not in text)
check("scope: feed branch + query untouched",
      "recordFeedBranch" in feed and "repo.dao.allFlow()" in feed
      and '"urgent_all"' in feed and '"front_page"' in feed
      and "showUrgentAll" in feed and "selectedFormat" in feed)
check("scope: no schema annotation touched (AppDatabase diff empty)",
      "AppDatabase.kt" not in " ".join(diff))

# 5. P1 preconditions verified present.
diag = read("app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt")
check("p1-precondition: fingerprint line first in Diagnostics",
      "diagnostics_fingerprint" in diag and "BuildConfig.GIT_COMMIT" in diag)
check("p1-precondition: branch trace wired in FeedScreen",
      "recordFeedBranch" in feed and "feedBranch" in feed)

# 6. Version untouched (P6 owns 5.0.0/6).
gradle = read("app/build.gradle.kts")
check("version: still 4.0.1 / 5",
      'versionCode = 5' in gradle and 'versionName = "4.0.1"' in gradle)

# 7. Report honesty.
report = read("docs/v5/v5-p3-report.md")
check("report: G1 recorded still open", "G1 REMAINS OPEN" in report)
low = report.lower()
check("report: no A1-fix claim",
      "a1 fixed" not in low and "the a1 fix" not in low and "g1 passed" not in low)

print(f"\n{len(fails)} failures" if fails else "\nALL V5 P3 CHECKS PASSED")
sys.exit(1 if fails else 0)
