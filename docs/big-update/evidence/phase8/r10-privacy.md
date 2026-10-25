# R10 — Diagnostics + privacy audit (Phase 8 rehearsal, 2026-10-26)

## Verdict: OPEN — lab audit filed, phone half missing

### (a) Copy-diagnostics output — PHONE ARTIFACT MISSING

The spec requires the full copy-diagnostics output as filed
(`phase7/e6-diagnostics.txt`), inspected line by line. That file
does not exist — no phone ran the diagnostics copy on any v4
build. The grep-style check below is therefore run against the
lab CODE that produces the copy, not against a filed output, and
cannot by itself pass R10(a).

Lab code facts (checked this rehearsal, `DiagnosticsScreen.kt`):

- Header comment (lines ~53–54): hash is stored as present/absent
  only — "the hash value itself is never shown". "NO PII, no
  article content, no invite code or hash, no [device id]".
- Copy comment (line ~175): copy-diagnostics copies exactly the
  lines shown.
- Section-name mapping uses Persian diagnostics names; per-section
  force-refresh re-reads the manifest and never bypasses the
  invite gate (Phase 1 report).
- Grep check on the diagnostics screen for code/hash/token
  leakage: the only `hash` references are the present/absent
  rule and the comment that hash values are never shown; no
  invite-code value, no token, no device id is rendered by the
  screen. (Command: `grep -n "invite\|hash" .../DiagnosticsScreen.kt`;
  result: policy comments + present/absent handling only.)

### (b) Telemetry surface — diagnostics-only, local-first

- No check-in path found: grep for `check-in|checkin|telemetry|
  analytics` across `app/src/main/java/` returns only comments
  stating the diagnostics-only, local-first rule (InviteGate,
  ChainRecord) — no reporting call, no analytics SDK, no HTTP
  client sending behaviour anywhere in the app code found by
  this sweep.
- No article content, no invite code/hash, no token, no device id
  in diagnostics output (see (a)).
- No app behaviour (reads / bookmarks / searches) is sent
  anywhere: no network call outside the edge sync path was found
  in this sweep. (Full-network audit on the built APK remains a
  v4 publish-check item, PUB-9, not claimed here.)

### (c) FCM-state line — stated precisely, not assumed

"FCM dormant — no Firebase project, no google-services.json, no
sender." Evidence this rehearsal:

- `app/google-services.json`: DOES NOT EXIST
  (`ls` → "No such file or directory").
- No Firebase project is referenced anywhere in the lab repo
  (vNext decision, M5/M7 records: polling + local notifications
  only).
- QUALIFICATION, stated not hidden: FCM SCAFFOLDING code exists
  in the tree (`app/src/main/java/com/ecojournal/app/fcm/
  EcoMessagingService.kt`, header: "FCM SCAFFOLDING — DORMANT BY
  DECISION, not by omission", plus an `FCM_TOKEN` key in
  `SettingsStore.kt`). Scaffolding without a project, without
  `google-services.json`, and without a sender cannot receive
  anything — but the spec's clean line is "no sender", and the
  dormant code is recorded here so no reviewer mistakes
  scaffolding for activation. FCM activation remains a separate
  decision, never implied by any lift.

## Unblocked by

Yasin filing `phase7/e6-diagnostics.txt` from a phone (copy output
from the v4 build) so the line-by-line inspection can run against
a real filed output instead of lab code. Owner: Yasin, phone.
