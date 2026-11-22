---
name: schedule
description: Create and manage scheduled reminders, checks, and recurring reports.
---

# Schedule

"Remind / check / watch X" → local job that fires on time. For anything checkable from this laptop.

## Use when

- "schedule X", "remind me Y", "check Z every morning", "watch tickets", "alert if <symbol> falls below <price>".
- Recurring health checks.

## Ask only what's missing

1. **What** + alert condition ("only if price < $X", "only if wrong, else all-clear").
2. **When**: one-shot or repeat (daily 08:00, every 10m…).
3. **End**: repeats need `--until YYYY-MM-DD` or `--count N`. One-shots end after firing.
4. **How**: Terminus inbox thread (`schedule:<name>`, bell + unread). Only delivery leg.

Then `schedule.py add` + confirm in one line (what/when/where).

## Job types

- `remind` — static text at a time. `--chat <session-id>` delivers into that thread instead of the job thread (same-chat follow-ups).
- Delivery default: no `--chat`, fires to the dedicated `schedule:<name>` thread. Use `--chat` only when the user says here / this chat / same thread. Resolve via `schedule.py chats`: exactly one live chat = use it, else ask, never guess.
- `exec` — shell command, output pushed (truncated). Deterministic checks with clean APIs; see `checks/`.
- `agent` — headless run + prompt (web search + browser below). Bound it: sources, result limit, alert condition, full pages only after shortlisting.
- Every agent payload carries its output contract, or the transcript ships: verdict-only reply starting with a unique first line (`--start-marker` set to it), or `SILENT`-unless-actionable. No contract, no job.
- Output exactly `SILENT` → logged, no push. Write prompts as "reply SILENT unless X".
- Watchers: put the change detection in code, not the prompt. `--precheck '<cmd>'` runs first: empty stdout = nothing new, the model is not called that tick; output = appended to the prompt; non-zero exit = failure alert (a broken check never silences a job). `$SCHEDULE_STATE` names the job's state file for cursors.
- `--state` gives an agent job a JSON memory (`~/.config/terminus/schedule/state/<name>.json`, 0600, 8 KB cap): the agent reads it, skips what it already reported, rewrites it. Oversized or broken = rolled back and flagged in the delivery. Ids, dates, one-line reasons only.
- Replies in a job's thread carry the deliveries since your last message (the app adds them to what it sends), so "do that" after a brief works.

## Wake-me requests ("wake me in 20 min about X", "alarm me at 7")

NOT schedule jobs. A ringing alarm arms on the phone itself, when its `terminus://alarm` link lands. With the app closed it still lands within seconds through the live link (LiveLinkService, APK v1270+) as long as the phone is on and online; otherwise on the next inbox sync. So the link must arrive before the ring time: never rely on a job firing at the alarm minute itself.

- In chat: the backend emits a `terminus://alarm?at=<epochMs>&msg=<text>` link (builtin prompt instruction); the app arms the device alarm the moment the link arrives. Nothing to schedule — the conversation IS the arming moment.
- Said to an operator (terminal session, laptop): do the arithmetic, hand back the exact link for the human to tap in the app, and say plainly it rings only once tapped. Never promise server-side ringing.

## Scripts

- `add --name <id> (--at "YYYY-MM-DD HH:MM" | --cron "M H DOM MON DOW" | --every 10m|1h|1d) --type remind|exec|agent --payload <text> [--until] [--count] [--chat] [--title] [--priority] [--start-marker] [--retire-on-alert] [--precheck <cmd>] [--state]`
- `list`, `remove <name>`, `run-due` (ticker only), `install` (per-minute LaunchAgent). `--adaptive` honors a trailing `NEXT_RUN=YYYY-MM-DD HH:MM`; one-shots retire after firing.
- Jobs live in `~/.config/terminus/schedule/jobs.json`, never git; results and fire logs stay local.

## Browser (JS-heavy pages)

Headless Playwright, already running: `POST 127.0.0.1:9009/open {url,selector?}` → PNG; `GET /terminus/<file>`; `GET /health`. Down → `cd terminus/server && node dist/server.js`.

Showing the human a file? See the artifacts skill. Inline `[image:/abs/path caption]` only for 1–2 images to look at.

## Examples

```sh
python3 skills/schedule/schedule.py add --name symbol-watch --every 1h --count 24 --type agent \
  --payload "Check <SYMBOL> via the configured quote source. Alert only if below <PRICE> or moved >5%; else SILENT." --title "Price watch"
python3 skills/schedule/schedule.py list
```

`checks/`: reusable, args in payload, never hardcoded — `weather.py --spot "Label:lat,lon[:Timezone]"` (Open-Meteo, no key), `system_snapshot.py` (one widget line), `cleanup.py` (monthly trim + prune, exec).

## Payload style — phone-readable (hard rule)

Push renders exactly what you save, so write for a phone screen:

- Short lines with real line breaks — never one compressed blob.
- Lead with action + tappable number first (push truncates the rest).
- Max ~6 short bullets. Plain digits, real emails — no "dot" obfuscation.
- Read it back at phone width before saving. If it looks drab, rewrite it.

## Limits (say out loud)

- Laptop asleep/off = cron sleeps. Needs awake + logged in + network for push (fires still logged).
- `agent` costs inference per fire; `exec` for dumb polling, `agent` for judgment.

## Sealed delivery (agent jobs that must not leak narration)
`schedule.py add ... --artifact /abs/file-{date}.md [--gate "cmd {file}"] [--receipt-cmd "cmd {file}"]`: the agent writes the artifact; the
runner deletes any stale copy first, runs the gate (exit 0 required), then delivers the artifact's bytes (or the receipt command's first line).
The agent's own text is logged to fired.log, never delivered. Any failure = one `⚠ not delivered — <reason>` line. Use absolute paths in every job payload
(`$TERMINUS_DIR` is unset in job shells).
