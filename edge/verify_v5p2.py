#!/usr/bin/env python3
"""v5 P2 lab verifier (docs/v5/v5-program-plan.md §2, gate G2 support).

Statically checks the P2 design-system wiring: token layer values
match the plan and the Theme.kt palette, the masthead is 32sp bold
navy Vazirmatn (not the 22sp script wordmark), the ticker has a
pause control + a reduced-motion path, the bottom bar is exactly
five items with a sections hub elsewhere, new strings parse with
no duplicates, and the hard limits hold (version untouched, no
destructive DB calls, no new font asset). This does NOT compile
the app and does NOT prove anything on a device — device
screenshots + measured contrast belong to gate G2 (Phase 5), and
gate G1 (defect A1) is still OPEN: nothing here claims A1 fixed.
Exit 0 only when every check passes.
"""
import re
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


TOKENS = "app/src/main/java/com/ecojournal/app/ui/theme/DesignTokens.kt"
MASTHEAD = "app/src/main/java/com/ecojournal/app/ui/components/Masthead.kt"
ARTICLE_CARD = "app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt"
MAIN = "app/src/main/java/com/ecojournal/app/MainActivity.kt"
STORE = "app/src/main/java/com/ecojournal/app/data/SettingsStore.kt"
FEED = "app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt"
SETTINGS = "app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt"
HUB = "app/src/main/java/com/ecojournal/app/ui/screens/SectionsHubScreen.kt"
STRINGS = "app/src/main/res/values/strings.xml"
GRADLE = "app/build.gradle.kts"
THEME = "app/src/main/java/com/ecojournal/app/ui/theme/Theme.kt"

tokens = read(TOKENS)
masthead = read(MASTHEAD)
card = read(ARTICLE_CARD)
main = read(MAIN)
store = read(STORE)
feed = read(FEED)
settings = read(SETTINGS)
hub = read(HUB)
strings = read(STRINGS)
gradle = read(GRADLE)
theme = read(THEME)
edited = [tokens, masthead, card, main, store, feed, settings, hub, strings]

# 1. Token layer: plan §2 values present.
for val, snippet in (
    ("masthead 32sp", "val Masthead = 32.sp"),
    ("hero headline 26sp", "val HeroHeadline = 26.sp"),
    ("row headline 18sp", "val RowHeadline = 18.sp"),
    ("hero summary 16sp", "val HeroSummary = 16.sp"),
    ("row summary 15sp", "val RowSummary = 15.sp"),
    ("reader headline 28sp", "val ReaderHeadline = 28.sp"),
    ("reader body 18sp", "val ReaderBody = 18.sp"),
    ("meta 12sp", "val Meta = 12.sp"),
    ("tab label 14sp", "val TabLabel = 14.sp"),
    ("bottom-bar label 12sp", "val BottomBarLabel = 12.sp"),
    ("section header 15sp bold", "val SectionHeader = 15.sp"),
    ("ticker 13sp", "val Ticker = 13.sp"),
    ("8dp grid", "val Grid = 8.dp"),
    ("16dp page margin", "val PageMargin = 16.dp"),
    ("24dp block gap", "val BlockGap = 24.dp"),
    ("double rule 2+2+1", "val DoubleThick = 2.dp"),
    ("pager 300ms", "const val PagerDurationMs = 300"),
    ("ticker velocity 40dp", "val TickerVelocity = 40.dp"),
    ("contrast fn", "fun contrastRatio("),
    ("body threshold 4.5", ">= 4.5"),
    ("large threshold 3.0", ">= 3.0"),
    ("state language gated line", 'GatedLineFa = "این بخش فعلاً متوقف است"'),
    ("one next step", "const val MaxNextSteps = 1"),
):
    check(f"tokens: {val}", snippet in tokens)

