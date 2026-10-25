# ECO JOURNAL — Version 5, Phase P1 Report: reproduce + instrument

Status: **INSTRUMENTATION ONLY — no redesign, no build, no push,
no publish, no live change.** Lab commit only (see §6).
Task order: P1 reproduce + instrument, gate G1, from
`docs/v5/v5-program-plan.md` (Phase 0, 628 lines).
Base verified before work: HEAD `d827a6c` (4.0.1 edge publish),
gradle still `4.0.1` / versionCode 5 (unchanged — P6 owns the
5.0.0 / 6 bump), live Pages edge still manifest v3.

## 1. G1 verdict — HONEST: A1 remains UNIDENTIFIED

Defect A1 (feed flashes content for ~1 second on refresh, then
jumps back to the empty "no successful sync recorded yet" page
on Yasin's phone) is **NOT identified by this phase**, and no
fix for it is claimed. Instrumentation does not fix anything;
it only makes the next device run discriminating. Phase 2 may
proceed on design tokens (§2 of the plan) but must not present
any feed rebuild as "the A1 fix", and the Phase 3 feed-stays
gate (G3) cannot pass without the device evidence in §4.

What P1 static review ADDED beyond Phase 0 (all verified this
run, not assumed):

1. **Live manifest has ZERO `retracted` items** (full-text
   search of the live manifest this run). The tombstone path
   therefore cannot have emptied anything on the current edge —
   the "sync retracts the feed" mechanism is now disproved
   against live data, not just against code.
2. **Live manifest re-confirms A2 exactly as Phase 0 stated:**
   `articles` count 249; `bundles`, `radar`, `nightly` all
   `status: "not_yet_published"`, count 0, **no `file` field**.
   So `syncBundlesSection` / `syncRadarSection` still fetch
   `bundles.json` / `radar.json` → 404 → global `Partial` →
   `last_sync_at` never advances. A2 is unchanged and is NOT
   fixed in P1 (that is Phase 4 work); it fully explains the
   permanent "no successful sync recorded yet" line, but it
   does NOT explain a 1-second flash-then-vanish, because a
   failed section never empties its cache.
3. **The seed lands atomically.** `BundledSeed` inserts all
   fresh rows in ONE Room transaction at the end
   (`BundledSeed.kt:235-245`: the single transaction) and
   claims the same 249 articles as the manifest. A seed-then-sync-delete sequence
   cannot produce a flash: rows appear all at once or not at
   all, and nothing deletes them afterwards.
4. **Two rendered regions sit ABOVE the feed branch and can
   present an "empty-looking" page while articles exist.**
   `FeedScreen.kt`: a selected format filter returns early with
   only typed cards or `feed_typed_empty`; a non-null search
   result returns early with search rows or
   `search_no_results`; `showUrgentAll` returns early with the
   rail list. None of these is the feed branch, and none was
   covered by Phase 0's branch analysis. The branch trace (§2)
   records all six regions, so a future report can tell a
   filter/search page apart from a genuinely empty feed.
5. **The invite re-lock path is a real whole-app state flip
   that static review had not traced.** `syncV3` and
   `syncLegacy` both call `settings.setInviteOk(false)` when a
   fetched invite list invalidates the stored hash — that
   returns the user to the invite gate, not to an empty feed,
   but a misdescribed "back to an empty page" could be this
   bounce. It is now traced (`invite_gate` / `invite_relock`).
6. **H2 (wrong build on the phone) is still the most testable
   hypothesis and is still OPEN.** Screenshot 1 showed neither
   the 4.0.1 error-code line nor retry/Diagnostics buttons in
   the empty area. Nothing in P1 can retroactively identify
   that build; only the new fingerprint line on the NEXT build
   can. If the phone was on v4 (not 4.0.1), the entire A1
   report may describe pre-fix behaviour, and no redesign
   should be built on it.
7. **The phone-cannot-reach-Pages possibility is still open.**
   No app redesign fixes a device/network fact. The manifest
   trace entry now discriminates it directly: `network` vs
   `http_*` vs `ok` on the first entry after a refresh tap —
   and if NO trace entry appears after a tap, the tap never
   reached the repository at all (old build without trace, or
   a crash before sync), which is itself diagnostic.

Ranked hypotheses after P1 (unchanged order, now testable):
H1 flash is not articles (progress/pager/recreation/gate
bounce/filter-search region) — branch trace decides it.
H2 phone not on 4.0.1 — fingerprint line decides it.
H3 process death mid-249-file loop — trace shows a manifest
`ok` with articles `partial` and a large missing count, and
branch entries showing the feed never left `empty`.
H4 seed/sync key mismatch — reconciliation decides it
(large missing AND extra together = mismatch; it duplicates,
never hides, so a vanish with missing == extra == 0 rules
H4 out).

## 2. What was instrumented (no behaviour change)

Rules kept throughout: no schema change (meta table reuse
only); no sync/feed logic altered (trace writes are
best-effort, swallowed, and placed OUTSIDE Room
transactions); counts / section keys / fixed codes / epochs
only — never titles, bodies, URLs, invite codes/hashes,
tokens, or device ids; nothing is sent anywhere (Diagnostics
display + the existing copy action only); trace capped at 40
entries, oldest dropped.

### (a) Build fingerprint — Diagnostics line 1
- `app/build.gradle.kts:14-23` — `v5GitCommit` from
  `git rev-parse --short HEAD` at build time, hex-only or
  `"unknown"` on failure; `:38` —
  `buildConfigField("String", "GIT_COMMIT", …)`; `:81` —
  `buildConfig = true` (generates
  `com.ecojournal.app.BuildConfig`). Version lines `:36-37`
  UNCHANGED (`versionCode = 5`, `versionName = "4.0.1"`).
- `ui/screens/DiagnosticsScreen.kt:469-484` — fingerprint
  line is now the FIRST line of the Diagnostics list
  (`diagnostics_fingerprint`: version + code + git commit +
  edge BASE_URL). The old version line is kept directly
  below it, unchanged.
- `app/src/main/res/values/strings.xml:101-110` — 10 new
  strings (fingerprint, trace/branch/recon titles, lines and
  "none" lines); arg counts verified by the verifier
  (fingerprint 4, trace line 6, branch line 4, recon line 5).

### (b) Sync trace — per-section attempts with HTTP codes
- NEW `data/SyncTrace.kt:51-54` — meta keys `v5_sync_trace`,
  `v5_feed_branch_trace`, `v5_seed_recon`, cap 40; `:80-108`
  `record()` (section, fixed code, fetched, missing, state);
  `:109-132` `recordFeedBranch()`; `:134-151`
  `recordRecon()`; `:153-213` parsers. `-1` means "not
  applicable" and renders as "not recorded", never as zero.
- `data/EdgeRepository.kt:175-185` — private `trace()`
  helper (same package, no import needed).
- Call sites (all outside transactions; full list verified
  by grep after patching):
  - `sync()`: `:203` bad_manifest, `:206` manifest ok,
    `:224` network, `:229` http_404 → legacy fallback,
    `:234` other HTTP code.
  - `syncOnly()`: `:262` manifest failure, `:265`
    manifest ok.
  - `syncV3` start: `:402` reconciliation (see (d)).
  - Articles `:422` no_section, `:438` up-to-date,
    `:517-522` complete/partial with `fresh.size` + missing.
  - Shorts `:543`, `:557`, `:637-642` (same shape).
  - Market `:664`, `:677`, `:691` fetch code, `:760`
    bad_market, `:802-808` success with snapshot count +
    corrupt count.
  - Channel refs `:838`, `:849`, `:870` fetch code.
  - Invites/revocations: success traces guarded by
    `wants()` (`:913-923` region), failure traces in their
    own `wants()` blocks (`:941`, `:952`) — a
    `syncOnly` for another section never claims these
    sections up-to-date.
  - Channel typed `:1103` up-to-date, `:1208-1213`
    complete/partial with `fresh.size` + missing.
  - Bundles `:1237` up-to-date, `:1250` fetch code (the A2
    404 line), `:1267` bad_section, `:1283-1288` success.
  - Radar `:1312`, `:1325` fetch code, `:1361`,
    `:1380-1385` success.
  - Nightly `:1409` not_yet_published, `:1419`
    up-to-date, `:1429` fetch code, `:1586-1591` success.
  - Invite re-lock: `:1061` (syncV3) and `:2446`
    (syncLegacy) — both paths traced after the patch
    script caught the duplicate block.

### (c) Feed-branch trace — which region actually rendered
- `ui/screens/FeedScreen.kt:239-248` — `feedBranch` computed
  from the same states the Column branches on
  (`urgent_all` / `filter` / `search` / `loading` / `empty` /
  `front_page`), recorded by
  `LaunchedEffect(feedBranch, all.size, typedItems.size)`.
  Count keys are included because rows land in one
  transaction per section, so count changes are transitions,
  not a per-row flood. This is the single most discriminating
  new signal for A1: `front_page → empty` with a falling
  article count proves rows vanished (contradicting static
  review — then the cause is below the app layer: Room flow,
  process death, or a second DB); `empty` throughout proves
  the flash was never feed rows; `filter`/`search` proves
  the "empty page" was a filtered/searched region.

### (d) Seed/manifest reconciliation — H4 test
- `EdgeRepository.kt:390-410` region (recordRecon call at
  `:402`) — at each `syncV3` start, cached article appKeys
  (kind == "article" only, so shorts cannot inflate "extra")
  are compared with manifest article appKeys; one snapshot
  (`local` / `manifest` / `missing` / `extra`) is stored.
  Missing = in manifest, not cached (never fetched or key
  mismatch); extra = cached, not in manifest (stale seed or
  key mismatch).

### (e) Diagnostics display + copy parity
- `DiagnosticsScreen.kt:97-99` — flows for the three meta
  keys; `:389-455` — trace lines (newest 12, reversed),
  branch lines (newest 12), recon lines, built once for
  display AND copy; `:649` — display loop;
  `:700` — copy join extended with all three new lists
  (copy == shown lines, the existing Diagnostics rule).

### (f) Lab verifier
- NEW `edge/verify_v5p1.py` — static checks only: Gradle
  wiring + version unchanged, SyncTrace keys/cap/swallow/
  parsers, EdgeRepository call-site presence (trace count
  ≥ 30, per-section and per-function presence, manifest +
  invite_gate + recon), FeedScreen six branches + effect
  keys, Diagnostics fingerprint-first + display + copy,
  strings present with unique names and exact arg counts,
  no destructive DB calls in edited files.
- Result this run: **ALL V5 P1 CHECKS PASSED** (70 checks,
  0 failures). Caveat: the verifier's per-function check for
  the four Phase 2/5 section functions is coarse (it asserts
  `trace(key,` presence file-wide for each); per-function
  placement was confirmed separately by grep line numbers
  listed in §2(b). The verifier does NOT compile anything —
  see §5.

## 3. How to read the next phone report (decision table)

Given a Diagnostics copy from a build WITH this
instrumentation, after Yasin reproduces the flash:

| What the report shows | Conclusion |
|---|---|
| Fingerprint git commit != the P1 commit, or version != 4.0.1/5 | H2: phone is not on the instrumented build; disregard the report, install the right build first. |
| Branch trace shows `empty` throughout (article count 0 at every entry) | H1 confirmed in its "not articles" form: the 1-second flash was progress/pager/recreation, never feed rows. Phase 3 must find what rendered for 1 second (screen recording frame analysis), not rebuild the feed query. |
| Branch trace shows `front_page` (count N) → `empty` (count 0) | Rows really vanished: static review is wrong somewhere below the app layer. Do NOT redesign the feed UI; investigate Room flow emission, process death, or a second DB instance on device. |
| Branch trace shows `filter` or `search` at the "empty" moment, with articles > 0 in the same window | Not a feed failure: a format filter or a search result set was empty. Phase 3 fixes filter/search empty language, not sync. |
| Sync trace shows manifest `network` (or no manifest entry at all after a tap) | Phone cannot reach Pages (or the tap never reached sync). Device/network fact; no app redesign fixes it. Browser test of the edge URL on the phone is the next step. |
| Sync trace shows articles `partial` with large missing, branch `empty` throughout | H3-leaning: the 249-file loop never completes on this phone/network. Phase 3 needs a bounded/partial-apply or resume story, not a UI restyle. |
| Recon shows missing ≈ extra ≈ large | H4: seed keys ≠ manifest keys (duplicates expected, not a vanish). Phase 3 reconciles key derivation; the vanish still needs another explanation. |
| Recon shows missing == extra == 0, sync trace articles `up_to_date`, branch `empty` | Contradiction set: cache and manifest agree, sync claims success, feed still empty → the feed query/branch itself is suspect on device (highest-value Phase 3 target). |
| Sync trace shows `invite_gate` / `invite_relock` at the flash moment | The "empty page" was the invite gate bounce. Fix is in gate messaging (why the hash invalidated), not the feed. |
| Sync trace shows bundles/radar `http_404`, articles `up_to_date`, branch `front_page` with rows | A2 only: feed works, global clock poisoned. That is the known Phase 4 fix, not A1. |

## 4. Device-reproduction protocol (Phase 3 must carry this)

No Phase 2/3 feed claim about A1 is valid without this run,
on a build containing this instrumentation (P6 build gate
must pass first — P1 was not built by task order):

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

Until steps 2–4 produce a report, G1 stays open and Phase 3
must not declare A1 fixed.

## 5. Caveats and limits (read before relying on this phase)

- **No build was run** (task order: no build, no push, no
  publish). Kotlin/Gradle correctness was checked by
  reading, not by compiling. Real risks carried forward:
  `BuildConfig.GIT_COMMIT` generation (needs AGP to honour
  `buildConfig = true` + the `buildConfigField` in
  `defaultConfig`), the `ProcessBuilder` configuration-time
  git call in `app/build.gradle.kts`, and the exact
  `LaunchedEffect` placement in `FeedScreen.kt`. The P6
  cloud build is the gate that must catch these; if it
  fails, P1's fingerprint work is the first suspect, not
  the trace logic.
- The verifier is static only and its per-function section
  check is coarse (see §2(f)); placement was confirmed by
  grep, not by execution.
- Trace entries are capped at 40 and branch entries at 40;
  a long session overwrites early history. For the
  reproduction protocol this is fine (copy immediately
  after the flash), but a stale report copied hours later
  may have lost the flash window — the protocol's
  "copy immediately" step exists for this reason.
- Trace writes add one meta read + one meta write per
  section outcome. No user-visible behaviour changes, but
  this is an extra DB write per sync section; if the P6
  build shows sync slowdowns, the trace write is the
  first thing to profile, not the first thing to blame
  without measurement.

## 6. Files changed, commit, and hard-limit compliance

Changed (lab commit only, NO push):
- `app/build.gradle.kts` (+20/−0 region: fingerprint field
  + `buildConfig = true`; version untouched)
- `app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt`
  (+122/−2: trace helper + 35 patch sites + 2
  `wants()`-guard fixes)
- `app/src/main/java/com/ecojournal/app/ui/screens/DiagnosticsScreen.kt`
  (+105/−1: fingerprint line 1, three trace flows, three
  line blocks, display loop, copy extension)
- `app/src/main/java/com/ecojournal/app/ui/screens/FeedScreen.kt`
  (+25: branch computation + effect)
- `app/src/main/res/values/strings.xml` (+13: 10 strings,
  no duplicates)
- NEW `app/src/main/java/com/ecojournal/app/data/SyncTrace.kt`
  (214 lines)
- NEW `edge/verify_v5p1.py` (verifier, all checks pass)
- NEW `docs/v5/v5-p1-report.md` (this file)

NOT touched (pre-existing dirty files left alone, not
committed): `docs/big-update/new-requirements.md`,
`docs/vnext-plan.md`, `docs/v5/v5-program-plan.md`
(parent's untracked Phase 0 doc), `bridge/*`, and all other
pre-existing untracked files.

Hard limits honoured: no cron changes, no live-channel
changes or posts, no special-issues/voices publication or
gate changes, no FCM/Play, no Bale work, no schema change
(meta reuse only, no migration needed), no night-law/bar/
gate/cadence behaviour changes, no versionName/versionCode
change, no push, no build, no publish, no live change of
any kind. The only Gradle change is the additive
fingerprint field + `buildConfig = true`.

## 7. What Phase 2 / Phase 3 must do next

- **Phase 2 (design system):** may proceed on tokens,
  masthead direction (still Yasin's §5 decision, untouched
  here), bottom-bar reduction, ticker pause + motion
  toggle. It must NOT use A1 as a design rationale for any
  feed change, and must NOT mark G1 passed.
- **Phase 3 (feed + reader rebuild):** must FIRST run the
  §4 device-reproduction protocol on the instrumented build
  and record the G1 verdict from the decision table in §3.
  Only then may it rebuild the feed — and the rebuild's
  scope follows the table row that matched (e.g. filter/
  search empty language, or a bounded/partial-apply sync,
  or a recreation-safe branch), not a generic restyle.
- **Phase 4 (already known, unchanged):** status-aware
  bundles/radar sync (nightly guard mirrored),
  per-section success clocks, global clock over REQUIRED
  sections only, edge contract test. P1's bundles/radar
  `http_404` trace lines are the device-side proof that
  Phase 4's fix landed, when it lands.
- **P6 build gate (blocking for any device claim):**
  compile the instrumented tree, verify the APK's
  `BuildConfig.GIT_COMMIT` equals the P1 commit, and
  verify install-over behaviour before any phone run.
