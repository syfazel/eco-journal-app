# TalkBack walkthrough script — Phase 7 phone checklist (PHONE ONLY)

Status: NOT RUN. TalkBack can never be passed by code inspection.
Run on Yasin's phone, on the v4 APK, with TalkBack on. For each step:
do it, then log PASS / FAIL + a 10-second screen recording or a note
of exactly what TalkBack announced. One FAIL stops nothing else — log
it and continue; the fix list goes back to the lab.

Setup (do once, before step 1):

- S0. TalkBack ON (Settings → Accessibility → TalkBack). Font size:
  first pass at the phone's current size, second pass at the largest
  size the app offers (1.3x in app settings), third pass at 0.85x.
- S1. Phone online, then the same walkthrough once in airplane mode
  (offline leg, steps O1–O4 at the end).
- S2. Have at least one bookmark, one saved bundle, and one read
  article before starting, so empty states are not the only thing seen.

## Feed

- T1. Land on the feed. TalkBack should announce the masthead and the
  section tabs. Swipe through all 8 tabs: each tab name must be
  announced in Persian (no Latin key, no raw category name).
- T2. On a typed card: the format badge must announce "قالب: …"
  (Persian), the crisis chip (if any) must announce "سطح بحران …",
  and the bar must announce its label — RED for a typed/war-room item,
  YELLOW for an energy item, red winning when both apply. A card with
  NO bar must announce no bar at all (never "no bar").
- T3. Urgent rail: swipe the rail cards. A pin older than 6 hours must
  announce "پین منقضی (بیش از ۶ ساعت)". A fresh pin must NOT.
- T4. Filter chips: each chip must be at least a fingertip tall and
  announce its filter name; the sync-status line must not steal focus
  from the chips.
- T5. Open an article from the feed. The reader header must announce
  the bar label (same RED/YELLOW rule as T2). Back must return to the
  same feed position, not the top.

## War Room

- T6. SITREP rows: each row announces its role (button) and opens the
  detail when double-tapped. No row may be announced as plain text.
- T7. Radar layers: each layer header announces "باز کردن لایهٔ …" or
  "بستن لایهٔ …" matching its actual state (open headers say "بستن",
  closed headers say "باز کردن"). Expand and collapse layer 2.
- T8. Radar rows: the "open story" button on each row must announce
  the story title, not a repeated identical "باز کردن داستان". Two
  different rows must sound different.
- T9. Severity filter chips (all severities + each grade): each
  announces its grade; the selected one announces as selected.
- T10. Failed-state leg (force it: airplane mode + pull refresh on the
  war-room section, or wait for a failed sync): the error line must
  name the section in Persian ("اتاق جنگ" / "رادار"), never the raw
  key (`radar`, `channel_typed`), and the retry button must be
  reachable and announce "تلاش دوباره".

## Nightly, Bundles, Bookmarks, Channel Reader

- T11. Nightly: search row, latest-edition row, dossier rows, edition
  detail rows and dossier-edition rows — each announces "باز کردن …"
  with its own title. An edition row must NOT announce a RED/YELLOW
  bar (editions aggregate categories; a single bar would mislabel
  them). A dossier-edition row likewise.
- T12. Bundles: bundle rows announce "باز کردن بستهٔ …" with the bundle
  title; no bar on bundle rows (same aggregation rule). Last-night
  leftover headlines DO announce RED (they are typed items).
- T13. Bookmarks: saved-bundle rows announce "باز کردن بستهٔ …";
  saved-article rows announce "باز کردن …" with the article title.
- T14. Channel reader: timeline rows announce "باز کردن …" with the
  item title; the reader header announces the bar label (RED/YELLOW
  rule). Back returns to the timeline position.

## Market

- T15. Market title announces as a heading. The chart announces what
  it is ("نمودار تاریخچهٔ بازار") and does NOT claim to be
  interactive — the accessible data is the selected-snapshot card
  (plain text) plus prev/next.
- T16. Prev/next buttons announce "قبلی" / "بعدی" in Persian and move
  the selected snapshot; the card text changes to match.
- T17. Failed-state leg: with market sync failed, the tab shows a
  failed line naming "بازار" in Persian, and refresh retries it. The
  tab must NOT show a stale board as if it were fresh — the failed
  line is the honesty marker.
- T18. At 1.3x font: the chart card, the snapshot card and the formula
  rows must not clip or overlap. At 0.85x: no row may collapse below
  a fingertip target.

## Analysis, Method, gated placeholders

- T19. Analysis rows announce as buttons and open the detail. In the
  detail, source links must actually open the news source (never the
  blog) when double-tapped.
- T20. Failed-state leg: with nightly sync failed, Analysis shows a
  failed line naming the nightly section in Persian; stale analysis
  items below it must be recognisable as stale by that line.
- T21. Method page: scrolls fully at 1.3x; all 7 sections reachable.
- T22. Voices placeholder: scrolls fully at 1.3x; announces only the
  §3.6 placeholder text — no name, no count, no example voice.
- T23. Special Issues placeholder: scrolls fully at 1.3x; announces
  only "ویژه‌نامه‌ها — متوقف است".

## Settings and notifications

- T24. Settings: every switch announces together with its label
  (double-tap toggles the labelled setting, not an anonymous switch).
  The font-size slider announces its description and its current value
  in Persian digits. Theme names announce in Persian.
- T25. Quiet-hours and mute-urgent switches announce their labels.
- T26. Trigger a notification (or read the last one): counts announce
  in Persian digits, never Latin digits.

## Offline leg (airplane mode, after the online pass)

- O1. Feed opens from cache; the offline line names the last sync time
  in Persian. No section goes blank.
- O2. A failed sync in airplane mode does NOT clear the cache.
- O3. Retry buttons (war-room, bundles, market, analysis) are reachable
  and announce "تلاش دوباره"; tapping one in airplane mode fails
  honestly (failed line stays) instead of pretending success.
- O4. Back online: one refresh restores the failed lines to fresh data.

## Contrast and target leg (measure, don't eyeball)

- C1. Yellow bar chip (black text on yellow) and red bar chip (white
  text on red): measure on-device; both must clear AA (4.5:1).
- C2. WATCH badge and crisis L3 chip (black text on orange): measure;
  must clear AA.
- C3. Every tappable row/button in steps T1–T26: fingertip target
  (≥48dp). Flag any row that is hard to hit at 1.3x.

## Sign-off

- All steps PASS → Phase 7 phone leg done; log + recording filed under
  `docs/big-update/evidence/phase7/` with the date.
- Any FAIL → file the exact announcement/behaviour, return to the lab
  with the step number; Phase 7 is not done until the re-run passes.
- E7 (test-channel leg) and E8 (install-over leg) are NOT part of this
  script; see `e1-e8-reverification.md`.
