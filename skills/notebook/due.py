#!/usr/bin/env python3
"""Deadline radar: dated bullets in the weekly notebook that fall due soon.

Reads notebook/????-Www.md (last 10 weeks) and prints ONLY lines whose date lands within
[today, today+WINDOW]. Like read.py it never dumps a weekly; it emits a few capped lines
for the morning brief (`== DUE ==`). Fail-open: no notebook -> SKIP, never an exception.

A bullet counts when it carries a date on/after the day it was written (so "ordered 3 Oct"
in an entry from 5 Oct is history, "return window closes 18 October" is a deadline).
Dates understood: 2026-10-18 | 18 Oct(ober) [2026] | Oct(ober) 18 | "Tue 15th" (weekday+day).
Why-it-matters lines are skipped; entries whose latest Next says "No follow-up" are skipped.

STRUCTURED FIRST: a `- Due: 2026-11-04 · label` bullet (written by `add.py --due`, resolved by the clock, never by the
model) is an explicit deadline. An entry that has one is read ONLY from those bullets (no prose guessing, and a
"No follow-up" Next does not hide it); past dates expire automatically after their day passes. The prose scan above remains
the fallback for older entries that have no Due bullet.

Usage: due.py [--today YYYY-MM-DD] [--window 14] [--dir notebook/]
"""
import argparse, datetime as dt, glob, os, re, sys

MON = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}
DOW = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
ISO = re.compile(r"\b(20\d\d)-(\d\d)-(\d\d)\b")
DMY = re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:\s+(20\d\d))?\b", re.I)
MDY = re.compile(r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(20\d\d))?", re.I)
WD = re.compile(r"\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b", re.I)
HEAD = re.compile(r"^##\s+\w{3}\s+(\d{4}-\d\d-\d\d)")
# Agent working notes, not user deadlines: sweep logs and playbook lessons
# carry dates ("priced at 625 on 27 Oct") that are history, not commitments.
SKIP_TOPICS = {"catalyst-watch", "playbook-lessons"}
STRUCT = re.compile(r"^Due:\s*(\d{4}-\d\d-\d\d)(?:\s*·\s*(.+))?$")


def _d(y, m, d):
    try:
        return dt.date(y, m, d)
    except ValueError:
        return None


def dates_in(line, written):
    """Candidate dates in a line, resolved against the entry's written date."""
    out = []
    for m in ISO.finditer(line):
        x = _d(int(m[1]), int(m[2]), int(m[3]))
        if x: out.append(x)
    spans = []
    for rx, order in ((DMY, "dm"), (MDY, "md")):
        for m in rx.finditer(line):
            if order == "dm": d, mo, y = int(m[1]), MON[m[2][:3].lower()], m[3]
            else: mo, d, y = MON[m[1][:3].lower()], int(m[2]), m[3]
            if m.start() in spans: continue
            spans.append(m.start())
            if y:
                x = _d(int(y), mo, d)
            else:  # no year: first occurrence on/after the entry date (within ~10 months)
                x = _d(written.year, mo, d)
                if x and x < written - dt.timedelta(days=60): x = _d(written.year + 1, mo, d)
            if x: out.append(x)
    for m in WD.finditer(line):
        want, dom = DOW.index(m[1][:3].lower()), int(m[2])
        if m.start() in spans or not 1 <= dom <= 31: continue
        for k in range(0, 95):  # next date on/after written whose weekday and day-of-month match
            x = written + dt.timedelta(days=k)
            if x.weekday() == want and x.day == dom: out.append(x); break
    return out


def entries(text):
    cur = None
    for raw in text.splitlines():
        m = HEAD.match(raw)
        if m:
            if cur: yield cur
            cur = {"written": dt.date.fromisoformat(m[1]), "title": "", "topic": "", "lines": [], "nofollow": False, "due": []}
            continue
        if cur is None: continue
        s = raw.strip()
        if s.startswith("Topic:"): cur["topic"] = s[6:].strip()
        elif s.startswith("- "):
            body = s[2:]
            sm = STRUCT.match(body)
            if sm:
                try: cur["due"].append((dt.date.fromisoformat(sm[1]), (sm[2] or "").strip()))
                except ValueError: pass
                continue
            if body.lower().startswith("why it matters"): continue
            if body.lower().startswith("next") and re.search(r"no follow-?up", body, re.I): cur["nofollow"] = True
            elif body.lower().startswith("next"): cur["nofollow"] = False
            cur["lines"].append(body)
        elif s and not cur["title"] and not s.startswith(("Session:", "<!--")):
            cur["title"] = s.rstrip(".")
    if cur: yield cur


def scan(nbdir, today, window=14, weeks=10):
    files = sorted(glob.glob(os.path.join(nbdir, "????-W??.md")))[-weeks:]
    found = {}  # (topic, date) -> row ; later entries win
    struct = {}  # topic -> rows from structured Due bullets ; later entries replace
    for fp in files:
        try:
            text = open(fp, encoding="utf-8").read()
        except OSError:
            continue
        for e in entries(text):
            if e["due"]:
                # The latest entry of a topic owns its deadlines: re-dating in a later week replaces the old date.
                tkey = e["topic"] or e["title"]
                title = e["title"] or e["topic"]
                struct[tkey] = [((x - today).days, x, label or title, title) for x, label in e["due"]
                                if 0 <= (x - today).days <= window]
                continue
            if e["nofollow"]: continue
            if e["topic"] in SKIP_TOPICS: continue
            for body in e["lines"]:
                clean = re.sub(r"^\[hint\]\s*", "", body)
                for x in dates_in(clean, e["written"]):
                    n = (x - today).days
                    if x < e["written"] or not 0 <= n <= window: continue
                    found[(e["topic"] or e["title"], x)] = (n, x, clean[:140], e["title"] or e["topic"])
    for tkey, rows in struct.items():
        for row in rows:
            found[(tkey, row[1])] = row
    return sorted(found.values())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--today"); ap.add_argument("--window", type=int, default=14); ap.add_argument("--dir")
    a = ap.parse_args()
    today = dt.date.fromisoformat(a.today) if a.today else dt.date.today()
    root = os.environ.get("TERMINUS_DIR") or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    nb = a.dir or os.path.join(root, "notebook")
    print("== DUE ==")
    if not os.path.isdir(nb):
        print("SKIP|notebook not found - say nothing about deadlines"); return 0
    try:
        rows = scan(nb, today, a.window)
    except Exception as e:
        print(f"SKIP|deadline scan failed ({type(e).__name__})"); return 0
    if not rows:
        print(f"NONE|nothing dated in the next {a.window} days"); return 0
    print("fields: DUE|D-n|date|line|entry")
    for n, x, line, title in rows[:6]:
        print(f"DUE|{'D-%d' % n if n > 0 else 'TODAY'}|{x}|{line.replace('|', '/')}|{title.replace('|', '/')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
