# v4 phone-checklist inputs — consolidated from Phase 7

These are the Phase 7 contributions to the single v4 phone checklist
that runs on the final v4 APK. They do not replace the per-phase
checklist items from Phases 1–6; they are the polish/accessibility
leg that only Phase 7 adds. Nothing here is passed by lab work.

## A. TalkBack walkthrough (full script)

`talkback-walkthrough.md` in this folder — 26 online steps (T1–T26),
4 offline steps (O1–O4), 3 measured-contrast/target steps (C1–C3).
Run at 0.85x, current size, and 1.3x. Log PASS/FAIL per step with a
short recording or the exact announcement.

## B. E1–E8 re-verification

`e1-e8-reverification.md` in this folder — all eight gates, with the
Phase 7 lab position for each. E7 stays blocked on Yasin's test
channel; E8's phone half (install over, no uninstall) is the last
install check on the v4 APK.

## C. State-matrix items Phase 7 added or sharpened

1. Market tab failed line (Persian "بازار") + retry, after a forced
   failed market sync; stale board must not pose as fresh.
2. Analysis failed line (Persian nightly name) + stale-item honesty,
   after a forced failed nightly sync.
3. War-room and bundles failed lines with Persian section names —
   never raw keys (`radar`, `channel_typed`, `bundles`).
4. Expired urgent pin (>6h) labelled on the rail card itself.
5. Offline cache retention: a failed sync never blanks a section.
6. Morning-bundle empty copy: "not yet published" / "arrives at 08:00"
   — wording still to be settled editorially; the v4 checklist must
   include the empty state once the copy exists (P7-R4, carried).
7. Missing-manifest-section UNKNOWN vs "up to date" line (P7-R3,
   carried — Phase 1 engine semantics, not a phone guess).

## D. Font-extreme and contrast items

1. Gated placeholders (Voices, Special Issues) scroll fully at 1.3x.
2. Market chart card + snapshot card + formula rows: no clip/overlap
   at 1.3x, no collapsed targets at 0.85x.
3. Measured (not eyeballed) AA on: yellow/black bar chip, red/white
   bar chip, orange/black WATCH and L3 chips.
4. All tappable rows ≥48dp at 1.3x (spot-check every screen).

## E. Items Phase 7 explicitly defers OUT of the v4 phone checklist

- OFL lookalike font, CNN ticker, sync compaction, helper modules
  (H1/H2), remote in-app updating — V4-H/I programme, separate
  checklist, after all phases.
- Voices finalisation (P6-V4–V7), special-issues C1–C7 + named-post
  order (P6-S5–S8), week-card decision (P6-T5) — each needs Yasin's
  separate decision; none is smuggled into the v4 checklist as done.
- Freeze-lift itself — Phase 8 rehearsal is dry-run only; the real
  lift needs Yasin's explicit "open for <scope>" sentence after a
  proven real connection.

## F. Evidence filing rule (applies to the whole v4 checklist)

Every phone result files under `docs/big-update/evidence/phase7/` (for
Phase 7 items) or the corresponding phase folder, with the date, the
APK version (must read 4.0.0 on the final), and the step number. No
evidence file may contain an invite code, a token, a hash value, a
device id, or personal content.
