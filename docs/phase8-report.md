# Phase 8 report — Freeze-lift rehearsal, DRY-RUN ONLY (LAB ONLY)

- Base: Phase 7 commit `aaf5b87`. Spec:
  `docs/big-update/phases7-8-pilot-freezelift-spec.md` §5;
  v4 plan `docs/v4/v4-program-plan.md` (V4-G).
- Hard limits kept: dry-run only — no cron enabled/disabled/run,
  no bot started, no channel post, no edge content committed to
  live Pages, no allow-list change, no FCM, no Play, no push, no
  build, no APK distribution, no test-channel creation, no
  voices/special-issues publication, no freeze change.
- Build under rehearsal: lab commit `aaf5b87` only. NO cloud
  build exists for Phase 7 and NO v4 APK exists — stated plainly;
  the rehearsal is against the lab commit, not a shipped build.

## 1. What was walked (R1–R11, evidence rule: no artifact = no pass)

| # | Verdict | Why, in one line |
|---|---|---|
| R1 | BLOCKED + OPEN | No per-item E1–E8 phone files; E7 needs the test channel; no v4 build for E8 |
| R2 | BLOCKED | No test channel → no 4-timestamp test item; Tier A lab test is background, not evidence |
| R3 | OPEN | Nightly generator bundle fully verified in temp dir (7 components, v1→v2 correction, held items, search grep-hit); render + search screenshots missing |
| R4 | OPEN | Phase 7 evidence dir holds 3 scripts only — no TalkBack walk log, font sets, contrast measurements, state matrix, or diagnostics file |
| R5 | PASS | Fresh read-only cron.list: all 51 live goal-2 schedules enabled=false; 51 + 8 purged historical = 59 definition files; draft re-enables nothing |
| R6 | OPEN | Gate quoted + dated (6/7, failing only 60-day at 34 days, file 2026-10-23T23:13Z, pre-freeze); Market no-probability phone screenshot missing |
| R7 | BLOCKED | No test channel exists (bot cannot create one); allow-list file absent = no live channel authorised (partial positive, not a pass) |
| R8 | FAIL | Pages status `built` (gh api), BUT served `manifest.json` → HTTP 404: live edge is still the M5 v2 edge; Phase 1 v3 was never published |
| R9 | OPEN | Gate logic implemented in lab; no 4-case phone test log with screenshot refs |
| R10 | OPEN | Lab privacy audit filed (diagnostics = shown lines, no PII/codes/tokens; FCM dormant scaffolding only, no project, no google-services.json, no sender); phone diagnostics file missing |
| R11 | ARTIFACT MET | Draft scope BLANK, "AWAITING YASIN'S SENTENCE — NOT A LIFT"; lift NOT performed |

Counts: PASS 1 / ARTIFACT MET 1 / FAIL 1 / OPEN 5 / BLOCKED 3.
Nine non-pass lines. Zero blockers is required before any lift —
this rehearsal is NOT lift-ready, and the draft says so.

## 2. Evidence filed (all under `docs/big-update/evidence/phase8/`)

`r1-egates.md`, `r2-nrt-timing.md`, `r3-nightly.md`,
`r5-cron-list.txt`, `r6-ml-gate.md`, `r7-testchannel.md`,
`r8-pages.md`, `r9-gate.md`, `r10-privacy.md`,
`r11-stays-frozen.md`, `rehearsal-report.md`,
`post-rehearsal-cron-list.txt` (second listing, identical:
all goal-2 still enabled=false — rehearsal enabled NOTHING).

## 3. Outputs

- Lift-order draft: `docs/big-update/lift-order-draft.md` — scope
  BLANK, cron section "No crons re-enabled by this draft",
  stays-frozen section copied VERBATIM from spec §5.5, Monday
  04:10 line present ("eco-lowburn-restore stays DISABLED unless
  Yasin explicitly re-enables it by name"). Supersedes the
  M9-era draft for the big update / v4.
- Temp dirs from the R3 dry run (`/tmp/phase5-fixture-staging`,
  `/tmp/phase5-day1`, `/tmp/phase5-day2`) DELETED after the run;
  pre-existing unrelated `/tmp/phase5-*` entries left untouched.

## 4. The two findings that matter most

1. **R8 FAIL is load-bearing.** The v4 sync story assumes a
   serving manifest v3, but live Pages returns 404 for it. An
   authorised `publish_edge.py --real` (separately authorised,
   NOT part of this dry run) must land manifest v3 on Pages and
   pass a re-fetch before any lift can claim v3 sync.
2. **TalkBack is still the gap most likely to be falsely
   closed** (spec §6.3). No walk log exists; Phase 7 stays open
   on it, and Phase 8 stays open with it. No reviewer may convert
   "components look accessible" into a pass.

## 5. Blockers and their Yasin-hand unblockers

- Test channel (R2, R7, E7): Yasin creates a private channel,
  adds @YASINECOBOT as admin, sends the `-100…` id. Nothing else
  unblocks these three lines.
- Phone evidence (R1, R3-render, R4, R6-screenshot, R9, R10):
  Yasin's phone pass on a v4 build, filed under `evidence/phase7/`.
- R8: separately authorised edge publish (lab action, needs its
  own go-ahead — NOT granted by this rehearsal).
- Lift itself: Yasin's sentence naming the scope, each item by
  name. "Everything" fails R11 outright.

## 6. Stop point

Phase 8 ends here. v4-H components are NOT started in this run.
Freeze unchanged; no live change; no publication of voices or
special issues.
