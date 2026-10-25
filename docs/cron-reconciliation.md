# ECO JOURNAL — Cron Reconciliation (Big Update, Phase 1)

**Status: LAB ONLY, READ-ONLY. No execution. No live change.**
No cron was enabled, disabled, added, updated, removed, or run in
producing this document. No bot, channel, production code, or freeze
state was touched. No GitHub push was made. No Kotlin file,
`edge/generate_edge.py`, or other document was modified — this file
is the only output of this pass.

**Reconciliation date:** 2026-10-25 (Tehran), Phase 1 of
`big-update-plan.md` (§5, Phase 1 scope: "cron-definition
reconciliation (46 definitions vs 24 active — produce the reconciled
list; no orphan cron is re-enabled by accident)").

---

## 1. Method, evidence sources, honesty box

### 1.1 Method

1. Enumerated every definition file under
   `~/workspace/goals/goal-2/crons/` (the goal-2 cron directory is
   the single directory that holds ECO JOURNAL cron definitions;
   `~/workspace/goals/eco-journal/crons/` is empty).
2. Read each file's frontmatter (`id`, `title`, `enabled`,
   schedule kind/time/interval) and its body purpose.
3. Cross-checked the file set against a live `cron.list`
   (read-only listing, `include_disabled=true`) taken during this
   pass, and against the dated freeze record.
4. Reconstructed "active at freeze" from the freeze record's own
   explicit name list — not by guessing from current flags (every
   recurring definition file now says `enabled: false`, which only
   proves everything is off *now*, not what was on *at the freeze*).

### 1.2 Evidence sources

| Source | What it established |
|---|---|
| `~/workspace/goals/goal-2/crons/` — 59 definition files (daily 29, hourly 11, minutely 4, weekly 2, runonce 13) | The full definition inventory, ids, titles, documented schedules. |
| Live `cron.list` (read-only, 2026-10-25, this pass) | Every goal-2 ECO JOURNAL schedule currently `enabled: false`, including both special-issues crons and both future runonce jobs. System schedules (feed-pulse, heartbeat, profile-image) and academy/class schedules in the same listing were excluded as non-ECO JOURNAL. |
| `~/memory/2026-10-24.md`, freeze entry (line ~651) | The exact 24 jobs disabled in the freeze action, named one by one (see §4). This is the authority for the ACTIVE-at-freeze verdict. |
| `~/workspace/goals/goal-2/agent_notes/2026-10-24/goal-2.md` §1 | Freeze confirmation: 2026-10-24 11:02 Tehran, all 24 active ECO JOURNAL crons disabled, Monday 2026-10-26 04:10 restore job disabled in the same action; consequences (no channel posts, no 23:00 report, no 2026-10-27 daily letter, no 2026-10-28 v0 training). |
| `~/workspace/goals/goal-2/agent_notes/2026-10-22/goal-2.md` | War-room-only order (2026-10-21T22:41Z): 18 crons disabled — freewire ×10, newsdesk ×4, nightdesk, marginalia, article-daily, weekly-poll. |
| `~/memory/2026-10-24.md` pipeline-health note (line ~92) | `eco-journal-daily-refresh` explicitly "غیرفعال — عمدی، به‌خاطر تعطیل‌بودن وبلاگ" (disabled intentionally because the blog is shut); `economic-30min` recorded as a deleted cron (not in the directory — see §3.6). |
| `~/workspace/goals/goal-2/hidden_files/site-watchdog.log` (last run 2026-10-19 09:18) and `~/workspace/goals/goal-2/hidden_files/ml-sessions/briefs/` (only `brief-2026-10-19-morning.md`) | Corroboration that site-watchdog and the ML session briefs had already stopped before the freeze. |
| `~/workspace/goals/goal-2/crons/runonce/eco-lowburn-restore__runonce@2026-10-26T04:10:00.md` (body) | Low-burn interval values and the four interval restorations it was to perform (see §3.5 and §6). |
| `~/memory/2026-10-25.md` special-issues flushes (~01:39–01:58 Tehran) | Special-issues crons created and enabled 2026-10-25 ~01:47, then disabled and verified (`enabled=false`, `next_run=null`) at Yasin's order ~01:56 the same night; 4 posts queued, 0 sent. |
| `~/workspace/goals/eco-journal/app/docs/big-update-plan.md`, `freeze-lift-order-draft.md` | Phase 1 scope; the plan's "46 vs 24" framing (corrected in §2); the lift-order rule that the Monday 04:10 job stays disabled unless explicitly re-enabled. |
| `~/workspace/econ-news-blog/editorial/sources_registry.json`, `~/workspace/goals/goal-2/hidden_files/wire-fast-poll.log` | Feed-count evidence for §7 (registry `count: 73`, generated 2026-10-22; wire log repeatedly `23/23 feeds, 0 failed`, last pre-freeze run 2026-10-24 11:00 Tehran). |

