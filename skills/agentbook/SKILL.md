---
name: agentbook
description: Per-chat working memory that survives compaction. Read it at the start of a chat and after compaction; update it when a decision, constraint, open item or reusable reference appears.
---

# Agentbook

Tiny capped notes for THIS chat only: what you decided and why, what is open, what you will need again. The chat is fixed by the environment; there is no id to pass. Not the notebook (life notes) and not `logbook.md` (app notes). Never hand-edit the file. Books stay on this machine in `~/.config/terminus/agentbook/`; they are not committed. Keep secrets and personal identifiers (emails, phone numbers, account or ID numbers, addresses) out of them: each book is sent to the model with the chat.

```sh
python3 <terminus>/skills/agentbook/read.py
python3 <terminus>/skills/agentbook/write.py set goal "<one line>"
python3 <terminus>/skills/agentbook/write.py add decisions "<line>"
python3 <terminus>/skills/agentbook/write.py del open 2
python3 <terminus>/skills/agentbook/write.py del open "send-now"   # the one line containing that text
python3 <terminus>/skills/agentbook/write.py touch decisions 1
```

Sections: `goal` (one line), `decisions`, `open`, `refs`, `constraints`. Lines are 160 chars max and the whole book 3200 chars. Over a cap, the oldest lines are dropped to make room and listed in the output, so delete what you know is dead before it costs you something live. `write.py rewrite` (new book on stdin) works only once the book is 70% full, as the way to compress it.

## Aging

Every line is dated and shown with its age. Untouched lines expire on the next write: open 14 days, refs 21, decisions 45, constraints 90, goal never. `touch <section> <n>` renews a line that is still true; delete lines that stopped mattering instead of waiting. Books of chats untouched for 60 days are deleted.

## Style

Telegraphic. `pg not sqlite: concurrent writes`. Keep a few words of why on each decision. Paths, ids, commands, versions and error strings go in verbatim. No prose, no history.

## When to write

- A decision is made or reversed: `add decisions`, `del` the line it replaces.
- A task opens or closes: `add open` / `del open`.
- You find a path, id or command you will reuse: `add refs`.
- The user states a constraint or preference: `add constraints`.
- The root cause of a bug is found.
- You finish a meaningful piece of work (feature done, bug fixed, plan agreed, research concluded): before replying, record the outcome and close its `open` item.
- A hidden line from the server says the book is stale or compaction is close: update it before anything else.

The test for "worth writing": after a compaction, would you be lost or repeat work without this line? If not, skip it. Do not write every turn. After a compaction, read the book first.
