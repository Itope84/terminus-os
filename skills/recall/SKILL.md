---
name: recall
description: Find and bring a relevant past chat into the current session across supported backends.
---

# Recall

Find the old thread in any local backend store and collapse it here. Search is read-only. The current backend does not limit which stores can be searched. This is recall only: do not import, migrate, resume, or change the identity of another backend's session.

## Search

Use the user's home directory. Check titles/metadata first, then search message text only if needed. Rank by title match, then content match; show a few candidates (backend, title/summary, date, short ID). Confirm a candidate with a bounded snippet before extracting; ask only if ambiguous.

- **OpenCode:** read `~/.local/share/opencode/opencode.db` via SQLite read-only. Inspect tables first; live tables are usually `session_v2` and `session_message`. Search recent sessions/titles before `session_message.data`.
- **Codex:** session JSONL is under `~/.codex/sessions/`; `~/.codex/session_index.jsonl` maps IDs to files. Use only index/session message records.
- **Claude:** transcripts are JSONL under `~/.claude/projects/<project>/`; project paths are not titles. Use filenames and message metadata first.

Choose the source backend from the user's request, independent of the backend serving this conversation. For example, "search my OpenCode chats" from Codex means read the OpenCode store; "find that Claude session" from OpenCode means read the Claude store. Search only the named source when the user names one. If no source is named, search all supported stores. Use the user's home directory, not the current project directory. Do not assume every store exists or is readable; report unavailable stores clearly.

## Extract and summarize

Never dump a transcript. Keep matching user turns and nearby assistant text; for OpenCode, use sequence numbers to bound the relevant thread. Parse text fields only, cap excerpts around 3,500 characters, and do not paste raw JSON. Return decisions, choices and links, rejected options with reasons, open items, and one next step. Cite the source with title, date, and truncated ID.

1. Recent sessions (orient first):
`SELECT id, title, datetime(time_updated/1000,'unixepoch') FROM session_v2 ORDER BY time_updated DESC LIMIT 40;`
2. Search titles:
`SELECT id, title, datetime(time_updated/1000,'unixepoch') FROM session_v2 WHERE title LIKE '%KEYWORD%' ORDER BY time_updated DESC LIMIT 20;`
3. Search message contents only if titles miss; return session IDs first:
`SELECT DISTINCT session_id FROM session_message WHERE lower(data) LIKE '%keyword%' ORDER BY time_created DESC LIMIT 10;`
4. Confirm a candidate with one bounded snippet, never the transcript:
`SELECT session_id, substr(data, max(instr(lower(data),'keyword')-120,1), 300) FROM session_message WHERE lower(data) LIKE '%keyword%' ORDER BY time_created DESC LIMIT 5;`

### Codex

Session files are JSONL under `~/.codex/sessions/` (usually nested by date); `~/.codex/session_index.jsonl` can help map thread IDs to files. Read only these session/index files. Do not inspect auth, logs, state databases, or unrelated Codex data.

1. List recent `*.jsonl` files by modification time and use the session index or `session_meta` records for IDs and titles when present.
2. If titles do not identify a match, stream the JSONL files line by line and inspect message records only. Codex records commonly have `type: response_item` and a `payload` containing a message `role` and `content`; extract only text blocks (`input_text` / `output_text` or equivalent). Ignore tool calls, reasoning, and non-message records. Treat the actual keys in the file as authoritative; formats can vary by version.
3. Keep only matching session IDs, timestamps, and a short text snippet. Never print whole JSONL records or entire files.

### Claude Code

Session transcripts are JSONL under `~/.claude/projects/<project>/`. Search filenames and lightweight session metadata first; project names are path-encoded and are not session titles.

1. List recent transcript files and identify the session ID from the filename/records.
2. If needed, stream JSONL and inspect user/assistant message text only (commonly `type`, `message.role`, and `message.content`). Ignore tool-use/result payloads and non-message records. Treat actual record keys as authoritative because formats can vary by version.
3. Return only matching session IDs, timestamps, and a short text snippet. Never print whole JSONL records or entire files.

### Rank and confirm

When searching multiple stores, combine candidates into one list labelled with backend, title or first user-message summary, date, and short ID. Prefer a title match, then a content match; show the strongest few candidates and ask only if the intended thread is ambiguous. Before extracting, confirm with one bounded snippet from the selected source.

## 2. Extract — least exposure

Never dump a whole session. For SQLite, use the OpenCode method below. For JSONL stores, stream records and retain only the matching user turns and the nearby assistant text; do not load or print the full transcript.

1. For OpenCode, scan user turns to find thread boundaries:
`SELECT seq, time_created, data FROM session_message WHERE session_id='ses_...' AND type='user' ORDER BY seq ASC;`
Keep turns matching the topic; note the seq range.
2. For OpenCode, assistant text only for that range — parse JSON (`data` -> `content[]` where `type='text'`), skip tool/reasoning payloads, cap ~3500 chars per excerpt. For Codex/Claude, apply the provider-specific message parsing above and the same excerpt cap. Don't paste raw JSON to chat.
3. One more keyword pass inside the session if the range is unclear:
`SELECT seq FROM session_message WHERE session_id='ses_...' AND lower(data) LIKE '%keyword%' ORDER BY seq ASC;`

## 3. Collapse — answer in the current session

Reply here with: decisions, picks + links, rejected options + why, open items, one next step. Short ref (title + date + truncated id, e.g. `ses_f44a…`). Never re-paste the full transcript.

## Rules

- Read-only access to session stores only. Never modify them or touch auth, runtime state, or daemon state.
- Search the requested source backend even when it differs from the current backend. If no source is specified, search all available supported backends. If a requested store is unavailable, say which source could not be searched.
- Backend-neutral recall does not mean session migration: never import, resume, or reuse a source session ID in another backend.
- No transcript dumps — snippets + collapsed summary only.
- Missing everywhere (no title + no content hit): say what was searched and offer a fresh start.
