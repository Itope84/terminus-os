#!/bin/bash
# Installs the agents' Chrome as a login item (LaunchAgent me.terminus.chrome-agent), restarted if it
# quits. Uninstall: launchctl bootout gui/$(id -u)/me.terminus.chrome-agent && rm the plist.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL=me.terminus.chrome-agent
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
mkdir -p "$HOME/Library/Logs/terminus"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$HERE/chrome-agent.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/terminus/chrome-agent.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/terminus/chrome-agent.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL; DevTools on http://127.0.0.1:${TERMINUS_CHROME_PORT:-9222}"
