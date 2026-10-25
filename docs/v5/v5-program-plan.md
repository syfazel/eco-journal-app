# ECO JOURNAL — Version 5 Program Plan (PHASE 0 ONLY)

Status: **PLAN ONLY — no code, no build, no push, no publish.**
This document is the Phase 0 output of Yasin's order of 2026-10-26
~01:27 Tehran: stop patching 4.0.x; for version 5, manage a DEEP
troubleshooting of UI/UX across ALL sections and challenges, planned
thoughtfully, resulting in an app much better, newer, and closer to
the New York Times app (patterns only — never NYT branding, name,
logo, or assets; the app name stays ECO JOURNAL throughout).

Authority and stop point: the 4.0.2 patch run was stopped before it
pushed anything. HEAD is still the 4.0.1 edge publish (`d827a6c`).
Nothing in this plan resumes 4.0.2. No v5 build starts until Yasin
reviews this plan. Special issues and voices stay GATED (nothing
publishes); Bale work stays PENDING (kept, not started, separate
program); no FCM, no Play.

Base facts (verified in this audit, not assumed):
- Live Pages edge serves manifest v3 with 249 articles; the app's
  `EdgeConfig.BASE_URL` (`EdgeRepository.kt:51`) is the same address.
- On Yasin's phone, 4.0.1 still fails: feed shows "no successful
  sync recorded yet"; refresh flashes content for ~1 second, then
  jumps back to the empty page ("the feed does not stay at all,
  nothing works at all"); the Bundles screen reports bundles update
  failed (`http_404`) with no bundles published.
- Version history: v4 = versionCode 4, 4.0.1 = versionCode 5 (live).
  v5 must use a code > 5. Decision below: **5.0.0, versionCode 6**.
- Already in the app: v4 masthead (script wordmark via the PLATFORM
  cursive alias — NO bundled OFL font asset exists yet), CNN-style
  red ticker, compact sync row, key-based tabs, reading ruler +
  calm mode, story arc strip, V4-I remote update (version.json +
  one-tap install via shared signing, cert `45220b15…`).
- Prior art this plan builds on (not reinvents):
  `docs/nyt-ui-spec.md` (newspaper front-page spec: 32sp bold navy
  masthead, double rule, hero 26sp / rows 18sp, reader 28sp /
  18sp at 1.75×, Vazirmatn, paper/ink/navy palette) and
  `docs/app-update-system-fix-plan.md` (the 4.0.1 fix plan).

---

## 1. Audit findings — every screen, every state

Severity: **P0** = blocks the app's core job (feed that stays,
sync that succeeds). **P1** = major UX break. **P2** = polish /
consistency. "Evidence" is file:line in the 4.0.1 tree unless
marked LIVE (checked against the live edge on 2026-10-26).

### A1 (P0, UNRESOLVED MECHANISM) — feed flashes, then vanishes

What the user sees: empty feed + "no successful sync recorded
yet"; on refresh, content appears for ~1 second, then the empty
page returns. On his latest message: "the feed does not stay at
all."

What static review PROVES and what it does NOT:
- There is **no article-deletion path in sync**. The only deletes
  in `EdgeRepository.kt` are nightly-edition deletes (`:1434`,
  `:1464`, `:1489–1490`) and `deleteAllFormulas` (market only).
  The articles section only upserts (`dao.upsertAll(fresh)`,
  tombstones for manifest-`retracted` items only). The live
  manifest carries no `retracted` article items (sampled items are
  `posted`/`approved`). So "sync deletes the feed" is DISPROVEN
  as a mechanism — do not "fix" it by adding deletion guards.
- There is a **single Room instance** on one file (`eco.db`,
  `DatabaseProvider.kt:23`, migrations 1–7, no destructive
  fallback), and one repository in `MainActivity.kt:114`. So
  "seed writes to one DB, sync to another" is DISPROVEN.
- The feed branch (`FeedScreen.kt:624–668`) is: seeding +
  empty → spinner; `all` empty AND typed empty → empty state;
  otherwise `FrontPageTabs`. `allFlow()` (`AppDatabase.kt:432`)
  excludes only `retracted` rows. Nothing in the branch removes
  rows. So the vanish is NOT explainable from the branch alone.

Ranked hypotheses (v5 Phase 1 must discriminate, not guess):
1. **H1 — the flash is not articles at all.** The 1-second
   "page" may be the sync-progress/spinner state, a tab pager
   mid-animation, or the seed completing in a composition that
   is then replaced (e.g. Activity recreation, invite-gate
   re-check, or update-check recomposition). Needs: a screen
   recording + a sync trace log (see §3, gate G1).
