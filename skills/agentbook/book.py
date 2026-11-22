#!/usr/bin/env python3
"""Agentbook core: one small capped working-memory file per chat.

Identity comes only from the environment the server sets when it spawns the
chat's process (TERMINUS_AGENTBOOK="<backend>/<chat id>"), so an agent can
only ever touch its own chat's book. Stored under
<repo>/agentbook/<backend>/<chat id>.md (AGENTBOOK_HOME overrides).
"""

import datetime as dt
import os
import re
import sys
import tempfile
from pathlib import Path

SECTIONS = ("goal", "decisions", "open", "refs", "constraints")
# Characters, not tokens (~4 chars per token). TOTAL is the hard ceiling.
CAPS = {"goal": 300, "decisions": 1200, "open": 800, "refs": 800, "constraints": 500}
TOTAL = 3200
LINE_MAX = 160
SINGLE = ("goal",)  # one line, `set` only
REWRITE_AT = 0.7  # `rewrite` is accepted once the book is this full
# Days a line lives untouched before it is dropped on the next write. goal never expires.
TTL = {"open": 14, "refs": 21, "decisions": 45, "constraints": 90}

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")


class BookError(Exception):
    pass


def today() -> dt.date:
    raw = os.environ.get("AGENTBOOK_TODAY")
    return dt.date.fromisoformat(raw) if raw else dt.date.today()


def home() -> Path:
    return Path(os.environ.get("AGENTBOOK_HOME") or Path(__file__).resolve().parents[2] / "agentbook")


# Backends whose shell tool exposes the session id itself, so nothing needs to be set at spawn.
NATIVE_ID = {"CODEX_THREAD_ID": "codex", "OPENCODE_SESSION_ID": "opencode"}
BACKENDS = ("opencode", "codex", "claude", "agy", "commandcode")


def identity(env=None) -> tuple:
    env = env if env is not None else os.environ
    raw = env.get("TERMINUS_AGENTBOOK", "")
    if not raw:
        for var, be in NATIVE_ID.items():
            if env.get(var):
                raw = f"{be}/{env[var]}"
                break
    backend, _, chat = raw.partition("/")
    if not _ID.match(backend) or not _ID.match(chat):
        raise BookError("no agentbook for this chat (TERMINUS_AGENTBOOK is unset or malformed)")
    return backend, chat


def path_for(backend: str, chat: str) -> Path:
    return home() / backend / f"{chat}.md"


def parse(text: str) -> dict:
    out = {s: [] for s in SECTIONS}
    cur = None
    for raw in text.splitlines():
        m = re.match(r"^## (\w+)\s*$", raw)
        if m:
            cur = m.group(1) if m.group(1) in out else None
            continue
        if cur and raw.startswith("- "):
            m2 = re.match(r"^- (\d{4}-\d{2}-\d{2}) (.*)$", raw)
            out[cur].append((m2.group(1), m2.group(2).strip()) if m2 else (today().isoformat(), raw[2:].strip()))
    return out


def render(data: dict) -> str:
    parts = []
    for s in SECTIONS:
        parts.append(f"## {s}")
        parts.extend(f"- {d} {t}" for d, t in data[s])
    return "\n".join(parts) + "\n"


def size(data: dict) -> int:
    return sum(len(t) for s in SECTIONS for _, t in data[s])


def load(backend: str, chat: str) -> dict:
    p = path_for(backend, chat)
    try:
        return parse(p.read_text())
    except FileNotFoundError:
        return {s: [] for s in SECTIONS}


def clean(text: str) -> str:
    t = " ".join(str(text).split())
    if not t:
        raise BookError("empty text")
    if len(t) > LINE_MAX:
        raise BookError(f"line is {len(t)} chars, max {LINE_MAX}: shorten it")
    return t


def validate(data: dict) -> None:
    for s in SECTIONS:
        used = sum(len(t) for _, t in data[s])
        if used > CAPS[s]:
            raise BookError(f"{s} is {used}/{CAPS[s]} chars: trim {used - CAPS[s]}")
        if s in SINGLE and len(data[s]) > 1:
            raise BookError(f"{s} holds one line")
    total = size(data)
    if total > TOTAL:
        raise BookError(f"book is {total}/{TOTAL} chars: trim {total - TOTAL}")


