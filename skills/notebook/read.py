#!/usr/bin/env python3
"""Gated notebook reader. The ONLY sanctioned way to read weeklies.

Usage:
  read.py <keyword>              # hint lines matching keyword (file:line + parent ## header)
  read.py --entry <N> <file>     # full Nth ## block of file (1-based, from a prior search)
  read.py --recent               # headers of the last 5 entries this week (no bodies)
  read.py --topic <slug>         # matching topic entry in the current week
  read.py --user                 # whole user.md headlines file (open, not gated)
  read.py --open                 # last 28 days: entries whose Next is not "No follow-up" (max 12)

Never dumps whole weeklies. Direct reads of notebook/*.md are denied by
opencode perms; this script is the interface. Writes go through add.py.
user.md is headlines only, so --user returns it wholly on request.
"""

import re
import sys
import datetime as dt
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parent


def is_terminus_root(path: Path) -> bool:
    return (
        (path / ".git").exists()
        and (path / "AGENTS.md").is_file()
        and (path / "skills" / "notebook").is_dir()
    )


REPO_ROOT = SKILL_DIR.parent.parent
if not is_terminus_root(REPO_ROOT):
    REPO_ROOT = next(
        (path for path in (Path.cwd(), *Path.cwd().parents) if is_terminus_root(path)),
        None,
    )
ENTRIES_DIR = REPO_ROOT / "notebook" if REPO_ROOT else None


def blocks(path: Path) -> list[tuple[str, list[str]]]:
    text = re.sub(r"(?s)<!--.*?-->", "", path.read_text())
    parts = re.split(r"(?m)^(?=## )", text)
    out = []
    for p in parts[1:]:
        lines = p.splitlines()
        out.append((lines[0].removeprefix("##").strip(), lines[1:]))
    return out


def cmd_search(kw: str) -> int:
    kw = kw.lower()
    hits = 0
    for f in sorted(ENTRIES_DIR.glob("????-W??.md"), reverse=True):
        for i, (head, body) in enumerate(blocks(f)):
            for ln, line in enumerate(body):
                if "[hint]" in line and kw in line.lower():
                    print(f"{f.name}#{i + 1} :: ## {head}")
                    print(f"  L{ln}: {line.strip()[:160]}")
                    hits += 1
    print(f"-- {hits} hint hit(s). Use: read.py --entry <N> <file> --")
    return 0


def cmd_entry(n: str, fname: str) -> int:
    f = ENTRIES_DIR / fname
    if not f.exists():
        print(f"ERROR no such file: {fname}", file=sys.stderr)
        return 1
    bl = blocks(f)
    try:
        head, body = bl[int(n) - 1]
    except (ValueError, IndexError):
        print(f"ERROR entry {n} out of range (1-{len(bl)})", file=sys.stderr)
        return 1
    print(f"## {head}")
    print("\n".join(body).rstrip())
    return 0


def cmd_recent() -> int:
    files = sorted(ENTRIES_DIR.glob("????-W??.md"), reverse=True)
    if not files:
        print("no weeklies")
        return 0
    bl = blocks(files[0])
    for i, (head, _) in enumerate(bl[-5:], start=max(1, len(bl) - 4)):
        print(f"{files[0].name}#{i} :: ## {head}")
    return 0


def cmd_topic(topic: str) -> int:
    year, week, _ = dt.date.today().isocalendar()
    fname = f"{year}-W{week:02d}.md"
    f = ENTRIES_DIR / fname
    if not f.exists():
        print("no current-week notebook")
        return 0
    hits = 0
    for index, (head, body) in enumerate(blocks(f), start=1):
        if f"Topic: {topic}" in {line.strip() for line in body}:
            print(f"{fname}#{index} :: ## {head}")
            hits += 1
    if not hits:
        print("no matching topic this week")
    return 0


OPEN_DAYS = 28
OPEN_MAX = 12


def recent_week(f: Path) -> bool:
    m = re.fullmatch(r"(\d{4})-W(\d{2})\.md", f.name)
    try:
        sunday = dt.date.fromisocalendar(int(m[1]), int(m[2]), 7)
    except (TypeError, ValueError):
        return False
    return (dt.date.today() - sunday).days <= OPEN_DAYS


def cmd_open() -> int:
    files = [f for f in sorted(ENTRIES_DIR.glob("????-W??.md"), reverse=True) if recent_week(f)]
    hits = 0
    for f in files:
        for index, (head, body) in reversed(list(enumerate(blocks(f), start=1))):
            lines = [line.strip() for line in body]
            nexts = [l.removeprefix("- Next:").strip() for l in lines if l.startswith("- Next:")]
            nexts = [n for n in nexts if n and n.rstrip(". ").lower() != "no follow-up"]
            if not nexts:
                continue
            if hits == OPEN_MAX:
                print(f"-- more than {OPEN_MAX} open; showing the newest --")
                return 0
            topic = next((l.removeprefix("Topic:").strip() for l in lines if l.startswith("Topic:")), "")
            print(f"{f.name}#{index} :: ## {head} :: {topic}")
            print(f"  Next: {' | '.join(nexts)[:200]}")
            hits += 1
    print(f"-- {hits} open follow-up(s). Use: read.py --entry <N> <file> --")
    return 0


def cmd_user() -> int:
    f = ENTRIES_DIR / "user.md"
    if not f.exists():
        print("ERROR no user.md", file=sys.stderr)
        return 1
    print(f.read_text().rstrip())
    return 0


def main() -> int:
    a = sys.argv[1:]
    if not a or a[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0
    if a[0] == "--entry":
        if len(a) != 3 or not a[1].isdigit() or int(a[1]) < 1:
            print("ERROR usage: read.py --entry <positive-number> <YYYY-Www.md>", file=sys.stderr)
            return 2
        if not re.fullmatch(r"\d{4}-W\d{2}\.md", a[2]):
            print("ERROR entry file must be a weekly notebook filename", file=sys.stderr)
            return 2
    elif a[0] == "--topic":
        if len(a) != 2 or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", a[1]):
            print("ERROR usage: read.py --topic <lowercase-topic-slug>", file=sys.stderr)
            return 2
    elif a[0] == "--recent":
        if len(a) != 1:
            print("ERROR usage: read.py --recent", file=sys.stderr)
            return 2
    elif a[0] == "--user":
        if len(a) != 1:
            print("ERROR usage: read.py --user", file=sys.stderr)
            return 2
    elif a[0] == "--open":
        if len(a) != 1:
            print("ERROR usage: read.py --open", file=sys.stderr)
            return 2
    elif a[0].startswith("-") and a[0] not in ("user", "user.md"):
        print(f"ERROR unknown option: {a[0]}", file=sys.stderr)
        return 2
    if ENTRIES_DIR is None:
        print(
            "ERROR: no Terminus repo found; run the notebook helper from the Terminus repo.",
            file=sys.stderr,
        )
        return 2
    if a[0] == "--entry" and len(a) == 3:
        return cmd_entry(a[1], a[2])
    if a[0] == "--recent":
        return cmd_recent()
    if a[0] == "--open":
        return cmd_open()
    if a[0] == "--topic":
        return cmd_topic(a[1])
    if a[0] == "--user" or " ".join(a).strip().lower() in ("user", "user.md"):
        if len(a) != 1:
            print("ERROR usage: read.py --user", file=sys.stderr)
            return 2
        return cmd_user()
    return cmd_search(" ".join(a))


if __name__ == "__main__":
    sys.exit(main())
