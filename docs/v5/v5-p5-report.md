# ECO JOURNAL — Version 5, Phase P5 Report: polish + accessibility

Status: **POLISH + ACCESSIBILITY ONLY (lab) — no build, no
push, no publish, no live change.** Lab commit only (see §9).
Task order: P5 polish + accessibility, per
`docs/v5/v5-program-plan.md` §2–§3, on top of P4 commit
`f87ca70`, under Yasin's full go-ahead of 2026-10-26 ~01:36
Tehran (all open decisions delegated to Muse).

## 1. Gating section — P1 §4 protocol NOT executed; G1 REMAINS OPEN

**Gate G1 is still open. Defect A1 (feed flashes content for
~1 second on refresh, then jumps back to the empty page) is
NOT identified by this phase, and nothing in this phase
fixes it, works around it, or uses it as a rationale.**
No device run was available in this lab run (no phone in
the lab, no build by task order — the same position as P3
and P4), so the P1 §4 device-reproduction protocol could
not be executed and **no row of the P1 §3 decision table
matched**. P5's scope therefore follows the plan's Phase 5
items (TalkBack polish, font extremes, measured-contrast
protocol, motion verification, state-matrix re-run) and
nothing else. P5 does not re-litigate P3/P4 presentation:
no feed branch, feed query, reader text, digest, filter/
search-empty language, section screen layout, sync logic,
or clock was touched (FeedScreen changes are scroll +
spinner-announcement only; the verifier's scope check
enforces the P5 file list).

The P1 §3 decision table and §4 protocol remain the gating
authority, carried in full in `docs/v5/v5-p1-report.md` §3–§4
and `docs/v5/v5-p3-report.md` §2. In brief, the protocol
still owed is: install the instrumented build over the
current app; open Diagnostics FIRST and report the
fingerprint line verbatim; tap refresh ONCE while
screen-recording; copy the Diagnostics report verbatim
immediately after the flash (fingerprint + sync trace +
feed-branch trace + reconciliation); report Wi-Fi/mobile
and whether the phone's browser opens the edge manifest
URL; if the branch trace shows `front_page → empty`,
repeat with rotation and background/foreground. Until
those steps produce a report, G1 stays open, the Phase 3
feed-stays gate (G3) cannot pass, and no P5 polish may be
described as resolving A1.

## 2. Gate statuses (honest, lab-only)

- **G1 — OPEN.** No device run; A1 mechanism unidentified
  (§1). The P1 instruments the protocol needs are still
  present in this tree (fingerprint Diagnostics line 1,
  branch trace, sync trace, reconciliation — re-verified
  by the P1 verifier run in §8).
- **G2 — OPEN.** Device screenshots at 0.85×/1.0×/1.3×
  with measured contrast do not exist yet. What P5 did
  instead, honestly labelled: static font-extremes
  verification (§4) and lab contrast recomputation from
  hex (§5). Lab ratios do NOT pass G2; §7 gives the
  device-measurement protocol that can.
- **G4 — EDGE LEG PASS / SYNC LEG OPEN.** Unchanged from
  P4: the status-aware bundles/radar guards, required-only
  clock, and edge contract are lab-verified, but only a
  phone sync can prove `last_sync_at` advances on device
  while bundles/radar are unpublished. P5 changes nothing
  in sync code, so it neither advances nor regresses G4.
- **G5 — NOT MET.** The TalkBack walkthrough script is
  written (§3) but the walk itself was NOT performed —
  there is no walk log and no recording, and P5 claims
  none. G5 needs the performed walk (§3, §7).

## 3. TalkBack audit — what was fixed, what was already sound

### (a) Fixed in P5: five unlabelled switches (proven gap)

SettingsScreen's four reader-helper switch rows and the
update auto-check row used plain `Row`s: the Switch was a
separate semantics node from its label Text, so TalkBack
announced a bare «switch, on/off» with no label. The
Phase 7 pattern for exactly this problem already existed
in the same file (per-service toggles, merged
`Column`). P5 applies it to all five rows
(`SettingsScreen.kt`, `mergeDescendants = true`):

- ticker toggle — `:193` (label «نوار اخبار زنده زیر
  نام اپ»)
- reduced-motion toggle — `:211` (label «کاهش حرکت
  (نوار ثابت به‌جای متحرک)»)
- reading-ruler toggle — `:232` (label «خط‌کش مطالعه»)
- calm-mode toggle — `:253` (label «حالت آرام مطالعه»)
- update auto-check toggle — `:634` (label «بررسی
  خودکار نسخهٔ تازه»)

No behaviour changed: the same Switch, same setter, same
state — only the TalkBack node merges. The walkthrough
(§7) must confirm no switch became unreachable by
merging; that confirmation is device evidence, not
claimed here.

### (b) Fixed in P5: headings on every rebuilt screen title

Heading navigation is how TalkBack users jump between
sections. War Room, Market, Nightly, and the masthead
already had `heading()`; the rest did not. P5 adds it
(no text, size, or layout change — a semantics modifier
only):

