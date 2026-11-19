#!/usr/bin/env python3
"""Yemen data sourcing rules — v6.1.0 data revision (LAB ONLY).

Implements Yasin's editorial rule: government forces on the opposing
front basically publish false news — their sources are NOT used at all.
Applied by edge/generate_yemen.py as a post-processing transform on
top of the field-for-field dashboard mapping, so the removal is
auditable separately from the mapping.

Tiers (see edge/SOURCING-RULES.md for the full documented list):
  Tier A — direct government-force sources (official statements,
    spokespersons, army announcements, Saudi state media, coalition
    spokesperson): removed entirely, including items whose whole
    existence is such a statement.
  Tier B — government-claim-only items relayed by wires (Reuters /
    AFP / Al Jazeera relaying a government claim with no independent
    corroboration): the claim / item is removed entirely.
  Tier C — aligned but not official outlets (South24, Sheba
    Intelligence, Yemen Monitor, Al-Jumhuriya, Al-Ain): an item where
    such an outlet is the SOLE evidence for a government claim is
    removed; where independent non-government evidence also supports
    the item, the item is kept and the Tier C attribution is stripped.

What is NOT a source removal: mentions of government forces as
actors ("government forces withdrew", reported by Reuters / AFP /
ISW) stay — they are events, not sources. Likewise, a Tier C outlet
reporting a *Houthi* action (e.g. South24 reporting a Houthi missile
strike, Sheba reporting Houthi reinforcement movements) is not a
government claim and is kept, with the reasoning recorded in
SOURCING-RULES.md.

Places / events that lose their only source are KEPT (geography
stays) with source nulled and flagged:
  "source_removed_gov": true,
  "source_removal_note_fa": "منبع دولتی حذف شد — نیازمند منبع مستقل"
Items whose whole existence was a government claim are removed
entirely (event 18 Saudi command change, event 5 Operation Yemen
Dawn, event 21 Iranian-commander claim, event 22 Hayfan/Jawf claims,
the 201-neutralized claim, the 906-operations claim, the 661-attacks
claim, the 877/703-strikes claims, the Shield-of-the-Homeland unit,
the Shabwa-defence unit, the Bir al-Maraziq arrow).

Every replacement below is anchored on an exact substring of the
v5 dashboard snapshot (2026-11-18). A replacement that does not
match EXACTLY raises — the generator refuses to write rather than
silently shipping an unremoved government claim.

Also in this module: the event taxonomy (taxonomy_version 1).
"""

import json

GOV_NOTE = "منبع دولتی حذف شد — نیازمند منبع مستقل"

# Strings that must NOT survive anywhere in the generated yemen.json.
# The verifier greps for these and fails on any hit. (Persian digits
# are used where the source text uses them; "2015" contains "201" so
# the 201-claim is matched with its full phrase, not bare digits.)
MUST_NOT_SURVIVE = [
    "nid=5877",                       # South24 Saudi command-change story
    "201 Houthis neutralized",
    "۲۰۱ حوثی خنثی",
    "۶۶۱ حمله", "661 attacks", "۶۶۱",
    "۹۰۶ عملیات",
    "۸۷۷ حمله", "۷۰۳ حمله",
    "۲۰٬۰۰۰ حوثی", "۴۸٬۵۷۶",
    "Yemen Monitor", "یمن مانیتور",
    "Al-Jumhuriya", "الجمهوری",
    "Al-Ain", "العین",
    "Shafaq", "شفق",
    "Arab News", "عرب‌نیوز",
    "The National", "نشنال",
    "Government claims al-Jawf",
    "Yemeni government forces seize district",
    "Saudi Arabia Changes Joint Forces Command",
    "Saudi Arabia replaces military commander",
    "House of Saud",
    "وضاح الدبیش",                    # govt spokesperson, quoted by name
    "ابو طاها الشرافی",               # Sheba-attributed commander name
    "۷۰ (شین‌هوا)",                   # gov-sourced 70 km figure
    "۶۰ در برابر ۷۰",
]

# Event taxonomy (taxonomy_version 1). Keyed by dashboard event id.
# event_type: advance | retreat | airstrike | missile_drone | clash |
#             claim | siege | humanitarian
# confidence: ansarullah-confirmed (a Saree / Al-Masirah / Military
#   Media statement says it) | ansarullah-reported (Ansarullah media
#   reports it, no Saree statement) | unverified (everything else,
#   including UN-sourced items — this taxonomy grades Ansarullah
#   attestation, not truth).
EVENT_TAXONOMY = {
    1: ("clash", "unverified"),
    2: ("advance", "unverified"),
    3: ("advance", "unverified"),
    4: ("advance", "unverified"),
    6: ("missile_drone", "unverified"),
    7: ("advance", "unverified"),
    8: ("advance", "unverified"),
    9: ("clash", "unverified"),
    10: ("missile_drone", "unverified"),
    11: ("advance", "unverified"),
    12: ("missile_drone", "unverified"),
    13: ("missile_drone", "unverified"),
    14: ("advance", "unverified"),
    15: ("missile_drone", "unverified"),
    16: ("claim", "ansarullah-confirmed"),
    17: ("advance", "unverified"),
    19: ("missile_drone", "unverified"),
    20: ("advance", "unverified"),
    23: ("missile_drone", "ansarullah-confirmed"),
    24: ("humanitarian", "unverified"),
}

TAXONOMY_VERSION = 1
EVENT_TYPES = {"advance", "retreat", "airstrike", "missile_drone",
               "clash", "claim", "siege", "humanitarian"}
CONFIDENCES = {"ansarullah-confirmed", "ansarullah-reported",
               "unverified"}


def _sub_once(text, old, new, ctx):
    if text.count(old) != 1:
        raise ValueError("sourcing rule %s: expected exactly 1 occurrence "
                         "of %r, found %d" % (ctx, old, text.count(old)))
    return text.replace(old, new)


def _sub_all(text, old, new, ctx):
    if old not in text:
        raise ValueError("sourcing rule %s: substring not found: %r"
                         % (ctx, old))
    return text.replace(old, new)


def _find(items, key, value, ctx):
    hits = [it for it in items if it.get(key) == value]
    if len(hits) != 1:
        raise ValueError("sourcing rule %s: expected 1 item with %s=%r, "
                         "found %d" % (ctx, key, value, len(hits)))
    return hits[0]


def _remove_by(items, key, value, log, section, reason):
    before = len(items)
    items[:] = [it for it in items if it.get(key) != value]
    if len(items) != before - 1:
        raise ValueError("sourcing rule %s: expected to remove 1 item "
                         "with %s=%r" % (section, key, value))
    log.append({"section": section, "id": value, "action": "removed",
                "reason": reason})


def _remove_by_pred(items, pred, log, section, ident, reason):
    before = len(items)
    items[:] = [it for it in items if not pred(it)]
    if len(items) != before - 1:
        raise ValueError("sourcing rule %s: expected to remove 1 item "
                         "(%s)" % (section, ident))
    log.append({"section": section, "id": ident, "action": "removed",
                "reason": reason})


