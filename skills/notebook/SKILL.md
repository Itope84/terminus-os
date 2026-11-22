---
name: notebook
description: Search or update the personal notebook for remembered facts, decisions, and follow-ups.
---

# Notebook

Use for durable personal facts, decisions, purchases, reminders, and read-back questions. One plaintext entry per topic per ISO week; use the Terminus repo helpers, never hand-edit entries.

## Repository location and commands

The Terminus repository is `<terminus>`. Notebook helpers live inside that repository at `<terminus>/skills/notebook/`; they are not at `~/Desktop/skills/notebook/` and are not part of the installed skill directory. **Do not assume the shell's current directory is the repository.** Either change to the repo first:

```sh
cd <terminus>
python3 skills/notebook/read.py --recent
```

**Deadlines: always `--due`.** Any real deadline (return window, appointment, renewal, payment) goes in as `--due "<when>[: label]"`, where when is `YYYY-MM-DD`, `+3d`, `+2w`, `today`, `tomorrow` or a weekday name (the next one after today). The helper resolves it against the real clock, refuses past or impossible dates, and prints `Due: Wed 4 Nov 2026 (in 5 days)`: read that line back and correct the entry if it is not the day you meant. Never work out a date yourself and never leave a deadline only in prose: readers consume the `- Due:` field, and a date written only in a fact is missed (the helper warns when it sees one). A later update that gives a new `--due` replaces the old one; an update with none keeps it.

Run from terminus root. Repeat `--fact` / `--why` / `--next` for extra bullets; each takes one single line. Never hand-write bullets — always use this helper, which enforces hint targets and verifies the write. `--hint fact:N` marks one or two fact bullets (repeat flag for a second). The hinted fact must contain the nouns future-you will grep for — names, places, refs, amounts, dates. Keyword search matches `[hint]` lines only, so an entry with no hinted fact is invisible. `--next "No follow-up"` when there is no action; do not invent missing facts. Use a stable `--topic` slug for one subject only — related is not same (`router-reboot` vs `router-quote`). One entry per topic per ISO week: new facts/reasons merge without duplicates, latest Next replaces stale actions, source sessions retained. Appending needs the exact title; a new title is refused (new subject = new topic; `--allow-title-change` overrides). Different topic or week starts a new entry. Helper reports created vs merged. `--session unknown` only when session metadata is unavailable.

All notebook reads and writes go through the repo-relative helper from the Terminus repo root into `notebook/`. An installed helper locates that repo from the working directory and fails rather than using another store if it cannot find it. The notebook is local working memory — keep it out of commits.
Before writing, search the current week via `read.py --topic <slug>` and reuse its key + title for the same subject; a related-but-different subject gets a new key. Merges need an exact topic key; the helper refuses duplicate topics, title mismatches, or unsupported matches rather than guessing.

## Attachments / data (hard rule)

```sh
python3 <terminus>/skills/notebook/read.py --recent
```

Use the same repo-root or absolute-path form for every `read.py` and `add.py` command below. If a command reports that the helper cannot find the Terminus repo, rerun it from `<terminus>`; do not look for a `skills/` directory under `~/Desktop`.

## Write

From the Terminus repo, check the current week first with `python3 skills/notebook/read.py --topic <slug>`, then use `add.py`:

```sh
python3 skills/notebook/add.py --title "<title>" --session "<id> · <title>" \
  --topic "<stable-slug>" --fact "<one fact>" --why "<reason>" \
  --next "<action or No follow-up>" --hint fact:1
```

- Use exact topic and title to merge; one topic per week. Facts/reasons merge, latest Next replaces the old one, source sessions are retained. Use `--allow-title-change` only when intended. Repeat `--fact`, `--why`, or `--next` for separate one-line bullets.
- Every real deadline must use `--due "<date or relative time>[: label]"`; accepted forms include `YYYY-MM-DD`, `+3d`, `+2w`, `today`, `tomorrow`, and weekday. Trust the helper's resolved date and read it back. Prose dates alone are missed by reminders. An omitted due date preserves the existing one.
- Add one or two `--hint fact:N` tags to facts containing searchable names, places, references, amounts, or dates. Search sees hinted facts only. Use `--session unknown` only if session metadata is unavailable. Never invent facts; use `--next "No follow-up"` when appropriate.
- Entries contain facts, why they matter, next action, and hint(s). Keep one fact per bullet; dated events use exact dates/times. Ask whether the information will matter later before saving.
- The helper verifies writes and merges. For manual edits only, run its `--lint` afterward.

Use plaintext `notebook/` only. The installed helper must resolve the Terminus repo and fail if it cannot; never fall back to another store.

## Attachments and scope

- Store attachments in `artifacts/notebook-data/<topic-slug>/`, not the seven-day media folder or repo; cite the gallery-relative path. `notebook/` and `artifacts/` are local and gitignored.
- Weeklies are life-only. Put build progress, benchmarks, and deploy logs in `notebook/build-log.md`. Skip anything unlikely to matter later.

## Read back

Use only `python3 skills/notebook/read.py`: `<keyword>` searches hints; `--entry <N> <file>` reads one entry; `--recent`, `--topic <slug>`, `--user`, and `--open` provide bounded views. Check current week first. Never read weekly files through shell tools; full reads require per-task user approval. Deletion also requires explicit instruction and `add.py --delete`.
