# ECO JOURNAL — Big Update Plan (investigation + planning ONLY)

**Status: PLAN ONLY. No execution. No live change.**
The freeze stays ON. No cron, bot, channel post, or service was enabled,
started, or changed in producing this plan. No GitHub push was made.
The freeze lift is NOT part of this plan's execution — it is a separate,
later decision by Yasin, after the update is proven connected (see §7).

**Goal (Yasin, 2026-10-25):** the app must be able to do updates.
Everything the channel was defined to output — systems, editorial, blog —
must live in the app. News posted to the channel must appear in the app
shortly after. Every night there must be a big blog update. Every frozen
channel thing must be inspected and intelligently placed in the app, with
a forecast of where each thing goes. NOTHING missed, NO service left out.
ML must be updated to match the new platform logic and structure.
Prioritisation and timing matter.

**How this plan was built:** four parallel read-only audits (2026-10-25):
channel systems (36 services, 24/24 frozen crons named), editorial + blog
(33 elements), ML (full pipeline + war forecast), app current state
(vNext M1–M9, edge v2, Pages live). Where auditors disagreed or could not
verify, this plan says so explicitly (§8) instead of picking a number
silently. Known discrepancies carried forward, not hidden:
- "24 crons" = the count active at the 2026-10-24 freeze. The cron
  directory holds 46 recurring definitions + 13 run-once files; the rest
  were already off before the freeze (notably 18 desk crons switched off
  under the 2026-10-22 war-room-only order). Reconciliation is a Phase 1
  task, not an assumption.
- `channel_posts` (23 rows) vs `content_queue.queue` (508 rows, 495 sent)
  are different tables/systems. Neither alone is "the channel total".
- The "nightly big blog update" was NOT found as a named, scheduled
  service. §4.2 designs it as a new composite from existing pieces.
  It is a proposal, not a recovered spec.
- ML numbers: full-text coverage is 25.0% → 80.8% in the files (not
  29% → 80.9%); scorecard windows are 7d + 30d (14d is the rolling-report
  default and the Phase-1 pass-criterion duration, not a scorecard
  window); the Oct 28 v0 run and the Nov 6 gate projection are different
  milestones. Registry research metrics (e.g. gold walk-forward 0.897)
  are pre-audit and leakage-inflated — quarantined, never quoted as
  model quality.

**Standing rules that constrain this plan (not re-decided here):**
- Law 14: bot posts to the channel ONLY — no personal DMs. App local
  notifications are device notifications, not DMs, and do not violate
  Law 14 — but no app feature may DM a reader either.
- Law 15 / night law: day score ≥70 → instant; night 00:00–08:00 only
  ≥85 instant, 70–84 → 08:00 morning bundle. FLASH exempt; at L3/L4,
  WATCH also exempt at night. The app must enforce the same rule in its
  notification path, not just the publisher.
- Delegated editorial authority (2026-10-18): Muse approves/rejects,
  Yasin is observer. Provenance `muse-editorial (delegated by Yasin
  2026-10-18)`. Labels distill the documented policy, not Yasin's taste.
- BLOG_LIVE=False: no blog button/link in channel posts until reopen is
  ordered. In-app blog surfaces must not depend on a live external blog
  button until then.
- Telemetry decision (vNext-plan §2.6): diagnostics-only, local-first.
  No article content, codes, tokens, device ids, URLs with parameters,
  analytics SDK, location, or contacts. No app check-in path exists.
  Reads/bookmarks/searches/notification-opens are on-device only and
  are NOT available to server-side ML without a new, explicit Yasin
  privacy decision + opt-in pipeline. This plan does NOT assume them.
- FCM decision (vnext-decisions §3): NO Firebase/FCM for the pilot.
  Push is a decision-revisit option in this plan (§4.1.4), not an
  enabled component.
- Special issues: separately frozen 2026-10-25 ~01:56, own decision.
  Nothing in this plan auto-unfreezes it.
- Debug signing: shared debug keystore (GitHub secret) proven 2026-10-25
  (two independent builds, identical cert). All future cloud builds
  update over each other. Release signing is a separate later decision.

---

## 1. Service register — every inventoried service, and where it goes

Placement key:
- **APP:** a reader-facing surface in the app (screen / section / badge /
  filter / notification type). This is the default — exclusions need a reason.
- **PIPE:** pipeline/internal only — feeds the app but has no reader
  surface of its own. Still implemented, just not as a screen.
- **NOT-IN-APP:** explicitly not in the app, with justification. These are
  the only services allowed to stay out, and each carries its reason.

### 1.1 Live news, war room, and alerts

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S01 | War Room OS core — crisis level, adaptive radar intervals, unified urgency, night-block decision, crisis-adjusted caps, volume pace | APP: **War Room section** (top-level section, not a feed tab). Shows current crisis level L1–L4, radar cadence, and a "why this level" line. Level drives caps and notification policy app-side. |
| S02 | War events store — event ingestion, status/outcome, daily chronicle | PIPE: event → story → edge article pipeline. APP surface: the story's chronicle renders as the "story timeline" inside the reader (updates chained under the original, per the reply-chain format). |
| S03 | War signals + shadow model — signal store, source-trust scores, war features | PIPE: internal only. Never a reader surface. Feeds the ML track (§6), not the feed. |
| S04 | Hotlane — conditional-fetch fast lane for tier-0 strike queries | PIPE: ingest fast lane only. No reader surface. Its output lands via S06/S07. |
| S05 | Red Alert format engine — 7 formats (FLASH, WATCH, GEOPOL, MARKET, VERIFY, SITREP, WIRE) with word budgets and validation | APP: **format as a first-class type everywhere** — format badge on every card, format filter in feed, format-specific rendering (verdict line for VERIFY, prices line for MARKET, exactly-2 watch-next for SITREP). New edge/Room fields: `format_type`, `urgency_score`, `crisis_level` (see §3). |
| S06 | Red Alert watch runner — stage / publish / flush / catchup | PIPE: publisher-side runner. APP surface: its outputs arrive as S05-typed items; the 08:00 flush arrives as the **Morning Bundle** (§1.2). |
| S07 | SITREP builder — 2-hour briefing, 180–260 words, 5 fixed sections, silence if nothing meaningful | APP: **War Room section → SITREP cards**, one per briefing, with issue time and crisis level. Silence rule preserved: no empty SITREP is ever published to the app. |
| S08 | Urgent watch — instant 🔴 فوری + pin, night-held release, old-pin removal, short-news bundle | APP: **Urgent rail at the top of the feed** + urgent notification channel (high importance, sound) for ≥70 day / ≥85 night only. Night-held items (70–84) appear in the Morning Bundle, never as an instant notification. Pin = the urgent rail's first card until superseded or 6h old. |
| S09 | Wire fast poll — new wire rows into data core only | PIPE: ingest only. WIRE items that pass the gate arrive as S05 WIRE cards. |
| S10 | Feed ingest — 73 war-room feeds + general ingest list (23-feed wire subset is a different set; reconcile in Phase 1) | PIPE: ingest only. Feed-count reconciliation (73 vs 23) is a Phase 1 data task. |
| S11 | Geopolitics radar (bot) — keyword hits → keypoints drafts | APP: **Radar section** (inside War Room): radar signals as a list with severity, source, and linked story. Category `geopolitics` is NOT the radar — the radar is signals, not a tab. |
| S12 | News desk slots — morning/midday/evening/night slot posts | APP: **Feed slots** — the feed's day view groups slot posts under their slot (morning/midday/evening/night) instead of a flat list. Slot identity is a new edge field (`slot`). |

