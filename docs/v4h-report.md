# v4-H — Post-phase experience: implementation report (LAB ONLY)

- Base: Phase 8 commit `8ede333`. Order inside v4-H per the v4
  plan: (1) sync-compact frame, (2) script wordmark + CNN ticker
  laid out together, (3) improved tabs, (4) H1 + H2, (5) v4 fix
  list throughout. Local commit only, no push, no build, no APK
  distribution, NO live edge publish (publish_edge.py --real was
  NOT run), no live change, no freeze change, no test-channel
  creation. v4-I remote update is NOT started in this run.
- Note on the parent's other track: the live edge v3 publish
  (commit 65c3ab8, separate worktree) and the test channel Yasin
  created are recorded in MEMORY, not in this commit; this run
  touches neither. Local main is therefore ahead of origin/main
  on phase commits and behind on the edge commit — the v4
  publish step must rebase, not force-push.

## 1. What was built, in order

### (1) V12 sync-compact frame — `ui/components/SyncCompact.kt`
- Feed's old sync block (sync button + status line + last-sync
  line + offline line + spacers) is replaced by ONE line:
  `CompactSyncRow` — icon sync button (48dp), one-line status,
  last-sync time, retry only on failure, details button to
  Diagnostics. Same runner (`runSync`), same fixed-string
  messages, same cadence. Nothing about sync behaviour changed.
- Nothing material is hidden: failures still show in red with
  retry; the "never succeeded" line is preserved (a blank state
  must never read as "up to date"); full per-section state,
  force-refresh, and error codes stay in Diagnostics, one tap
  away via `onOpenDiagnostics` (wired in MainActivity to the
  `diagnostics` route). A compact row that swallowed failures
  would be a regression, not a fix.

### (2) V15 wordmark + V11 ticker, together — `ui/components/Masthead.kt`
- `Masthead` replaces the old 32sp bold masthead on the feed:
  small script "ECO JOURNAL" (22sp) + the red ticker bar
  directly under the app name + the double rule, as one unit.
- Ticker (`WarRoomTicker`): CNN-style red moving bar via
  `basicMarquee` (infinite, 40dp/s). Topics come ONLY from
  `TickerTopics.topics(instantItems)`: instant typed items,
  newest first, deduped by story_id, capped at 10. Held,
  feed-only, and retracted items can never appear (a held night
  item in the ticker would break Law 15). Empty state is an
  honest static line, never a recycled headline. TalkBack hears
  the topics once as a single announcement, not the animation.
- FONT HONESTY (the one deviation to record): NO Blacksword is
  shipped — its licence is personal-use-only and was never
  cleared for app use — and no bundled OFL script font file
  exists in this repo, because no font binary could be fetched
  through the allowed tools (web access via exec is prohibited
  and the VM has no script font installed). The wordmark
  renders in the platform cursive family (`FontFamily.Cursive`)
  as the script lookalike, via the single `ScriptFontFamily`
  alias: a bundled OFL script font can replace that alias
  without touching any call site. The report does NOT claim a
  named OFL font is shipped; the bundled-font asset is owed
  before the wordmark is called final on the phone checklist.

### (3) V16 improved tabs — FeedScreen
- Baseline defect: tabs were tracked by pager INDEX, so a new
  category inserted before the current tab shifted every index
  and the pager jumped to a different section on sync. Fix: the
  selected section KEY is remembered on every page change and
  restored whenever the section list changes. Swipe/tap stay in
  sync through the same pagerState; per-tab scroll states are
  unchanged. No tab was added, removed, or reordered: War Room
  stays a section, red alerts stay inside the feed (bar rule).

### (4) H1 Reading Ruler + Calm Mode, H2 Story Arc Strip
- H1 ruler (`ReaderHelpers.ReadingRuler`): a translucent guide
  band overlaid on the reader (Box over the scrolling column, so
  text never reflows), draggable vertically, clamped 0–600dp.
  It marks a line only; it hides nothing and records nothing.
  Toggle in Settings; hidden in calm mode.
- H1 calm mode (reader flag): taller line height (2.0x vs
  1.75x), no ruler overlay, no arc strip. Attribution is never
  removed: the byline (verbatim source name) always stays, and
  points / why-it-matters stay (they are content, not chrome).