### 1.3 Honesty box — what was verified live vs from documents

- **Verified from a live listing (this pass, read-only):** that
  every goal-2 ECO JOURNAL schedule in `cron.list` is currently
  `enabled: false` — all 46 recurring definitions and the 5
  runonce definitions the scheduler still lists. No schedule was
  found enabled. This is a *current-state* check only.
- **Verified from documents, NOT from a live listing:** which
  jobs were active *at the freeze* (2026-10-24 11:02 Tehran).
  The live listing cannot show the past; that verdict rests on
  the freeze record's explicit 24-name list in
  `~/memory/2026-10-24.md` plus the 2026-10-22 war-room-only
  record. Where those two records plus the stopped-log evidence
  agree, the verdict is firm; where they do not pin a date (the
  exact day the 22 pre-freeze-off jobs were switched off), this
  document says so rather than inventing a date.
- **Known stale-snapshot caveat (AGENTS.md, 2026-10-22):**
  `cron.status` can return stale snapshots, so this pass used
  `cron.list` *corroborated by* the definition files and the
  dated freeze log — never a single status call. The one
  discrepancy found this way is documented in §3.6: 8 past
  runonce definition files still say `enabled: true` in their
  file frontmatter, but the live scheduler no longer lists them
  at all (they fired long ago and were purged from the live
  list). The files are historical copies, not live schedules.
- **NO cron state was changed by this pass.** No `cron.add`,
  `cron.update`, `cron.remove`, or `cron.run` was issued. The
  only cron tool used was the read-only `cron.list`.

---

## 2. Count reconciliation: why "46 vs 24" needs a correction

The plan's headline ("46 recurring definitions vs 24 active") is
almost right, and the correction matters for lift safety:

- **46** = recurring definitions in the directory *today*
  (daily 29 + hourly 11 + minutely 4 + weekly 2).
- **24** = jobs disabled in the freeze action on 2026-10-24 —
  but that 24 is **22 recurring + 2 future runonce jobs**
  (`eco-lowburn-restore`, `eco-ml-training-v0`), per the freeze
  log's own name list (§4).
- **2 of the 46 recurring definitions did not exist at the
  freeze at all**: the special-issues pair
  (`eco-special-issues-morning/evening`) was created 2026-10-25,
  the day *after* the freeze, enabled briefly, then frozen
  separately the same night. At freeze time the recurring
  inventory was therefore **44**, not 46.
- Arithmetic that closes exactly: 44 recurring at freeze =
  **22 ACTIVE-at-freeze + 22 already-off-before-freeze**
  (18 war-room-only desk crons + ML briefs ×2 + blog refresh +
  site watchdog). Plus 2 future runonce = **24 freeze actions**.
  Plus 2 post-freeze special-issues definitions = **46 recurring
  today**. Plus 11 past runonce definitions = **59 definition
  files total**.

Anyone who lifts by "re-enable the 24" without this correction
will either miss 2 jobs or, worse, re-enable from the wrong set
(e.g. treating the 46 as "24 active + 22 to check" and sweeping
the special-issues pair or a desk cron into the active set).

### Verdict counts (59 definition files)

| Verdict | Count | Meaning |
|---|---|---|
| ACTIVE-at-freeze | 24 | In the freeze log's 24-name list: 22 recurring + 2 future runonce. These are the ONLY jobs a general lift order may consider resuming, and only in the order Yasin names. |
| DISABLED-at-freeze | 22 | Recurring definitions already off before the freeze (never in the 24). Re-enabling any of these is a *new* decision, not a lift. |
| SEPARATE-FREEZE | 2 | Special-issues pair: created after the freeze, frozen under its own separate decision 2026-10-25. Never part of the 24; lift requires its own explicit order. |
| STALE | 11 | Past runonce definitions whose moment has passed. Never re-enable; several are superseded or dangerous if re-run (see §3.5). |
| DUPLICATE (same job under two names) | 0 | No two cron ids are the same job. Overlapping *effects* between distinct jobs are real and are listed as hazards in §3, not as duplicates. |
| UNKNOWN (freeze status) | 0 | Every one of the 59 files has a firm freeze-status verdict from the records above. Open *questions* that are still UNKNOWN (not cron verdicts) are listed in §6. |

