---
name: inbox
description: Deliver into the Terminus app inbox from laptop sessions, jobs, or backends. Use when pushing a result, reminder, or alarm link to your device.
---

# Inbox

The only delivery leg into the app. Bearer-gated (`~/.config/terminus/browser/token`).

## Contract

POST `http://127.0.0.1:9009/api/inbox` with JSON:

- `job` (required): slug — picks the fallback thread (`schedule:<job>`, or `alerts`).
- `title`, `body`: text. At least one required.
- `chat` (optional): exact session/thread id. **When present and valid, the
  entry lands in that thread instead of the job thread.**

```sh
curl -s -X POST -H "Authorization: Bearer $(cat ~/.config/terminus/browser/token)" \
  -H "Content-Type: application/json" \
  -d '{"job":"alarm","title":"...","body":"...","chat":"s-123"}' \
  http://127.0.0.1:9009/api/inbox
```

`chat` must match `^[A-Za-z0-9_:-]{1,80}$` — anything else is ignored and the
entry falls back to the job thread. Never fail on a bad override; route
deterministically.

## Routing rule (deterministic)

- Known session (chat backend answering in a thread, operator with a live
  id from `schedule.py chats`): pass `chat` — same-thread delivery.
- Unknown session (cron ticks, watcher alerts): omit it — job thread.
- An entry with a future `terminus://alarm` link arms the device alarm at
  sync time, whichever thread it lands in. No tap, no open needed.

## Discovery

`schedule.py chats` lists live app threads right now. A stale entry (socket
outlived the thread) resolves by asking the human to open the chat they mean
and re-listing.
