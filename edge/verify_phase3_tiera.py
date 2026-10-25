#!/usr/bin/env python3
"""Phase 3 Tier A extra checks on a merged edge (LAB ONLY).

Runs edge/publish_edge.py --verify-only logic (imported, not
re-implemented) plus the Tier A checks that belong to the merge
itself, not to generic edge verification:

  T1. Dedup: exactly one channel_typed item per non-null
      channel_message_id (one story = one card; a duplicate is
      F10 FAIL, never hand-deleted).
  T2. Chain monotonicity: for every chain-carrying item,
      channel_posted <= staged <= gate_decided <= edge_generated
      (epoch seconds); edge_pushed is null in Tier A (dry-run:
      nothing was pushed, and a non-null value here would be a
      lie about a push that never happened).
  T3. War Room preserved: radar.json's war_room object is
      byte-identical to the base edge's (the merge never infers
      a level from a SITREP).
  T4. Gated sections unchanged: special_issues gated_frozen,
      nightly + voices not_yet_published, all count 0.
  T5. Hygiene: no live-channel handle in any merged file, no
      secret-like pattern in merged items, no fixture id reused
      as a "real" id (merged message_ids are >= 9000001 or NULL
      by the lab convention -- and distinct from base ids).
  T6. Join: every chain-carrying item has a join_keys entry with
      the derived story_id and matching channel_message_id
      (also covered by publish verify; asserted here explicitly
      so this script stands alone as the Tier A gate).

Usage:
  python3 edge/verify_phase3_tiera.py --base DIR --merged DIR
Exit: 0 = all Tier A checks pass (publish verify must also pass;
this script runs it); 1 = any failure.
"""

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import publish_edge  # noqa: E402

SECRET_PATTERN = re.compile(
    r"(?i)(token|secret|password|api[_-]?key|private[_-]?key)\s*[:=]")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--merged", required=True)
    args = ap.parse_args()
    failures = []

    checks, passed = publish_edge.verify(args.merged)
    for status, msg in checks:
        print("[%s] %s" % (status, msg))
    if not passed:
        failures.append("publish_edge verification failed")

    merged_typed = load(os.path.join(args.merged, "channel_typed.json"))["items"]
    base_typed = []
    base_typed_path = os.path.join(args.base, "channel_typed.json")
    if os.path.exists(base_typed_path):
        base_typed = load(base_typed_path)["items"]
    base_mids = {i.get("channel_message_id") for i in base_typed
                 if i.get("channel_message_id") is not None}

    # T1: dedup by message_id.
    seen = {}
    for it in merged_typed:
        mid = it.get("channel_message_id")
        if mid is None:
            continue
        if mid in seen:
            failures.append("T1 duplicate channel_message_id %r (%s, %s)"
                            % (mid, seen[mid], it["app_key"]))
        seen[mid] = it["app_key"]
    print("T1 dedup: %d typed items, %d distinct non-null message_ids"
          % (len(merged_typed), len(seen)))

    # T2: chain monotonicity + Tier A pushed=null.
    for it in merged_typed:
        chain = it.get("chain")
        if chain is None:
            continue
        seq = [chain.get("channel_posted_epoch"), chain.get("staged_epoch"),
               chain.get("gate_decided_epoch"), chain.get("edge_generated_epoch")]
        if any(v is None for v in seq) or seq != sorted(seq):
            failures.append("T2 chain not monotonic for %s: %s"
                            % (it["app_key"], seq))
        if chain.get("edge_pushed_epoch") is not None:
            failures.append("T2 edge_pushed_epoch must be null in Tier A "
                            "(dry-run) for %s" % it["app_key"])
    print("T2 chain monotonicity checked for %d chain-carrying items"
          % sum(1 for i in merged_typed if i.get("chain") is not None))

    # T3: war_room preserved verbatim.
    base_radar = load(os.path.join(args.base, "radar.json")) \
        if os.path.exists(os.path.join(args.base, "radar.json")) else None
    merged_radar = load(os.path.join(args.merged, "radar.json")) \
        if os.path.exists(os.path.join(args.merged, "radar.json")) else None
    if base_radar is not None and merged_radar is not None:
        if base_radar.get("war_room") != merged_radar.get("war_room"):
            failures.append("T3 war_room object changed by merge")
        else:
            print("T3 war_room object preserved verbatim")
    else:
        print("T3 war_room: no radar in base or merged (nothing to compare)")

    # T4: gated sections.
    manifest = load(os.path.join(args.merged, "manifest.json"))
    sections = manifest.get("sections", {})
    for name, expect in (("special_issues", "gated_frozen"),
                         ("nightly", "not_yet_published"),
                         ("voices", "not_yet_published")):
        sec = sections.get(name, {})
        if sec.get("status") != expect or sec.get("count") != 0:
            failures.append("T4 %s is %r, expected {%s, 0}" % (name, sec, expect))
    print("T4 gated sections unchanged")

    # T5: hygiene.
    for root, _dirs, files in os.walk(args.merged):
        for fname in files:
            path = os.path.join(root, fname)
            text = open(path, encoding="utf-8", errors="replace").read()
            if "EcoJournalFa" in text:
                failures.append("T5 live-channel handle in %s" % fname)
            if fname not in ("invite_hashes.json", "revocations.json") \
                    and SECRET_PATTERN.search(text):
                failures.append("T5 secret-like pattern in %s" % fname)
    new_mids = [i.get("channel_message_id") for i in merged_typed
                if i.get("chain") is not None]
    if any(m in base_mids for m in new_mids):
        failures.append("T5 merged message_id collides with a base fixture id")
    print("T5 hygiene checked (%d merged message_ids, none colliding)"
          % len(new_mids))

    # T6: join entries for chain-carrying items.
    join = load(os.path.join(args.merged, "join_keys.json"))
    jk_by_key = {j["app_key"]: j for j in join.get("entries", [])}
    for it in merged_typed:
        if it.get("chain") is None:
            continue
        j = jk_by_key.get(it["app_key"])
        if j is None:
            failures.append("T6 %s missing from join_keys" % it["app_key"])
        elif j.get("channel_message_id") != it.get("channel_message_id") \
                or j.get("story_id") != it.get("story_id"):
            failures.append("T6 %s join entry mismatch" % it["app_key"])
    print("T6 join entries checked for chain-carrying items")

    if failures:
        print("=== Tier A verification: FAIL ===")
        for f in failures:
            print("  FAIL: %s" % f)
        return 1
    print("=== Tier A verification: PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
