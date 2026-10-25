# Phase 1 — Edge side of the Update Engine (LAB ONLY)

**Scope note.** This document covers ONLY the lab edge side:
`edge/generate_edge.py` (manifest v3 + join keys + shorts fix) and the
new lab command `edge/publish_edge.py`. The Kotlin app side (manifest
parsing, delta sync, per-section states) belongs to the other worker;
no Kotlin file was touched here, and `docs/cron-reconciliation.md`
belongs to another worker too. Nothing in this pass touched
`econ-news-blog/` or `yasineco_bot/` except read-only SQLite
(`mode=ro`), no `.env` was opened, no token/invite code/secret was
printed or embedded, nothing was pushed to GitHub, no publish ran in
real mode, and no live cron, bot, channel, or freeze state was touched.

**Deliberate end state of this pass.** The canonical `edge/out/`
snapshot was NOT regenerated: verification ran against a temporary
directory (`edge/out-test-tmp`, deleted afterwards), so the committed
edge still carries the old export (index v2 with shorts as
`general`, no `manifest.json` yet). The first authorised
`publish_edge.py --real` run will regenerate the canonical edge and
create `manifest.json` + `join_keys.json` there. Do not mistake the
absence of `manifest.json` in `edge/out/` today for a missing feature
— the code path is proven below; it simply has not been run against
the canonical directory yet.

---

## 1. Manifest v3 schema (exact — Kotlin parses exactly this)

File: `edge/out/manifest.json`. Top-level keys, exactly:

```json
{
  "version": 3,
  "generated_at_utc": "<iso-8601 UTC>",
  "generator": "edge/generate_edge.py (Phase 1, LAB ONLY)",
  "sections": { "...": "..." },
  "join_keys_file": "join_keys.json"
}
```

Sections (exactly these 11 keys, no extras):

| Section | Shape |
|---|---|
| `articles` | `{"hash", "count", "items": [{"id", "app_key", "content_hash", "updated_at_epoch", "status", "file", "channel_message_id", "story_id"}]}` |
| `shorts` | `{"hash", "count", "items": [{"id", "app_key", "content_hash", "updated_at_epoch", "status", "category", "category_fa"}]}` |
| `market` | `{"hash", "count", "file": "market.json"}` |
| `channel_refs` | `{"hash", "count", "file": "channel_refs.json"}` |
| `invites` | `{"hash", "file": "invite_hashes.json"}` (no `count` — by design) |
| `revocations` | `{"hash", "file": "revocations.json"}` (no `count` — by design) |
| `bundles` | `{"status": "not_yet_published", "count": 0}` |
| `radar` | `{"status": "not_yet_published", "count": 0}` |
| `nightly` | `{"status": "not_yet_published", "count": 0}` |
| `voices` | `{"status": "not_yet_published", "count": 0}` |
| `special_issues` | `{"status": "gated_frozen", "count": 0}` |

Notes on the shapes (do not "fix" these without coordinating with
the Kotlin worker):
- `articles.items[].file` is the per-article file path as in
  `index.json` (`articles/<sha256-of-id>.json`). Shorts have no `file`
  in manifest items — their content lives in `short_cards.json`.
- `bundles` / `radar` / `nightly` / `voices` carry no `hash` yet
  because they have no published content yet; a hash over an empty
  list would falsely imply "synced and empty". Their honest state is
  `not_yet_published`.
- `special_issues` is `gated_frozen`, not `not_yet_published`:
  that service was separately frozen 2026-10-25 ~01:56 and nothing
  here unfreezes it.
- `invites` / `revocations` carry no `count` in the agreed schema;
  the counts (10 hashes / 1 hash at verification time) live in
  `GENERATION_REPORT.json` under `phase1`, not in the manifest.
- `index.json` stays version 2 with its generator string unchanged
  (`edge/generate_edge.py (M3, LAB ONLY)`) for backward
  compatibility. The only entry-level change vs the previous export
  is the shorts fix (§4). `index.json` and `manifest.json` share one
  `generated_at_utc` timestamp per run.

## 2. Section-hash definitions

Canonical JSON everywhere:

```python
json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
```

A section hash is the SHA-256 hex of the UTF-8 encoding of that
canonical form. Definitions, per section:

