# Freeze-lift order — DRAFT ONLY, NOT EXECUTED (Phase 8 rehearsal, v4)

**Status: draft.** This draft enables, starts, posts, publishes,
and unfreezes NOTHING. The lift happens only when Yasin writes his
scope sentence. App readiness alone does **not** lift anything.
This draft supersedes the M9-era draft (`docs/freeze-lift-order-draft.md`)
for the big update / v4; where the two disagree, the newer evidence
(R1–R11 artifacts in `docs/big-update/evidence/phase8/`) governs.

## 1. Build under rehearsal

- Lab commit: `aaf5b87` (Phase 7: polish, accessibility, full pilot
  checklist — lab only).
- Cloud build id: NONE (Phase 7 was never cloud-built).
- APK file: NONE (no v4 build exists yet).
- versionCode / versionName as-shipped: NOT APPLICABLE (no build).
  The v4 plan targets versionName `4.0.0` / versionCode `4` when a
  build is made; that target is not a shipped artifact.

## 2. Rehearsal date + evidence index

- Rehearsal date: 2026-10-26 (Asia/Tehran, early hours).
- Evidence directory: `docs/big-update/evidence/phase8/`.
- R1–R11 table (one line per criterion, with artifact file name):

| # | Criterion | Verdict | Artifact |
|---|---|---|---|
| R1 | Update Engine E1–E8 on Yasin's phone | BLOCKED (Yasin, phone) + OPEN (v4 build) | `r1-egates.md` |
| R2 | Near-real-time timed end-to-end test | BLOCKED (Yasin, test channel) | `r2-nrt-timing.md` |
| R3 | Nightly edition dry-run | OPEN (generator pass, render proof missing) | `r3-nightly.md` |
| R4 | Full pilot checklist on Yasin's phone (incl. TalkBack) | OPEN (Yasin, phone) | Phase 7 evidence set incomplete — see `rehearsal-report.md` |
| R5 | Cron reconciliation complete | PASS (zero-re-enable rehearsal) | `r5-cron-list.txt` |
| R6 | ML gate honestly reported | OPEN (phone screenshot missing) | `r6-ml-gate.md` |
| R7 | Test channel + allow-list verified | BLOCKED (Yasin, channel creation) | `r7-testchannel.md` |
| R8 | Pages + edge serving (manifest v3) | FAIL (manifest v3 returns HTTP 404) | `r8-pages.md` |
| R9 | Invite + revocation gate test | OPEN (Yasin, phone) | `r9-gate.md` |
| R10 | Diagnostics + privacy verified | OPEN (phone diagnostics file missing) | `r10-privacy.md` |
| R11 | Lift scope named by Yasin | ARTIFACT MET (scope blank, see §4) — lift NOT performed | `r11-stays-frozen.md` + this draft |

Counts: PASS 1 (R5, as a zero-re-enable rehearsal) + ARTIFACT MET 1
(R11, scope blank) / FAIL 1 (R8) / OPEN 5 (R3, R4, R6, R9, R10) /
BLOCKED 3 (R1, R2, R7). Zero blockers is required before any lift;
this draft has nine non-pass lines. It must not be read as lift-ready.

## 3. Blockers (every OPEN / BLOCKED / FAIL line, with owner)

