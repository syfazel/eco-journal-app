# Phase 7 report — Polish, accessibility, full pilot checklist (LAB ONLY)

- Base: Phase 6 commit `ed2f209`. Phase 7 is one clean pass on top of it.
  The cancelled earlier Phase 7 attempt produced no result and no code;
  this pass treats it as never started.
- Scope: lab audit of every Phase 1–6 surface, lab-fixable polish only,
  the TalkBack walkthrough script as a PHONE checklist, the E1–E8
  re-verification pack, and consolidated v4 phone-checklist inputs.
- Hard limits kept: lab only, local commit only, no push, no build,
  no APK distribution, no live change, no freeze change, no test-channel
  creation, no voices/special-issues publication.
- War-room/energy bar rule (all phases): RED for typed/war-room items,
  YELLOW for energy-domain items, red wins over yellow, always with a
  text label, never a separate tab or parallel service. Aggregation rows
  (editions, bundles) get NO item bar — they mix categories — while
  last-night leftovers get RED because they ARE typed items.
- Delegated choices honoured: OFL lookalike font is a V4-H item (recorded
  in the v4 plan, NOT implemented here); bubble/spread thresholds OFF;
  no FCM; no Play; voices and special issues stay gated placeholders.

## 1. Audits (lab, code inspection only — never a phone pass)

Three read-only audits were run before any fix (working notes in /tmp,
not deliverables):

- UI/a11y audit — themes, clickable targets, semantics, scroll safety.
- State-matrix audit — empty/error/offline/retry per section.
- Strings/contrast audit — duplicates, dead strings, Latin digits,
  hard-coded UI text, hard-coded colour contrast.

### 1.1 Findings that became fixes

| # | Finding | Fix (file) |
|---|---------|------------|
| A1 | Yellow-bar label on the themed surface: contrast 1.78–1.97 (fails AA) | Bar label now sits on a filled chip in the bar colour with a contrasting text (black on yellow, white on red) — `ChannelCard.kt` |
| A2 | WATCH badge white-on-`#E65100`: 3.79 (fails AA) | WATCH and crisis L3 chips now use black text — `ChannelCard.kt` |
| A3 | Only 6 `contentDescription`s in the whole UI layer; no heading semantics anywhere | Format badge and crisis chips carry Persian descriptions; market title is a heading; chart announces itself — `ChannelCard.kt`, `MarketScreen.kt` |
| A4 | Clickable rows ~28–35dp with no role/label (Nightly, War Room, Bundles, Bookmarks, ChannelReader, Analysis) | All rows: `role = Role.Button` (or `Button` for actions), `onClickLabel` with a Persian "open …" description where the row opens something, `padding(vertical = 12.dp)` — `NightlyScreen.kt`, `WarRoomScreen.kt`, `BundlesScreen.kt`, `BookmarksScreen.kt`, `ChannelReaderScreen.kt`, `AnalysisScreen.kt` |
| A5 | Settings switches without associated labels; font slider without description; theme labels hard-coded | Switches merged with their labels via `mergeDescendants` + `contentDescription`; slider carries `font_size_desc`; theme names from strings — `SettingsScreen.kt` |
| A6 | Market chart invisible to TalkBack; prev/next labels hard-coded | Chart announces `market_chart_desc`; prev/next from strings; the selected-snapshot card (plain text) + prev/next remain the accessible representation — `MarketScreen.kt` |
| A7 | Analysis source links were dead text | Links now open via `LocalUriHandler` to the news source itself, never the blog — `AnalysisScreen.kt` |
| A8 | Latin digits leaked into a notification count | `$count` converted with `toPersianDigits()` — `Notifications.kt:374` |
| A9 | 8 tab labels hard-coded in `MainActivity.kt:89-104`; theme labels, market prev/next, market labels in three variants hard-coded | `Tab` now carries `labelRes`; all 8 tabs, theme labels and market prev/next from strings — `MainActivity.kt`, `SettingsScreen.kt`, `MarketScreen.kt` |
| A10 | Gated placeholder screens (Voices, Special Issues) did not scroll — could clip at 1.3x font | Both now `verticalScroll(rememberScrollState())`, same pattern as MethodScreen — `GatedSectionsScreen.kt` |
| A11 | War Room and Bundles error lines leaked raw section keys (`radar`, `channel_typed`, `bundles`) into reader copy | Error lines now use the Persian Diagnostics section names — `WarRoomScreen.kt`, `BundlesScreen.kt` |
| A12 | Market tab had NO failed-sync surface (failures visible only in Diagnostics) and the refresh button's result was discarded | Market route loads the market section state after every refresh (and on entry) and passes it to the tab, which renders its own failed line — `MainActivity.kt`, `MarketScreen.kt` |
| A13 | Analysis had NO state surface: stale items rendered silently after a failed nightly sync | Analysis loads the nightly section state on entry and renders a failed line under the title (analysis travels in the nightly section since Phase 5) — `AnalysisScreen.kt` |
| A14 | `stringResource` inside `Modifier.semantics {}` blocks (my first draft) — that lambda is not a composable scope and would not compile | All five spots precompute the string in composable scope and hand the plain string to semantics; sweep confirms zero `stringResource` calls remain inside any semantics block — `ChannelCard.kt`, `WarRoomScreen.kt`, `MarketScreen.kt` |

