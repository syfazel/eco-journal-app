# E1–E8 re-verification pack — Phase 7 (phone leg, NOT passed by lab)

The E-gates were defined in Phase 1. Phase 7 re-verifies them against
the Phase 7 tree; every gate still needs the phone except where noted.
Lab status below is what the code inspection establishes; PHONE status
is open for all eight until Yasin runs them on the v4 APK.

## E1 — Navigation (appKey)

- What to check: every tab, deep link and back action resolves by
  appKey; no route renders the wrong article; back returns to the
  prior position, not the top.
- Lab: navigation code untouched by Phase 7 except tab labels (now
  resource-backed, same order, same 8 tabs). No route change.
- Phone: walk all 8 tabs + one article + one bundle + one edition +
  one channel item; log any wrong-article or top-reset.
- Status: PHONE-PENDING.

## E2 — Data correctness (hash-gated sync)

- What to check: a sync with unchanged section hashes downloads
  nothing; a changed section downloads only that section; counts in
  Diagnostics match the edge manifest.
- Lab: manifest v3 / delta / per-section retry code untouched by
  Phase 7. New failed lines read the same section states Diagnostics
  reads; they add no sync path.
- Phone: run Diagnostics before and after a no-change sync; compare
  counts; force one section change and confirm only it moves.
- Status: PHONE-PENDING.

## E3 — Feed / reader fidelity

- What to check: feed cards, reader headers and bar labels match the
  edge data; RED typed / YELLOW energy, red over yellow, text label
  always present; no invented article, no placeholder body.
- Lab: bar rendering untouched except the chip text colours (A1/A2)
  and badge descriptions (A3); bar assignment logic unchanged.
  Aggregation rows (editions, bundles) carry no item bar — verified
  in code; last-night leftovers carry RED — verified in code.
- Phone: compare 5 feed cards + 2 readers against the edge manifest
  entries; check bar label announcements (see TalkBack script T2/T5).
- Status: PHONE-PENDING.

## E4 — Market correctness (computed formulas)

- What to check: board values recompute from the snapshot + formula
  defs (M4 formulas, NULL + missing-index inputs, never silent
  substitution); history chart matches the snapshot series.
- Lab: formula code untouched by Phase 7. Market failed line reads
  the market section state only; it cannot change a value.
- Phone: recompute one board value by hand from the snapshot card +
  formula text; check the history card matches the selected snapshot.
- Status: PHONE-PENDING.

## E5 — Nightly edition fidelity

- What to check: the nightly section shows the real edition (Jalali
  date, ≥300-word articles, ≥800-word full pieces, versioned
  corrections visible); held items show a reason, never a guess.
- Lab: nightly generator untouched by Phase 7; edition rows gained
  role/label/padding only; the U4 gate (Tier-A flag, summary score,
  rewrite score unpinned) is unchanged and still open.
- Phone: open the latest edition; verify date, one full piece, one
  correction note if present; confirm no held item renders as content.
- Status: PHONE-PENDING.

## E6 — Editorial / voices / special-issues gates

- What to check: Method page shows the 7 fixed sections v1
  (1405-08-03); Voices shows ONLY the §3.6 placeholder (no name,
  count or example); Special Issues shows ONLY "ویژه‌نامه‌ها —
  متوقف است"; the 100-cap voices guard still refuses on an
  unfinalised list (exit 2 in the lab tool).
- Lab: gated screens gained scroll only; placeholder copy unchanged;
  the voices 100-cap guard code untouched.
- Phone: open all three screens; confirm no name/count/example leaks;
  confirm both placeholders scroll fully at 1.3x.
- Status: PHONE-PENDING.

## E7 — Test-channel end-to-end (BLOCKED)

- What to check: a real channel post flows channel → staging → edge
  → app (near-real-time chain, Tier B) with correct bar and timing.
- Lab: Tier A chain code untouched by Phase 7.
- Blocker: needs the test channel, which only Yasin can create
  (private channel + @YASINECOBOT admin + the -100… id). Also blocked
  by the hard-coded pilot Pages edge address: a real test publish
  would reach pilot readers. Do NOT run Tier B until both are resolved
  by Yasin's hand + a lab edge-isolation decision.
- Status: BLOCKED — not attempted, not passed.

## E8 — Build + install-over (HALF DONE)

- Build half: Phase 7 adds no build; the v4 cloud build (after all
  phases) is the compile gate. Lab checks (balance, string resolution,
  semantics sweep, fixture verifiers) are the pre-build evidence, not
  a build pass.
- Phone half: install the v4 APK OVER the previous shared-signature
  build WITHOUT uninstalling; confirm data (bookmarks, settings)
  survives and the version shows 4.0.0. A fresh-install-only success
  does NOT pass E8.
- Status: PHONE-PENDING (build half awaits the v4 cloud build).

## Re-verification verdict

No gate is marked passed by Phase 7. The lab work narrows the phone
leg (failed lines, bar announcements, scroll safety, target sizes)
but replaces none of it. The consolidated phone checklist that runs
these gates together is `v4-phone-checklist-inputs.md`.
