# ECO JOURNAL — Version 5, Phase P2 Report: design system

Status: **DESIGN SYSTEM ONLY — no build, no push, no publish, no
live change.** Lab commit only (see §6).
Task order: P2 design system, per `docs/v5/v5-program-plan.md`
§2, on top of P1 commit `209e0fb`, under Yasin's full go-ahead
of 2026-10-26 ~01:36 Tehran (all open decisions delegated to
Muse). Base verified before work: HEAD `209e0fb`, gradle still
`4.0.1` / versionCode 5 (unchanged — P6 owns the 5.0.0 / 6 bump).

## 1. G1 verdict — STILL OPEN, and P2 does not fix A1

**Gate G1 is still open. Defect A1 (feed flashes, then
vanishes) is NOT identified, and nothing in this phase fixes
it, works around it, or uses it as a rationale.** P2 changes
the masthead, the ticker controls, and the bottom bar only.
It does not touch feed branching, any feed query, sync, or
the Room schema. The Phase 3 feed-stays gate (G3) still
requires the P1 device-reproduction protocol
(`docs/v5/v5-p1-report.md` §4) on an instrumented build
before any feed claim is valid. No file in this phase
claims otherwise (the verifier checks for "A1 fixed" /
"G1 passed" strings and fails if they appear).

## 2. Delegated decisions taken (recorded, not re-asked)

1. **Masthead = 32sp bold navy newspaper masthead** (plan
   recommendation). This supersedes the earlier small-script
   preference for v5: the audit found the 22sp platform
   cursive weak, and no bundled OFL script font asset
   exists — Blacksword is personal-use only and is never
   shipped. The old `ScriptWordmark` composable is retained
   in `Masthead.kt` ONLY as a legacy secondary mark and is
   no longer called by `Masthead()`; it must not be
   re-promoted without a bundled OFL script font file first.
2. **Red CNN-style ticker stays** (Yasin's post-phase
   requirement), with two new controls: a session-local
   pause/resume button on the ticker itself, and a
   persistent reduced-motion setting in Settings that
   renders the ticker as static text (no marquee at all).
   Pause is deliberately session-local (leaving the feed
   resets to playing, matching the session-only filter
   behaviour); reduced motion is the persistent control.
3. **Bottom bar = five**: feed, warroom, market, bookmarks,
   settings. Bundles, Nightly, Analysis, Method move to a
   sections hub. Count stays five — verified, not asserted
   (verifier counts the `TABS` list).
4. **Bottom-bar label size = 12sp, not 14sp.** The plan's
   type scale says tab labels 14sp, but its navigation rule
   says bottom-bar labels "must fit on one line at 12sp on
   a 360dp-wide phone" — and 14sp is what wrapped labels
   on the eight-item bar (plan §1 M3). The 12sp rule
   governs the bar; 14sp stays the tab-label token for
   use elsewhere. `maxLines = 1` is set on bar labels.
   Caveat: `BottomBar` has no app-fontScale parameter, so
   the 12sp token is used as-is (it still scales with the
   system font size, as all sp values do); the 0.85×/1.3×
   app-font-scale fit check belongs to the P5 device gate.
5. **Type = Vazirmatn throughout (OFL, already bundled).**
   No new font asset, no Blacksword, no other
   publication's typeface, name, logo, or assets anywhere.
   Patterns only, per the plan.
6. **Sections hub entry lives in Settings, not in the bar
   and not on the feed.** Adding a sixth bar item would
   break the five-count; adding chrome to the feed would
   touch the A1 surface P2 must not touch. P3 may surface
   the hub from the feed's empty-state section index (plan
   §2), which is feed work and belongs there.
7. **Ticker empty state is visually distinct from a frozen
   ticker**: empty uses a darker red (`#8D1B1B`) strip;
   a paused ticker keeps the normal red strip with the
   resume icon visible. An empty ticker must not look
   like a broken one (plan §1 M2).

## 3. What was built