def save(backend: str, chat: str, data: dict) -> None:
    validate(data)
    p = path_for(backend, chat)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=".tmp-")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(render(data))
        os.replace(tmp, p)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def expire(data: dict) -> tuple:
    """Drop lines untouched past their section's TTL. Returns (book, how many)."""
    now = today()
    new, gone = {}, 0
    for s in SECTIONS:
        ttl = TTL.get(s)
        keep = [(d, t) for d, t in data[s] if ttl is None or (now - dt.date.fromisoformat(d)).days <= ttl]
        gone += len(data[s]) - len(keep)
        new[s] = keep
    return new, gone


def fit(data: dict, keep: tuple = None) -> tuple:
    """Make the book fit its caps by dropping the oldest lines (never goal, never the line just written).
    Returns (book, [(section, text), ...] dropped). Oldest = earliest date, then earliest position."""
    new = {s: list(v) for s, v in data.items()}
    dropped = []

    def drop(sec: str, i: int) -> None:
        dropped.append((sec, new[sec][i][1]))
        del new[sec][i]
        nonlocal keep
        if keep and keep[0] == sec and keep[1] > i:
            keep = (sec, keep[1] - 1)

    for sec in SECTIONS:
        if sec in SINGLE:
            continue
        while sum(len(t) for _, t in new[sec]) > CAPS[sec]:
            cands = [(d, i) for i, (d, _) in enumerate(new[sec]) if keep != (sec, i)]
            if not cands:
                break
            drop(sec, min(cands)[1])
    while size(new) > TOTAL:
        cands = [(d, sec, i) for sec in SECTIONS if sec not in SINGLE for i, (d, _) in enumerate(new[sec]) if keep != (sec, i)]
        if not cands:
            break
        d, sec, i = min(cands)
        drop(sec, i)
    return new, dropped


def section(name: str) -> str:
    if name not in SECTIONS:
        raise BookError(f"unknown section {name!r}; use one of: {', '.join(SECTIONS)}")
    return name


def apply(data: dict, op: str, args: list) -> dict:
    """Return a new book with one operation applied. Pure; raises BookError."""
    new = {s: list(v) for s, v in data.items()}
    if op == "set":
        if len(args) != 2:
            raise BookError("usage: set <section> <text>")
        new[section(args[0])] = [(today().isoformat(), clean(args[1]))]
    elif op == "add":
        if len(args) != 2:
            raise BookError("usage: add <section> <text>")
        s = section(args[0])
        if s in SINGLE:
            raise BookError(f"{s} holds one line: use set")
        line = clean(args[1])
        if any(t == line for _, t in new[s]):
            raise BookError("already there")
        new[s].append((today().isoformat(), line))
    elif op == "del":
        if len(args) != 2 or not args[1].isdigit():
            raise BookError("usage: del <section> <line number>")
        s, n = section(args[0]), int(args[1])
        if not 1 <= n <= len(new[s]):
            raise BookError(f"{s} has {len(new[s])} lines")
        del new[s][n - 1]
    elif op == "touch":
        if len(args) != 2 or not args[1].isdigit():
            raise BookError("usage: touch <section> <line number>")
        s, n = section(args[0]), int(args[1])
        if not 1 <= n <= len(new[s]):
            raise BookError(f"{s} has {len(new[s])} lines")
        new[s][n - 1] = (today().isoformat(), new[s][n - 1][1])
    else:
        raise BookError(f"unknown op {op!r}")
    return new


def rewrite(data: dict, text: str) -> dict:
    if size(data) < TOTAL * REWRITE_AT:
        raise BookError(f"rewrite only once the book is {int(REWRITE_AT * 100)}% full; use set/add/del")
    new = parse(text)
    for s in SECTIONS:
        new[s] = [(d, clean(t)) for d, t in new[s]]
    return new


def die(msg: str) -> None:
    print(f"agentbook: {msg}", file=sys.stderr)
    sys.exit(1)