def _null_event_source(event, log):
    event["source_name"] = None
    event["source_url"] = None
    event["source_removed_gov"] = True
    event["source_removal_note_fa"] = GOV_NOTE
    log.append({"section": "events", "id": event["external_id"],
                "action": "source_nulled",
                "reason": "only source was a government-force source"})


def _null_place_source(place, log):
    place["source_note"] = None
    place["source_removed_gov"] = True
    place["source_removal_note_fa"] = GOV_NOTE
    log.append({"section": "places", "id": place["external_id"],
                "action": "source_nulled",
                "reason": "only source was a government-force source"})


def apply_sourcing_rules(doc):
    """Mutate the yemen.json doc in place per the tiers above.
    Returns the removal log (list of dicts). Raises on any anchor
    mismatch — the generator must refuse to write then."""
    log = []

    # ---- headline (top level + snapshot copies): drop the
    # government-claim sentences; the 70 km figure came from a
    # government military source via Xinhua — AFP's 60 km stands.
    old_headline = doc["headline_fa"]
    new_headline = _sub_once(
        old_headline, "و تا حدود ۷۰ کیلومتری عدن",
        "و تا حدود ۶۰ کیلومتری عدن", "headline/70km")
    new_headline = _sub_once(
        new_headline,
        "، در حالی که ارتش دولت با اعزام نیرو از عدن این جبهه را تقویت "
        "می‌کند؛ نیروهای دولتی در ۳۰ اکتبر از بازپس‌گیری مواضعی در "
        "حیفان و پیشروی در الجوف خبر دادند و هم‌زمان حملات موشکی به "
        "فرودگاه عدن و کاخ معاشیق و پیش از آن در ۱۳ اکتبر حملهٔ هوایی "
        "به یک مرکز فرماندهی حوثی–سپاه در تعز گزارش شد.",
        "، در حالی که ضدحملهٔ دولت در ۱۳ نوامبر موقتاً پیشروی را "
        "برگرداند.", "headline/gov-claim-sentences")
    doc["headline_fa"] = new_headline
    for snap in doc["snapshots"]:
        if snap.get("headline_fa") == old_headline:
            snap["headline_fa"] = new_headline
    log.append({"section": "headline", "id": "headline_fa",
                "action": "reworded",
                "reason": "removed government-claim sentences (30 Oct "
                          "Hayfan/Jawf claims, 13 Oct airstrike claim); "
                          "70 km figure replaced with AFP 60 km"})

    # ---- top-level sources: drop the 7 government-claim relays /
    # Tier C entries whose items are independently supported elsewhere.
    for fragment in (
            "Yemeni government forces seize district by Bab el-Mandeb",
            "Saudi Arabia Changes Joint Forces Command",
            "201 Houthis neutralized",
            "Saudi Arabia replaces military commander",
            "Government claims al-Jawf mountain range",
            "South24 — Southern Forces Shift from Bab Al-Mandeb",
            "Sheba Intelligence — Houthi Offensive in Lahj"):
        _remove_by_pred(doc["sources"],
                         lambda s, f=fragment: f in s.get("label", ""),
                         log, "sources", fragment,
                         "Tier A/B/C: government-claim relay / aligned "
                         "outlet whose item is independently supported")

    # ---- events: remove 4 whole items, null 1 source, reword 7.
    for eid, reason in (
            (18, "Tier A/B: Saudi command change — government announcement"),
            (5, "Tier B: Operation Yemen Dawn — government-claim-only, "
                "relayed by Reuters/South24"),
            (21, "Tier C: Iranian-commander claim — Sheba sole evidence"),
            (22, "Tier C: Hayfan/Jawf claims — Sheba sole evidence")):
        _remove_by(doc["events"], "external_id", eid, log, "events", reason)
    ev = {e["external_id"]: e for e in doc["events"]}

    e20 = ev[20]
    _null_event_source(e20, log)
    e20["title_fa"] = "پیشروی حوثی‌ها به‌سوی عدن (گزارش ۱۵ نوامبر)"
    e20["detail_fa"] = (
        "گزارش‌ها از پیشروی حوثی‌ها به مواضعی در حدود ۷۰ کیلومتری شهر "
        "بندری عدن حکایت می‌کنند؛ این رقم تنها از یک منبع نظامی دولت "
        "نقل شده و مستقل تأیید نشده است (AFP همان روز از پیشروی تا "
        "حدود ۶۰ کیلومتری عدن در محور خرز گفت).")
    log.append({"section": "events", "id": 20, "action": "reworded",
                "reason": "70 km figure kept only with an explicit "
                          "unverified caveat; title no longer states it "
                          "as fact"})

    e4 = ev[4]
    e4["source_name"] = _sub_once(
        e4["source_name"], "House of Saud / Al Jazeera, 12 Sep",
        "Al Jazeera, 12 Sep", "event4/source")
    e3 = ev[3]
    e3["source_name"] = _sub_once(
        e3["source_name"], "Reuters via Daily Sabah / PolGeoNow",
        "Reuters / PolGeoNow", "event3/source")
    e3["source_url"] = (
        "https://www.polgeonow.com/2026/11/yemen-map-houthi-control-2026-"
        "war-red-sea-chokepoint.html")
    e14 = ev[14]
    e14["source_name"] = _sub_once(
        e14["source_name"], "AFP; Xinhua; South24; ISW, 15 Nov",
        "AFP; Xinhua; ISW, 15 Nov", "event14/source")
    e13 = ev[13]
    e13["detail_fa"] = _sub_once(
        e13["detail_fa"],
        "سخنگوی دولت از اصابت دو موشک بالستیک به عدن خبر داد؛ ",
        "", "event13/gov-spokesperson")
    e19 = ev[19]
    e19["detail_fa"] = _sub_once(
        e19["detail_fa"],
        " الجزیره: دولت ادعای پیشروی بیشتر در جبل دهیده (جوف) کرد.",
        "", "event19/gov-claim")
    e15 = ev[15]
    e15["detail_fa"] = _sub_once(
        e15["detail_fa"],
        " South24 در ۱۶ نوامبر: ۱۶ مسافر غیرنظامی و ۲ سرباز کشته و ۱۸ "
        "نیروی امنیت ملی زخمی.", "", "event15/south24-figures")
    e15["source_name"] = _sub_once(
        e15["source_name"], "AFP via Times of Israel; South24, 16 Nov",
        "AFP via Times of Israel", "event15/source")
    e12 = ev[12]
    e12["title_fa"] = "حمله‌های موشکی به عدن؛ آغاز استحکامات دور شهر"
    e12["detail_fa"] = (
        "حمله‌های موشکی به اردوگاه‌ها و فرودگاه عدن در ۱۱ تا ۱۴ نوامبر "
        "گزارش شد (Reuters، AFP)؛ دولت از همان روز دور عدن کیسه‌شنی "
        "چید و تونل حفر کرد (AFP).")
    e12["source_name"] = "AFP"
    e12["source_url"] = (
        "https://www.timesofisrael.com/houthis-advance-toward-aden-"
        "closing-to-within-60-km-as-government-bolsters-defenses/amp/")
    e11 = ev[11]
    e11["detail_fa"] = _sub_once(
        e11["detail_fa"], " (South24)", "", "event11/south24")
    e11["source_name"] = _sub_once(
        e11["source_name"], "ISW / South24, 12 Nov", "ISW, 12 Nov",
        "event11/source")
    e16 = ev[16]
    e16["detail_fa"] = _sub_once(
        e16["detail_fa"],
        " سخنگوی دولت: ۹۰۶ عملیات و ۲۰۱ حوثی «خنثی‌شده» — فقط ادعای "
        "دولت.", "", "event16/gov-figures")
    e16["source_name"] = _sub_once(
        e16["source_name"], "Shafaq / Al-Masirah; Al Mayadeen; ICRC via AFP",
        "Al-Masirah; Al Mayadeen; ICRC via AFP", "event16/source")
    log.append({"section": "events", "id": "3/4/11/12/13/15/16/19",
                "action": "reworded",
                "reason": "stripped Tier A/B/C attributions and "
                          "government-claim sentences"})

    # ---- places: strip 7 Tier C attributions, null 3 sole sources.
    pl = {p["external_id"]: p for p in doc["places"]}
    for pid, old, new in (
            (31, "Al Jazeera 4 & 16 Nov; ISW 7 Nov 2026",
             "ISW, 7 Nov 2026; Al Jazeera liveblog, 16 Nov 2026"),
            (40, "Tribune / Al-Jumhuriya, 4 Nov 2026",
             "Tribune, 4 Nov 2026"),
            (41, "JPost / Al-Ain, Oct 2026", "JPost, Oct 2026"),
            (46, "Southern Armed Forces statement via South24 / ISW, "
                 "15 Nov 2026", "ISW, 15 Nov 2026"),
            (48, "Strategic Energy Map; South24, 7 Oct 2026",
             "Strategic Energy Map, Nov 2026"),
            (62, "Sheba 12 Nov; AFP 15 Nov 2026", "AFP, 15 Nov 2026"),
            (63, "ISW; South24, 15 Nov 2026", "ISW, 15 Nov 2026"),
            (67, "ISW; South24, Nov 2026", "ISW, Nov 2026")):
        place = pl[pid]
        place["source_note"] = _sub_once(
            place["source_note"] or "", old, new, "place%d/source" % pid)
    for pid in (49, 50, 56, 73):
        _null_place_source(pl[pid], log)
    # Place notes carry attributions too: strip the Tier C ones whose
    # facts are independently supported; the 12 Nov Kharaz missile
    # attack is also Saree-claimed (Aden channels_official).
    pl[60]["note"] = _sub_once(
        pl[60]["note"], "حمله موشکی به خرز ۱۲ نوامبر (South24).",
        "حمله موشکی به خرز ۱۲ نوامبر (به ادعای سریع).", "place60/note")
    pl[62]["note"] = _sub_once(
        pl[62]["note"], "شبا ۱۲ نوامبر: هنوز در کنترل ارتش یمن؛ ",
        "", "place62/note")
    pl[63]["note"] = _sub_once(
        pl[63]["note"], "(ISW؛ منبع South24)", "(ISW)", "place63/note")
    pl[67]["note"] = _sub_once(
        pl[67]["note"],
        "؛ درگیری در الصریح/الشط، المحاوله و الاغبیره (South24).",
        "؛ درگیری در الصریح/الشط (ISW).", "place67/note")
    log.append({"section": "places", "id": "31/40/41/46/48/62/63/67",
                "action": "reworded",
                "reason": "stripped Tier C attributions; independent "
                          "evidence also supports these places"})

    # ---- claims: reword 3 confirmed, remove 4 contested, reword 1.
    confirmed = doc["claims"]["confirmed"]
    c0 = _find(confirmed, "title",
               "عقب‌نشینی نیروهای جنوب از فرودگاه ذباب و باب‌المندب در "
               "۱۵ نوامبر", "claims/confirmed-retreat")
    c0["body"] = _sub_once(
        c0["body"], "رویترز، South24، الجزیره، نشنال و عرب‌نیوز عیناً نقل کردند",
        "رویترز و الجزیره عیناً نقل کردند", "claims/confirmed-retreat")
    c1 = _find(confirmed, "title",
               "استقرار مجدد نیروهای دولت در السقیه (حدود ۱۵۰ کیلومتری "
               "غرب عدن)", "claims/confirmed-suqiya")
    c1["body"] = (
        "ISW از مواضع دفاعی تازهٔ دولت در السقیه خبر داد؛ رویترز نیز "
        "استقرار دوباره را گزارش کرد.")
    c5 = _find(confirmed, "title",
               "ادامهٔ شلیک موشک و پهپاد حوثی‌ها به سمت عربستان و عدن در "
               "۱۳–۱۵ نوامبر", "claims/confirmed-launches")
    c5["body"] = (
        "سریع مدعی شلیک موشک و پهپاد به سمت عربستان و عدن است؛ اصابت و "
        "خسارت مستقل تأیید نشده است.")
    for title in (
            "طارق صالح و مقاومت ملی",
            "اردوگاه خرز در ۱۲ نوامبر",
            "پهپادهای مکه و مدینه",
            "ادعای تلفات دولت: ۹۰۶ عملیات و «خنثی‌سازی» ۲۰۱ حوثی در "
            "۲۴ ساعت"):
        _remove_by(doc["claims"]["contested"], "title", title, log,
                   "claims.contested",
                   "Tier A/B/C: government claim with no independent "
                   "evidence")
    framing = _find(doc["claims"]["contested"], "title",
                    "قاب‌بندی عقب‌نشینی: «بازآرایی» یا «فرار»؟",
                    "claims/framing")
    framing["body"] = _sub_once(
        framing["body"],
        "نیروهای جنوب و سخنگوی دولت آن را «بازآرایی/جابه‌جایی حساب‌شده» "
        "برای حفاظت از عدن می‌خوانند — «نه شکست است، نه عقب‌نشینی» "
        "(سرهنگ وضاح الدبیش).",
        "نیروهای جنوب آن را «بازآرایی» برای حفاظت از عدن می‌خوانند.",
        "claims/framing")
    log.append({"section": "claims", "id": "confirmed/contested",
                "action": "reworded",
                "reason": "stripped Tier C attributions and the Saudi "
                          "coalition interception claim"})

    # ---- frontlines: strip sources, reword summaries/waypoints.
    fl = {f["id"]: f for f in doc["frontlines"]}

    def drop_source(front, label_fragment, ctx):
        before = len(front["sources"])
        front["sources"] = [s for s in front["sources"]
                            if label_fragment not in s.get("label", "")]
        if len(front["sources"]) != before - 1:
            raise ValueError("sourcing rule %s: source %r not found"
                             % (ctx, label_fragment))

    lahj = fl["lahj-bbm"]
    drop_source(lahj, "South24, 16 Nov 2026", "frontline/lahj-bbm")
    lahj["summary_fa"] = _sub_once(
        lahj["summary_fa"],
        "۱۶ نوامبر رویترز (از مسیر South24) به نقل از پنج منبع نظامی یمنی",
        "۱۶ نوامبر رویترز به نقل از پنج منبع نظامی یمنی",
        "frontline/lahj-bbm")
    lahj["trace_note_fa"] = _sub_once(
        lahj["trace_note_fa"], "رد بالا جغرافیای South24/ISW را دنبال می‌کند",
        "رد بالا جغرافیای ISW را دنبال می‌کند", "frontline/lahj-bbm")
    for wp in lahj["waypoints"]:
        if wp.get("name_fa", "").startswith("قاوه"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"], "(Reuters از مسیر South24، ۱۶ نوامبر)",
                "(Reuters، ۱۶ نوامبر)", "frontline/lahj-bbm/qawah")

    taiz = fl["taiz"]
    drop_source(taiz, "Shafaq News, 14 Nov 2026", "frontline/taiz")
    taiz["summary_fa"] = _sub_once(
        taiz["summary_fa"],
        " ۱۶ نوامبر دولت مدعی شد موضعی از حوثی‌ها در صبر الموادم را "
        "گرفته است — راستی‌آزمایی‌نشده.", "", "frontline/taiz")
    taiz["summary_fa"] = _sub_once(
        taiz["summary_fa"],
        "۱۱ نوامبر در جبهه‌های الشقب و الاقرود (سنمات، السریری) درگیری "
        "بود و دولت حملهٔ چندساعتهٔ حوثی‌ها به الاقرود را دفع کرد.",
        "۱۱ نوامبر در جبهه‌های الشقب و الاقرود (سنمات، السریری) درگیری بود.",
        "frontline/taiz")
    taiz["summary_fa"] = _sub_once(
        taiz["summary_fa"],
        "، در حالی که دولت ۴ و ۶ نوامبر مدعی توقف پیشروی و بازپس‌گیری "
        "مواضع شد — این شهرستان باید «مورد مناقشه» نقشه شود، نه در دست "
        "هیچ‌یک از دو طرف.",
        " — این شهرستان باید «مورد مناقشه» نقشه شود، نه در دست هیچ‌یک "
        "از دو طرف.", "frontline/taiz")
    for wp in taiz["waypoints"]:
        if wp.get("name_fa", "").startswith("صبر الموادم"):
            wp["note_fa"] = "تغییر سرزمینی در این نقطه تأیید نشده است."
        if wp.get("name_fa", "").startswith("طور الباحه"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"],
                "درگیری در ورودی آن ۲ نوامبر؛ ادعای مواضع از سوی دولت "
                "۶ نوامبر.",
                "درگیری در ورودی آن ۲ نوامبر؛ تغییر سرزمینی تأیید نشده است.",
                "frontline/taiz/tor")

    marib = fl["marib-jawf"]
    drop_source(marib, "Sheba Intelligence, 9 Nov 2026", "frontline/marib")
    drop_source(marib, "Yemen Monitor (Marib/al-Jawf)", "frontline/marib")
    drop_source(marib, "The Guardian, 16 Nov 2026", "frontline/marib")
    marib["summary_fa"] = _sub_once(
        marib["summary_fa"],
        "؛ شبا همان روز از پیشروی حدود ۱۵ کیلومتری دولت به‌سوی بیر "
        "المرازیق در شرق الجوف و تقویت حوثی‌ها از خولان از مسیر "
        "خولان–صرواح به المخدره خبر داد.", ".", "frontline/marib")
    marib["summary_fa"] = _sub_once(
        marib["summary_fa"],
        " ادعای دولت در ۱۷ نوامبر مبنی بر کنترل ارتفاعات الدهیده به نظر "
        "بازنشر ادعای ۹ نوامبر است، نه پیشروی تازه.", "",
        "frontline/marib")
    marib["summary_fa"] = _sub_once(
        marib["summary_fa"],
        " گزارش گاردین (۱۶ نوامبر) از عقب‌نشینی دولت «از بخش‌هایی از "
        "مأرب» تک‌منبع و بی‌پشتوانه است — نقشه نشود.", "",
        "frontline/marib")
    marib["confidence_fa"] = (
        "گزارش‌شده — پیشروی‌های دولت از ISW به نقل از خبرنگاران یمنی و "
        "تصاویر مکان‌یابی‌شده آمده، اما موقعیت‌های دقیق تأییدنشده‌اند")
    marib["trace_note_fa"] = _sub_once(
        marib["trace_note_fa"],
        "این رد از نام کوه‌ها و اردوگاه‌ها استنباط شده است؛ شبا صریحاً "
        "می‌گوید موقعیت‌های دقیق در دست دولت تأییدنشده‌اند.",
        "این رد از نام کوه‌ها و اردوگاه‌ها استنباط شده است؛ موقعیت‌های "
        "دقیق در دست دولت تأییدنشده‌اند.", "frontline/marib")
    for wp in marib["waypoints"]:
        name = wp.get("name_fa", "")
        if name.startswith("بیر المرازیق"):
            wp["note_fa"] = ("هدف گزارش‌شدهٔ پیشروی دولت؛ موقعیت دقیق "
                             "تأیید نشده است.")
        elif name.startswith("رغوان"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"], "حملات حوثی‌ها؛ ادعای دفع از سوی دولت "
                "۱۶ نوامبر.", "حملات حوثی‌ها؛ تغییر سرزمینی تأیید نشده است.",
                "frontline/marib/raghwan")
        elif name.startswith("رشته‌کوه جبل دهیده"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"],
                "ادعای کنترل دولت ۹ نوامبر / ۱۷ نوامبر — گزارش‌شده، "
                "تأییدنشده.",
                "کنترل مورد مناقشه — گزارش‌شده، تأییدنشده.",
                "frontline/marib/dahida")
        elif name.startswith("شهر مأرب"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"],
                "پایگاه تجمع دولت؛ پهپادهای حوثی‌ها ۱۴ نوامبر بر فراز "
                "مأرب رهگیری شدند (الجزیره).",
                "پایگاه تجمع دولت؛ پهپادهای حوثی‌ها ۱۴ نوامبر بر فراز "
                "مأرب گزارش شدند (الجزیره).", "frontline/marib/city")

    hod = fl["hodeidah"]
    drop_source(hod, "Arab News, 4 Nov 2026", "frontline/hodeidah")
    hod["summary_fa"] = _sub_once(
        hod["summary_fa"],
        "۴ نوامبر استاندار حدیده از «درگیری شدید» در ساحل غربی و هدف "
        "دولت برای بازپس‌گیری شهرستان‌های باقی‌مانده در عملیات «طلوع "
        "یمن» گفت و بیشتر آوارگان از الخوخه، حیس و مخا گریخته بودند. ",
        "", "frontline/hodeidah")
    for wp in hod["waypoints"]:
        if wp.get("name_fa", "").startswith("الخوخه"):
            wp["note_fa"] = _sub_once(
                wp["note_fa"],
                "در دست حوثی‌ها؛ بیشتر آوارگان ساحل از اینجا گریختند "
                "(Arab News، ۴ نوامبر).",
                "در دست حوثی‌ها؛ بیشتر آوارگان ساحل از اینجا گریخته‌اند.",
                "frontline/hodeidah/khokha")

    bayda = fl["bayda"]
    drop_source(bayda, "Arab News, 11 Nov 2026", "frontline/bayda")
    drop_source(bayda, "Arab News, 12 Nov 2026", "frontline/bayda")
    bayda["summary_fa"] = _sub_once(
        bayda["summary_fa"],
        "؛ مجموع حملات دولت در آن روزها (۸۷۷ حمله در ۱۱ نوامبر و ۷۰۳ "
        "حمله در ۱۲ نوامبر) الجوف، مأرب، البیضا و تعز را پوشش می‌داد، "
        "بدون نام مکان یا ادعای سرزمینی مشخص در البیضا.", ".",
        "frontline/bayda")

    saada = fl["saada"]
    drop_source(saada, "Shafaq News, 14 Nov 2026", "frontline/saada")
    log.append({"section": "frontlines", "id": "lahj-bbm/taiz/marib-jawf/"
                "hodeidah/bayda/saada",
                "action": "reworded",
                "reason": "stripped Tier A/B/C sources and "
                          "government-claim sentences; dhale and abyan "
                          "unchanged (no government-claim source)"})

    # ---- advance arrows: remove 1, reword 4.
    _remove_by(doc["advance_arrows"], "id", "arr-govt-maraziq", log,
               "advance_arrows",
               "Tier C: ~15 km advance toward Bir al-Maraziq — Sheba "
               "sole evidence for a government claim")
    ar = {a["id"]: a for a in doc["advance_arrows"]}
    ar["arr-dhubab-khor"]["source_fa"] = _sub_once(
        ar["arr-dhubab-khor"]["source_fa"],
        "ISW-CTP و Reuters، ۱۵ نوامبر؛ Reuters از مسیر South24، "
        "۱۶ نوامبر ۲۰۲۶", "ISW-CTP و Reuters، ۱۵–۱۶ نوامبر ۲۰۲۶",
        "arrow/dhubab-khor")
    ar["arr-khor-qawah"]["confidence_fa"] = (
        "گزارش‌شده — خطوط تازه مستقل راستی‌آزمایی نشده‌اند")
    ar["arr-khor-qawah"]["source_fa"] = _sub_once(
        ar["arr-khor-qawah"]["source_fa"],
        "Reuters از مسیر South24 (پنج منبع نظامی یمنی)، ۱۶ نوامبر ۲۰۲۶",
        "Reuters (پنج منبع نظامی یمنی)، ۱۶ نوامبر ۲۰۲۶",
        "arrow/khor-qawah")
    ar["arr-govt-hazm"]["confidence_fa"] = (
        "گزارش‌شده — موقعیت‌های دقیق تأییدنشده‌اند")
    ar["arr-govt-hazm"]["source_fa"] = _sub_once(
        ar["arr-govt-hazm"]["source_fa"],
        "ISW-CTP، ۹ و ۱۲ نوامبر؛ شبا اینتلیجنس، ۹ نوامبر ۲۰۲۶",
        "ISW-CTP، ۹ و ۱۲ نوامبر ۲۰۲۶", "arrow/govt-hazm")
    khaw = ar["arr-khawlan-sirwah"]
    khaw["note_fa"] = _sub_once(
        khaw["note_fa"],
        "؛ شبا فرماندهی را به «ابو طاها الشرافی» نسبت می‌دهد — "
        "موقعیت/فرماندهی راستی‌آزمایی‌نشده.", ".", "arrow/khawlan")
    khaw["confidence_fa"] = "گزارش‌شده — موقعیت دقیق تأییدنشده است"

    # ---- unit positions: remove 2, reword 4.
    def drop_unit(name, reason):
        _remove_by_pred(doc["unit_positions"],
                         lambda u, n=name: u.get("name_fa") == n,
                         log, "unit_positions", name, reason)

    drop_unit("نیروهای سپر میهن (سپر ملی)",
              "Tier B: 17 Nov al-Mahawila claim — government-claim-only, "
              "relayed by Al Jazeera, unverified")
    drop_unit("نیروهای دفاع شبوه و یگان‌های منطقهٔ سبأ",
              "Tier A/B: Bayda operations — Arab News relay of "
              "government strike claims (877/703)")
    un = {u["name_fa"]: u for u in doc["unit_positions"]}
    saf = un["نیروهای مسلح جنوب"]
    saf["source_fa"] = _sub_once(
        saf["source_fa"],
        "بیانیهٔ نیروهای مسلح جنوب؛ ISW-CTP و Reuters، ۱۵ نوامبر ۲۰۲۶",
        "ISW-CTP و Reuters، ۱۵ نوامبر ۲۰۲۶", "unit/southern-forces")
    saf["note_fa"] = _sub_once(
        saf["note_fa"],
        "۱۵ نوامبر بازآرایی از فرودگاه ذباب و منطقهٔ باب‌المندب برای "
        "دفاع از عدن را خودشان اعلام کردند؛",
        "۱۵ نوامبر از فرودگاه ذباب و منطقهٔ باب‌المندب برای دفاع از عدن "
        "عقب نشستند (ISW و Reuters)؛", "unit/southern-forces")
    saf["confidence_fa"] = "تأییدشده (ISW-CTP و Reuters)"
    marib_u = un["ارتش یمن — مناطق نظامی ۳، ۶ و ۷، سپر ملت/میهن و نیروهای "
                "اضطراری"]
    marib_u["source_fa"] = _sub_once(
        marib_u["source_fa"], "ISW-CTP، ۹–۱۲ نوامبر؛ شبا، ۹ نوامبر ۲۰۲۶",
        "ISW-CTP، ۹–۱۲ نوامبر ۲۰۲۶", "unit/marib")
    khaw_u = un["تقویت‌های انصارالله از خولان"]
    khaw_u["note_fa"] = (
        "تقویت‌ها از مسیر خولان–صرواح به المخدره رسیدند؛ نام فرماندهٔ "
        "میدانی در منابع علنی بررسی‌شده نیامده و نامشخص است.")
    khaw_u["confidence_fa"] = "گزارش‌شده — موقعیت دقیق تأییدنشده است"
    taiz_u = un["ارتش یمن — محور تعز و مقاومت مردمی"]
    taiz_u["note_fa"] = _sub_once(
        taiz_u["note_fa"],
        "نیروهای محور تعز و مقاومت مردمی جیب شهر را نگه داشته‌اند؛ "
        "۱۱ نوامبر حملهٔ چندساعتهٔ حوثی‌ها به الاقرود دفع شد.",
        "نیروهای محور تعز و مقاومت مردمی جیب شهر را نگه داشته‌اند؛ "
        "۱۱ نوامبر در جبهه‌های الاقرود/الشقب درگیری بود.",
        "unit/taiz")

    # ---- forces: strip 2 sentences.
    forces = {f["name"]: f for f in doc["forces"]}
    plc = forces["دولت / شورای رهبری (PLC)"]
    plc["body"] = _sub_once(
        plc["body"],
        "؛ فرمانده نیروهای مشترک سعودی ۱۶ نوامبر با فرمان سلطنتی عوض شد "
        "(ژنرال فیاض الرویلی جای سپهبد فهد بن حمد السلمان).", "",
        "forces/plc")
    nr = forces["مقاومت ملی (طارق صالح)"]
    nr["body"] = _sub_once(
        nr["body"],
        " یک منبع مقاومت ملی به شبا (۱۲ نوامبر) کاهش استقرار را "
        "«بازتوزیع دستورگرفته از بالا» خواند، نه رها کردن مواضع — "
        "اختلاف حل‌نشده است.", "", "forces/national-resistance")

    # ---- military features: strip Yemen Monitor / Sheba-claim clauses.
    mf = {m["title_fa"]: m for m in doc["military_features"]}
    route = mf["مسیر تدارکات صنعا–مأرب/الجوف از تقاطع الجوف (الجدعان)"]
    route["body_fa"] = _sub_once(
        route["body_fa"],
        " دولت در هفتهٔ اول نوامبر بر بخش‌هایی از مسیرهای تدارکاتی "
        "العلم/اللبنات کنترل آتش گرفت (Yemen Monitor).", "",
        "milfeat/route")
    route["source_fa"] = _sub_once(
        route["source_fa"], "شبا اینتلیجنس، ۹ نوامبر؛ Yemen Monitor، نوامبر ۲۰۲۶",
        "شبا اینتلیجنس، ۹ نوامبر ۲۰۲۶", "milfeat/route")
    route["confidence_fa"] = "گزارش‌شده (شبا اینتلیجنس)"
    hazm = mf["محور دولت به‌سوی الحزم (شمال‌غرب مأرب / جنوب الجوف)"]
    hazm["body_fa"] = _sub_once(
        hazm["body_fa"],
        " و بر بخش‌هایی از مسیرهای تدارکاتی العلم/اللبنات کنترل آتش گرفت.",
        "", "milfeat/hazm")
    hazm["source_fa"] = _sub_once(
        hazm["source_fa"], "ISW-CTP، ۹ و ۱۲ نوامبر؛ Yemen Monitor، نوامبر ۲۰۲۶",
        "ISW-CTP، ۹ و ۱۲ نوامبر ۲۰۲۶", "milfeat/hazm")
    hazm["confidence_fa"] = "گزارش‌شده (ISW-CTP)"
    dah = mf["رشته‌کوه جبل دهیده و ارتفاعات الدهیده"]
    dah["body_fa"] = (
        "ارتفاعات دهیده در شرق الحزم: دولت ۹ نوامبر مدعی بیرون راندن "
        "حوثی‌ها از مواضع باقی‌مانده در رشته‌کوه شد (ISW: تصاویر "
        "مکان‌یابی‌شده حضور نیروهای دولتی را نشان داد).")
    dah["source_fa"] = "ISW-CTP، ۹ نوامبر ۲۰۲۶"
    siege = mf["ادعای محاصرهٔ عدن از سوی انصارالله — ردشده"]
    siege["body_fa"] = _sub_once(
        siege["body_fa"], "اما Reuters، AFP، ISW و South24 در ۱۵–۱۶ نوامبر",
        "اما Reuters، AFP و ISW در ۱۵–۱۶ نوامبر", "milfeat/siege")
    siege["source_fa"] = _sub_once(
        siege["source_fa"],
        "PressTV، ۱۳ نوامبر؛ Reuters/South24/ISW، ۱۵–۱۶ نوامبر ۲۰۲۶",
        "PressTV، ۱۳ نوامبر؛ Reuters/ISW، ۱۵–۱۶ نوامبر ۲۰۲۶",
        "milfeat/siege")

    # ---- top-level key points: drop the Sheba sentence on Khor Omairh.
    kp = _find(doc["key_points"], "name", "خور عمیره",
               "keypoints/khor-omairh")
    kp["basis"] = _sub_once(
        kp["basis"],
        " منابع شبا در ۱۲ نوامبر هنوز خور عمیره را دست ارتش یمن "
        "می‌دانستند؛", "", "keypoints/khor-omairh")

    # ---- disagreements: strip South24 casualty figures + Shafaq.
    dis = {d["title"]: d for d in doc["disagreements"]}
    dis["تلفات موشک خور عمیره (۱۵ نوامبر)"]["body"] = (
        "AFP: ‏۵ کشته/۸ زخمی ← به‌روزرسانی AFP: ‏۸/۱۲. صورت درست: "
        "«دست‌کم ۸ کشته (AFP)» با تاریخ.")
    dis["فاصله تا عدن"]["body"] = _sub_once(
        dis["فاصله تا عدن"]["body"], "AFP/شینهوا: ۶۰ کیلومتر (خرز، محور داخلی)",
        "AFP: ۶۰ کیلومتر (خرز، محور داخلی)", "disagreements/distance")
    dis["حملات عربستان"]["body"] = _sub_once(
        dis["حملات عربستان"]["body"],
        "سریع: ۷۵ حمله در ۲۴ ساعت (۱۵ نوامبر، از شفق) در برابر ۸۸ حمله "
        "در ۲۴ ساعت (۱۶ نوامبر، لایوبلاگ الجزیره) — بیانیه‌ها/بازه‌های "
        "متفاوت، فقط از سوی حوثی‌ها؛ طرف سعودی از رهگیری‌ها می‌گوید، نه "
        "شمار حملات.",
        "سریع: ۷۵ حمله در ۲۴ ساعت (۱۵ نوامبر) در برابر ۸۸ حمله در "
        "۲۴ ساعت (۱۶ نوامبر، لایوبلاگ الجزیره) — بیانیه‌ها/بازه‌های "
        "متفاوت، فقط از سوی حوثی‌ها.", "disagreements/strikes")

    # ---- map reading: strip Sheba + South24 attributions.
    mr = {m["title"]: m for m in doc["map_reading"]}
    mr["«4 BO» و «5 BO» یعنی چه؟ — تأییدنشده"]["body"] = _sub_once(
        mr["«4 BO» و «5 BO» یعنی چه؟ — تأییدنشده"]["body"],
        " و گزارش شبا از تهاجم دومحوره (الحجاج/الافج؛ و المضاربه از مسیر "
        "الحیمه به سمت تقاطع الصریح)", "", "mapreading/bo")
    mr["هشدار نام‌گذاری — «فرودگاه زقر»"]["body"] = _sub_once(
        mr["هشدار نام‌گذاری — «فرودگاه زقر»"]["body"],
        "بیانیهٔ خود نیروهای جنوب، به نقل رویترز و South24، «فرودگاه "
        "ذباب» می‌گوید.",
        "بیانیهٔ خود نیروهای جنوب، به نقل رویترز، «فرودگاه ذباب» "
        "می‌گوید.", "mapreading/zaqar")

    # ---- top-level open questions: strip the Shafaq relay mention.
    doc["open_questions"] = [
        _sub_once(
            q,
            "ادعاهای حوثی‌ها از نقل‌قول‌های المسیره در شفق/فلسطین کرونیکل "
            "و بیانیه‌های سریع از طریق ISW/الجزیره آمده است.",
            "ادعاهای حوثی‌ها از نقل‌قول‌های المسیره در فلسطین کرونیکل و "
            "بیانیه‌های سریع از طریق ISW/الجزیره آمده است.",
            "openquestions/masirah")
        if "نقل‌قول‌های المسیره در شفق" in q else q
        for q in doc["open_questions"]
    ]

    # ---- Aden: timeline -1, cross-check rewords, contradictions -1,
    # images -1, sources -5 (renumbered), layers/key-points rewords.
    aden = doc["aden"]
    _remove_by(aden["timeline"], "date", "2026-11-17", log, "aden.timeline",
               "Tier B: 661-attacks claim — government-claim-only, "
               "relayed by Al Jazeera")
    tl12 = _find(aden["timeline"], "date", "2026-11-12", "aden/timeline12")
    tl12["body_fa"] = _sub_once(
        tl12["body_fa"],
        "همان روز شبا اینتلیجنس نوشت خور عمیره، رأس العاره و ساحل تا "
        "باب‌المندب «همچنان در کنترل ارتش یمن» است و نبرد به مناطق "
        "ساحلی نرسیده — یعنی تیتر «تکمیل محاصره» در ۱۳ نوامبر حتی با خط "
        "زمانی خودش هم زودهنگام بود.",
        "تا ۱۲ نوامبر ساحل هنوز در دست دولت بود (عقب‌نشینی ۱۵ نوامبر "
        "نشان می‌دهد نبرد تا آن روز به مناطق ساحلی نرسیده بود) — یعنی "
        "تیتر «تکمیل محاصره» در ۱۳ نوامبر حتی با خط زمانی خودش هم "
        "زودهنگام بود.", "aden/timeline12")
    tl12["source_fa"] = _sub_once(
        tl12["source_fa"], "PressTV، ۱۳ نوامبر؛ Sheba Intelligence، "
        "۱۲ نوامبر ۲۰۲۶", "PressTV، ۱۳ نوامبر؛ ISW-CTP، ۱۵ نوامبر ۲۰۲۶",
        "aden/timeline12")
    tl15b = [t for t in aden["timeline"]
             if t.get("title_fa", "").startswith("المسیره: مانور پهلوگیری")]
    if len(tl15b) != 1:
        raise ValueError("aden timeline Al-Masirah entry not found once")
    tl15b[0]["source_fa"] = _sub_once(
        tl15b[0]["source_fa"], "Al-Masirah به نقل از Shafaq؛ Al Mayadeen، "
        "۱۵ نوامبر ۲۰۲۶ — نمایه",
        "Al-Masirah؛ Al Mayadeen، ۱۵ نوامبر ۲۰۲۶ — نمایه",
        "aden/timeline15b")

    cc = aden["cross_check"]
    aj = [c for c in cc if c.get("source", "").startswith("Al Jazeera، ۱۶")]
    if len(aj) != 1:
        raise ValueError("aden cross-check Al Jazeera row not found once")
    aj[0]["says_fa"] = _sub_once(
        aj[0]["says_fa"],
        " دولت مدعی ۶۶۱ حمله در ۱۶ نوامبر و ۱۷۳ کشته و زخمی حوثی است.",
        "", "aden/crosscheck-aj")
    s24 = [c for c in cc if c.get("source", "").startswith("South24")]
    if len(s24) != 1:
        raise ValueError("aden cross-check South24 row not found once")
    s24[0]["source"] = ("Reuters؛ ISW-CTP، ۱۵ نوامبر ۲۰۲۶ — راستی‌آزمایی زنده")
    s24[0]["says_fa"] = (
        "نیروهای جنوب از ذباب/باب‌المندب به السقیه منتقل شدند تا دفاع از "
        "عدن را تقویت کنند؛ بیانیه نگفت همهٔ یگان‌ها عقب نشستند. محاصرهٔ "
        "عدن را تأیید نمی‌کند: استقرار دوباره برای دفاع از عدن و حمله‌های "
        "موشکی به عدن را گزارش می‌کند، نه محاصره.")
    sheba = [c for c in cc if c.get("source", "").startswith("Sheba")]
    if len(sheba) != 1:
        raise ValueError("aden cross-check Sheba row not found once")
    sheba[0]["source"] = ("ISW-CTP، ۱۵ نوامبر ۲۰۲۶ — راستی‌آزمایی زنده")
    sheba[0]["says_fa"] = (
        "پیشروی حوثی‌ها در المضاربه و العاره و نبرد در الصریح و الشط؛ تا "
        "۱۲ نوامبر ساحل هنوز در دست دولت بود (عقب‌نشینی ۱۵ نوامبر) و نبرد "
        "به آن مناطق ساحلی نرسیده بود. محاصرهٔ عدن را تأیید نمی‌کند — و "
        "خط مبنای پیش از عقب‌نشینی است: نشان می‌دهد ساحل ۱۲ نوامبر هنوز "
        "در دست دولت بود، یعنی تیتر «تکمیل محاصره» در ۱۳ نوامبر حتی در خط "
        "زمانی خودش هم زودهنگام بود.")

    _remove_by_pred(
        aden["contradictions"],
        lambda c: c.get("title", "").startswith("۴. ارقام عملیات و تلفات دولت"),
        log, "aden.contradictions", "4. government figures",
        "Tier A/B: government operations/casualty figures (20,000 / "
        "661 / 48,576) — unverified government claims")
    contra2 = [c for c in aden["contradictions"]
               if c.get("title", "").startswith("۲. فاصله‌ها")]
    if len(contra2) != 1:
        raise ValueError("aden contradiction on distances not found once")
    contra2[0]["title"] = _sub_once(
        contra2[0]["title"],
        "فاصله‌ها: ۶۰ کیلومتر (AFP، اردوگاه خرز) در برابر ۷۰ (شین‌هوا) "
        "در برابر ۸۰ (رویترز، طور الباحه) در برابر ۱۰۰ (رویترز، خور "
        "عمیره؛ ISW، جبل خرز)",
        "فاصله‌ها: ۶۰ کیلومتر (AFP، اردوگاه خرز) در برابر ۸۰ (رویترز، "
        "طور الباحه) در برابر ۱۰۰ (رویترز، خور عمیره؛ ISW، جبل خرز)",
        "aden/contradictions2")
    aden["verdict"]["confidence_fa"] = _sub_once(
        aden["verdict"]["confidence_fa"],
        "(۶۰ در برابر ۷۰ در برابر ۸۰ کیلومتر)",
        "(۶۰ در برابر ۸۰ در برابر ۱۰۰ کیلومتر)", "aden/verdict")

    _remove_by_pred(
        aden["images"],
        lambda im: "شبا اینتلیجنس" in im.get("name", ""),
        log, "aden.images", "Sheba map 25 Oct",
        "Tier C: Sheba map — sole evidence for the government-control "
        "depiction; already judged outdated in its own verdict")
    for fragment in ("5. South24", "6. South24", "7. Sheba",
                     "11. Al Jazeera — «ارتش یمن از ۶۶۱ حمله",
                     "17. Shafaq"):
        _remove_by_pred(aden["sources"],
                         lambda s, f=fragment: s.get("label", "").startswith(f),
                         log, "aden.sources", fragment,
                         "Tier A/B/C: government-claim relay / aligned "
                         "outlet")
    for i, s in enumerate(aden["sources"]):
        label = s["label"]
        body = label.split(". ", 1)[1] if ". " in label else label
        s["label"] = "%d. %s" % (i + 1, body)

    layers = aden["layers"]["confirmed"]
    layers[0]["body"] = _sub_once(
        layers[0]["body"], "(AFP، Reuters، ISW، الجزیره، South24 — همگرا.)",
        "(AFP، Reuters، ISW، الجزیره — همگرا.)", "aden/layers0")
    layers[1]["body"] = _sub_once(
        layers[1]["body"],
        "(بیانیهٔ نیروهای جنوب به نقل از South24/الجزیره/Reuters؛ ISW.)",
        "(بیانیهٔ نیروهای جنوب به نقل از الجزیره/Reuters؛ ISW.)",
        "aden/layers1")
    layers[2]["body"] = _sub_once(
        layers[2]["body"], "(Reuters، South24، AFP.)", "(Reuters، AFP.)",
        "aden/layers2")
    official = aden["layers"]["official_claims"]
    official[0]["body"] = _sub_once(
        official[0]["body"],
        "شبا می‌گوید ساحل ۱۲ نوامبر هنوز در دست دولت بود؛ ISW می‌گوید",
        "ISW می‌گوید", "aden/layers-official0")

    # Aden timeline 15 Nov: the redeployment is corroborated by
    # Al Jazeera / Reuters — strip the South24 attribution.
    tl15 = [t for t in aden["timeline"]
            if t.get("title_fa", "").startswith("عقب‌نشینی دولت از باب‌المندب")]
    if len(tl15) != 1:
        raise ValueError("aden timeline 15 Nov retreat entry not found once")
    tl15[0]["body_fa"] = _sub_once(
        tl15[0]["body_fa"], "(South24، الجزیره، Reuters)",
        "(الجزیره، Reuters)", "aden/timeline15")

    # Aden key points arrive as plain body strings (the generator maps
    # them with as_str_list); match by content anchor.
    kp_fired = 0
    for i, text in enumerate(aden["key_points"]):
        if text.startswith("PressTV (۱۳ نوامبر): اولتیماتوم"):
            kp_fired += 1
            aden["key_points"][i] = (
                "PressTV (۱۳ نوامبر): اولتیماتوم به نیروهای آنجا — ادعا، "
                "راستی‌آزمایی‌نشده. ISW (۱۵ نوامبر): مواضع دفاعی دولت در "
                "السقیه؛ نیروهای جنوب پس از ترک ذباب به السقیه منتقل شدند. "
                "تصرف آن به دست حوثی‌ها تأیید نشده است.")
        elif "فرودگاه ذباب، جبل کهبوب و الشط را گرفتند" in text and \
                "(South24، الجزیره، Reuters)" in text:
            kp_fired += 1
            aden["key_points"][i] = text.replace(
                "(South24، الجزیره، Reuters)", "(الجزیره، Reuters)")
        elif "المسیره (به نقل از شفق، ۱۵ نوامبر)" in text:
            kp_fired += 1
            aden["key_points"][i] = _sub_once(
                text,
                " المسیره (به نقل از شفق، ۱۵ نوامبر) مدعی کنترل «از کهبوب "
                "تا خور عمیره» شد.",
                " المسیره مدعی کنترل «از کهبوب تا خور عمیره» شد.",
                "aden/keypoints-khor")
            aden["key_points"][i] = _sub_once(
                aden["key_points"][i],
                " شبا (۱۲ نوامبر، اکنون منسوخ): خور عمیره آن زمان در کنترل "
                "ارتش یمن بود.", "", "aden/keypoints-khor2")
    if kp_fired != 3:
        raise ValueError("aden key-points: expected 3 rewords, fired %d"
                         % kp_fired)

    for ch in aden["channels_official"]:
        if ch.get("name", "").startswith("تلویزیون المسیره"):
            ch["body_fa"] = _sub_once(
                ch["body_fa"], " (به گزارش Shafaq، ۱۵ نوامبر)", "",
                "aden/channels-masirah")
    aden["open_questions"] = [
        _sub_once(
            q,
            "ادعاهایشان فقط از گزارش ثانویه شناخته می‌شود (شفق، الجزیره، "
            "پانویس‌های ISW).",
            "ادعاهایشان فقط از گزارش ثانویه شناخته می‌شود (الجزیره، "
            "پانویس‌های ISW).", "aden/openquestions")
        if "گزارش ثانویه شناخته می‌شود (شفق" in q else q
        for q in aden["open_questions"]
    ]
    log.append({"section": "aden", "id": "timeline/cross-check/sources",
                "action": "reworded",
                "reason": "removed the 661-attacks timeline entry and "
                          "all Tier A/B/C attributions; sources renumbered"})

    # ---- recompute counts (places untouched in number, everything
    # else shrank by the removals above).
    doc["counts"] = {
        "places": len(doc["places"]),
        "events": len(doc["events"]),
        "frontlines": len(doc["frontlines"]),
        "arrows": len(doc["advance_arrows"]),
        "unit_positions": len(doc["unit_positions"]),
        "forces": len(doc["forces"]),
        "sources": len(doc["sources"]),
    }
    doc["faction_counts"] = {}
    for p in doc["places"]:
        f = p["faction"]
        doc["faction_counts"][f] = doc["faction_counts"].get(f, 0) + 1
    return log


