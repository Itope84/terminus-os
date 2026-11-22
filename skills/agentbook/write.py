#!/usr/bin/env python3
"""Update this chat's agentbook (identity from the environment, never an argument).

Usage:
  write.py set <section> "<text>"      # replace a section with one line (goal always; others if tiny)
  write.py add <section> "<text>"      # append one line
  write.py del <section> <n|"text">   # drop line n, or the one line containing text
  write.py touch <section> <n>         # still true: restart its expiry clock
  write.py rewrite < new-book.md       # full replace; only once the book is 70% full; empty input is refused
Sections: goal decisions open refs constraints. Lines max 160 chars; whole book 3200.
Over a cap, the oldest lines are dropped to fit (never goal, never the line just written) and listed.
Untouched lines expire: open 14d, refs 21d, decisions 45d, constraints 90d (goal never).
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import book  # noqa: E402


def main() -> None:
    argv = sys.argv[1:]
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0 if argv else 1)
    try:
        backend, chat = book.identity()
        data = book.load(backend, chat)
        if argv[0] == "rewrite":
            # Never wipe the book by accident: no piped input, or an empty one, is refused.
            text = "" if sys.stdin.isatty() else sys.stdin.read()
            if not text.strip():
                raise book.BookError("rewrite needs the new book on stdin; nothing was changed")
            new = book.rewrite(data, text)
            if book.size(new) == 0:
                raise book.BookError("rewrite produced an empty book; nothing was changed")
        else:
            if argv[0] == "del" and len(argv) == 3 and argv[1] in book.SECTIONS and not argv[2].isdigit():
                # del <section> "<text>": the one line containing that text.
                hits = [i for i, (_, t) in enumerate(data[argv[1]]) if argv[2].lower() in t.lower()]
                if len(hits) != 1:
                    raise book.BookError(f"del: {len(hits)} lines in {argv[1]} contain that text; use the line number")
                argv = ["del", argv[1], str(hits[0] + 1)]
            new = book.apply(data, argv[0], argv[1:])
        new, gone = book.expire(new)
        keep = None
        if argv[0] in ("add", "set") and len(argv) > 1 and argv[1] in book.SECTIONS and new[argv[1]]:
            keep = (argv[1], len(new[argv[1]]) - 1)
        new, dropped = book.fit(new, keep)
        book.save(backend, chat, new)
    except book.BookError as e:
        book.die(str(e))
    print(f"ok [{book.size(new)}/{book.TOTAL} chars]" + (f", expired {gone} old line(s)" if gone else ""))
    for sec, text in dropped:
        print(f"dropped oldest to fit: [{sec}] {text}")


if __name__ == "__main__":
    main()
