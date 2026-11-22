---
name: bg
description: Run long tasks in the background and report when they finish.
---

# Background tasks (bg.sh)

```sh
terminus/tools/bg.sh <label> [--msg <text>] [--result <file>] [--quiet] <command> [args...]
```

Runs detached, logs to `~/.config/terminus/bg-<label>.log`, and sends a completion notice to the Terminus inbox (exit code + log tail).

## Rules

- Long jobs go through this — never block a chat turn polling.
- Label well: the label is what the finish ping names.
- Every launch states its handoff up front: `--msg` says what "done" still needs (verify X, hand over Y). Jobs producing handoff data (links, sizes, durations) write a ONE-LINE result file passed as `--result`; the ping reads `result: ... — next: ...`, nothing raw.
- Result lines for files must include the gallery link, never just the local path: `printf 'video=%s duration=%s bytes=%s local=%s' "$url" ...`. Link is the handoff; local path is the fallback.
- Never test through the inbox: `--quiet` runs with no ping.
- On FAILED pings, check the log before retrying.