### 1.2 Findings recorded, NOT fixed in code (phone-gated or out of scope)

- P7-R1 (OPEN — phone): TalkBack walkthrough, 0.85x/1.3x font extremes,
  measured contrast, and the full state matrix can only be passed on
  Yasin's phone. The script is `docs/big-update/evidence/phase7/talkback-walkthrough.md`.
  Code inspection and simulation are never a pass for these.
- P7-R2 (OPEN — phone): E1–E8 re-verification (see §4); E7 stays blocked
  on the test channel (Yasin's hand only); E8's phone half (install over
  the previous build without uninstall) is unproven.
- P7-R3 (CARRIED): a missing manifest section renders UNKNOWN and does
  NOT set `anyIncomplete`, so the global "up to date" line can show while
  a section is absent. Fixing the sync-state semantics is a Phase 1
  engine change, not Phase 7 polish — recorded here, not silently fixed.
- P7-R4 (CARRIED): `morning_bundle_empty` is a dead string; the copy
  "morning bundle not yet published" / "arrives at 08:00" does not exist
  as a rendered empty state. Wording it is an editorial decision — left
  for the v4 phone-checklist pass, not invented here.
- P7-R5 (CARRIED): the war-room section carries no age label, and an
  expired urgent pin (>6h) stays at the end of the rail without a label
  in the rail itself. The expired tag exists on the card (`expiredTag`)
  and is wired on Feed urgent-rail cards; the rail-order/age copy is a
  phone-checklist item, not a lab guess.
- P7-R6 (CARRIED — data, not UI text): Feed's "همه" section label is a
  data label built in code (`Section(null, "همه")`), not a `Text`
  literal; converting it to a resource would change section identity
  handling and is deferred, not forced.
- P7-R7 (CARRIED): 13 strings remain unreferenced (incl. the dead
  `morning_bundle_empty` above) and 19 value-duplicate groups remain.
  None is reader-facing; deleting strings is a cleanup pass, not polish,
  and was not done to avoid breaking any external reference.
- V4-H/I items explicitly NOT in Phase 7: OFL lookalike font, CNN ticker,
  sync-section compaction, helper modules (H1 reading ruler + calm mode,
  H2 story arc), remote in-app updating. See the v4 program plan.

## 2. Verification (lab)

- Brace/paren balance (stateful string/comment/char stripping): OK on
  all 14 touched Kotlin files.
- `strings.xml` parses: 368 strings, 0 duplicate names, 0 unresolvable
  `R.string.*` references across the whole tree.
- Semantics sweep: zero `stringResource` calls inside any `.semantics {}`
  block (the A14 compile risk, fixed and re-verified).
- Fixture verifiers: Phase 2 ALL PASS; Phase 4 38/38; Phase 5 PASS;
  Phase 6 25/25 + file-hygiene PASS. Phase 3's verifier takes
  `--base/--merged` fixture args and touches no Phase 7 code — not run.
- NOT verified (by design): no cloud build was run (constraint), so
  compile is established by the checks above, not by a build. The first
  v4 cloud build is the compile gate; see §4, E8.

## 3. Files changed (14; local commit only, no push)

```
app/src/main/java/com/ecojournal/app/MainActivity.kt
app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt
app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/BookmarksScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/BundlesScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/ChannelReaderScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/GatedSectionsScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/MarketScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/NightlyScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/WarRoomScreen.kt
app/src/main/java/com/ecojournal/app/util/Notifications.kt
app/src/main/res/values/strings.xml
```

Deliberately NOT in this commit: pre-existing modified files
(`docs/vnext-plan.md`, `docs/big-update/new-requirements.md`,
`edge/out/revocations.json`) and all pre-existing untracked docs
(`docs/big-update/*`, `docs/v4/`, `docs/freeze-lift-order-draft.md`,
`docs/pilot-checklist-vnext.md`). New Phase 7 docs:
`docs/phase7-report.md` (this file),
`docs/big-update/evidence/phase7/talkback-walkthrough.md`,
`docs/big-update/evidence/phase7/e1-e8-reverification.md`,
`docs/big-update/evidence/phase7/v4-phone-checklist-inputs.md`.

## 4. What still needs the phone (nothing below is passed by this phase)

1. TalkBack walkthrough — script + log/capture, per
   `docs/big-update/evidence/phase7/talkback-walkthrough.md`.
2. Font extremes 0.85x and 1.3x on every screen (esp. gated placeholders,
   market chart card, settings slider row).
3. Measured contrast on the fixed chips (yellow/black, red/white,
   orange→black WATCH/L3) with a real device reading, not the lab ratio.
4. State matrix on-device: failed market sync line, failed nightly line
   on Analysis, failed war-room/bundles lines with Persian names,
   expired-pin tag on the urgent rail, offline cache retention.
5. E1–E8 re-verification pack —
   `docs/big-update/evidence/phase7/e1-e8-reverification.md`
   (E7 blocked on the test channel; E8 phone half unproven).
6. v4 consolidated phone-checklist inputs —
   `docs/big-update/evidence/phase7/v4-phone-checklist-inputs.md`.

## 5. Stop point

Phase 7 ends here. Phase 8 (freeze-lift rehearsal, dry-run only) is NOT
started in this run, per the task. Freeze unchanged; no live change;
no publication of voices or special issues.
