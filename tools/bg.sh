#!/usr/bin/env bash
# bg.sh — run anything detached, get a ping on finish (terminus inbox only).
# usage: bg.sh <label> [--msg <text>] [--result <file>] <command> [args...]
# e.g.: bg.sh render ffmpeg -i in.mov out.mp4
# e.g.: bg.sh sample-render --msg "verify MP4 frames, stage, hand over gallery link" \
#         --result /tmp/sample-render-result.txt npx hyperframes render -o renders/x.mp4
#
# Final-response convention: --msg is static context (what "done" must still
# need: verify X, hand over Y). --result is a file the command writes with its
# handoff data (links, sizes, durations); bg.sh pastes it into the finish ping
# so the ping itself carries the handoff, not just a log tail. Keep result
# files to ONE clean line (printf, not log tails) — bg.sh collapses the rest.
# --quiet skips the inbox ping (tests, probes); the log is still written.
set -u
label="${1:?usage: bg.sh <label> [--msg <text>] [--result <file>] <command> [args...]}"; shift
msg=""
result=""
quiet=0
while [ $# -gt 0 ]; do
  case "${1:-}" in
    --msg) msg="${2:?--msg needs text}"; shift 2 ;;
    --result) result="${2:?--result needs a file path}"; shift 2 ;;
    --quiet) quiet=1; shift ;;
    --) shift; break ;;
    *) break ;;
  esac
done
if [ $# -eq 0 ]; then echo "usage: bg.sh <label> [--msg <text>] [--result <file>] <command> [args...]" >&2; exit 2; fi
safe="$(printf '%s' "$label" | tr -c 'A-Za-z0-9_-' '_')"
log="$HOME/.config/terminus/bg-${safe}.log"
token_file="$HOME/.config/terminus/browser/token"
(
  "$@" >"$log" 2>&1
  rc=$?
  last="$(tail -c 300 "$log" 2>/dev/null | tr -d '\000' | tail -n 3)"
  resdat=""
  if [ -n "$result" ] && [ -f "$result" ]; then
    resdat="$(head -c 1000 "$result" 2>/dev/null | tr -d '\000')"
  fi
  python3 - "$label" "$rc" "$last" "$token_file" "$msg" "$resdat" "$quiet" <<'EOF'
import json, re, sys, urllib.request
label, rc, last, token_file, msg, resdat, quiet = sys.argv[1:8]
if quiet == "1":
    sys.exit(0)
tick = "done" if rc == "0" else f"FAILED (exit {rc})"
noise = ("hf-progress", "Streaming frame", "hyperframes.dev", "Framey",
         "cdn.jsdelivr", "screenshot capture", "drawelement capture",
         "encode (during capture)", "rendered in", "Keep editing")
clean = [ln for ln in last.splitlines()
         if ln.strip() and not any(n in ln for n in noise)
         and not re.search(r"[█░]{4,}", ln)]
clean = clean[-4:]
rd = re.sub(r"\s+", " ", resdat).strip()
rd = " / ".join(seg.strip() for seg in re.split(r"[◇◆]", rd)
                if seg.strip() and not any(n in seg for n in noise))
rd = re.sub(r"Keep editing by chatting with Framey[^.]*\.?", "", rd).strip()
urls = re.findall(r"https?://\S+", rd)
dur = re.search(r"duration=([\d.]+)", rd)
size = re.search(r"bytes=(\d+)", rd)
rest = re.sub(r"https?://\S+", "", rd)
rest = re.sub(r"\bduration=[\d.]+", "", rest)
rest = re.sub(r"\bbytes=\d+", "", rest)
rest = re.sub(r"\b\w+=", "", rest)
rest = re.sub(r"\s{2,}", " ", rest).strip(" /;,. ")
bits = []
bits.extend(u.rstrip(").,") for u in urls[:3])
if dur:
    bits.append(f"{float(dur.group(1)):.1f}s")
if size:
    bits.append(f"{int(size.group(1)) / 1e6:.1f}MB")
if rest:
    bits.append(rest[:300])
parts = []
if bits:
    parts.append("result: " + " · ".join(bits))
if msg:
    parts.append(f"next: {msg}")
if clean and (rc != "0" or not bits):
    parts.append("tail: " + " / ".join(clean)[-500:])
body = " — " + " — ".join(parts) if parts else " — no output"
try:
    token = open(token_file).read().strip()
    payload = json.dumps({"job": "ops", "title": f"bg {tick}: {label}",
                          "body": body[-1800:]}).encode()
    req = urllib.request.Request("http://127.0.0.1:9009/api/inbox", data=payload,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    urllib.request.urlopen(req, timeout=15).read()
except Exception:
    pass
EOF
) >/dev/null 2>&1 & disown
echo "backgrounded: $label → $log"