---

## 3. Reconciled table — every definition found

Schedules are as documented in the definition files (Asia/Tehran
unless noted). "Low-burn" values are the intervals in force at the
freeze under the 2026-10-23 low-consumption regime; "normal" values
are those the (frozen) lowburn-restore job was to restore.
Evidence key: **F24** = freeze log 24-name list
(`~/memory/2026-10-24.md`); **W22** = 2026-10-22 war-room-only
record; **LIVE** = live `cron.list` this pass (all false);
**FILE** = definition file frontmatter/body; **SPEC** =
special-issues freeze record 2026-10-25; **LOG** = stopped-log
evidence (site-watchdog log, ML briefs directory).

### 3.1 Recurring — ACTIVE-at-freeze (22)

| # | id | Purpose | Schedule (documented) | Evidence |
|---|---|---|---|---|
| A1 | `eco-redalert-watch` | War-room watch: stage/publish/flush cycles | every 30m (low-burn; normal 2m per restore job) | F24, LIVE |
| A2 | `eco-wire-fast-poll` | Fast poll of wire feeds into data core | every 10m (low-burn; normal 5m) | F24, LIVE |
| A3 | `eco-redalert-catchup` | Governor catchup pass (held/queued items) | every 1h (low-burn; normal 30m) | F24, LIVE |
| A4 | `eco-urgent-watch` | Urgent line guard: instant 🔴 release, pin, night-held bundle | every 5m (untouched by low-burn regime) | F24, LIVE |
| A5 | `eco-redalert-sitrep` | SITREP briefing, every 2h, silence if nothing meaningful | every 2h | F24, LIVE |
| A6 | `eco-market-pulse` | Periodic war-market pulse post (08:00–23:00, fresh snapshot only) | every 2h | F24, LIVE |
| A7 | `eco-editorial-pass` | "خبر مهم" editorial pass: translation + delegated approve/reject | every 2h | F24, LIVE |
| A8 | `eco-editorial-supervision` | Daily editorial supervision (writing quality, standing watch) | daily 09:50 | F24, LIVE |
| A9 | `eco-catalyst-teacher` | Catalyst teacher cycle: labels for uncertain war-room items | every 6h | F24, LIVE |
| A10 | `eco-assemble-editorial` | Assemble editorial signals (decided keypoints + marginalia → signals) | every 12h | F24, LIVE |
| A11 | `eco-ml-snapshot-hourly` | ML price snapshots (hourly in market hours per plan; cron interval 1h) | every 1h | F24, LIVE |
| A12 | `eco-ml-nightly-quality` | Nightly ML quality: close loops, story canon, full text | daily 23:45 | F24, LIVE |
| A13 | `ml-daily-pipeline` | ML daily pipeline: read day's news, features, price snapshot | daily 23:30 | F24, LIVE |
| A14 | `eco-war-forecast-daily` | War forecasters: tension index + 24h gold / tether-spread / coin-bubble direction (internal only) | daily 22:30 | F24, LIVE |
| A15 | `eco-forecast-signals` | Deterministic daily forecast signals (`forecast_signals.json`) | daily 06:50 | F24, LIVE |
| A16 | `eco-pipeline-health` | Pipeline health monitor (6h; note: its own health-criterion misread of wire logs is a known monitor bug, not a failure) | every 6h | F24, LIVE |
| A17 | `eco-channel-watch` | Channel silence monitor (>150 min silence in active hours = ALERT) + floor watch | every 1h | F24, LIVE |
| A18 | `eco-channel-daily-report` | 23:00 nightly channel report (private message to Yasin) | daily 23:00 | F24, LIVE |
| A19 | `eco-dawn-review` | Dawn review (04:00): overnight health + issues file | daily 04:00 | F24, LIVE |
| A20 | `eco-daily-letter` | Daily ML letter PDF (Yasin only; start guard blocked anything before 2026-10-27) | daily 08:30 | F24, LIVE |
| A21 | `eco-channel-ml-impact-weekly` | Weekly channel/bot impact report on ML | weekly Sat 09:00 | F24, LIVE |
| A22 | `yasineco-bot-watchdog` | Bot watchdog: restart @YASINECOBOT if down | every 30m (low-burn; normal 15m) | F24, LIVE |

### 3.2 Runonce — ACTIVE-at-freeze (2, both future-dated at freeze)

