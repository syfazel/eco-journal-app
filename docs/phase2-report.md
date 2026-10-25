# Phase 2 — Channel Surfaces: implementation report (LAB ONLY)

Date: 2026-10-25 (Asia/Tehran). Spec:
`docs/big-update/phase2-channel-surfaces-spec.md` (all sections,
including §11/§12, criteria P2-A1–P2-A13). Parent plan:
`docs/big-update-plan.md` §5 Phase 2.

Status: IMPLEMENTED IN LAB, NOT BUILT, NOT PUSHED, NOT
DISTRIBUTED. No Gradle build and no cloud build were run in this
pass (no JDK/SDK/Gradle exists in this sandbox); no live cron,
bot, channel, or freeze state was touched; no FCM activation; no
test channel created; no special-issues publication; no voices
publication. Work is committed LOCALLY only — the parent owns
any push/build decision.

READ THIS FIRST — the single most important honesty note, carried
from the spec §14: Phase 2's typed surfaces are only as
trustworthy as publisher fields (`format_type`, `urgency_score`,
`crisis_level`, SITREP section shape) that do NOT exist in any
shipped edge data today, and whose only real source is the Phase 3
bridge, which is itself gated on a test channel only Yasin can
create. Every Phase 2 pass below is therefore a FIXTURE pass
until Phase 3 exists, and fixture shape may diverge from real
publisher shape. **The first real typed item must trigger
re-verification of P2-A1–P2-A4**, not be treated as a routine
sync. No criterion below is claimed as phone-passed.

## 1. What was built

### 1.1 Pure domain + time (no Android UI dependency)