### 1.2 Desks, digests, bundles, and the publisher path

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S13 | Freewire (سیستم آزاد) — single semi-important items, rewrite, source name only, no link | APP: Feed, as WIRE/freewire cards with the "source name only, no link" rule preserved in rendering (no source button for these cards). |
| S14 | Night desk — "جامانده‌های دیشب", 1–3 messages, 12–30 items/night, 00:05 | APP: **Night Desk card in the Morning Bundle** and in the nightly edition (§4.2). Never a standalone midnight push (night law). First auto-run was scheduled after the freeze and never verified as run — Phase 2 must verify it in dry-run before it ships. |
| S15 | Marginalia + economic hourly — marginalia notes + 8 daily bundles, hourly economic digests | APP: **Bundles section**: marginalia notes as a bundle type, hourly digests as a time-grouped bundle. Bundle identity is a new edge/Room field (`bundle_id` + position). |
| S16 | Editorial pass "خبر مهم" + foreign quota — delegated approve/reject, 3–5 English items/day as drafts | PIPE + APP: the pass is the gate that creates app items (see §5, Phase 2). Foreign-quota items arrive as translated briefs with their source-diversity note; no separate "foreign" tab. |
| S17 | Channel caps + volume governor — per-format caps, total 300/day, floor 250, crisis redistribution | PIPE + APP: caps are enforced publisher-side AND reflected app-side: the feed shows a "today's pace" line in Diagnostics (counts only, no content), and the governor's decisions (held/queued) are visible as bundle membership, never as missing content. |
| S18 | Digests & bundles — slot bundles, ≤1 short-news bundle/day (≥3 items), morning digest (disabled 2026-10-22, stays off unless re-decided) | APP: **Bundles section** + Morning Bundle (08:00). The disabled morning digest is NOT revived by this plan — it needs its own decision, recorded as an open item (§8). |
| S19 | Publisher — single channel-send path, pins for FLASH/urgent, blog button only while BLOG_LIVE | PIPE: the send path becomes the **edge-publish path** (§3, §4.1). App-side, the pin rule becomes the urgent rail (S08). Blog button stays OFF in the app until the BLOG_LIVE decision is revisited. |
| S20 | Channel watch — silence monitor (exit 0/1/2, >150 min silence in active hours = ALERT) | PIPE + APP (diagnostics only): silence/floor status appears in Diagnostics as an aggregate health line (e.g. "last edge publish X ago"), never as reader content. Owner alerts stay owner-only. |

### 1.3 Market

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S21 | Market board + formulas — live prices + 4 computed fields (implied_usd, intrinsic_emami, bubble_pct, spread_pct) | APP: **Market tab — ALREADY BUILT (M4)**. All 15 snapshot fields, evaluated formulas, change vs previous, 24h staleness label. Phase 4 adds history/charts and intraday series (new time-series table). Server-side market features must match the app's exact formulas or train/serve skew appears (ML track). |
| S22 | Market pulse — periodic war-market pulse post, 08:00–23:00 only, only if snapshot fresh and not already sent | APP: **Market tab → Pulse cards**, time-stamped, suppressed outside 08:00–23:00 and when stale — same suppression rules as the channel, enforced in the edge generator. |
| S23 | Market watch (slot) — board read with bubble/spread commentary, morning/evening slots | APP: **Market tab → Watch commentary** attached to the snapshot it comments on (not a floating post). Skipped at cap, as before. |
| S24 | Bubble/spread alerts — 🚨 حباب سکه / 🚨 اسپرد تتر on admin threshold cross, 12h cooldown per metric | APP: **Market tab → Alert banners** + a dedicated market-alert notification type (separate from urgent news). Threshold values live in settings (admin-configured; code default 0 = off — the actual admin values were not read in audit and must be recovered in Phase 4, not guessed). Cooldown 12h per metric preserved. |

### 1.4 Editorial governance, pipeline, and rendering rules

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S25 | Editorial rubric + delegated decisions — R1–R7 reject gates, A1–A5 approve conditions, +1/−1 labels, decision log + provenance | APP (governance, not a screen): every item carries its provenance label ("تحریریهٔ ECO JOURNAL", never a personal name, never an AI trace). The rubric itself is published in-app as **About → Method** (summary, not the full internal rubric). Decisions feed the ML track, not the feed. |
| S26 | Pipeline items → extracts → drafts → approved → posts (data_core + content_queue) | PIPE: this IS the edge-publish pipeline (§3). The app never reads data_core directly; it reads the edge. The two-system count gap (channel_posts 23 vs queue 508) is resolved by making the edge the single published truth, with the queue as the staging record. |
| S27 | Style guide v1 + translation glossary + text edit — dry precise tone, Persian half-spaces, Persian digits, Shamsi date + Tehran time, one story = one card, no card without a healthy source link | APP (rendering rules, all screens): Persian digits everywhere, Shamsi + Tehran time in bylines, half-space rendering, one story = one card (duplicates merged by story_id, see S34). Labels fixed: «بازنشر از {source}» vs «ترجمهٔ فارسی تحریریه» — never confused. Boilerplate scraper sentences always stripped. |
| S28 | Analytical formats — op-ed 1,000–3,000 words, weekly analysis 3,000–5,000 words (Saturdays), monthly ML report 2,000–4,000 words, mandatory thesis → evidence → counter-argument → scenario structure, corrections as «اصلاح شد» notes | APP: **Analysis section** (inside the nightly edition and as a standalone section). Corrections render as visible «اصلاح شد» notes on the original item, never silent deletion. War-room corrections render as separate «اصلاحیه ⚠️» items counted in the nightly edition. |
| S29 | Channel doctrine "complete news" — self-contained brief, ≤~4,000 chars, lead + key facts + «چرا مهم است» (labelled editorial opinion), source name + direct link to the news source, never to our blog | APP (rendering rule for every article/short): the reader shows the complete brief self-contained; the source button goes to the news source, never to a blog link (BLOG_LIVE=False). |
| S30 | Verbatim republication — ONLY Donya-e Eqtesad + Tabnak, with attribution, no alteration | APP (rendering rule): verbatim items carry the «بازنشر از {source}» badge and full attribution. All other Persian sources render as brief/summary only — full copy is a legal risk and is blocked in the edge generator, not just in policy. |