| # | id | Purpose | Schedule (documented) | Evidence |
|---|---|---|---|---|
| A23 | `eco-lowburn-restore` | **Restore war-room intervals after the 2026-10-26 03:57 weekly reset** (30m→2m, 10m→5m, 1h→30m, 30m→15m). **STAYS DISABLED unless Yasin explicitly re-enables it** — see §5. | runonce 2026-10-26 04:10 Tehran | F24, LIVE (false) |
| A24 | `eco-ml-training-v0` | ML training v0 on 500 labels (honest baseline comparison; training run, not deployment) | runonce 2026-10-28 03:21 Tehran | F24, LIVE (false) |

A23 + A24 together with A1–A22 = the 24 of §4.

### 3.3 Recurring — DISABLED-at-freeze (22, already off before the freeze)

| # | id | Purpose | Schedule (documented) | Why off before freeze (evidence) |
|---|---|---|---|---|
| D1–D10 | `eco-freewire-01` … `eco-freewire-10` | Freewire (سیستم آزاد) single semi-important items, 10 slots/day | daily 08:30, 10:00, 11:30, 13:30, 15:00, 16:30, 18:30, 20:00, 21:30, 22:30 | W22 war-room-only order |
| D11–D14 | `eco-newsdesk-morning`, `eco-newsdesk-midday`, `eco-newsdesk-evening`, `eco-newsdesk-night` | News desk slot posts | daily 08:00, 13:00, 18:00, 21:00 | W22 |
| D15 | `eco-nightdesk` | Night desk "جامانده‌های دیشب" (1–3 messages, 00:05) | daily 00:05 | W22 |
| D16 | `eco-marginalia-daily` | Daily marginalia factory (notes + hourly economic digests input) | daily 06:50 | W22 |
| D17 | `eco-article-daily` | Daily editorial articles (deep + short → queue + `editorial/articles/`) | daily 07:30 | W22; also big-update-plan S35 "pre-freeze OFF, not in the frozen 24" |
| D18 | `eco-weekly-poll` | Weekly poll (Fri, 4 top stories) | weekly Fri 18:00 | W22; big-update-plan S36 NOT-IN-APP (voting backend does not exist) |
| D19 | `eco-ml-morning-brief` | ML morning-session brief (file only, silent; sessions 2026-10-19/20 era) | daily 09:30 | Not in F24; briefs directory stops at 2026-10-19 morning — sessions ended before freeze |
| D20 | `eco-ml-evening-brief` | ML evening-session brief (file only, silent) | daily 17:30 | Not in F24; same session-era evidence as D19 |
| D21 | `eco-journal-daily-refresh` | Periodic blog (space-4 artifact) content refresh | every 6h | Not in F24; memory 2026-10-24: disabled intentionally because the blog is shut; BLOG_LIVE=False, public link revoked |
| D22 | `eco-site-watchdog` | Permanent lightweight site watchdog | every 6h | Not in F24; last log run 2026-10-19 09:18; supersedes the expired 48h site-check (see §3.5) |

Note on D19/D20/D21/D22: the records prove they were off at
freeze (absent from the F24 list) and corroborate they had
already stopped earlier; the exact calendar day each was
switched off is not recorded in the sources searched. That gap
does not change the verdict — for lift purposes all four are
"off before freeze, needs a new decision", never "part of the
24".

### 3.4 Recurring — SEPARATE-FREEZE (2, created after the freeze)

| # | id | Purpose | Schedule (documented) | Evidence |
|---|---|---|---|---|
| S1 | `eco-special-issues-morning` | Special issues: publish ≤1 queued post (midday slot of the 2/day cap) | daily 12:50 (`@user.current` tz) | SPEC; LIVE (false) |
| S2 | `eco-special-issues-evening` | Special issues: publish ≤1 queued post (evening slot of the 2/day cap) | daily 20:50 (`@user.current` tz) | SPEC; LIVE (false) |

Created and enabled 2026-10-25 ~01:47, disabled and verified
2026-10-25 ~01:56 at Yasin's order, before any send (4 posts
queued in `special_issues.db`, 0 sent). Preconditions before
any reopen (from big-update-plan S32, carried here unchanged):
re-verify the 2 partially-verified queued posts (CAP refunds;
Brookings Black Americans) from the source page itself; resolve
the queue-location note (registry says 4 queued, `queue/`
empty, drafts in `posts/` + PDFs in `pdfs/`); recover the
missing full texts (`articles/` empty despite the README).
ML firewall is absolute: this service never enters ML.

### 3.5 Runonce — STALE (11, moment passed; never re-enable as-is)