def apply_taxonomy(doc):
    """Add event_type / confidence / front to every event, and
    taxonomy_version at top level. Raises if an event is unclassified
    (a new event must get an explicit classification, never a guess)."""
    for e in doc["events"]:
        eid = e["external_id"]
        if eid not in EVENT_TAXONOMY:
            raise ValueError("taxonomy: no classification for event %r — "
                             "classify it explicitly in EVENT_TAXONOMY"
                             % eid)
        event_type, confidence = EVENT_TAXONOMY[eid]
        assert event_type in EVENT_TYPES and confidence in CONFIDENCES
        e["event_type"] = event_type
        e["confidence"] = confidence
        e["front"] = "yemen"
    doc["taxonomy_version"] = TAXONOMY_VERSION


def check_no_forbidden_strings(doc):
    """Return the list of MUST_NOT_SURVIVE strings still present in
    the document (empty = clean). Live feed posts are excluded: they
    are verbatim Ansarullah text fetched at run time, and ordinary
    Arabic words in them (e.g. «شفق» = dawn) must not trip the gate
    that guards the curated dashboard data."""
    scrubbed = {k: v for k, v in doc.items() if k != "feeds"}
    text = json.dumps(scrubbed, ensure_ascii=False)
    return [s for s in MUST_NOT_SURVIVE if s in text]


def remaining_tier_c_mentions(doc):
    """Count surviving Tier C outlet mentions (kept deliberately, with
    reasons in SOURCING-RULES.md) — informational, not a failure."""
    text = json.dumps(doc, ensure_ascii=False)
    return {name: text.count(name)
            for name in ("South24", "شبا", "Sheba")}