2. **H2 — wrong build on the phone.** The 4.0.1 empty state
   (`FeedScreen.kt:632–668`) shows an error-code line + retry +
   Diagnostics buttons. Screenshot 1 shows NEITHER an error line
   NOR retry/details buttons in the empty area, and its sync-row
   text matches the shared `sync_never_succeeded` string
   (`strings.xml:28`), which exists in both v4 and 4.0.1. The
   phone's build identity is therefore UNCONFIRMED from the
   screenshots. v5 gate G1 requires a build fingerprint (version
   + code on Diagnostics line 1) before any further diagnosis.
3. **H3 — process death / restart during the 249-file loop.**
   The articles fetch runs up to 240 s in chunks of 4
   (`EdgeRepository.kt:437–460`). If the OS kills the process
   mid-loop, the transaction never commits — but committed rows
   persist across restarts, so this alone cannot empty a shown
   feed. It can only explain "never completes". Low rank.
4. **H4 — seed/sync key mismatch producing duplicates, then a
   filter hiding them.** Seed entities derive `appKey` from the
   article JSON or `ArticleKeys.appKeyFor(id)`
   (`ArticleParsing.kt:158–161`); sync uses manifest `app_key`.
   A mismatch would DUPLICATE rows, not hide them — and the
   "همه" tab shows all non-retracted rows. Disproven as a
   vanish mechanism, but still a v5 reconciliation check
   (seed-key vs manifest-key report in Diagnostics).

Blunt statement: **A1 is not root-caused by this audit.** Any
plan that claims otherwise is guessing. v5 Phase 1's first gate
is reproducing A1 on a device build with logging (sync trace +
seed report + build fingerprint + screen recording). Until then,
no feed rebuild is declared "the fix" for A1.

### A2 (P0, PROVEN) — bundles/radar 404s poison every sync

Mechanism, step by step (all verified):
- Live manifest `bundles` section: count 0, `file: None`,
  `status: 'not_yet_published'`, blank hash. Same for `radar`.
  The live edge directory contains NO `bundles.json` and NO
  `radar.json` (confirmed from the `edge/out/` listing).
- `syncBundlesSection` (`EdgeRepository.kt:1133–1151`) does NOT
  check `status`: if the section exists, and the blank hash is
  never "up to date" (`DeltaPlanner.isUpToDate` refuses blank
  hashes, `UpdateEngine.kt`), it fetches
  `$base/${section.file ?: "bundles.json"}` → HTTP 404 →
  `SectionOutcome(FAILED, http_404)`. `syncRadarSection`
  (`:1199–1217`) does exactly the same with `radar.json`.
- Contrast: `syncNightlySection` (`:1289–1300`) DOES guard
  `not_yet_published`/`gated_frozen` and records
  NOT_YET_PUBLISHED with no fetch. Bundles/radar lack the guard
  the nightly section already proves works.
- Aggregation (`EdgeRepository.kt:887–897`, `:960–975`): any
  FAILED/PARTIAL section sets `anyIncomplete = true`; the whole
  sync returns `Partial`; and `last_sync_at` advances ONLY when
  `!anyIncomplete`. Therefore **with bundles + radar permanently
  FAILED, no full sync can ever record `last_sync_at`** — the
  feed's "no successful sync recorded yet" (`strings.xml:28`)
  is not a display bug, it is the honest readout of a sync
  that can never complete. The Bundles screen's own retry
  (`BundlesScreen.kt:110–133`) calls `syncOnly(BUNDLES)`, which
  re-runs the same 404 fetch — a dead-end retry.
