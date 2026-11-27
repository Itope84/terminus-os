#!/bin/bash
# Terminus's own OpenCode 2 (the server speaks OpenCode's v2 API), separate from any OpenCode 1.x you
# already run: its own install, data, state and cache under ~/.local/share/terminus/opencode2, sharing
# only your config (~/.config/opencode) and a copy of your provider logins. Serves 127.0.0.1:8093 with a
# password kept in ~/.config/terminus/secrets/opencode2.pass. LaunchAgent me.terminus.opencode2.
# Uninstall: launchctl bootout gui/$(id -u)/me.terminus.opencode2 && rm ~/Library/LaunchAgents/me.terminus.opencode2.plist
set -euo pipefail
VERSION="${OPENCODE2_VERSION:-2.3.2}"
D="$HOME/.local/share/terminus/opencode2"
LABEL=me.terminus.opencode2
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
SECRET="$HOME/.config/terminus/secrets/opencode2.pass"
mkdir -p "$D/xdg/data/opencode" "$D/xdg/state" "$D/xdg/cache" "$(dirname "$SECRET")" "$HOME/Library/Logs/terminus"
[ -f "$D/package.json" ] || (cd "$D" && npm init -y >/dev/null)
(cd "$D" && npm install --no-audit --no-fund "@opencode/cli@$VERSION" >/dev/null)
# Provider logins: copy once from OpenCode 1.x (run `opencode2 auth login` style changes there later).
[ -f "$D/xdg/data/opencode/auth.json" ] || { [ -f "$HOME/.local/share/opencode/auth.json" ] && cp "$HOME/.local/share/opencode/auth.json" "$D/xdg/data/opencode/auth.json"; }
chmod 600 "$D/xdg/data/opencode/auth.json" 2>/dev/null || true
[ -f "$SECRET" ] || (umask 077 && openssl rand -hex 24 > "$SECRET")
chmod 600 "$SECRET"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string><string>-c</string>
    <string>export OPENCODE_SERVER_PASSWORD="\$(cat '$SECRET')"; exec '$D/node_modules/.bin/opencode' serve --hostname 127.0.0.1 --port 8093</string>
  </array>
  <key>WorkingDirectory</key><string>$HOME</string>
  <key>EnvironmentVariables</key><dict>
    <key>XDG_DATA_HOME</key><string>$D/xdg/data</string>
    <key>XDG_STATE_HOME</key><string>$D/xdg/state</string>
    <key>XDG_CACHE_HOME</key><string>$D/xdg/cache</string>
    <key>PATH</key><string>$HOME/.local/bin:$(dirname "$(command -v node)"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/terminus/opencode2.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/terminus/opencode2.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL (OpenCode $VERSION) on http://127.0.0.1:8093"
