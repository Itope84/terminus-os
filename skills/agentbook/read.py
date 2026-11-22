#!/usr/bin/env python3
"""Read this chat's agentbook (identity from the environment, never an argument).

Usage:
  read.py            # the book, numbered per section, with size
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import book  # noqa: E402


def main() -> None:
    if len(sys.argv) > 1:
        book.die("takes no arguments; the chat is fixed by the environment")
    try:
        backend, chat = book.identity()
    except book.BookError as e:
        book.die(str(e))
    data = book.load(backend, chat)
    for s in book.SECTIONS:
        print(f"## {s}")
        for i, (d, line) in enumerate(data[s], 1):
            age = (book.today() - __import__("datetime").date.fromisoformat(d)).days
            print(f"{i}. {line} ({age}d)")
    print(f"[{book.size(data)}/{book.TOTAL} chars]")


if __name__ == "__main__":
    main()
