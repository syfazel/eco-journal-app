# ECO JOURNAL — Version 5.6.0 (code 12): Yemen-tab fix — publish report

Date: 2026-11-18 (Asia/Tehran). Parent task: implement the Yemen-tab
fix (R1–R6) and publish v5.6.0/code 12. HEAD before: fa7ec0a (v5.5.0/
code 11). This report is LAB + PUBLISH evidence only — the phone test
on Yasin's device is still pending (see §7).

## 1. Root causes (re-checked before changing — all confirmed)

- **R1 — most likely: Yasin is still on 5.1.** Phone test was never
  done; 5.1 has NO Yemen tab at all. No code fix helps a phone that
  never updated. Fix: 5.6 ships **mandatory: true**, and the update
  banner now renders ON the Yemen tab (AppUpdateCompactRow), not
  only in Settings — a 5.1/5.5 phone cannot miss it.
- **R2 — invite gate blocked the whole app.** MainActivity.kt
  (old lines ~133, ~161–184): fresh install / no valid invite →
  full-screen InviteScreen, Yemen never visible. Fix: the gate now
  guards ONLY the Broadcast destination; Yemen + Settings are open
  without a code. Decision taken per task: Yemen is public
  dashboard data, no PII.
- **R3 — empty-state trap.** YemenScreen.kt (old ~165–189): seed/sync
  failure → infinite spinner + «بذر در حال بارگذاری است», no retry,
  no error, no diagnostics link. Fix: retry button (re-runs the
  seed AND a forced sync), last error code in Persian, Diagnostics
  link.
- **R4 — archive gaps (real).** AdenTab showed verdict/ring/timeline/
  layers/contradictions but dropped aden.images (7), aden.crossCheck
  (7), aden.sources (28 vs the 25 on TimelineTab),
  aden.channelsOfficial (5), socialPosts (9), amplifiers (4),
  seenNoteFa, taizNoteFa. ArchiveTab was a read-only list of the 1
  dashboard snapshot. Fix: generator + Yemen.kt + Room carry all of
  them; local snapshots (create/view-frozen/delete) in new Room
  tables; dashboard version history v1–v5 listed.
- **R5 — map was bare.** Frontlines/arrows (circle heads, not
  arrowheads)/unit squares/dots, no legend, no Aden ring, no labels.
  Fix: triangular arrowheads, dashed 5-slot Aden ring coloured by
  segment status, Aden + key-point labels, legend; ±10 km warning
  stays under the map.
- **R6 — refresh button was weak.** Small «به‌روزرسانی» button; the
  force-refresh mechanism itself worked (EdgeRepository.kt:418 +
  UpdateEngine.kt:126–130). Fix: full-width «به‌روزرسانی لحظه‌ای»
  button + Jalali success time (or Persian failure + retry).
  YemenSyncWorker 24h unchanged.

## 2. What changed (commits)

- `4bf825d` — code + edge: R1–R6 implementation, version 5.6.0/12,
  Room 11→12 (five Aden JSON columns + yemen_snapshots /
  yemen_snapshot_places), BundledSeed v3 (Aden re-seed on new
  bundle), regenerated yemen.json (hash fcd61c83…, seed identical),
  `edge/verify_v5p6.py` (new, 85 checks).
- `b9db682` — edge only: `edge/out/app-debug-v5.6.apk` + version.json.

Files: MainActivity.kt, YemenScreen.kt, Yemen.kt, AppDatabase.kt,
DatabaseProvider.kt, EdgeRepository.kt (snapshot create/delete),
BundledSeed.kt, DiagnosticsScreen.kt (local-snapshot count line),
strings.xml, app/build.gradle.kts, .github/workflows/android.yml
(verify now names the v5.6 APK), edge/generate_yemen.py,
edge/verify_yemen.py, edge/verify_v5p6.py, edge/out/{yemen,manifest,
health}.json, seed/yemen.json. No FCM, no new worker, no channel
posts, no Play Store. YemenSyncWorker behaviour untouched.

## 3. Build + D18 verify (cloud)

- First run `44262886767`: Build debug APK **success**, Upload
  **success**; only the manifest-verify step failed — expected
  first-pass failure (no v5.6 APK committed yet; the gate working
  as designed). Identity-only step skipped.
- Cloud APK (run 44262886767 artifact): **22,180,767 bytes**,
  SHA-256 `3940071eadafd9a22ee5f7354cfe1e1f4430c506cb15728ee4a8e7eba7b525cd`,
  aapt `com.ecojournal.app`, versionCode **12**, versionName
  **5.6.0**, minSdk 26, targetSdk 34. Signing cert SHA-256
  `45220b1509e14db5…` — **MATCH** with the shared debug cert (same
  as v5.5), so it updates over 5.1–5.5.
- D18 legs (local, after generate): leg 1 verify vs committed APK
  **PASS** (hash OK, aapt identity OK); leg 2 identity-only vs the
  fresh cloud APK **PASS**. `verify_yemen.py` **47 checks PASS**;
  `verify_v5p6.py` **85 checks PASS**.
- Second run `44263690982` (after the edge publish): **success** —
  cloud build fully green. Pages deploys `44262886746` and
  `44263690999`: success.

Local-build note (honest): a local `./gradlew assembleDebug` could
NOT run in this sandbox — the wrapper download timed out and the
local Gradle daemon could not connect (plus an approval for that
route timed out and was not retried). Compile authority is the
cloud build above (green, twice: build step + full second run),
as in previous phases.

## 4. Live verification (Pages, after deploy)

- Live `version.json`: `5.6.0` / `12`,
  `apk_sha256: 3940071e…b525cd`, `mandatory: true`,
  `apk_url: https://syfazel.github.io/eco-journal-app/app-debug-v5.6.apk`.