| # | id | Schedule (documented) | Status note |
|---|---|---|---|
| R1 | `eco-1000-visitors-plan` | runonce 2026-10-20 03:24 | Past; still listed live as disabled. Historical only. |
| R2 | `eco-ml-5day-review` | runonce 2026-10-21 10:00 | Past; still listed live as disabled. Historical only. |
| R3 | `eco-ml-training-first` | runonce 2026-10-22 03:21 | Past; **superseded by** `eco-ml-training-v0` (A24). File frontmatter still says `enabled: true` but the live scheduler no longer lists it — stale copy (see §3.6). |
| R4 | `eco-ml-warroom-expansion-review` | runonce 2026-10-22 12:00 | Past; file says `enabled: true`, live list omits it — stale copy. |
| R5 | `eco-morning-report-2026-10-17` | runonce 2026-10-17 10:30 | Past; file says `enabled: true`, live list omits it — stale copy. |
| R6 | `eco-phase23-24h-check` | runonce 2026-10-20 22:30 | Past; file says `enabled: true`, live list omits it — stale copy. |
| R7 | `eco-publish-after-pilot` | runonce 2026-10-18 19:35 | Past pilot-phase job; still listed live as disabled. Superseded by later pilot/vNext phases. |
| R8 | `eco-redalert-launch` | runonce 2026-10-22 03:15 | Past launch moment; file says `enabled: true`, live list omits it — stale copy. Re-running a launch job would re-announce, not resume. |
| R9 | `eco-resume-after-24h-shutdown` | runonce 2026-10-22 02:00 | Past; file says `enabled: true`, live list omits it — stale copy. **Dangerous if resurrected**: it encodes the superseded 2026-10-21 24h-shutdown return, which the 2026-10-24 full freeze explicitly superseded. Never re-enable. |
| R10 | `eco-site-check-48h-stop` | runonce 2026-10-19 00:50 | Past; file says `enabled: true`, live list omits it — stale copy. **Superseded by** `eco-site-watchdog` (D22), which is itself off. |
| R11 | `yasin-deep-think-tokens-ml` | runonce 2026-10-19 18:24 | Past; file says `enabled: true`, live list omits it — stale copy. |

### 3.6 File-vs-live discrepancy (documented, not resolved by editing)

- 8 runonce files (R3, R4, R5, R6, R8, R9, R10, R11) carry
  `enabled: true` in their file frontmatter but do not appear in
  the live `cron.list` at all. Interpretation: they fired (or
  were purged) and the files are historical copies; the
  namespace note confirms definition files are read-only copies
  and editing them does not change saved definitions. The live
  list governs for "does this schedule exist now"; the files
  govern for "what did this job say". Neither source supports
  re-enabling any of them.
- The 5 runonce jobs the live list *does* still show
  (R1, R2, R7, A23, A24) are all `enabled: false`.
- No recurring definition file disagrees with the live list:
  all 46 recurring files say `enabled: false` and the live list
  agrees for all 46.

---

## 4. The 24-active-at-freeze list, named (authority: freeze log)

Disabled in the freeze action, 2026-10-24 11:02 Tehran, in the
freeze log's own order (`~/memory/2026-10-24.md`):

1. `eco-redalert-watch`
2. `eco-wire-fast-poll`
3. `eco-redalert-catchup`
4. `eco-urgent-watch`
5. `eco-redalert-sitrep`
6. `eco-market-pulse`
7. `eco-editorial-pass`
8. `eco-editorial-supervision`
9. `eco-catalyst-teacher`
10. `eco-assemble-editorial`
11. `eco-ml-snapshot-hourly`
12. `eco-ml-nightly-quality`
13. `ml-daily-pipeline`
14. `eco-war-forecast-daily`
15. `eco-forecast-signals`
16. `eco-pipeline-health`
17. `eco-channel-watch`
18. `eco-channel-daily-report`
19. `eco-dawn-review`
20. `eco-daily-letter`
21. `eco-channel-ml-impact-weekly`
22. `eco-ml-training-v0` (runonce, future at freeze)
23. `yasineco-bot-watchdog`
24. `eco-lowburn-restore` (runonce, future at freeze)

No gaps: this list is complete as documented, and it is the
only list a general lift order may draw from. It does NOT
include the desk crons (D1–D18), the ML briefs (D19–D20), blog
refresh (D21), site watchdog (D22), or the special-issues pair
(S1–S2).

---

## 5. Duplicate / stale / overlap analysis — what can go wrong at lift

**No true duplicates found.** No cron id is the same job under
a second name, and no two ids share a definition file. The
dangers below are overlaps between *distinct* jobs, plus stale
jobs that look live. Each is a lift-time hazard, not a verdict
change.

