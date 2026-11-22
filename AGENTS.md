# terminus agent notes

Personal hub: skills, scripts, browser. See README.md.
- State → `~/.config/`, scratch → `/tmp/`, secrets out of git. Exception: `./.run/` (gitignored, 0700) — device/operational config, never permission-walled. ONLY true secrets in `~/.config/terminus/secrets/`.
- Reuse skills/scripts/tools; don't rebuild API clients or notify plumbing.

## Shell

- Prefer `rtk` for noisy commands. Raw only when full output is needed.

## Reads

- Grep first, read the matching block only. Never digest whole files, histories, or stores unless asked. Least exposure wins — especially personal data.

## Web fetch routing — canonical, others point here

- Climb, never retry a rejected rung: backend-native web fetch when available (OpenCode `webfetch`, Codex `web__run`, Claude `WebFetch`) → `tools/fetch` (`tools/fetch/.venv/bin/python tools/fetch/fetch.py <url> [--raw]`) → `browser` Playwright (`:9009`, bearer `$(cat ~/.config/terminus/browser/token)`; headless, deterministic, no model cost) → `browser-mobile` skill (the phone's agent tab; for interaction such as clicks and forms, logged-in phone sessions, or proving it on-device — needs the app open). If a backend has no native fetch tool, start at `tools/fetch`. Akamai deny = stop rule, offer manual. Bot wall surviving a logged-in profile = stop and report, never attempt captcha solving. Payment never moves without the human's direct go-ahead in chat.
- For anything that must run on the phone, read `skills/browser-mobile/SKILL.md`; `GET /api/pb/status` first, ask the human to open the app if offline.
- This section is canonical; `tools/fetch/README.md` and `server/README.md` are pointers.

## Core user info

- `notebook/user.md` holds one-line headlines (facts worth remembering); detail lives in `notebook/YYYY-Www.md` weeklies. No personal data in git-tracked files.

## Notebook

- Weeklies = life only; app/dev issues → tracked `logbook.md` (What / So what / Now what + `[hint]`). Running list, max 20 `##` sections — adding the 21st means deleting the oldest first. Plaintext stays local (gitignored). See `logbook.md` for the entry format.

## Secrets

- API keys resolve in this order: `$VAR` env → `~/.config/terminus/keys/<name>` files (0700 dir, 0600 files, never committed) → fail loud. Context gets `$VAR` references, never values — never in env, args, chat, or files.

## Media server — artifacts gallery

- `server/` on `:9009`; gallery `artifacts/` day-folders, 7-day prune for generated files, including APKs and nested render folders. Handoff: verify byte count, share link, wipe on confirm.
- Chat lifecycle: `server/CHAT_MIRROR.md` is canonical. Verify in `/debug/chat` + mirror verifiers before touching stream/settle/stop code.

## App build + stage — terminus chat (Android)

- Bump `versionCode`/`versionName`; `./build-local.sh` → BUILD SUCCESSFUL (node 24 via nvm). Stage to `$TMPDIR/terminus/<day>/` + `artifacts/<day>/`; verify `cmp` + curl size.
- Product rule: chat flow, rooms per session. No silent self-heal (clear errors + one-tap actions). No delete confirm; deletes id-bound, never "current session".

## Traffic / schedules / background

- TomTom (bring your own key), OSRM fallback.
- Schedules live in `skills/schedule/schedule.py` (mirror to `~/.config/opencode/skills/schedule/`); suggest jobs for deadlines, renewals, checks. Agent jobs capped 25 min; notebook access via `skills/notebook/read.py` only.
- Long jobs → `tools/bg.sh`; long-lived state under `~/.config/terminus/`, never `/tmp` or the gallery.
