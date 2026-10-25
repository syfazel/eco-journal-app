#!/usr/bin/env python3
"""Phase 2 tests — ECO JOURNAL bridge (LAB ONLY, stdlib unittest).

Covers: status machine (legal walk + illegal jumps + rejected
terminal), Telegram 4096 cap (boundary + splitting), send-lock
contention, cross-run idempotency, dry-run writing nothing as sent,
live gates failing closed, channel-reader dedup (3 layers), and the
invite-code lifecycle (hash-only storage).

Zero network: the live path is exercised only with a mock sender;
no test ever calls the real Bot API. Production DBs are never
opened here — fixtures are real edge seed articles (read-only) and
temp lab queue DBs.

Run:  python3 bridge/test_phase2.py
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parent
APP_ROOT = BRIDGE_DIR.parent
sys.path.insert(0, str(BRIDGE_DIR))

import bridge_lib  # noqa: E402
import channel_reader  # noqa: E402
import publish_worker  # noqa: E402


def real_article():
    index = json.loads((APP_ROOT / "edge" / "out" / "index.json").read_text(encoding="utf-8"))
    for entry in index["entries"]:
        if entry.get("kind") == "article" and entry.get("file"):
            return json.loads((APP_ROOT / "edge" / "out" / entry["file"]).read_text(encoding="utf-8"))
    raise AssertionError("no seed article found")


def run_cli(db, *args):
    return subprocess.run(
        [sys.executable, str(BRIDGE_DIR / "control.py"), "--db", str(db), *args],
        capture_output=True, text=True, cwd=str(APP_ROOT))


class TestStatusMachine(unittest.TestCase):
    def test_legal_walk(self):
        # M7: published_app -> sending -> published_channel (the
        # crash-recovery state is now part of the legal walk).
        status = "draft"
        for target in ("review", "approved", "queued", "published_app",
                       "sending", "published_channel"):
            ok, _ = bridge_lib.transition(status, target)
            self.assertTrue(ok, f"{status} -> {target} must be legal")
            status = target

    def test_illegal_jumps(self):
        illegal = [
            ("draft", "approved"), ("draft", "queued"), ("draft", "published_app"),
            ("draft", "published_channel"), ("draft", "rejected"),
            ("review", "queued"), ("review", "published_app"), ("review", "published_channel"),
            ("approved", "review"), ("approved", "published_app"), ("approved", "published_channel"),
            ("queued", "approved"), ("queued", "published_channel"),
            ("published_app", "queued"), ("published_app", "approved"),
            ("published_app", "published_channel"),  # M7: must go via sending
            ("sending", "queued"), ("sending", "review"), ("sending", "approved"),
            ("published_channel", "draft"), ("published_channel", "review"),
            ("rejected", "draft"), ("rejected", "review"), ("rejected", "approved"),
            ("unknown", "review"),
        ]
        for src, dst in illegal:
            ok, _ = bridge_lib.transition(src, dst)
            self.assertFalse(ok, f"{src} -> {dst} must be refused")

    def test_rejected_is_terminal_but_legal_from_review(self):
        ok, _ = bridge_lib.transition("review", "rejected")
        self.assertTrue(ok)
        ok, _ = bridge_lib.transition("rejected", "draft")
        self.assertFalse(ok)


class TestCapValidation(unittest.TestCase):
    def test_exact_cap_passes(self):
        body = "x" * 4000
        text = "T\n\n" + body + "\n\nمنبع: S"
        # Build exactly 4096 chars: title(1)+2+4000+2+len("منبع: S")=7 -> 4010; pad body.
        body = "x" * (4096 - 1 - 2 - 2 - 7)
        text = "T\n\n" + body + "\n\nمنبع: S"
        self.assertEqual(len(text), 4096)
        ok, _ = bridge_lib.validate_channel_text(
            text, {"title": "T", "body_blocks": [body], "source": "S"})
        self.assertTrue(ok)

    def test_one_over_cap_fails(self):
        body = "x" * (4096 - 1 - 2 - 2 - 7 + 1)
        text = "T\n\n" + body + "\n\nمنبع: S"
        self.assertEqual(len(text), 4097)
        ok, why = bridge_lib.validate_channel_text(
            text, {"title": "T", "body_blocks": [body], "source": "S"})
        self.assertFalse(ok)
        self.assertIn("over Telegram cap", why)

    def test_split_roundtrip(self):
        paras = ["para %d " % i + "y" * 900 for i in range(6)]
        text = "Title\n\n" + "\n\n".join(paras) + "\n\nمنبع: S"
        self.assertGreater(len(text), 4096)
        parts = bridge_lib.split_channel_text(text)
        self.assertIsNotNone(parts)
        self.assertTrue(all(len(p) <= 4096 for p in parts))
        self.assertEqual("\n\n".join(parts), text)

    def test_single_huge_paragraph_cannot_split(self):
        text = "T\n\n" + "z" * 5000 + "\n\nمنبع: S"
        self.assertIsNone(bridge_lib.split_channel_text(text))
        ok, why = bridge_lib.validate_channel_text(
            text, {"title": "T", "body_blocks": ["z" * 5000], "source": "S"})
        self.assertFalse(ok)
        self.assertIn("manual edit", why)

    def test_missing_attribution_and_empty_body(self):
        ok, _ = bridge_lib.validate_channel_text(
            "T\n\nbody\n\nمنبع: ", {"title": "T", "body_blocks": ["body"], "source": ""})
        self.assertFalse(ok)
        ok, _ = bridge_lib.validate_channel_text(
            "T\n\n\n\nمنبع: S", {"title": "T", "body_blocks": [], "source": "S"})
        self.assertFalse(ok)


class TestLockAndWorker(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "q.db"
        self.report = Path(self.tmp.name) / "report.json"
        self.article = real_article()
        self.key = f"article:{self.article['id']}"
        con = bridge_lib.init_db(self.db)
        ok, _ = bridge_lib.enqueue_item(con, self.key, self.article)
        self.assertTrue(ok)
        for target in ("review", "approved", "queued", "published_app"):
            ok, _ = bridge_lib.set_status(con, self.key, target)
            self.assertTrue(ok)
        con.close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run_records_nothing_as_sent(self):
        code, report = publish_worker.run_worker(
            db_path=self.db, live=False, report_file=self.report)
        self.assertEqual(code, 0)
        self.assertEqual(report["mode"], "dry-run")
        self.assertEqual(report["live_sends"], 0)
        self.assertEqual(len(report["would_send"]), 1)
        con = bridge_lib.init_db(self.db)
        self.assertEqual(con.execute("SELECT COUNT(*) FROM sent").fetchone()[0], 0)
        item = bridge_lib.get_item(con, self.key)
        self.assertEqual(item["status"], "published_app")  # untouched
        self.assertIsNone(item["channel_message_id"])
        con.close()

    def test_live_gates_fail_closed(self):
        for var in ("BRIDGE_LIVE", "BRIDGE_CHANNEL_ID", "ECO_BOT_TOKEN",
                    "BRIDGE_CHANNEL_ALLOWLIST"):
            os.environ.pop(var, None)
        calls = []
        code, report = publish_worker.run_worker(
            db_path=self.db, live=True, report_file=self.report,
            sender=lambda text, chat: calls.append((text, chat)))
        self.assertEqual(code, 0)
        self.assertEqual(report["mode"], "dry-run")  # refused -> dry-run
        self.assertEqual(report["live_sends"], 0)
        self.assertEqual(calls, [])  # sender never invoked

    def test_cross_run_idempotency_with_mock_sender(self):
        os.environ["BRIDGE_LIVE"] = "1"
        os.environ["BRIDGE_CHANNEL_ID"] = "test-channel-123"
        os.environ["BRIDGE_CHANNEL_ALLOWLIST"] = "test-channel-123"
        os.environ["ECO_BOT_TOKEN"] = "DUMMY-NOT-REAL"
        try:
            calls = []
            code, report = publish_worker.run_worker(
                db_path=self.db, live=True, report_file=self.report,
                sender=lambda text, chat: (calls.append((text, chat)), 999001)[1])
            self.assertEqual(code, 0)
            self.assertEqual(report["live_sends"], 1)
            self.assertEqual(report["sent"][0]["message_ids"], [999001])
            self.assertEqual(len(calls), 1)
            # Second run: item is published_channel, nothing to send.
            code2, report2 = publish_worker.run_worker(
                db_path=self.db, live=True, report_file=self.report,
                sender=lambda text, chat: (calls.append((text, chat)), 999002)[1])
            self.assertEqual(report2["live_sends"], 0)
            self.assertEqual(len(calls), 1)  # no second send
            # Report file must never contain the token value.
            self.assertNotIn("DUMMY-NOT-REAL", self.report.read_text(encoding="utf-8"))
            con = bridge_lib.init_db(self.db)
            self.assertTrue(bridge_lib.already_sent(con, self.key))
            con.close()
        finally:
            for var in ("BRIDGE_LIVE", "BRIDGE_CHANNEL_ID", "ECO_BOT_TOKEN",
                        "BRIDGE_CHANNEL_ALLOWLIST"):
                os.environ.pop(var, None)

    def test_lock_contention_exits_cleanly(self):
        with bridge_lib.send_lock():
            # Second non-blocking acquire in this process must fail.
            with self.assertRaises(bridge_lib.LockHeld):
                with bridge_lib.send_lock():
                    pass
            # And the worker must exit 2 without sending anything.
            code, _ = publish_worker.run_worker(
                db_path=self.db, live=False, report_file=self.report)
            self.assertEqual(code, 2)
            # M7: the report is rewritten even on lock contention.
            rep = json.loads(self.report.read_text(encoding="utf-8"))
            self.assertEqual(rep["mode"], "skipped: lock held")


class TestChannelReader(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.staging = Path(self.tmp.name) / "staging"

    def tearDown(self):
        self.tmp.cleanup()

    def fixture(self):
        return [
            {"update_id": 1, "channel_post": {
                "message_id": 101, "chat": {"id": -1001, "title": "TestChan"},
                "date": 1761400000, "text": "Headline one\n\nBody of post one."}},
            {"update_id": 2, "channel_post": {
                "message_id": 102, "chat": {"id": -1001, "title": "TestChan"},
                "date": 1761400060, "text": "Headline two\n\nBody of post two."}},
            {"update_id": 3, "channel_post": {  # media-only: no text
                "message_id": 103, "chat": {"id": -1001, "title": "TestChan"},
                "date": 1761400120, "photo": [{"file_id": "abc"}]}},
            {"update_id": 4, "channel_post": {  # duplicate of 101
                "message_id": 101, "chat": {"id": -1001, "title": "TestChan"},
                "date": 1761400000, "text": "Headline one\n\nBody of post one."}},
            {"update_id": 5, "message": {"message_id": 201, "text": "not a channel post"}},
        ]

    def test_staging_and_dedup(self):
        report = channel_reader.process_updates(
            self.fixture(), self.staging)
        self.assertEqual(sorted(report["staged_new"]), ["channel-101", "channel-102"])
        self.assertEqual(len(report["skipped"]), 3)  # media-only, duplicate, non-channel
        item = json.loads((self.staging / "channel-101.json").read_text(encoding="utf-8"))
        self.assertEqual(item["idempotency_key"], "channel:101")
        self.assertEqual(item["channel_message_id"], 101)
        self.assertEqual(item["title"], "Headline one")
        # Second run: nothing new, existing files counted as updated.
        report2 = channel_reader.process_updates(self.fixture(), self.staging)
        self.assertEqual(report2["staged_new"], [])
        self.assertEqual(sorted(report2["staged_updated"]), ["channel-101", "channel-102"])
        self.assertEqual(len(list(self.staging.glob("channel-*.json"))), 2)

    def test_edited_post_updates_same_file(self):
        channel_reader.process_updates(self.fixture(), self.staging)
        edited = [{"update_id": 9, "edited_channel_post": {
            "message_id": 101, "chat": {"id": -1001, "title": "TestChan"},
            "date": 1761400000, "text": "Headline one EDITED\n\nNew body."}}]
        report = channel_reader.process_updates(edited, self.staging)
        self.assertEqual(report["staged_updated"], ["channel-101"])
        item = json.loads((self.staging / "channel-101.json").read_text(encoding="utf-8"))
        self.assertEqual(item["title"], "Headline one EDITED")

    def test_edge_index_dedup(self):
        fake_edge = Path(self.tmp.name) / "edge"
        fake_edge.mkdir()
        (fake_edge / "index.json").write_text(json.dumps({
            "entries": [{"channel_message_id": 101},
                        {"channel_message_id": None}]}), encoding="utf-8")
        old = channel_reader.EDGE_OUT
        channel_reader.EDGE_OUT = fake_edge
        try:
            report = channel_reader.process_updates(self.fixture(), self.staging)
        finally:
            channel_reader.EDGE_OUT = old
        self.assertEqual(report["staged_new"], ["channel-102"])
        self.assertTrue(any(s.get("message_id") == 101 and "edge index" in s["reason"]
                            for s in report["skipped"]))

    def test_parse_shapes(self):
        self.assertEqual(len(channel_reader.parse_updates({"result": self.fixture()})), 5)
        self.assertEqual(len(channel_reader.parse_updates(self.fixture()[0])), 1)
        with self.assertRaises(ValueError):
            channel_reader.parse_updates({"foo": 1})


class TestControlCLI(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "q.db"
        index = json.loads((APP_ROOT / "edge" / "out" / "index.json").read_text(encoding="utf-8"))
        self.articles = []
        for entry in index["entries"]:
            if entry.get("kind") == "article" and entry.get("file") and len(self.articles) < 2:
                self.articles.append(APP_ROOT / "edge" / "out" / entry["file"])

    def tearDown(self):
        self.tmp.cleanup()

    def test_queue_walk_and_illegal_reject(self):
        art = str(self.articles[0])
        r = run_cli(self.db, "enqueue", art)
        self.assertEqual(r.returncode, 0, r.stderr)
        article = json.loads(Path(art).read_text(encoding="utf-8"))
        key = f"article:{article['id']}"
        for cmd in ("submit", "approve", "schedule", "mark-app"):
            r = run_cli(self.db, cmd, key)
            self.assertEqual(r.returncode, 0, f"{cmd}: {r.stderr}")
        # approve from published_app is illegal.
        r = run_cli(self.db, "approve", key)
        self.assertEqual(r.returncode, 1)
        # reject only from review: enqueue a second item, submit, reject.
        art2 = str(self.articles[1])
        r = run_cli(self.db, "enqueue", art2)
        self.assertEqual(r.returncode, 0, r.stderr)
        article2 = json.loads(Path(art2).read_text(encoding="utf-8"))
        key2 = f"article:{article2['id']}"
        r = run_cli(self.db, "submit", key2)
        self.assertEqual(r.returncode, 0, r.stderr)
        r = run_cli(self.db, "reject", key2, "--note", "not for readers")
        self.assertEqual(r.returncode, 0, r.stderr)
        r = run_cli(self.db, "queue", "list", "--status", "rejected")
        self.assertIn(key2, r.stdout)
        # hold only in review.
        r = run_cli(self.db, "hold", key, "--note", "wait")
        self.assertEqual(r.returncode, 1)

    def test_invite_lifecycle_hash_only(self):
        r = run_cli(self.db, "invites", "generate", "--label", "reader1")
        self.assertEqual(r.returncode, 0, r.stderr)
        code = [l for l in r.stdout.splitlines() if l.startswith("code ")][0].split(": ", 1)[1]
        self.assertTrue(code.startswith("EJ-"))
        r = run_cli(self.db, "invites", "verify", code)
        self.assertEqual(r.returncode, 0, r.stderr)
        # M7: verify output is "operator verified", never "reader seen".
        self.assertIn("operator verified", r.stdout)
        r = run_cli(self.db, "invites", "verify", code)
        self.assertIn("operator verified", r.stdout)
        # The code itself must NOT be stored: only its hash is.
        con = bridge_lib.init_db(self.db)
        rows = con.execute("SELECT code_hash, label FROM invites").fetchall()
        con.close()
        self.assertEqual(len(rows), 1)
        self.assertNotEqual(rows[0][0], code)
        self.assertNotIn(code, json.dumps(rows))
        r = run_cli(self.db, "invites", "revoke", "--label", "reader1")
        self.assertEqual(r.returncode, 0, r.stderr)
        r = run_cli(self.db, "invites", "verify", code)
        self.assertEqual(r.returncode, 1)
        self.assertIn("revoked", r.stderr)
        r = run_cli(self.db, "invites", "verify", "EJ-XXXX-XXXX-XXXX")
        self.assertEqual(r.returncode, 1)

    def test_health_runs(self):
        r = run_cli(self.db, "health")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("live_sends recorded: 0", r.stdout)
        self.assertIn("hash match: True", r.stdout)
        self.assertIn("unresolvable entry files: 0", r.stdout)


class TestM7(unittest.TestCase):
    """M7 (P2-17) gaps: split sending, captions, edited posts,
    duplicate record_sent, mark-app verification, cadence,
    allow-list, sending reconciliation, queue show, health safety."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "q.db"
        self.report = Path(self.tmp.name) / "report.json"
        self.staging = Path(self.tmp.name) / "staging"

    def tearDown(self):
        self.tmp.cleanup()

    def _queue_article(self, article, key):
        con = bridge_lib.init_db(self.db)
        ok, _ = bridge_lib.enqueue_item(con, key, article)
        self.assertTrue(ok)
        for target in ("review", "approved", "queued", "published_app"):
            ok, _ = bridge_lib.set_status(con, key, target)
            self.assertTrue(ok)
        con.close()

    def test_numbered_split_parts(self):
        paras = ["para %d " % i + "y" * 900 for i in range(6)]
        text = "Title\n\n" + "\n\n".join(paras) + "\n\nمنبع: S"
        parts = bridge_lib.numbered_split_parts(text)
        self.assertIsNotNone(parts)
        self.assertGreater(len(parts), 1)
        self.assertTrue(all(len(p) <= 4096 for p in parts))
        self.assertTrue(parts[0].startswith("(1/"))
        self.assertTrue(parts[-1].startswith(f"({len(parts)}/"))
        self.assertIn("Title", parts[0])
        self.assertIn("منبع: S", parts[-1])

    def test_split_send_records_all_message_ids(self):
        paras = ["para %d " % i + "y" * 900 for i in range(6)]
        article = {"id": "big-1", "title": "Big",
                   "body_blocks": paras, "source": "S",
                   "original_url": "https://example.test/x"}
        self._queue_article(article, "article:big-1")
        os.environ["BRIDGE_LIVE"] = "1"
        os.environ["BRIDGE_CHANNEL_ID"] = "test-channel-123"
        os.environ["BRIDGE_CHANNEL_ALLOWLIST"] = "test-channel-123"
        os.environ["ECO_BOT_TOKEN"] = "DUMMY-NOT-REAL"
        try:
            ids = iter([91001, 91002])
            code, report = publish_worker.run_worker(
                db_path=self.db, live=True, report_file=self.report,
                sender=lambda text, chat: next(ids))
            self.assertEqual(code, 0)
            self.assertEqual(report["live_sends"], 1)
            self.assertEqual(len(report["sent"][0]["message_ids"]), 2)
            con = bridge_lib.init_db(self.db)
            row = con.execute("SELECT message_ids FROM sent WHERE key = ?",
                              ("article:big-1",)).fetchone()
            self.assertEqual(json.loads(row[0]), [91001, 91002])
            con.close()
        finally:
            for var in ("BRIDGE_LIVE", "BRIDGE_CHANNEL_ID", "ECO_BOT_TOKEN",
                        "BRIDGE_CHANNEL_ALLOWLIST"):
                os.environ.pop(var, None)

    def test_caption_post_stages_with_media_note(self):
        updates = [{"update_id": 1, "channel_post": {
            "message_id": 301, "chat": {"id": -1001, "title": "TestChan"},
            "date": 1761400000, "caption": "Cap headline\n\nCap body.",
            "photo": [{"file_id": "abc"}]}}]
        report = channel_reader.process_updates(updates, self.staging)
        self.assertEqual(report["staged_new"], ["channel-301"])
        item = json.loads((self.staging / "channel-301.json").read_text(encoding="utf-8"))
        self.assertTrue(item["has_media"])
        self.assertIn("media_note", item)
        self.assertEqual(item["title"], "Cap headline")

    def test_edited_post_updates_queue_and_records_after_send(self):
        updates = [{"update_id": 1, "channel_post": {
            "message_id": 401, "chat": {"id": -1001, "title": "TestChan"},
            "date": 1761400000, "text": "Orig\n\nBody."}}]
        channel_reader.process_updates(updates, self.staging, enqueue=True,
                                       db_path=self.db)
        con = bridge_lib.init_db(self.db)
        item = bridge_lib.get_item(con, "channel:401")
        self.assertEqual(item["status"], "review")
        con.close()
        edited = [{"update_id": 2, "edited_channel_post": {
            "message_id": 401, "chat": {"id": -1001, "title": "TestChan"},
            "date": 1761400000, "text": "Edited\n\nNew body."}}]
        channel_reader.process_updates(edited, self.staging, enqueue=True,
                                       db_path=self.db)
        con = bridge_lib.init_db(self.db)
        item = bridge_lib.get_item(con, "channel:401")
        self.assertEqual(item["title"], "Edited")
        self.assertEqual(item["status"], "review")  # status untouched
        # Move to sent, then edit again: must be an edit record.
        for target in ("approved", "queued", "published_app", "sending",
                       "published_channel"):
            ok, _ = bridge_lib.set_status(con, "channel:401", target)
            self.assertTrue(ok)
        con.close()
        channel_reader.process_updates(edited, self.staging, enqueue=True,
                                       db_path=self.db)
        con = bridge_lib.init_db(self.db)
        item = bridge_lib.get_item(con, "channel:401")
        self.assertEqual(item["title"], "Edited")  # payload not overwritten
        edits = con.execute("SELECT COUNT(*) FROM edits WHERE key = ?",
                            ("channel:401",)).fetchone()[0]
        self.assertEqual(edits, 1)
        con.close()

    def test_record_sent_duplicate_is_noop(self):
        article = {"id": "dup-1", "title": "T", "body_blocks": ["b"], "source": "S"}
        self._queue_article(article, "article:dup-1")
        con = bridge_lib.init_db(self.db)
        bridge_lib.set_status(con, "article:dup-1", "sending")
        ok, _ = bridge_lib.record_sent(con, "article:dup-1", [123])
        self.assertTrue(ok)
        ok, why = bridge_lib.record_sent(con, "article:dup-1", [124])
        self.assertFalse(ok)
        self.assertIn("duplicate", why)
        rows = con.execute("SELECT COUNT(*) FROM sent WHERE key = ?",
                           ("article:dup-1",)).fetchone()[0]
        self.assertEqual(rows, 1)
        con.close()

    def test_sending_item_not_resent_by_next_pass(self):
        article = {"id": "send-1", "title": "T", "body_blocks": ["b"], "source": "S"}
        self._queue_article(article, "article:send-1")
        con = bridge_lib.init_db(self.db)
        bridge_lib.set_status(con, "article:send-1", "sending")
        con.close()
        code, report = publish_worker.run_worker(
            db_path=self.db, live=False, report_file=self.report)
        self.assertEqual(code, 0)
        self.assertTrue(any("reconciliation" in b["reason"]
                            for b in report["blocked"]))

    def test_channel_allowlist_and_cadence(self):
        os.environ.pop("BRIDGE_CHANNEL_ALLOWLIST", None)
        ok, why = bridge_lib.check_channel_id("123")
        self.assertFalse(ok)
        self.assertIn("allow-list", why)
        os.environ["BRIDGE_CHANNEL_ALLOWLIST"] = "123,456"
        try:
            ok, _ = bridge_lib.check_channel_id("123")
            self.assertTrue(ok)
            ok, _ = bridge_lib.check_channel_id("999")
            self.assertFalse(ok)
        finally:
            os.environ.pop("BRIDGE_CHANNEL_ALLOWLIST", None)
        con = bridge_lib.init_db(self.db)
        self.assertTrue(bridge_lib.cadence_ok(con)[0])
        for i in range(2):
            con.execute("INSERT INTO sent (key, message_ids, channel_configured, sent_at)"
                        " VALUES (?,?,1,?)",
                        (f"k{i}", "[1]", bridge_lib.now_iso()))
        con.commit()
        self.assertFalse(bridge_lib.cadence_ok(con)[0])
        con.close()

    def test_mark_app_verifies_edge_entry(self):
        # Real edge article: mark-app must succeed (entry + hash match).
        index = json.loads((APP_ROOT / "edge" / "out" / "index.json").read_text(encoding="utf-8"))
        art_file = None
        for entry in index["entries"]:
            if entry.get("kind") == "article" and entry.get("file"):
                art_file = APP_ROOT / "edge" / "out" / entry["file"]
                break
        r = run_cli(self.db, "enqueue", str(art_file))
        self.assertEqual(r.returncode, 0, r.stderr)
        article = json.loads(Path(art_file).read_text(encoding="utf-8"))
        key = f"article:{article['id']}"
        for cmd in ("submit", "approve", "schedule"):
            r = run_cli(self.db, cmd, key)
            self.assertEqual(r.returncode, 0, f"{cmd}: {r.stderr}")
        r = run_cli(self.db, "mark-app", key)
        self.assertEqual(r.returncode, 0, f"mark-app: {r.stderr}")
        # Fake article: no edge entry -> refused, status does not move.
        fake = {"id": "no-such-article", "title": "Fake",
                "body_blocks": ["b"], "source": "S"}
        con = bridge_lib.init_db(self.db)
        bridge_lib.enqueue_item(con, "article:no-such-article", fake)
        for target in ("review", "approved", "queued"):
            bridge_lib.set_status(con, "article:no-such-article", target)
        con.close()
        r = run_cli(self.db, "mark-app", "article:no-such-article")
        self.assertEqual(r.returncode, 1)
        self.assertIn("not found in the new edge index", r.stderr)

    def test_queue_show_body_and_retire_lab(self):
        art = real_article()
        con = bridge_lib.init_db(self.db)
        bridge_lib.enqueue_item(con, f"article:{art['id']}", art)
        con.execute("INSERT INTO invites (code_hash, label, state, created_at)"
                    " VALUES ('h1', 'lab-1', 'unused', '2026-01-01')")
        con.commit()
        con.close()
        r = run_cli(self.db, "queue", "show", f"article:{art['id']}")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("body:", r.stdout)
        self.assertIn("original_url:", r.stdout)
        self.assertIn("provenance:", r.stdout)
        r = run_cli(self.db, "invites", "retire-lab")
        self.assertEqual(r.returncode, 0, r.stderr)
        con = bridge_lib.init_db(self.db)
        states = con.execute("SELECT DISTINCT state FROM invites").fetchall()
        con.close()
        self.assertEqual(states, [("retired",)])

    def test_promote_writes_edge_entry(self):
        import control as control_mod
        tmp_root = Path(self.tmp.name) / "approot"
        (tmp_root / "bridge" / "staging").mkdir(parents=True)
        (tmp_root / "edge").mkdir(parents=True)
        staging_item = {"id": "channel-777", "title": "Promoted",
                        "summary": "sum", "body_blocks": ["Body text."],
                        "category": "channel", "category_fa": "کانال",
                        "source": "TestChan", "original_url": "",
                        "published_at": "2026-10-25T00:00:00+03:30",
                        "channel_message_id": 777,
                        "provenance": "test"}
        (tmp_root / "bridge" / "staging" / "channel-777.json").write_text(
            json.dumps(staging_item), encoding="utf-8")
        old_root, old_edge = control_mod.APP_ROOT, control_mod.EDGE_OUT
        control_mod.APP_ROOT, control_mod.EDGE_OUT = tmp_root, tmp_root / "edge" / "out"
        try:
            class Args:  # minimal argparse namespace
                staging_id = "channel-777"
            con = bridge_lib.init_db(self.db)
            rc = control_mod.cmd_promote(con, Args())
            con.close()
            self.assertEqual(rc, 0)
            index = json.loads((tmp_root / "edge" / "out" / "index.json").read_text(encoding="utf-8"))
            entry = [e for e in index["entries"] if e["id"] == "channel-777"]
            self.assertEqual(len(entry), 1)
            self.assertTrue(entry[0]["app_key"].startswith("a-"))
            self.assertTrue(entry[0]["content_hash"])
        finally:
            control_mod.APP_ROOT, control_mod.EDGE_OUT = old_root, old_edge


if __name__ == "__main__":
    unittest.main(verbosity=2)
