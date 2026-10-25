# Phase 8 — Freeze-lift rehearsal report (DRY-RUN ONLY)

- Rehearsal date: 2026-10-26 (Asia/Tehran, early hours).
- Build under rehearsal: lab commit `aaf5b87` (Phase 7). Cloud
  build: NONE. APK: NONE. (Phase 7 was never cloud-built; the
  rehearsal is against the lab commit, stated plainly, not
  against a shipped build.)
- Run order followed (§5.6): build frozen → Phase 7 evidence
  re-confirmed (incomplete, see R4) → R8 + R9 → R2 + R3 → R5 + R6
  → R7 attempted (blocked) → R10 + R1 → draft assembled → temp
  dirs deleted → post-rehearsal cron check (identical listing).
- Nothing was enabled, started, posted, published, allow-listed,
  or unfrozen. No cron, bot, channel, or freeze state was changed.
  No push, no build, no APK distribution, no test-channel
  creation, no voices/special-issues publication, no FCM, no Play.

## R1–R11 table (artifact file name per line)

| # | Verdict | Artifact |
|---|---|---|
| R1 | BLOCKED (Yasin, phone) + OPEN (v4 build) | `r1-egates.md` |
| R2 | BLOCKED (Yasin, test channel) | `r2-nrt-timing.md` |
| R3 | OPEN (generator pass, render proof missing) | `r3-nightly.md` |
| R4 | OPEN (Yasin, phone) | Phase 7 evidence set incomplete (3 script files only) |
| R5 | PASS (zero-re-enable rehearsal) | `r5-cron-list.txt` |
| R6 | OPEN (phone screenshot missing) | `r6-ml-gate.md` |
| R7 | BLOCKED (Yasin, channel creation) | `r7-testchannel.md` |
| R8 | FAIL (manifest v3 HTTP 404 on live Pages) | `r8-pages.md` |
| R9 | OPEN (Yasin, phone) | `r9-gate.md` |
| R10 | OPEN (phone diagnostics file missing) | `r10-privacy.md` |
| R11 | ARTIFACT MET (scope blank) — lift NOT performed | `r11-stays-frozen.md` + `../lift-order-draft.md` |

Counts: PASS 1 / ARTIFACT MET 1 / FAIL 1 / OPEN 5 / BLOCKED 3.
Zero blockers is required before any lift; this rehearsal has
nine non-pass lines. It is NOT lift-ready, and the draft says so.

## The single most important finding

R8 FAIL: the live Pages edge does not serve manifest v3 at all
(HTTP 404). The Phase 1 engine, the per-section diagnostics, and
the whole near-real-time story all assume a serving manifest v3 —
but the canonical edge output was never regenerated and
`publish_edge.py --real` was never run, so the live edge is still
the M5 v2 edge. Until an authorised edge publish lands manifest
v3 on Pages (separately authorised, NOT part of this dry run),
no lift can honestly claim the app syncs against a v3 edge.

## The single most important gap (spec §6.3, guarded)

TalkBack: no walk log, no recording, no quoted announcements
exist anywhere in `evidence/phase7/` — only the walkthrough
script. Phase 7 therefore stays open on TalkBack, and because
Phase 8 depends on Phase 7 (R4), the lift rehearsal stays open
with it. No reviewer may convert "components look accessible"
into a TalkBack pass.

## Lift-order draft

`docs/big-update/lift-order-draft.md` — scope BLANK, marked
"AWAITING YASIN'S SENTENCE — NOT A LIFT"; cron section: "No
crons re-enabled by this draft"; stays-frozen section copied
verbatim from spec §5.5; Monday 04:10 line present. This draft
supersedes the M9-era draft for the big update / v4.

## Sign-off block (§6.2)

```
Rehearsal date: 2026-10-26  Build under rehearsal: lab commit aaf5b87 (no cloud build, no APK)
R1–R11:  PASS 1 / ARTIFACT MET 1 / FAIL 1 / OPEN 5 / BLOCKED 3 (every non-pass listed with owner in the draft §3)
Lift-order draft: docs/big-update/lift-order-draft.md  (scope: BLANK)
Post-rehearsal cron check: all ECO JOURNAL schedules still enabled=false — output filed as phase8/post-rehearsal-cron-list.txt
Rehearsal enabled / started / posted / unfroze NOTHING: confirmed by the rehearsal agent (this report + post-rehearsal listing)
```

## Stop point

Phase 8 ends here. v4-H components are NOT started in this run,
per the task. The freeze is unchanged.
