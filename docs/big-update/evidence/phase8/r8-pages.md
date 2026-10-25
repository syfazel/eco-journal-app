# R8 — Pages + edge serving verification (Phase 8 rehearsal, 2026-10-26)

## Verdict: FAIL (not blocked, not close enough)

One sub-check passes, one fails outright, two are unverifiable —
and the spec says any mismatch = fail, not "close enough".

### (a) Pages deploy status — PASS (sub-check)

Command (read-only): `gh api repos/syfazel/eco-journal-app/pages`
Output (2026-10-26, this rehearsal):
`{"build_type": "workflow", "html_url": "https://syfazel.github.io/eco-journal-app/", "status": "built"}`
Pages status is `built`, build type `workflow`, at the edge URL
the app wires (`EdgeRepository.BASE_URL`).

### (b) Served manifest.json v3 — FAIL

Fetch (text fetch, this rehearsal):
`https://syfazel.github.io/eco-journal-app/manifest.json`
Result: HTTP 404 — "Page not found". The live Pages edge does NOT
serve manifest v3. Cause (known, not guessed): the Phase 1 edge
output was deliberately never regenerated — the canonical
`edge/out/` still holds the old export (shorts `general`, no
`manifest.json`), and `publish_edge.py --real` was never run.
The live edge is still the M5 v2 edge (`index.json` schema v2,
281 entries). Manifest v3 exists only in lab temp outputs and in
the Phase 5 fixture edge, never on the live edge.

### (c) Served section hashes vs committed edge — NOT VERIFIABLE

No served manifest exists to recompute against, so no
match/mismatch per section can be stated. Hash VALUES are
redacted in this rehearsal by policy (presence only) — but here
even presence fails: there is no manifest to check presence in.

### (d) Invite-list + revocation serving — UNVERIFIED

Cannot be verified through the manifest path (absent, see (b)),
and no separate endpoint is invented for this rehearsal. The
invite/revocation files exist in lab (`edge/out-phase5-fixture`
has `invite_hashes.json` and `revocations.json` as fixture
copies), but fixture copies are not serving evidence.

## What unblocks R8

An authorised `publish_edge.py --real` run that regenerates the
canonical edge output (manifest v3 + per-section hashes + invite
hashes + revocations) and pushes it to Pages, followed by a
re-fetch showing `version` = 3, per-section hash presence, and a
hash-recompute match per section. That publish is NOT part of
this dry-run rehearsal (spec §5.1 forbids committing edge content
to live Pages during rehearsal) and must be separately authorised.
Until then, R8 stays FAIL, and any lift that assumes a serving
manifest v3 is unfounded.
