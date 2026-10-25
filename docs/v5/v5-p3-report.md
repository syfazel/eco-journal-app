# ECO JOURNAL — Version 5, Phase P3 Report: feed + reader rebuild

Status: **FEED + READER REBUILD ONLY — no build, no push, no
publish, no live change.** Lab commit only (see §6).
Task order: P3 feed + reader rebuild, per
`docs/v5/v5-program-plan.md` §2–§3, on top of P2 commit
`54fdab4`, under Yasin's full go-ahead of 2026-10-26 ~01:36
Tehran (all open decisions delegated to Muse).

## 1. G1 verdict — G1 REMAINS OPEN, and this rebuild is NOT a remedy for A1

**Gate G1 is still open. Defect A1 (feed flashes content for
~1 second on refresh, then jumps back to the empty page) is
NOT identified by this phase, and nothing in this phase
fixes it, works around it, or uses it as a rationale.**
No device run was available in this lab run (no phone in
the lab, no build by task order), so the P1 §4
device-reproduction protocol could not be executed — it is
carried forward verbatim in §2 as the gating section, with
every lab-side precondition verified present (§3). The
rebuild below is justified ONLY by the audit's other
findings: state language (plan §1 F1–F3, R1), the feed
digest line, the empty-state section index, honest
filter/search empties, and P2 token adoption. Any reading
of this phase as closing A1 would be wrong, and the Phase
3 feed-stays gate (G3) cannot pass without the device
evidence in §5.

## 2. Gating section — P1 §3 decision table + P1 §4 protocol (carried, not executed)

No Phase 3 feed claim about A1 is valid without a device
run on an instrumented build. When the run happens, read
the Diagnostics copy with this table (from
`docs/v5/v5-p1-report.md` §3, unchanged in meaning):

| What the report shows | Conclusion |
|---|---|
| Fingerprint git commit != the instrumented commit, or version != 4.0.1/5 | H2: phone is not on the instrumented build; disregard the report, install the right build first. |
| Branch trace shows `empty` throughout (article count 0 at every entry) | H1 confirmed in its "not articles" form: the 1-second flash was progress/pager/recreation, never feed rows. Phase 3 follow-up must find what rendered for 1 second (screen recording frame analysis), not rebuild the feed query. |
| Branch trace shows `front_page` (count N) → `empty` (count 0) | Rows really vanished: static review is wrong somewhere below the app layer. Do NOT redesign the feed UI; investigate Room flow emission, process death, or a second DB instance on device. |
| Branch trace shows `filter` or `search` at the "empty" moment, with articles > 0 in the same window | Not a feed failure: a format filter or a search result set was empty. The P3 filter/search empty language (§4) already covers this row's UX side. |
| Sync trace shows manifest `network` (or no manifest entry at all after a tap) | Phone cannot reach Pages (or the tap never reached sync). Device/network fact; no app redesign fixes it. Browser test of the edge URL on the phone is the next step. |
| Sync trace shows articles `partial` with large missing, branch `empty` throughout | H3-leaning: the 249-file loop never completes on this phone/network. Needs a bounded/partial-apply or resume story, not a UI restyle. |
| Recon shows missing ≈ extra ≈ large | H4: seed keys ≠ manifest keys (duplicates expected, not a vanish). Reconcile key derivation; the vanish still needs another explanation. |
| Recon shows missing == extra == 0, sync trace articles `up_to_date`, branch `empty` | Contradiction set: cache and manifest agree, sync claims success, feed still empty → the feed query/branch itself is suspect on device (highest-value follow-up target). |
| Sync trace shows `invite_gate` / `invite_relock` at the flash moment | The "empty page" was the invite gate bounce. Fix is in gate messaging (why the hash invalidated), not the feed. |
| Sync trace shows bundles/radar `http_404`, articles `up_to_date`, branch `front_page` with rows | A2 only: feed works, global clock poisoned. That is the known Phase 4 fix, not A1. |

Device-reproduction protocol (P1 §4, still owed — no step
was runnable in this lab run):

