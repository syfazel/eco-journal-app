# M8 notes — polish, icon, accessibility, diagnostics (lab only)

## Launcher icon (final, not placeholder)
- New mark drawn for all densities (mdpi 48 → xxxhdpi 192, plus
  `ic_launcher_round` copies): navy `#0F2A44` full-bleed background,
  white serif "EJ" centred, gold double rule underneath echoing the
  app masthead rule. No other publication's name, logo, or mark
  anywhere; re-grep for NYT name/logo before any release build.
- The old icon was a 2-colour navy/gold placeholder (solid gold
  centre square). The new icon keeps the same navy/gold palette so
  the app still reads as ECO JOURNAL at a glance.

## Gate loading fix (the known M7 limitation)
- `MainActivity` now collects settings with `initial = null`, so
  "no value yet" is distinct from "stored inviteOk=false". The
  invite gate is shown only after a real DataStore emission; before
  that, a centred spinner + "در حال بررسی دسترسی…" is shown.
  A returning reader no longer flashes the code-entry screen.
- A corrupt DataStore file still falls back to defaults via the
  M5 `.catch` (that emission is real, so loading still ends).

## Strings / digits (P2-01)
- Moved to `strings.xml`: settings headings, market sentences
  (title, loading, unavailable + detail, retry, latest/age/id,
  stale, compare, counts, formulas title/empty/value/uncomputable,
  inputs, change/no-change), section tab descriptions, diagnostics
  strings, gate-loading text, masthead brand (`app_name`).
- Deliberately left in code: the 13 market price-field labels
  (domain labels, not sentences), the "•" bullet before key points,
  and data-driven text (titles, bylines, formula descriptions).
- Persian digits: market prices, counts, sync counts, slider value,
  diagnostics counts/version-code and formatted dates all pass
  through the Persian-digit formatters. No Latin digits remain in
  user-facing numeric UI found by grep.

## Decisions recorded (explicit, not half-built)
- **Hero summary cap: 6 lines** (ArticleCard hero, vs 3 lines for
  rows). The plan asked for an explicit decision; the M3 6-line cap
  is kept as the design decision, not left uncapped.
- **Pull-to-refresh: DEFERRED.** The feed already has a sync button
  with progress + retry; a half-built pull gesture would duplicate
  it. Revisit only if a pilot reader asks for it.
- **Share action in reader: DEFERRED** for the same reason (plan
  §4 made it conditional on approval; no approval was given, so it
  is not built).
- **Jalali (Persian-calendar) byline dates: DEFERRED.** java.time
  has no Persian chronology in the stdlib, and a hand-rolled
  Jalali conversion is error-prone calendar code. Bylines keep
  their shipped date strings for vNext; a Jalali formatter needs a
  tested library or a verified algorithm, not a guess.
- **Feed header: fixed** (masthead + search + sync stay above the
  pager; no collapsing header was built).

## Empty / error / offline states audit (all designed, none blank)
- Feed: seeding spinner (only while seeding with an empty cache),
  empty-cache message, per-section empty message, search zero
  results with query echo + clear, sync failure/partial/not-
  configured messages with retry.
- Reader: not-found page with back + retry + fetchOne fallback;
  slow cache read is never mistaken for "not found".
- Market: loading / error + retry / stale / empty-formulas states,
  all distinct; null prices show "—".
- Bookmarks: empty hint, orphan rows with prune action.
- Settings: no empty state needed (every control is wired); the
  diagnostics entry is the new state surface.
- Invite: fixed error string, no hints about valid codes.

## Accessibility — checked statically (TalkBack device walkthrough still open)
- Present and verified by grep: tab content descriptions
  ("بخش …"), article-card descriptions ("مقاله: …"), reader back
  + bookmark descriptions, search-clear description, bottom-nav
  labels as descriptions, notifications Switch description (M8).
- Touch targets: sync button 48dp (M3), market retry 48dp (M8),
  settings theme/notification/invite/diagnostics buttons 48dp
  min (M8), IconButtons are 48dp by default, NavigationBar items
  are full-width targets.
- Font scale applies to feed, market, settings, reader, and
  diagnostics (all new M8 text uses `fontScale`).
- NOT yet done (needs a real device, no emulator here): TalkBack
  traversal order through feed → reader → market, contrast
  measurement of sepia/dark palettes, and font-scale extremes
  (0.85× / 1.3×) on small screens. Recorded for the M9 pilot
  checklist, not claimed as passed.

## Diagnostics screen (plan §2.6)
- Route `diagnostics`, reached from Settings; bottom bar hidden
  there like on the reader route. Shows: app version + build
  number (from PackageManager, not a hard-coded string), cached
  article/market/formula/bookmark counts, seed version, edge URL,
  last successful sync, last sync attempt, last fixed error code,
  and index-hash present/absent (value never shown).
- Actions: sync retry (same fixed-string outcomes as the feed
  button) and copy-diagnostics (copies exactly the lines shown).
- Deliberately absent: invite code/hash, FCM token, device id,
  article content, any raw exception text. New DAO methods
  (`articleCountFlow`, `bookmarkCountFlow`) are query-only, so
  no Room migration was needed for M8.
