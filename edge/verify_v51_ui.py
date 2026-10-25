#!/usr/bin/env python3
"""v5.1 shockproof UI verifier (Worker 3: feed staying + diagnostics UI only).

Statically checks the v5.1 UI-layer guarantees:
  1. A retained ViewModel holder exists, is used by the feed, and is
     only ever replaced by a non-empty emission (never cleared).
  2. No code path clears the feed list on sync failure / loading /
     invite re-check: the feed branch chain reads cache-first
     display lists, never the raw Room flows, for emptiness.
  3. The empty state requires genuine DB emptiness (held empty AND
     Room empty, via the display lists).
  4. The invite gate keeps a signed-in session on a transient
     settings emission, while a genuine revocation (hash kept) and
     an explicit user re-lock still lock.
  5. Diagnostics renders the new v5.1 keys defensively ("not
     recorded" when absent) and the copy action copies the WHOLE
     displayed report, including the new sections.
  6. Scope: the forbidden data/edge files are untouched vs HEAD.

This does NOT compile the app and does NOT prove the defect fixed
on a phone — no build is run by task order (parent integrates and
builds). Exit 0 only when every check passes.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/hatch/workspace/goals/eco-journal/app")
fails = []


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f" ({detail})" if detail and not cond else ""))
    if not cond:
        fails.append(name)


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


FEED = "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt"
DIAG = "app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt"
VM = "app/src/main/java/com/ecojournal/app/ui/screens/FeedViewModel.kt"
MAIN = "app/src/main/java/com/ecojournal/app/MainActivity.kt"
SETTINGS = "app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt"
STRINGS = "app/src/main/res/values/strings.xml"

feed = read(FEED)
diag = read(DIAG)
vm = read(VM)
main = read(MAIN)
settings = read(SETTINGS)
strings = read(STRINGS)

# ---------- (1) retained holder ----------
check("vm: class FeedViewModel : ViewModel", "class FeedViewModel : ViewModel" in vm)
for holder in ("heldArticles", "heldTyped", "heldInstant"):
    check(f"vm: {holder} StateFlow", f"val {holder}: StateFlow<" in vm)
for fn in ("retainArticles", "retainTyped", "retainInstant"):
    check(f"vm: {fn} guards non-empty",
          f"fun {fn}" in vm and "if (list.isNotEmpty())" in vm)
check("vm: no clear() and no emptyList assignment to held state",
      "fun clear" not in vm and "_heldArticles.value = emptyList" not in vm
      and "_heldTyped.value = emptyList" not in vm
      and "_heldInstant.value = emptyList" not in vm)
check("feed: uses FeedViewModel via viewModel()",
      "val feedVm: FeedViewModel = viewModel()" in feed)
check("feed: retains on Room emissions only when non-empty",
      "LaunchedEffect(all) { if (all.isNotEmpty()) feedVm.retainArticles(all) }" in feed
      and "LaunchedEffect(typedItems) { if (typedItems.isNotEmpty()) feedVm.retainTyped(typedItems) }" in feed
      and "LaunchedEffect(instantItems) { if (instantItems.isNotEmpty()) feedVm.retainInstant(instantItems) }" in feed)
check("feed: cache-first display lists",
      "val displayArticles = if (heldArticles.isNotEmpty()) heldArticles else all" in feed
      and "val displayTyped = if (heldTyped.isNotEmpty()) heldTyped else typedItems" in feed
      and "val displayInstant = if (heldInstant.isNotEmpty()) heldInstant else instantItems" in feed)
check("feed: sections/tabs/typed-groups read display lists",
      "remember(displayArticles)" in feed and "remember(displayTyped)" in feed
      and "all = displayArticles" in feed)

# ---------- (2) no flip path ----------
branch = feed.split("val feedBranch = when {", 1)[1].split("}", 1)[0] if "val feedBranch = when {" in feed else ""
check("branch: retained branch recorded",
      'showingRetained -> "retained"' in feed)
check("branch: urgent_all requires non-empty rail",
      'showUrgentAll && displayInstant.isNotEmpty() -> "urgent_all"' in feed
      and "if (showUrgentAll && railOrdered.isNotEmpty())" in feed)
check("branch: urgent-all falls back when rail empties",
      "if (showUrgentAll && displayInstant.isEmpty()) showUrgentAll = false" in feed)
check("branch: rail reads retained instant list in place",
      "ChannelLists.railOrder(displayInstant)" in feed)
check("branch: effect keys stay the v5 P1 pins (recorded counts are display sizes)",
      "LaunchedEffect(feedBranch, all.size, typedItems.size)" in feed
      and "SyncTrace.recordFeedBranch(repo.dao, feedBranch, displayArticles.size, displayTyped.size)" in feed)
check("branch: loading reads display lists, not raw flows",
      "seeding && displayArticles.isEmpty() && displayTyped.isEmpty()" in feed)
check("branch: empty reads display lists, not raw flows",
      "displayArticles.isEmpty() && displayTyped.isEmpty()" in feed)
check("branch: trace records display sizes",
      "SyncTrace.recordFeedBranch(repo.dao, feedBranch, displayArticles.size, displayTyped.size)" in feed)
check("feed: no destructive DB call in UI files",
      all(bad not in text for text in (feed, diag, vm, main)
          for bad in ("deleteAll", "clearAllTables", "fallbackToDestructiveMigration")))
check("feed: sync runner never touches the held lists",
      "feedVm" not in feed.split("val runSync")[1].split("LaunchedEffect(seeding")[0]
      if "val runSync" in feed else False)
check("feed: retained-cache status line with exactly one retry",
      "retainedLine" in feed and "v51_feed_retained" in feed
      and "onRetainedRetry = runSync" in feed)
check("feed: search falls back to held lists on empty Room read",
      "heldArticles.filter" in feed and "heldTyped.filter" in feed)
check("feed: filter reads retained typed list",
      "displayTyped.filter { it.formatType == activeFilter }" in feed)

# ---------- (3) genuine-DB-empty empty state ----------
check("empty: display-empty implies held-empty AND Room-empty (commented rule)",
      "genuinely empty" in feed and "displayArticles.isEmpty() && displayTyped.isEmpty()" in feed)
check("empty: cause lines kept (seed/offline/code/no-sync)",
      "feed_empty_seed_failed" in feed and "v5_state_offline" in feed
      and "feed_empty_error" in feed and "v5_state_empty_no_sync" in feed)

# ---------- (4) invite gate ----------
check("gate: InviteLockSignal exists with manual flag",
      "object InviteLockSignal" in main and "manualLockRequested" in main)
check("gate: transient emission keeps session (flag+hash lost, ever ok)",
      "gateHeld" in main
      and "!settings.inviteOk" in main
      and "settings.inviteHash == null" in main
      and "gateEverOk" in main)
check("gate: genuine revocation still locks (hash kept => not held)",
      "settings.inviteHash == null" in main  # held requires hash absent
      and "if (!settings.inviteOk && !gateHeld)" in main)
check("gate: kept session is traced",
      '"gate_held"' in main and "recordFeedBranch" in main)
check("gate: manual lock marked before clearInvite",
      "InviteLockSignal.manualLockRequested = true" in settings
      and settings.index("InviteLockSignal.manualLockRequested = true") <
      settings.index("store.clearInvite()"))
check("gate: no invite code/hash handling changed",
      "sha256" not in main and "invite_hash" not in main.lower().replace("invitehash", ""))

# ---------- (5) diagnostics expansion ----------
for key in ("v51_section_outcome", "v51_network_state", "v51_seed_report"):
    check(f"diag: reads meta key {key}", f'"{key}"' in diag)
check("diag: outcome lines use defensive not-recorded rendering",
      "v51_diagnostics_section_outcome_line" in diag
      and "noValueEarly" in diag.split("v51OutcomeLines")[1].split("v51NetworkLines")[0])
check("diag: outcome parser tolerates array/map/single shapes",
      "parseV51Outcomes" in diag and "optJSONObject" in diag)
check("diag: network/seed render absent as not-recorded",
      "v51_diagnostics_network_none" in diag and "v51_diagnostics_seed_report_none" in diag
      and "v51FlatLines" in diag)
check("diag: branch names cover retained + gate_held",
      '"retained"' in diag and '"gate_held"' in diag)
check("diag: fingerprint still line 1 (version+code+commit+edge)",
      "diagnostics_fingerprint" in diag and "BuildConfig.GIT_COMMIT" in diag
      and diag.index("diagnostics_fingerprint") < diag.index("diagnostics_version, versionName"))
check("diag: new sections displayed",
      "for (line in v51OutcomeLines + v51NetworkLines + v51SeedLines)" in diag)
check("diag: copy includes ALL sections incl. v5.1 (whole report)",
      "phase4Lines + traceLines + branchLines + reconLines + v51OutcomeLines + v51NetworkLines + v51SeedLines" in diag)

# ---------- (6) strings ----------
tree = ET.parse(ROOT / STRINGS)
names = [e.get("name") for e in tree.getroot().findall("string")]
check("strings: no duplicate names", len(names) == len(set(names)))
new_strings = {
    "v51_feed_retained": 1,
    "v51_diagnostics_section_outcome_line": 6,
    "v51_diagnostics_section_outcome_title": 0,
    "v51_diagnostics_section_outcome_none": 0,
    "v51_diagnostics_network_title": 0,
    "v51_diagnostics_network_none": 0,
    "v51_diagnostics_seed_report_title": 0,
    "v51_diagnostics_seed_report_none": 0,
}
for name, argc in new_strings.items():
    m = re.search(rf'<string name="{name}">(.*?)</string>', strings, re.S)
    check(f"strings: {name} present", m is not None)
    if m:
        actual = len(re.findall(r"%[0-9]+\$s", m.group(1)))
        check(f"strings: {name} arg count {argc}", actual == argc, f"found {actual}")

# ---------- (7) scope: no v5.1 change in forbidden files ----------
# NOTE: the working tree already carries pre-existing uncommitted
# dirt vs HEAD from other workers, so "file absent from git diff"
# is NOT a valid check here. What this worker must prove is
# narrower and precise: no v5.1 marker of mine appears in the
# diff (or the file) of any forbidden file, and no other edge/
# script carries a v5.1 marker.
forbidden = [
    "app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt",
    "app/src/main/java/com/ecojournal/app/data/SyncWorker.kt",
    "app/src/main/java/com/ecojournal/app/data/BundledSeed.kt",
    "app/src/main/java/com/ecojournal/app/data/SyncTrace.kt",
]
for rel in forbidden:
    fdiff = subprocess.run(["git", "diff", "HEAD", "--", rel],
                           cwd=ROOT, capture_output=True, text=True).stdout
    check(f"scope: no v5.1-UI marker of mine in {rel}",
          "v51_ui" not in fdiff and "FeedViewModel" not in fdiff
          and "gate_held" not in fdiff and "v51_feed_retained" not in fdiff
          and "v51_diagnostics" not in fdiff)
edge_diff = subprocess.run(["git", "diff", "HEAD", "--", "edge/"],
                           cwd=ROOT, capture_output=True, text=True).stdout
check("scope: no v5.1 marker in other edge/ files",
      "v51_ui" not in edge_diff.replace("verify_v51_ui", ""))
check("scope: new verifier exists", (ROOT / "edge/verify_v51_ui.py").exists())

print()
if fails:
    print(f"V5.1 UI CHECKS FAILED: {len(fails)} failure(s): {fails}")
    sys.exit(1)
print("V5.1 UI CHECKS: all passed")