### 5.1 Flush / catchup overlap — the proven double-post pattern

- `eco-redalert-watch` (stage/publish/flush) and
  `eco-redalert-catchup` (governor catchup pass) both move queued
  items toward the channel. On 2026-10-23 08:00–08:09, two
  overlapping `--flush` runs double-posted 17 stories; the fix
  was a non-blocking `flock` in `flush()` (AGENTS.md lesson:
  any select-then-delete+send path must hold a lock, and
  overlapping cron cycles are normal on this box).
- **Lift rule:** before re-enabling A1 and A3 together, verify
  the flock guard covers *both* paths (watch-flush and
  catchup), not just the path that was fixed. Re-enabling both
  at once without that check re-creates the exact incident
  shape. Never re-enable a flush-capable job alongside another
  flush-capable job on the assumption that "the lock was
  fixed" — the fix must be re-verified for each path.

### 5.2 Urgent release vs war-room publish

- `eco-urgent-watch` (5m) releases held urgent items and manages
  pins; `eco-redalert-watch` publishes fresh urgent items. Both
  apply the night law (day ≥70 instant; night 00–08 only ≥85
  instant, 70–84 held to the 08:00 bundle). If their cadences or
  law thresholds drift apart at lift (e.g. watch restored to
  2m but urgent-watch left at a low-burn value, or vice versa),
  an item can be published by one path and re-released by the
  other, or a night-held item can leak as an instant notification
  in the app. Restore intervals as a matched set (see §6).

### 5.3 Volume accounting: desk crons vs war-room caps

- The D-group (freewire ×10, newsdesk ×4, nightdesk,
  marginalia, article-daily) all feed the same channel volume
  the governor counts toward floor 250 / target 300 / cap 400
  (non-flash). They were switched off precisely because they
  burned tokens while the war room carried volume. Re-enabling
  any desk cron without re-checking the governor means the
  volume math silently changes: the same day can overshoot the
  caps or double-count a story that the war room also publishes
  (story_key drift — transliteration variants like
  آتلانتیک/اتلانتیک already created duplicate queue rows on
  2026-10-23). Desk crons are not "orphans to sweep back in";
  each needs its own re-decision and a cap re-check.

### 5.4 Nightly cluster collision (23:00 / 23:30 / 23:45)

- `eco-channel-daily-report` (23:00), `ml-daily-pipeline`
  (23:30), `eco-ml-nightly-quality` (23:45) form a sequence:
  the report closes the day's counts, the pipeline stores the
  day's features, nightly quality closes loops and canon. The
  big-update nightly edition (§4.2) also keys off "after the
  23:00 report inputs close". Re-enabling them out of order, or
  adding a nightly-edition generator at a colliding time,
  produces reports that describe a day the pipeline has not
  finished, or editions built from unclosed loops. The plan
  correctly refuses to invent a nightly-edition cron time that
  collides with these three; lift must keep that refusal.

### 5.5 Watchdog pile-up (four health jobs, one live need)

- `eco-channel-watch` (silence/floor, 1h),
  `eco-pipeline-health` (6h), `yasineco-bot-watchdog` (bot
  process, 30m low-burn), and `eco-site-watchdog` (site, 6h,
  off) overlap in "is the platform healthy?" coverage, and
  `eco-pipeline-health` is already known to misread wire logs
  ("failed" substring in "0 failed"). At lift, re-enabling all
  health jobs at once multiplies owner alerts for one incident
  and risks the known misread being reported as a real failure.
  Site-watchdog (D22) must not return with the others: the site
  it watched is shut (BLOG_LIVE=False, link revoked) and its
  last healthy signal dates to 2026-10-19.

### 5.6 Bot internal jobs are NOT cron definitions — the hidden overlap

- The bot's in-process JobQueue (`feed_watcher` 30m,
  `alert_check` 1h, `flash_monitor` 1h, `keypoints_daily`
  07:00; big-update-plan S49) duplicates cron functions
  (wire polling, market alerts, flash monitoring, keypoints)
  whenever the bot process runs. These jobs exist only while
  the bot runs, so they have no cron verdict and no cron
  listing — but at lift, restarting the bot (via
  `yasineco-bot-watchdog`, A22) silently restarts them
  alongside their cron counterparts. **Lift rule:** the bot's
  internal jobs must be inventoried against A2/A6/A4/A7 before
  the watchdog is re-enabled, or the first bot restart
  double-runs ingest/alerts. This inventory is an open item
  (§6), not something this pass could verify from cron records
  alone.

### 5.7 Same-clock collisions inside the off set

