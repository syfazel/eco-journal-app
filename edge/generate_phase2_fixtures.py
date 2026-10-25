#!/usr/bin/env python3
"""Phase 2 lab edge fixtures (LAB ONLY — never production data).

Builds a complete fixture edge directory from the current edge/out
base: copies every base file, then adds/replaces the three Phase 2
sections (channel_typed, bundles, radar) with lab fixtures and
rewrites manifest.json accordingly (section hashes per the Phase 2
spec section 2.1: SHA-256 over canonical JSON of the 4-field item
view, sorted by app_key).

Usage:
    python3 edge/generate_phase2_fixtures.py [--base BASE] [target-dir]
Default base: edge/out (must contain manifest.json; if the
committed out/ lacks it, generate a base first with
`python3 edge/generate_edge.py --out edge/out-p2base` and pass
--base edge/out-p2base). Default target: edge/out-phase2-fixture
(lab output; never the live edge, never pushed to Pages).

Fixture rules (spec 12.1):
- Every fixture file is labelled "LAB FIXTURE".
- channel_message_id is NULL except on items that explicitly
  simulate a staged post, where it is a clearly fake id >= 9000001
  that can never collide with a real message id.
- No real channel data, no real invite code, no blog URL, no
  model probability anywhere in the fixtures.
- Fixture day: Shamsi 1405-08-03 = Gregorian 2026-10-25 (Tehran).
  Night-law cases use published_at_epoch in the Tehran night
  window (00:00-08:00), so the phone test needs NO real night
  and NO phone-clock change (spec 12.1).
- The phone-test procedure (what to tap/observe per criterion)
  lives in docs/phase2-report.md, not here.
"""

import hashlib
import json
import os
import shutil
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TEH = ZoneInfo("Asia/Tehran")
HERE = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.join(HERE, "out")
DAY = "1405-08-03"  # Shamsi date of the fixture day.


def epoch(y, mo, d, h, mi=0):
    """Epoch seconds for a Tehran wall-clock time."""
    return int(datetime(y, mo, d, h, mi, tzinfo=TEH).timestamp())


