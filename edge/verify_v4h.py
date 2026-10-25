#!/usr/bin/env python3
"""v4-H lab verifier (no build): brace balance, strings, semantics
sweep (brace-matched), bar-rule and gating invariants. Run from
app/ root: python3 edge/verify_v4h.py"""
import re, subprocess, sys, xml.etree.ElementTree as ET
ROOT = 'app/src/main/java/com/ecojournal/app/'
FILES = ['ui/components/Masthead.kt', 'ui/components/SyncCompact.kt',
 'ui/components/ReaderHelpers.kt', 'ui/components/ArticleCard.kt',
 'ui/screens/FeedScreen.kt', 'ui/screens/ArticleScreen.kt',
 'ui/screens/SettingsScreen.kt', 'data/TickerTopics.kt',
 'data/Phase2Domain.kt', 'data/SettingsStore.kt', 'data/AppDatabase.kt',
 'MainActivity.kt']
def read(p): return open(ROOT + p, encoding='utf-8').read()
def balance(path):
    s = read(path); i = 0; stack = []; state = None
    while i < len(s):
        c = s[i]
        if state == 'line':
            if c == '\n': state = None
        elif state == 'block':
            if c == '*' and i + 1 < len(s) and s[i+1] == '/': state = None; i += 1
        elif state == 'str':
            if c == '\\': i += 1
            elif c == '"': state = None
        elif state == 'char':
            if c == '\\': i += 1
            elif c == "'": state = None
        else:
            if c == '/' and i + 1 < len(s) and s[i+1] == '/': state = 'line'
            elif c == '/' and i + 1 < len(s) and s[i+1] == '*': state = 'block'; i += 1
            elif c == '"': state = 'str'
            elif c == "'": state = 'char'
            elif c in '([{': stack.append(c)
            elif c in ')]}':
                o = {')': '(', ']': '[', '}': '{'}[c]
                assert stack and stack[-1] == o, f"{path}: mismatch {c}"
                stack.pop()
        i += 1
    assert not stack, f"{path}: unclosed {stack}"
def block(s, start):
    depth = 0; i = start; instr = False; esc = False
    while i < len(s):
        c = s[i]
        if instr:
            if esc: esc = False
            elif c == '\\': esc = True
            elif c == '"': instr = False
        else:
            if c == '"': instr = True
            elif c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0: return s[start+1:i]
        i += 1
    return ""
for f in FILES: balance(f)
print("balance OK:", len(FILES), "files")
tree = ET.parse('app/src/main/res/values/strings.xml')
names = [e.get('name') for e in tree.getroot().findall('string')]
assert len(names) == len(set(names)), "duplicate strings"
for n in ['ticker_empty', 'ticker_desc', 'ticker_toggle', 'sync_compact_up_to_date',
          'sync_compact_failed', 'sync_details', 'reading_ruler_toggle',
          'calm_mode_toggle', 'story_arc_title', 'reader_helpers_title']:
    assert n in names, f"missing string {n}"
print("strings OK:", len(names))
for f in FILES:
    s = read(f)
    for m in re.finditer(r'\.semantics\s*\{', s):
        assert 'stringResource' not in block(s, m.end() - 1), \
            f"stringResource inside semantics {f}"
print("semantics sweep OK")
feed = read('ui/screens/FeedScreen.kt')
assert 'Masthead(' in feed and 'CompactSyncRow(' in feed and 'typedFormatFor' in feed
art = read('ui/components/ArticleCard.kt')
assert 'typedFormatType' in art and 'forArticleWithTyped' in art
assert art.count('label = "انرژی"') == 0
t = read('data/TickerTopics.kt')
assert 'nightLawResult == "instant"' in t and 'MAX_TOPICS = 10' in t
st = subprocess.check_output(['git', 'status', '--short']).decode()
for banned in ['GatedSectionsScreen', 'VoicesScreen', 'SpecialIssuesScreen', 'publish_edge']:
    assert banned not in st, f"gated/publish touched: {banned}"
print("bar-rule + gating checks OK")
print("v4-H verifier: ALL PASS")