- 06:50: `eco-forecast-signals` (A15, active) and
  `eco-marginalia-daily` (D16, off) share a clock time. If a
  future order revives marginalia without moving it, two
  content jobs fire simultaneously. Same pattern at 08:30
  (`eco-daily-letter` + `eco-freewire-01`) and 22:30
  (`eco-war-forecast-daily` + `eco-freewire-10`). Clock
  collisions are a scheduling smell to check at lift, not a
  reason to re-enable either side.

### 5.8 Superseded runonce jobs (see §3.5)

- `eco-ml-training-first` → superseded by `eco-ml-training-v0`.
- `eco-site-check-48h-stop` → superseded by `eco-site-watchdog`
  (itself off).
- `eco-resume-after-24h-shutdown` → superseded first by the
  low-burn regime, then by the full freeze. Its 2026-10-22
  02:00 moment is gone; re-enabling it would execute a return
  from a shutdown that no longer describes reality.
- `eco-redalert-launch` → a launch moment, not a resumable job.
- All 11 STALE runonce jobs: delete-or-archive is a *later*
  Yasin decision; this pass changes nothing, but no lift order
  should name any of them.

---

## 6. Lift safety rules (binding constraints carried into Phase 1)

1. **The Monday 2026-10-26 04:10 job stays DISABLED.**
   `eco-lowburn-restore` (A23) was disabled in the freeze
   action itself and stays disabled unless Yasin explicitly
   re-enables it by name. Its scheduled moment (04:10 on
   2026-10-26, after the 03:57 weekly reset) must not pass
   silently into a restore: if the freeze is still in force at
   that time, nothing restores; if Yasin lifts before then, the
   interval restorations (30m→2m, 10m→5m, 1h→30m, 30m→15m)
   are a separate explicit step, applied as a matched set
   with §5.2, not by re-enabling A23 alone.
2. **No orphan cron is re-enabled by accident.** "Orphan" =
   any job not in the §4 list of 24. The D-group (22), the
   special-issues pair (S1–S2), and all 11 STALE runonce jobs
   stay off under any general lift wording ("باز کن",
   "resume", "lift the freeze"). Each needs its own named
   order. A lift checklist that says "re-enable all ECO
   JOURNAL crons" is a defective checklist — it would revive
   desk volume, a shut blog's refresh, a dead site's watchdog,
   and a superseded shutdown-return job in one stroke.
3. **Any UNKNOWN-verdict item stays off until resolved.**
   No cron in this inventory carries an UNKNOWN freeze-status
   verdict, but the following are UNKNOWN and block their
   respective lifts until resolved (never assumed):
   - (a) The lowburn-restore body's "5 crons" vs its 4 listed
     interval changes: which job is the fifth? (Candidates:
     an unlisted interval change, or a miscount. Do not guess;
     check the 2026-10-23 low-burn change record before
     restoring intervals.)
   - (b) Bot JobQueue internal jobs vs cron counterparts (§5.6):
     inventory before re-enabling the bot watchdog.
   - (c) The wire-feed list behind the 23-count vs the
     73-registry (§7): diff by feed id before trusting either
     list as "the ingest set" at lift.
   - (d) The exact switch-off dates of D19–D22: irrelevant to
     the verdict (off at freeze, proven by exclusion from
     F24), but a lift order that claims "these were switched
     off on date X" would be inventing a date — do not claim it.
4. **Special-issues crons stay frozen under their separate
   decision.** S1 (12:50) and S2 (20:50) are not resumed by a
   general lift, by app readiness, or by the big-update plan.
   Only Yasin's explicit special-issues reopen order resumes
   them, after the §3.4 preconditions (re-verify the 2
   partially-verified posts; resolve the queue-location note;
   recover missing full texts) and with the ML firewall intact.
5. **Lift order within the 24 (proposed, Yasin names the
   real order):** app + edge sync only → bridge queue in
   dry-run review → channel publishing at 2/day → war room +
   reports → ML (per `freeze-lift-order-draft.md` §2.8, item 6).
   This document does not choose the order; it constrains it:
   no step may silently pull in a non-24 job, and the
   23:00/23:30/23:45 cluster (§5.4) resumes as a sequence.