1. Install the instrumented build OVER the current app (no
   uninstall if the shared signature matches; if an
   uninstall is needed, say so — a fresh install changes
   the seed story and must be recorded).
2. Open Diagnostics FIRST and report the fingerprint line
   verbatim (version, code, git commit, edge URL).
3. Go to the feed. If it is empty, tap refresh ONCE and
   screen-record continuously (the recording is evidence;
   a description of the flash is not).
4. The moment the flash happens, note the clock time, then
   open Diagnostics, tap copy, and send the copied report
   verbatim — do NOT summarise it. The report must contain:
   the fingerprint line, the sync-trace block, the
   feed-branch block, and the reconciliation block.
5. Also report: was the phone on Wi-Fi or mobile data, and
   does the phone's browser open
   `https://syfazel.github.io/eco-journal-app/manifest.json`
   (yes/no is enough — this separates a device/network
   failure from an app failure).
6. If the branch trace shows `front_page → empty`, repeat
   with rotation and background/foreground during the same
   session and report whether the vanish follows rotation,
   backgrounding, or neither — that split separates
   Activity recreation (H1) from process death (H3).

Until steps 2–4 produce a report, G1 stays open and no
feed rebuild — this one included — may be described as
resolving A1.

## 3. Lab-side preconditions for the protocol — VERIFIED, no gaps

Both P1 instruments the protocol depends on are present
and wired in the P3 tree (checked by reading + the P3
verifier, not assumed from P1):

- **Fingerprint line, Diagnostics line 1:**
  `DiagnosticsScreen.kt:476` (`diagnostics_fingerprint`:
  version + code + `BuildConfig.GIT_COMMIT` + edge
  BASE_URL), first in the Diagnostics list. The old
  version line is kept directly below it.
- **Branch trace, feed:** `FeedScreen.kt:317` computes
  `feedBranch` from the same states the Column branches
  on (`urgent_all` / `filter` / `search` / `loading` /
  `empty` / `front_page`); `:326` records it via
  `SyncTrace.recordFeedBranch` on every transition
  (branch + article/typed counts). No gap found: the
  trace covers all six rendered regions, including the
  filter/search/urgent-all regions that sit above the
  feed branch.
- **Caveat carried from P1:** the trace is capped at 40
  entries — the protocol's "copy immediately after the
  flash" step exists because a stale report copied hours
  later may have lost the flash window.

## 4. What was rebuilt

### (a) Feed digest line — sync truth on the feed (plan §2)
- `FeedScreen.kt:249` — `feedDigest` derives from the
  EXISTING P1 sync trace (`metaValueFlow(SyncTrace.
  TRACE_KEY)`, parsed by `SyncTrace.parseTrace`): latest
  entry per section; failed sections in
  `SyncSections.ALL` order; a manifest failure wins over
  section failures because it blocks everything. No new
  query, no new sync behaviour, no new stored state — a
  read of diagnosis state that already exists.
- `FeedScreen.kt:617–676` — the digest row under the sync
  row, visible only while NOT syncing and only when the
  trace records a failure; it disappears when nothing
  has failed (never a fake "everything is fine" banner).
  Text by case: manifest `network` → fixed
  `v5_state_offline`; other manifest code → new
  `v5_feed_digest_manifest`; exactly one failed section
  → fixed `v5_state_section_failed` (Persian section
  label via `sectionLabelFa`, `:95–117`); several failed
  → new `v5_feed_digest_failed` (count + worst code,
  where "worst" is defined as the first failed section's
  code in `SyncSections.ALL` order). Exactly one next
  step: Diagnostics (retry already lives on the sync row
  itself). States `up_to_date` / `partial` /
  `not_yet_published` / `unknown` never produce a digest
  line on their own — a partial section is reported by
  the sync row + empty state, and an unpublished section
  is not a failure (the Phase 4 A2 story, unchanged).