- What it does NOT do: it does not delete caches ("a failed
  section never empties its cache" holds). The poison is in the
  global outcome + clock, not in the data.

Fix direction (Phase 4, §3): status-aware section sync for
bundles/radar (mirror the nightly guard); per-section success
clocks; a global clock defined over REQUIRED sections only
(articles, shorts, market, channel_typed/channel_refs) while
optional/unpublished sections degrade independently with a
reason; and an edge contract test — every manifest section
that is fetchable must have its file present on the edge, or
the manifest must mark it non-fetchable. The generator must
either emit `bundles.json`/`radar.json` (even empty) or stop
listing those sections as fetchable. Both sides, not one.

### Feed (all tabs) — P0/P1

- **F1 (P0, consequence of A2):** the sync row's "never
  succeeded" line is permanently true. Its full text
  (`strings.xml:28`: "…؛ فقط مقالات کش‌شده نمایش داده
  می‌شوند") is ellipsized to one 12sp line in `SyncCompact.kt`
  (`maxLines = 1`, `TextOverflow.Ellipsis`), so on the phone it
  reads as the truncated "…؛ فقط مقالات" seen in screenshot 1.
  The user cannot read the state the row claims to report.
- **F2 (P1):** the sync row is the ONLY feed-level sync surface;
  per-section truth lives only in Diagnostics (one tap away, but
  the user has to know to go there). A failed sync shows a
  red line + retry, but the retry re-runs the same poisoned
  full sync (A2) — motion without progress.
- **F3 (P1):** format filter chips (FeedScreen filter row) show
  ONLY typed cards when a format is selected; with zero typed
  items (fresh install, held night items) every filter reads as
  an empty feed ("feed_typed_empty") with no explanation that
  the filter, not the cache, is empty. Filter state is
  session-only and invisible in the sync row.
- **F4 (P2):** search is inline-only (300 ms debounce,
  `FeedScreen.kt` search effect), offline Room search, no
  history/suggestions screen. Adequate; not a v5 priority, but
  the search-results header must show counts honestly (it does)
  and the empty-results text must not be confusable with the
  feed-empty text (it is a different string — keep it so).
- **F5 (P2):** tab row is key-based (V16, `FeedScreen.kt`
  sections/pager effects) — the one navigation fix that works
  and must be kept. But section tabs are derived from cached
  categories only: on an empty cache there is exactly one tab
  ("همه"), so the tab row cannot teach the user what sections
  exist. v5 keeps data-driven tabs but adds a static section
  index (see §2) for the empty state.

### Article reader — P1/P2

- Scale is sound: headline 28sp × fontScale, body 18sp ×
  fontScale at 1.75× (calm 2.0×), hero card 26sp / summary 16sp,
  rows 18sp / 15sp (`ArticleScreen.kt:227–228`,
  `ArticleCard.kt:192–267`). This matches the newspaper spec
  and is KEPT, not redesigned.
- **R1 (P1):** the reader's failure modes are better than the
  feed's (not-found + retry with edge fallback,
  `ArticleScreen.kt:105–219`), but a cached article with an
  empty body still renders as a near-blank page with no "this
  article has no body in the cache" line. v5 adds a body-empty
  state with the same retry/Diagnostics path.
- **R2 (P2):** reading ruler (draggable overlay) + calm mode +
  story arc strip (`ReaderHelpers.kt`) are genuinely useful,
  but they are undiscoverable (Settings toggles only) and the
  arc strip appears only for multi-version stories — a reader
  who never finds them gets no benefit. v5 adds a one-line
  reader footer hint on first use, not more chrome.
- **R3 (P2):** no images exist in the data, so the reader is
  text-only by data, not by choice. Any v5 design that assumes
  a photo-led hero is designing for data that does not exist
  (see §2 non-fits).

### Channel reader, War Room — P1

- Channel reader has no empty/error greps of its own; it
  inherits the feed's data. If a typed item's body is missing,
  the reader must show the same body-empty state as R1 (v5
  shared component, not a second implementation).
- War Room (`WarRoomScreen.kt:207–247`) is the best section
  after Diagnostics: offline line, section error line with code
  + retry, SITREP and signal empties handled separately. Its
  gap is liveness: no live-blog auto-refresh, and the ticker
  (instant items only) is the only live surface. v5 keeps War
  Room's error discipline as the template for all sections,
  and does NOT promise live-blog behaviour the data cannot
  support (no live-blog items exist; SITREP every 2 h + instant
  rail + ticker are the honest live set).

### Market — P2

- States are genuinely distinguished (seeding / seed-done-empty
  / error + retry, `MarketScreen.kt:65–229`), and the seed's
  newest snapshot is dated 2026-10-24 — i.e. market data is
  STALE BY SEED, not by sync, and the screen says so only in a
  code comment, not on screen. v5 adds a visible "seed date"
  line on the Market screen when live sync has never updated
  it. Formulas-empty state exists (`:332`); thresholds stay OFF
  (Yasin's decision, unchanged).

### Bundles, Nightly, Analysis — P1/P2

- Bundles: error line + retry exist, but retry is the A2
  dead end (see above). Empty state is honest ("no bundle
  published yet"). Special issues show "stopped" — the CORRECT
  gated state, not a defect; v5 must not "fix" it into an
  error or a "coming soon" list.
- Nightly: count 0 on the edge, so the section is an empty
  shell until the generator ships editions. Its error lines
  exist (`NightlyScreen.kt:148+`); v5 must not redesign Nightly
  around content that does not exist yet — the Nightly rebuild
  is conditional on the generator's first real edition.
- Analysis: nightly error code shown, empty state exists
  (`AnalysisScreen.kt:77–131`). Voices/Analysis gating is
  correct in code (no names published); the gated screen
  (`GatedSectionsScreen.kt`) must be audited in Phase 1 to
  confirm it shows a gated reason and NEVER a "coming soon"
  name list — that check is a Phase 1 gate item, not an
  assumption.

### Bookmarks, Settings, Diagnostics, Invite gate

- Bookmarks: orphans handled (`BookmarksScreen.kt:90–178`),
  empty state exists. P2: orphan bundles are shown without
  explaining WHY they are orphaned (source retracted?) — one
  line of reason text in v5.
- Settings: update card (installed version, toggle, manual
  re-check, progress, install, dismiss, retry), notification
  preferences, ruler/calm/ticker toggles. P1 gap: NO build
  fingerprint line (version + code + signature state) on
  Settings or Diagnostics line 1 — the exact gap that left A1
  undiagnosable from screenshots. v5 Phase 1 adds it first.
- Diagnostics (`DiagnosticsScreen.kt`): the best screen in the
  app — per-section state/count/error, seed missing count +
  seed error (v4.0.1), last error, edge URL, per-section
  force-refresh. Its defect is distribution: it is the ONLY
  place sync truth lives. v5 puts a one-line digest of it on
  the feed (sections failed + worst code), with Diagnostics
  one tap away — the digest never replaces Diagnostics.
- Invite gate (`InviteScreen.kt`): fixed error, no hint about
  which part mismatched, loading state exists. Good; unchanged
  in v5 except that a re-lock (invite re-check flipping
  `inviteOk` to false mid-session) must explain itself instead
  of silently returning the user to the gate — v5 adds a
  "session ended, code required again" line for that path.

### Masthead, ticker, bottom bar — P1 (the visible "UI is broken")

- **M1 (P1):** the masthead is 22sp script via the PLATFORM
  cursive family (`Masthead.kt:55`) — not the 32sp bold navy
  masthead of `docs/nyt-ui-spec.md`, and not a bundled OFL
  font (the asset is still owed; Blacksword is personal-use
  only and is never to be shipped). On the phone screenshots
  it renders thin and large; legibility at 22sp script on
  small phones is UNPROVEN. v5 decision required (Yasin, §5).
- **M2 (P1):** the ticker (`basicMarquee`, 40 dp/s,
  `Masthead.kt:100–102`) runs infinitely with no pause on
  touch and no reduced-motion respect; its empty state is a
  static line ("no live topic right now") which is honest but
  visually identical to a broken ticker. v5: pause on touch +
  a motion toggle, and an empty state that is visually
  distinct from a frozen ticker.
- **M3 (P1):** the bottom bar has EIGHT items
  (`MainActivity.kt:93–108`: feed, warroom, bundles, nightly,
  analysis, market, bookmarks, settings). Both screenshots show
  the consequence: labels wrap and split across lines ("صفحهٔ
  اصلی", "ذخیره‌شده‌ها") and tap targets shrink. This alone
  makes the app LOOK broken even when it works. v5 reduces to
  five primary destinations + a sections hub (see §2).
- **M4 (P2):** the compact sync row (V12) fixed the "sync block
  eats the page" complaint, but at 12sp one-line ellipsized it
  now hides the state it exists to report (F1). v5 keeps the
  one-line row but gives it two lines when it carries a
  failure, and never ellipsizes an error code.

### Accessibility, motion, offline — P1

- Phase 7's TalkBack walkthrough exists only as a script
  (26+4+3 steps); it was NEVER performed on a phone, and
  font extremes (0.85×/1.3×) plus measured contrast were
  lab-only. The yellow-label contrast fix (black text on
  yellow/orange) is in code but unmeasured on device. v5
  Phase 5 makes device evidence the gate — a claim without a
  walk log + recording fails, as in the v4 plan.
- No motion rules exist anywhere (marquee, pager animation,
  list animation). v5 writes them (§2) before rebuilding.
- Offline-first (Room + seed + offline search) is the app's
  strongest property and is KEPT. Its hole is visibility:
  "seed empty" vs "seed broken" vs "sync failed" are
  indistinguishable on the feed today. v5's feed digest (§2)
  closes exactly that hole.

---

## 2. NYT reference patterns → ECO JOURNAL translations

Patterns only. No NYT name, logo, trademark, masthead, or
assets appear in the app, and nothing here copies NYT copy or
layout pixel-for-pixel. Sources: the newspaper front-page
tradition (masthead → hero → ruled rows → section tabs),
typographic hierarchy (most important = largest + highest
contrast), and rule-based section separation (double rules
between big topics, single rules between stories, light rules
within a story). The app's own prior spec
(`docs/nyt-ui-spec.md`) already translated most of this once;
v5 keeps what it got right and corrects where v4 drifted.

| NYT pattern | ECO JOURNAL translation (Persian RTL) |
|---|---|
| Masthead + double rule as the page's anchor | Restore the newspaper masthead as primary: 32sp bold Vazirmatn navy, centred, double rule (2dp + 2dp gap + 1dp) beneath — the `nyt-ui-spec` masthead v4-H replaced. The script wordmark becomes a secondary mark ONLY if a bundled OFL script font lands (Yasin decision, §5); until then it does not ship as the masthead. |
| Hero hierarchy: kicker → big headline → summary → byline | Kept and tightened: kicker 12sp bold navy (with «گزیده» prefix for shorts), hero headline 26sp/34sp, summary 16sp/24sp, byline 12sp grey (source • cached date). One hero per section page, then ruled rows. No hero without a summary — a hero with no summary falls back to row treatment. |
| Ruled text rows, no cards/shadows for following stories | Kept: following articles are text rows with a 1dp `outlineVariant` rule between them, max 3 lines headline (18sp/26sp) and summary (15sp/22sp). Cards/shadows are reserved for NOTHING in the feed — the feed is a newspaper page, not a card deck. |
| Section tabs, "Top Stories" first | "همه" (top stories) first, then data-driven category tabs, key-persistent (V16 kept). Addition: a static section index on the empty state so an empty cache still teaches the sections. |
| Reader: serif body, generous measure, clear byline block | Reader headline 28sp × fontScale at 1.35×, body 18sp × fontScale at 1.75× (calm 2.0×), summary bold when present, double rule under the byline block. Measure: full-width paragraphs in RTL with 16dp margins are KEPT (no multi-column on phones); line-length control comes from fontScale, not columns. |
| Live coverage flagged in red above the headline | Our red bar (typed items) + yellow bar (energy) + red ticker strip are the translation — label + bar, never colour alone, never a separate tab (Yasin's cross-phase rule, kept). The ticker stays UNDER the masthead (its current place) with pause + motion controls (§1 M2). |
| "We're having trouble loading" honesty | Every empty/error/offline state gets: what happened (plain Persian), the code, and exactly one next step (retry and/or Diagnostics). Never a spinner without a bound, never a blank page, never a fake "up to date". This is the single most NYT-like property to import: the paper never prints a blank front page and calls it news. |
| Section separation by rules + whitespace | Double rule between major blocks, single rule between stories, light rule within a story's aspects (bundles/arc). Proximity groups related items; hierarchy gets whitespace, not decoration. |

Where NYT patterns do NOT fit (stated, not forced):
- **Photo-led hero:** our data has no images. No image slots,
  no placeholder art, no stock photos. The hero is typographic.
- **Live blogs:** no live-blog items exist. SITREP (2 h),
  instant rail, and ticker are the honest live set; v5 does not
  invent a live-blog screen.
- **Paywall / account patterns:** our gate is the invite code
  (hash-only, M7). No account screens, no paywall language.
- **NYT typefaces (Cheltenham/Karnak/Franklin):** proprietary.
  We use Vazirmatn (OFL) throughout; the script wordmark needs
  an OFL lookalike, still owed — never Blacksword (personal-use
  only), never an NYT face.
- **Dense desktop front page:** phone-first. One column, one
  hero, ruled rows. The desktop grid does not transfer.

### Design system decisions (v5, binding unless Yasin overrides)

- **Type scale (sp, × fontScale where noted):** masthead 32
  bold navy; hero headline 26/34; row headline 18/26 (max 3
  lines); summary 16/24 hero, 15/22 rows; reader headline 28
  (× fontScale) at 1.35×; reader body 18 (× fontScale) at
  1.75×, calm 2.0×; meta/byline/caption 12; tab labels 14;
  sync row 12 (two lines when carrying a failure); section
  headers 15 bold.
- **Spacing:** 8dp grid; 16dp page margins; 24dp between major
  blocks; 8dp between kicker/headline/summary; 1dp rules
  (`outlineVariant`), double rule 2dp + 2dp gap + 1dp.
- **Colour (kept palette, measured in Phase 5):** light
  paper-white, ink `#121212`, navy primary `#0F2A44`, grey
  bylines `#5A5A5A`, rules `#E8E8E8`/`#D8D8D8`; sepia paper
  `#FBF3DF`, ink `#2B2118`, primary `#5C4321`; dark paper
  `#121212`/surface `#1E1E1E`, ink `#F2F2F2`, primary
  `#9DB8D6`. Contrast targets: body ≥ 4.5:1, large text
  ≥ 3:1, measured on device screenshots — lab ratios do not
  pass this gate.
- **Motion:** pager 300 ms ease; ticker pauses on touch and
  stops entirely under a motion toggle; no infinite motion
  without a user control; list changes animate only on
  insert/remove, never on sync refresh (a refresh must not
  make the feed visibly jump — this is a direct answer to
  A1's "page jumps" complaint, whatever its mechanism).
- **Empty/error/offline language (Persian, fixed strings):**
  every state names what happened + the code + one next step.
  Examples: feed empty with no sync ever → "هنوز مقاله‌ای در
  حافظه نیست و هیچ همگام‌سازی موفقی ثبت نشده است — [تلاش
  دوباره] [تشخیص]"; section failed → "بخش X به‌روز نشد
  (کد) — [تلاش دوباره]"; gated → "این بخش فعلاً متوقف است"
  (no "coming soon", no names for voices). Raw codes are
  never shown alone; friendly text is never shown without a
  code on the Diagnostics path.
- **Navigation:** bottom bar = 5 primary: صفحهٔ اصلی، اتاق
  جنگ، بازار، ذخیره‌شده‌ها، تنظیمات. Bundles, Nightly,
  Analysis, Method move to a "بخش‌ها" hub (one more tap,
  labels that fit). Bottom-bar labels must fit on one line at
  12sp on a 360dp-wide phone — verified on device, not in
  preview. The hidden-bar rule (reader/diagnostics/channel/
  bundle/edition/dossier hide the bar) is KEPT.
- **Sync truth on the feed:** one digest line under the sync
  row when anything is wrong: "۳ بخش ناموفق (بسته‌ها:
  http_404…)" + Diagnostics button. When all required
  sections are up to date, the digest line disappears (not a
  fake "everything is fine" banner).

---

## 3. Phase order with entry/exit gates

No phase starts until the previous phase's gate is met with
evidence (artifact, not assertion). "Phone" means Yasin's phone
or a device build he runs — screenshots/recordings count as
evidence; emulator-only claims do not pass phone gates.

**Phase 0 — Audit (THIS DOCUMENT).**
Gate G0 (met by this doc): every screen audited with
file:line evidence; A2 proven; A1 honestly marked unresolved
with ranked hypotheses. Exit: Yasin reviews this plan.

**Phase 1 — Reproduce + instrument (no redesign yet).**
- Add: build fingerprint line 1 on Diagnostics (version,
  code, signature state); sync trace (section start/end +
  outcome + code, kept in Diagnostics, never PII); seed-key
  vs manifest-key reconciliation line; feed-branch logging
  (which branch rendered, and when it flips).
- Reproduce A1 on a device build with a screen recording.
- Confirm the gated screens show a gated reason, never a
  "coming soon" name list (voices) and never an error for a
  gated section (special issues).
- Gate G1: A1 mechanism identified from the trace/recording
  OR explicitly narrowed to one hypothesis with the
  discriminating test named; build fingerprint confirmed on
  Yasin's phone (which build he actually runs). No Phase 2
  work starts on an unidentified A1.

**Phase 2 — Design system.**
- Tokens for §2 (type, spacing, rules, colour, motion,
  language) implemented as theme values; masthead direction
  decided (Yasin, §5); bottom-bar reduction implemented;
  ticker pause + motion toggle.
- Gate G2: device screenshots at 0.85×/1.0×/1.3× font scales
  showing masthead, hero, rows, reader, and bottom bar with
  labels fitting on one line; contrast ratios measured from
  those screenshots (body ≥ 4.5:1, large ≥ 3:1).

**Phase 3 — Feed + reader rebuild.**
- Front page (hero + ruled rows + section tabs + section
  index on empty), reader (headline/body/byline/double rule,
  ruler + calm + arc strip kept, body-empty state added),
  filter/search states with honest empties, feed digest line.
- Gate G3 (the A1 gate): feed STAYS populated across manual
  refresh, auto-sync, rotation, background/foreground, and
  process kill/restart, on device, with the recording as
  evidence. Empty/error/offline matrix per state (feed empty
  with no sync, feed empty with failed sync, feed populated
  with partial sync, offline launch) all pass with the §2
  language + retry + Diagnostics.

**Phase 4 — Sections rebuild + sync/diagnostics rebuild.**
- War Room, Market (with visible seed-date line), Bundles,
  Nightly (conditional on first real edition), Analysis,
  Bookmarks (orphan reasons), Search — each to its §2 state
  matrix.
- Sync: status-aware bundles/radar (nightly guard mirrored);
  per-section success clocks; global clock over REQUIRED
  sections only; edge contract test (fetchable ⇒ file exists)
  in the edge verifier; Bundles retry that cannot re-run a
  known-404 without a changed manifest (retry shows the
  manifest state, not just the error).
- Gate G4: a full sync completes with `last_sync_at`
  advancing while bundles/radar are unpublished; Bundles
  screen shows a reason, never `http_404` poisoning; the edge
  contract test passes on the live manifest.

**Phase 5 — Polish + accessibility.**
- TalkBack walkthrough PERFORMED on a phone with walk log +
  recording (the Phase 7 script, finally executed); font
  extremes; measured contrast; motion controls verified;
  per-section empty/error/offline matrix re-run on the final
  build; E1–E8-style re-verification on the final build.
- Gate G5: evidence set complete (walk log + recording +
  screenshots + contrast measurements). A claim without the
  log fails, as in v4.

**Phase 6 — Publish (checklist §4).**
- Gate G6: every PUB item checked, not asserted. Then Yasin's
  phone checklist on the v5 build (install-over from 4.0.1,
  no-banner-on-equal, banner-from-4.0.1, feed-stays, sections
  matrix spot-checks).

**Parallel, never blocking v5 reader release:** ML track
(join-first, per the v4 ML spec — unchanged by this plan) and
the Bale program (still pending, separate). Neither is in v5
scope, and neither blocks the v5 reader release.

---

## 4. Version decision + publish checklist (PUB mirror)

- **Version: 5.0.0, versionCode 6.** Package unchanged
  (`com.ecojournal.app`), label unchanged (`ECO JOURNAL`),
  signing unchanged (shared debug keystore via GitHub secret,
  cert `45220b15…`) — so the v5 APK installs OVER 4.0.1
  without uninstall and without re-entering the invite code.
  A phone still on the old vNext signature needs one final
  uninstall + reinstall + pilot-01 re-entry, exactly as before.
  APK file name: `app-debug-v5.apk` (gitignored, never
  committed).
- **PUB-1 Code complete:** Phases 1–5 committed locally,
  phase-labelled, working tree clean apart from explicitly
  excluded files; no debug/fixture/test-channel data in the
  release tree; no Blacksword, no NYT assets, no names.
- **PUB-2 Gates honest:** G1–G6 recorded pass/blocked/held
  with artifacts; no gate marked pass on a fixture where the
  spec requires a phone pass; voices/special issues recorded
  in their true gated state; A1 recorded in its true state.
- **PUB-3 Cloud build green:** GitHub Actions workflow on the
  pushed commit concludes `success`; a red build is fixed
  (max two fix rounds, as in v4) or publishing stops.
- **PUB-4 Shared signing verified:** APK signing cert SHA-256
  equals `45220b15…` via apksigner; a mismatch stops
  distribution outright.
- **PUB-5 APK identity verified with aapt:** package
  `com.ecojournal.app`, versionName `5.0.0`, versionCode `6`,
  minSdk 26, targetSdk/compileSdk 34, label `ECO JOURNAL`,
  size + SHA-256 recorded; file saved as `app-debug-v5.apk`.
- **PUB-6 Phone checklist passed on the v5 build:** Phase 5
  evidence set + install-over from 4.0.1 + no-banner-on-equal
  (6 = 6) + banner-from-4.0.1 (6 > 5) + feed-stays recording.
- **PUB-7 No live change beyond the plan:** no cron
  enabled/disabled/run, no bot change, no channel post, no
  production database change — verified by scope (lab repo
  + edge publish only). Special-issues crons stay disabled;
  lowburn restore stays disabled unless Yasin names it anew.
- **PUB-8 Freeze/lift honesty:** v5 ships no lift, no
  live-channel allow-listing, no special-issues publication,
  no voices publication, no FCM activation.
- **PUB-9 Privacy verified:** Diagnostics copy = shown lines
  exactly; no PII, content, invite code/hash, token, device
  id, or URL-with-parameters in APK strings or diagnostics
  output; no app behaviour (reads/bookmarks/searches) sent
  anywhere.
- **PUB-10 Distribution note honest:** delivery states the
  install path (My Files → Package Installer, not the Muse
  preview), whether an uninstall is needed (only for the old
  vNext signature), and that the invite code is re-entered
  only in that case. The remote-update banner path is the
  primary delivery (6 > 5 on a 4.0.1 phone); the Muse
  attachment is the fallback only if the banner does not
  appear — and a missing banner is itself a defect to report,
  not a reason to silently sideload.
- **Version manifest rule (release blocker, as in V4-I):**
  `version.json` must name `5.0.0` / `6` with the REAL v5 APK
  SHA-256 and a REAL hosted `apk_url` (verified by download
  + hash MATCH, as in 4.0.1), or publishing stops. No
  placeholder hash exists anywhere.

---

## 5. Yasin-only decisions (nothing else can decide these)

1. **Masthead direction:** restore the 32sp bold navy
   newspaper masthead (this plan's recommendation — legible,
   proven in the prior spec, no asset needed), or keep the
   script wordmark (requires a bundled OFL script font file
   first; Blacksword is personal-use only and is never an
   option for the app). Until he decides, Phase 2 cannot
   finalise the masthead.
2. **APK hosting for v5:** Pages force-add (as in 4.0.1 —
   direct via the version manifest, no public release listing)
   or a public GitHub release. Recommendation: Pages force-add
   (same as 4.0.1, proven by hash MATCH).
3. **Bottom-bar five:** which five stay primary (this plan
   proposes feed, warroom, market, bookmarks, settings, with
   bundles/nightly/analysis/method in a sections hub). He may
   swap one, but the count stays five — eight labels cannot
   fit a phone bar.
4. **Content gates:** none proposed. Voices and special issues
   stay gated; any change needs his explicit word naming the
   posts/list. The nightly rebuild stays conditional on the
   generator's first real edition.
5. **Bale:** still pending and separate. Nothing in v5
   depends on it, and v5 does not start it.

---

## 6. What v5 will NOT fix (said plainly)

- Editorial content volume or quality, channel cadence, or
  the stale-queue problem (old items posted as "urgent" after
  the lift) — those are operations/editorial decisions, not
  UI, and need their own orders (a freshness gate for the
  urgent label, a queue review).
- ML (join/volume/training, calibration gate), FCM/Play,
  domain steps, or the Bale connection. None are in v5 scope.
- The phone's network path to Pages: if the phone cannot
  reach Pages at all (browser test in the 4.0.1 script), no
  app redesign fixes that — it is a device/network fact the
  Phase 1 trace must establish first.
- A1, until Phase 1 reproduces it with logging. This plan
  refuses to promise A1 fixed by redesign alone; the gate
  structure exists precisely because 4.0.1's redesign did not
  fix what it could not reproduce.

---

*Companion records (unchanged, still governing their areas):
`../nyt-ui-spec.md` (prior newspaper spec), `../app-update-system-fix-plan.md`
(4.0.1 fix plan), `../big-update-plan.md` (54-service register),
`../cron-reconciliation.md` (freeze/lift cron authority),
`../v4/v4-program-plan.md` (v4 PUB checklist mirrored here).
Where this plan and a companion disagree on v5 ordering, this
plan governs; where they disagree on a phase's acceptance
criteria, the phase spec governs.*

---

## 7. Phase actuals (added in P6 — appendix, not a rewrite)

The plan above stands as written in Phase 0. What actually
happened, per phase (full records: `v5-p1-report.md` …
`v5-p6-report.md`):

- **P0 (this plan):** delivered as written — A2 proven,
  A1 honestly unresolved with ranked hypotheses H1–H4.
- **P1 — Reproduce + instrument (commit `209e0fb`):** as
  planned, minus the device run: build fingerprint on
  Diagnostics line 1, per-section sync trace, six-region
  feed-branch trace, seed/manifest reconciliation, gated-
  screen checks. G1 stayed OPEN — no device run was
  available in the lab, so the §4 protocol was carried
  forward, not executed.
- **P2 — Design system (commit `54fdab4`):** as planned.
  Delegated masthead = 32sp bold navy newspaper masthead
  (supersedes the script wordmark; no OFL font asset was
  bundled, no Blacksword); bottom bar five + sections hub;
  ticker pause + motion toggle. G2 stayed OPEN — device
  screenshots + measured contrast do not exist.
- **P3 — Feed + reader rebuild (commit `e1a6641`):** as
  planned. Feed digest line, fixed-language empty state
  with section index, honest filter/search empties, reader
  body-empty states, two-line sync row on failure. G3
  cannot pass without device evidence; G1 stayed OPEN.
  This rebuild is NOT claimed as a remedy for A1.
- **P4 — Sections + sync/diagnostics rebuild (commit
  `f87ca70`):** as planned, including the A2 fix:
  status-aware bundles/radar (nightly guard mirrored),
  per-section success clocks, global clock over REQUIRED
  sections only, edge contract test, Bundles retry that
  cannot re-run a known-404, Market seed-date line,
  Bookmarks orphan reasons. G4: edge leg PASS, sync leg
  OPEN (only a phone sync proves `last_sync_at` advances
  while bundles/radar are unpublished).
- **P5 — Polish + accessibility (commit `f2d936d`):** as
  planned, minus the device walk: five unlabelled Settings
  switches merged with their labels, headings on every
  rebuilt screen title, seeding-spinner announcement,
  static font-extremes verification, lab contrast
  recomputation, motion threading verified. G5 NOT MET —
  the TalkBack walkthrough script is written but the walk
  was never performed (no log, no recording).
- **P6 — Publish (commits `873701f`, `7cd988b`, plus
  this appendix + `v5-p6-report.md`):** as planned. 5.0.0 / 6, cloud build green
  first round (zero fix rounds), shared-signature APK
  verified, Pages force-add (delegated decision, as in
  4.0.1 — no public GitHub release), live edge verified
  (version.json 5.0.0/6 + real hash, apk_url hash MATCH,
  manifest articles 249). Gates carried forward OPEN:
  G1, G2, G4-sync-leg, G5 — all pending Yasin-only device
  steps; PUB-6 (phone checklist on the v5 build) is NOT
  passed by this phase and must not be reported as passed.
