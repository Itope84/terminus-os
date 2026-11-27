#!/bin/bash
# The agents' own Chrome (browser tier 2): a real, visible Chrome with its own profile, listening for
# DevTools on loopback only. Chrome DevTools MCP attaches to it (server/mcp/claude-browser.json and
# OpenCode's config). Sign in to sites here once, by hand (Screen Sharing or at the Mac); the profile
# keeps the sessions. It is never your everyday Chrome profile.
set -euo pipefail
PORT="${TERMINUS_CHROME_PORT:-9222}"
PROFILE="${TERMINUS_CHROME_PROFILE:-$HOME/.config/terminus/chrome-agent}"
CHROME="${TERMINUS_CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
mkdir -p "$PROFILE"
chmod 700 "$PROFILE"
exec "$CHROME" \
  --remote-debugging-address=127.0.0.1 \
  --remote-debugging-port="$PORT" \
  --user-data-dir="$PROFILE" \
  --no-first-run \
  --no-default-browser-check \
  --disable-background-timer-throttling \
  --disable-renderer-backgrounding \
  about:blank