### (a) Token layer — one coherent layer, new file
- NEW `app/src/main/java/com/ecojournal/app/ui/theme/DesignTokens.kt`
  (185 lines): `EcoDesignTokens` with `Spacing` (8dp grid,
  16dp page margin, 24dp block gap, 8dp element gap),
  `Type` (masthead 32 bold; hero 26/34; row 18/26; hero
  summary 16/24; row summary 15/22; reader 28 at 1.35× =
  38 line, body 18 at 1.75× = 32 line, calm 2.0× = 36 line;
  meta 12; tab 14; bottom-bar 12; sync row 12; section
  header 15 bold; ticker 13), `LineHeight`, `Palette`
  (hex values copied verbatim from `Theme.kt` — no second
  palette; ticker red `#C62828` kept, empty red `#8D1B1B`
  added), `Rules` (double rule 2dp + 2dp gap + 1dp),
  `Motion` (pager 300 ms, ticker velocity 40 dp), and
  `StateLanguage` (gated line "این بخش فعلاً متوقف است",
  max one next step).
- Pure-Kotlin WCAG contrast helpers in the same file:
  `contrastRatio()`, `meetsBodyContrast()` (≥ 4.5:1),
  `meetsLargeContrast()` (≥ 3:1), so Phase 5 can measure
  the same pairs on device screenshots against the lab
  values in §4.

### (b) Masthead + ticker
- `app/src/main/java/com/ecojournal/app/ui/components/Masthead.kt`
  (rewritten, 219 lines):
  - `:82` `MastheadWordmark` — 32sp bold navy Vazirmatn,
    centred, `heading()` semantics, token spacing.
  - `:105` `WarRoomTicker` — new `reducedMotion` param
    (`:109`); `:122` `animating = topics.isNotEmpty() &&
    !reducedMotion && !paused`; pause/resume `IconButton`
    (`:177`, shown only when motion is on); static text
    (no marquee) when paused or reduced-motion; empty
    state on the darker red strip; TalkBack still hears
    the topics once via `contentDescription`, never the
    moving text alone.
  - `:195` `Masthead()` — wordmark + ticker + double
    rule, in that order, as one unit; passes
    `reducedMotion` through.
- `app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt`
  — `NewspaperDoubleRule` now uses
  `EcoDesignTokens.Rules` thicknesses/gap instead of
  literals (same 2+2+1 geometry, now token-owned).

### (c) Bottom bar (five) + sections hub
- `app/src/main/java/com/ecojournal/app/MainActivity.kt`:
  - `:97–102` `TABS` reduced to five (feed, warroom,
    market, bookmarks, settings); bundles/nightly/analysis
    tab entries and their now-unused icons removed. The
    routes themselves still exist — only the bar changed.
  - `:543` new `composable("sections")` hub route.
    "sections" is NOT in the `hideBar` set, so the bar
    stays visible on the hub and no tab shows selected
    there; the hub never strands the reader.
  - `:327` feed receives `reducedMotion =
    settings.reducedMotion`; `:561` settings receives
    `onSections = { nav.navigate("sections") }`.
  - `:616` bar label uses
    `EcoDesignTokens.Type.BottomBarLabel` (12sp) with
    `maxLines = 1`.
- NEW `app/src/main/java/com/ecojournal/app/ui/screens/SectionsHubScreen.kt`
  (88 lines): hub header + description + four buttons
  (bundles, nightly, analysis, method) reusing the
  existing tab/method strings. Gated sections (voices,
  special issues) are deliberately NOT hub destinations;
  they stay reachable only via their gated placeholders
  in Settings, exactly as before. No new content, no gate
  behaviour change.
- `app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt`:
  `:103` new `onSections` param; `:200` reduced-motion
  toggle row + description; `:577` sections-hub button
  after the gated-section buttons.