- H2 arc strip (`StoryArcStrip`, pure logic in
  `data/TickerTopics.kt`): the article + typed items sharing
  its story_id, oldest first; typed nodes open their channel
  reader (wired through MainActivity's article route); the
  article node is the current page, not a button. Retracted
  versions get no node; a single-version story gets no strip.
  No new data, no scores, no predictions.

### (5) V19 fix list — the ArticleEntity formatType RED-path gap
- THE GAP: `ArticleEntity` carries no formatType, and the feed
  card hard-coded the «انرژی» label for ANY bar. So the RED
  path could never reach a feed card or a reader header, and a
  RED bar rendered there would have been mislabelled «انرژی».
- THE FIX: `DomainBars.forArticleWithTyped(article, formatType)`
  (RED wins over YELLOW, exactly as Phase 6's barFor) +
  `labelFor` (RED names the format, YELLOW names «انرژی»).
  Resolution: feed maps appKey → story_id (new `joinKeysFlow`)
  → newest non-retracted typed format from visible typed
  items; the reader resolves the same via `joinKeyByAppKey` +
  the existing `channelByStory` query (no new DAO query needed
  there). `ArticleCard` takes `typedFormatType`; both hero and
  row render the resolved label, and render no strip when the
  bar is NONE or the label is null.
- Bar rule kept everywhere: RED typed, YELLOW energy, label +
  bar (never colour alone), never a separate tab/service;
  aggregation rows (editions, bundles) stay bar-less — no
  aggregation screen was touched in this run.

## 2. Constraints honoured
- Thresholds OFF: no threshold value was added, changed, or
  defaulted anywhere in this run.
- No FCM, no Play, no release signing. Voices / special issues
  screens untouched (git status confirms); nothing published,
  nothing gated opened.
- No sync, notification, night-law, bar, badge, or bundle
  behaviour changed — V12/H1/H2 are presentation only.
- No live edge publish, no `publish_edge.py --real`, no cron /
  bot / channel / freeze change.

## 3. Verification (lab, no build by constraint)
- `edge/verify_v4h.py`: ALL PASS — brace/paren balance on all
  12 touched Kotlin files; strings.xml parses (386 strings, 0
  duplicates, all 10 new v4-H strings present); semantics sweep
  (brace-matched: zero `stringResource` calls inside any
  `.semantics {}` block — an earlier crude sweep flagged the
  masthead falsely because its 200-char window bled into the
  following Text; the brace-matched sweep clears it);
  bar-rule + gating checks (ticker rule, RED path, hardcoded
  «انرژی» label gone, gated screens untouched, no publish_edge
  touch).
- NOT verified (by design): no cloud build was run, so compile
  is established by the checks above, not by a build. The v4
  cloud build is the compile gate.

## 4. Files changed (local commit only)
```
app/src/main/java/com/ecojournal/app/data/TickerTopics.kt (new)
app/src/main/java/com/ecojournal/app/data/Phase2Domain.kt
app/src/main/java/com/ecojournal/app/data/AppDatabase.kt (joinKeysFlow only; no schema change, no migration)
app/src/main/java/com/ecojournal/app/data/SettingsStore.kt (ruler/calm/ticker keys)
app/src/main/java/com/ecojournal/app/ui/components/Masthead.kt (new)
app/src/main/java/com/ecojournal/app/ui/components/SyncCompact.kt (new)
app/src/main/java/com/ecojournal/app/ui/components/ReaderHelpers.kt (new)
app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt
app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt
app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt
app/src/main/java/com/ecojournal/app/MainActivity.kt
app/src/main/res/values/strings.xml (+18 strings)
edge/verify_v4h.py (new)
docs/v4h-report.md (this file)
```
Deliberately NOT in this commit: pre-existing modified files
(`docs/vnext-plan.md`, `docs/big-update/new-requirements.md`,
`edge/out/revocations.json`) and all pre-existing untracked docs.

## 5. What still needs the phone
1. Masthead on a real phone: script wordmark legibility at
   22sp, ticker smoothness/legibility (the "highest quality"
   claim), ticker on/off toggle, empty-state line.
2. Compact sync row: reading view regained on the feed;
   failure line + retry; details button to Diagnostics.
3. Key-based tabs: insert a new category (fixture) and confirm
   the pager does not jump sections.
4. Ruler drag + calm mode line height + arc strip navigation
   (typed node → channel reader; no strip on single-version
   stories; no node for a retracted version).
5. RED bar on a feed card + reader header for an article whose
   story has a typed item, with the format's own label (needs
   a real typed item — fixture until the bridge carries one).
6. Bundled OFL script font asset (see §1(2)): replace
   `ScriptFontFamily` and re-check the wordmark.
7. v4 cloud build (compile gate) + E1–E8 re-verification on the
   final v4 build. v4-I remote update is NOT started here.

## 6. Stop point
v4-H ends here. v4-I remote update is NOT started in this run,
per the task. Freeze unchanged; nothing published.