- `BundlesScreen.kt:100` (bundles title)
- `AnalysisScreen.kt:118` (analysis title)
- `DiagnosticsScreen.kt:550` (diagnostics title)
- `SectionsHubScreen.kt:63` (hub title)
- `GatedSectionsScreen.kt:56` (voices title), `:84`
  (special-issues title)
- `MethodScreen.kt:56` (method title)
- `InviteScreen.kt:72` (invite title)
- `ChannelReaderScreen.kt:171` (timeline title)
- `ArticleScreen.kt:280` (reader headline)
- `SettingsScreen.kt` section headings: font `:126`,
  theme `:158`, reader helpers `:185`, notifications
  `:277`, per-service notifications `:366`, quiet
  hours `:424`, app update `:620`

Imports added alongside each (`heading` + `semantics`
where the file lacked them). The one rebuilt screen
without a heading landmark is **Bookmarks**: it has no
title Text at all (empty state is a single line; the
populated list is a bare LazyColumn), so its landmark
remains the bottom-bar tab label. Recorded as a known
gap for the walk (§7), not silently papered over with a
visible title that would change the layout.

### (c) Fixed in P5: seeding spinner announcement + scrollable short states

- `FeedScreen.kt:834–840` — the seeding branch showed a
  bare `CircularProgressIndicator` with no text and no
  announcement. It now carries `contentDescription` from
  the new string `v5_feed_seeding`
  (`strings.xml:494`: «در حال بارگذاری بذر آفلاین…»).
  The branch is still bounded exactly as before (seed
  reports → empty state, never a permanent spinner).
- `FeedScreen.kt:730` (filter empty), `:771` (search
  empty), `:863` (feed empty) — these three short states
  sit in the feed's NON-scrolling root Column. At 1.3×
  on a small phone the feed-empty tail (section index +
  hub button) could clip with no way to reach it. All
  three Columns now scroll on their own
  (`.verticalScroll(rememberScrollState())`, the same
  pattern every other screen already uses). No other
  feed branch changed: the populated front page, search
  results, and urgent rail are already LazyColumns.

### (d) Verified sound, no change (audit evidence)

- **Slider:** font-size slider already carries
  `font_size_desc` («اندازهٔ قلم») with a separate
  value line above it (`SettingsScreen.kt:142`).
- **Chart self-description:** the market chart Canvas
  announces `market_chart_desc` («نمودار تاریخچهٔ
  بازار؛ با لمس نمودار نزدیک‌ترین برداشت انتخاب
  می‌شود و جزئیات آن در کارت زیر نمودار خوانده
  می‌شود.», `MarketScreen.kt:640`). Deliberate
  judgement, stated plainly: the chart announces its
  interaction and points to the text alternative (the
  snapshot card + prev/next buttons), which carry the
  actual figures. P5 does NOT add spoken values to the
  chart description — inventing a formatted min/max
  announcement without a build to test it would risk a
  wrong or unlocalised announcement. If the snapshot
  card is ever unreachable, the chart is a walk FAIL,
  not a partial pass (§7).
- **Ticker:** full topic text announced once via
  `ticker_desc`, never the looping marquee alone
  (`Masthead.kt:134`); pause/resume buttons labelled
  (`:181`).
- **Cards:** article cards announce «مقاله: {title}»
  as a button with the «باز کردن مقاله» action
  (`ArticleCard.kt:154–156`); typed cards announce
  «قالب: {badge}» (`ChannelCard.kt:128`); category
  bars always carry a text label, never colour alone
  (`ChannelCard.kt:60–99`).
- **Ruler / arc / layers / stories:** ruler description
  (`ReaderHelpers.kt:88`), arc description with all
  version titles (`:117`), per-version open buttons
  (`:145`), radar layer collapse/expand descriptions
  (`WarRoomScreen.kt:526`), story buttons with titles
  (`:657`).
- **Bottom bar:** icon content descriptions are the tab
  labels (`MainActivity.kt:617`); NavigationBarItem
  supplies selected state.
- **Fields:** feed search, nightly search, invite code,
  and quiet-hours fields all have visible labels.
- **Progress:** the gate-loading spinner has a text
  line (`gate_loading`, `MainActivity.kt:130`); the
  sync-row spinner sits in a row whose content
  description is the status sentence
  (`SyncCompact.kt:68`); the download progress bar
  announces its version (`AppUpdateBanner.kt:102`).

### (e) The walkthrough script (the instrument, not the evidence)

NEW `docs/v5/talkback-walkthrough.md` — a reusable
script with setup, per-screen steps + expected
announcements (§1 feed, §2 settings, §3 reader, §4
sections, §5 invite/diagnostics), font-extremes passes
(§6), motion states (§7), the contrast-measurement
protocol (§8), the required log contents (§9), and the
known lab limits (§10). The performed walk — log +
recording — is owed by Yasin on his phone and is NOT
claimed by this phase.

## 4. Font extremes — static verification

Effective sizes at the in-app fontScale values
(Settings slider range is hard-clamped 0.85–1.3,
`SettingsStore.kt:144`):

