# R9 — Invite + revocation gate test log (Phase 8 rehearsal, 2026-10-26)

## Verdict: OPEN (owner: Yasin, phone)

No dated gate-test lines with screenshot references exist. The
gate LOGIC is implemented in lab (hash-only invite check,
revocation re-lock only on a successful fetch, offline never locks
out — Phase 1 report; M7 gate), but the spec's R9 artifact is a
phone test log, and no phone ran it. Lab implementation is not a
gate test log.

## Required four cases — all missing their phone evidence

| Case | Required artifact | State |
|---|---|---|
| Wrong code rejected | Dated test line + screenshot ref | MISSING — no phone run |
| Valid code admitted | Dated test line + screenshot ref | MISSING — no phone run |
| Revoked code re-locked on next sync (+ the successful-fetch line that triggered it) | Dated test line + screenshot ref + fetch line | MISSING — no phone run, no revocation test |
| Offline admitted reader stays admitted | Dated test line + screenshot ref | MISSING — no phone run |

No pilot code appears in this log, by policy: test cases are
referenced by outcome + screenshot file name only — and since no
screenshots exist, no case is referenced as passed.

## Unblocked by

Yasin running the four gate cases on a phone against a v4 build
(wrong / valid / revoked / offline) and filing the dated log with
screenshot references. The revoked case additionally needs a
revocation entry to exist in the serving revocation list — which
depends on R8 (manifest v3 serving) being fixed first.
