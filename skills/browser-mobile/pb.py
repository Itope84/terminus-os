#!/usr/bin/env python3
"""pb — drive the phone's agent browser in one line per step, plain-text output.

  pb.py --tab abcd open https://example.com     # open + observe
  pb.py --tab abcd observe
  pb.py --tab abcd click @e3 [--label "Add to basket"]
  pb.py --tab abcd click "Add to basket"         # by visible text: finds it, clicks if exactly one control matches
  pb.py --tab abcd find "red kettle"             # the blocks containing the text, with refs; first scrolled into view
  pb.py --tab abcd extract text|tables|links [maxChars]   # whole-page read past the observe limit, no refs
  pb.py --tab abcd fill @e5 "text"   |  select @e6 "value"  |  check @e7 on|off
  pb.py --tab abcd press Enter       |  scroll down [2]     |  scrollto @e9
  pb.py --tab abcd back | forward | reload | wait 1500
  pb.py --tab abcd shot              |  note "progress line"
  pb.py --tab abcd handoff "reason"  |  wait-handoff        |  close
  pb.py --tab abcd raw '{"cmd":"batch","actions":[...]}'
  pb.py status

Every acting command observes in the same round trip, so never follow an act with a separate observe.
--tab: 4 letters picked once per conversation (or PB_TAB). --by defaults to $PB_BY or "agent".
Reads the narrow phone-browser token from ~/.config/terminus/browser/pb-token; never prints it.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("PB_URL", "http://127.0.0.1:9009")
TOKEN_FILE = os.path.expanduser("~/.config/terminus/browser/pb-token")


def call(path, body=None, timeout=200):
    tok = open(TOKEN_FILE).read().strip()
    req = urllib.request.Request(BASE + path, method="POST" if body is not None else "GET",
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"},
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read() or b"{}")
        except ValueError:
            return {"ok": False, "error": f"http {e.code}"}
    except (urllib.error.URLError, TimeoutError) as e:
        return {"ok": False, "error": f"unreachable: {e}"}


def render(resp):
    """Plain text: warning first, then outcome lines, then the page."""
    out = []
    if resp.get("warning"):
        out.append("!! WARNING: " + resp["warning"])
    if not resp.get("ok", False):
        out.append("ERROR: " + str(resp.get("error") or resp))
        return "\n".join(out)
    reply = resp.get("reply")
    if not isinstance(reply, dict):
        out.append(str(reply))
        return "\n".join(out)
    if reply.get("error"):
        err = reply["error"]
        out.append("ERROR: " + (json.dumps(err) if not isinstance(err, str) else err))
    reports = reply.get("reports") or ([reply["report"]] if reply.get("report") else [])
    for r in reports:
        line = r.get("summary") or f"{(r.get('action') or {}).get('type')} → {r.get('status')}"
        if r.get("error"):
            line += f"  [{r['error'].get('code') if isinstance(r['error'], dict) else r['error']}]"
        out.append("• " + line)
        for ev in r.get("events") or []:
            if ev.get("type") == "console":
                out.append(f"  console.error: {str(ev.get('text'))[:160]}")
            elif ev.get("type") not in ("action", "settle", "page_started", "page_finished", "navigated"):
                out.append(f"  {ev.get('type')}: " + json.dumps({k: v for k, v in ev.items() if k not in ('seq', 'time', 'type')})[:160])
    if reply.get("skipped"):
        out.append(f"• skipped {reply['skipped']} later action(s)")
    for key in ("find", "extract"):
        block = reply.get(key)
        if isinstance(block, dict):
            if block.get("warnings"):
                out.append("page warnings: " + "; ".join(map(str, block["warnings"]))[:300])
            out.append(block.get("text", ""))
            return "\n".join(x for x in out if x)
    obs = reply.get("observation") or (reply if "text" in reply and "url" in reply else None)
    if obs:
        for key in ("handoff", "pendingDialog"):
            if obs.get(key):
                out.append(f"{key}: {json.dumps(obs[key])[:200]}")
        if obs.get("warnings"):
            out.append("page warnings: " + "; ".join(map(str, obs["warnings"]))[:300])
        out.append(obs.get("text", ""))
        if obs.get("truncated"):
            out.append("[truncated: scroll or scrollto for more]")
    if reply.get("file"):
        out.append(f"screenshot: {reply['file']} ({reply.get('width')}x{reply.get('height')})")
        return "\n".join(out)
    rest = {k: v for k, v in reply.items() if k not in ("id", "ok", "report", "reports", "observation", "error", "skipped")}
    if rest and not obs:
        out.append(json.dumps(rest)[:1500])
    return "\n".join(x for x in out if x) or "ok"


def main(argv):
    tab = os.environ.get("PB_TAB", "")
    by = os.environ.get("PB_BY", "agent")
    label = None
    args = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--tab", "--by", "--label") and i + 1 < len(argv):
            if a == "--tab":
                tab = argv[i + 1]
            elif a == "--by":
                by = argv[i + 1]
            else:
                label = argv[i + 1]
            i += 2
            continue
        args.append(a)
        i += 1
    if not args or args[0] in ("-h", "--help", "help"):
        print(__doc__)
        return 0
    verb, rest = args[0], args[1:]
    if verb == "status":
        print(json.dumps(call("/api/pb/status")))
        return 0
    if not tab:
        print("ERROR: pass --tab <4 letters> (pick once per conversation, reuse on every call)")
        return 2

    def act(action):
        if label:
            action["label"] = label
        return {"cmd": "act", "action": action, "observe": True}

    def need(n):
        if len(rest) < n:
            raise SystemExit(f"ERROR: {verb} needs {n} argument(s); see --help")

    if verb == "open":
        need(1); cmd = {"cmd": "open", "url": rest[0], "observe": True}
    elif verb == "observe":
        cmd = {"cmd": "observe"}
    elif verb == "click" and rest and not re.fullmatch(r"@e\d+", rest[0]):
        # Click by text: find the blocks, then the one control whose name contains the text.
        q = " ".join(rest)
        found = call("/api/pb/cmd", {"by": by, "tab": tab, "command": {"cmd": "find", "text": q}, "timeoutMs": 60000})
        text = ((found.get("reply") or {}).get("find") or {}).get("text", "") if found.get("ok") else ""
        cands = []
        prev = ""
        for line in text.splitlines():
            m = re.search(r'\[(@e\d+)\] (\w+)(?: "([^"]*)")?', line)
            # A headline link prints without its name (it repeats the heading line above it).
            name = (m.group(3) or prev) if m else ""
            if m and q.lower() in name.lower() and m.group(1) not in [c[0] for c in cands]:
                cands.append((m.group(1), m.group(2), name))
            hm = re.search(r'heading\(\d\) "([^"]*)"', line)
            prev = hm.group(1) if hm else ""
        if len(cands) != 1:
            print(render(found))
            print(f"click \"{q}\": {len(cands)} matching controls; pick a ref above and click it" if cands or found.get("ok")
                  else "")
            return 1
        cmd = act({"type": "click", "ref": cands[0][0]})
    elif verb == "click":
        need(1); cmd = act({"type": "click", "ref": rest[0]})
    elif verb == "find":
        need(1); cmd = {"cmd": "find", "text": " ".join(rest)}
    elif verb == "extract":
        cmd = {"cmd": "extract", "what": rest[0] if rest else "text"}
        if len(rest) > 1:
            cmd["maxChars"] = int(rest[1])
    elif verb == "fill":
        need(2); cmd = act({"type": "fill", "ref": rest[0], "text": rest[1]})
    elif verb == "select":
        need(2); cmd = act({"type": "select", "ref": rest[0], "value": rest[1]})
    elif verb == "check":
        need(2); cmd = act({"type": "check", "ref": rest[0], "checked": rest[1].lower() in ("on", "true", "1", "yes")})
    elif verb == "press":
        need(1); cmd = act({"type": "press", "key": rest[0]})
    elif verb == "scroll":
        direction = rest[0] if rest else "down"
        cmd = act({"type": "scroll", "dir": direction, "amount": float(rest[1]) if len(rest) > 1 else 1})
    elif verb == "scrollto":
        need(1); cmd = act({"type": "scroll_to", "ref": rest[0]})
    elif verb in ("back", "forward", "reload"):
        cmd = act({"type": verb})
    elif verb == "wait":
        need(1); cmd = act({"type": "wait", "ms": int(rest[0])})
    elif verb == "shot":
        cmd = {"cmd": "screenshot", "maxWidth": int(rest[0]) if rest else 720}
    elif verb == "note":
        need(1); cmd = {"cmd": "note", "text": " ".join(rest)}
    elif verb == "handoff":
        cmd = {"cmd": "handoff", "reason": " ".join(rest) or "Your turn on the phone"}
    elif verb == "wait-handoff":
        # Loop await_handoff until the person hands back; phone_offline and timeouts keep waiting (up to ~15 min).
        deadline = time.time() + 900
        while time.time() < deadline:
            resp = call("/api/pb/cmd", {"by": by, "tab": tab, "command": {"cmd": "await_handoff"}, "timeoutMs": 170000})
            txt = json.dumps(resp)
            if "still_waiting" in txt or "phone_offline" in txt or "phone_timeout" in txt:
                continue
            print(render(resp))
            print("(handed back: observe before acting)")
            return 0
        print("ERROR: still waiting after 15 min; ask the user in chat")
        return 1
    elif verb == "close":
        cmd = {"cmd": "close"}
    elif verb == "raw":
        need(1); cmd = json.loads(rest[0])
    else:
        print(f"ERROR: unknown command {verb!r}; see --help")
        return 2
    resp = call("/api/pb/cmd", {"by": by, "tab": tab, "command": cmd, "timeoutMs": 90000})
    print(render(resp))
    return 0 if resp.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