### (d) Reduced-motion setting (DataStore key only)
- `app/src/main/java/com/ecojournal/app/data/SettingsStore.kt`:
  `:59` key `reduced_motion`, `:101` field (default
  false), `:166` read, `:271` `setReducedMotion()`.
  This is a DataStore preference key, NOT a Room schema
  change — no migration, no destructive clear.
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt`:
  `:149` new `reducedMotion` param (default false, so
  unwired callers are unchanged), `:380` passed to
  `Masthead()`. Feed branching, sync, and queries are
  untouched — the parameter is a straight pass-through.

### (e) Strings (fixed language, no duplicates)
- `app/src/main/res/values/strings.xml:454–463` — 10 new
  strings: `ticker_pause`, `ticker_resume`,
  `ticker_motion_toggle` + `ticker_motion_desc`,
  `sections_hub_title` + `sections_hub_desc`, and the
  fixed state language `v5_state_empty_no_sync`,
  `v5_state_section_failed` (2 args: section, code),
  `v5_state_offline` (1 arg: code),
  `v5_state_gated` ("این بخش فعلاً متوقف است").
  Total strings file: 430 names, all unique
  (verifier-checked). The `v5_state_*` strings are the
  DESIGN-SYSTEM language definition; wiring them into
  feed/reader states is Phase 3 work (§7), not claimed
  here. Existing strings are untouched.

### (f) Lab verifier
- NEW `edge/verify_v5p2.py` — static checks only:
  token values, palette match against `Theme.kt`,
  masthead token/bold/Vazirmatn + script-not-masthead,
  ticker pause + reduced-motion threading end to end
  (store → activity → feed → masthead), bottom-bar
  count = 5 with the exact five routes, hub four
  destinations + no gated destinations + bar visible
  on hub, strings parse + unique + arg counts, all 11
  contrast pairs recomputed from hex, version
  untouched, no destructive DB calls, no false A1/G1
  claims. Result this run: **ALL V5 P2 CHECKS PASSED
  (103 checks, 0 failures).** The P1 verifier was also
  re-run: **ALL V5 P1 CHECKS PASSED** (no P1 regression).
  Caveat: the verifier does NOT compile anything — see §5.

## 4. Contrast notes (lab-computed from hex, WCAG formula)

Every body pair passes ≥ 4.5:1 in the lab; the large-text
target (≥ 3:1) is therefore also met for every pair. The
weakest pair is the ticker (13sp white on `#C62828`,
5.62:1) — it passes the body target despite being small
text, so no ticker colour change is needed.

| Pair | Ratio |
|---|---|
| Ink `#121212` on light paper | 18.73:1 |
| Navy `#0F2A44` on light paper | 14.63:1 |
| Grey byline `#5A5A5A` on light paper | 6.90:1 |
| White on ticker red `#C62828` | 5.62:1 |
| White on ticker empty red `#8D1B1B` | 9.11:1 |
| Ink `#F2F2F2` on dark paper `#121212` | 16.73:1 |
| Navy `#9DB8D6` on dark paper | 9.15:1 |
| Sepia ink `#2B2118` on `#FBF3DF` | 14.24:1 |
| Sepia primary `#5C4321` on `#FBF3DF` | 8.33:1 |
| Sepia grey `#6B5D4A` on `#FBF3DF` | 5.77:1 |
| Dark grey `#B0B0B0` on dark surface `#2A2A2A` | 6.62:1 |

**Honest limit:** these are lab ratios from hex constants,
not device measurements. Gate G2 (device screenshots at
0.85×/1.0×/1.3× with measured contrast) is still open and
belongs to Phase 5 evidence, not to this report. The
yellow-label contrast fix from v4 is untouched here.

## 5. Caveats and limits (read before relying on this phase)

- **No build was run** (task order: no build, no push, no
  publish). Kotlin correctness was checked by reading,
  not by compiling. Real risks carried to the P6 cloud
  build: the `Modifier.then(if … basicMarquee …)` branch
  in `WarRoomTicker`, the `Icons.Filled.Pause/PlayArrow`
  imports, and the `SectionsHubScreen` button loop
  destructuring. If the build fails, these are the first
  suspects, not the token values.
- The verifier is static only, and two of its checks are
  coarse by design: the "hub does not add gated
  destinations" check keys on the absence of a
  `VoicesScreen` navigation (the hub file mentions voices
  only in a comment explaining the exclusion), and the
  "no new font asset" check keys on the absence of a
  non-Vazirmatn `R.font` reference. Both were confirmed
  by reading the hub file, not just by the verifier.
- Ticker pause is session-local by decision (§2.2); a
  reader who wants motion off permanently uses the
  Settings reduced-motion toggle. If Yasin wants pause
  itself persisted, that is a one-key SettingsStore
  change in P3, not a redesign.
- The sections hub is reachable from Settings only in
  this phase (§2.6). Discoverability from the feed is a
  P3 item (empty-state section index), not a P2 gap to
  be patched by adding bar items.

