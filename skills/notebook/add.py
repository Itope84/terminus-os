#!/usr/bin/env python3
"""Create or merge a structured topic entry in this week's plaintext notebook."""

import argparse
import datetime as dt
import json
import re
import sys
import urllib.request
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
TEMPLATE = SKILL_DIR / "template.md"


def week_bounds(now: dt.datetime) -> tuple[str, str, str]:
    year, week, _ = now.isocalendar()
    monday = now - dt.timedelta(days=now.weekday())
    sunday = monday + dt.timedelta(days=6)
    tag = f"{year}-W{week:02d}"
    return tag, monday.strftime("%Y-%m-%d"), sunday.strftime("%Y-%m-%d")


def resolve_session_title(session_id: str) -> str:
    """Look up a live chat title for a bare session ID via the local server.

    Reads the bearer token from its file; the value is only ever sent to
    localhost and never printed. Raises ValueError when the title cannot
    be resolved, so the caller passes the full form instead of inventing one.
    """
    from os.path import expanduser
    try:
        token = Path(expanduser("~/.config/terminus/browser/token")).read_text().strip()
    except OSError:
        raise ValueError(f"cannot resolve title for {session_id}: no bearer token file")
    request = urllib.request.Request(
        "http://127.0.0.1:9009/api/sessions",
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.load(response)
    except Exception as exc:
        raise ValueError(f"cannot resolve title for {session_id}: server unreachable ({exc})")
    sessions = payload.get("sessions", payload if isinstance(payload, list) else [])
    for entry in sessions:
        if str(entry.get("id", "")) == session_id:
            title = str(entry.get("title", "")).strip()
            if title:
                return f"{session_id} · {title}"
    raise ValueError(
        f"cannot resolve title for {session_id}: not in live sessions; "
        'pass --session "ses_<id> · <title>" explicitly'
    )


WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
WEEKDAY_INDEX = {**{w: i for i, w in enumerate(WEEKDAYS)},
                 **{w: i for i, w in enumerate(["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"])}}
DUE_LINE = re.compile(r"^(\d{4}-\d{2}-\d{2})(?: · (.+))?$")


def resolve_due(spec: str, today: dt.date) -> tuple[dt.date, str]:
    """Turn one --due value into (date, label). The CLOCK does the date arithmetic, never the model.

    Forms: "2026-11-04", "+3d", "+2w", "today", "tomorrow", "wed" (the next Wednesday after today).
    An optional label follows a colon: "wed: Return Pixel 7". Past dates and anything else are refused,
    so a deadline can only be stored if it is a real calendar day from today onwards.
    """
    raw = spec.strip()
    when, _, label = raw.partition(":")
    when, label = when.strip().lower(), label.strip()
    if "\n" in raw or "\r" in raw:
        raise ValueError("--due must be a single line")
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", when):
        try:
            day = dt.date.fromisoformat(when)
        except ValueError:
            raise ValueError(f"--due {when!r} is not a real calendar date")
    elif when == "today":
        day = today
    elif when == "tomorrow":
        day = today + dt.timedelta(days=1)
    elif (m := re.fullmatch(r"\+(\d{1,3})([dw])", when)):
        day = today + dt.timedelta(days=int(m[1]) * (7 if m[2] == "w" else 1))
    elif when in WEEKDAY_INDEX:
        ahead = (WEEKDAY_INDEX[when] - today.weekday()) % 7 or 7
        day = today + dt.timedelta(days=ahead)
    else:
        raise ValueError(
            f"--due {when!r} not understood: use YYYY-MM-DD, +3d, +2w, today, tomorrow or a weekday name "
            "(a weekday means the next one after today)")
    if day < today:
        raise ValueError(f"--due {day.isoformat()} is in the past (today is {today.isoformat()})")
    if (day - today).days > 800:
        raise ValueError(f"--due {day.isoformat()} is more than two years away: check the year")
    if "·" in label:
        raise ValueError("--due label must not contain '·'")
    return day, label


def due_line(day: dt.date, label: str) -> str:
    return f"{day.isoformat()} · {label}" if label else day.isoformat()


def describe_due(day: dt.date, label: str, today: dt.date) -> str:
    n = (day - today).days
    rel = "today" if n == 0 else "tomorrow" if n == 1 else f"in {n} days"
    return f"{day.strftime('%a %-d %b %Y')} ({rel})" + (f" — {label}" if label else "")


def build_entry(
    title: str,
    session: str,
    topic: str,
    facts: list[str],
    why: list[str],
    next_steps: list[str],
    hints: list[str],
    dues: list[str] | None = None,
    today: dt.date | None = None,
) -> str:
    fields = {"fact": facts, "why": why, "next": next_steps}
    resolved = [resolve_due(d, today or dt.date.today()) for d in (dues or [])]
    if re.match(r"^#{2,}\s", title.lstrip()):
        raise ValueError("title must not start with a Markdown heading marker")
    if not title.strip() or "\n" in title or "\r" in title:
        raise ValueError("title must be a non-empty single line")
    if not session.strip() or "\n" in session or "\r" in session:
        raise ValueError("session must be a non-empty single line")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", topic):
        raise ValueError("topic must be a stable lowercase slug, e.g. router-reboot-home")
    for name, values in fields.items():
        if not values:
            raise ValueError(f"provide at least one --{name} bullet")
        for value in values:
            if not value.strip() or "\n" in value or "\r" in value:
                raise ValueError(f"each --{name} value must be a non-empty single line")
    all_values = [value for values in fields.values() for value in values]
    if any("[hint]" in value.lower() for value in (title, session, *all_values)):
        raise ValueError("use --hint to mark searchable bullets; do not type [hint] manually")
    if session.strip() != "unknown":
        if re.fullmatch(r"ses_[A-Za-z0-9]+", session.strip()):
            session = resolve_session_title(session.strip())
        elif not re.fullmatch(r"ses_[A-Za-z0-9]+ · .+", session.strip()):
            raise ValueError('session must be "unknown", a bare "ses_<id>" (title is looked up), or "ses_<id> · <title>"')
    if not 1 <= len(hints) <= 2 or len(set(hints)) != len(hints):
        raise ValueError("provide one or two distinct --hint targets, e.g. fact:1")

    hint_targets: set[tuple[str, int]] = set()
    for hint in hints:
        section, separator, ordinal = hint.partition(":")
        if section != "fact" or not separator or not ordinal.isdigit() or int(ordinal) < 1:
            raise ValueError("hint target must be fact:N")
        target = (section, int(ordinal))
        if target[1] > len(fields[section]):
            raise ValueError(f"hint target {hint} does not identify a supplied bullet")
        hint_targets.add(target)
    if len(hint_targets) != len(hints):
        raise ValueError("hint targets must identify distinct bullets")

    lines = [title.strip(), f"Session: {session.strip()}", f"Topic: {topic}"]
    for index, value in enumerate(facts, start=1):
        marker = "[hint] " if ("fact", index) in hint_targets else ""
        lines.append(f"- {marker}{value.strip()}")
    lines.extend(f"- Why it matters: {value.strip()}" for value in why)
    lines.extend(f"- Next: {value.strip()}" for value in next_steps)
    lines.extend(f"- Due: {due_line(day, label)}" for day, label in resolved)
    return "\n".join(lines)


def visible_text(text: str) -> str:
    return re.sub(
        r"(?s)<!--.*?-->",
        lambda match: "".join("\n" if char == "\n" else " " for char in match.group()),
        text,
    )


def entry_spans(text: str) -> list[tuple[int, int]]:
    headers = list(re.finditer(r"(?m)^## .*$", visible_text(text)))
    return [
        (header.start(), headers[index + 1].start() if index + 1 < len(headers) else len(text))
        for index, header in enumerate(headers)
    ]


def parse_entry(block: str) -> dict[str, object]:
    lines = [line.strip() for line in block.splitlines()]
    if not lines or not lines[0].startswith("## "):
        raise ValueError("topic entry has no timestamp header")
    body = [line for line in lines[1:] if line]
    if len(body) < 2:
        raise ValueError("topic entry is incomplete")
    parsed: dict[str, object] = {
        "title": body[0], "sessions": [], "topic": None,
        "facts": [], "why": [], "next": [], "due": [],
    }
    for line in body[1:]:
        if line.startswith("Session: "):
            parsed["sessions"].append(line)
        elif line.startswith("Topic: "):
            if parsed["topic"] is not None:
                raise ValueError("topic entry has multiple Topic lines")
            parsed["topic"] = line.removeprefix("Topic: ")
        elif line.startswith("- [hint] "):
            parsed["facts"].append((line.removeprefix("- [hint] "), True))
        elif line.startswith("- Why it matters: "):
            parsed["why"].append(line.removeprefix("- Why it matters: "))
        elif line.startswith("- Next: "):
            parsed["next"].append(line.removeprefix("- Next: "))
        elif line.startswith("- Due: "):
            value = line.removeprefix("- Due: ")
            if not DUE_LINE.match(value):
                raise ValueError("topic entry has a malformed Due line; refusing to merge it")
            parsed["due"].append(value)
        elif line.startswith("- "):
            parsed["facts"].append((line.removeprefix("- "), False))
        else:
            raise ValueError("topic entry has an unsupported line; refusing to merge it")
    if not parsed["sessions"] or not parsed["facts"] or not parsed["why"] or not parsed["next"]:
        raise ValueError("topic entry is missing required fields")
    return parsed


def render_entry(
    title: str,
    sessions: list[str],
    topic: str,
    facts: list[tuple[str, bool]],
    why: list[str],
    next_steps: list[str],
    dues: list[str] | None = None,
) -> str:
    lines = [title, *sessions, f"Topic: {topic}"]
    lines.extend(f"- {'[hint] ' if hinted else ''}{fact}" for fact, hinted in facts)
    lines.extend(f"- Why it matters: {item}" for item in why)
    lines.extend(f"- Next: {item}" for item in next_steps)
    lines.extend(f"- Due: {item}" for item in (dues or []))
    return "\n".join(lines)


def merge_topic_entry(
    existing: str, incoming: dict[str, object], stamp: str,
    allow_title_change: bool = False,
) -> str:
    old = parse_entry(existing)
    if old["topic"] != incoming["topic"]:
        raise ValueError("topic mismatch while merging")
    if not allow_title_change and old["title"].strip().casefold() != incoming["title"].strip().casefold():
        raise ValueError(
            f'topic {incoming["topic"]} already exists this week under a different '
            f'title ({old["title"]!r} vs {incoming["title"]!r}); merging would bury '
            "a new subject under an old headline. Reuse the existing title to "
            "append to that subject, choose a distinct --topic for the new "
            "subject, or pass --allow-title-change to override"
        )

    sessions = list(old["sessions"])
    for session_line in incoming["sessions"]:
        if session_line != "Session: unknown" and "Session: unknown" in sessions:
            sessions.remove("Session: unknown")
        if session_line not in sessions:
            sessions.append(session_line)

    facts: list[tuple[str, bool]] = list(old["facts"])
    fact_indexes = {fact.casefold(): index for index, (fact, _) in enumerate(facts)}
    incoming_hint_facts: list[str] = []
    for index, (fact, hinted) in enumerate(incoming["facts"], start=1):
        key = fact.casefold()
        if hinted:
            incoming_hint_facts.append(key)
        if key in fact_indexes:
            previous_index = fact_indexes[key]
            previous_fact, previous_hint = facts[previous_index]
            facts[previous_index] = (previous_fact, previous_hint or hinted)
        else:
            fact_indexes[key] = len(facts)
            facts.append((fact, hinted))

    priority = list(incoming_hint_facts)
    priority.extend(
        fact.casefold()
        for fact, hinted in facts
        if hinted and fact.casefold() not in incoming_hint_facts
    )
    kept_hints = set(priority[:2])
    facts = [(fact, fact.casefold() in kept_hints) for fact, _ in facts]

    why = list(old["why"])
    for item in incoming["why"]:
        if item.casefold() not in {existing_item.casefold() for existing_item in why}:
            why.append(item)

    # Like Next, the latest Due replaces the old one; an update with no Due keeps the deadline it had.
    # --clear-due is explicit because clearing a real reminder is otherwise easy to do by accident.
    dues = [] if incoming.get("clear_due") else (
        list(incoming["due"]) if incoming["due"] else list(old["due"])
    )
    merged = render_entry(
        old["title"], sessions, old["topic"], facts, why, incoming["next"], dues
    )
    return f"## {stamp}\n\n{merged}\n"


def resolve_lint_file(spec: str) -> Path:
    if spec in ("current", ""):
        tag, _, _ = week_bounds(dt.datetime.now().astimezone())
        return ENTRIES_DIR / f"{tag}.md"
    if not re.fullmatch(r"\d{4}-W\d{2}\.md", spec):
        raise ValueError("lint target must be a weekly filename like 2026-W38.md")
    return ENTRIES_DIR / spec


def cmd_lint(week_file: Path) -> int:
    """Read-only: report entries with no searchable [hint] line."""
    if not week_file.exists():
        print(f"LINT: no such file: {week_file.name}")
        return 2
    text = week_file.read_text()
    spans = entry_spans(text)
    missing = []
    for index, (start, end) in enumerate(spans, start=1):
        block = text[start:end]
        if "[hint]" not in block:
            first = block.splitlines()
            header = first[0].strip() if first else "(empty)"
            missing.append((index, header))
    print(f"LINT: {week_file.name}: {len(spans)} entries, {len(missing)} without [hint].")
    for index, header in missing:
        print(f"  #{index} :: {header}")
    return 1 if missing else 0


def warn_prose_dates(lines: list[str], today: dt.date) -> None:
    """A date sitting only in prose never reaches the brief or the Due tab: say so, once."""
    try:
        sys.path.insert(0, str(SKILL_DIR))
        import due as due_mod  # same folder
        hits = [x for line in lines for x in due_mod.dates_in(line, today) if 0 <= (x - today).days <= 400]
    except Exception:
        return
    if hits:
        first = min(hits)
        print(f"WARNING: the text mentions {first.isoformat()} but no --due was given, so it will not appear in "
              "the morning brief or the Due list. Re-run with --due to store it as a deadline.")


def cmd_delete(topic: str, week_file: Path) -> int:
    """Delete the single entry with this topic from the weekly file.

    True deletion: only for explicit per-task human instruction (e.g. moving
    agent working notes out of life weeklies). Refuses when the topic matches
    zero or 2+ entries. Prints the removed header for the record.
    """
    if not week_file.exists():
        print(f"ERROR no such file: {week_file.name}", file=sys.stderr)
        return 1
    text = week_file.read_text()
    spans = entry_spans(text)
    hits = []
    for start, end in spans:
        try:
            parsed = parse_entry(text[start:end])
        except ValueError:
            continue
        if parsed["topic"] == topic:
            hits.append((start, end, text[start:].splitlines()[0].strip()))
    if not hits:
        print(f"ERROR no entry with topic {topic!r} in {week_file.name}", file=sys.stderr)
        return 1
    if len(hits) > 1:
        print(f"ERROR {len(hits)} entries with topic {topic!r} in {week_file.name}; refusing", file=sys.stderr)
        return 1
    (start, end, header), = hits
    week_file.write_text(text[:start] + text[end:])
    print(f"deleted {week_file.name} :: {header}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--title", required=False)
    parser.add_argument("--session", required=False, help='session id and title, or "unknown"')
    parser.add_argument("--topic", required=False, help="stable lowercase key; one merged entry per ISO week")
    parser.add_argument("--fact", required=False, action="append", help="repeat once per fact")
    parser.add_argument("--why", required=False, action="append", help="repeat once per reason or implication")
    parser.add_argument("--next", required=False, action="append", help='repeat once per follow-up; use "No follow-up" if none')
    parser.add_argument(
        "--due",
        required=False,
        action="append",
        help='a real deadline, stored as a field the brief and the app read: "2026-11-04", "+3d", "+2w", '
        '"tomorrow" or a weekday name, optionally with a label ("wed: Return Pixel 7"). The helper resolves it '
        "against today's date and prints the result; past dates are refused. Use it for every deadline, "
        "never only in prose.",
    )
    parser.add_argument(
        "--clear-due",
        action="store_true",
        help="explicitly remove the existing Due field when merging a topic entry",
    )
    parser.add_argument(
        "--hint",
        required=False,
        action="append",
        help="mark one or two exact fact bullets, e.g. --hint fact:1 "
        "(repeat the flag for a second). The hinted fact MUST contain the "
        "nouns future-you will grep (names, places, refs, amounts, dates): "
        "keyword search matches [hint] lines only, so a hint without "
        "keywords hides the entry.",
    )
    parser.add_argument(
        "--lint",
        nargs="?",
        const="current",
        default=None,
        metavar="WEEK-FILE",
        help="read-only check: list entries with no searchable [hint] line. "
        "Defaults to the current ISO week; pass e.g. 2026-W38.md for "
        "another week. Exits 1 when any entry lacks a hint.",
    )
    parser.add_argument(
        "--delete",
        action="store_true",
        help="true deletion: remove the single entry with --topic from the weekly "
        "file (needs explicit human instruction each time; prints the removed header)",
    )
    parser.add_argument(
        "--file",
        required=False,
        default=None,
        metavar="WEEK-FILE",
        help="weekly file for --delete (default: current ISO week)",
    )
    parser.add_argument(
        "--allow-title-change",
        action="store_true",
        help="permit merging into an existing topic whose title differs "
        "(default refuses: a new title usually means a new subject that "
        "needs its own --topic).",
    )
    args = parser.parse_args()
    if ENTRIES_DIR is None:
        print(
            "ERROR: no Terminus repo found; run the notebook helper from the Terminus repo.",
            file=sys.stderr,
        )
        return 2
    if args.clear_due and args.due:
        parser.error("--clear-due cannot be combined with --due")
        return 2
    if args.delete:
        if not args.topic:
            parser.error("--delete needs --topic")
            return 2
        try:
            week_file = resolve_lint_file(args.file or "current")
        except ValueError as exc:
            parser.error(str(exc))
            return 1
        return cmd_delete(args.topic, week_file)
    if args.lint is not None:
        try:
            week_file = resolve_lint_file(args.lint)
        except ValueError as exc:
            parser.error(str(exc))
            return 1
        return cmd_lint(week_file)
    missing = [
        name
        for name in ("title", "session", "topic", "fact", "why", "next", "hint")
        if not getattr(args, name)
    ]
    if missing:
        parser.error(
            "the following arguments are required: " + ", ".join(f"--{name}" for name in missing)
        )
        return 2
    try:
        text = build_entry(
            args.title, args.session, args.topic, args.fact, args.why, args.next, args.hint,
            args.due, dt.date.today(),
        )
    except ValueError as exc:
        parser.error(str(exc))
        return 1

    today = dt.date.today()
    for spec in args.due or []:
        day, label = resolve_due(spec, today)
        print(f"Due: {describe_due(day, label, today)}")
    if not args.due:
        warn_prose_dates(args.fact + args.next, today)

    now = dt.datetime.now().astimezone()
    tag, monday, sunday = week_bounds(now)
    ENTRIES_DIR.mkdir(parents=True, exist_ok=True)
    week_file = ENTRIES_DIR / f"{tag}.md"
    if not week_file.exists():
        template = TEMPLATE.read_text() if TEMPLATE.exists() else "# Week {week}\n"
        week_file.write_text(
            template.format(week=tag, monday=monday, sunday=sunday)
        )

    stamp = now.strftime("%a %Y-%m-%d %H:%M")
    contents = week_file.read_text()
    incoming_block = f"## {stamp}\n\n{text}\n"
    incoming = parse_entry(incoming_block)
    incoming["clear_due"] = args.clear_due
    spans = entry_spans(contents)
    matches = []
    for start, end in spans:
        block = contents[start:end]
        if re.search(rf"(?m)^Topic: {re.escape(args.topic)}\s*$", block):
            matches.append((start, end, block))
    if len(matches) > 1:
        print(f"ERROR: multiple entries already use topic {args.topic}; refusing to merge.", file=sys.stderr)
        return 2

    updated_existing = bool(matches)
    if matches:
        target_start, _, old_block = matches[0]
        try:
            merged_block = merge_topic_entry(
                old_block, incoming, stamp,
                allow_title_change=args.allow_title_change,
            )
        except ValueError as exc:
            print(f"ERROR: {exc}; no notebook changes made.", file=sys.stderr)
            return 2
        remaining = [contents[start:end].strip() for start, end in spans if start != target_start]
        updated = contents[:spans[0][0]].rstrip()
        for block in remaining:
            updated += f"\n\n{block}"
        updated += f"\n\n{merged_block.strip()}\n"
        week_file.write_text(updated)
        expected_block = merged_block
    else:
        expected_block = f"\n{incoming_block}"
        with week_file.open("a") as f:
            f.write(expected_block)
            f.flush()

    verified = week_file.read_text()
    topics = sum(
        bool(re.search(rf"(?m)^Topic: {re.escape(args.topic)}\s*$", verified[start:end]))
        for start, end in entry_spans(verified)
    )
    if topics != 1 or not verified.endswith(expected_block):
        print("ERROR: notebook write verification failed; inspect before retrying.", file=sys.stderr)
        return 1
    entry_number = len(entry_spans(verified))
    action = "Merged" if updated_existing else "Created"
    print(f"{action} plaintext notebook/{week_file.name} topic {args.topic}, entry #{entry_number}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
