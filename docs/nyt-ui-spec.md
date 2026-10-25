# Newspaper front-page UI spec — ECO JOURNAL app (LAB, phase 2)

> Inspiration only: a classic broadsheet front page (large masthead,
> section tabs, hero story, ruled text rows). No other publication's
> name, logo, trademark, masthead or content appears anywhere in
> this app. The app name stays **ECO JOURNAL** and every headline,
> summary and body paragraph is verbatim from the existing Room
> cache. Nothing in this redesign invents article text.

## Masthead
- Top of the home screen, centred: `ECO JOURNAL` in large bold
  navy Vazirmatn (32sp), on flat paper background.
- Directly underneath: a double rule (2dp line, 2dp gap, 1dp line,
  in ink colour). Same double rule is reused under a reader header.
- Search field and the existing sync button/state sit below the
  masthead, before the tabs, so they are always reachable.

## Tabs + pager (the "smooth tabs" requirement)
- A `ScrollableTabRow` lists sections: **همه** first, then every
  distinct category actually present in the Room cache, in the
  order it first appears in the cache (publishedAt DESC).
  Labels are the cached `categoryFa` values; no section is
  invented. In the current seed data the expected categories are
  the cached ones (general, geopolitics, energy, wallstreet,
  business, rial, with their Persian labels) — the code does not
  hard-code that list, so if the cache changes, the tabs change
  with it.
- The tab row is synced to a `HorizontalPager`
  (`androidx.compose.foundation.pager`, `rememberPagerState`,
  `beyondBoundsPageCount = 1` for neighbours):
  tapping a tab calls `pagerState.animateScrollToPage(index)`
  inside a coroutine scope; swiping the pager updates
  `selectedTabIndex` from `pagerState.currentPage`.
- While a search query is present, the pager is replaced by a
  flat search-results list (offline Room search only); clearing
  the query restores the pager.

## Article hierarchy on a section page
- **Hero (first article on the page):** kicker
  (`categoryFa`, bold navy, 12sp; short cards prefix it with
  `گزیده`), large bold headline 26sp / 34sp line height,
  summary 16sp / 24sp, byline (source • cached date, 12sp grey),
  then the double rule.
- **Following articles:** text rows, no card, no shadow, no
  elevation. Kicker, bold headline 18sp / 26sp (max 3 lines),
  summary 15sp / 22sp (max 3 lines), byline 12sp, then a thin
  light-grey rule (`outlineVariant`, 1dp) between rows.
- Empty section: honest text only — "در این بخش هنوز مقاله‌ای
  در حافظه نیست." No placeholder articles, ever.

## Reader
- Kicker, large bold headline 28sp × fontScale (line height
  ×1.35), byline block, double rule, then the summary in bold
  (when present) and verbatim body paragraphs at
  18sp × fontScale with 1.75× line height. The existing
  `fontScale` setting is respected throughout.
- Bookmark button and original-source button are unchanged in
  behaviour; the source URL is still verbatim from the cache.

## Colours (all themes keep the same hierarchy)
- Light: paper-white background/surface, ink-black text
  (`#121212`), navy primary `#0F2A44`, grey bylines `#5A5A5A`,
  rules `#E8E8E8` / `#D8D8D8`.
- Sepia: paper `#FBF3DF`, ink `#2B2118`, primary `#5C4321`,
  rules `#E8DCBE` / `#D8C9A8`.
- Dark: dark paper `#121212` / surface `#1E1E1E`, ink `#F2F2F2`,
  primary `#9DB8D6`, rules `#2A2A2A` / `#3A3A3A`.
- Font everywhere: Vazirmatn (Regular + Bold, OFL). Layout is
  RTL throughout, as before.

## What was relaxed vs what was kept
- **Relaxed (at the user's explicit instruction, 2026-10-25):**
  the old lightweight budget — download under 12 MB and cold
  start under 1.5s on a mid-range phone — no longer binds.
  Keeping neighbouring pager pages composed (`beyondBoundsPageCount
  = 1`) and the foundation pager dependency are accepted costs
  for smooth swiping. Heavy is OK if it buys smoothness.
- **Kept:** offline-first (Room cache, offline search, sync
  button/state), verbatim article text, RTL, Vazirmatn, market
  screen, bookmarks screen, settings screen, notification
  deep-link to `article/{id}`, and the frozen live platform:
  nothing here posts to any channel or touches production.

## Bottom navigation
- Now: صفحه اصلی / بازار / ذخیره‌شده‌ها / تنظیمات.
- The old `categories` route is kept but delegates to the home
  front page, rather than being deleted, so any code that
  references that route still lands somewhere sensible.
  `CategoriesScreen.kt` itself is left untouched (its rows now
  render in the same newspaper style via the shared card).

## Verification status (honest)
- All changed Kotlin files were checked for brace/paren balance
  (script, not a compiler). **No compile is claimed:** no
  working Gradle build exists in this sandbox (see
  `docs/apk-build-phase3.md`); the GitHub Actions cloud build
  is the verifier for this redesign.
