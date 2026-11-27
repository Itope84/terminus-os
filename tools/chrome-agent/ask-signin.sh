#!/bin/bash
# For agents: ask the owner to sign in to a site in the agents' Chrome, and wait for them.
#   tools/chrome-agent/ask-signin.sh <url> [reason] [chatId]
# Opens the url in a new tab of the agents' Chrome, notifies the owner's phone (Terminus → Mac
# browser), then waits up to an hour. Exits 0 when they tap Done, 2 if they tap Can't, 3 on timeout.
# Never ask for passwords in chat; this is the way.
set -euo pipefail
URL="${1:?url}"; REASON="${2:-}"; CHAT="${3:-${TERMINUS_CHAT_ID:-}}"
BASE="${TERMINUS_URL:-http://127.0.0.1:9009}"
AUTH="Authorization: Bearer $(cat "$HOME/.config/terminus/browser/token")"
body=$(python3 -c 'import json,sys; print(json.dumps({"url":sys.argv[1],"reason":sys.argv[2],"chatId":sys.argv[3]}))' "$URL" "$REASON" "$CHAT")
id=$(curl -fsS -H "$AUTH" -H 'content-type: application/json' -d "$body" "$BASE/api/pwa/signin" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "asked the owner to sign in ($id); waiting…" >&2
for _ in 1 2 3 4 5 6; do
  state=$(curl -fsS -m 620 -H "$AUTH" "$BASE/api/pwa/signin?id=$id&wait=600" | python3 -c 'import json,sys; print(json.load(sys.stdin)["request"]["state"])')
  case "$state" in
    done) echo "signed in"; exit 0 ;;
    cancelled) echo "the owner couldn't sign in"; exit 2 ;;
  esac
done
echo "no answer within an hour"; exit 3