- Honest limit: the digest reflects the trace, so a sync
  path that never traces (e.g. the legacy fallback) can
  fail without a digest line; the sync row's own failure
  + retry still shows in that case.

### (b) Feed empty state — fixed language + section index
- `FeedScreen.kt:813–905` — first line by cause:
  seed failure keeps `feed_empty_seed_failed`; code
  `network` → `v5_state_offline`; any other recorded
  code → existing `feed_empty_error`; no sync ever and
  no code → fixed `v5_state_empty_no_sync` (replacing
  the old `feed_empty` text as the first line, because
  that text implied an edge-setup step that no longer
  applies). The old `feed_empty` string is kept ONLY
  for the narrow case of an empty cache AFTER a sync was
  recorded — a state the digest + Diagnostics must then
  explain. Retry + Diagnostics buttons are unchanged
  (pre-existing P1 pattern; the "one next step" rule is
  applied per new line in this phase, see §4(c)).
- `FeedScreen.kt:872–900` — static section index:
  title (`v5_feed_section_index_title`), the four
  sections-hub destinations as one static line
  (`v5_feed_section_index_names`: بسته‌ها • نسخهٔ
  شبانه • تحلیل • روش کار), and a hub button
  (`v5_feed_section_index_open`) via the new
  `onOpenSections` param (`FeedScreen.kt:183`, wired in
  `MainActivity.kt:331` to `nav.navigate("sections")`).
  Gated sections (voices, special issues) are NOT
  listed — they stay gated, never advertised as
  destinations. When unwired (previews) the names still
  render and only the button hides.

### (c) Honest filter / search / section empties
- Filter empty (`FeedScreen.kt:715–737`): the old
  `feed_typed_empty` line ("هنوز خبری منتشر نشده است")
  read exactly like a cache empty. It is replaced by
  `v5_feed_filter_empty`, which names the filter as the
  cause, plus exactly one next step — clear the filter
  (`v5_feed_filter_clear`, session-only, same as
  tapping the «همه» chip). `feed_typed_empty` is now
  unreferenced anywhere; the string itself is left in
  `strings.xml` (removing it is unrelated churn).
- Search empty (`FeedScreen.kt:752–769`): keeps its own
  `search_no_results` string (never confusable with the
  feed empty, plan §1 F4) and gains exactly one next
  step — clear the query (`v5_feed_search_clear`),
  which restores the pager.
