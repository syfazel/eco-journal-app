# قانون منبع‌یابی داده‌های یمن — Sourcing Rules (v6.1.0)

## قانون سردبیری (دستور یاسین)

نیروهای دولتی جبههٔ مقابل اساساً خبر دروغ منتشر می‌کنند — منابع آن‌ها
به‌هیچ‌وجه استفاده نمی‌شود. این قانون در `edge/sourcing.py` پیاده شده و
`edge/generate_yemen.py` آن را پس از نگاشتِ رونوشت‌به‌رونوشتِ داشبورد
اعمال می‌کند. هر جایگزینی روی یک زیررشتهٔ دقیق از اسنپ‌شات داشبورد
v5 (۲۰۲۶-۱۱-۱۸) قفل شده است؛ اگر زیررشته‌ای دقیقاً پیدا نشود، ژنراتور
از نوشتن سر باز می‌زند تا ادعای دولتیِ حذف‌نشده‌ای بی‌سروصدا عبور نکند.

## Editorial rule (English summary)

Government forces on the opposing front basically publish false news —
their sources are not used at all. Tier A (direct government-force
sources: official statements, spokespersons, army announcements, Saudi
state media, coalition spokesperson) is removed entirely. Tier B
(government-claim-only items relayed by wires — Reuters / AFP /
Al Jazeera relaying a government claim with no independent
corroboration) is removed entirely. Tier C (aligned but not official
outlets: South24, Sheba Intelligence, Yemen Monitor, Al-Jumhuriya,
Al-Ain): an item where such an outlet is the SOLE evidence for a
government claim is removed; where independent non-government evidence
also supports the item, the item is kept and the Tier C attribution is
stripped. Mentions of government forces as *actors* (e.g. "government
forces withdrew", as reported by Reuters / AFP / ISW) stay — they are
events, not sources. A Tier C outlet reporting a *Houthi* action is not
a government claim and is kept (see "kept mentions" below).

> توجه: فهرست دقیق آیتم‌های Tier A/B گزارش پژوهش در دسترس عامل داده
> نبود (در زمینهٔ عامل والد بود)؛ حذف زیر آیتم‌به‌آیتم از روی خود داده
> بازسازی و این‌جا کامل فهرست شده است تا با گزارش پژوهش قابل تطبیق باشد.
> Note: the research report's exact Tier A/B item lists were not in this
> data agent's context; the removal below was reconstructed item by item
> from the data and is fully enumerated here so it can be diffed against
> the report.

## چه چیزی حذف شد — What was removed (26 items removed entirely)

| بخش | آیتم | دلیل |
|---|---|---|
| events | ۱۸ — تغییر فرمانده نیروهای مشترک عربستان | اطلاعیهٔ رسمی دولت/عربستان (Tier A/B) |
| events | ۵ — عملیات «طلوع یمن» و ادعای بازپس‌گیری باب‌المندب | فقط ادعای دولت، به نقل رویترز/South24 (Tier B) |
| events | ۲۱ — کشته‌شدن فرمانده ایرانی در تعز | تنها منبع شبا (Tier C) |
| events | ۲۲ — نبردهای ۳۰ اکتبر؛ ادعای بازپس‌گیری حیفان/الجوف | تنها منبع شبا (Tier C) |
| events | ۲۰ — منبع تهی شد (نه حذف): پیشروی تا ۷۰ کیلومتری عدن | تنها منبع، منبع نظامی دولت بود؛ رویداد ماند، `source_removed_gov` خورد |
| sources (کلی) | Reuters ۵ اکتبر (تصرف ناحیهٔ باب‌المندب)؛ South24 تغییر فرماندهی سعودی؛ Shafaq «۲۰۱ حوثی خنثی شدند»؛ The Hindu جایگزینی فرمانده سعودی؛ Al Jazeera ادعای دولت در الجوف | رلهٔ ادعای دولت (Tier A/B) — ۵ مورد |
| sources (کلی) | South24 «جابه‌جایی نیروهای جنوب از باب‌المندب»؛ Sheba «تهاجم حوثی‌ها در لحج» | Tier C؛ موضوع با منابع مستقل هم پشتیبانی می‌شود، انتساب حذف شد — ۲ مورد |
| claims.contested | طارق صالح و مقاومت ملی؛ اردوگاه خرز در ۱۲ نوامبر؛ پهپادهای مکه و مدینه؛ ادعای تلفات دولت: ۹۰۶ عملیات و «خنثی‌سازی» ۲۰۱ حوثی | فقط ادعای دولت، بدون پشتوانهٔ مستقل — ۴ مورد |
| advance_arrows | arr-govt-maraziq — پیشروی به‌سوی بیر المرازیق (~۱۵ کیلومتر) | تنها منبع شبا (Tier C) |
| unit_positions | نیروهای سپر میهن (ادعای ۱۷ نوامبر المحاوله) | فقط ادعای دولت به نقل الجزیره (Tier B) |
| unit_positions | نیروهای دفاع شبوه و یگان‌های منطقهٔ سبأ | رلهٔ عرب‌نیوز از ادعای حملات دولت (۸۷۷/۷۰۳) (Tier A/B) |
| aden.timeline | ۲۰۲۶-11-17 — ادعای «۶۶۱ حمله در یک روز» | فقط ادعای دولت به نقل الجزیره (Tier B) |
| aden.contradictions | «۴. ارقام عملیات و تلفات دولت» (۲۰٬۰۰۰ / ۶۶۱ / ۴۸٬۵۷۶) | ارقام ادعایی دولت، راستی‌آزمایی‌نشده |
| aden.images | نقشهٔ شبا اینتلیجنس، ۲۵ اکتبر | تنها مدرک تصویر کنترل دولتی؛ حکم خودش هم «منسوخ» بود |
| aden.sources | South24 (×۲)، Sheba، Al Jazeera «۶۶۱ حمله»، Shafaq «۲۰۱ حوثی» | رلهٔ ادعای دولت / Tier C — ۵ مورد |

## منبع‌تهی‌شده‌ها — Sources nulled, item kept (5)

مکان/رویداد می‌ماند (جغرافیا می‌ماند)، منبع `null` می‌شود و پرچم
`source_removed_gov: true` + یادداشت فارسی
«منبع دولتی حذف شد — نیازمند منبع مستقل» می‌خورد:

- رویداد ۲۰ — پیشروی به‌سوی عدن (رقم ۷۰ کیلومتر فقط با قید «تنها از
  منبع نظامی دولت نقل شده و مستقل تأیید نشده است» مانده؛ تیتر دیگر آن
  را واقعیت نمی‌گوید)
- مکان ۴۹/۵۰ — جزیره حنیش بزرگ/کوچک (تنها مدرک: مقام مورد استناد AFP)
- مکان ۵۶ — اردوگاه رأس عباس، غرب عدن (تنها منبع: South24)
- مکان ۷۳ — الحیمه / الافج (تنها منبع: Sheba)

## بازنویسی‌ها — Reworded (headline + 8 groups)

- `headline_fa` (و رونوشت آن در snapshots): جمله‌های ادعای دولت
  (بازپس‌گیری حیفان و پیشروی الجوف در ۳۰ اکتبر؛ حملهٔ هوایی ۱۳ اکتبر
  به مرکز فرماندهی) حذف شد؛ رقم «۷۰ کیلومتری عدن» (منبع نظامی دولت از
  مسیر شین‌هوا) با «۶۰ کیلومتری» AFP جایگزین شد؛ «اعزام نیرو از عدن»
  با «ضدحملهٔ دولت در ۱۳ نوامبر» (ISW) جایگزین شد.
- رویدادها ۳/۴/۱۱/۱۲/۱۳/۱۵/۱۶/۱۹: حذف جملهٔ سخنگوی دولت (۱۳)، حذف
  ادعای پیشروی جبل دهیده (۱۹)، حذف ارقام تلفات South24 در خور عمیره
  (۱۵)، حذف جملهٔ «۹۰۶ عملیات و ۲۰۱ حوثی» و انتساب شفق (۱۶)، حذف
  انتساب South24 (۱۱/۱۲/۱۴)، حذف «House of Saud» (۴) و «Daily Sabah»
  (۳) از نام منبع.
- مکان‌ها ۳۱/۴۰/۴۱/۴۶/۴۸/۶۲/۶۳/۶۷: حذف انتساب‌های Al-Jumhuriya،
  Al-Ain، South24، Sheba و بیانیهٔ نیروهای جنوب؛ مدرک مستقل باقی است.
- claims.confirmed: حذف South24/نشنال/عرب‌نیوز از عقب‌نشینی ذباب؛
  استقرار السقیه به ISW/رویترز برگشت؛ جملهٔ رهگیری سخنگوی ائتلاف
  سعودی از شلیک‌های موشکی حذف شد.
- claims.contested «قاب‌بندی عقب‌نشینی»: نقل‌قول مستقیم سخنگوی دولت
  (سرهنگ وضاح الدبیش) حذف شد.
- frontlines (لحج/تعز/مأرب-جوف/حدیده/بیضا/صعده): حذف منابع South24،
  Sheba، Yemen Monitor، Shafaq، Arab News و Guardian (تک‌منبعِ
  بدبرچسب) و جمله‌های ادعای دولت (صبر الموادم، بیر المرازیق،
  ۸۷۷/۷۰۳ حمله، استاندار حدیده). جبهه‌های ضالع و ابین دست‌نخورده
  (منبع ادعای دولت نداشتند).
- aden: حذف جملهٔ «۶۶۱ حمله» از cross-check الجزیره؛ cross-check
  South24 و Sheba به Reuters/ISW برگشت؛ جملهٔ شبا از timeline
  ۱۲ نوامبر و layers؛ منابع aden دوباره شماره‌گذاری شدند (۲۸ ← ۲۳).

## ذکرهای باقی‌ماندهٔ Tier C — Kept mentions (with reasons)

این‌ها ادعای دولت نیستند، پس می‌مانند؛ اعتبارسنج خودکار آن‌ها را
گزارش می‌کند اما خطا نمی‌گیرد:

- **South24 (۱۰ مورد):** گزارش حملهٔ موشکی حوثی‌ها به اردوگاه
  العلالهٔ قعطبه (۴ نوامبر) در frontline ضالع + منبع آن + یگان
  کمربند امنیتی؛ پایش موشک‌های حوثی ۱–۱۲ نوامبر در timeline عدن؛
  گزارش South24 از «هنوز دست حوثی‌ها» بودن میون (۷ اکتبر) در
  key-points و disagreements؛ دو نقل‌قول که South24 می‌گوید خطوط را
  «نتوانستیم مستقل راستی‌آزمایی کنیم» (frontline لحج + open
  questions)؛ توصیف جغرافیایی السقیه در trace-note لحج.
- **شبا (۴ مورد):** گزارش حرکت تقویت‌های حوثی از خولان (پیکان
  arr-khawlan-sirwah، یگان تقویت‌ها، و military feature مسیر
  تدارکات) — گزارش اقدام حوثی‌ها، نه ادعای دولت. نام فرماندهٔ
  منتسب («ابو طاها الشرافی») حذف شد.
- **Daily Sabah (۱ مورد):** فقط در فهرست کلی منابع؛ مقالهٔ دیدار
  دیپلماتیک است، نه ادعای میدانی دولت؛ انتساب آن از رویداد ۳ حذف شد.
- رشته‌های زیر پس از حذف در کل سند **صفر** شده‌اند (اعتبارسنج
  بررسی می‌کند): Shafaq/شفق، Yemen Monitor، Al-Jumhuriya، Al-Ain،
  Arab News/عرب‌نیوز، The National/نشنال، «۶۶۱»، «۹۰۶»، «۸۷۷/۷۰۳»،
  «۲۰٬۰۰۰ حوثی»، «۴۸٬۵۷۶»، «House of Saud»، «وضاح الدبیش».

## طبقه‌بندی رویدادها — Event taxonomy (version 1)

هر رویداد در `yemen.json` سه فیلد دارد: `event_type`، `confidence`،
`front` (همهٔ رویدادهای یمن: `"yemen"`).

- `event_type`: `advance` | `retreat` | `airstrike` | `missile_drone`
  | `clash` | `claim` | `siege` | `humanitarian` — بر پایهٔ متن و منبع
  خود رویداد (جدول صریح در `edge/sourcing.py::EVENT_TAXONOMY`؛ رویداد
  تازه بدون طبقه‌بندی صریح، ژنراتور را متوقف می‌کند، نه حدس).
- `confidence` — درجهٔ **گواهی انصارالله** است، نه درجهٔ درستی:
  - `ansarullah-confirmed` — فقط وقتی بیانیهٔ سریع / المسیره /
    رسانهٔ نظامی آن را می‌گوید؛
  - `ansarullah-reported` — رسانهٔ انصارالله گزارش می‌کند، بدون
    بیانیهٔ سریع؛
  - `unverified` — بقیهٔ موارد، از جمله اقلام با منبع سازمان ملل.
- توزیع فعلی (۲۰ رویداد): advance ۹، missile_drone ۷، clash ۲،
  claim ۱، humanitarian ۱؛ ansarullah-confirmed ۲، unverified ۱۸،
  ansarullah-reported ۰.

## فیدها — Feeds

`edge/fetch_feeds.py` (فقط stdlib: urllib + html.parser) سه کانال
رسمی انصارالله را با GET ساده می‌خواند: `t.me/s/army21ye` (یحیی
سریع)، `t.me/s/MilitaryMediaY` (رسانهٔ نظامی)، `t.me/s/Almasirah_En`
(المسیره انگلیسی). فقط پست‌های واقعیِ خوانده‌شده وارد `feeds.items`
می‌شوند (متن عربیِ اصلی، بی‌کم‌وکاست؛ `tier: ansarullah-official`)؛
هرگز نقل‌قول ساخته نمی‌شود. کانالی که خوانده نشود در
`feeds.unavailable` با خطا فهرست می‌شود و تولید کل سند را خراب
نمی‌کند. توجه: صفحهٔ t.me/s فقط ~۲۰ پست اخیر را نشان می‌دهد؛ برای
Almasirah_En (پُرپست) پنجره فقط همان روز است.
آخرین خوانش موفق: ۲۰۲۶-۱۱-۱۹، ۵۸ پست (army21ye ۱۹، MilitaryMediaY
۱۹، Almasirah_En ۲۰).

## مرزهای جغرافیایی — GeoJSON

`assets/geo/yemen-adm1.geojson` (۲۲ استان) و
`assets/geo/yemen-outline.geojson` (مرز کشور) از **HDX COD-AB یمن**
(OCHA، گردآورنده: سازمان مرکزی آمار یمن، معتبر ۲۰۱۹-۱۱-۲۲، WGS84)
با Douglas–Peucker (تحمل ۰٫۰۰۰۸ درجه ≈ ۹۰ متر، مختصات تا ۴ رقم اعشار)
ساده شده‌اند — ابزار: `edge/simplify_geojson.py`. پروانه:
**CC BY-IGO** (HDX/OCHA). جزئیات کامل در `assets/geo/README.md`.
مرزهای مشترک به‌ازای هر استان جداگانه ساده شده‌اند، پس برای نمایش
روی نقشه‌اند، نه تحلیل یا مرز قانونی.

## شمار نهایی — Final counts (post-removal)

places ۹۰ · events ۲۰ (از ۲۴) · frontlines ۸ · arrows ۸ (از ۹) ·
units ۱۰ (از ۱۲) · forces ۷ · sources ۱۸ (از ۲۵) · claims:
confirmed ۶، contested ۲ (از ۶)، houthi_only ۶ · aden: timeline ۷
(از ۸)، images ۶ (از ۷)، sources ۲۳ (از ۲۸)، contradictions ۳ (از ۴).
گزارش کامل حذف در `edge/out/removal-log.json` (۳۸ ورودی: ۲۶ حذف کامل،
۵ منبع‌تهی، ۷ بازنویسی) و بررسی خودکار در `edge/verify_yemen.py`.
