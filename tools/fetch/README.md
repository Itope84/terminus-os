# fetch — local fallback fetcher

No third-party network services. Chrome TLS impersonation (`curl_cffi`)
plus clean-text extraction (`trafilatura`), all on this machine.

```sh
cd terminus/tools/fetch
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python fetch.py <url> [--raw] [--impersonate chrome|safari]
```

Single source: `AGENTS.md` Web fetch routing. Climb webfetch → fetch → browser; TLS-level walls = fetch `--raw` + parse JSON.

## Walls log (tried, dead — don't retry blindly)

- DataDome-class wall: raw fetch 403, browser blank, undetected-chromedriver blank too. Human-click territory.
- PerimeterX-class shell: fetch challenge shell, browser renders nothing. Human-click territory.
- Akamai-class wall: 403 raw and in the browser. Do not escalate evasion.
- Empty shell on both rungs: human-click territory.
- Expedia hotel finder: fetch 429 walled, browser serves a literal "Bot or Not?" challenge page. Human-click territory.
- TikTok search: 353KB shell, zero results, login markers. Websearch indexes the videos — use that, never scrape direct.

## Stealth browsers

- `invisible_playwright` (stealth Firefox, 3k stars): **no macOS binaries** — refuses on Mac (verified 3 Oct 2026, isolated venv). No trial possible here; revisit only on Linux infra.
