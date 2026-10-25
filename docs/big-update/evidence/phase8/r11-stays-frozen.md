# R11 — Stays-frozen confirmation (Phase 8 rehearsal, 2026-10-26)

Each item below was CONFIRMED still frozen/queued in this rehearsal
(read-only checks; nothing was enabled, published, or re-verified
as "ready"). This file is the evidence behind the stays-frozen
section of the lift-order draft (copied verbatim from spec §5.5).

- [ ] **Special-issues auto-publish (S32):** separate decision,
      separate order. Its 4 queued posts stay queued; its 2
      partially-verified posts (CAP refunds, Brookings Black
      Americans) stay unpublished until re-verified from the source
      page itself. Preconditions still open at rehearsal time:
      resolve the queue-location discrepancy (registry says 4 queued,
      `queue/` empty, drafts in `posts/` + PDFs in `pdfs/`), recover
      the missing full texts (`articles/` empty despite the README),
      and re-verify the 2 partial posts. — CONFIRMED: no
      special-issues cron is enabled (R5 listing: both
      special-issues crons enabled=false); no special-issues post
      was published or re-verified as ready in this rehearsal.
- [ ] **Private reports/letters as app content (S37/S38):** the
      23:00 report and daily letter stay Yasin-private; their
      probabilities stay off app surfaces until calibration passes.
      — CONFIRMED: report cron (`eco-channel-daily-report`) and
      letter cron (`eco-daily-letter`) both enabled=false (R5);
      no probability line in Market tab code (R6 grep).
- [ ] **Morning digest revival (inside S18):** disabled 2026-10-22,
      stays off unless re-decided. — CONFIRMED: the digest id
      appears in NO re-enable list in the draft (the draft
      re-enables nothing).
- [ ] **Voices publication (S31):** until Yasin finalises the
      registry list. The blank slot stays blank. — CONFIRMED:
      Phase 6 ships gated placeholders only; no voice name, badge,
      or page was published in this rehearsal.
- [ ] **Domain steps (nic.ir, Cloudflare, Pages token):** Yasin-only
      steps, not done by the agent, not a lift precondition for the
      app (the app does not depend on the external blog domain).
      — CONFIRMED: none attempted in this rehearsal.
- [ ] **Release signing / Play distribution:** debug signing is
      shared and proven; release signing and any store distribution
      are separate decisions. Debug APKs stay direct-distribution
      (My Files install). — CONFIRMED: no release signing or
      store step was taken in this rehearsal.
- [ ] **FCM activation:** separate decision, separate phase, never
      implied by the lift. — CONFIRMED: see R10(c) FCM-state line
      (dormant scaffolding only, no project, no google-services.json,
      no sender).
- [ ] **Yasin-sent / owner private delivery rules:** no rule was
      found in the ML files for an archive-only/separate-stream
      treatment — if Yasin wants one, he states it as a new decision;
      the rehearsal does not invent it. — CONFIRMED: no rule
      invented in this rehearsal; the ML spec's stricter
      archive-only reading is flagged for Yasin's confirmation at
      lift, not decided here.

R11 scope verdict: the lift-order draft's scope field is BLANK,
marked "AWAITING YASIN'S SENTENCE — NOT A LIFT". A draft with a
scope filled in by the agent, or with "everything" / "the freeze"
as the scope, would FAIL R11. This draft does neither, so the
R11 ARTIFACT requirement is met — while the lift itself remains
entirely unperformed.