def content_hash(obj):
    canonical = json.dumps(obj, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def section_hash(items4):
    ordered = sorted(items4, key=lambda d: d["app_key"])
    canonical = json.dumps(ordered, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def item(app_key, format_type, headline, body, published, **kw):
    """One typed fixture item. published = epoch seconds (Tehran)."""
    obj = {
        "app_key": app_key,
        "guid": "lab-fixture-" + app_key,
        "story_id": kw.pop("story_id", "story-lab-" + app_key),
        "channel_message_id": kw.pop("channel_message_id", None),
        "format_type": format_type,
        "urgency_score": kw.pop("urgency_score", None),
        "crisis_level": kw.pop("crisis_level", None),
        "headline": headline,
        "body": body,
        "published_at_epoch": published,
        "slot": kw.pop("slot", None),
        "bundle_id": kw.pop("bundle_id", None),
        "bundle_position": kw.pop("bundle_position", None),
        "bundle_total": kw.pop("bundle_total", None),
        "source_name": kw.pop("source_name", "منبع آزمایشگاهی"),
        "source_link": kw.pop("source_link", None),
        "attribution": kw.pop("attribution", None),
        "verdict_status": kw.pop("verdict_status", None),
        "verdict_text": kw.pop("verdict_text", None),
        "prices_json": kw.pop("prices_json", None),
        "sitrep_sections_json": kw.pop("sitrep_sections_json", None),
        "watch_next_json": kw.pop("watch_next_json", None),
        "status": kw.pop("status", "posted"),
        "updated_at_epoch": published,
    }
    obj["content_hash"] = content_hash(
        {k: v for k, v in obj.items() if k != "content_hash"})
    if kw:
        raise ValueError("unknown item field(s): %s" % sorted(kw))
    return obj


def sitrep_sections(n=5):
    return [{"heading": "بخش %d آزمایشگاهی" % i,
             "body": "متن آزمایشگاهی بخش %d." % i} for i in range(1, n + 1)]


def watch_next(n=2, story_prefix="lab-story"):
    return [{"title": "زیر نظر %d" % i,
             "linked_story_id": "%s-%d" % (story_prefix, i)}
            for i in range(1, n + 1)]


def build_items():
    items = []
    # --- Urgent / night-law cases (spec 5.2) ---
    items.append(item(
        "lab-flash-day", "FLASH", "LAB FIXTURE — خبر فوری روز",
        "متن آزمایشگاهی خبر فوری روز.",
        epoch(2026, 10, 25, 10, 30), urgency_score=90, crisis_level="L3",
        slot="morning", channel_message_id=9000001))
    items.append(item(
        "lab-flash-night-noscore", "FLASH", "LAB FIXTURE — خبر فوری شب بدون امتیاز",
        "فوری شبانه بدون امتیاز؛ معافیت فلش باید آن را فوری کند.",
        epoch(2026, 10, 25, 2, 0), urgency_score=None, crisis_level="L2",
        slot="night", channel_message_id=9000002))
    items.append(item(
        "lab-watch-l3-night", "WATCH", "LAB FIXTURE — تحت نظر سطح ۳ در شب",
        "واچ سطح ۳ در شب با امتیاز ۵۰؛ معافیت شبانه باید آن را فوری کند.",
        epoch(2026, 10, 25, 3, 0), urgency_score=50, crisis_level="L3",
        slot="night"))
    items.append(item(
        "lab-watch-l2-night-held", "WATCH", "LAB FIXTURE — تحت نظر سطح ۲ در شب (نگه‌داشته‌شده)",
        "واچ سطح ۲ در شب با امتیاز ۷۵؛ باید تا بسته صبح نگه داشته شود.",
        epoch(2026, 10, 25, 2, 30), urgency_score=75, crisis_level="L2",
        slot="night"))
    items.append(item(
        "lab-geopol-night-held", "GEOPOL", "LAB FIXTURE — ژئوپلیتیک شب (نگه‌داشته‌شده)",
        "ژئوپلیتیک شب با امتیاز ۸۰؛ باید تا بسته صبح نگه داشته شود.",
        epoch(2026, 10, 25, 1, 15), urgency_score=80, crisis_level="L2",
        slot="night"))
    items.append(item(
        "lab-geopol-night-85", "GEOPOL", "LAB FIXTURE — ژئوپلیتیک شب امتیاز ۸۵",
        "ژئوپلیتیک شب با امتیاز ۸۵؛ باید فوری شود.",
        epoch(2026, 10, 25, 4, 0), urgency_score=85, crisis_level="L2",
        slot="night"))
    items.append(item(
        "lab-flash-old-pin", "FLASH", "LAB FIXTURE — خبر فوری قدیمی برای آزمون سنجاق",
        "فوری منتشرشده در روز قبل؛ در آزمون سنجاق باید از جایگاه اول خارج شود.",
        epoch(2026, 10, 24, 18, 0), urgency_score=95, crisis_level="L3",
        slot="evening"))
    # --- Day feed-only / instant cases ---
    items.append(item(
        "lab-market-day", "MARKET", "LAB FIXTURE — خبر بازار روز",
        "متن آزمایشگاهی خبر بازار با خط قیمت.",
        epoch(2026, 10, 25, 12, 0), urgency_score=65, crisis_level="L1",
        slot="midday",
        prices_json={"asset": "طلا", "price": 1234.5, "change": 1.25,
                     "bubble_pct": 3.0, "spread_pct": 0.5}))
    items.append(item(
        "lab-verify-day", "VERIFY", "LAB FIXTURE — راستی‌آزمایی روز",
        "متن آزمایشگاهی راستی‌آزمایی.",
        epoch(2026, 10, 25, 14, 0), urgency_score=72, crisis_level="L2",
        slot="midday", verdict_status="confirmed",
        verdict_text="متن حکم آزمایشگاهی."))
    items.append(item(
        "lab-verify-pending", "VERIFY", "LAB FIXTURE — راستی‌آزمایی در حال بررسی",
        "متن آزمایشگاهی راستی‌آزمایی در حال بررسی.",
        epoch(2026, 10, 25, 15, 0), urgency_score=60, crisis_level="L2",
        slot="midday", verdict_status="pending",
        verdict_text="حکم اتاق جنگ: ⏳ در حال بررسی"))
    items.append(item(
        "lab-geopol-day", "GEOPOL", "LAB FIXTURE — ژئوپلیتیک روز",
        "متن آزمایشگاهی ژئوپلیتیک روز.",
        epoch(2026, 10, 25, 11, 0), urgency_score=55, crisis_level="L1",
        slot="morning"))
    items.append(item(
        "lab-wire-link", "WIRE", "LAB FIXTURE — وایر با لینک (باید حذف شود)",
        "وایر آزمایشگاهی که لینک دارد؛ اپ باید بدون دکمه لینک نشانش دهد.",
        epoch(2026, 10, 25, 13, 0), urgency_score=40,
        slot="midday", source_name="منبع وایر آزمایشگاهی",
        source_link="https://example.invalid/lab-wire"))
    items.append(item(
        "lab-wire-plain", "WIRE", "LAB FIXTURE — وایر ساده",
        "وایر آزمایشگاهی بدون لینک؛ فقط نام منبع.",
        epoch(2026, 10, 25, 13, 30), urgency_score=40,
        slot="midday", source_name="منبع وایر آزمایشگاهی"))
    # --- SITREPs: 08:00 L2, 10:00 L3 (arc + level change) ---
    items.append(item(
        "lab-sitrep-0800", "SITREP", "LAB FIXTURE — گزارش وضعیت ۰۸:۰۰",
        "خلاصه آزمایشگاهی گزارش وضعیت صبح.",
        epoch(2026, 10, 25, 8, 0), urgency_score=70, crisis_level="L2",
        slot="morning",
        sitrep_sections_json=sitrep_sections(5),
        watch_next_json=watch_next(2)))
    items.append(item(
        "lab-sitrep-1000", "SITREP", "LAB FIXTURE — گزارش وضعیت ۱۰:۰۰",
        "خلاصه آزمایشگاهی گزارش وضعیت دوم.",
        epoch(2026, 10, 25, 10, 0), urgency_score=70, crisis_level="L3",
        slot="morning",
        sitrep_sections_json=sitrep_sections(5),
        watch_next_json=watch_next(2, "lab-story-sitrep")))
    # --- Malformed SITREP (P2-A13): 3 sections, 1 watch-next ---
    items.append(item(
        "lab-sitrep-bad", "SITREP", "LAB FIXTURE — گزارش وضعیت ناقص",
        "خلاصه آزمایشگاهی گزارش ناقص.",
        epoch(2026, 10, 25, 12, 30), urgency_score=60, crisis_level="L2",
        slot="midday",
        sitrep_sections_json=sitrep_sections(3),
        watch_next_json=watch_next(1)))
    # --- Attribution cases ---
    items.append(item(
        "lab-verbatim-donya", "GEOPOL", "LAB FIXTURE — بازنشر دنیای اقتصاد",
        "متن آزمایشگاهی بازنشر.",
        epoch(2026, 10, 25, 16, 0), urgency_score=50,
        slot="evening", source_name="دنیای اقتصاد",
        source_link="https://example.invalid/donya", attribution="verbatim"))
    items.append(item(
        "lab-translated", "GEOPOL", "LAB FIXTURE — ترجمه تحریریه",
        "متن آزمایشگاهی ترجمه‌شده.",
        epoch(2026, 10, 25, 16, 30), urgency_score=50,
        slot="evening", source_name="منبع خارجی آزمایشگاهی",
        source_link="https://example.invalid/foreign", attribution="translated"))
    items.append(item(
        "lab-verbatim-blocked", "GEOPOL", "LAB FIXTURE — ادعای بازنشر از منبع غیرمجاز",
        "متن آزمایشگاهی؛ ادعای بازنشر باید به حالت عادی برگردد.",
        epoch(2026, 10, 25, 17, 0), urgency_score=50,
        slot="evening", source_name="منبع غیرمجاز آزمایشگاهی",
        source_link="https://example.invalid/other", attribution="verbatim"))
    # --- Story-dedup pair: same story, older + newer ---
    items.append(item(
        "lab-story-old", "GEOPOL", "LAB FIXTURE — نسخه قدیمی یک خبر",
        "نسخه قدیمی؛ فید باید فقط نسخه جدید را نشان دهد.",
        epoch(2026, 10, 25, 9, 0), urgency_score=50,
        slot="morning", story_id="story-lab-dup"))
    items.append(item(
        "lab-story-new", "GEOPOL", "LAB FIXTURE — نسخه جدید یک خبر",
        "نسخه جدید؛ فید باید فقط همین را نشان دهد.",
        epoch(2026, 10, 25, 9, 30), urgency_score=55,
        slot="morning", story_id="story-lab-dup"))
    # --- Unslotted item (NULL slot -> «بدون بازه») ---
    items.append(item(
        "lab-noslot", "GEOPOL", "LAB FIXTURE — خبر بدون بازه",
        "متن آزمایشگاهی بدون بازه.",
        epoch(2026, 10, 25, 18, 0), urgency_score=45, slot=None))
    # --- Corrupt / anomaly items ---
    items.append(item(
        "lab-bad-format", "NOTAFORMAT", "LAB FIXTURE — قالب ناشناخته",
        "این قلم باید رد شود و بقیه بخش اعمال شود.",
        epoch(2026, 10, 25, 19, 0), urgency_score=50))
    bad_headline = item(
        "lab-bad-headline", "GEOPOL", "",
        "این قلم تیتر خالی دارد و باید رد شود.",
        epoch(2026, 10, 25, 19, 10), urgency_score=50)
    items.append(bad_headline)
    items.append(item(
        "lab-bad-score", "GEOPOL", "LAB FIXTURE — امتیاز خارج از بازه",
        "امتیاز ۱۵۰ خارج از ۰ تا ۱۰۰ است؛ باید فقط در فید باشد.",
        epoch(2026, 10, 25, 19, 20), urgency_score=150))
    items.append(item(
        "lab-bad-bundle-member", "GEOPOL", "LAB FIXTURE — عضویت بسته ناقص",
        "bundle_id دارد ولی position ندارد؛ باید بدون بسته در فید باشد.",
        epoch(2026, 10, 25, 19, 30), urgency_score=50,
        bundle_id="lab-bundle-broken"))
    # --- Retracted item (bundle member + bookmark-orphan test) ---
    items.append(item(
        "lab-retracted", "GEOPOL", "LAB FIXTURE — خبر بازپس‌گرفته‌شده",
        "این خبر بازپس گرفته شده است.",
        epoch(2026, 10, 25, 8, 30), urgency_score=50,
        slot="morning", status="retracted"))
    # --- Short-news members (also morning-bundle members) ---
    for i in range(1, 4):
        items.append(item(
            "lab-shortnews-%d" % i, "WIRE",
            "LAB FIXTURE — خبر کوتاه %d" % i,
            "متن آزمایشگاهی خبر کوتاه %d." % i,
            epoch(2026, 10, 25, 7, 0 + i * 5), urgency_score=30,
            slot="morning", bundle_id="lab-shortnews",
            bundle_position=i, bundle_total=3))
    # --- Slot-bundle + marginalia + hourly members ---
    for i in range(1, 3):
        items.append(item(
            "lab-slotbundle-%d" % i, "GEOPOL",
            "LAB FIXTURE — عضو بسته بازه %d" % i,
            "متن آزمایشگاهی عضو بسته بازه %d." % i,
            epoch(2026, 10, 25, 8, 10 + i * 5), urgency_score=40,
            slot="morning", bundle_id="lab-slotbundle",
            bundle_position=i, bundle_total=2))
    for i in range(1, 3):
        items.append(item(
            "lab-marginalia-%d" % i, "GEOPOL",
            "LAB FIXTURE — حاشیه %d" % i,
            "متن آزمایشگاهی حاشیه %d." % i,
            epoch(2026, 10, 25, 20, 0 + i * 5), urgency_score=35,
            slot="evening", bundle_id="lab-marginalia",
            bundle_position=i, bundle_total=2))
    for i in range(1, 3):
        items.append(item(
            "lab-hourly-%d" % i, "MARKET",
            "LAB FIXTURE — گزیده ساعتی %d" % i,
            "متن آزمایشگاهی گزیده ساعتی %d." % i,
            epoch(2026, 10, 25, 21, 0 + i * 5), urgency_score=35,
            slot="evening", bundle_id="lab-hourly",
            bundle_position=i, bundle_total=2))
    # --- Night Desk members: 12 items (previous night, low scores) ---
    for i in range(1, 13):
        items.append(item(
            "lab-nightdesk-%02d" % i, "WIRE",
            "LAB FIXTURE — جامانده دیشب %02d" % i,
            "متن آزمایشگاهی جامانده دیشب %02d." % i,
            epoch(2026, 10, 24, 23, 0 + i), urgency_score=30,
            slot="night"))
    return items


def build_bundles(items):
    by_key = {it["app_key"]: it for it in items}
    morning_members = [
        "lab-watch-l2-night-held", "lab-geopol-night-held",
        "lab-sitrep-1000",
        "lab-shortnews-1", "lab-shortnews-2", "lab-shortnews-3",
        "lab-slotbundle-1", "lab-slotbundle-2",
        "lab-retracted",  # retracted member -> honest missing line
    ]
    bundles = [
        {
            "bundle_id": "morning-bundle-" + DAY,
            "bundle_kind": "morning_bundle",
            "title": "بسته صبح آزمایشگاهی",
            "member_app_keys": morning_members,
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 8, 0),
        },
        {
            "bundle_id": "lab-nightdesk",
            "bundle_kind": "night_desk",
            "title": "جامانده‌های دیشب",
            "member_app_keys": ["lab-nightdesk-%02d" % i for i in range(1, 13)],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 0, 5),
        },
        {
            "bundle_id": "lab-shortnews",
            "bundle_kind": "short_news",
            "title": "خبرهای کوتاه آزمایشگاهی",
            "member_app_keys": ["lab-shortnews-1", "lab-shortnews-2",
                                "lab-shortnews-3"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 7, 30),
        },
        {
            "bundle_id": "lab-slotbundle",
            "bundle_kind": "slot_bundle",
            "title": "بسته بازه صبح آزمایشگاهی",
            "member_app_keys": ["lab-slotbundle-1", "lab-slotbundle-2"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 8, 20),
        },
        {
            "bundle_id": "lab-marginalia",
            "bundle_kind": "marginalia",
            "title": "حاشیه‌های آزمایشگاهی",
            "member_app_keys": ["lab-marginalia-1", "lab-marginalia-2"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 20, 30),
        },
        {
            "bundle_id": "lab-hourly",
            "bundle_kind": "hourly_digest",
            "title": "گزیده ساعتی آزمایشگاهی",
            "member_app_keys": ["lab-hourly-1", "lab-hourly-2"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 21, 30),
        },
        # Blocked: disabled morning digest (must render nowhere).
        {
            "bundle_id": "lab-morning-digest",
            "bundle_kind": "morning_digest",
            "title": "گزیده صبح قدیمی (باید مسدود شود)",
            "member_app_keys": ["lab-geopol-day"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 8, 0),
        },
        # Held: unknown kind (header held, items stay in feed).
        {
            "bundle_id": "lab-unknown-kind",
            "bundle_kind": "mystery_kind",
            "title": "بسته با نوع ناشناخته",
            "member_app_keys": ["lab-geopol-day"],
            "bundle_date_shamsi": DAY,
            "status": "posted",
            "updated_at_epoch": epoch(2026, 10, 25, 8, 0),
        },
    ]
    for b in bundles:
        b["content_hash"] = content_hash(
            {k: v for k, v in b.items() if k != "content_hash"})
    return bundles