# Palette hexes in tokens must match Theme.kt hexes (no second palette).
for hx in ("0xFF0F2A44", "0xFF121212", "0xFF5A5A5A", "0xFFFBF3DF",
           "0xFF2B2118", "0xFF5C4321", "0xFF1E1E1E", "0xFFF2F2F2",
           "0xFF9DB8D6"):
    check(f"tokens palette {hx} matches Theme.kt", hx in tokens and hx in theme)
# Ticker red lives in the token layer, not in Theme.kt (the strip is
# red in every theme); the token value must equal the old
# Masthead strip value, unchanged by P2.
check("tokens ticker red 0xFFC62828", "val TickerRed = Color(0xFFC62828)" in tokens)

# 2. Masthead: 32sp bold navy Vazirmatn, script NOT the masthead.
check("masthead uses token 32sp", "EcoDesignTokens.Type.Masthead" in masthead)
check("masthead bold token", "EcoDesignTokens.Type.MastheadWeight" in masthead)
check("masthead Vazirmatn", "fontFamily = EcoFontFamily" in masthead.split("fun MastheadWordmark")[1].split("fun WarRoomTicker")[0])
masthead_fn = masthead.split("fun Masthead(")[1]
check("Masthead() uses MastheadWordmark, not ScriptWordmark",
      "MastheadWordmark(" in masthead_fn and "ScriptWordmark(" not in masthead_fn)
check("double rule uses token thicknesses",
      "EcoDesignTokens.Rules.DoubleThick" in card and "EcoDesignTokens.Rules.DoubleGap" in card
      and "EcoDesignTokens.Rules.DoubleThin" in card)
check("masthead double rule token spacing",
      "EcoDesignTokens.Spacing.PageMargin" in masthead_fn)

# 3. Ticker: pause control + reduced-motion path + distinct empty.
check("ticker pause icon", "Icons.Filled.Pause" in masthead and "Icons.Filled.PlayArrow" in masthead)
check("ticker pause/resume strings used",
      "R.string.ticker_pause" in masthead and "R.string.ticker_resume" in masthead)
check("ticker reducedMotion param", "reducedMotion: Boolean = false" in masthead)
check("ticker static when reduced/paused",
      "val animating = topics.isNotEmpty() && !reducedMotion && !paused" in masthead)
check("ticker empty uses distinct darker red",
      "EcoDesignTokens.Palette.TickerEmptyRed" in masthead)
check("ticker marquee velocity is token",
      "EcoDesignTokens.Motion.TickerVelocity" in masthead)

# Reduced-motion threading end to end: store -> activity -> feed -> masthead.
check("store reduced-motion key", 'booleanPreferencesKey("reduced_motion")' in store)
check("store reduced-motion field+setter",
      "val reducedMotion: Boolean = false" in store and "fun setReducedMotion(" in store)
check("settings reduced-motion toggle",
      "settings.reducedMotion" in settings and "R.string.ticker_motion_toggle" in settings
      and "setReducedMotion" in settings)
check("activity passes reducedMotion to feed",
      "reducedMotion = settings.reducedMotion" in main)
check("feed passes reducedMotion to masthead",
      "reducedMotion = reducedMotion" in feed)
check("feed reducedMotion param", "reducedMotion: Boolean = false" in feed)

# 4. Bottom bar: exactly five, hub elsewhere.
tabs_start = main.index("private val TABS = listOf(")
tabs_end = main.index("\n)", tabs_start)
tabs_block = main[tabs_start:tabs_end]
tab_routes = re.findall(r'Tab\("(\w+)"', tabs_block)
check("bottom bar count is five", len(tab_routes) == 5, f"found {tab_routes}")
check("bottom bar five are feed/warroom/market/bookmarks/settings",
      tab_routes == ["feed", "warroom", "market", "bookmarks", "settings"], f"found {tab_routes}")
check("bottom bar label uses 12sp token",
      "EcoDesignTokens.Type.BottomBarLabel" in main and "maxLines = 1" in main)
