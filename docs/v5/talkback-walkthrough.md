# ECO JOURNAL — TalkBack walkthrough script (v5, reusable)

Status: **SCRIPT ONLY — the walkthrough itself has NOT been
performed.** It can only be performed by Yasin on his phone:
TalkBack on, swipe navigation, a walk log, and a screen
recording. A claim without the log + recording fails gate G5,
as in the v4 plan and the v5 program plan §3 Phase 5. This
file is the reusable script; the performed run's log goes in
the P5/P6 report (or a dated evidence file), never by editing
the expected announcements below to match a failed run.

## 0. Setup (do once, record the answers)

1. Phone: model + Android version + ECO JOURNAL build
   (Diagnostics line 1: version, code, git commit, edge URL).
   If the fingerprint is not the v5 build, STOP — the walk
   is evidence about the wrong build.
2. Turn TalkBack ON (Settings → Accessibility → TalkBack).
   Use TalkBack's own gestures: swipe right = next element,
   swipe left = previous, double-tap = activate, swipe up
   then right = TalkBack local context menu (headings list).
3. Start a screen recording BEFORE step 1 of §1. Keep it
   running for the whole walk; narrate nothing over it —
   the announcements are the evidence.
4. Font scale for §6: the in-app slider (Settings →
   «اندازهٔ فونت مقاله») at 0.85×, then 1.0×, then 1.3×.
   Also note the phone's SYSTEM font size (Settings →
   Display → Font size), because the bottom bar follows
   the system scale, not the in-app slider (see §6).

## 1. Feed — masthead, ticker, sync row, cards, tabs

1. Open the app on the feed. Swipe to the masthead.
   EXPECTED: heading «ECO JOURNAL» (TalkBack heading
   navigation must land on it).
2. Swipe to the ticker strip.
   EXPECTED (topics present): «نوار اخبار زندهٔ اتاق جنگ:
   …» — the full topic text, read ONCE, not repeated as
   the marquee loops. EXPECTED (no topics): «در حال حاضر
   موضوع زنده‌ای در اتاق جنگ نیست» on the darker red strip.
3. Double-tap the ticker pause button.
   EXPECTED: button announces «توقف نوار اخبار زنده»,
   then the strip stops moving; double-tap again announces
   «ادامهٔ نوار اخبار زنده» and motion resumes.
4. Swipe to the sync row.
   EXPECTED: the status sentence (e.g. «هنوز همگام‌سازی
   موفقی ثبت نشده است؛ فقط مقالات کش‌شده نمایش داده
   می‌شوند.» when no sync ever succeeded) + the
   «همگام‌سازی» button + «تلاش دوباره» when failed +
   «جزئیات همگام‌سازی» (Diagnostics). A failed sync must
   be heard in full, never cut off mid-sentence.
5. Swipe through the first hero card and two row cards.
   EXPECTED per card: «مقاله: {title}» as a button with
   the «باز کردن مقاله» action; kicker, summary, and
   byline follow as plain text. A typed (red-bar) card
   additionally announces its format badge as «قالب:
   {badge}» — category is never colour alone.
6. Swipe to the section tabs.
   EXPECTED per tab: «بخش {label}» with selected state
   («selected» on the current tab only).
7. If the feed is empty, swipe through the whole empty
   state top to bottom.
   EXPECTED, in order: the cause line (no-sync /
   offline-with-code / error-with-code / seed-failed),
   «تلاش دوباره», «جزئیات همگام‌سازی», «بخش‌های
   روزنامه», «بسته‌ها • نسخهٔ شبانه • تحلیل • روش کار»,
   «مشاهدهٔ همهٔ بخش‌ها». Nothing below the fold may be
   unreachable: at 1.3× the empty state scrolls (§6).
   FAIL if any element is announced but cannot be
   reached, or reachable but announces nothing.

## 2. Settings — slider, merged switches, headings

1. Open Settings. Open TalkBack's headings list.
   EXPECTED headings, in order: «اندازهٔ فونت مقاله»,
   «پوسته», «ابزارهای مطالعه» (reader helpers title),
   «اعلان مقالات تازه»,
   per-service notification heading, «ساعات سکوت»,
   «به‌روزرسانی برنامه» (app_update_title).
   FAIL if heading navigation skips a section.
2. Swipe to the font-size slider.
   EXPECTED: «اندازهٔ قلم» + the current value read
   from the value line above it («اندازهٔ فونت: …×»).
   Drag the slider (TalkBack slider gesture) from 0.85×
   to 1.3× and confirm the preview paragraph grows.