- `app/src/main/java/com/ecojournal/app/data/Jalali.kt` (new):
  Jalali (Shamsi) conversion + Asia/Tehran clock. Verified
  against 8 Gregorian↔Jalali anchors (see §4). The conversion
  uses TRUNCATING division (reference algorithm's `~~(a/b)`);
  floor division was tried first and shifted Jan/Feb dates by a
  full cycle — caught by mirror-testing, fixed, and documented
  in the file.
- `app/src/main/java/com/ecojournal/app/data/NightLaw.kt` (new):
  Law 15 evaluated ONCE per item at first sync from shipped
  fields only, in evaluation order (FLASH exempt → night
  L3/L4-WATCH exempt → night ≥85 instant → night 70–84 held →
  night <70/NULL feed-only → day ≥70 instant → day else
  feed-only). Night = 00:00–08:00 Tehran from
  `published_at_epoch`; exactly 08:00:00 is DAY. Pin expiry:
  6h wall-clock age.
- `app/src/main/java/com/ecojournal/app/data/Phase2Domain.kt`
  (new): format badges/colours/caps (daily VOLUME caps: FLASH
  999 sentinel, WATCH 60, GEOPOL 60, MARKET 30, VERIFY 40,
  SITREP 12, WIRE none — O-1 open); crisis-level chip colours;
  slot order/headers; bundle-kind labels; attribution rules
  (ONLY Donya-e Eqtesad + Tabnak may ever be verbatim);
  verdict chips; the full §10 anomaly code list; list rules
  (dedup by `story_id`, rail order, radar order, staleness =
  older than 1× the edge-supplied interval, never guessed).
- `app/src/main/java/com/ecojournal/app/data/ChannelParsing.kt`
  (new): edge JSON → Room entities with the spec's corrupt /
  incomplete handling (unknown format / empty headline / bad
  status / bad publish time → counted + skipped; WIRE link
  dropped at render; bad bundle membership → unbundled;
  out-of-range score → feed-only; malformed SITREP/VERIFY/
  MARKET → rendered with the §3.3 incomplete line, never
  padded, truncated, or invented). Night-law fields are
  preserved on re-download, never recomputed.

### 1.2 Room schema (v4 → v5, additive only)

- `data/AppDatabase.kt`: new tables `channel_items`,
  `bundles`, `radar_signals`, `bundle_bookmarks`; new
  `ChannelDao` (visible/instant/held/search/bookmark queries;
  held items excluded from every list query by construction);
  `MIGRATION_4_5` (additive only, no existing table altered).
- `data/DatabaseProvider.kt`: migration chain extended to
  `MIGRATION_4_5`.
- `data/UpdateEngine.kt`: `SyncSections.CHANNEL_TYPED` added;
  `ALL` now lists 12 sections (Diagnostics gains the
  `channel_typed` line automatically).

### 1.3 Sync engine (no new sync mechanism)

- `data/EdgeRepository.kt`: per-section sync blocks for
  `channel_typed`, `bundles`, `radar` on the exact Phase 1
  pattern (manifest-first, per-section hash, one transaction
  per section, hash stored only on complete success, failed /
  corrupt section never empties its cache). Held release runs
  on any successful sync at/after `held_until_epoch` — release
  is a time rule, not a bundle-header rule (§9.4), so held
  items release even if no Morning Bundle header ever ships.
  Post-pass computes the overwrite-for-today anomaly codes
  (caps, bundle-shape flags); parse anomalies accumulate for
  the day. Notification dispatch (full syncs only): one urgent
  notification per newly synced INSTANT item (deduped by the
  stored `notified` flag; an item that arrived while the
  toggle was off is marked notified WITHOUT firing — no
  retro-notification); one default-channel Morning Bundle
  notification per bundle, never before 08:00 Tehran,
  suppressed when the bundle was already opened (§9.5).
  War Room header fields (`war_room` object in radar.json)
  are stored in meta when shipped, cleared when absent.
- `util/Notifications.kt`: new `urgent` channel (high
  importance, sound, vibration — INSTANT items only) and the
  Morning Bundle notification (default channel, count only,
  never a headline leak). Deep-link extras for channel items
  and bundles added.

### 1.4 Screens (all surfaces, all §11 states)

- `ui/components/ChannelCard.kt` (new): `TypedCard` — badge,
  crisis chip, score tag, prices line (MARKET, tap → Market
  tab), verdict line (VERIFY), 5 sections + exactly-2
  watch-next (SITREP), source line per §2.6 (WIRE: name only,
  NO link button, ever), position tag (bundle pages only),
  «بسته» tag, «از شب گذشته» tag, timeline hint, guarded
  source button (http(s) only, never the blog).
- `ui/screens/WarRoomScreen.kt` (new): header block (level —
  shipped war_room level, else latest SITREP level, else
  «سطح بحران اعلام نشده», NEVER a default L1; verbatim
  why-line only when shipped; radar cadence line; last-SITREP
  time; level-change note from two shipped SITREP levels
  only), SITREP list (newest first, FULL bodies), radar
  block (layers «لایه ۱/۲/۳» + unclassified tail, severity
  then recency, stale signals under «سیگنال‌های قدیمی» with
  the «قدیمی» tag, linked-story taps, no radar notifications),
  arc view («مسیر روز», chronological, level-path strip).
  Order fixed per spec §6.1. Per-section error lines + retry.
- `ui/screens/BundlesScreen.kt` (new): bundles list (kind
  label, count, Shamsi date) + `BundlePageScreen` — single
  scrollable page in exact `member_app_keys` order, position
  tags, missing-member honest line, bundle bookmarking, and
  the Morning Bundle page in fixed §9.3 section order
  (held → Night Desk card → slot/digest → SITREP arc →
  short-news; position tags refer to whole-bundle position,
  never renumbered). Special Issues appears ONLY as the
  single line «ویژه‌نامه‌ها — متوقف است» (non-tappable, no
  content). The disabled morning digest renders nowhere
  (its header is held at parse; its items still render in
  feed individually).
- `ui/screens/ChannelReaderScreen.kt` (new): typed reader with
  the RED bar in its header (via TypedCard), full body and
  format blocks, bookmark toggle, and the story timeline
  (chronological, oldest first, latest pinned as
  «آخرین به‌روزرسانی»). A retracted item shows the honest
  «این خبر دیگر در دسترس نیست» line, never a stale body.
- `ui/screens/FeedScreen.kt`: urgent rail at the top of the
  feed (max 5, FLASH-first ordering, «N خبر فوری دیگر»
  one-off view, pin-expiry handling — see §3 decisions),
  format filter chips (single-select, session-only, feed
  only), slot-grouped day view on the «همه» page (newest day
  first, slots صبح/ظهر/عصر/شب then «بدون بازه», deduped by
  story_id), merged offline search (articles + typed, badges
  shown, not format-filtered, held items excluded), offline
  line on sync failure.
- `ui/screens/DiagnosticsScreen.kt`: per-section lines for
  `channel_typed`, `bundles`, `radar` (via the extended
  section list); held count («در انتظار بسته صبح: {n}»,
  counts only, never titles); per-format today counts
  against daily caps; non-zero anomaly lines only.
  Copy-diagnostics copies EXACTLY the displayed lines
  (global + per-section + Phase 2 lines) — verified by
  construction (one list builds both display and copy).
- `ui/screens/BookmarksScreen.kt`: typed-item bookmarks and
  bundle bookmarks (with orphan bundle rows + prune);
  live channel bookmarks are filtered OUT of the orphan list
  (the orphan query cannot see channel rows), while retracted
  channel bookmarks correctly stay orphans (P2-A10).
- `ui/screens/ArticleScreen.kt` + `ui/components/ArticleCard.kt`:
  energy bar (see §2).
- `MainActivity.kt`: bottom tabs «اتاق جنگ» and «بسته‌ها»
  added (War Room is a top-level section, not a feed tab);
  routes `warroom`, `bundles`, `bundle/{bundleId}`,
  `channel/{appKey}`; notification deep links for urgent
  items and Morning Bundles; bottom bar hidden on reader /
  bundle / diagnostics routes.
- `app/src/main/res/values/strings.xml`: 60+ new strings; every
  §11 empty/error string is exact per spec; no generic
  "no data" string is shared across surfaces.

### 1.5 Edge fixtures + verifier (fixture-based testing only)

- `edge/generate_phase2_fixtures.py` (new): builds a complete
  fixture edge dir from a generated base (default target
  `edge/out-phase2-fixture`, committed locally for the phone
  test; the parent decides how it is served — no push was
  made here). 48 typed items, 8 bundles, 6 radar signals,
  fixture day Shamsi 1405-08-03 (2026-10-25 Tehran). Covers:
  all 7 formats; night-law cases (FLASH night no-score,
  WATCH L3 night score 50, WATCH L2 night 75 held, GEOPOL
  night 80 held, GEOPOL night 85 instant); old FLASH for
  pin expiry; 2 same-day SITREPs (L2→L3 arc + level change);
  malformed SITREP (3 sections, 1 watch-next); WIRE with
  link; verbatim / translated / blocked-verbatim; story-dedup
  pair; NULL-slot item; unknown format; empty headline;
  out-of-range score (150); broken bundle membership;
  retracted item (also a Morning Bundle member); short-news
  (3), slot-bundle, marginalia, hourly bundles; Night Desk
  (12 items); morning-digest (blocked) and unknown-kind
  (held) bundles; radar layers 1/2/3 + unclassified + stale
  + unranked signals. All `channel_message_id` values are
  NULL except clearly fake ids ≥ 9000001. No blog URL, no
  probability, no live channel data anywhere.
- `edge/verify_phase2_fixtures.py` (new): 70 fixture-side
  checks, ALL PASSING (formats, independently recomputed
  night-law decisions, held set, war-room fields, radar,
  Morning Bundle composition, bundle positions, slots, dedup
  pair, integrity fixtures, hygiene, manifest hash
  recomputation for all three sections, gated sections
  unchanged). This verifies the FIXTURES, not the app —
  the app side still needs the phone test (§5).

## 2. Cross-phase bar rule — implementation (file:line)

Yasin's rule (recorded in `docs/big-update/new-requirements.md`
§0, applying to every later phase): Red Alert / War Room has
its OWN SEPARATE CATEGORIES inside the app — NOT a parallel
service, NOT a fully separate tab — with a yellow or red bar /
strip on feed cards and reader headers, inside the same
feed/tabs system.

