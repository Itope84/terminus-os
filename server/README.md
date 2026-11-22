# server — Mac server (opencode proxy, inbox, gallery, notes, browser automation)

Standalone browser for screenshots, scraping, and automation. Serves the Terminus app and its skills.

## Quick start

```sh
npm install
npx playwright install chromium
npm run build
node dist/server.js              # http://127.0.0.1:9009 (see Network below)
# or
npm run serve
```

Needs Node 20 or newer. Agy chat titles are read with `node:sqlite`, which needs Node 22.5+; on older Node
everything else works and those titles are simply skipped.

## First run (pairing + layout)

- Network: the server listens on loopback (`127.0.0.1`) by default, so it is never
  exposed to the local network by accident. When Tailscale is running it also listens on
  that address, for use away from home (see the main README). Set `HOST=<address>` to
  choose an interface, or `HOST=0.0.0.0` to listen on all of them. A loopback listener is
  always added, which is also how the Android emulator reaches it (`http://10.0.2.2:9009`).
- Repo root: the checkout itself, or set `TERMINUS_DIR` to point elsewhere.
  Gallery defaults to `<root>/artifacts/` (`MEDIA_DIR` overrides); runtime
  state (`.run/`) stays out of git.
- Auth: bearer token in a `0600` file the server reads at boot (see
  `tokenFile` in `src/auth.ts`); the app pairs by scanning/entering it once.
  WebViews use short-lived link tokens (`POST /api/link-token`), never the
  master token in URLs.
- Chat defaults (backend, models, system prompt) persist per device in
  `.run/chat-defaults.json` via `POST /api/defaults`; a version bump re-seeds
  new sessions automatically.

## API

Base `http://127.0.0.1:9009` (or the address you chose with `HOST`).

| Method | Path | Body / Query | Returns |
| --- | --- | --- | --- |
| `GET` | `/health` | — | `{"ok":true,"mediaUrl":"http://<server-host>:9009/terminus/"}` |
| `POST` | `/open` | `{"url":"https://example.com","selector":"#main","fullPage":true}` | `image/png` + saves `shot-*.png` to `$(tmpdir)/terminus/` |
| `GET` | `/terminus/<file>` | `?download=1` attachment, `Range:` bytes | images, video/audio with 206 streaming, else octet-stream |
| `POST` | `/scrape` | `{"url":"https://example.com","profile":"shop"}` | `{ok,url,title,text,html,extracted}` |
| `POST` | `/act` | `{"url":"...","profile":"shop","actions":[{"type":"fill","selector":"input","text":"hi"},{"type":"click","selector":"button"}]}` | `{ok,url,title,screenshotUrl}` |
| `POST` | `/cookies` | `{"profile":"shop","file":"site_cookies.txt","url":"https://example.com"}` | `{ok,profile,imported,skipped}` |
| `GET` | `/screenshot?url=https://...` | `&profile=shop` optional | `image/png` |
| `GET` | `/profiles` | — | `{ok,profiles:[{name,loaded,headed}]}` |
| `POST` | `/profile` | `{"name":"shop","headed":true}` | ensures persistent login profile |
| `DELETE` | `/profile?name=shop&wipe=1` | — | closes profile, `wipe=1` deletes its data |
| `GET` | `/notes` | — | Authenticated notebook viewer |
| `GET` | `/notes-profile` | — | Authenticated, on-demand rendering of local `notebook/user.md` |

`POST /open` logs `saved /tmp/terminus/shot-*.png → http://<server-host>:9009/terminus/shot-*.png`.

The advertised host is the Tailscale address when Tailscale is running, otherwise the LAN address, falling back to loopback.

## CLI

```sh
node dist/index.js open https://example.com --screenshot /tmp/out.png
```

## Notes

- Code layout: `src/server.ts` (automation + routing), `src/media.ts` (gallery: list/bytes/range/delete), `public/index.html` (viewer). One process, one port — no split planned.
- Gallery files live under `<repo>/artifacts/` by default (`TERMINUS_DIR` env overrides the repo root; `MEDIA_DIR` sets the gallery directly). Files older than `MEDIA_RETENTION_DAYS` (default: 7) are pruned when the gallery is listed. `notebook-data` is always retained; comma-separated `MEDIA_KEEP_DIRS` can retain additional named folders.
- Login profiles live under `~/.config/terminus/browser/profiles/<name>/` — cookies survive restarts, never in git. Create headed for typing logins (`{"name":"shop","headed":true}`), then re-create headless to background it. Pass `"profile":"shop"` on `/open`/`/scrape`/`/act` to use the session.
- Form driving via `POST /act`: action list of `goto`/`fill`/`fillSecret`/`click`/`press`/`select`/`wait`/`waitFor` (max 50, first-match selectors). Fill/click stop with 422 + screenshot state on failure. Action text is never logged.
- Single source: `AGENTS.md` Web fetch routing. Bot walls = fetch first, bridge cookies if needed, don't re-render TLS-level 403s.
- Secrets for `fillSecret` live in `~/.config/terminus/browser/secrets/<name>` (0600, strict names, no traversal) — the human drops the file locally, the agent only ever references it by name. Passwords, codes and captchas stay human: the agent drives to the gate and stops.
- Clone profiles: copy a browser profile to `~/.config/terminus/browser/clones/<name>/`, then `POST /profile {"name":"x","channel":"chrome","clone":"<name>"}` drives the real Chrome binary on the copy (decrypts its own Keychain key, no prompts).

## Session transfer playbook

Goal: reuse a human's live site login. In order of preference:

1. **Cookie export file** — human exports via extension, drops file in `secrets/`, `POST /cookies {profile, file, url?}` (cookies.txt or JSON). No passwords, no keychain. Dies if the site rotates or fingerprint-binds sessions.
2. **Profile clone** — human quits the browser fully (flushes memory tokens to disk; live copies carry stale tokens — there is no WAL in Chrome's Default dir, staleness is memory-vs-disk), agent copies minus caches to `clones/<name>/`, launches with `channel: "chrome"`. Real binary, own Keychain ACL, IndexedDB/localStorage included. Fingerprint-bound sessions still die server-side.
3. **Headed handoff** — agent drives the form to the gate (`/act`), human solves captcha/code in the visible window.

Never: captcha solving, password/code handling, headless Keychain writes (`security … -w` exits 36 with no GUI to approve; reads of `Chrome Safe Storage` fail the same way — the OS flat-denies, no dialog).
- Bearer auth everywhere except the gallery shell (token UI); bind is loopback by default (plus the Tailscale address when it is running), not public internet.
