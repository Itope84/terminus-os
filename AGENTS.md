# terminus agent notes

Personal hub: skills, scripts, browser. See README.md.
- State → `~/.config/`, scratch → `/tmp/`, secrets out of git. Exception: `./.run/` (gitignored, 0700) — device/operational config, never permission-walled. ONLY true secrets in `~/.config/terminus/secrets/`.
- Reuse skills/scripts/tools; don't rebuild API clients or notify plumbing.

## Shell

- Prefer `rtk` for noisy commands. Raw only when full output is needed.

## Reads

- Grep first, read the matching block only. Never digest whole files, histories, or stores unless asked. Least exposure wins — especially personal data.

## Web fetch routing — canonical, others point here

- Reading a page, climb, never retry a rejected rung: backend-native web fetch when available (OpenCode `webfetch`, Claude `WebFetch`) → `tools/fetch` (`tools/fetch/.venv/bin/python tools/fetch/fetch.py <url> [--raw]`). If a backend has no native fetch tool, start at `tools/fetch`.
- Using a browser (clicks, forms, logged-in pages, anything a fetch can't see), three tiers in order:
  1. Headless Playwright on this Mac: `POST :9009/open|/scrape|/act` (bearer `$(cat ~/.config/terminus/browser/token)`; login profiles via `/profile`). Deterministic, no model cost. The default.
  2. The agents' own Chrome through Chrome DevTools MCP (`mcp__chrome-devtools__*`, both Claude and OpenCode). A real, visible Chrome on `127.0.0.1:9222` with its own profile (`tools/chrome-agent`); use it when headless is blocked or the page needs a real browser. Open your own page; never close pages you didn't open.
  3. Claude in Chrome (`mcp__claude-in-chrome__*`, Claude only): the owner's everyday Chrome with their sign-ins. Last resort; say what you're about to do there first.
  The phone's browser (`browser-mobile`) is Android only; on iPhone there is none. Akamai deny = stop rule, offer manual. Bot wall surviving a logged-in profile = stop and report, never attempt captcha solving. Payment never moves without the human's direct go-ahead in chat.
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