- Colour decision [SPEC DECISION — the spec fixes badge
  colours but no bar colours]: RED (#C62828) for war-room /
  red-alert items; YELLOW (#F9A825) for energy-domain items.
  A typed item is always RED even if its topic is energy
  (red-alert treatment outranks domain colour).
- `app/src/main/java/com/ecojournal/app/data/Phase2Domain.kt:24-37`
  — `DomainBar` enum + `DomainBars` (RED_ARGB line 28,
  YELLOW_ARGB line 29, `forChannelItem()` → RED line 32,
  `forArticle()` → YELLOW iff category == "energy" lines
  35–36).
- `app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt:60-84`
  — `DomainBarStrip` composable (bar + distinct category
  label, never colour alone); line 240–242 — every typed
  card renders it with `forChannelItem()` (RED) and its
  format badge text as the label.
- `app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt:111`
  — bar computed per article; lines 162–163 (hero) and
  224–225 (row) — energy articles render the YELLOW bar with
  the «انرژی» label.
- `app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt:219-221`
  — reader header renders the same YELLOW bar + «انرژی»
  label for energy articles.
- The typed reader header bar is the TypedCard header itself
  (ChannelReaderScreen renders TypedCard with `preview =
  false`); no second strip was added, so the bar appears
  exactly once per reader.
- No separate service and no separate tab was invented: War
  Room stays a top-level section per spec §4, red-alert items
  stay in the feed with their bar + badge, and energy items
  stay in their existing category tab with the yellow bar.

## 3. Decisions taken where the spec left a gap ([SPEC DECISION]s and additions)

All are recorded here so a reviewer can challenge each in one
place. None invents content, a threshold, or a section name.

1. Bar colours (above) — spec fixes badge colours only.
2. Edge `attribution` field: the spec's §2.6 table needs to
   know whether an item is verbatim/translated, but §2.2's
   field table has no such field. Phase 2 requires the edge
   to ship `attribution` ("verbatim" / "translated"); without
   it, items render NORMAL (never inferred verbatim from a
   source name alone). A verbatim claim from a non-allowlisted
   source renders NORMAL and counts `verbatim_not_allowed`.
3. War Room header source: the spec's header needs a shipped
   level/why/interval. Phase 2 reads an optional `war_room`
   object in radar.json (`crisis_level`, `why_level`,
   `radar_interval_minutes`); when absent, the level falls
   back to the latest SITREP's level (never L1), the why-line
   is omitted, and the interval is "not announced" (which also
   disables staleness labels, per spec §6.4). Phase 3 must
   ship this object or confirm the SITREP fallback.
4. Typed-item status vocabulary: spec §2.2 allows only
   `posted` / `retracted`. Phase 2 follows it strictly
   (anything else → `bad_status`, skipped). If the real
   publisher ships `approved`, the first real item will
   surface this immediately — part of the P2-A1–P2-A4
   re-verification.
5. Pin-expiry landing position: when the rail's first card is
   >6h old, it leaves the pinned position; the rest re-sort
   and the expired card moves to the END of the rail (still
   visible, never hidden). The spec orders the expiry but not
   the landing position.
6. Feed integration: the «همه» page renders typed day/slot
   groups FIRST, then article rows, in one LazyColumn.
   Articles carry no slot field in vNext data, so their rows
   keep existing section order below the typed groups instead
   of being forced into «بدون بازه» (which would restyle the
   whole production feed on day one for zero typed data).
   Typed grouping itself is fully implemented per §7.
7. Morning Bundle section classification order: held →
   Night Desk → slot/digest → SITREP → short-news, with
   first-match wins (a held SITREP stays in section 1; a
   SITREP in a short-news bundle stays in section 4). The
   spec fixes section order but not multi-membership
   precedence.
8. New fixed codes for spec-unnamed cases (all documented in
   `Phase2Anomalies`): `bad_section` (section file
   unparsable), `bad_published_at`, `bad_status`,
   `bad_sitrep_empty`, `verbatim_not_allowed`.
   `bad_headline` is spec-named (§2.2).
9. Radar severity is opaque: the spec never enumerates
   severity values, so Phase 2 ranks ONLY on the shipped
   `severity_rank`; unranked signals sort after ranked ones
   (spec §6.3's own rule). `bad_radar_field` counts bad
   layers only.
10. Bundle dates are compared after Latin-digit normalisation
    (`toLatinDigits`), so a Persian-digit Shamsi date from
    the edge still matches the day key.
11. Morning Bundle notification `n`: held-member count, else
    bundle member count (spec §9.5's wording, applied
    literally).

## 4. Verification performed (no build, no phone)

- Brace/paren/bracket balance: BALANCED in all 20 touched
  Kotlin files (script `/tmp/p2_verify.py` — scratch, not
  committed; re-runnable logic, not a build).
- All 135 `R.string.*` references in touched files resolve
  against strings.xml (180 defined, 0 missing, 0 duplicates);
  strings.xml parses as XML.
- Fixture verifier: 70/70 PASS (see §1.5).
- Jalali mirror test (same algorithm in Python): 8/8 anchors
  pass, including 2026-10-25 = 1405-08-03, 2026-03-21 =
  1405-01-01, 2025-03-21 = 1404-01-01, 2000-01-01 =
  1378-10-11. The truncating-division catch (§1.1) was found
  by this test, not by inspection.
- Night-law mirror test: the verifier recomputes every
  fixture decision independently (zoneinfo Tehran) and
  matches the spec table, including both exemptions.
- Python files compile (`py_compile` clean).
- Hygiene greps: no blog button/link in code, no model
  probability on any surface, no FCM use, no token/code/
  secret in any new file, no live channel id in any fixture
  or test config (all fixture ids NULL or ≥ 9000001).
- NOT verified (stated plainly): no Gradle/cloud build, no
  APK, no phone run, no Room migration run, no Compose
  preview. A Kotlin compile error that balance/string checks
  cannot see (e.g. a type mismatch) would surface only at
  the parent's cloud build. The parent's build is therefore
  a GATE, not a formality, before any phone test.

## 5. Per-criterion results P2-A1–P2-A13

Every criterion is LAB-READY (code + fixtures exist and the
fixture side is verified) and PHONE-PENDING (no phone run
happened). "Lab-ready" is NOT a pass. A criterion passes
only on Yasin's real phone (or a phone he designates), per
spec §12.1. Phone-test procedure: serve
`edge/out-phase2-fixture/` as the edge (mechanism: parent's
choice — local static host or a test Pages branch; NOT the
live edge, NOT pushed here), sync, and observe each item
below. Times below are fixture Tehran times; no real night
and no phone-clock change is needed for any criterion.

- P2-A1 (typed cards + filter): LAB-READY / PHONE-PENDING.
  Observe: 7 fixture cards with correct badge text/colour
  and format blocks (MARKET prices line, VERIFY verdict
  line, SITREP 5 sections + 2 watch-next, WIRE no link
  button); each filter chip shows ONLY that format's cards;
  «همه» restores the full feed.
- P2-A2 (War Room): LAB-READY / PHONE-PENDING. Observe:
  header L3 (shipped war_room) with verbatim why-line,
  «رادار: هر ۶۰ دقیقه», last SITREP 10:00; SITREP list
  newest-first with full bodies; arc «مسیر روز» chronological
  with level path L2 → L3.
- P2-A3 (night-held): LAB-READY / PHONE-PENDING. Observe
  after syncing fixtures (fixture time is night for the held
  items, regardless of the phone's real clock): NO urgent
  notification; held items NOT in rail/feed/search;
  Diagnostics held-count = 2 with no title leaked. NOTE:
  the held items' `held_until_epoch` is 2026-10-25 08:00
  Tehran, which is in the PAST at any later phone test —
  so a phone test that syncs the fixtures after 08:00 will
  RELEASE them on first sync (correct per §9.4) and P2-A3's
  "invisible" assertion must be observed on a FRESH install
  whose first sync is evaluated against the fixture's own
  night window, OR by re-stamping the fixture day to the
  test day before serving. The parent/tester must pick one
  path and record it; do NOT fake it with a phone-clock
  change (spec §12.1 forbids that). Same caveat applies to
  P2-A5's "post-08:00" timing.
- P2-A4 (urgent): LAB-READY / PHONE-PENDING. Observe: ONE
  urgent-channel notification per instant fixture (sound,
  high importance), tap deep-links to the item reader, item
  is the rail's first card; re-sync does NOT re-notify.
  (The FLASH night no-score item and the WATCH L3 night
  item both exercise exemptions.)
- P2-A5 (Morning Bundle): LAB-READY / PHONE-PENDING.
  Observe: bundle page in §9.3 order, held items tagged
  «از شب گذشته», Night Desk as ONE card with 12 items in
  order, EXACTLY ONE bundle notification (or zero if the
  bundle was opened first — tester records which path).
  See the P2-A3 timing note.
- P2-A6 (pin + expiry): LAB-READY / PHONE-PENDING. Observe:
  a newer instant fixture supersedes the rail's first card;
  the old FLASH fixture (2026-10-24 18:00, >6h old at any
  2026-10-25 test) has left the pinned position.
- P2-A7 (slots + bundles): LAB-READY / PHONE-PENDING.
  Observe: day view groups under «صبح/ظهر/عصر/شب» in order,
  NULL-slot item under «بدون بازه»; bundle pages show
  «{position} از {total}» on every member in position
  order; the Morning Bundle's retracted member renders the
  honest «برخی اقلام این بسته در دسترس نیست» line with no
  substitution.
- P2-A8 (radar): LAB-READY / PHONE-PENDING. Observe: layers
  «لایه ۱/۲/۳» in order + unclassified tail, severity-then-
  recency within layer, linked-story tap opens the story
  reader, unlinked signals have no dead affordance, the
  previous-day signal renders under «سیگنال‌های قدیمی»
  with the «قدیمی» tag, and radar generates NO notification.
- P2-A9 (states): LAB-READY / PHONE-PENDING. Observe:
  airplane-mode launch shows the full last-good cache on
  every Phase 2 surface with the offline line and nothing
  emptied (E1 re-verified for new surfaces); a per-section
  failure (fixture: `radar` section unreachable) shows the
  per-section error line + retry on Radar only; every §11
  empty string is observed at least once across fixtures
  (or the unobserved one is recorded as untested, not
  passed — the tester must tick them off, including
  «ویژه‌نامه‌ها — متوقف است» and the Morning Bundle empty
  line on a day with no bundle).
- P2-A10 (retraction + bookmark): LAB-READY / PHONE-PENDING.
  Procedure: bookmark a typed fixture, then sync a fixture
  revision that retracts it (the current fixtures ship one
  item already retracted; the tester bookmarks it AFTER a
  first sync only if a pre-retraction revision is served —
  otherwise bookmark the retracted item's row via the
  two-step procedure: serve fixtures WITHOUT the retraction,
  bookmark, re-serve WITH it, sync). Expected: item leaves
  feed/search/bundle lists, bookmark survives as an orphan
  row with prune action. This two-step procedure is REQUIRED
  — a single-sync observation cannot prove orphan survival.
- P2-A11 (Diagnostics + copy): LAB-READY / PHONE-PENDING.
  Observe: per-section lines for `channel_typed`, `bundles`,
  `radar`; non-zero anomaly lines; copy-diagnostics output
  compared character-for-character with the displayed lines
  (no hash values, no content, no held titles, no codes /
  tokens). The copy list and the display list are the same
  object in code, but the phone comparison is still required.
- P2-A12 (freeze + rehearsal hygiene): LAB-READY /
  PHONE-PENDING, with the lab half verified now: no cron /
  bot / channel state changed during Phase 2 work (only
  `cron.list`-style reads were never even needed — no cron
  tool was used at all in this pass); the live channel id
  appears NOWHERE in any allow-list, fixture, or test config
  (fixtures use only NULL or ≥ 9000001 fake ids; grep
  asserted by the verifier's hygiene checks); Special Issues
  renders only «ویژه‌نامه‌ها — متوقف است»; morning digest
  renders nowhere (header held at parse); no blog button on
  any Phase 2 surface; no model probability on any surface.
  The phone half (tester confirms after the phone test)
  remains open.
- P2-A13 (format-integrity fixtures): LAB-READY /
  PHONE-PENDING. Observe: the malformed SITREP renders its
  3 shipped sections + 1 watch-next + the §3.3 incomplete
  line (never padded to 5, never truncated); the WIRE-with-
  link fixture renders with NO link button and Diagnostics
  counts `wire_link_dropped`.

Exit gate (spec §12.3): Phase 2 is DONE only when P2-A1–P2-A13
all pass on a real phone, or a criterion is explicitly
recorded as lab-only with Yasin's acknowledgement. Phase 2
completion does NOT open Phase 3's live test (needs Yasin's
test channel + allow-list) and does NOT open Phase 5 (needs
Phase 1's E-gates on the phone). A criterion failed on the
phone returns to Phase 2 implementation.

## 6. Phase 1 regression check (explicit)

Yasin provisionally reported the Phase 1 install "seems all
good" after uninstall/reinstall. That does NOT retro-pass the
E-gates (spec gate note), and this pass does not claim
otherwise. What Phase 2 changed in Phase 1 paths:

- Articles/shorts/market/channel_refs/invites sync blocks:
  UNTOUCHED (byte-identical logic; only the state-only loop
  lost `bundles`/`radar`, which are now real sections — the
  intended Phase 2 change, not a regression).
- Legacy index sync: UNTOUCHED.
- Manifest parser: iterates one more section key; old
  manifests without `channel_typed` skip it harmlessly.
- Room v4 → v5: additive migration only; a v4 install keeps
  its cache. (Migration itself is phone-unverified until the
  parent's build + install test.)
- Feed: with zero typed items, the feed is unchanged EXCEPT
  the format-filter chip row, which now always renders (it
  filters typed cards; with none, «همه» shows the old feed).
  The rail renders only when instant items exist.
- Diagnostics: lines ADDED; copy still equals display.
- SyncWorker: UNTOUCHED (typed notifications are dispatched
  inside `EdgeRepository.sync`, which the worker already
  calls — no double notification: article keys and typed
  keys travel on separate paths).
- No Phase 1 regression was found by inspection, but E1–E8
  remain PHONE-PENDING exactly as the Phase 1 report left
  them. If any E-gate fails on the phone, Phases 3 and 5
  stay gated regardless of Phase 2's state.

## 7. What Phase 3 needs from this phase

1. The production edge generator must emit `channel_typed`,
   `bundles`, and `radar` sections in the fixture shapes
   (field tables in spec §2.2–§2.4 + the `attribution` and
   `war_room` additions in §3 above) — today ONLY the lab
   fixture builder emits them. The manifest section-hash
   rule (§2.1) and `published_empty` state (live-but-empty
   section, never `not_yet_published` once shipped) must be
   honoured by the generator.
2. Bridge staging merge (Phase 3 work): `channel_message_id`
   populated from real channel posts (never fabricated);
   the test channel (Yasin-only creation) + allow-list.
3. Publisher confirmations (first-real-item re-verification
   for P2-A1–P2-A4): status vocabulary (`posted` only, or
   also `approved`?); `attribution` shipping; `war_room`
   object shipping (or the SITREP fallback confirmed);
   SITREP canonical section names (spec O-2 — the app
   renders shipped headings until then, and must not claim
   them as canonical); WIRE cap/budget (O-1); radar severity
   ranks and interval cadence per crisis level; bundle
   `bundle_date_shamsi` format (Latin `YYYY-MM-DD` assumed).
4. Night Desk live verification (O-4): fixtures prove
   rendering only; the live 00:05 run never ran before the
   freeze and remains unverified until lift. Phase 2 claims
   no live Night Desk behaviour.
5. Carried open items (spec §13, unchanged): O-1 WIRE cap,
   O-2 SITREP section names, O-3 WATCH/GEOPOL/MARKET/VERIFY
   word budgets (generator-side, never render truncations),
   O-4 Night Desk live run, O-5 market alert thresholds
   (Phase 4, never guessed), O-6 Morning Bundle timing
   under Doze (polling reality, §9.4 honesty line).
6. Phone-test serving decision (parent): how
   `edge/out-phase2-fixture/` reaches the test phone, and
   which P2-A3/P2-A5 timing path is used (fresh-install
   night-window observation vs re-stamped fixture day).
   Record the choice in the phone-test report; do not
   improvise it during the test.

## 8. New requirements recorded (not forced into Phase 2 code)

Full document: `docs/big-update/new-requirements.md`.

- (a) ECO JOURNAL wordmark: small English handwritten/script
  font like the blog logo, header only, never the NYT name/
  logo. LICENSING BLOCKER: the blog font Blacksword is
  personal-use-only — check the licence, buy app-use rights,
  or (default) use a freely-licensed OFL script lookalike.
  NOT implemented in Phase 2.
- (b) Tabs system improved over the current one (smoother,
  better). NOT implemented in Phase 2 (existing tabs reused,
  two tabs added); needs a baseline phone comparison first.
- (c) AFTER all phases: 1–2 helper modules, DESIGNED ONLY
  in new-requirements.md, NOT implemented: H1 Reading Ruler
  + Calm Mode (presentation-only reading comfort), H2 Story
  Arc Strip (graphical timeline navigation, badge colours
  reused, no new data, no predictions). Each gets its own
  phone test after Phases 1–7; neither may change sync,
  notification, night-law, bar, badge, or bundle behaviour.
- Cross-phase bar rule (§0 of that doc): implemented in
  Phase 2 (§2 above) and binding on every later phase.

## 9. Files changed (lab repo only, local commit)

Kotlin (new): `data/Jalali.kt`, `data/NightLaw.kt`,
`data/Phase2Domain.kt`, `data/ChannelParsing.kt`,
`ui/components/ChannelCard.kt`,
`ui/screens/WarRoomScreen.kt`, `ui/screens/BundlesScreen.kt`,
`ui/screens/ChannelReaderScreen.kt`.
Kotlin (modified): `data/AppDatabase.kt`,
`data/DatabaseProvider.kt`, `data/EdgeRepository.kt`,
`data/UpdateEngine.kt`, `util/Notifications.kt`,
`util/PersianFormat.kt`, `ui/components/ArticleCard.kt`,
`ui/screens/FeedScreen.kt`, `ui/screens/ArticleScreen.kt`,
`ui/screens/BookmarksScreen.kt`,
`ui/screens/DiagnosticsScreen.kt`, `MainActivity.kt`,
`app/src/main/res/values/strings.xml`.
Edge tooling (new): `edge/generate_phase2_fixtures.py`,
`edge/verify_phase2_fixtures.py`,
`edge/out-phase2-fixture/` (generated fixture edge: base
snapshot + 3 fixture sections + rewritten manifest).
Docs (new): `docs/phase2-report.md` (this file),
`docs/big-update/new-requirements.md`.
NOT changed: any cron, bot, channel, freeze state, live edge
(`edge/out/` untouched), FCM config, signing config, or the
pre-existing modified `docs/vnext-plan.md` (touched by an
earlier pass, left alone).

## 10. Constraints honoured

Lab repo only. No push, no cloud build, no APK distribution.
No live cron/bot/channel change. Freeze untouched (all ECO
JOURNAL schedules remain disabled; nothing was re-enabled).
No FCM activation. No test channel created (Yasin-only). No
special-issues publication (frozen line only). No voices
publication (registry still gated). No tokens, invite codes,
or secrets anywhere (grep-verified: no new file contains
one; fixtures use only fake ids). No model probability on
any surface. No private report / daily-letter content. No
blog button/link anywhere. Persian digits, Shamsi date +
Tehran time in bylines, Persian half-spaces in all new
strings, one story = one card (dedup by `story_id`).
If any P2 gate fails on the phone, that chain stops there —
nothing in this report waives it.