def build_radar():
    signals = [
        {"signal_id": "lab-radar-1", "title": "سیگنال آزمایشگاهی لایه ۱ با رتبه بالا",
         "layer": 1, "severity": "بالا", "severity_rank": 3,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": "story-lab-dup",
         "detected_at_epoch": epoch(2026, 10, 25, 9, 0),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 25, 9, 0)},
        {"signal_id": "lab-radar-2", "title": "سیگنال آزمایشگاهی لایه ۱ بدون رتبه",
         "layer": 1, "severity": "متوسط", "severity_rank": None,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": None,
         "detected_at_epoch": epoch(2026, 10, 25, 9, 30),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 25, 9, 30)},
        {"signal_id": "lab-radar-3", "title": "سیگنال آزمایشگاهی لایه ۲",
         "layer": 2, "severity": "بحرانی", "severity_rank": 4,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": None,
         "detected_at_epoch": epoch(2026, 10, 25, 8, 45),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 25, 8, 45)},
        {"signal_id": "lab-radar-4", "title": "سیگنال آزمایشگاهی لایه ۳",
         "layer": 3, "severity": "کم", "severity_rank": 1,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": None,
         "detected_at_epoch": epoch(2026, 10, 25, 10, 15),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 25, 10, 15)},
        # Stale: detected the previous day; older than any plausible
        # fixture interval at any later render time.
        {"signal_id": "lab-radar-stale", "title": "سیگنال آزمایشگاهی قدیمی",
         "layer": 2, "severity": "متوسط", "severity_rank": 2,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": None,
         "detected_at_epoch": epoch(2026, 10, 24, 10, 0),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 24, 10, 0)},
        # Unknown layer -> unclassified tail.
        {"signal_id": "lab-radar-badlayer", "title": "سیگنال آزمایشگاهی با لایه ناشناخته",
         "layer": 9, "severity": "متوسط", "severity_rank": None,
         "source_name": "منبع رادار آزمایشگاهی",
         "source_link": None, "linked_story_id": None,
         "detected_at_epoch": epoch(2026, 10, 25, 11, 0),
         "status": "active",
         "updated_at_epoch": epoch(2026, 10, 25, 11, 0)},
    ]
    for s in signals:
        s["content_hash"] = content_hash(
            {k: v for k, v in s.items() if k != "content_hash"})
    return {
        "war_room": {
            "crisis_level": "L3",
            "why_level": "LAB FIXTURE — خط چرایی سطح بحران آزمایشگاهی.",
            "radar_interval_minutes": 60,
        },
        "signals": signals,
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--base=")]
    base = BASE_DIR
    for a in sys.argv[1:]:
        if a.startswith("--base="):
            base = a[len("--base="):]
    target = args[0] if args else os.path.join(HERE, "out-phase2-fixture")
    if not os.path.isfile(os.path.join(base, "manifest.json")):
        print("base manifest missing: %s (run generate_edge.py --out <base> first)"
              % os.path.join(base, "manifest.json"))
        return 1
    if os.path.abspath(target) == os.path.abspath(base):
        print("refusing to overwrite the base edge")
        return 1
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(base, target)
    items = build_items()
    bundles = build_bundles(items)
    radar = build_radar()

    with open(os.path.join(target, "channel_typed.json"), "w", encoding="utf-8") as f:
        json.dump({"label": "LAB FIXTURE", "items": items}, f,
                  ensure_ascii=False, indent=1)
    with open(os.path.join(target, "bundles.json"), "w", encoding="utf-8") as f:
        json.dump({"label": "LAB FIXTURE", "bundles": bundles}, f,
                  ensure_ascii=False, indent=1)
    with open(os.path.join(target, "radar.json"), "w", encoding="utf-8") as f:
        json.dump(dict(radar, label="LAB FIXTURE"), f,
                  ensure_ascii=False, indent=1)

    manifest_path = os.path.join(target, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)

    def items4(entries, key_field):
        return [{"app_key": e[key_field],
                 "content_hash": e["content_hash"],
                 "updated_at_epoch": e["updated_at_epoch"],
                 "status": e["status"]} for e in entries]

    typed_manifest = [{
        "id": it["app_key"], "app_key": it["app_key"],
        "content_hash": it["content_hash"],
        "updated_at_epoch": it["updated_at_epoch"],
        "status": it["status"], "channel_message_id": it["channel_message_id"],
        "story_id": it["story_id"],
    } for it in items]
    manifest["sections"]["channel_typed"] = {
        "hash": section_hash(items4(items, "app_key")),
        "count": len(items),
        "status": "published",
        "file": "channel_typed.json",
        "items": typed_manifest,
    }
    manifest["sections"]["bundles"] = {
        "hash": section_hash(items4(bundles, "bundle_id")),
        "count": len(bundles),
        "status": "published",
        "file": "bundles.json",
        "items": [{"id": b["bundle_id"], "app_key": b["bundle_id"],
                   "content_hash": b["content_hash"],
                   "updated_at_epoch": b["updated_at_epoch"],
                   "status": b["status"]} for b in bundles],
    }
    manifest["sections"]["radar"] = {
        "hash": section_hash(items4(radar["signals"], "signal_id")),
        "count": len(radar["signals"]),
        "status": "published",
        "file": "radar.json",
        "items": [{"id": s["signal_id"], "app_key": s["signal_id"],
                   "content_hash": s["content_hash"],
                   "updated_at_epoch": s["updated_at_epoch"],
                   "status": s["status"]} for s in radar["signals"]],
    }
    # Gated sections stay exactly as the base manifest ships them.
    assert manifest["sections"]["special_issues"]["status"] == "gated_frozen"
    assert manifest["sections"]["nightly"]["status"] == "not_yet_published"
    assert manifest["sections"]["voices"]["status"] == "not_yet_published"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("fixture edge written to %s" % target)
    print("channel_typed: %d items, bundles: %d, radar: %d signals"
          % (len(items), len(bundles), len(radar["signals"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
