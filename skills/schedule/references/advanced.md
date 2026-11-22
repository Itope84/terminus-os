# Advanced schedule operations

Read this only when configuring agent harnesses or sealed delivery.

## Agent harness

`--harness` defaults to `claude`; `opencode`, `codex`, and `auto` remain selectable. Every Claude run uses `claude-haiku-5-5` (`CLAUDE_MODEL` in `schedule.py`). Jobs without a harness now run on Claude too. All runs cap at 25 minutes. Codex runs workspace-write sandboxed in `~/Desktop` with outbound networking enabled for live-data jobs and `/etc/ssl/cert.pem` supplied for Go CLI trust; Claude uses JSON output and the OpenCode-parity permission set (`server/src/backends/claude.ts`).

## Sealed delivery

For agent jobs that must not deliver narration, use `--artifact /absolute/file-{date}.md` with optional `--gate "cmd {file}"` and `--receipt-cmd "cmd {file}"`. The runner removes stale output, runs the gate (must exit 0), then delivers the artifact or receipt's first line. Agent text stays in `fired.log`; failures deliver one `not delivered` line. Job shells do not define `$TERMINUS_DIR`, so use absolute paths. Morning briefs and catalyst sweeps enable this automatically from payload names; `premarket.py` feeds the brief's OVERNIGHT section.

`--start-marker` removes text above its first matching line. `--adaptive` honors a trailing `NEXT_RUN=YYYY-MM-DD HH:MM` and strips it before delivery.
