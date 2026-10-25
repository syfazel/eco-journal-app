# R2 — Near-real-time timed end-to-end test (Phase 8 rehearsal, 2026-10-26)

## Verdict: BLOCKED (owner: Yasin — test channel)

No four-timestamp test exists for a test-channel item, because no
test channel exists (see r7-testchannel.md). The four required
timestamps cannot be produced:

1. Channel post time — NO SOURCE (no test channel to post to).
2. Edge publish time — NO SOURCE (no publish-edge run for a test item
   in this rehearsal; `publish_edge.py --real` was NOT run, and a
   rehearsal `--real` run would ship a TEST post to pilot readers
   because the app edge URL is hard-coded to the pilot Pages URL —
   see Phase 3 spec Tier B block).
3. Pages serve time — NO SOURCE (no test item deployed).
4. App sync time — NO SOURCE (no test item to sync).

## What is NOT R2 evidence

- Phase 3 Tier A lab timed test (commit `2c0a02a`, PASS in lab) is
  background, not R2 evidence: it used lab staging fixtures, not a
  channel post, and the rehearsal freshness rule (§5.2) forbids
  passing a line on an earlier build's artifact.
- "It arrived" notes without all four timestamps would FAIL R2
  by the spec's own rule, so none is claimed.

## Unblocked by

Yasin creating the private test channel + adding @YASINECOBOT as
admin + handing over its `-100…` id (his-hand step §A), AND
rehearsal-edge isolation (debug-only edge-URL override build, or
Yasin's explicit single-run order naming the pilot edge for that
TEST post). Until both resolve, Tier A remains the complete test
and R2 stays BLOCKED.