- Live APK download: 22,180,767 B, SHA-256 `3940071e…b525cd` —
  **MATCH** with version.json.
- Live `manifest.json` v4: yemen hash `fcd61c83…`, count 90.
- Live `yemen.json`: 90 places / 24 events / 8 frontlines /
  9 arrows / 12 units; Aden images 7 / cross-check 7 / official
  channels 5 / social posts 9 / amplifiers 4 / sources 28;
  1 snapshot («نسخهٔ ۵…», 90 places, 24 events).

## 5. Phone steps for Yasin (from 5.1 AND from 5.5)

**If the phone is on 5.1 (most likely — the primary cause):**
5.1 has no Yemen tab and no Yemen content at all. Open the app →
the update offer for **5.6.0** must appear (mandatory — no
«بعداً» button). If it appears on the home screen, tap
«دانلود»، then «نصب با نصب‌کنندهٔ سیستم» and allow install.
If no offer appears, go to **تنظیمات (⚙️)** → «به‌روزرسانی
برنامه» → «بررسی نسخهٔ تازه». If that also shows nothing,
report it first (do NOT sideload yet) — the offer itself is the
test. After install, the bottom bar has three tabs: **یمن**،
**پیام‌رسانی**، **تنظیمات**. Yemen opens WITHOUT any invite
code. Tapping **پیام‌رسانی** still asks for the code — that is
correct and unchanged.

**If the phone is on 5.5:** same offer (5.6.0, mandatory). After
install, Yemen keeps its cached data; the first launch re-seeds
the new Aden sections and the 24h sync picks up the new
yemen.json (hash changed). No code is needed for Yemen any more;
a revoked/missing code now only locks the پیام‌رسانی tab.

**Install note:** same package `com.ecojournal.app`, same shared
debug signature as 5.1–5.5, so 5.6 installs OVER the old app
(no uninstall, no data loss). If Android refuses with «برنامه
نصب نشد», report the exact message — that would mean a signature
mismatch, which the lab check above rules out.

## 6. What he should see on EACH of the 6 Yemen sub-tabs

1. **وضعیت و نقشه** — headline, faction-share bar (حوثی ۳۹ / دولت
   ۳۰ / مورد مناقشه ۱۶ / القاعده ۳ / شورای انتقالی ۱ / نامشخص ۱),
   «۲۴ رویداد • ۹۰ نقطهٔ کنترل», last-update line, full-width
   **«به‌روزرسانی لحظه‌ای»** button; after tapping it, either
   «به‌روزرسانی شد: <تاریخ و ساعت شمسی>» or a red failure line
   (same button = retry). Map: orange frontlines, arrows with
   **triangular heads**, unit squares, faction dots, **dashed
   ring around Aden** (red west, orange northwest/north, green
   east/south), labels «عدن» + key points (ذباب، باب‌المندب…),
   **راهنما** legend underneath, and the ±10 km approximation
   warning always under the map. Then human stats + key points.
2. **مرزهای درگیری** — 8 frontlines with status/summary/movement/
   confidence; 9 advance arrows; 12 unit positions.
3. **محاصرهٔ عدن** — verdict («عدن محاصره نشده، فقط از غرب
   تهدید شده» — as the dashboard states it), 5 ring segments,
   timeline, key points, three claim layers, contradictions,
   channels methodology + cross-check summary, **یادداشت تصاویر
   دیده‌شده** + **یادداشت تعز**, then the NEW sections:
   **تصاویر بررسی‌شده (۷)**، **راستی‌آزمایی با منابع مستقل (۷
   ردیف)**، **کانال‌های رسمی (۵)**، **پست‌های شبکه‌های اجتماعی
   (۹)**، **تقویت‌کننده‌ها (۴)**، and Aden's own **منابع (۲۸)**.
4. **پرونده‌ها و تحلیل** — forces (۷), military features,
   map reading, disagreements, open questions. (Unchanged.)
5. **خط زمانی و منابع** — claim filter chips, 24 events,
   document sources (۲۵). (Unchanged.)
6. **آرشیو نسخه‌ها** — first the dashboard snapshot («نسخهٔ ۵ —
   ارتقای رابط کاربری و آرشیو نسخه‌ها (۱۷ نوامبر ۲۰۲۶)»,
   ۹۰ نقطه • ۲۴ رویداد), then **تاریخچهٔ نسخه‌های داشبورد**
   v1–v5, then **اسنپ‌شات‌های محلی**: a label field + «ساختن
   اسنپ‌شات از وضعیت فعلی» button; each local snapshot shows
   its date/counts, «مشاهدهٔ نقاط» (frozen 90 points) and «حذف».

**Diagnostics should show** (تنظیمات → عیب‌یابی): version
**5.6.0 / 12** + git commit fingerprint, «نقاط یمن: ۹۰»،
«رویدادهای یمن: ۲۴»، «جبهه‌های یمن: ۸»، **«اسنپ‌شات‌های محلی
یمن: <تعداد>»** (0 until he creates one), seed version **3**,
Yemen section «up_to_date», last sync time, last error
(«—» when clean). If Yemen is ever empty, the tab now shows
«تلاش دوباره» + «آخرین خطا: <کد>» + «مشاهدهٔ عیب‌یابی» instead
of a bare spinner.

## 7. Still open / phone-pending

- The phone test itself (5.1 → 5.6 update offer, Yemen tab
  without a code, all six sub-tabs as in §6, Diagnostics lines).
  Nothing in this report claims a phone result.
- The shared broadcast password is still NOT chosen/told to
  members via Bale (pre-existing 5.5 item) — unrelated to 5.6.
