#!/usr/bin/env python3
"""Fetch recent posts from Ansarullah Telegram channels (t.me/s pages).

Stdlib only: urllib + html.parser. No API keys, no login. Plain GET
with a browser User-Agent — the public t.me/s pages serve the most
recent ~20 posts per channel as static HTML.

Channels (Ansarullah / official, per the Yemen-app sourcing rule):
  - army21ye        — Yahya Saree, spokesperson of the Yemeni Armed
                      Forces (Sanaa)
  - MilitaryMediaY  — Yemeni Military Media
  - Almasirah_En    — Al-Masirah English

Output: a JSON object
  {"fetched_at_utc": ISO, "items": [...], "unavailable": [...]}
where each item is
  {"id": "army21ye:4093", "channel": "army21ye",
   "channel_title_fa": "...", "author_fa": "...",
   "published_at": "2026-11-16T15:58:53+00:00",
   "text_fa": "<Arabic original text, verbatim>",
   "url": "https://t.me/army21ye/4093",
   "tier": "ansarullah-official"}

Only real fetched posts are ever emitted — never invented, never
summarised into fake quotes. A post without any text (bare photo /
video with no caption) is skipped. Items are deduplicated by URL and
capped at MAX_POSTS per channel (most recent first in file order,
i.e. the page's own chronological order).

A channel that fails to fetch or parse is reported in "unavailable"
with the error string; the run still exits 0 so generation degrades
gracefully instead of failing.

Usage:
  python3 edge/fetch_feeds.py [--out PATH] [--max-posts N]
  python3 edge/fetch_feeds.py --check-only
"""

import argparse
import html as html_mod
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser

CHANNELS = [
    {
        "channel": "army21ye",
        "channel_title_fa": "یحیی سریع — سخنگوی نیروهای مسلح یمن",
        "author_fa": "یحیی سریع",
    },
    {
        "channel": "MilitaryMediaY",
        "channel_title_fa": "رسانهٔ نظامی یمن",
        "author_fa": "رسانهٔ نظامی یمن",
    },
    {
        "channel": "Almasirah_En",
        "channel_title_fa": "المسیره انگلیسی",
        "author_fa": "المسیره",
    },
]

MAX_POSTS = 20
TIMEOUT_SECONDS = 30
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)

_MESSAGE_OPEN = re.compile(
    r'<div class="tgme_widget_message\b[^"]*" data-post="([^"]+)"', re.S)
_TIME_RE = re.compile(r'<time datetime="([^"]+)"')
_TEXT_OPEN = re.compile(
    r'<div class="tgme_widget_message_text[^"]*"[^>]*>', re.S)


class _TextStripper(HTMLParser):
    """Strip tags from a message-text fragment; <br> becomes newline."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "br":
            self.parts.append("\n")

    def handle_data(self, data):
        self.parts.append(data)

    def text(self):
        # Collapse 3+ newlines, strip leading/trailing blank lines,
        # but otherwise keep the author's text verbatim.
        out = "".join(self.parts)
        out = re.sub(r"\n{3,}", "\n\n", out)
        return out.strip()


def _strip_text(fragment):
    parser = _TextStripper()
    parser.feed(fragment)
    parser.close()
    return parser.text()


def parse_channel_page(html_text, channel):
    """Parse one t.me/s/<channel> page into a list of raw post dicts
    (oldest first, in page order). Raises ValueError on bad input."""
    blocks = _MESSAGE_OPEN.split(html_text)
    # split() yields: [pre, post1, block1, post2, block2, ...]
    posts = []
    for i in range(1, len(blocks), 2):
        post_ref = blocks[i]
        block = blocks[i + 1]
        m_time = _TIME_RE.search(block)
        if not m_time:
            continue
        published_at = m_time.group(1)
        m_text = _TEXT_OPEN.search(block)
        text = ""
        if m_text:
            start = m_text.end()
            end = block.find("</div>", start)
            if end == -1:
                continue
            text = _strip_text(block[start:end])
        if not text:
            continue  # media-only post with no caption: skip
        try:
            _chan, post_id = post_ref.split("/", 1)
        except ValueError:
            continue
        posts.append({
            "id": "%s:%s" % (channel, post_id),
            "channel": channel,
            "published_at": published_at,
            "text_fa": html_mod.unescape(text),
            "url": "https://t.me/%s/%s" % (channel, post_id),
            "tier": "ansarullah-official",
        })
    return posts


def fetch_channel_page(channel, timeout=TIMEOUT_SECONDS):
    url = "https://t.me/s/" + channel
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise IOError("HTTP %s for %s" % (resp.status, url))
        raw = resp.read()
    return raw.decode("utf-8", errors="replace")


def fetch_all(max_posts=MAX_POSTS, timeout=TIMEOUT_SECONDS):
    """Fetch every channel. Returns the feeds object; never raises
    for a per-channel failure (recorded under "unavailable")."""
    items = []
    unavailable = []
    seen_urls = set()
    for spec in CHANNELS:
        channel = spec["channel"]
        try:
            page = fetch_channel_page(channel, timeout=timeout)
            posts = parse_channel_page(page, channel)
            if not posts:
                raise ValueError("no text posts parsed from t.me/s/%s"
                                 % channel)
            for post in posts[-max_posts:]:
                if post["url"] in seen_urls:
                    continue
                seen_urls.add(post["url"])
                post["channel_title_fa"] = spec["channel_title_fa"]
                post["author_fa"] = spec["author_fa"]
                items.append(post)
        except Exception as exc:  # noqa: BLE001 — degrade, don't fail
            unavailable.append({
                "channel": channel,
                "channel_title_fa": spec["channel_title_fa"],
                "available": False,
                "error": "%s: %s" % (type(exc).__name__, exc),
            })
    items.sort(key=lambda p: (p["published_at"], p["url"]))
    return {
        "fetched_at_utc": datetime.now(timezone.utc).isoformat(),
        "items": items,
        "unavailable": unavailable,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None,
                    help="write feeds JSON here (default: stdout)")
    ap.add_argument("--max-posts", type=int, default=MAX_POSTS)
    ap.add_argument("--check-only", action="store_true",
                    help="fetch and print counts, write nothing")
    args = ap.parse_args()

    feeds = fetch_all(max_posts=args.max_posts)
    counts = {}
    for it in feeds["items"]:
        counts[it["channel"]] = counts.get(it["channel"], 0) + 1
    for spec in CHANNELS:
        ch = spec["channel"]
        if ch in counts:
            stamps = [it["published_at"] for it in feeds["items"]
                      if it["channel"] == ch]
            print("%s: %d posts, oldest %s, newest %s"
                  % (ch, counts[ch], min(stamps), max(stamps)))
        else:
            err = next((u["error"] for u in feeds["unavailable"]
                        if u["channel"] == ch), "unknown")
            print("%s: UNAVAILABLE — %s" % (ch, err))
    print("total items: %d, unavailable channels: %d"
          % (len(feeds["items"]), len(feeds["unavailable"])))
    if args.check_only:
        return 0
    text = json.dumps(feeds, ensure_ascii=False, indent=1) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote:", args.out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