- **articles / shorts:** canonical JSON of the list of
  `{"app_key", "content_hash", "updated_at_epoch", "status"}` for
  that section's items, **sorted by `app_key`**. The manifest's own
  `items` lists keep index order (articles: `extracted_at` DESC;
  shorts: `decided_at` DESC); only the hashed list is sorted. The
  app therefore recomputes the hash by projecting its stored items
  to those four fields and sorting — it must not hash the manifest
  `items` array as-is (field order and extra fields differ).
- **market:** canonical JSON of
  `{"snapshots": [...], "formulas_active": [...]}` exactly as
  written to `market.json`. `market.json`'s bookkeeping keys
  (`snapshot_count`, `first_ts`, `last_ts`, `lineage_note`) are
  deliberately NOT part of the hash.
- **channel_refs:** canonical JSON of the refs list exactly as
  written to `channel_refs.json`.
- **invites / revocations:** canonical JSON of the hashes list read
  from `invite_hashes.json` / `revocations.json` (hashes only, never
  codes — see §5).

`publish_edge.py` recomputes every one of these from the generated
files themselves (articles/shorts from `index.json` entries, market
from `market.json`, refs from `channel_refs.json`, invites/
revocations from their hashes files) and compares with the manifest;
it also checks manifest article items field-by-field against the
index, so a manifest-only lie fails verification.

## 3. Join keys (`edge/out/join_keys.json`)

```json
{"version": 1, "entries": [{"guid", "app_key", "story_id", "channel_message_id"}]}
```

One entry per index entry, in index order (articles first, then
shorts). Rules:

- `story_id = "story-" + first 20 hex of SHA-256(guid)`.
- Articles: `guid` = `item_guid` from `data_core.article_extracts`.
  ONLY if `item_guid` is empty does the generator fall back to
  `extract_id`, and every such fallback is counted in
  `GENERATION_REPORT.json` under
  `phase1.guid_fallbacks_to_extract_id`. A guid is never invented:
  the fallback reuses an existing real id, it does not fabricate
  one. Verification run: **0 fallbacks** (all 249 articles carry a
  real `item_guid`).
- Shorts: `guid` = the short id itself (`draft-N`, e.g. `draft-87`).
- `channel_message_id` is `null` on all 281 entries today. That is
  honest, not a gap to paper over: no current edge item has a real
  channel post id (article extracts carry 0 non-null values, and
  shorts have none by construction). The bridge staging path
  (Phase 3) is what will populate it later — see §6.
- `app_key` is the same `a-<sha256(id)[:20]>` value as in
  `index.json`, so the app can join manifest ↔ index ↔ join_keys on
  `app_key` without ever using the verbatim id in a route.

## 4. Shorts category fix

**Before:** shorts were silently `general` / `عمومی` in index
entries, with no category field at all in `short_cards.json` — a
silent default that hid "we don't know the topic" as if it were a
real category.

**Now (Phase 1):** shorts carry the explicit marker
`category = "uncategorised"`, `category_fa = "دسته‌بندی‌نشده"` in
all three places: `short_cards.json` objects (new `category` /
`category_fa` fields), `index.json` short entries, and manifest
shorts items.

**Hash consequence (correct, not a bug):** the short content hash
is recomputed with category `uncategorised` using the same 9-field
U+0001 join the Kotlin `ContentHashes.forArticle` uses — fields
title, summary (= full text), body joined by newline (= the single
full text), points `"[]"`, why `""`, category, source,
`original_url`, `decided_at`. Because the category input changed,
**all 32 short hashes changed** in this pass. The first v3 sync will
therefore refetch all 32 shorts even though their text is unchanged.
That refetch is the correct behaviour of a hash-gated sync, and
`publish_edge.py` verifies the new hashes by independently
recomputing the same 9-field join from each short object.

**Residual (deliberately NOT fixed here):** 45 full articles whose
topic is unknown still carry `general` / `عمومی` in this pass. Their
category comes from `data_core.items.topic` via a fixed map, and
changing that mapping is a data decision for a later pass, not
something to silently fix alongside the shorts change. Recorded here
so it is not mistaken for an oversight: articles `general` = 45,
shorts `general` = 0 after this pass (was 32 + 45 = 77 `general`
index entries before).

## 5. Invite / revocation files — ownership boundary

`invite_hashes.json` and `revocations.json` are **owned by
`bridge/control.py`**, which writes hashes only (never codes). The
generator must not become a second writer of those files:

- When `--out` IS the canonical `edge/out`, the generator reads the
  files as-is and leaves them untouched.
- When `--out` is a different directory (e.g. the verification temp
  dir), the generator copies each file **verbatim** into `--out`
  (only if present in the canonical dir) so the output is
  self-contained and its manifest hashes are verifiable standalone.