### 1.5 Voices, special issues, blog, and the nightly edition

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S31 | Voices registry v1 — DRAFT, never executed: 9 Iranian + 1 blank + 10 international + 4 contrarians + 3 China analysts + 1 China outlet = 28 slots, 5 criteria, 3 tiers, attribution contract | APP: **Voices section** — voice pages (person, role at time of writing, outlet, date, source link, quote vs paraphrase labelling). GATED: the registry is a draft awaiting Yasin's edit. Until he finalises it, the app shows only per-article attribution (source string), and NO voice page is published. Next steps (from the registry itself): finalise list → `key_voices` table → 100-item author-coverage test → `#/voices` section. The 1 blank slot stays blank until Yasin proposes a name — never filled by the agent. |
| S32 | Special-issues service — Top-30 democratic-think-tank articles (past year, economy + global tensions), full read → analysis → journalistic rewrite → channel post + own PDF, 2/day, ML firewall (never enters ML) | APP: **Special Issues section** (issue page + PDF viewer/download) — BUT STAYS FROZEN. Placement is designed now, publication is NOT enabled by this plan. Separate explicit order required. Preconditions before any reopen (blocking): re-verify the 2 partially-verified posts (CAP refunds, Brookings Black Americans) from the source page itself; resolve the queue-location discrepancy (registry says 4 queued, `queue/` empty, drafts in `posts/` + PDFs in `pdfs/`); recover the missing full texts (`articles/` empty despite the README). The 4 queued posts stay queued. The ML firewall is absolute: no ML-DB rows, no labels, no model input from this service, ever. |
| S33 | Blog space-4 — homepage 4 modules (Wall Street/short selling; world board + rial market; journal monitoring + synthesis; geopolitical/war radar), archive, about | APP: **Blog section = the nightly edition + archive** (§4.2). The 4 homepage modules map to app sections: Markets, World board, Journal/Analysis, War Room/Radar. The external blog artifact stays unshared (public link revoked) until the domain steps (nic.ir, Cloudflare, Pages token) are done by Yasin — those are his steps, not the agent's, and are NOT in this plan's execution. |
| S34 | Phase-3 "30×" blog engine — DESIGN ONLY, never executed: ~30 frozen cards → 300+ articles/month 1, Tier-A → full article, briefs ≥70 → 300–500-word summary, ownrewrite >30 → candidates, duplicate topics/week → ONE living dossier page, 7 sections + searchable archive + live «تازه‌ها», quality floor (no article <300 words, no full article <800; if feed is short, 5 good not 15 thin), article ≠ channel-post copy (≥60% fresh text + separate canonical) | APP: this design BECOMES the nightly-edition generator (§4.2), adapted: Tier-A → full article in the nightly edition, briefs ≥70 → analytical summary, duplicate topics → one dossier page (not 5 articles), quality floor enforced in the generator (thin items held, never padded). The "≥60% fresh text" rule applies between the channel post and the nightly article — the nightly article is a re-edit, not a re-post. |
| S35 | Daily article engine (eco-article-daily, 07:30, deep + short articles → queue + editorial/articles/) — pre-freeze OFF, not in the frozen 24 | APP: feeds the nightly edition (morning articles are the day's first batch). Re-enable only as part of the lift order (§7), not early. |
| S36 | Weekly poll (Fri 18:00, 4 top stories poll) | NOT-IN-APP (for now, with reason): poll VOTING needs an authenticated voting backend that does not exist (votes must be counted once per reader, and the app has no accounts — only a pilot-distribution gate). Placement until then: the week's top-4 stories render as a read-only "Week's top stories" card in the nightly edition. Voting itself is deferred to a later decision, not silently dropped. |

### 1.6 Reports, letters, forecast (owner/internal-facing)

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S37 | Daily channel report 23:00 — nightly counts (digest/urgent/pilot, queue/blog status, Red Alert line) | NOT-IN-APP (reason: this is a PRIVATE report to Yasin, not channel content. The app is pilot-distributed to readers; putting Yasin's private nightly counts in front of pilot readers would leak internal operations. Delivery stays as before — private message to Yasin — unless he explicitly orders an owner-only section. The app's Diagnostics shows only aggregate health (last sync, counts), not the report's content.) |
| S38 | Daily letter (ML) — war-forecast accuracy vs reality + new forecast set with probabilities, PDF, 08:30, guard blocked anything before 2026-10-27 | NOT-IN-APP (same reason as S37: Yasin-only PDF, and it carries UNCALIBRATED model probabilities which must not reach any reader surface until Phase 3 calibration — see §6). Delivery stays private. A future owner-only letter section needs a role decision first. |
| S39 | War forecast — 24h tension index + gold / tether-spread / coin-bubble direction, scored against reality, outlook line in the 23:00 report | PIPE + APP (guarded): the forecast engine is internal (ML track). NO probability reaches the app until calibration passes (§6, gate closed). After calibration, the forecast appears ONLY as the Market tab's outlook line with its calibration note — never as a trading signal, never as a categorical prediction. |
| S40 | Forecast signals — deterministic daily signals (price-level, consensus-story kinds), contract-validated, "signals, not predictions" | PIPE: internal input to the forecast engine and to the nightly edition's "signals" box (labelled as signals, never as predictions). |
| S41 | Consensus engine — multi-outlet agreement/divergence clusters, verification + forecast inputs | PIPE: internal. APP surface: the consensus/divergence matrix renders inside Analysis articles (journal monitoring module, S33) and inside VERIFY follow-ups — as content, not as a separate engine screen. |

### 1.7 ML pipeline, watchdogs, and platform plumbing

| ID | Service (channel output) | Placement in the app |
|---|---|---|
| S42 | ML daily pipeline (23:30) — read day's news, extract features, store, refresh price snapshot | PIPE: ML track (§6). No reader surface. |
| S43 | ML snapshot hourly (market hours hourly, nights every 3h, gap SLO 6h) + nightly quality (23:45, close loops, story canon, full text) | PIPE: ML track. Resumption is part of the lift order (§7), not automatic. ESS is frozen at 16/15/15 until it resumes. |
| S44 | Catalyst teacher cycle (6h) — teacher labels for uncertain items | PIPE: ML track. Weight stays 1.0 until Phase-2 Snorkel/Dawid–Skene re-estimation (§6). Teacher must judge app-era items before any weight change. |
| S45 | Assemble editorial (12h) — decided keypoints + published marginalia → signals, gate x/7 | PIPE: ML track. Under app-first, the assembly input becomes app-publish decisions (§6). |
| S46 | Channel-ML impact weekly (Sat 09:00) + editorial supervision (daily 09:50) | PIPE + APP (diagnostics only): the weekly impact report stays an internal report to Yasin. Its aggregate counts (not content) may appear in Diagnostics as pipeline-health lines. Supervision findings that affect readers (e.g. a bad batch) surface as corrections (S28), not as a report screen. |
| S47 | ML training v0 (run-once 2026-10-28 03:21, 500-label gate, honest baseline comparison, no fabricated labels) | PIPE: ML track. The Oct 28 run is a training run, not a deployment. Deployment needs 7/7 gate or Yasin's explicit decision (§6). Frozen until lift; the run itself does not unfreeze anything. |
| S48 | Pipeline health (6h) + dawn review (04:00) + site watchdog (6h) + channel watch (see S20) | NOT-IN-APP as reader surfaces (reason: internal watchdog/ops tooling; readers do not need pipeline logs). APP surface: Diagnostics aggregate health lines only (last sync, edge freshness, error codes). Owner alerts stay owner-only. |
| S49 | Bot JobQueue — feed_watcher 30m, alert_check 1h, flash_monitor 1h, keypoints_daily 07:00 (in-process, only while bot runs) | PIPE: these are the bot's internal jobs. Under app-first, their FUNCTIONS move to the edge-publish path (§3): feed watching → ingest, alert check → S24, flash monitor → S08, keypoints daily → S16. The bot process itself stays stopped until lift. |
| S50 | Publisher send path + pins + blog-button rule (see S19) | PIPE: see S19. Listed separately here because it is also the app's edge-publish dependency: without it, nothing in §1.1–§1.5 reaches the app at all. |
| S51 | Channel caps governor (see S17) | PIPE: see S17. |
| S52 | Dedup — story_key headline hash (KNOWN DEFECT: transliteration drift creates duplicate keys, e.g. آتلانتیک/اتلانتیک; token-overlap ≥0.35 exists but is not the queue key) | PIPE + APP (invisible but critical): Phase 1 replaces the queue key with the semantic story_id (MinHash/LSH canon, S53) BEFORE any volume returns, or duplicates pile up in the app exactly as they did in the queue on 2026-10-23. Same-day posted story_key block + `update=true` + `development` rule for updates is preserved. |
| S53 | Story canon (MinHash/LSH) — 16,558 assignments, 14,148 stories, 885 events → 793 unique stories | PIPE: the dedup and join spine for everything app-first. Phase 1 extends it through bridge → edge → app with a guid ↔ app_key ↔ story_id mapping table (the item_id_map migration-009 pattern). Without this mapping, app events cannot join to training rows at all. |
| S54 | Full-text backfill + item-ID mapping (migration 009, 15,146 rows, 100% news_features coverage, join-integrity test with alarm) | PIPE: app articles are full bodies by construction, so the problem becomes join integrity, not extraction. The nightly join-integrity test + alarm is carried into the app-first pipeline as a Phase 1 acceptance item. |

**Register totals (counted from the table above, by placement-cell prefix):**
- Total inventoried services: **54** (S01–S54).
- Primary app surfaces (placement cell starts "APP"): **24** — war room,
  typed formats, SITREP, urgent rail, radar, slots, freewire, night desk,
  marginalia/bundles, market board/pulse/watch/alerts, governance and
  rendering rules, analysis, voices (gated), special issues (gated,
  frozen), blog/nightly, daily articles, forecast outlook (guarded),
  forecast-signals box, consensus matrix.
- Pipeline/internal (placement cell starts "PIPE"): **26** — these feed
  the app but have no reader surface of their own. Several of them
  (S20, S46, S48-adjacent) still contribute an aggregate Diagnostics
  line, noted in their rows; that does not make them reader surfaces.
- Explicitly NOT in the app, with reasons: **4 rows** (S36 voting, S37
  private report, S38 private letter, S48 watchdog screens) **plus 1
  embedded exclusion** (the disabled morning digest inside S18) = **5
  exclusions** in total.
- Gated/frozen even after the general lift unless separately ordered:
  S32 special issues, S38 letter probabilities, S31 voices until Yasin
  finalises the list, the disabled morning digest, and release signing.
- The table governs, not this summary: if a recount disagrees, the rows
  are what execution must cover. The already-built vNext M1–M9 surfaces
  (feed, reader, market, bookmarks, settings, diagnostics, invite gate)
  are the shells these services land in; they are not double-counted as
  separate register rows.

> Honesty note on the counts: the table is the authority. The auditors'
> raw counts (36 channel systems, 33 editorial elements, plus ML and app
> inventories) overlap heavily — war room, SITREP, market, special
> issues, and the pipeline appear in several audits. This register
> deduplicates them into 54 distinct services. If the parent recounts
> and gets a different total, the table rows — not the headline number —
> are what execution must cover. Nothing in the auditors' lists is
> missing from the table; pre-freeze-OFF services not in the frozen 24
> (article-daily, freewire slots, newsdesk slots, marginalia, nightdesk,
> ML briefs, site watchdog, blog refresh, weekly poll) are included
> above as S12–S15, S35, S36, S48, S33 respectively, so "frozen" does
> not silently drop them.

---

## 2. What the app already has vs what is missing (vNext M1–M9 baseline)

Already built (verified by the app audit): package `com.ecojournal.app`,
versionCode 1, versionName still `1.0.0-phase2` (labelling item, not a
code failure), minSdk 26, targetSdk 34, Room v3 (5 tables: articles,
bookmarks, meta, market_snapshots, market_formulas), edge v2 (281
entries: 249 full + 32 short, 164 market snapshots, 4 formulas, 23
channel_refs), Pages edge LIVE, invite gate (hash-only, sync re-check),
feed tabs as the category surface (categories screen deleted, M6),
market tab complete (M4), diagnostics screen (M8), WorkManager polling +
local notifications, FCM dormant by decision.

Missing for channel-completeness (the gap this plan closes):
- No typed format surface at all: no `format_type`, `urgency_score`,
  `crisis_level`, `bundle_id`/`digest_id`, `slot`, `report/letter issue
  date`, `pdf_url`, `media_url`/`has_media`, `author`/`voice_id`,
  `priority`/`is_breaking` in Room or edge. `channel_message_id` exists
  as a key but is NULL on all 281 current entries — the channel↔app
  join key is currently unusable and must be fixed in Phase 1.
- No `manifest.json` exists and the app never fetches one. What the code
  calls "manifest-gated" is index-gated and only partial: every sync
  downloads the full index.json (374 KB) + market.json (69 KB) +
  short_cards.json (53 KB) + both invite lists, sequentially, and the
  stored `index_hash` is never compared before fetching. Per-article
  files are hash-skipped; market/shorts are fully re-downloaded.
- No parallel fetch, no delta for market/shorts, no per-section sync
  state, no notification history table, no per-service notification
  preferences, no quiet hours, no sync-frequency setting (fixed 15 min).
- No war-room, SITREP, urgent-rail, bundles, radar, special-issues,
  blog/nightly, voices, reports, or letters surface (see §1.2/§1.5/§1.6).
- No pull-to-refresh (deferred in M8), no images (Coil is a dependency
  but no image composable or image URL field exists), no share, no
  charts/history (market is latest + previous only).
- Shorts are hard-coded to `general`/عمومی — real short categories are
  unrecoverable from shipped data (drafts carry no topic column), so
  shorts stay explicitly "uncategorised" until the generator carries a
  real category or the plan's explicit-uncategorised branch is used.
- Bridge live `poll_loop` is code-only and never executed by `main()`;
  normal input is a saved updates JSON file. The lab has a staging/
  review path for channel posts, not a live channel→app feed.
- The publish chain is manual and unbounded: editorial approval → lab
  regenerate → commit → push → Pages deploy → next poll. Nothing in
  this chain is automated today.

---

## 3. The Update Engine — dedicated milestone (Yasin's additional requirement)

**This is a phased milestone in its own right, not a footnote.**
It is placed FIRST among the build phases (§5, Phase 1) because the
near-real-time phase (§5, Phase 3) and the nightly blog phase (§5,
Phase 5) both depend on it and cannot be claimed done without it.
Execution must not start until this section exists — it now does.

### 3.1 What "stronger" means, concretely

1. **Full service coverage, not articles-only.** Sync covers ALL service
   types in §1: articles, shorts, channel posts (typed formats), alerts,
   market (+ formulas), radar signals, bundles/digests, SITREPs,
   reports/letters metadata (where allowed — S37/S38 stay private, so
   their metadata never syncs to readers), blog nightly bundle, special
   issues (gated, §1.5), voices. A sync that only moves articles is a
   failed sync under this plan, even if it reports success.
2. **True manifest-gated delta sync.** New `manifest.json` (schema v3)
   at the Pages root: generated time, per-section content hashes
   (articles, shorts, market, channel-typed, bundles, radar, nightly,
   voices, special-issues), per-item `content_hash` + `updated_at_epoch`
   + `status` (approved/posted/retracted) + tombstones. The app fetches
   the manifest FIRST; unchanged hash = zero further fetches for that
   section. Changed sections fetch only their changed items, in one
   Room transaction per section, with the section hash stored only on
   transaction success. Partial section = partial state for that
   section only, never a global failure, never a silent success.
3. **Per-section progress and honest states.** The sync UI shows, per
   section: queued / downloading (n/m) / up-to-date / partial (n missing)
   / failed (fixed error code). A failed section never empties that
   section's cache. The global sync line is a roll-up, not a replacement:
   "Market failed, articles up to date" is a valid, honest outcome.
4. **Retry with backoff + manual force-refresh.** Failed sections retry
   with exponential backoff (WorkManager backoff, not a tight loop).
   A manual force-refresh bypasses the cached manifest (re-downloads
   the manifest and re-checks every section hash) and is available from
   the feed sync button AND from Diagnostics, per section. Force-refresh
   never bypasses the invite gate or the revocation check.
5. **Offline resilience.** A failed sync, a corrupt item, a missing
   file, or an unreachable edge never empties the cache. The bundled
   seed + last-good state remain the fallback. Corrupt items are counted
   and skipped, never a crash. Retracted items become tombstones (row
   kept, leaves feed/search, bookmark preserved).
6. **Per-section transparency in Diagnostics.** For EACH section:
   last successful sync time, last attempt time, last fixed error code,
   item count, manifest hash present/absent (value never shown).
   Diagnostics also shows edge health (Pages reachable? manifest fresh?)
   as an aggregate line, and the copy-diagnostics action copies exactly
   the lines shown — no PII, no content, no codes, no tokens.
7. **Join keys fixed.** `channel_message_id` populated from the real
   channel post (via the bridge staging path, S06/S52), not left NULL.
   guid ↔ app_key ↔ story_id mapping table built (S53/S54 pattern) and
   carried through bridge → edge → app. Shorts get a real category or
   an explicit "uncategorised" marker — never a silent `general`.
8. **Publish-chain automation (lab side, part of the engine).** The
   manual regenerate → commit → push → Pages chain gets a single
   lab-side "publish edge" command (regenerate + hash-verify + commit +
   push) with a dry-run mode. This does NOT auto-publish editorial
   content — it automates the mechanical chain AFTER editorial approval.
   Editorial approval itself stays human/delegated (S25).

### 3.2 Near-real-time trigger path (inside the engine)

- **Base (polling, no new decision needed):** WorkManager periodic work,
  15-minute minimum (Android-enforced), flex 5 min, `CONNECTED`
  constraint, exponential backoff. Poll fetches manifest first (small),
  then only changed sections. This is the guaranteed fallback and stays
  even if push is ever added.
- **Foreground boost (no new decision needed):** app foreground triggers
  a manifest check (not a full sync) on launch and on return from
  background after >15 min. Manual sync button + Diagnostics retry
  remain the user-driven fast path.
- **Push hint (decision-revisit ONLY, not enabled by this plan):** if
  Yasin later revisits the FCM decision, the shape is fixed here so it
  cannot be improvised later: FCM data message as a HINT ONLY
  (`appKey` + `content_hash` hint) → immediate manifest check /
  `fetchOne` for that item → local notification. Push is never the sole
  path (polling stays), a push for an unknown id is dropped never
  rendered, and a push for an unapproved or not-yet-published edge item
  must fetch-fail silently (never leak timing). Server side needs: a
  Firebase project, `google-services.json`, the Gradle plugin, token
  lifecycle management (rotation, revoked/uninstalled readers, topic
  vs per-reader routing), and a publisher sender gated on edge
  publication. None of that is built by this plan.
- **What the engine does NOT promise:** sub-15-minute latency on
  polling alone, Doze-proof delivery, or a bound on the manual lab
  publish step until §3.1.8 is built. Any claim stronger than §4.1's
  latency table is a false claim.

### 3.3 Engine acceptance criteria (phone-testable, Phase 1 gate)

- E1. Airplane-mode launch shows the full last-good cache (feed, market,
  bookmarks) with honest "offline, last sync <time>" lines; nothing
  empties.
- E2. A sync with the edge unchanged downloads only the manifest
  (verify in Diagnostics: per-section "up to date", no article
  re-downloads) — the current full-download-every-poll behaviour is gone.
- E3. A sync with one changed article + one changed market snapshot
  updates exactly those two sections; other sections report "up to
  date" without re-downloading.
- E4. A sync with the edge unreachable reports per-section "failed
  (network)", keeps all cached content, and retries with backoff; a
  manual force-refresh retries immediately.
- E5. A retracted item disappears from feed/search but its bookmark
  survives (orphan row with prune action, as in M3).
- E6. Diagnostics shows per-section last-sync / last-attempt / error
  code / count for every section in §1, and copy-diagnostics copies
  exactly those lines.
- E7. A channel post staged via the bridge (dry-run lab path) appears
  in the edge index with a non-NULL `channel_message_id` and the
  correct `format_type`, and syncs into the app as a typed card (not a
  generic article row).
- E8. Two consecutive cloud builds share one signature (already proven
  for debug signing) AND the engine build's APK updates over the
  previous shared-signed APK without uninstall — the signature lesson
  from 2026-10-25 must never regress.

---

## 4. The two update rhythms, designed concretely

### 4.1 Rhythm A — Near-real-time: channel post → app

**Target latency (honest, end-to-end):**
| Step | Target | Notes |
|---|---|---|
| Editorial approval → edge publish (lab chain, after §3.1.8) | ≤5 min | Mechanical chain only; editorial deliberation time is separate and unbounded by design. Before §3.1.8, this step is UNBOUNDED (manual). Never quote a total latency without stating which side of §3.1.8 you are on. |
| Edge publish → Pages serving | typically 1–3 min, no guaranteed bound | Pages deploy + CDN propagation. The engine must tolerate a stale manifest during propagation (hash mismatch = retry, not corrupt state). |
| Pages serving → app visible (polling) | typically 15–30 min after serving; no guaranteed upper bound | 15-min poll grid + 5-min flex + Doze deferral + backoff on failure. Night alerts are exactly when Doze deferral bites hardest — stated, not hidden. |
| Pages serving → app visible (manual sync / foreground) | seconds–minutes after user action | The only sub-15-minute polling path, and it requires the user to act. |
| Pages serving → app visible (IF push is later enabled) | seconds–few minutes, normal case | Per §3.2 push-hint shape. Not available under the current FCM-NO decision. |
| **End-to-end, polling, after §3.1.8** | **typically ~20–35 min from channel post to app, no guaranteed bound** | This is the claim the app may make. "Shortly after" in Yasin's words is operationalised as this table, not as "instant". |

**Mechanism (in order):**
1. Channel publish happens (bot/publisher, Law 14 channel-only).
2. The bridge staging path captures the post (`channel_post` update →
   staging file with `message_id`, caption/text, `has_media` note) with
   3-layer dedup by `message_id` — captioned posts are NOT dropped as
   "media-only" (that was a Phase-2 bridge defect; M7 fixed caption
   handling, and this plan keeps the fix).
3. Editorial gate (delegated, S25) approves the item for the app
   (app-publish decision, §6). Unapproved items never reach the edge.
4. Lab "publish edge" command (§3.1.8) regenerates the edge with the
   new item typed (S05), recomputes per-section hashes + manifest v3,
   commits, pushes; Pages deploys.
5. App poll (or foreground manifest check, or manual sync) fetches the
   manifest, sees the changed section hash, fetches only the changed
   items, applies them in one transaction, and notifies per the
   notification policy below.
6. Failure at any step leaves the previous good state intact and
   records a fixed error code per section (§3.1.3/§3.1.6).

**Fallback when edge/Pages is slow:**
- Stale manifest or deploy-in-progress → retry with backoff, keep
  last-good cache, show "edge updating, last sync <time>" — never an
  empty feed, never a fake "up to date".
- Edge unreachable → per-section failed states + retry (E4).
- A single corrupt item → counted, skipped, rest of the section still
  applies (never a whole-section failure for one bad file).
- If Pages is down entirely, the bundled seed + last-good cache is the
  app, indefinitely, with honest staleness labels (market >24h already
  does this; articles get the same treatment in Phase 1).

**Notification policy (Law 14 + Law 15, enforced app-side):**
- App notifications are LOCAL device notifications. They are not DMs
  and do not violate Law 14. No app feature sends a DM to any reader.
- Urgent channel (high importance, sound): ONLY instant-eligible items
  — day score ≥70, night score ≥85, FLASH (exempt), WATCH at L3/L4 at
  night (exempt). Everything else uses the default channel.
- Night-held items (70–84 at night) generate NO instant notification.
  They appear in the 08:00 Morning Bundle and generate ONE bundle
  notification at 08:00 (if the user has bundle notifications on).
- Per-service notification preferences (Phase 4): urgent, market
  alerts, bundles, SITREP, nightly edition, special issues (gated) —
  each toggleable separately. A single binary toggle (today's state)
  cannot express "urgent yes, bundles no" and is replaced in Phase 4.
- Quiet hours: user-set, default none. Quiet hours suppress non-urgent
  notifications only — urgent (≥85 night / FLASH) still notifies
  unless the user explicitly mutes urgent too (a deliberate, separate
  toggle, not a default).
- Notification taps deep-link to the item by `appKey`; bundle taps open
  the bundle; a tap for a retracted item opens the feed with an honest
  "no longer available" line, never a crash.
- No notification is ever generated from a payload body. Push hints
  (if ever enabled) carry only `appKey` + hash hint; content always
  comes from the edge fetch. This prevents both spoofing and timing leaks.

### 4.2 Rhythm B — Nightly big blog update

**What "big" means (this is the definition — a nightly bundle that is
only the day's channel posts re-listed is NOT big and fails acceptance):**
1. **The day's new articles** — Tier-A deep analyses as full articles
   + top briefs (score ≥70) as 300–500-word analytical summaries +
   ownrewrite (>30) candidates, deduplicated by story (one dossier page
   per topic per week, not 5 articles). Quality floor enforced: no
   article <300 words, no full article <800 words; if the feed is
   short, publish 5 good articles, not 15 thin ones.
2. **The day's market close** — latest `prices` snapshot + computed
   formulas + the day's market pulse/watch commentary + bubble/spread
   alert history for the day.
3. **The day's digests, consolidated** — slot bundles, short-news bundle,
   night-desk leftovers ("جامانده‌های دیشب"), and the SITREP arc for
   the day (all SITREPs, in order, with the day's crisis-level path).
4. **The day's analysis** — op-ed / weekly analysis (Saturdays) /
   monthly ML report (when due), with thesis → evidence →
   counter-argument → scenario structure, plus the consensus/divergence
   matrix for the day's journal monitoring.
5. **Special issues (gated)** — the day's special-issue pair (2/day
   when unfrozen, with PDFs) as its own nightly section. While frozen,
   this section renders as "Special issues — paused" with no content,
   never with stale content presented as new.
6. **Corrections** — every «اصلاح شد» note and war-room «اصلاحیه ⚠️»
   from the day, attached to their originals and listed in the edition.
7. **Archive + search refresh** — the edition updates the searchable
   archive and the «تازه‌ها» index. The archive is data-driven, not
   baked at build time.

**How it is generated:**
- Generator: the Phase-3 "30×" design (S34), adapted, running as a
  lab-side nightly job (NOT a Muse cron that auto-publishes — see
  publish gate below). Inputs: approved extracts + queue + prices +
  war-room posted rows + special-issues posts (gated) + decision log.
  Deduplication by story_id (S53). Fresh-text rule: nightly article
  ≥60% fresh text vs its channel post + separate canonical, or it does
  not ship as an article (it stays a brief).
- Timing: generation runs overnight (after the 23:00 report inputs
  close and before the morning). The exact clock time is set in Phase 5
  against the lift order — this plan does not invent a cron time that
  collides with the frozen 23:00/23:30/23:45 jobs. The edition is dated
  by Shamsi date + Tehran time and carries a "last updated" stamp.
- Versioning: every nightly edition is immutable once published:
  `edition_id` = Shamsi date (e.g. `edition-1405-08-04`), `version` = 1,
  corrections after publication create `version` 2 with a visible
  correction note — never a silent overwrite. The app keeps the last N
  editions locally (N set in Phase 5, default 30) and fetches older ones
  on demand from the edge. Edition manifest hash covers the full
  edition content (articles + market + digests + corrections), not just
  the entry list — the same lesson as §3.1.2.

**How it lands in the app:**
- A dedicated **Nightly section** (top-level, alongside Feed / Market /
  War Room / Bundles / Analysis): tonight's edition as the hero, past
  editions as an archive list, each edition openable as a single
  scrollable edition page (not a pile of feed rows). The edition page
  is also bookmarkable and searchable.
- The nightly edition arrives through the Update Engine like any other
  section (per-section hash, delta, partial/failed states). A failed
  nightly sync leaves yesterday's edition in place with an honest
  "yesterday's edition, tonight's failed (network)" line — never a
  blank nightly section.
- A nightly-edition notification (default channel, not urgent) fires
  once, on first successful nightly sync after publication, if the
  user has nightly notifications on. It never fires at night outside
  the user's quiet-hours setting (deferred to morning if needed).

**Publish gate (adapted from the blog publish law):**
- The nightly edition is NOT auto-published by the generator. It is
  staged, then published to the edge by the editorial gate (delegated,
  S25) — the app equivalent of "public link only by Yasin's live tap"
  for the external blog. During the freeze, nightly generation runs
  ONLY in dry-run (edition built, hash computed, never committed to
  the edge). After lift, the gate is: delegated approval → publish edge
  command → Pages → app sync. Yasin may at any time require his own tap
  for a given night's edition; that requirement, if he states it, becomes
  a blocking gate in Phase 5, not an optional step.

---

## 5. Phases in order (correct prioritisation)

Timing is in **working sessions** (agent working sessions: investigation,
implementation, cloud build, verification), NOT calendar dates and NOT
fake deadlines. Yasin's phone test gates every phase: a phase is not
"done" until its acceptance criteria pass on a real phone (or the
criterion is explicitly marked lab-only). Phases are sequential except
the ML track (§6), which runs parallel and gates on the same phase
boundaries.

### Phase 1 — Update Engine (the dedicated milestone; NOTHING that depends on it starts before it)
**Scope:** §3 in full: manifest v3 + per-section hashes, per-section
delta sync + per-section progress/partial/failed states, retry +
backoff + force-refresh (feed + Diagnostics, per section), offline
resilience (never empties cache), per-section Diagnostics transparency,
join-key fixes (`channel_message_id` populated, guid ↔ app_key ↔
story_id mapping table, shorts real-category-or-explicit-uncategorised),
parallel fetch (~4), index-hash pre-check (fix the current
full-download-every-poll behaviour), lab "publish edge" command
(regenerate + hash-verify + commit + push, with dry-run mode),
cron-definition reconciliation (46 definitions vs 24 active — produce
the reconciled list; no orphan cron is re-enabled by accident),
story_key → story_id queue-key migration (S52, before any volume
returns), feed-count reconciliation (73 vs 23).
**Dependencies:** none (baseline vNext M1–M9). This phase depends on
nothing new; everything later depends on it.
**Acceptance (phone-testable):** E1–E8 from §3.3, all passing.
**Timing:** 3–4 working sessions + 1 cloud build + Yasin phone test.
**Exit gate:** if E2 (manifest-only sync when unchanged) fails, Phase 3
and Phase 5 do NOT start. This gate is not waivable by schedule pressure.

### Phase 2 — Channel surfaces (typed formats in the app)
**Scope:** S05/S07/S08/S11/S12/S13/S15/S18 surfaces: format badges +
filters, War Room section (level, radar cadence), SITREP cards, urgent
rail + urgent notification channel, Radar section, feed slot grouping,
bundle/digest grouping (bundle_id + position), Morning Bundle (08:00),
Night Desk card, marginalia bundles. Night-law enforcement in the app
notification path (≥85 night / FLASH / L3-L4 WATCH only for urgent
channel; 70–84 held to Morning Bundle). Format word budgets + validation
carried into the edge generator (a cap-hit item is held, never
truncated to fit).
**Dependencies:** Phase 1 (typed fields + per-section sync must exist
before typed cards can render or sync).
**Acceptance (phone-testable):** a FLASH card, a WATCH card, a GEOPOL
card, a MARKET card (with prices line), a VERIFY card (with verdict
line), a SITREP card, a WIRE card, and a bundle (with position
1/N…N/N) each render distinctly and filter correctly; a night-held
item (simulated 70–84 at night) produces NO instant notification and
appears in the Morning Bundle; an urgent item (≥85) produces an urgent
notification that deep-links to the item; a retracted item's bookmark
survives as an orphan row.
**Timing:** 4–5 working sessions + 1 cloud build + Yasin phone test.

### Phase 3 — Near-real-time channel → app (depends on Phase 1)
**Scope:** §4.1 end-to-end: bridge live `poll_loop` for channel posts
(currently code-only — wire it to the staging path with the
allow-listed test channel only, never the live channel, until lift),
staging → editorial gate → publish-edge command → Pages → app poll /
foreground manifest check / manual sync. Latency instrumentation:
each step timestamps (channel post time, edge publish time, Pages
serve time, app sync time) recorded in Diagnostics (aggregate only).
Timed end-to-end test in dry-run/lab conditions first, then (only
after lift, §7) live.
**Dependencies:** Phase 1 (engine), Phase 2 (typed cards to render
what arrives). NOT dependent on FCM — polling is the shipped path.
**Acceptance (phone-testable):** a lab test-channel post (test channel
only) appears in the app as a typed card with its Diagnostics
timestamps showing each step; measured end-to-end latency is reported
against the §4.1 table (typically ~20–35 min polling, no bound claimed);
a Pages-slow case shows "edge updating" honesty, not an empty feed.
**Timing:** 3–4 working sessions + 1 cloud build + Yasin phone test.
**Explicit non-goal:** FCM activation. If Yasin revisits FCM, it is a
separate decision with its own phase, after Phase 3 proves polling.

### Phase 4 — Market, radar, and notification preferences
**Scope:** market history/charts + intraday series (new time-series
table), radar signal list hardening (severity, source, linked story),
bubble/spread alert banners + dedicated alert notification type
(thresholds recovered from admin settings, never guessed; 12h cooldown
preserved), per-service notification preferences (urgent, market,
bundles, SITREP, nightly, special-issues-gated), quiet hours (non-urgent
only; urgent mute is a separate deliberate toggle), per-service
notification history inside the app (what was notified, when — local
only, no server).
**Dependencies:** Phase 1 (per-section sync), Phase 2 (typed surfaces).
**Acceptance (phone-testable):** market history renders for the last N
snapshots; a threshold-cross alert (simulated) banners + notifies once
and does not re-notify within 12h; turning off bundle notifications
silences bundles while urgent still notifies; quiet hours suppress a
bundle notification and defer it to morning.
**Timing:** 2–3 working sessions + 1 cloud build + Yasin phone test.

### Phase 5 — Nightly big blog update (depends on Phases 1 + 2)
**Scope:** §4.2 in full: nightly generator (adapted Phase-3 design),
edition schema (`edition_id`, `version`, correction versions), archive
+ search index refresh, Nightly section + edition page + bookmarking,
nightly notification (default channel, quiet-hours aware), publish gate
(delegated approval → publish edge; dry-run only during freeze),
quality floor enforcement (thin items held, never padded), ≥60%
fresh-text rule vs channel post, dossier pages for duplicate topics.
**Dependencies:** Phase 1 (engine + edition section sync), Phase 2
(typed content to consolidate). The nightly edition consolidates
Phase 2 surfaces — it cannot be built before they exist.
**Acceptance (phone-testable):** a dry-run nightly edition (lab data)
renders as a single edition page with all 7 "big" components (§4.2)
present and correct; a thin-item case is held, not padded; a
correction after publication creates version 2 with a visible note
(never a silent overwrite); a failed nightly sync leaves yesterday's
edition with an honest line; archive shows past editions and search
finds edition content.
**Timing:** 3–4 working sessions + 1 cloud build + Yasin phone test.

### Phase 6 — Editorial, voices, analysis, special issues (gated)
**Scope:** About → Method page (rubric summary + provenance), Analysis
section (op-ed / weekly / monthly ML report, with structure +
corrections), Voices section (S31 — ONLY after Yasin finalises the
registry list; `key_voices` table + 100-item coverage test first),
Special Issues section (S32 — designed, NOT published; preconditions
in §1.5 must all pass first, then a SEPARATE explicit order from Yasin
is still required), weekly top-stories read-only card (S36, voting
deferred).
**Dependencies:** Phase 1, Phase 2, Phase 5 (nightly edition is where
analysis and special issues land). Voices and special issues each have
their own gates inside this phase — the phase is not "done" because
code exists; it is done when each gated item's gate passes.
**Acceptance (phone-testable):** Analysis articles render with
thesis/evidence/counter-argument/scenario structure and visible
corrections; Voices pages render ONLY for finalised voices with full
attribution contract fields (quote vs paraphrase labelled); Special
Issues section renders "paused" while frozen and, after its separate
order (not in this plan), renders an issue page + PDF; top-stories card
renders without any voting UI.
**Timing:** 3–4 working sessions + 1 cloud build + Yasin phone test
(excluding Yasin's own time to finalise the voices list and to
re-verify the 2 partial special-issue posts — that time is his, not
the agent's, and is not estimated here).

### Phase 7 — Polish, accessibility, and the full pilot checklist
**Scope:** final icon/branding check (EJ only, no other publication),
strings/digits polish sweep, TalkBack walkthrough on a real phone
(traversal order, sepia/dark contrast measurement, 0.85×/1.3× font
extremes — all still open from M8), empty/error/offline states audit
across all new sections, Diagnostics expansion (per-section lines for
every new section), full §5-style pilot checklist for the big update
(feed, war room, bundles, market, nightly, radar, special-issues-paused,
gate, sync, notifications, offline, diagnostics).
**Dependencies:** Phases 1–6.
**Acceptance (phone-testable):** the full pilot checklist passes on
Yasin's phone, with TalkBack items explicitly walked (not statically
claimed). Any checklist failure returns to its owning phase — Phase 7
does not waive failures.
**Timing:** 2 working sessions + Yasin phone test.

### Phase 8 — Freeze-lift rehearsal (dry-run ONLY — not a lift)
**Scope:** walk §7's checklist end-to-end in dry-run: every criterion
checked against evidence (builds green, phone checklist passed, NRT
timed test reported, nightly dry-run edition rendered, ML gate status
reported, cron reconciliation list produced, test channel + allow-list
verified, Pages serving verified, invite/revocation verified,
diagnostics verified, special issues confirmed still frozen). Produce
the one-page lift-order draft (updated from the M9 draft) with the
scope field left BLANK for Yasin to fill ("lift the freeze for X").
**Dependencies:** Phases 1–7.
**Acceptance:** the draft exists, every §7 criterion has a pass/fail
line with evidence, and NOTHING was enabled, started, posted, or
unfrozen during the rehearsal. If any criterion fails, the rehearsal
reports it as a blocker — it does not proceed to a lift.
**Timing:** 1–2 working sessions. The lift itself is Yasin's sentence,
not a phase.

---

## 6. ML track (parallel, aligned to the new structure)

The ML track runs alongside Phases 1–8 and gates on the same phase
boundaries. It does NOT gate Phase 1–3 code (the app must work without
ML), but no ML output reaches any app surface until its gate passes.

**ML-A — Join keys and the unit of analysis (aligns with Phase 1).**
Redefine the unit from raw feed item → canon story → edge
article/short. Build and maintain guid ↔ app_key ↔ story_id mapping
(S53/S54 pattern) through bridge → edge → app. Item time becomes
edge-publish time, not device sync time and not raw feed-fetch time
(device clock skew must never become a feature). Join-integrity test +
alarm carried over from migration 009. Without this, dedup, tension,
and label joins regress to the pre-Phase-1 state.
**Verdict: build (new mapping table + tests).**

**ML-B — Labels for the app era (aligns with Phase 2).**
Channel approve/reject becomes app-publish decision (publish to edge /
hold / reject) under a versioned app rubric, with the same discipline:
recorded at decision time, provenance + timestamp + referable ID,
skips (cap, dupe, no-body) audit-only never labels ("no fake −1s").
Policy version bumps (v2.2 → app-era version); models are evaluated
within a single policy window. Wire war-room VERIFY/publish decisions
as labels (docs/28 Phase 2 item 6, not done) — or the label stream
starves exactly as it did Oct 20→24 (1,595 → 7/day) when the channel
stopped being the surface. Yasin-sent posts: the files establish
yasin-direct (/fori) as a distinct 3.0-weighted included provenance
with count 0 — no archive-only/separate-stream rule for yasin-sent
posts was found in the ML files, so this plan does NOT assert one;
if Yasin wants such a rule, it must be stated as a new decision.
**Verdict: relabel semantics (keep schema, new decision types +
policy version); build the app-publish decision log.**

**ML-C — Features, tension, and the market loop (aligns with Phases 3–4).**
Tension index: switch input from item counts to story counts
(Phase 1 added story counts to `parts` but deliberately left the
formula untouched — the switch itself is a formula decision for this
track, recorded explicitly). `flash_share` (20% of the index) needs an
app equivalent or a formula decision — it has no app equivalent today.
Retire or re-derive `has_points_fa` (largest v0 coefficient, workflow
leakage: "a Persian rewrite was produced" is not news content) with a
leakage audit. Server-side market features must match the app's exact
formulas (M4) or train/serve skew appears. Snapshot loop: hourly/3-hourly
with 6h gap SLO resumes ONLY as part of the lift order (§7) — ESS is
frozen at 16/15/15 and the Phase-1 14-day pass criterion (ESS ≥50/target,
median gap ≤2h, mature pending <10%, compute <10 min) cannot complete
until then. Compute stays diff-only (31s), never the old 100+ min path.
**Verdict: keep compute + snapshot machinery (frozen), change tension
input, retire/re-derive leaky features, relabel v1 targets to
v2-rv-deadband (v1 raw-sign labels, incl. 7,704 base==future rows, must
not train anything new).**

**ML-D — Calibration, shadow, scorecard, and the v0→v1 decision (aligns with Phases 5–8).**
Calibration gate stays CLOSED until Phase 3 (docs/28): purged/embargoed
walk-forward harness, Platt/isotonic calibration, regime features,
conformal 90% sets. Every commander output carries "احتمال‌های مدل
کالیبره‌نشده‌اند" until then, and NO raw model probability reaches any
app surface (feed ranking, market annotation, "why this story" copy)
without that label — and no probability at all in the app until
calibration passes on ≥30 walk-forward days. Shadow loop gains app arms
("would this story be an article vs a short vs held" — new shadow table,
new baseline; channel-format shadow accuracy 53.7% does not transfer).
Scorecard code (7d/30d, ESS, Wilson CI) is kept; its delivery carrier
moves — the 23:00 report and daily letter are frozen/private surfaces,
so the carrier decision (in-app diagnostics aggregate? resumed report?
neither is specified yet) is an open item for Phase 8, not an assumption.
Teacher/catalyst stays at weight 1.0 until Phase-2 Snorkel/Dawid–Skene
re-estimation; teacher must judge app-era items before any weight
change (agreement 30.8–56.7% by sample, vs-reality n=65 at 3.1% from a
single episode — not a usable kill/keep verdict). Gold set / yasin-direct:
designed, 0 rows, not built — becomes MORE important in the app era
(the only anchor for the app-era rubric); building it needs Yasin's time
(~20 min/month, batches of 20, target ≥100 items), which is his time and
is not estimated as agent sessions. v0 (873 rows, test 0.523 vs baseline
0.557, never deployed) is RETIRED from production; v1 retrains ONLY at
7/7 gate or by Yasin's explicit decision. The Oct 28 03:21 v0 run is a
training run, not a deployment, and does not unfreeze anything. Token
cap: 5% weekly (teacher 50% / weekly batches 30% / 23:00 draft 10% /
buffer 10%), auto-stop at 80%/100% — logistic/rule/statistical models
cost zero tokens (local CPU).
**Verdict: keep measurement spine (forecasts table + UNIQUE(day,target),
full probability vectors, purged walk-forward, ESS beside every price
metric, 7d/30d scorecard, 7/7 gate, kill criteria); retrain war-forecast
logregs only on ESS≥50 with purged split (never serve the current
experimental-internal, uncalibrated models); keep brent as `collecting`
until it has labeled history; quarantine registry research metrics.**

**ML track in 5 lines (as requested):**
1. Unit changes from feed item to canon story → edge article/short,
   joined by a new guid ↔ app_key ↔ story_id mapping; item time becomes
   edge-publish time.
2. Labels change from channel approve/reject to versioned app-publish
   decisions (same no-fake-−1 discipline); war-room VERIFY/publish
   decisions get wired as labels or the stream starves again.
3. Tension moves from item counts to story counts (formula decision),
   `has_points_fa` is retired/re-derived after a leakage audit, and
   market features must match the app's exact M4 formulas.
4. No probability reaches the app until Phase-3 calibration passes on
   ≥30 walk-forward days; shadow gains app arms; the scorecard code is
   kept but its carrier (diagnostics vs resumed report) is undecided.
5. v0 is retired from production; v1 retrains only at 7/7 gate or by
   Yasin's explicit decision; ESS stays frozen at 16/15/15 and the
   14-day pass criterion cannot complete until the snapshot loop resumes
   under the lift order — and app reads/bookmarks/searches are NOT ML
   inputs without a new explicit Yasin privacy decision.

---

## 7. Freeze-lift criteria — ALL must pass before Yasin ends the freeze

"If everything is really connected" is operationalised as this
checklist. EVERY item must pass. A single failure blocks the lift
recommendation — the rehearsal (Phase 8) reports it, it does not waive it.

- [ ] **Update Engine proven:** §3.3 E1–E8 all pass on Yasin's phone,
  including manifest-only sync when unchanged and per-section
  partial/failed honesty.
- [ ] **Near-real-time proven:** §4.1 timed end-to-end test reported
  (lab test channel first); latency table (§4.1) confirmed against
  measurement, not asserted. Polling path proven; push (if ever) is a
  separate decision and is NOT a lift precondition.
- [ ] **Nightly edition proven:** a dry-run nightly edition renders
  complete (§4.2, all 7 components) with quality floor, correction
  versioning, and archive/search refresh — in dry-run, before any live
  edition is published.
- [ ] **Full pilot checklist passed:** Phase 7 checklist passes on
  Yasin's phone, including the TalkBack walkthrough (not statically
  claimed), font extremes, sepia/dark contrast, offline, gate, sync,
  notifications, and diagnostics.
- [ ] **Cron reconciliation complete:** the 46-definition vs 24-active
  list is reconciled in writing; every cron to be re-enabled is named
  explicitly in the lift order; no orphan cron is re-enabled by
  accident; the Monday 04:10 low-burn restore stays DISABLED unless
  Yasin explicitly re-enables it (it must never auto-reopen the freeze).
- [ ] **ML gate status reported:** gate_status (currently 6/7, failing
  only the 60-day condition at 34 days) reported honestly; snapshot
  loop resumption is IN the lift order (not assumed); no uncalibrated
  probability is on any app surface; v0-retired / v1-gate status stated.
- [ ] **Test channel + allow-list verified:** a private test channel
  exists (created by Yasin — the bot CANNOT create a channel), the bot
  is admin, and its id is in the allow-list. The LIVE channel id is NOT
  allow-listed by the rehearsal. Live-channel publishing needs its own
  explicit scope line in the lift order.
- [ ] **Pages + edge verified serving:** Pages status `built`, manifest
  v3 serving, per-section hashes matching the committed edge, invite
  hashes + revocations serving.
- [ ] **Invite + revocation verified:** gate works (wrong code rejected,
  valid code admitted, revoked code re-locked on next sync, offline
  never locks a reader out). Pilot codes are Yasin's to distribute, one
  per reader; no code appears in any repo, log, or report.
- [ ] **Diagnostics + privacy verified:** per-section diagnostics
  complete, copy-diagnostics copies exactly the shown lines, no PII /
  content / codes / tokens / device ids anywhere in telemetry, no
  check-in path created, FCM still dormant unless separately decided.
- [ ] **Lift scope named by Yasin:** the lift order says, in Yasin's
  own sentence, "lift the freeze for X" with X naming the scope
  (which crons, war room, ML, 23:00 report, daily letter, blog, app
  sync — each named, not "everything"). An unnamed scope is not a lift.

**What stays frozen EVEN AFTER a general lift, unless Yasin says otherwise:**
- **Special-issues auto-publish** (S32): separate decision, separate
  order. Its 4 queued posts stay queued; its 2 partially-verified posts
  stay unpublished until re-verified from the source page.
- **Private reports/letters as app content** (S37/S38): the 23:00 report
  and daily letter stay Yasin-private; their probabilities stay off
  app surfaces until calibration passes.
- **Morning digest revival** (inside S18): disabled 2026-10-22, stays
  off unless re-decided.
- **Voices publication** (S31): until Yasin finalises the registry list.
- **Domain steps** (nic.ir, Cloudflare, Pages token): Yasin-only steps,
  not done by the agent, not a lift precondition for the app (the app
  does not depend on the external blog domain).
- **Release signing / Play distribution**: debug signing is shared and
  proven; release signing and any store distribution are separate
  decisions. Debug APKs stay direct-distribution (My Files install).
- **FCM activation**: separate decision, separate phase, never implied
  by the lift.
- **Yasin-sent / owner private delivery rules**: no rule found in ML
  files for an archive-only/separate-stream treatment — if Yasin wants
  one, he states it as a new decision; this plan does not invent it.

---

## 8. Open risks and unknowns (stated honestly)

1. **Bot cannot create a test channel.** Only Yasin can create a
   private test channel in Telegram, admin the bot, and put its id in
   the allow-list. Until he does, Phase 3's live test uses lab staging
   only. This is a Yasin-time dependency, not an agent task.
2. **Pages propagation delay is unbounded by contract.** The §4.1
   latency table includes it honestly. A "shortly after" promise that
   ignores Pages deploy + CDN propagation is false.
3. **WorkManager 15-minute floor vs "shortly after".** Polling alone
   cannot beat 15 minutes, and Doze defers further — exactly at night,
   when alerts matter most. The plan's answer is honest latency + a
   manual/foreground fast path + a separately-decided push-hint option,
   not a claim that polling is instant.
4. **FCM decision revisit is real work, not a toggle.** Project,
   `google-services.json`, plugin, token lifecycle, publisher sender
   gating, privacy review — all still to do IF Yasin revisits it. The
   pilot proved polling; push must prove it is a blocker first (per
   the recorded decision: "revisit only after the pilot, if push
   latency is proven a pilot blocker").
5. **Story-key dedup defect is load-bearing.** If Phase 1 ships volume
   before the story_id queue-key migration, duplicates pile up in the
   app exactly as on 2026-10-23 (same Atlantic story queued 4×). This
   is why Phase 1 gates Phase 3/5.
6. **`channel_message_id` is NULL on all 281 current entries.** The
   channel↔app join is unusable until the bridge staging path populates
   it. Any plan that assumes the join works today is wrong.
7. **Shorts categories are unrecoverable from shipped data.** Drafts
   carry no topic column. Shorts stay explicitly uncategorised until
   the generator carries a real category — never silently `general`.
8. **Cron reconciliation (46 vs 24) is unfinished business.** The
   reconciled list is a Phase 1 deliverable. Re-enabling from the wrong
   list could revive a cron Yasin deliberately killed (e.g. the 18
   war-room-only desk crons, or the disabled morning digest).
9. **ML ESS is frozen and small (16/15/15).** The 14-day pass criterion
   (ESS ≥50/target) cannot complete while the snapshot loop is frozen.
   Any ML timeline that promises v1 before lift + 14 days of hourly
   snapshots is false.
10. **Teacher agreement is low and single-episode.** 30.8–56.7% by
    sample; vs-reality n=65 at 3.1% from one market episode. No weight
    change, no teacher-based app feature, until Phase-2 re-estimation.
11. **Calibration is closed.** Any app surface showing a model
    probability before Phase-3 calibration is a violation of the
    recorded gate, not a product decision.
12. **App telemetry cannot become ML input by assumption.** Reads,
    bookmarks, searches, and notification opens are on-device only by
    decision. Using them needs a new explicit Yasin privacy decision +
    opt-in pipeline. This plan's ML track does not use them.
13. **Version label debt.** versionName is still `1.0.0-phase2`, not
    `1.0.0`. It is a labelling decision for the release, not a code
    failure — but it must be decided before any public-facing release,
    or the app will ship under a "phase2" name.
14. **Special-issues gaps are blocking, not cosmetic.** Empty
    `articles/` (full texts missing), queue-location discrepancy, and
    2 partially-verified posts (author/date not seen on the source
    page) must ALL resolve before any special-issue reopen — and the
    reopen still needs its own order.
15. **Voices registry is a draft.** Only a subset of voices is
    source-confirmed; the rest are pending. Publishing voice pages for
    unconfirmed voices would violate the registry's own criteria.
16. **Night-desk and several pre-freeze services were never verified
    as ever having run** (night desk's first auto-run post-dates the
    freeze; several Phase-3 blog pieces are design-only). Phase 2/5
    dry-runs must verify them before they ship, not assume they worked.
17. **Numbers in this plan are frozen snapshots (mostly to 2026-10-24).**
    Nothing after the freeze was verified as live. If a number here
    disagrees with a later live reading, the live reading governs and
    this plan must be corrected — not the other way round.

---

## 9. What this plan deliberately does NOT do

- No cron/bot/channel/service is enabled, started, posted, or unfrozen.
- No production code, database, or freeze state is changed.
- No GitHub push is made (this file is lab-only, untracked until Yasin
  decides otherwise).
- No Firebase project, `google-services.json`, test channel, or release
  signing is created.
- No invite code, token, or secret appears in this plan.
- No live system is claimed to be running. The app, edge, and bridge
  states described are the last verified lab/frozen states.
- Execution phases (§5) are a forecast, not a start. The first
  execution step is Phase 1, and it starts only when Yasin says so —
  this investigation task does not start it.