| Element | 0.85× | 1.0× | 1.3× |
|---|---|---|---|
| Masthead 32sp | 27.2 | 32 | 41.6 |
| Hero headline 26sp | 22.1 | 26 | 33.8 |
| Row headline 18sp | 15.3 | 18 | 23.4 |
| Hero summary 16sp | 13.6 | 16 | 20.8 |
| Row summary 15sp | 12.8 | 15 | 19.5 |
| Reader headline 28sp | 23.8 | 28 | 36.4 |
| Reader body 18sp | 15.3 | 18 | 23.4 |
| Meta / sync row 12sp | 10.2 | 12 | 15.6 |
| Ticker 13sp | 11.1 | 13 | 16.9 |
| Bottom-bar label 12sp | 12 | 12 | 12 |

Findings:

- **No bare sp size exists anywhere** in screens or
  components (verifier-checked): every fontSize is a
  token × fontScale or a literal × fontScale. Nothing
  is fixed-size in a way that breaks at 1.3×.
- **Bottom bar is invariant under the in-app slider:**
  `BottomBar(nav)` takes no fontScale parameter
  (`MainActivity.kt:599`), so its 12sp labels do not
  grow with the slider — they fit MORE easily at 1.3×
  in-app, not less. The bar follows the SYSTEM font
  scale instead (all sp values do). Only the system
  scale can break it, which is why §7 requires the
  system-size bar check. This is a documented split,
  not a bug P5 can fix by threading fontScale into the
  bar (that would change the bar's contract and is a
  P6 decision if Yasin wants it).
- **Truncation inventory (none breaks layout):**
  hero summary max 6 lines, row headline/summary max
  3 lines, arc node titles max 2 lines (full title in
  the TalkBack description), ticker max 1 line + full
  description, sync row max 1 line normally / 2 on
  failure, sync last-sync line max 1 line, bar labels
  max 1 line. All truncations are inside bounded
  containers (scrolling lists/columns) or carry their
  full text in a TalkBack description — except the
  bar labels and the sync last-sync line, whose full
  text lives in Diagnostics. At 1.3× the risk is
  information loss on those two lines (ellipsis), not
  overflow — a device-check item (§7), not a lab FAIL.
- **Scroll coverage, re-checked:** every screen's root
  scrolls (LazyColumn or verticalScroll) EXCEPT the
  feed root Column — and P5 closed its three short-state
  gaps (§3(c)). The feed's populated branches were
  already LazyColumns.

