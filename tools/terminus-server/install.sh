#!/bin/bash
# Installs the Terminus server as a login item (LaunchAgent me.terminus.server), restarted if it quits.
# HTTP on :9009 (loopback + Tailscale IP) and HTTPS on :9443 with the Tailscale certificate, which is
# what the iPhone web app at https://<mac>.ts.net:9443/app/ needs (Web Push and the mic need HTTPS).
# Settings come from ~/.config/terminus/server.env (created on first run; edit, then rerun this).
# Uninstall: launchctl bootout gui/$(id -u)/me.terminus.server && rm ~/Library/LaunchAgents/me.terminus.server.plist
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LABEL=me.terminus.server
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
ENVF="$HOME/.config/terminus/server.env"
NODE="$(command -v node)"
mkdir -p "$HOME/.config/terminus" "$HOME/Library/Logs/terminus"
if [ ! -f "$ENVF" ]; then
  cat > "$ENVF" <<ENV
# Terminus server settings (sourced by the LaunchAgent). One KEY=value per line.
OPENCODE_BASE_URL=http://127.0.0.1:8080
TERMINUS_TLS_CERT=
TERMINUS_TLS_KEY=
TERMINUS_TLS_PORT=9443
TERMINUS_CLAUDE_MCP_CONFIG=$ROOT/server/mcp/claude-browser.json
TERMINUS_CLAUDE_CHROME=1
ENV
  chmod 600 "$ENVF"
  echo "wrote $ENVF: set TERMINUS_TLS_CERT and TERMINUS_TLS_KEY, then rerun"
fi
(cd "$ROOT/server" && npm run build >/dev/null)
[ -f "$ROOT/web/dist/index.html" ] || (cd "$ROOT/web" && pnpm install --frozen-lockfile && pnpm build)
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string><string>-c</string>
    <string>set -a; . "$ENVF"; set +a; exec "$NODE" dist/server.js</string>
  </array>
  <key>WorkingDirectory</key><string>$ROOT/server</string>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$HOME/.local/bin:$(dirname "$NODE"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/terminus/server.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/terminus/server.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL; log: ~/Library/Logs/terminus/server.log"