3. Swipe through the four reader-helper switches.
   EXPECTED per switch, as ONE node each (this is the
   P5 fix — before P5 these announced as bare switches):
   «نوار اخبار زنده زیر نام اپ، switch، on/off»,
   «کاهش حرکت (نوار ثابت به‌جای متحرک)، switch، on/off»,
   «خط‌کش مطالعه، switch، on/off»,
   «حالت آرام مطالعه، switch، on/off».
   FAIL if any switch announces without its label.
4. Swipe through the per-service notification switches.
   EXPECTED: each announces its service label + state
   (merged node, pre-existing Phase 7 pattern).
5. Swipe to the update auto-check switch.
   EXPECTED: «بررسی خودکار نسخهٔ تازه، switch، on/off»
   as one node (P5 fix).
6. Toggle reduced motion ON here, then go back to the
   feed and repeat §1 steps 2–3.
   EXPECTED: the ticker renders as STATIC text (no
   marquee at all) and NO pause button is offered —
   under reduced motion there is nothing to pause.
   Toggle it back OFF at the end of the walk.

## 3. Reader — headline, bookmark, ruler, arc

1. Open any article.
   EXPECTED: the headline is a heading («{title}» via
   heading navigation), then byline, then body text.
2. Swipe to the bookmark control.
   EXPECTED: «ذخیره نشده است» (or «ذخیره شده است»
   when saved) + the button text «ذخیره شود» (or
   «از ذخیره‌شده‌ها حذف شود»). Toggle it and confirm
   the announcement flips.
3. If the reading ruler is on, swipe to it.
   EXPECTED: «خط‌کش مطالعه؛ با کشیدن عمودی جابه‌جا
   می‌شود». Drag it vertically and confirm the body
   does not reflow.
4. If the story has ≥ 2 versions, swipe to the arc strip.
   EXPECTED: «سیر داستان» + «نسخه‌های این داستان به
   ترتیب زمان: …» + per-version buttons «باز کردن
   نسخه: {title}».
5. Open a cached article with an empty body (only if a
   real one exists — never fabricate one by deleting a
   body).
   EXPECTED: «متن این مطلب در حافظهٔ دستگاه نیست؛
   همگام‌سازی دوباره ممکن است آن را بیاورد.» + retry.
   FAIL if the page is near-blank with no line.

## 4. War Room, Market, Bundles, Nightly, Analysis, Bookmarks

1. War Room: heading «اتاق جنگ» first; SITREP section,
   radar layers. EXPECTED per layer header: «بستن لایهٔ
   رادار {n}» / «باز کردن لایهٔ رادار {n}» as a button
   with expanded/collapsed state; per signal story
   button: «باز کردن داستان: {title}».
2. Market: heading «بورد بازار»; the history chart.
   EXPECTED: «نمودار تاریخچهٔ بازار؛ با لمس نمودار
   نزدیک‌ترین برداشت انتخاب می‌شود و جزئیات آن در کارت
   زیر نمودار خوانده می‌شود.» — then the snapshot card
   below (plain text) and prev/next buttons MUST be
   reachable and MUST carry the actual figures, because
   the chart itself does not speak values. If the seed
   line shows, EXPECTED: «این ارقام از بستهٔ همراه
   برنامه‌اند (تازه‌ترین دادهٔ بسته: …) و هنوز با
   همگام‌سازی زندهٔ بازار به‌روز نشده‌اند.»
3. Bundles: heading «بسته‌ها»; unpublished state
   EXPECTED: «بخش بسته‌ها هنوز منتشر نشده است؛ فایلی
   برای دریافت ندارد.» + «بازبینی وضعیت از سرور»
   (re-check, never a retry that re-fetches a known
   404). Frozen special issues EXPECTED: the single
   line «ویژه‌نامه‌ها — متوقف است», nothing else.
4. Nightly: heading «نسخهٔ شبانه»; empty EXPECTED:
   «هنوز هیچ نسخهٔ شبانه‌ای منتشر نشده است».
5. Analysis: heading «تحلیل»; empty EXPECTED:
   «هنوز تحلیلی منتشر نشده است».
6. Bookmarks: empty EXPECTED: «هنوز چیزی ذخیره
   نکرده‌اید.» An orphan row EXPECTED: «این مقاله دیگر
   در دسترس نیست.» + its reason («از منبع پس گرفته
   شده است.» or «دیگر در حافظهٔ دستگاه نیست.») +
   «حذف». NOTE (known gap, P5): Bookmarks has no
   heading landmark of its own — the bottom-bar tab
   label is its landmark. Record whether that matters
   in practice during this walk.
