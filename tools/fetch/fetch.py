#!/usr/bin/env python3
"""Local fallback fetcher: Chrome-TLS impersonation + clean-text extraction.

No third-party network services. Everything runs on this machine:
  curl_cffi  - HTTP with real Chrome/Safari TLS + HTTP/2 fingerprints
  trafilatura - main-content extraction -> clean text/markdown

Usage:
  .venv/bin/python fetch.py <url> [--raw] [--impersonate chrome|safari]

Exit codes: 0 ok, 1 network/parse error, 2 HTTP error status.
HTTP errors print `STATUS <code> <url>` to stderr and still dump any body.
"""

import argparse
import sys

from curl_cffi import requests as crequests

IMPERSONATIONS = ("chrome", "chrome124", "safari", "safari18_0")

CHROME_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)
SAFARI_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) "
    "Version/18.2 Safari/605.1.15"
)


def fetch(url: str, impersonate: str | None) -> tuple[str, str, str]:
    """Return (html, final_url, impersonation_used). Raises on failure."""
    tried = [impersonate] if impersonate else list(IMPERSONATIONS)
    last_err: Exception | None = None
    for imp in tried:
        try:
            ua = SAFARI_UA if imp.startswith("safari") else CHROME_UA
            resp = crequests.get(
                url,
                impersonate=imp,  # type: ignore[arg-type]
                headers={
                    "User-Agent": ua,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-GB,en;q=0.9",
                },
                timeout=30,
                allow_redirects=True,
            )
            if resp.status_code >= 400:
                print(f"STATUS {resp.status_code} {resp.url}", file=sys.stderr)
                if resp.status_code in (403, 429) and imp != tried[-1]:
                    continue  # try next fingerprint
            return resp.text, str(resp.url), imp
        except Exception as e:  # noqa: BLE001 - report then try next
            last_err = e
    raise RuntimeError(f"all impersonations failed for {url}: {last_err}")


def to_markdown(html: str, url: str) -> str:
    from trafilatura import extract

    text = extract(
        html,
        include_comments=False,
        include_tables=True,
        favor_precision=True,
        url=url,
    )
    return text.strip() if text else ""


def main() -> int:
    ap = argparse.ArgumentParser(description="Fetch a URL with Chrome impersonation.")
    ap.add_argument("url")
    ap.add_argument("--raw", action="store_true", help="print raw HTML, skip extraction")
    ap.add_argument("--impersonate", default=None, help="force one fingerprint")
    args = ap.parse_args()

    try:
        html, final_url, imp = fetch(args.url, args.impersonate)
    except Exception as e:  # noqa: BLE001
        print(f"ERROR {e}", file=sys.stderr)
        return 1

    print(f"URL {final_url}", file=sys.stderr)
    print(f"VIA {imp}", file=sys.stderr)
    if args.raw:
        print(html)
        return 0
    text = to_markdown(html, final_url)
    if not text:
        print("EMPTY extraction fell back to raw length "
              f"{len(html)} chars; rerun with --raw", file=sys.stderr)
        return 1
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