- Section-tab empty (`FeedScreen.kt:1167–1175`,
  `FrontPageTabs`): keeps `section_empty` ("in this
  section") and gains exactly one next step — sync
  retry via the new `onSync` param
  (`FeedScreen.kt:1043`, passed as `runSync` at `:925`
  and `:612`).

### (d) Reader — body-empty states (plan §1 R1)
- Article reader (`ArticleScreen.kt:395–424`): when the
  cached body is empty (`bodyBlocks` all blank — the
  test is the body itself; a summary, key points, or
  why-it-matters block does not hide a missing body),
  the page now shows `v5_reader_body_empty` in the
  error colour plus exactly one next step — re-fetch
  from the edge (the not-found retry path:
  `fetchOne` + reload). The top-bar back button is the
  way out, not a second step on this line.
- Channel reader (`ChannelReaderScreen.kt:145–162`):
  same shared string for a non-SITREP typed item with
  a blank body, plus exactly one next step — back.
  SITREP items are excluded: their body is normally
  blank and their sections carry the content (TypedCard
  already shows the incomplete line when those are
  missing). Channel items arrive only via sync, which
  the feed owns, so no sync retry is offered here.

### (e) Sync row — two lines on failure (plan §1 M4/F1)
- `SyncCompact.kt:105` — `maxLines = if (syncFailed) 2
  else 1`. The pre-P3 one-line ellipsis truncated the
  state the row exists to report. Error codes are never
  ellipsized away: codes live on the digest / empty-state
  lines (never single-line-ellipsized), not inside the
  status text.

### (f) Token adoption (P2 tokens on feed + reader)
Type sizes, page margins (16dp), element gaps (8dp) and
the story rule now come from `EcoDesignTokens` in all
six scope files; reader scale is token-identical to the
pre-P3 scale (kept, not resized):

- `FeedScreen.kt` — urgent-rail title + day headers
  18sp → `Type.RowHeadline`; slot headers 15sp →
  `Type.SectionHeader`; search count 13sp → `Type.Meta`
  (13 → 12sp, the only size change in the feed; no P2
  token exists for 13sp); all 16dp paddings →
  `Spacing.PageMargin`, all 8dp spacers →
  `Spacing.ElementGap`.
- `ArticleCard.kt` — hero 26/34 → `Type.HeroHeadline` /
  `LineHeight.HeroHeadline`; rows 18/26 →
  `Type.RowHeadline` / `LineHeight.RowHeadline`;
  summaries 16/24 → `Type.HeroSummary` /
  `LineHeight.HeroSummary`, 15/22 → `Type.RowSummary` /
  `LineHeight.RowSummary`; kicker/byline/duplicate
  notes 12sp → `Type.Meta`; story rule 1dp →
  `Rules.Story` (double rule was already token-owned
  in P2).
- `ArticleScreen.kt:232–240,275` — headline 28sp →
  `Type.ReaderHeadline`, headline line 1.35× →
  `LineHeight.ReaderHeadline` (38sp; pre-P3 product
  37.8sp rounds to the token); body 18sp →
  `Type.ReaderBody`, body line 1.75×/2.0× →
  `LineHeight.ReaderBody` (32sp; pre-P3 31.5sp rounds
  to the token) / `LineHeight.ReaderBodyCalm` (36sp,
  exact). No resize is intended; the ≤0.5sp roundings
  are the token values.
- `ChannelCard.kt` — headline 18/26, body 15/22, meta
  12sp → the same row tokens; 16dp padding →
  `PageMargin`, 8dp gaps → `ElementGap`.
- `ChannelReaderScreen.kt` — timeline rows 15sp →
  `Type.RowSummary`, dates 13sp → `Type.Meta`
  (13 → 12sp, same note as the feed count).
- `SyncCompact.kt` — status 12sp → `Type.SyncRow`,
  last-sync 11sp → `Type.Meta` (11 → 12sp, same note);
  row padding → `PageMargin`, gaps → `ElementGap`.
- Deliberately left literal (no P2 token exists):
  micro-spacers (3/4/6/10/12/14dp), 48dp touch
  targets, 20dp progress indicator, and the channel
  timeline title `20.sp` (`ChannelReaderScreen.kt:166`).
  These are listed here so P5 (or a token-extension
  pass) can close them; they are not silently "done".

### (g) Strings (all new, no duplicates)
- `strings.xml:468–476` — 9 new strings:
  `v5_feed_digest_failed` (2 args),
  `v5_feed_digest_manifest` (1 arg),
  `v5_feed_filter_empty`, `v5_feed_filter_clear`,
  `v5_feed_search_clear`,
  `v5_feed_section_index_title`,
  `v5_feed_section_index_names`,
  `v5_feed_section_index_open`,
  `v5_reader_body_empty`. Existing strings untouched.

### (h) Lab verifier
- NEW `edge/verify_v5p3.py` — static checks only:
  strings parse + unique + P3 arg counts; token
  adoption (no literal `.sp` except the documented
  20sp, no literal 16/8dp, token actually used, reader
  line-height token not product); state wiring
  (digest/filter/search/index/body-empty/section
  retry/two-line row); scope (only P3 files changed vs
  HEAD; sync/DB/diagnostics/store/Gradle untouched; no
  destructive DB calls; branch + query untouched);
  P1 preconditions (fingerprint + branch trace); version
  untouched; report honesty (G1 open, no A1-remedy
  claim). Result this run: **ALL V5 P3 CHECKS PASSED**
  (see §6 for the count). Caveat: the verifier does
  NOT compile anything — see §5.

## 5. Caveats and limits (read before relying on this phase)

- **No build was run** (task order: no build, no push, no
  publish). Kotlin correctness was checked by reading,
  by the balance check (braces/parens balanced in all
  edited files), and by the static verifier — not by
  compiling. Real risks carried to the P6 cloud build:
  the `FeedScreen` digest block (new `remember` +
  `metaValueFlow` + `context.getString` with 1- and
  2-arg strings), the `FrontPageTabs` `onSync` default
  param, the `ArticleScreen` body-empty retry block
  (reuses the not-found reload pattern), and the
  `SectionStates`/`SyncSections` imports in
  `FeedScreen.kt`. If the build fails, these are the
  first suspects, not the token values.
- **Mechanical-replacement incident, caught and fixed:**
  the token pass's naive `8.dp` replace once rewrote
  `48.dp` as `4EcoDesignTokens.Spacing.ElementGap`
  (4 sites: `FeedScreen.kt` filter-chip touch target,
  `SyncCompact.kt` icon size + both retry/details
  buttons) — which would not compile. All 4 sites were
  restored to `48.dp` and the verifier now fails on any
  digit-glued token (`\dEcoDesignTokens`). Reviewers
  should still eyeball the token diff hunks; a static
  check cannot prove a layout is right.
- **Digest coverage limit:** see §4(a) — untraced sync
  paths produce no digest line; the sync row still
  reports those failures.
- **The reader not-found page is unchanged:** it already
  names the state + back + retry, but `fetchOne`
  surfaces no error code, so no code line could honestly
  be added there. Same for the channel not-found line.
- **Reader footer hint (plan §1 R2, one-line hint on
  first use for ruler/calm/arc) is NOT in this phase:**
  it was not in the P3 task order and is not claimed
  here. It belongs to a later polish pass, not to P4's
  sections/sync rebuild.
- **Gated string `v5_state_gated` is not wired here:**
  it belongs to gated screens, not to the feed/reader;
  wiring it into the feed would mislabel gated content
  as a feed state. The hub and the section index both
  exclude gated destinations for the same reason.
- **Bottom-bar 0.85×/1.0×/1.3× fit checks are NOT done
  here:** they need a device (P5 evidence), not lab
  tokens. The bar itself is unchanged in P3.

## 6. Files changed, commit, verifier, and hard-limit compliance

Changed (lab commit only, NO push):
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt`
  (digest + empty/filter/search/section states + section
  index + token adoption + `onOpenSections`/`onSync`)
- `app/src/main/java/com/ecojournal/app/ui/screens/ArticleScreen.kt`
  (reader tokens + body-empty state)
- `app/src/main/java/com/ecojournal/app/ui/screens/ChannelReaderScreen.kt`
  (tokens + body-empty state)
- `app/src/main/java/com/ecojournal/app/ui/components/ArticleCard.kt`
  (hero/row tokens + token rule)
- `app/src/main/java/com/ecojournal/app/ui/components/ChannelCard.kt`
  (row tokens)
- `app/src/main/java/com/ecojournal/app/ui/components/SyncCompact.kt`
  (sync-row tokens + two-line failure)
- `app/src/main/java/com/ecojournal/app/MainActivity.kt`
  (feed → sections-hub wiring only, 3 lines)
- `app/src/main/res/values/strings.xml` (+9 strings,
  no duplicates)
- NEW `edge/verify_v5p3.py` (verifier)
- NEW `docs/v5/v5-p3-report.md` (this file)

NOT touched (pre-existing dirty files left alone, not
committed): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `docs/v5/v5-program-plan.md`,
`bridge/*`, and all other pre-existing untracked files.

Verifier runs this session:
- `edge/verify_v5p3.py`: **ALL V5 P3 CHECKS PASSED (68
  checks, 0 failures).**
- `edge/verify_v5p1.py`: **ALL V5 P1 CHECKS PASSED (70
  checks, 0 failures)** — no P1 regression.
- `edge/verify_v5p2.py`: **ALL V5 P2 CHECKS PASSED (103
  checks, 0 failures)** — no P2 regression.

Hard limits honoured: no cron changes, no live-channel
changes or posts, no special-issues/voices publication
or gate changes (hub + index both exclude them), no
FCM/Play, no Bale work, no Room schema change (trace
meta reuse only, no migration, no destructive clear),
no night-law/bar/gate/cadence behaviour changes (red
and yellow bars untouched; TypedCard bar logic
untouched — only its type/spacing literals changed), no
versionName/versionCode change (still 4.0.1 / 5),
no push, no build, no publish, no live change of any
kind. No feed branch, feed query, sync, or section
logic was altered: the digest only READS the existing
trace, and every other change is presentation (text,
buttons that call existing runners, or token values).

## 7. G3 evidence still owed on a device (Phase 3 gate, not met)

G3 cannot pass without a device run on a build
containing P1+P2+P3. Owed evidence, all recorded (not
asserted):

1. **Feed STAYS across refresh / sync / rotation /
   process-death** — the P1 §4 protocol run (§2) plus a
   recording of: manual refresh, auto-sync, rotation,
   background/foreground, and process kill/restart, each
   ending on a populated feed. This is the A1 gate; it
   is independent of everything P3 rebuilt.
2. **Empty/error/offline matrix on device:** feed empty
   with no sync; feed empty with failed sync (digest +
   code + Diagnostics); feed populated with partial
   sync (digest line visible, feed still readable);
   offline launch (offline line, cached rows shown).
   Each with the §4 language + exactly one next step
   (plus the pre-existing retry + Diagnostics pair on
   the empty state itself).
3. **Filter/search honesty on device:** a filter empty
   that reads as a filter empty (with clear-filter),
   a search empty that reads as a search empty (with
   clear-search), a section-tab empty with retry.
4. **Reader body-empty on device:** a cached bodyless
   article shows the body-empty line + retry (needs a
   bodyless fixture or a real bodyless cached item;
   do not fake it with a deleted body).
5. **Bar checks at 0.85× / 1.0× / 1.3× app font scales:**
   bottom-bar labels on one line at all three scales
   (P2's 12sp bar rule, device-measured — lab tokens
   alone do not pass this).

## 8. What Phase 4 must do next (vs what stays out)

**Phase 4 (sections rebuild + sync/diagnostics rebuild)
MUST:**
1. FIRST consume the §2 protocol result: if the device
   run identifies A1's mechanism, P4's scope follows
   that row of the table (recreation-safe branch,
   bounded/partial-apply sync, gate messaging, or a
   below-app-layer investigation) — it does not
   re-litigate P3's presentation changes.
2. Status-aware bundles/radar sync (nightly guard
   mirrored); per-section success clocks; global clock
   over REQUIRED sections only; edge contract test
   (fetchable ⇒ file exists). Until then the A2 poison
   (bundles/radar `http_404` → `last_sync_at` never
   advancing) is still live, and the P3 digest will
   keep showing it honestly — that is the digest
   working, not a P3 defect.
3. War Room, Market (visible seed-date line), Bundles,
   Nightly (conditional on the first real edition),
   Analysis, Bookmarks (orphan reasons), Search — each
   to the plan §2 state matrix, reusing the P3 state
   language and tokens (not a second language).
4. A Bundles retry that cannot re-run a known-404
   without a changed manifest (retry shows the manifest
   state, not just the error).

**Stays OUT of Phase 4 (unchanged by P3):** the reader
footer hint (§5), the 20sp/micro-spacer token gaps
(§4(f)), TalkBack walkthrough PERFORMED on a phone,
font extremes, contrast MEASURED from device
screenshots (all Phase 5), and the P6 publish checklist
(version bump to 5.0.0/6, cloud build, shared-signing
verification, phone checklist). The P6 build gate
remains blocking for ANY device claim: compile this
tree, verify the APK's `BuildConfig.GIT_COMMIT`
equals the P3 commit, and verify install-over
behaviour before any phone run.
