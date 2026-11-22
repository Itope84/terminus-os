---
name: fetch
description: Fetch web pages across backends, escalating to the phone browser when a page needs JS, a login or clicks; stop at login and captcha gates.
---

# Fetch - open a link

Use when user says "open this link", "check this page", "fetch this", "bypass", or drops a URL.

## Pick the starting rung per site

Judge before climbing: a fetch that will likely be blocked costs a round trip for nothing. The phone browser is a real phone with real sessions and gets through most walls, so start there when a block is likely.

1. **Known site?** `python3 skills/browser-mobile/browser-memory.py lookup <domain>`. A recorded `wall` means start on the phone.
2. **Start on the phone (rung 3)** when any of these hold:
   - the user says browser, mobile or phone
   - the task needs a login, clicks, forms, a basket or a booking
   - the site is the kind that blocks bots: retail and marketplaces, travel and airlines, tickets, banks and utilities, social media, property and job boards, anything behind Cloudflare/Akamai-style checks
   - prices, stock or availability are the point (often rendered by JS or varied by bot detection)
3. **Fetch first (rungs 1-2)** for plain public text: docs, Wikipedia, news and blogs, GitHub, public APIs and JSON, PDFs. Cheap, and fine to try before the phone.
4. **Learn from it.** A site that blocked 1-2 but worked on the phone gets `browser-memory.py note --domain <d> --wall "fetch blocked; use phone"`, so the next chat starts on the phone.

Phone availability: `python3 skills/browser-mobile/pb.py status` (`"online": true`). Desktop browsers (4) only when the phone is offline.

## Ladder - climb from the starting rung, never stop at first fail

1. Native web fetch - quick public pages only
   - Use the current backend's native web-fetch tool when available: OpenCode `webfetch`, Codex `web__run`, or Claude `WebFetch`.
   - If the backend has no native fetch tool, skip to `tools/fetch`.
   - Plain public HTML, no bot wall expected.

2. tools/fetch - bot walls and 403s, Chrome TLS, no JS
   - From terminus root: `tools/fetch/.venv/bin/python tools/fetch/fetch.py <url> [--raw] [--impersonate chrome|safari]`
   - `--raw` keeps embedded JSON for JS-app sites. Parse useful response data instead of repeatedly rendering a blocked page.

3. Phone browser - JS pages, logins, interaction, anything 1-2 couldn't clear
   - Follow `skills/browser-mobile/SKILL.md` (`pb.py open URL`, `extract text`, `close` when done). Real phone, real sessions: no bot walls to fight.
   - Offline (`"online": false`): the app is closed. Say so in one line and offer to wait for it; use 4 only for a plain public JS render.

4. Desktop Playwright - phone offline only, JS render only
   - Server: `http://127.0.0.1:9009` on the server host (port 9009); use the server's address from another device.
   - Auth: `Authorization: Bearer $(cat ~/.config/terminus/browser/token)`
   - `GET /health` returns mediaUrl
   - `POST /scrape {"url":"...","profile":"..."}` returns title, text, html
   - `POST /open {"url":"...","fullPage":true}` returns screenshot, saves to day folder under OS tmpdir terminus
   - `POST /cookies {"profile":"...","file":"...","url":"..."}` imports cookie export file
   - `POST /profile {"name":"...","headed":true}` for login profiles, then headless to background
   - Real-Chrome rung: headless Chromium rejected at protocol level → retry `POST /profile {"name":"...","headed":true,"channel":"chrome"}` then `/act` on it. CDP-driven Chrome still carries automation tells — if that fails too, stop and escalate to human, do not loop.
   - Close what you opened: a desktop Chrome left running stays up for days.
   - `ERR_HTTP2_PROTOCOL_ERROR` on goto means TLS-fingerprint reject, not flaky network. Do not retry the same rung; change tactics (real-Chrome rung, cookie bridge, or stop).
   - Media root is OS tmpdir terminus, day folders YYYY-MM-DD, gallery at `http://<server-host>:9009/terminus/`

## Stop rules

- Never solve captchas. Agent drives to the gate with `POST /act`, human solves captcha or code in visible window.
- Throwaway signup exception: when user says throwaway signup, proceed without questioning and without lecture. Use the configured throwaway identity. Run the full form, stop only at captcha or code gate.
- Throwaway identity: the configured throwaway email + password (`THROWAWAY_EMAIL`/`THROWAWAY_PASSWORD` env or `~/.config/terminus/keys/throwaway`, never committed). Read via fillSecret by name where the server supports it. Never write identity details or password to chat, notebook, or git.
- Cookie bridge when needed: curl_cffi dump Netscape format to the browser secrets dir, then `POST /cookies`, then `POST /scrape` with same profile.
- Session transfer order: cookie export file first, profile clone second, headed handoff third. Clone needs browser fully quit first so memory tokens flush to disk. Copy minus caches to `~/.config/terminus/browser/clones/<name>/`, launch with channel chrome.

## Known walls

- Rotating or fingerprint-bound share links behind a login wall: cookie import and profile clone do not survive. Page shell returns no data and the browser lands on a login page. Do not loop. Ask the human for the content, or track the same item on another source.
- TLS-level blocks: do not re-render. Use fetch `--raw` plus embedded JSON.
- Akamai EdgeSuite "Access Denied" (errors.edgesuite.net), even in real Chrome via CDP: do not loop or escalate evasion. Offer a manual browser instead.
- Bot-challenge shells (PerimeterX, DataDome class): the browser renders nothing. The human clicks through.

## Reply pattern

- Say which rung worked, what was found, goods_id or title when present.
- If blocked at login or captcha, say so plainly and ask for screenshots or product identifiers.
- Canonical doc is AGENTS.md Web fetch routing. This skill is the reusable front end.