6. **Re-verify, don't trust, at lift time.** Per the
   2026-10-22 AGENTS.md lesson, a single status call can lie:
   corroborate with `cron.list` + the definition files + this
   reconciliation before any channel-affecting re-enable, and
   verify via DB write paths (not a runner's "N sent" counter)
   after the first resumed run.

---

## 7. Feed-count reconciliation (73 vs 23) — RESOLVED, with one open diff

**Finding: the two numbers count two different sets. Neither
is wrong, and neither is "the channel total".**

| Number | What it counts | Evidence |
|---|---|---|
| **73** | The war-room source registry: `editorial/sources_registry.json`, field `count: 73`, `generated: 2026-10-22`, array length independently counted as 73 entries (fields: `id, name, url, lang, tier, origin, section`). This is the registry built by the 2026-10-22 supply-engine expansion ("E4 sources 54→73", agent_notes 2026-10-22). | `sources_registry.json` read directly; count field and array length agree. |
| **23** | The wire-fast-poll runtime set: the fast wire poller reports `23/23 feeds, 0 failed` on run after run (e.g. last pre-freeze run 2026-10-24 11:00 Tehran: `new_items=38, read=1342, feeds 23/23, failed=0, status=ok` — two minutes before the 11:02 freeze). This is the set the 10-minute (low-burn) wire poll actually polls. | `hidden_files/wire-fast-poll.log` (repeated `23/23` lines, Oct 19–24) + `~/memory/2026-10-24.md` ("23/23 feeds, 1336 read"). |

**Resolution:** big-update-plan S10 is correct that the
"23-feed wire subset is a different set" from the 73 — the
plan's wording ("73 war-room feeds + general ingest list")
should be read as "73 registry feeds, of which the wire poller
runs a 23-feed fast subset", not as "73 + 23 = 96 feeds".
There is no 96-feed total and no missing 50 feeds: the 73 is
the registry universe, the 23 is one poller's working set.

**Do not confuse with two further sets** (counted in this
pass so they are not mistaken for either number):
- `fetcher/feeds.json`: 42 feeds, updated 2026-10-19 — the
  general fetcher list, a third set.
- `editorial/redalert_feeds.json`: 18 feeds, verified
  2026-10-22 — the red-alert desk list, a fourth set.

**One explicitly open diff (UNRESOLVED sub-item, not a
guessed answer):** whether the 23 wire feeds are a *subset by
id* of the 73 registry feeds was NOT verified in this pass —
the wire poller's feed list lives in runtime code/DB state,
not in a counted JSON file this pass could diff, and this
pass does not touch production code paths to extract it.
Phase 1's data task must diff the wire poller's 23 ids
against the registry's 73 ids before either list is treated
as "the ingest set" in the app pipeline. Until that diff is
done, the app must carry both numbers with their labels
("registry: 73" / "wire poll: 23"), never a merged total.

**Separately, not to be confused with 73 vs 23:** the plan's
other count gap, `channel_posts` (23 rows) vs
`content_queue.queue` (508 rows, 495 sent), is a *different*
23 that happens to coincide numerically with the wire-feed 23.
The channel_posts 23 is a row count in a refs table
(big-update-plan §1, S26; `telegram-bridge-spec.md`:
"channel references: `channel_posts` rows (23) — message_id
only"), while the wire 23 is a feed count. Same numeral,
unrelated quantities — do not merge them.

---

## 8. What this pass could not verify (plainly stated)

1. **Live pre-freeze state beyond the freeze log.** No live
   listing can show 2026-10-24 11:02 state; the ACTIVE verdict
   rests on the dated freeze log's name list. If that log
   itself were wrong, this document would inherit the error —
   it is corroborated (not proven) by the 2026-10-22
   war-room-only record, the stopped-log evidence, and the
   current all-disabled live listing.
2. **Exact switch-off dates for D19–D22** (see §3.3 note).
3. **The lowburn-restore fifth cron** (§6.3a).
4. **Bot JobQueue internal jobs** (§5.6, §6.3b) — no cron
   record covers them; they need a bot-code inventory at lift.
5. **Wire-23 vs registry-73 id-level subset relationship**
   (§7 open diff).
6. **Whether any ECO JOURNAL cron definition exists outside
   `goals/goal-2/crons/`** — searched: `goals/eco-journal/crons/`
   is empty, and the live listing shows no other goal-2-owned
   ECO JOURNAL schedule. A definition created outside the goal
   workspace would not be found by this search; the live
   listing is the backstop, and it shows none enabled.
7. **Run-history truth for individual jobs** (last run, last
   status) was not pulled: `cron.runs` was not needed for a
   definition reconciliation, and run records do not change any
   verdict above. If lift needs last-run evidence for a
   specific job, pull it for that job at lift time.

---

*End of reconciliation. Nothing in this document enables,
disables, or runs anything. The freeze stays in force until
Yasin's explicit reopen order, naming its scope.*