Remaining device risk (for §7, not hidden): the
longest Persian bar label («ذخیره‌شده‌ها») at the
largest SYSTEM font size on a 360dp phone; the sync
row's one-line status at 1.3× in-app; the feed-empty
tail at 1.3× after the P5 scroll fix (confirm the fix,
don't assume it).

## 5. Contrast — lab recomputation (does NOT pass G2)

Recomputed from hex with the WCAG formula
(`edge/verify_v5p5.py`; every hex verified present in
`Theme.kt` / `DesignTokens.kt` / `Phase2Domain.kt`).
Every pair passes its lab threshold; the weakest are
the orange badge (5.54:1) and the ticker red (5.62:1).

| Pair | Ratio | Target |
|---|---|---|
| Ink `#121212` on light paper | 18.73:1 | ≥ 4.5 |
| Navy `#0F2A44` on light paper | 14.63:1 | ≥ 4.5 |
| Grey `#5A5A5A` on light paper | 6.90:1 | ≥ 4.5 |
| Error `#9E2B25` on light paper | 7.43:1 | ≥ 4.5 |
| Secondary `#3D5A73` on light paper | 7.22:1 | ≥ 4.5 |
| White on ticker red `#C62828` | 5.62:1 | ≥ 4.5 |
| White on ticker empty red `#8D1B1B` | 9.11:1 | ≥ 4.5 |
| Ink `#F2F2F2` on dark paper `#121212` | 16.73:1 | ≥ 4.5 |
| Navy `#9DB8D6` on dark paper | 9.15:1 | ≥ 4.5 |
| Secondary `#8FA9C4` on dark paper | 7.71:1 | ≥ 4.5 |
| Error `#E57373` on dark paper | 6.27:1 | ≥ 4.5 |
| Grey `#B0B0B0` on dark surface `#1E1E1E` | 7.69:1 | ≥ 4.5 |
| Grey `#B0B0B0` on dark surfaceVariant `#2A2A2A` | 6.62:1 | ≥ 4.5 |
| Sepia ink `#2B2118` on `#FBF3DF` | 14.24:1 | ≥ 4.5 |
| Sepia primary `#5C4321` on `#FBF3DF` | 8.33:1 | ≥ 4.5 |
| Sepia grey `#6B5D4A` on `#FBF3DF` | 5.77:1 | ≥ 4.5 |
| Sepia secondary `#7A6248` on `#FBF3DF` | 5.18:1 | ≥ 4.5 |
| Sepia grey on `#F3E8CC` | 5.24:1 | ≥ 4.5 |
| White on navy `#0F2A44` (onPrimary) | 14.63:1 | ≥ 4.5 |
| Navy `#0F2A44` on `#9DB8D6` (dark onPrimary) | 7.15:1 | ≥ 4.5 |
| Black on yellow bar `#F9A825` | 10.66:1 | ≥ 4.5 |
| White on red bar `#C62828` | 5.62:1 | ≥ 4.5 |
| White on grey badge `#616161` (L1) | 6.19:1 | ≥ 4.5 |
| Black on orange badge `#E65100` (L3) | 5.54:1 | ≥ 4.5 |

Honest limit, repeated because it matters: the light
theme's background/surface slots are NOT overridden in
`Theme.kt` (Material3 defaults apply), so the light
paper value used here is the token `#FFFFFF`, not a
measured render. Theme overlays can shift rendered
colour. Only device screenshots + pixel sampling (§7)
can pass G2. No colour was changed in P5 — every pair
already passed in the lab, so changing one would be
churn, not a fix.

## 6. Motion — threading verified end to end

`SettingsStore.kt` (`REDUCED_MOTION` key, `reducedMotion`
field default false, `setReducedMotion()`) →
`MainActivity.kt:336–337` (feed receives
`tickerEnabled = settings.tickerEnabled`,
`reducedMotion = settings.reducedMotion`) →
`FeedScreen.kt:174,178` (params, defaults true/false) →
`FeedScreen.kt:452–454` (passed to `Masthead()`) →
`Masthead.kt:109` (`reducedMotion` param) →
`Masthead.kt:122` (`animating = topics.isNotEmpty() &&
!reducedMotion && !paused`) → marquee applied only when
`animating`, static text otherwise; the pause button is
shown only when `!reducedMotion` (`:177` — under reduced
motion there is nothing to pause); the empty ticker uses
the distinct darker strip (`TickerEmptyRed`).

Other motion inventory: `basicMarquee` has exactly ONE
call site in the whole codebase (`Masthead.kt:164`) —
there is no second infinite animation to miss. The
HorizontalPager swipe animation is user-initiated and
bounded; no list uses item-placement animation. The
ticker is the only infinite motion, and it now has two
independent controls: session pause (ticker button) and
persistent reduced motion (Settings toggle), plus the
master ticker on/off switch. Device checks owed (§7):
pause/resume, reduced-motion static rendering, ticker-off
strip absence — all three states, on the phone.

## 7. Device protocols owed (G2/G5 evidence — NOT performed)

These are the exact checks Yasin must run on his phone
on the P6 build; the full scripts live in
`docs/v5/talkback-walkthrough.md` (§6–§8 there).

1. **TalkBack walk** (walkthrough §1–§5, §9–§10):
   TalkBack on, screen recording running, per-step
   expected announcements as written in the script
   (masthead heading, ticker once-only, merged switch
   labels, reader headline heading, chart + card pair,
   hub with no gated destinations). Log: build
   fingerprint verbatim, per-step pass/fail, verbatim
   quotes of any surprise announcement, recording name.
2. **Font extremes** (walkthrough §6): in-app slider at
   0.85×/1.0×/1.3× — masthead one line, cards wrap
   without overlap, feed-empty tail reachable (P5 scroll
   fix confirmation), sync row intact. Then SYSTEM font
   sizes small/default/large/largest — all five bar
   labels (صفحهٔ اصلی، اتاق جنگ، بازار، ذخیره‌شده‌ها،
   تنظیمات) fit / ellipsize / clip, recorded per label.
   A clipped bar label is a FAIL.
3. **Motion states** (walkthrough §7): ticker on +
   reduced motion off (marquee + pause works); ticker on
   + reduced motion on (static text, no pause button,
   no infinite motion anywhere); ticker off (no strip).
   A marquee running under reduced motion is a FAIL.
4. **Contrast measurement** (walkthrough §8): screenshots
   of feed, reader, ticker, red-bar card, yellow-bar
   card, Settings — in light, dark, AND sepia — with
   pixel-sampled WCAG ratios for every §5 pair per
   theme. Any body pair < 4.5:1 on device is a FAIL even
   though the lab ratio passed.
5. **G1 protocol + G4 sync leg** (carried from §1 and
   P4 §7): the P1 §4 reproduction run with the decision
   table, and a full sync proving `last_sync_at`
   advances while bundles/radar are unpublished, with
   the Bundles reason line and Diagnostics clocks.

## 8. Empty/error/offline matrix — every section × state

Rule applied to every row: what happened + the code
where one exists + exactly one next step (the feed
empty state itself carries the pre-existing retry +
Diagnostics pair; section rows carry their own retry /
re-check). **No row is blank, no spinner is unbounded,
and no row claims "up to date" without a real sync
result** — `sync_up_to_date` fires only on
`SyncResult.Ok` with the cached count
(`FeedScreen.kt:345`, `DiagnosticsScreen.kt:682`); the
idle sync row reads «همگام‌سازی انجام نشده»
(`sync_compact_idle`), never "up to date".

### Feed (FeedScreen + SyncCompact)

| State | Exact string shown |
|---|---|
| Seeding, cache empty | Spinner + TalkBack «در حال بارگذاری بذر آفلاین…» (`v5_feed_seeding`, P5). Bounded: seed report → empty state. |
| Empty, no sync ever | «هنوز مقاله‌ای در حافظه نیست و هیچ همگام‌سازی موفقی ثبت نشده است» (`v5_state_empty_no_sync`) + [تلاش دوباره] [تشخیص] + section index. |
| Empty, seed failed | «بذر آفلاین با خطا مواجه شد؛ همگام‌سازی دوباره را بزنید.» (`feed_empty_seed_failed`) + buttons. |
| Empty, offline code | «دستگاه آفلاین است؛ فقط مقالات کش‌شده نمایش داده می‌شوند (کد network)» (`v5_state_offline`) + buttons. |
| Empty, other code | «آخرین خطای ثبت‌شده: {code}» (`feed_empty_error`) + buttons. |
| Empty, sync recorded but cache empty | «هنوز مقاله‌ای در حافظه نیست. دکمهٔ همگام‌سازی را بزنید تا پس از تنظیم لبهٔ انتشار، مقالات دریافت شوند.» (`feed_empty`, narrow case only). |
| Populated, sync failed | Sync row red + [تلاش دوباره] + digest line: one section → «بخش {section} به‌روز نشد (کد {code})» (`v5_state_section_failed`); several → «{n} بخش به‌روز نشد (کد {code})» (`v5_feed_digest_failed`); manifest → «فهرست انتشار دریافت نشد (کد {code})» (`v5_feed_digest_manifest`) or offline line; + [تشخیص]. Feed stays readable. |
| Populated, sync partial | «همگام‌سازی ناقص ماند؛ {n} مقاله در تلاش بعدی دوباره دریافت می‌شوند.» (`sync_partial`). |
| Populated, sync ok, nothing new | «به‌روز است ({n} مقاله در حافظه).» (`sync_up_to_date`, Ok-only). |
| Populated, sync ok, new items | «{n} مقالهٔ تازه دریافت شد.» (`sync_new_articles`). |
| Syncing | «در حال همگام‌سازی…» (`sync_compact_syncing`) + spinner + progress «در حال دریافت مقالهٔ {a} از {b}…» (`sync_progress_articles`). |
| Sync never run (idle row) | «همگام‌سازی انجام نشده» (`sync_compact_idle`) + «هنوز همگام‌سازی موفقی ثبت نشده است؛ فقط مقالات کش‌شده نمایش داده می‌شوند.» (`sync_never_succeeded`). |
| Sync not configured | «لبهٔ انتشار هنوز تنظیم نشده است. فقط مقالات کش‌شده نمایش داده می‌شوند.» (`sync_not_configured`). |
| Sync failed, offline | «همگام‌سازی ناموفق بود: دستگاه آفلاین است یا شبکه در دسترس نیست. مقالات کش‌شده نمایش داده می‌شوند.» (`sync_failed_offline`). |
| Sync failed, HTTP | «همگام‌سازی ناموفق بود: لبهٔ انتشار در دسترس نیست. بعداً دوباره تلاش کنید.» (`sync_failed_http`). |
| Sync failed, bad manifest | «همگام‌سازی ناموفق بود: مانیفست انتشار (نسخهٔ ۳) خوانا نیست. بعداً دوباره تلاش کنید.» (`sync_failed_bad_manifest`). |
| Filter empty | «برای این قالب هنوز خبری منتشر نشده است؛ این خالی بودنِ فیلتر است، نه خالی بودنِ حافظهٔ مقالات.» (`v5_feed_filter_empty`) + [نمایش همه] (`v5_feed_filter_clear`). |
| Search empty | «در مقالات ذخیره‌شده چیزی برای این جست‌وجو یافت نشد.» (`search_no_results`) + [پاک کردن جست‌وجو] (`v5_feed_search_clear`). |
| Search results | «{n} نتیجه» (`search_results_count`). |
| Section-tab empty | «در این بخش هنوز مقاله‌ای در حافظه نیست.» (`section_empty`) + sync retry. |
| Urgent rail | Title «فوری» (`urgent_rail_title`); rail items are cards, never an empty rail line (empty rail renders nothing). |

### Reader (article + channel)

| State | Exact string shown |
|---|---|
| Article not found | «مقاله یافت نشد. شاید هنوز همگام‌سازی نشده است.» (`article_not_found`) + [تلاش دوباره (دریافت از لبه)] (`article_not_found_retry`). No code line here: `fetchOne` surfaces no code, so none is invented (P3 §5). |
| Article body empty | «متن این مطلب در حافظهٔ دستگاه نیست؛ همگام‌سازی دوباره ممکن است آن را بیاورد.» (`v5_reader_body_empty`) + re-fetch. |
| Channel item not found | «این خبر دیگر در دسترس نیست.» (`channel_not_found`) + [بازگشت]. |
| Channel body empty (non-SITREP) | Same `v5_reader_body_empty` line + back (SITREP items excluded: their sections carry the content). |
| Channel timeline | «زنجیرهٔ خبر» (`channel_timeline_title`) + per-version «باز کردن نسخه: {title}». |

### War Room

| State | Exact string shown |
|---|---|
| No crisis level | «سطح بحران اعلام نشده» (`war_room_level_missing`). |
| No radar cadence | «رادار: بازه اعلام نشده» (`war_room_radar_cadence_missing`). |
| Offline line (when a last sync exists) | «آفلاین — آخرین به‌روزرسانی {date}» (`feed_offline_line`). |
| Typed/radar failed | «به‌روزرسانی {section} ناموفق بود ({code}) — تلاش دوباره» (`section_error_line`) + [تلاش دوباره]. |
| Radar unpublished | «بخش رادار هنوز منتشر نشده است؛ فایلی برای دریافت ندارد.» (`v5_state_section_not_published`) + [بازبینی وضعیت از سرور] (`v5_section_recheck`, manifest re-read only). |
| No SITREP | «هنوز گزارش وضعیتی منتشر نشده است» (`war_room_no_sitrep`); arc: «امروز هنوز گزارش وضعیتی منتشر نشده» (`war_room_arc_empty`); arc no-change: «بدون تغییر در طول روز» (`war_room_arc_no_change`). |
| No radar signals | «هنوز سیگنالی ثبت نشده است» (`war_room_radar_empty`) / «هیچ سیگنال راداری ثبت نشده است» (`radar_empty_p4`). |
| Stale signals | Section «سیگنال‌های قدیمی» (`radar_stale_title`) + tag «قدیمی» (`radar_stale_tag`). |
| Story not in app | «داستان هنوز در برنامه نیست» (`radar_not_yet_in_app`). |

### Market

| State | Exact string shown |
|---|---|
| Loading | «در حال بارگذاری دادهٔ بازار…» (`market_loading`). |
| Seed ran, no snapshot | «دادهٔ بازار در دسترس نیست.» (`market_unavailable`) + detail «بذر همراه برنامه اجرا شد ولی هیچ اسنپ‌شاتی در حافظه نیست؛ فایل بازار ممکن است خراب یا ناقص باشد. با تلاش دوباره، بذر از همان‌جایی که مانده ادامه می‌دهد و چیزی از اول ساخته نمی‌شود.» (`market_unavailable_detail`) + [تلاش دوباره]. |
| Seed only (P4) | «این ارقام از بستهٔ همراه برنامه‌اند (تازه‌ترین دادهٔ بسته: {date}) و هنوز با همگام‌سازی زندهٔ بازار به‌روز نشده‌اند.» (`v5_market_seed_line`); disappears after the first successful market sync. |
| Stale (> 24 h) | «⚠ دادهٔ بازار قدیمی است (بیش از ۲۴ ساعت). این اسنپ‌شات از حافظهٔ محلی است و با همگام‌سازی آینده به‌روز می‌شود.» (`market_stale`). |
| Formulas empty | «فرمولی در دسترس نیست. فرمول‌ها همراه بذر بازار وارد می‌شوند؛ اگر خالی ماند، با همگام‌سازی آینده به‌روز می‌شوند.» (`market_formulas_empty`). |
| Formula uncomputable | «محاسبه ممکن نشد؛ یک یا چند ورودی در این اسنپ‌شات موجود نیست.» (`market_formula_uncomputable`). |
| History range empty | «در این بازه هیچ برداشتی ثبت نشده است» (`market_history_empty`). |
| Sync failed (tab) | Same `section_error_line` pattern as War Room, market section. Thresholds stay OFF (Yasin's decision). |

### Bundles / Nightly / Analysis / Bookmarks

| State | Exact string shown |
|---|---|
| Bundles empty | «هنوز بسته‌ای منتشر نشده است» (`bundles_empty`). |
| Bundles unpublished | «بخش بسته‌ها هنوز منتشر نشده است؛ فایلی برای دریافت ندارد.» (`v5_state_section_not_published`) + [بازبینی وضعیت از سرور]. |
| Bundles known 404 | «فایل بسته‌ها روی سرور پیدا نشد؛ تا فهرست انتشار سرور عوض نشود، تلاش دوباره همان نتیجه را می‌دهد.» (`v5_bundles_404_reason`) + re-check (never a re-fetch). |
| Bundle not found / empty page | «این بسته دیگر در دسترس نیست.» (`bundle_not_found`) / «این بسته خالی است» (`bundle_page_empty`). |
| Night desk empty | «این بخش خالی است» (`night_desk_empty`). |
| Nightly failed with hero | «نسخهٔ امشب نرسید (خطای شبکه) — نسخهٔ دیروز نمایش داده می‌شود.» (`nightly_failed_line`). |
| Nightly empty | «هنوز هیچ نسخهٔ شبانه‌ای منتشر نشده است» (`nightly_empty`). |
| Nightly search, no results | «چیزی پیدا نشد.» (`nightly_no_results`). |
| Nightly not kept locally | «این نسخه به‌صورت محلی نگه داشته نشده است؛ برای دریافت آن وصل شوید.» (`nightly_not_kept_locally`). |
| Nightly none tonight | «امشب چیزی نیست.» (`nightly_none_tonight`); no snapshot: «برای این روز برداشتی ثبت نشده است.» (`nightly_no_snapshot`); no corrections: «امروز اصلاحیه‌ای نیست.» (`nightly_no_corrections`). |
| Nightly/analysis held | «برخی اقلام این بخش نگه داشته شدند (در گزارش سازنده ثبت شده است).» (`nightly_held_line` / `analysis_held_note`). |
| Analysis empty | «هنوز تحلیلی منتشر نشده است» (`analysis_empty`); no top-stories card: «این هفته کارتی نیست.» (`topstories_no_card`). |
| Bookmarks empty | «هنوز چیزی ذخیره نکرده‌اید.» (`bookmarks_empty`). |
| Bookmark orphans | Title «ذخیره‌شده‌های دیگر در دسترس نیست» (`bookmarks_unavailable_title`) + row «این مقاله دیگر در دسترس نیست.» (`bookmark_unavailable_row`) + reason «از منبع پس گرفته شده است.» (`v5_bookmark_orphan_retracted`) or «دیگر در حافظهٔ دستگاه نیست.» (`v5_bookmark_orphan_missing`) + [حذف]. |

### Gated / invite / diagnostics / hub / update

| State | Exact string shown |
|---|---|
| Voices placeholder | «صداهای مهم — فهرست در انتظار نهایی‌شدن توسط یاسین است. تا آن زمان هیچ صفحهٔ صدایی منتشر نمی‌شود.» (`voices_placeholder`). No names, ever. |
| Special issues | «ویژه‌نامه‌ها — متوقف است» (`special_issues_placeholder` / `special_issues_frozen`, same line in hub-adjacent and bundles contexts). |
| Gated language, defined but unwired | `v5_state_gated` («این بخش فعلاً متوقف است») is a P2 language definition that was NEVER wired to any screen (P3 §5, unchanged in P5) — gated screens show their placeholder lines above, never this string. It is deliberately NOT in the verifier's used-list; claiming otherwise would be false. |
| Invite, wrong code | «کد واردشده معتبر نیست. دوباره بررسی کنید.» (`invite_error`) — never which part mismatched. |
| Invite, loading gate | «در حال بررسی دسترسی…» (`gate_loading`) + spinner (text-accompanied, not bare). |
| Diagnostics, no data yet | «هنوز ردی ثبت نشده است» (`diagnostics_trace_none` / `diagnostics_branch_none` / `diagnostics_recon_none`); values «ثبت نشده است» (`diagnostics_no_value`). Per-section states: «به‌روز» / «ناقص» / «ناموفق» / «هنوز منتشر نشده» / «متوقف (گیت‌دار)» / «نامشخص». Global-clock scope line (`v5_diagnostics_global_clock`) states the required-only scope. |
| Sections hub | «بخش‌ها» + «بسته‌ها، نسخهٔ شبانه، تحلیل و روش کار در یک جا» + four buttons. No gated destinations. |
| Update downloading | «در حال دانلود نسخهٔ {v}…» (`app_update_downloading`) + progress bar with version announcement. |
| Update ready | «نسخهٔ {v} دانلود و راستی‌آزمایی شد؛ با یک ضربه نصب می‌شود.» (`app_update_ready`) + [نصب با نصب‌کنندهٔ سیستم]. |

Matrix completeness is verifier-checked: every string
above except the explicitly unwired `v5_state_gated`
exists in `strings.xml` AND is referenced in code
(`edge/verify_v5p5.py` §5, which separately asserts
`v5_state_gated` is defined-but-unreferenced). Two honest
gaps carried from P3/P4, unchanged: untraced sync paths
produce no digest line (the sync row still reports those
failures), and the reader not-found page carries no error
code (`fetchOne` surfaces none — none invented).

## 9. Files changed, commit, and hard-limit compliance

Changed (lab commit only, NO push):
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt`
  (verticalScroll import; 3 scrollable short-state Columns;
  seeding-spinner TalkBack description)
- `app/src/main/java/com/ecojournal/app/ui/screens/SettingsScreen.kt`
  (heading import; 7 section headings; 5 merged switch rows)
- `app/src/main/java/com/ecojournal/app/ui/screens/BundlesScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/AnalysisScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/SectionsHubScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/GatedSectionsScreen.kt`
  (2 title headings + imports; gate checks untouched)
- `app/src/main/java/com/ecojournal/app/ui/screens/MethodScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/InviteScreen.kt`
  (title heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/ChannelReaderScreen.kt`
  (timeline heading + imports)
- `app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt`
  (reader-headline heading + import)
- `app/src/main/res/values/strings.xml` (+1 string,
  `v5_feed_seeding`; 448 names, all unique)
- NEW `edge/verify_v5p5.py` (verifier, §10)
- NEW `docs/v5/talkback-walkthrough.md` (reusable script)
- NEW `docs/v5/v5-p5-report.md` (this file)

NOT touched (pre-existing dirty files left alone, not
committed): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `bridge/*`, and all other
pre-existing untracked files.

Hard limits honoured: no cron changes, no live-channel
changes or posts, no special-issues/voices publication
or gate changes (gate `check()` lines intact; hub and
section index still exclude them), no FCM/Play, no Bale
work, no Room schema change (no entity/migration/
destructive call in the diff), no night-law/bar/gate/
cadence behaviour changes (`NightLaw.kt` and
`ChannelCard.kt` untouched; the diff contains no
`DomainBar`/`NightLaw` reference), no versionName/
versionCode change (still 4.0.1 / 5 — P6 owns 5.0.0/6),
no push, no build, no publish, no live change of any
kind.

## 10. Verifier runs this session

- NEW `edge/verify_v5p5.py`: **ALL V5 P5 CHECKS PASSED
  (315 checks, 0 failures, 0 skipped).** Static only — it
  does NOT compile anything (§11).
- `edge/verify_v5p1.py`: **ALL V5 P1 CHECKS PASSED**
  (no P1 regression).
- `edge/verify_v5p2.py`: **ALL V5 P2 CHECKS PASSED**
  (no P2 regression).
- `edge/verify_v5p3.py` / `edge/verify_v5p4.py`: their
  content checks pass, but their phase-pinned scope
  checks fail ONLY because P5 (this authorized phase)
  changed FeedScreen/DiagnosticsScreen and the section
  screens — the same phase-pinning effect P4 reported
  for P3, not a P3/P4 regression. (P3: 2 scope failures;
  P4: 2 scope failures, both the scope lines.)

## 11. Caveats and limits (read before relying on this phase)

- **No build was run** (task order: no build, no push,
  no publish). Kotlin correctness was checked by
  reading, by the brace/paren balance check in the
  verifier, and by static wiring checks — not by
  compiling. Real risks carried to the P6 cloud build:
  the `Modifier.semantics { heading() }` additions in
  files that previously had no semantics import (a
  wrong import line breaks the file, not the layout),
  the `mergeDescendants` Row modifiers in Settings, and
  the `verticalScroll` import + three scroll modifiers
  in FeedScreen. If the build fails, these are the
  first suspects, not the token values or the strings.
- **No phone result is claimed anywhere in this report
  or the walkthrough script.** Every device sentence is
  written as owed evidence (§7) or as an expected
  announcement for the walk to confirm — never as a
  performed result.
- **The merged-switch change alters TalkBack focus
  order** (label + switch become one stop). The lab
  cannot prove no switch became unreachable; the walk
  must confirm it (§7 step 2.3 note).
- **The bar's system-scale behaviour is unverified:**
  the largest-system-size bar check is the single
  highest-value device check in this phase's backlog,
  because it is the one layout P5 could not prove in
  the lab at all.
- **Contrast honesty restated:** §5 ratios are lab
  values from hex constants. The light theme's
  background slot is a Material3 default, not an app
  override — if the default differs from token paper
  white, rendered ratios shift. G2 stays open until
  pixel-sampled device measurements exist.

## 12. What Phase 6 must do next (vs what stays out)

**Phase 6 (publish) MUST:**
1. FIRST run the P1 §4 device-reproduction protocol
   (§1) and record the G1 verdict from the P1 §3
   decision table. G1 is still open, and no P5 polish
   may be described as resolving A1.
2. Prove G4 on device: a full sync on Yasin's phone
   advances `last_sync_at` while bundles/radar are
   unpublished; the Bundles screen shows the reason
   line; Diagnostics shows bundles/radar
   `not_yet_published` with null success clocks and
   required sections with fresh confirmation clocks.
3. Perform the TalkBack walkthrough
   (`docs/v5/talkback-walkthrough.md`) with Yasin on
   his phone — walk log + recording — and record G5
   from it, including: the five merged switches, the
   heading navigation, the font-extremes passes, the
   system-font bar table, the three motion states, and
   the pixel-sampled contrast table for G2.
4. Only then run the publish checklist (version bump
   to 5.0.0/6, cloud build, shared-signing
   verification, phone checklist). The P6 build gate
   remains blocking for ANY device claim: compile this
   tree, verify the APK's `BuildConfig.GIT_COMMIT`
   equals the P5 commit, and verify install-over
   behaviour before any phone run. If the cloud build
   fails, §11 names the first suspects.

**Stays OUT of Phase 6's polish scope (unchanged by
P5):** any change to night law, bars, gates, cadence,
special issues, voices, FCM/Play, Bale, or crons; any
re-litigation of P3/P4 presentation; any claim that
lab contrast or lab font checks pass G2/G5. The
Bookmarks heading gap (§3(b)) and the chart-values
question (§3(d)) are walk findings to record, not
reasons to delay the build — unless the walk itself
fails them.
