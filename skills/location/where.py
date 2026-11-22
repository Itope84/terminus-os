#!/usr/bin/env python3
"""The phone's location, from the Mac (server GET /api/location; the phone answers over its live link).

  where.py                      -> JSON {lat, lng, accuracy, at, ageSec, fresh[, note]} or {error}
  where.py --max-age 300        -> reuse a fix up to 5 min old without asking the phone
  where.py near --lat 48.8584 --lng 2.2945 --radius 400 --name "Example Station"
                                -> one line when within the radius, else SILENT (for schedule exec jobs)
  where.py distance --lat .. --lng ..   -> metres from here, one number

Exit 0 on an answer (including SILENT), 2 when the phone can't be asked and nothing is known, 3 when
location permission is still needed (the phone has shown the user an "Allow location" notification).
`near` never fails a tick: phone unreachable = SILENT; permission missing = one line saying so.
"""
import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("TERMINUS_URL", "http://127.0.0.1:9009")
TOKEN_FILE = os.path.expanduser("~/.config/terminus/browser/token")


def fetch(max_age=None, wait=20):
    q = f"?wait={int(wait)}" + (f"&maxAge={int(max_age)}" if max_age else "")
    req = urllib.request.Request(BASE + "/api/location" + q,
                                 headers={"Authorization": "Bearer " + open(TOKEN_FILE).read().strip()})
    try:
        with urllib.request.urlopen(req, timeout=wait + 10) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)
        except Exception:
            return {"error": f"http {e.code}"}
    except Exception as e:
        return {"error": f"server unreachable ({type(e).__name__})"}


def metres(a_lat, a_lng, b_lat, b_lng):
    r = 6371000.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lng - a_lng)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def fmt(m):
    return f"{m / 1000:.1f} km" if m >= 1000 else f"{round(m / 10) * 10:.0f} m"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--max-age", type=int)
    ap.add_argument("--wait", type=int, default=20)
    sub = ap.add_subparsers(dest="cmd")
    for name in ("near", "distance"):
        s = sub.add_parser(name)
        s.add_argument("--lat", type=float, required=True)
        s.add_argument("--lng", type=float, required=True)
        if name == "near":
            s.add_argument("--radius", type=float, default=400)
            s.add_argument("--name", default="the place")
    a = ap.parse_args()
    fix = fetch(a.max_age, a.wait)
    if "error" in fix and a.cmd == "near":
        # A watcher ticks every few minutes: a phone that's briefly offline is normal, not an alert.
        if fix["error"] == "permission_needed":
            print(f"Can't watch for {a.name}: location isn't allowed for Terminus yet. Tap the 'Allow location' notification, then ask again.")
        else:
            print("SILENT")
        return 0
    if "error" in fix:
        if fix["error"] == "permission_needed":
            print("location permission needed: the phone is asking the user", file=sys.stderr)
            return 3
        print(json.dumps(fix) if not a.cmd else f"no location: {fix['error']}", file=sys.stderr if a.cmd else sys.stdout)
        return 2
    if not a.cmd:
        print(json.dumps(fix))
        return 0
    d = metres(fix["lat"], fix["lng"], a.lat, a.lng)
    if a.cmd == "distance":
        print(round(d))
        return 0
    stale = "" if fix.get("fresh") else f" (position {fix.get('ageSec', 0) // 60} min old)"
    print(f"You're {fmt(d)} from {a.name}{stale}." if d <= a.radius else "SILENT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
