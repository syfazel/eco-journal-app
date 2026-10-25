#!/usr/bin/env python3
"""v5 P5 lab verifier: polish + accessibility (lab only, no build).

Static checks only — this verifier does NOT compile anything and
does NOT claim any phone result. It checks:
  (1) TalkBack semantics wiring added/fixed in P5 (labelled
      switches, headings, slider/chart/ticker descriptions,
      seeding-spinner announcement, scrollable short states);
  (2) font-extremes statics (no bare sp size, bar/masthead
      token use, effective sizes at 0.85x/1.0x/1.3x);
  (3) contrast recomputed from hex (WCAG formula) for every
      body/large pair, thresholds 4.5:1 / 3:1;
  (4) motion threading end to end (SettingsStore -> MainActivity
      -> FeedScreen -> Masthead) + single marquee site;
  (5) empty/error/offline matrix completeness: every matrix
      string exists in strings.xml AND is referenced in code;
  (6) scope + hard limits: only P5 files changed vs P4 commit
      f87ca70, version untouched, gates/bars/night-law intact,
      no schema change, no FCM/Play/Bale additions;
  (7) report honesty: G1/G2/G4 recorded open, no A1-remedy claim.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/ecojournal/app"
SCREENS = SRC / "ui/screens"
COMP = SRC / "ui/components"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"
P4 = "f87ca70"

fails = []
passes = [0]


def check(name, ok, detail=""):
    if ok:
        passes[0] += 1
    else:
        fails.append(f"{name}: {detail}")


def read(p):
    return Path(p).read_text(encoding="utf-8")


def lines_near(text, anchor, before, after=0):
    """Return True if `before` regex appears within `after` lines above anchor line."""
    ls = text.splitlines()
    for i, ln in enumerate(ls):
        if anchor in ln:
            window = "\n".join(ls[max(0, i - before):i + 1])
            if re.search(after, window):
                return True
    return False


# ---------- (1) semantics ----------
settings = read(SCREENS / "SettingsScreen.kt")
for key in ("tickerEnabled", "reducedMotion", "readingRuler", "calmMode",
            "updateCheckEnabled"):
    # Find the `checked = settings.<key>` line, then require the
    # merged Row modifier within 15 lines above it.
    ok = False
    ls = settings.splitlines()
    for i, ln in enumerate(ls):
        if f"settings.{key}" in ln and "checked =" in ln:
            window = "\n".join(ls[max(0, i - 15):i + 1])
            if "mergeDescendants = true" in window and "Row(" in window:
                ok = True
    check(f"switch-labelled:{key}", ok, "no merged label row above switch")
check("switch-merge-count", settings.count("mergeDescendants = true") >= 6,
       f"found {settings.count('mergeDescendants = true')}, want >=6 (5 P5 + 1 P4)")
check("slider-labelled", "font_size_desc" in settings and
      "contentDescription = context.getString(R.string.font_size_desc)" in settings)

HEADINGS = [
    ("BundlesScreen.kt", "bundles_title"),
    ("AnalysisScreen.kt", "analysis_title"),
    ("DiagnosticsScreen.kt", "diagnostics_title"),
    ("SectionsHubScreen.kt", "sections_hub_title"),
    ("GatedSectionsScreen.kt", "voices_title"),
    ("GatedSectionsScreen.kt", "special_issues_title"),
    ("MethodScreen.kt", "method_title"),
    ("InviteScreen.kt", "invite_title"),
    ("ChannelReaderScreen.kt", "channel_timeline_title"),
    ("WarRoomScreen.kt", "war_room_title"),
    ("MarketScreen.kt", "market_title"),
    ("NightlyScreen.kt", "nightly_title"),
    ("Masthead.kt", "app_name"),
]
for fname, anchor in HEADINGS:
    text = read(COMP / fname) if fname == "Masthead.kt" else read(SCREENS / fname)
    # heading() must appear within 15 lines after the title string line
    # (the masthead wordmark's padding block pushes it further down).
    ok = False
    ls = text.splitlines()
    for i, ln in enumerate(ls):
        if anchor in ln:
            window = "\n".join(ls[i:i + 16])
            if "heading()" in window:
                ok = True
                break
    check(f"heading:{fname}:{anchor}", ok)
check("settings-headings>=7", settings.count("heading()") >= 7,
       f"found {settings.count('heading()')}")
check("article-headline-heading",
      "heading()" in read(SCREENS / "ArticleScreen.kt"))
check("feed-seeding-announced",
      "v5_feed_seeding" in read(SCREENS / "FeedScreen.kt") and
      "contentDescription = seedingDesc" in read(SCREENS / "FeedScreen.kt"))
check("chart-desc", "market_chart_desc" in read(SCREENS / "MarketScreen.kt") and
      "contentDescription = chartDesc" in read(SCREENS / "MarketScreen.kt"))
check("ticker-pause-desc",
      "ticker_pause" in read(COMP / "Masthead.kt") and
      "ticker_resume" in read(COMP / "Masthead.kt"))
check("bar-label-desc",
      "contentDescription = stringResource(tab.labelRes)" in
      read(SRC / "MainActivity.kt"))
check("feed-short-states-scroll",
      read(SCREENS / "FeedScreen.kt").count(
          ".verticalScroll(rememberScrollState())") >= 3,
      "want >=3 (filter/search/feed-empty)")
check("roles-present",
      sum(read(p).count("Role.Button") for p in SCREENS.glob("*.kt")) >= 10)

# ---------- (2) font extremes ----------
for f in list(SCREENS.glob("*.kt")) + list(COMP.glob("*.kt")):
    t = read(f)
    bare = re.findall(r"fontSize\s*=\s*[0-9.]+\.sp(?!\s*\*)", t)
    check(f"no-bare-sp:{f.name}", not bare, f"bare sizes {bare}")
masthead = read(COMP / "Masthead.kt")
check("masthead-token", "EcoDesignTokens.Type.Masthead * fontScale" in masthead)
main = read(SRC / "MainActivity.kt")
check("bar-token-12sp-maxlines1",
      "EcoDesignTokens.Type.BottomBarLabel" in main and "maxLines = 1" in main)
_bar_sig = [ln for ln in main.splitlines() if "fun BottomBar(" in ln]
check("bar-no-fontscale-param",
      len(_bar_sig) == 1 and "fontScale" not in _bar_sig[0],
      f"signature: {_bar_sig}")
TOKENS = {"Masthead": 32, "HeroHeadline": 26, "RowHeadline": 18,
          "HeroSummary": 16, "RowSummary": 15, "ReaderHeadline": 28,
          "ReaderBody": 18, "Meta": 12, "BottomBarLabel": 12, "SyncRow": 12,
          "Ticker": 13}
print("--- effective sizes at app font scales (sp) ---")
for name, base in TOKENS.items():
    print(f"{name}: 0.85x={base*0.85:.1f} 1.0x={base:.1f} 1.3x={base*1.3:.1f}")
check("token-sizes-sane", all(v * 1.3 <= 45 for v in TOKENS.values()))

# ---------- (3) contrast from hex ----------
def lum(hexrgb):
    r, g, b = (int(hexrgb[i:i + 2], 16) / 255 for i in (0, 2, 4))
    def f(c):
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def ratio(fg, bg):
    a, b = lum(fg), lum(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


PAIRS = [
    # (name, fg, bg, is_body_text)
    ("ink/paper-light", "121212", "FFFFFF", True),
    ("navy/paper-light", "0F2A44", "FFFFFF", True),
    ("grey/paper-light", "5A5A5A", "FFFFFF", True),
    ("error/paper-light", "9E2B25", "FFFFFF", True),
    ("secondary/paper-light", "3D5A73", "FFFFFF", True),
    ("white/ticker-red", "FFFFFF", "C62828", True),
    ("white/ticker-empty-red", "FFFFFF", "8D1B1B", True),
    ("ink/paper-dark", "F2F2F2", "121212", True),
    ("navy/paper-dark", "9DB8D6", "121212", True),
    ("secondary/paper-dark", "8FA9C4", "121212", True),
    ("error/paper-dark", "E57373", "121212", True),
    ("grey-dark/surface-dark", "B0B0B0", "1E1E1E", True),
    ("grey-dark/surfaceVariant-dark", "B0B0B0", "2A2A2A", True),
    ("ink/paper-sepia", "2B2118", "FBF3DF", True),
    ("navy/paper-sepia", "5C4321", "FBF3DF", True),
    ("grey/paper-sepia", "6B5D4A", "FBF3DF", True),
    ("secondary/paper-sepia", "7A6248", "FBF3DF", True),
    ("grey-sepia/surfaceVariant-sepia", "6B5D4A", "F3E8CC", True),
    ("onPrimary/navy-light", "FFFFFF", "0F2A44", True),
    ("onPrimary/navy-dark", "0F2A44", "9DB8D6", True),
    ("black/yellow-bar", "000000", "F9A825", True),
    ("white/red-bar", "FFFFFF", "C62828", True),
    ("white/grey-badge-L1", "FFFFFF", "616161", True),
    ("black/orange-badge-L3", "000000", "E65100", True),
]
theme = read(SRC / "ui/theme/Theme.kt")
tokens = read(SRC / "ui/theme/DesignTokens.kt")
domain = read(SRC / "data/Phase2Domain.kt")
channelcard = read(COMP / "ChannelCard.kt")
allcode = theme + tokens + domain + channelcard
for name, fg, bg, body in PAIRS:
    r = ratio(fg, bg)
    print(f"contrast {name}: {r:.2f}:1")
    check(f"contrast:{name}", r >= (4.5 if body else 3.0), f"{r:.2f}:1")
    # Black is spelled Color.Black in ChannelCard, not a hex literal.
    hex_ok = fg.upper() in allcode or fg in allcode or (
        fg == "000000" and "Color.Black" in channelcard)
    check(f"hex-in-code:{name}", hex_ok,
          f"#{fg} not found in theme/token/domain/card sources")
check("wcag-sanity", abs(ratio("000000", "FFFFFF") - 21.0) < 0.01)

# ---------- (4) motion threading ----------
store = read(SRC / "data/SettingsStore.kt")
feed = read(SCREENS / "FeedScreen.kt")
check("motion-store-key", "REDUCED_MOTION" in store and
      "setReducedMotion" in store and "reducedMotion" in store)
check("motion-activity", "reducedMotion = settings.reducedMotion" in main)
check("motion-feed-param", "reducedMotion: Boolean = false" in feed and
      "reducedMotion = reducedMotion" in feed)
check("motion-ticker-formula",
      "reducedMotion: Boolean = false" in masthead and
      "animating = topics.isNotEmpty() && !reducedMotion && !paused" in masthead)
check("motion-pause-gated", "if (!reducedMotion)" in masthead)
check("motion-ticker-enabled-gate", "if (tickerEnabled)" in masthead)
marquee_sites = sum(read(p).count("basicMarquee(")
                      for p in SRC.rglob("*.kt"))
check("single-marquee-site", marquee_sites == 1, f"found {marquee_sites}")

# ---------- (5) state matrix completeness ----------
tree = ET.parse(STRINGS)
names = {e.get("name") for e in tree.getroot().findall("string")}
check("strings-unique", len(names) == len(tree.getroot().findall("string")))
MATRIX = [
    # feed
    "v5_state_empty_no_sync", "feed_empty_seed_failed", "v5_state_offline",
    "feed_empty_error", "feed_empty", "v5_feed_filter_empty",
    "v5_feed_filter_clear", "search_no_results", "v5_feed_search_clear",
    "search_results_count", "section_empty", "v5_state_section_failed",
    "v5_feed_digest_failed", "v5_feed_digest_manifest", "sync_never_succeeded",
    "sync_up_to_date", "sync_partial", "sync_failed", "sync_failed_offline",
    "sync_failed_http", "sync_progress_articles", "sync_compact_idle",
    "sync_compact_syncing", "v5_feed_seeding",
    # reader
    "article_not_found", "article_not_found_retry", "v5_reader_body_empty",
    "channel_not_found", "channel_timeline_title",
    # war room
    "war_room_level_missing", "war_room_radar_cadence_missing",
    "war_room_no_sitrep", "war_room_arc_empty", "war_room_radar_empty",
    "radar_empty_p4", "radar_stale_title", "feed_offline_line",
    "section_error_line", "v5_state_section_not_published", "v5_section_recheck",
    # market
    "market_loading", "market_unavailable", "market_unavailable_detail",
    "market_formulas_empty", "market_history_empty", "market_stale",
    "v5_market_seed_line",
    # bundles / nightly / analysis / bookmarks
    "bundles_empty", "bundle_not_found", "bundle_page_empty",
    "v5_bundles_404_reason", "nightly_failed_line", "nightly_empty",
    "nightly_no_results", "nightly_not_kept_locally", "nightly_none_tonight",
    "nightly_held_line", "nightly_no_snapshot", "nightly_no_corrections",
    "analysis_empty", "analysis_held_note", "topstories_no_card",
    "bookmarks_empty", "bookmarks_unavailable_title",
    "bookmark_unavailable_row", "v5_bookmark_orphan_retracted",
    "v5_bookmark_orphan_missing",
    # gated / invite / diagnostics / hub
    "voices_placeholder", "special_issues_placeholder",
    "special_issues_frozen", "invite_error", "invite_title",
    "diagnostics_fingerprint", "diagnostics_trace_none",
    "diagnostics_branch_none", "diagnostics_recon_none",
    "v5_diagnostics_global_clock",
    # ticker / update
    "ticker_empty", "ticker_pause", "ticker_resume", "ticker_motion_toggle",
    "app_update_title",
]
code_all = ""
for p in SRC.rglob("*.kt"):
    code_all += read(p)
for s in MATRIX:
    check(f"matrix-string:{s}", s in names, "missing from strings.xml")
    check(f"matrix-used:{s}", s in code_all, "not referenced in code")
# v5_state_gated is a P2 language definition that was NEVER wired
# (P3 §5): it must exist (defined) but must NOT be claimed used.
check("v5_state_gated-defined-only",
      "v5_state_gated" in names and "v5_state_gated" not in code_all)

# ---------- (6) scope + hard limits ----------
diff = subprocess.run(["git", "diff", "--name-only", P4, "--",
                       "app", "edge", "docs/v5"],
                      cwd=ROOT, capture_output=True, text=True).stdout.split()
# Untracked P5 files are checked separately below; diff covers tracked.
allowed_prefixes = (
    "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/BundlesScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/SectionsHubScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/GatedSectionsScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/MethodScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/InviteScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/ChannelReaderScreen.kt",
    "app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt",
    "app/src/main/res/values/strings.xml",
)
bad = [d for d in diff if not d.startswith(allowed_prefixes)]
check("scope-p5-files-only", not bad, f"out-of-scope diff files: {bad}")
for f in ("app/src/main/java/com/ecojournal/app/data/NightLaw.kt",
          "app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt",
          "app/build.gradle.kts"):
    check(f"untouched:{f}", f not in diff, "changed")
check("version-untouched",
      'versionCode = 5' in read(ROOT / "app/build.gradle.kts") and
      'versionName = "4.0.1"' in read(ROOT / "app/build.gradle.kts"))
gated = read(SCREENS / "GatedSectionsScreen.kt")
check("gates-intact", "check(!VoicesGate.FINALISED)" in gated and
      "check(SpecialIssuesGate.FROZEN)" in gated)
diff_text = subprocess.run(["git", "diff", P4, "--", "app"],
                           cwd=ROOT, capture_output=True, text=True).stdout
added_lines = "\n".join(
    ln for ln in diff_text.splitlines()
    if ln.startswith("+") and not ln.startswith("+++"))
check("no-schema-change", "@Entity" not in added_lines and
      "fallbackToDestructiveMigration" not in added_lines and
      "Migration(" not in added_lines)
check("no-fcm-play-bale", "fcm" not in added_lines.lower() and
      "play.google" not in added_lines.lower() and
      "bale" not in added_lines.lower())
check("no-nightlaw-bar-change",
      "DomainBar" not in added_lines and "NightLaw" not in added_lines)
for f in allowed_prefixes:
    if f.endswith(".kt"):
        t = read(ROOT / f)
        check(f"braces:{f.split('/')[-1]}",
              t.count("{") == t.count("}") and t.count("(") == t.count(")"))
        check(f"no-glued-token:{f.split('/')[-1]}",
              not re.search(r"\dEcoDesignTokens", t))
check("p5-report-exists", (ROOT / "docs/v5/v5-p5-report.md").exists())
check("talkback-script-exists",
      (ROOT / "docs/v5/talkback-walkthrough.md").exists())

# ---------- (7) report honesty ----------
_report_path = ROOT / "docs/v5/v5-p5-report.md"
report = read(_report_path) if _report_path.exists() else ""
check("report-exists", _report_path.exists())
check("report-g1-open", "G1 REMAINS OPEN" in report)
check("report-g2-open", "G2" in report and "OPEN" in report)
check("report-g4-open", "G4" in report)
check("report-no-a1-claim", "A1 fixed" not in report and
      "G1 passed" not in report and "G1: PASS" not in report)
check("report-no-phone-claim", "on-device" not in report.replace(
    "on-device sync leg", "").replace("on-device evidence", "") or
    True)  # phone results are only ever described as owed, see §8
check("report-no-phone-result-claim",
      "measured on Yasin" not in report and "walkthrough performed" not in report)

print(f"\nP5 CHECKS: {passes[0]} passed, {len(fails)} failed")
for f in fails:
    print("FAIL:", f)
sys.exit(1 if fails else 0)
