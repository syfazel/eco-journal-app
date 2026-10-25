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
        status = "draft"
        for target in ("review", "approved", "queued", "published_app", "published_channel"):
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
        for var in ("BRIDGE_LIVE", "BRIDGE_CHANNEL_ID", "ECO_BOT_TOKEN"):
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
        os.environ["ECO_BOT_TOKEN"] = "DUMMY-NOT-REAL"
        try:
            calls = []
            code, report = publish_worker.run_worker(
                db_path=self.db, live=True, report_file=self.report,
                sender=lambda text, chat: (calls.append((text, chat)), 999001)[1])
            self.assertEqual(code, 0)
            self.assertEqual(report["live_sends"], 1)
            self.assertEqual(report["sent"][0]["message_id"], 999001)
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
            for var in ("BRIDGE_LIVE", "BRIDGE_CHANNEL_ID", "ECO_BOT_TOKEN"):
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
        self.assertIn("activated", r.stdout)
        r = run_cli(self.db, "invites", "verify", code)
        self.assertIn("active", r.stdout)
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