## 6. Files changed, commit, and hard-limit compliance

Changed (lab commit only, NO push):
- `app/src/main/java/com/ecojournal/app/ui/theme/DesignTokens.kt`
  (NEW, 185 lines)
- `app/src/main/java/com/ecojournal/app/ui/components/Masthead.kt`
  (rewritten: wordmark, ticker controls, token spacing)
- `app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt`
  (double rule now token-owned; geometry unchanged)
- `app/src/main/java/com/ecojournal/app/ui/screens/SectionsHubScreen.kt`
  (NEW, 88 lines)
- `app/src/main/java/com/ecojournal/app/MainActivity.kt`
  (TABS 8 → 5, hub route, reduced-motion + hub wiring,
  12sp bar labels)
- `app/src/main/java/com/ecojournal/app/data/SettingsStore.kt`
  (DataStore `reduced_motion` key/field/setter only)
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt`
  (pass-through `reducedMotion` param only)
- `app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt`
  (reduced-motion toggle, hub button)
- `app/src/main/res/values/strings.xml` (+10 strings,
  no duplicates)
- NEW `edge/verify_v5p2.py` (103 checks, all pass)
- NEW `docs/v5/v5-p2-report.md` (this file)

NOT touched (pre-existing dirty files left alone, not
committed): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `docs/v5/v5-program-plan.md`, and all
other pre-existing untracked files.

Hard limits honoured: no cron changes, no live-channel
changes or posts, no special-issues/voices publication or
gate changes (hub explicitly excludes them), no FCM/Play,
no Bale work, no Room schema change (DataStore key only,
no migration needed), no night-law/bar/gate/cadence
behaviour changes (red-alert items stay inside War Room
with bar + badge; no parallel service, no separate tab),
no versionName/versionCode change (still 4.0.1 / 5),
no push, no build, no publish, no live change of any kind.

## 7. What Phase 3 must do next (vs what stays for P4/P5)

**Phase 3 (feed + reader rebuild) MUST:**
1. FIRST run the P1 §4 device-reproduction protocol on
   the instrumented build and record the G1 verdict from
   the P1 §3 decision table. P2 changes nothing about
   this obligation — G1 is still open (§1).
2. Adopt `EcoDesignTokens` on the feed and reader: hero
   26/34 + ruled rows 18/26 + summaries 16/24 / 15/22,
   reader 28/38 + body 18/32 (calm 36), section headers
   15 bold, 8dp grid, 16dp margins, 24dp block gaps,
   token double/single rules. The reader's existing
   scale already matches the token values — P3's job is
   to replace its literals with the tokens, not to
   resize it.
3. Wire the `v5_state_*` strings into every feed/reader
   empty/error/offline state (what happened + code +
   exactly one next step; two-line sync row when it
   carries a failure, never ellipsizing an error code),
   add the feed digest line (failed sections + worst
   code + Diagnostics), the static section index on the
   empty state (where the hub can also be surfaced),
   the body-empty reader state, and filter/search honest
   empties — all per plan §2.
4. Re-verify the bottom bar on device at 0.85×/1.0×/1.3×
   app font scales (labels on one line) as part of G3
   evidence; the lab token alone does not pass G2.

**Stays for Phase 4 (NOT P3):** status-aware
bundles/radar sync (nightly guard mirrored),
per-section success clocks, global clock over REQUIRED
sections only, edge contract test, Market visible
seed-date line, Bookmarks orphan reasons. P2's hub
routes to the existing Bundles/Nightly/Analysis
screens unchanged — their rebuilds are P4's, and the
Bundles `http_404` poison (A2) is still live until then.

**Stays for Phase 5 (NOT P3):** TalkBack walkthrough
PERFORMED on a phone with walk log + recording, font
extremes, contrast MEASURED from device screenshots
(G2), motion-control verification (ticker pause +
reduced motion on device), and the per-section
empty/error/offline matrix re-run on the final build.

**P6 build gate (blocking for any device claim):**
compile this tree, verify the APK's
`BuildConfig.GIT_COMMIT` equals the P2 commit, and
verify install-over behaviour before any phone run —
P2, like P1, was not built by task order.