- When neither location has the file, the manifest hash is the hash
  of the empty list `[]`, and the absence is recorded in
  `GENERATION_REPORT.json` (`phase1.invite_hashes_source` /
  `revocation_hashes_source`).
- A malformed hashes file (missing `hashes` list, non-string
  entries) makes the generator fail loudly rather than publish a
  wrong hash.
- `publish_edge.py` additionally verifies hashes-only: every value
  in both files must match `^[0-9a-f]{64}$`, and no file in the edge
  output may contain a secret-like pattern (`token` / `secret` /
  `password` / `api_key` / `private_key` followed by `:` or `=`),
  nor a forbidden file (`.env`, `google-services.json`).

At verification time: 10 invite hashes, 1 revocation hash, all
hex-64, copied verbatim from the canonical edge into the temp dir.

## 6. story_key → story_id migration design

**The production defect (background, not touched here).** The
production queue key `story_key` is a hash of the headline, and
transliteration/transcription drift per fetch mints a NEW key for
the same story (incident 2026-10-23: one Atlantic story queued 4×
as rows 118/120/124/125 under `آتلانتیک`/`اتلانتیک` spellings).
Token-overlap ≥0.35 exists in the codebase but is not the queue key,
so it does not prevent the pile-up.

**What this pass does (edge/app side, done).** On the edge/app side,
`story_key` is SUPERSEDED by the guid-derived `story_id`
(`story-<sha256(guid)[:20]>`, §3). The guid is stable across
fetches — it is the item's identity in `data_core`, not a rendering
of its headline — so the same story maps to the same `story_id`
regardless of spelling drift. The app joins everything (feed dedup,
bookmarks, future story timelines) on `story_id` / `app_key`, never
on a headline hash. `join_keys.json` is the mapping table that makes
this join auditable (the `item_id_map` migration-009 pattern from
the plan, carried through to the edge).

**What is specified but explicitly OUT of lab scope (residual).**
The production-side change — normalising the headline/event BEFORE
hashing (so `story_key` itself stops drifting), or replacing the
queue key with semantic-content dedup at the queue layer — is
specified here as the required production fix but was NOT
implemented: it would touch production queue code under
`econ-news-blog/` / `yasineco_bot/`, which this pass is forbidden
to modify. Until that production change lands, the edge can only
deduplicate what it is given; if the same story arrives under two
different guids, it will still appear as two `story_id`s. That is a
known, bounded residual, not a silent one: the join table makes it
detectable (two entries, same normalised title, different guids),
and the production fix removes the cause.

**Bridge staging (Phase 3, not this pass).** When the bridge staging
path captures a real channel post, it must write the post's real
`message_id` into the edge item's `channel_message_id` AND carry
the guid → `story_id` mapping through, so a staged post joins to
its article row instead of creating a parallel identity. E7 (bridge
dry-run post → typed card with non-null `channel_message_id`) is
the acceptance test for that step and is NOT claimed here.

## 7. publish_edge.py — lab command usage

```
# Dry run (the ONLY mode tested in this pass; changes nothing in git):
python3 edge/publish_edge.py --dry-run [--out DIR]

# Real publish (NEVER run in this pass; canonical edge/out only):
python3 edge/publish_edge.py --real
```

Steps, in order:
1. Run `generate_edge.py` into `--out` (default: canonical
   `edge/out`).
2. Verify (see §8): recompute every section hash, check join_keys
   coverage = articles + shorts, check counts against
   `GENERATION_REPORT.json`, check shorts markers, check
   hashes-only / no secrets. Any failure aborts with exit 1 and
   nothing is staged, committed, or pushed.
3. `--dry-run`: print the verification report and stop.
   `--real`: `git add edge/out` ONLY (never `git add -A`, so a stray
   `.env` / keystore / `google-services.json` elsewhere can never
   ride along), commit, push. A failed commit (e.g. nothing
   changed) aborts before any push.

Guards (all tested, all refuse with exit 2):
- Exactly one of `--dry-run` / `--real` must be given; with neither
  (or both) the script refuses to guess.
- `--out` must resolve inside the app root (same guard as the
  generator).
- `--real` is refused unless `--out` IS the canonical `edge/out`
  (publishing a temp directory would push a partial edge).

## 8. Verification results (this pass)