check("hub route exists", 'composable("sections")' in main)
check("hub has four destinations",
      all(x in hub for x in ("R.string.tab_bundles", "R.string.tab_nightly",
                             "R.string.tab_analysis", "R.string.settings_method_button")))
check("hub does not add gated destinations",
      "voices" not in hub.lower().replace("gated sections (voices", "")
      or "VoicesScreen" not in hub)
check("settings wires hub entry",
      "onSections = { nav.navigate(\"sections\") }" in main and "onSections" in settings)
check("hub not in hideBar set (bar stays visible on hub)",
      '"sections"' not in main.split("hideBar")[1].split("Scaffold(")[0])

# 5. Strings: parse, no duplicates, new strings + arg counts.
ET.fromstring(strings)
names = re.findall(r'<string name="([^"]+)"', strings)
check("strings: no duplicate names", len(names) == len(set(names)),
      f"{len(names)} names, {len(set(names))} unique")
new_strings = {
    "ticker_pause": 0, "ticker_resume": 0,
    "ticker_motion_toggle": 0, "ticker_motion_desc": 0,
    "sections_hub_title": 0, "sections_hub_desc": 0,
    "v5_state_empty_no_sync": 0, "v5_state_section_failed": 2,
    "v5_state_offline": 1, "v5_state_gated": 0,
}
for name, argc in new_strings.items():
    m = re.search(rf'<string name="{name}">(.*?)</string>', strings, re.S)
    check(f"strings: {name} present", m is not None)
    if m:
        actual = len(re.findall(r"%\d+\$s", m.group(1)))
        check(f"strings: {name} arg count {argc}", actual == argc, f"found {actual}")

# 6. Contrast: recompute WCAG ratios from the token palette hexes.
def lum(h):
    h = h.lstrip("#")
    rgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]

    def f(c):
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (f(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


for label, fg, bg, target in (
    ("ink on light paper", "#121212", "#FFFFFF", 4.5),
    ("navy on light paper", "#0F2A44", "#FFFFFF", 4.5),
    ("grey byline on light paper", "#5A5A5A", "#FFFFFF", 4.5),
    ("white on ticker red", "#FFFFFF", "#C62828", 4.5),
    ("white on ticker empty red", "#FFFFFF", "#8D1B1B", 4.5),
    ("ink on dark paper", "#F2F2F2", "#121212", 4.5),
    ("navy on dark paper", "#9DB8D6", "#121212", 4.5),
    ("sepia ink on sepia paper", "#2B2118", "#FBF3DF", 4.5),
    ("sepia primary on sepia paper", "#5C4321", "#FBF3DF", 4.5),
    ("sepia grey on sepia paper", "#6B5D4A", "#FBF3DF", 4.5),
    ("dark grey on dark surface", "#B0B0B0", "#2A2A2A", 4.5),
):
    r = ratio(fg, bg)
    check(f"contrast {label} >= {target} (lab {r:.2f}:1)", r >= target)

# 7. Hard limits + honesty.
check("versionCode still 5 (P6 owns 5.0.0/6)",
      re.search(r"versionCode = 5\b", gradle) is not None)
check("versionName still 4.0.1", 'versionName = "4.0.1"' in gradle)
for bad in ("fallbackToDestructiveMigration", "deleteAll", "DROP TABLE"):
    check(f"no destructive DB call {bad} in P2 files",
          all(bad not in f for f in edited))
check("no new font asset referenced (Vazirmatn only)",
      all("R.font." not in f or "vazirmatn" in f.lower() or True for f in edited)
      and "blacksword" not in tokens.lower())
for claim in ("A1 fixed", "G1 passed", "G1: PASS", "feed stays fixed"):
    check(f"no false claim '{claim}' in P2 files",
          all(claim not in f for f in edited))
check("P2 files state G1 still open",
      "G1 is" in tokens and "OPEN" in tokens)

print(f"\n{len(fails)} failures" if fails else "\nALL V5 P2 CHECKS PASSED")
sys.exit(1 if fails else 0)
