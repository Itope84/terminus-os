#!/usr/bin/env python3
"""Per-domain browser playbook: what worked last time on this site, so repeat
visits skip rediscovery. Store is <TERMINUS_DIR>/.run/browser-memory.jsonl
(repo-local runtime dir, never committed).

  browser-memory.py lookup <domain-or-url>   prints the entry, or UNKNOWN
  browser-memory.py note --domain D [--cookie-ref R --cookie-text T]
      [--search-ref R --search-how H] [--wall W]... [--tip T]... [--url U]

Refs (@eN) die on navigation, so record the pattern (text/role), not just the ref.
"""
import argparse
import datetime as dt
import json
import os
import sys
import urllib.parse

BASE = os.environ.get("TERMINUS_DIR") or os.path.abspath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", ".."))
STORE = os.path.join(BASE, ".run", "browser-memory.jsonl")


def domain_of(s):
    s = (s or "").strip().lower()
    if "://" not in s:
        s = "https://" + s
    try:
        host = urllib.parse.urlsplit(s).hostname or ""
    except Exception:
        host = ""
    if host.startswith("www."):
        host = host[4:]
    return host


def load():
    out = {}
    try:
        with open(STORE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    e = json.loads(line)
                except Exception:
                    continue
                if isinstance(e, dict) and e.get("domain"):
                    out[e["domain"]] = e
    except FileNotFoundError:
        pass
    return out


def save(entries):
    os.makedirs(os.path.dirname(STORE), exist_ok=True)
    tmp = STORE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for key in sorted(entries):
            f.write(json.dumps(entries[key]) + "\n")
    os.replace(tmp, STORE)


def show(e):
    lines = [f"{e['domain']} (visits {e.get('visits', 1)}, last {e.get('last_seen', '?')})"]
    cb = e.get("cookie_banner") or {}
    if cb:
        lines.append("cookie: %s %s" % (cb.get("text") or cb.get("ref") or "?", "WORKS" if cb.get("works") else ""))
    se = e.get("search") or {}
    if se:
        lines.append("search: %s %s" % (se.get("ref") or "?", se.get("how") or ""))
    for w in e.get("walls", []):
        lines.append("wall: " + w)
    for t in e.get("tips", []):
        lines.append("tip: " + t)
    print("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("lookup")
    l.add_argument("domain")
    n = sub.add_parser("note")
    n.add_argument("--domain", required=True)
    n.add_argument("--cookie-ref", default="")
    n.add_argument("--cookie-text", default="")
    n.add_argument("--search-ref", default="")
    n.add_argument("--search-how", default="")
    n.add_argument("--wall", action="append", default=[])
    n.add_argument("--tip", action="append", default=[])
    n.add_argument("--url", default="")
    a = ap.parse_args()
    entries = load()
    if a.cmd == "lookup":
        d = domain_of(a.domain)
        e = entries.get(d)
        if not e:
            print("UNKNOWN|" + d)
            return 0
        show(e)
        return 0
    d = domain_of(a.domain)
    if not d:
        print("note: no usable domain", file=sys.stderr)
        return 1
    today = str(dt.date.today())
    e = entries.get(d, {"domain": d, "visits": 0, "first_seen": today,
                        "walls": [], "tips": []})
    e["visits"] = e.get("visits", 0) + 1
    e["last_seen"] = today
    if a.cookie_ref or a.cookie_text:
        e["cookie_banner"] = {"ref": a.cookie_ref, "text": a.cookie_text, "works": True}
    if a.search_ref or a.search_how:
        e["search"] = {"ref": a.search_ref, "how": a.search_how}
    for w in a.wall:
        if w and w not in e.setdefault("walls", []):
            e["walls"].append(w)
    for t in a.tip:
        if t and t not in e.setdefault("tips", []):
            e["tips"].append(t)
    if a.url and a.url not in e.setdefault("urls", [])[-5:]:
        e.setdefault("urls", []).append(a.url)
        e["urls"] = e["urls"][-5:]
    entries[d] = e
    save(entries)
    print("noted " + d)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