| Blocker | Owner | Unblocked by |
|---|---|---|
| R1: no per-item E1–E8 phone evidence; no v4 build | Yasin (phone) + lab (build) | Cloud build of the v4 line + APK; Yasin's filed E1–E8 set |
| R2: no 4-timestamp test-channel timing | Yasin (test channel) + lab (rehearsal-edge isolation) | Test channel + edge-URL override (or explicit single-run order) |
| R3: no render screenshots / search-hit screenshot of a dry-run edition | Yasin (phone render) | Phone render of a dry-run edition, all 7 components |
| R4: Phase 7 phone evidence set incomplete (TalkBack walk log, font sets, contrast measurements, state matrix, refresh pairs, diagnostics honesty) | Yasin (phone) | Full Phase 7 phone pass, filed under `evidence/phase7/` |
| R6: no Market-tab no-probability screenshot; no post-lift gate re-reading | Yasin (phone) + lift naming ML | Phone screenshot; pipeline re-run only after a lift naming ML |
| R7: no test channel | Yasin (channel creation) | Create private channel + add @YASINECOBOT + send `-100…` id |
| R8: live Pages edge serves no manifest v3 (HTTP 404) | Lab (needs separate authorisation) | Authorised `publish_edge.py --real` + re-fetch showing v3 |
| R9: no 4-case gate test log with screenshot refs | Yasin (phone) | Phone gate run (wrong / valid / revoked / offline) |
| R10: no filed phone diagnostics output `phase7/e6-diagnostics.txt` | Yasin (phone) | Phone diagnostics copy filed from the v4 build |

## 4. Scope (BLANK until Yasin speaks)

```
Lift the freeze for: ______________________ (to be filled by Yasin, naming each item: which crons, war room, ML, 23:00 report, daily letter, blog, app sync)
```

Status of this field: **AWAITING YASIN'S SENTENCE — NOT A LIFT.**
A scope of "everything" or "the freeze" would FAIL R11 outright and
must never be written here. An unnamed scope is not a lift.

## 5. Cron section

**No crons re-enabled by this draft.** (Scope is blank, so no cron
is named for re-enable. When a real scope is named, each cron to be
re-enabled must be listed here BY ID — a group name such as "the
frozen crons" fails R5.)

## 6. Stays-frozen section (copied verbatim from spec §5.5 — never edited down)

- [ ] **Special-issues auto-publish (S32):** separate decision,
      separate order. Its 4 queued posts stay queued; its 2
      partially-verified posts (CAP refunds, Brookings Black
      Americans) stay unpublished until re-verified from the source
      page itself. Preconditions still open at rehearsal time:
      resolve the queue-location discrepancy (registry says 4 queued,
      `queue/` empty, drafts in `posts/` + PDFs in `pdfs/`), recover
      the missing full texts (`articles/` empty despite the README),
      and re-verify the 2 partial posts. Evidence: frozen-state note
      `phase8/r11-stays-frozen.md` confirming each sub-item still
      open/frozen.
- [ ] **Private reports/letters as app content (S37/S38):** the
      23:00 report and daily letter stay Yasin-private; their
      probabilities stay off app surfaces until calibration passes.
- [ ] **Morning digest revival (inside S18):** disabled 2026-10-22,
      stays off unless re-decided. Evidence: the digest id appears
      in NO re-enable list in the draft.
- [ ] **Voices publication (S31):** until Yasin finalises the
      registry list. The blank slot stays blank.
- [ ] **Domain steps (nic.ir, Cloudflare, Pages token):** Yasin-only
      steps, not done by the agent, not a lift precondition for the
      app (the app does not depend on the external blog domain).
- [ ] **Release signing / Play distribution:** debug signing is
      shared and proven; release signing and any store distribution
      are separate decisions. Debug APKs stay direct-distribution
      (My Files install).
- [ ] **FCM activation:** separate decision, separate phase, never
      implied by the lift. Evidence: R10 FCM-state line.
- [ ] **Yasin-sent / owner private delivery rules:** no rule was
      found in the ML files for an archive-only/separate-stream
      treatment — if Yasin wants one, he states it as a new decision;
      the rehearsal does not invent it.

## 7. Monday 04:10 line (present in every draft revision)

**eco-lowburn-restore (Mon 2026-10-26 04:10) stays DISABLED unless
Yasin explicitly re-enables it by name.** (Fresh R5 listing:
enabled=false. Its runonce time falls later today; disabled means
it will not fire.)

## 8. Explicit non-actions

**This draft enables, starts, posts, publishes, and unfreezes
NOTHING. The lift happens only when Yasin writes his scope
sentence.** Post-rehearsal verification (see `evidence/phase8/
post-rehearsal-cron-list.txt`) confirms every ECO JOURNAL schedule
is still `enabled=false` after this rehearsal.