7. Sections hub (from Settings): heading «بخش‌ها» +
   four buttons (bundles, nightly, analysis, method).
   EXPECTED: NO voices / special-issues destination is
   offered here — they stay gated placeholders in
   Settings only. FAIL if the hub names them.

## 5. Invite gate + Diagnostics (only if reachable in this run)

1. Invite gate (fresh install only): heading «ورود با
   کد دعوت», labelled code field («کد دعوت»), «تأیید
   کد» button; a wrong code EXPECTED: «کد واردشده
   معتبر نیست. دوباره بررسی کنید.» — never which part
   mismatched.
2. Diagnostics: heading «تشخیص و وضعیت برنامه»;
   fingerprint line FIRST («ساخت: نسخهٔ … (کد …) •
   گیت … • لبه: …»); per-section lines with
   required/optional markers; the global-clock scope
   line («ساعت سراسری بالا فقط بخش‌های الزامی …»).
   Copy + paste the report elsewhere and confirm the
   pasted text equals what TalkBack read (copy parity).

## 6. Font extremes (same walk, three passes)

Repeat §1 steps 1, 4, 5 and §2 step 2 at each in-app
font scale — 0.85×, 1.0×, 1.3× — and record per scale:
masthead fits on one line, hero/row cards wrap without
overlapping, the sync row never pushes the feed off
screen, the feed empty state scrolls to its hub button,
the bottom-bar five labels each fit on one line.
THEN repeat the bottom-bar check at the phone's SYSTEM
font sizes (small / default / large / largest): the bar
ignores the in-app slider (12sp token, no fontScale
parameter — MainActivity.kt BottomBar), so only the
system scale can break it. At the largest system size,
record for each of the five labels (صفحهٔ اصلی، اتاق
جنگ، بازار، ذخیره‌شده‌ها، تنظیمات) whether it fits,
ellipsizes, or clips — a clipped label is a FAIL even
if the layout does not overflow.

## 7. Motion (same walk, three states)

1. Ticker ON, reduced motion OFF (default): marquee
   runs; pause button pauses/resumes (§1 step 3).
2. Ticker ON, reduced motion ON (§2 step 6): static
   text, no pause button, no infinite motion anywhere
   on the feed (pager swipe animation is user-initiated
   and bounded — it is not infinite motion).
3. Ticker OFF (Settings switch): no ticker strip at
   all under the masthead; the double rule follows the
   wordmark directly.
Record all three states; a marquee that runs while
reduced motion is ON is a FAIL.

## 8. Contrast measurement protocol (for gate G2)

Lab ratios (verify_v5p5.py, WCAG formula from hex) do
NOT pass G2. On device, for each scale in §6:

1. Screenshot the feed (hero + rows), the reader, the
   ticker strip, a typed (red-bar) card, an energy
   (yellow-bar) card, and Settings — in light, dark,
   AND sepia themes (change theme in Settings).
2. For each screenshot, sample the actual rendered
   foreground/background pixels with a colour picker
   (the rendered colour, not the hex constant — theme
   overlays and night mode can shift it) and compute
   the WCAG ratio.
3. Pairs to measure (body ≥ 4.5:1, large ≥ 3:1):
   body ink on paper, navy headline on paper, grey
   byline on paper, white on ticker red, white on
   ticker empty red, black on yellow bar, white on red
   bar, black on orange badge (L3), white on grey
   badge (L1), error text on paper, sync-row grey on
   paper — in EACH theme.
4. Record the measured value per pair per theme in the
   walk log. Any body pair < 4.5:1 on device is a FAIL
   even if the lab ratio passed.

## 9. What the log must contain (or the walk does not count)

- Build fingerprint verbatim + phone model/Android.
- Per-section table: step → heard announcement →
   pass/fail (one line each; failures quoted verbatim).
- Font-scale table (§6) + system-font bar table.
- Motion states (§7) + contrast measurements (§8).
- The screen recording file name.
- Anything TalkBack announced that this script did NOT
  predict (surprises are findings, not noise — quote
  them verbatim).

## 10. Known P5 lab limits (do not let the walk hide them)

- The walk was never performed in the lab; this script
  is the instrument, not the evidence.
- Bookmarks has no heading landmark (see §4 step 6).
- The market chart announces its interaction, not its
  values — the snapshot card is the accessible data
  (§4 step 2). If the card is ever unreachable, the
  chart is a FAIL, not a partial pass.
- Merged switch rows (§2) change TalkBack focus order
  (label + switch become one stop); the walk must
  confirm no switch became unreachable by merging.
- The bar's 12sp token ignores the in-app font slider;
  only the system-scale check (§6) can break it.