Method: `generate_edge.py` run to `edge/out-test-tmp` (temp dir
inside the app root, deleted afterwards); manifest hashes then
recomputed by an INDEPENDENT script (fresh implementation, not
importing the generator); `publish_edge.py --dry-run` run against
the same temp output; `python3 -m py_compile` on both edited files;
brace/paren sanity via the same compile step (a syntax error would
fail it).

| Check | Result |
|---|---|
| `py_compile` on `generate_edge.py` + `publish_edge.py` | PASS |
| Counts: articles / shorts / index / market / refs | 249 / 32 / 281 / 164 / 23 |
| Manifest version / generator string | 3 / `edge/generate_edge.py (Phase 1, LAB ONLY)` |
| Manifest sections | exactly the 11 specified keys |
| Articles section hash recomputed identically | PASS (249 items) |
| Shorts section hash recomputed identically | PASS (32 items) |
| Market section hash recomputed identically | PASS (164 snapshots, 4 formulas) |
| Channel-refs hash recomputed identically | PASS (23 refs) |
| Invites hash recomputed identically | PASS (10 hashes) |
| Revocations hash recomputed identically | PASS (1 hash) |
| Join keys: entries / unique guids / story_id derivation | 281 / 281 / all correct |
| Join keys coverage = articles + shorts; app_keys match index | PASS |
| `channel_message_id` non-null | 0 (honest null, §3/§6) |
| Guid fallbacks to `extract_id` | 0 |
| Shorts category `uncategorised` in short_cards + index + manifest | PASS (32/32/32) |
| Short 9-field hash recomputation (Kotlin-compatible) | PASS (32/32) |
| Short hashes changed vs previous export | 32/32 (expected, §4) |
| Index v2: article entries byte-identical to previous export | PASS |
| Index v2: only entry diffs vs previous export | shorts `category` (32), `category_fa` (32), `content_hash` (32) — nothing else |
| Index still v2, generator string still `M3` | PASS |
| Article `general` count unchanged (residual) | 45 |
| Exclusions unchanged vs Phase 0 audit | pending 2560 / rejected 277 / approved-without-body 84 / drafts rejected 52 |
| `publish_edge.py --dry-run` | PASS, exit 0, 20/20 checks OK |
| Guards: neither flag / both flags / `--out` outside root / `--real` on non-canonical dir | all refused, exit 2 |
| Canonical `edge/out/` after the pass | UNTOUCHED (still old export, no `manifest.json` — by design, see top) |
| Git pushes / real publishes / cloud builds | NONE (none claimed, none run) |

`publish_edge.py --dry-run` output (abridged — full run printed
20 OK lines, zero FAILs, ending `result: PASS` then
`DRY-RUN: verification passed; nothing changed in git.`):
`articles hash recomputes identically (249 items)`,
`shorts hash recomputes identically (32 items)`,
`market hash recomputes identically (164 snapshots)`,
`channel_refs hash recomputes identically (23 refs)`,
`invites hash recomputes identically (10 hashes)`,
`revocations hash recomputes identically (1 hashes)`,
`join_keys coverage == articles + shorts (281)`,
`counts match GENERATION_REPORT.json`.

## 9. What remains (not done here)

- **Bridge staging merge = Phase 3.** Populating
  `channel_message_id` from real channel posts, typing staged posts
  (`format_type`, urgency, crisis level), and the E7 acceptance test
  (staged test-channel post → typed card in the app). Needs a test
  channel, which only Yasin can create — that is the blocking
  external dependency, not a code gap.
- **Production story_key fix (residual, §6).** Headline
  normalisation before hashing (or semantic dedup at the queue
  layer) in production code — explicitly out of lab scope here.
- **Kotlin manifest consumer.** The other worker's code must fetch
  `manifest.json` FIRST, compare per-section hashes, and fetch only
  changed sections (E2/E3 acceptance). Until it ships, manifest v3
  is produced but not yet consumed.
- **45 `general` articles (§4).** Unknown-topic article categories
  need a data decision in a later pass.
- **First real publish.** `publish_edge.py --real` has never been
  run; the canonical edge has no manifest yet (by design, §top).
  Running it is a parent decision, not something this lab pass does
  on its own.
- **Unpublished sections.** `bundles`, `radar`, `nightly`, `voices`
  gain real hashes/content only in Phases 2/5; `special_issues`
  stays `gated_frozen` until Yasin separately orders its reopen
  (with its preconditions: re-verify the 2 partially-verified posts
  from source pages, resolve the queue-location discrepancy,
  recover the missing full texts).
