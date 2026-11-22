#!/usr/bin/env python3
"""Reusable weather snapshot (no key). Open-Meteo, stdlib only.

One HTTP call per spot so each spot keeps its own timezone:
  python3 checks/weather.py --spot "Home:48.8566,2.3522:Europe/Paris" \
    --spot "Tokyo:35.6762,139.6503:Asia/Tokyo"

Spot format: Label:lat,lon[:Timezone]. Timezone defaults to UTC.
Wear hints are opt-in per label and reusable anywhere:
  python3 checks/weather.py --spot "Home:48.8566,2.3522:Europe/Paris" --wear Home
Output is one phone-ready line per spot:
  Home 🌧️ 19° (H21°/L12°) — rain ≈09:00-17:00 · light layer + brolly
Spots without --wear print weather only (use for a second city).
"""
import argparse
import json
import urllib.parse
import urllib.request

EMOJI = [
    (lambda c: c == 0, "☀️"),
    (lambda c: c in (1,), "🌤️"),
    (lambda c: c in (2,), "☁️"),
    (lambda c: c == 3, "☁️"),
    (lambda c: c in (45, 48), "🌫️"),
    (lambda c: c in (51, 53, 55, 56, 57), "🌦️"),
    (lambda c: c in (61, 63, 65, 66, 67, 80, 81, 82), "🌧️"),
    (lambda c: c in (71, 73, 75, 77, 85, 86), "🌨️"),
    (lambda c: c in (95, 96, 99), "⛈️"),
]


def emoji_for(code):
    try:
        c = int(code)
    except (TypeError, ValueError):
        return "☁️"
    for pred, e in EMOJI:
        if pred(c):
            return e
    return "☁️"


def parse_spot(spec):
    parts = spec.split(":")
    if len(parts) < 2:
        raise ValueError(f"bad --spot {spec!r}, want Label:lat,lon[:Timezone]")
    label = parts[0].strip() or "Spot"
    tz = parts[3] if len(parts) > 3 else (parts[2] if len(parts) > 2 and "/" in parts[2] else "UTC")
    latlon = parts[1] if len(parts) == 2 else parts[1] + ":" + parts[2] if "/" not in parts[2] and len(parts) == 3 and "," not in parts[2] else parts[1]
    # Handle lat,lon possibly split across colons (IPv6 never; tz contains slash).
    # Rebuild: everything between label and trailing tz is lat,lon.
    body = spec[len(label) + 1:]
    if body.endswith(":" + tz) and tz != body:
        body = body[:-(len(tz) + 1)]
    latlon = body
    try:
        lat_s, lon_s = latlon.split(",", 1)
        lat, lon = float(lat_s), float(lon_s)
    except ValueError:
        raise ValueError(f"bad --spot {spec!r}, want Label:lat,lon[:Timezone]")
    return label, lat, lon, tz


def fetch_spot(label, lat, lon, tz, wear=False):
    q = urllib.parse.urlencode({
        "latitude": lat, "longitude": lon,
        "current": "temperature_2m,apparent_temperature,weathercode,wind_speed_10m",
        "hourly": "temperature_2m,precipitation_probability,weathercode",
        "daily": "temperature_2m_max,temperature_2m_min",
        "timezone": tz, "forecast_days": 2, "past_days": 1,
    })
    url = "https://api.open-meteo.com/v1/forecast?" + q
    req = urllib.request.Request(url, headers={"User-Agent": "terminus-weather/1.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.load(r)
    cur = d.get("current", {})
    temp = cur.get("temperature_2m")
    feels = cur.get("apparent_temperature", temp)
    wind = cur.get("wind_speed_10m", 0) or 0
    code = cur.get("weathercode", 3)
    hourly = d.get("hourly", {})
    times = hourly.get("time", [])
    probs = hourly.get("precipitation_probability", [])
    # Rain window today: hours with prob >= 50, using local ISO times.
    # Note: with past_days=1, hourly starts yesterday, so take today
    # from current.time, not times[0].
    today = (cur.get("time", "") or "")[:10] or (times[0][:10] if times else "")
    wet = [t[11:16] for t, p in zip(times, probs)
           if t.startswith(today) and isinstance(p, (int, float)) and p >= 50]
    rained = bool(wet)
    if not wet:
        rain = "dry day"
    elif len(wet) >= 20:
        rain = "rain all day"
    else:
        # Compress to first-last; note peak as mid.
        rain = f"rain \u2248{wet[0]}-{wet[-1]}"
    t_str = f"{round(float(temp))}\u00b0" if isinstance(temp, (int, float)) else "n/a"
    hl = ""
    try:
        days = d.get("daily", {}) or {}
        tmax = days.get("temperature_2m_max", []) or []
        tmin = days.get("temperature_2m_min", []) or []
        # past_days=1 + forecast_days=2 → [yesterday, today, tomorrow].
        hi, lo = round(float(tmax[1])), round(float(tmin[1]))
        hl = f" (H{hi}\u00b0/L{lo}\u00b0)"
    except (TypeError, ValueError, IndexError):
        pass
    line = f"{label} {emoji_for(code)} {t_str}{hl} \u2014 {rain}"
    vs = day_delta(d)
    if vs:
        line += f" \u00b7 {vs}"
    if wear:
        line += f" \u00b7 {wear_hint(feels, rained, wind)}"
    return line


def day_delta(d):
    """Day-over-day max-temp comparison, e.g. ≈2° cooler vs yesterday."""
    try:
        days = (d.get("daily", {}) or {})
        tmax = days.get("temperature_2m_max", []) or []
        if len(tmax) < 2:
            return ""
        # past_days=1 + forecast_days=2 → [yesterday, today, tomorrow].
        y, t = float(tmax[0]), float(tmax[1])
        delta = round(t - y)
        if abs(t - y) < 0.5:
            return "same as yesterday"
        word = "warmer" if delta > 0 else "cooler"
        return f"\u2248{abs(delta)}\u00b0 {word} vs yesterday"
    except (TypeError, ValueError, IndexError):
        return ""


def wear_hint(feels, rained, wind):
    """Short wear hint from feels-like temp, rain, wind. Reusable anywhere."""
    try:
        t = float(feels)
    except (TypeError, ValueError):
        base = "check layers"
        return base + (" + brolly" if rained else "")
    if t <= 2:
        base = "heavy coat + hat + gloves"
    elif t <= 5:
        base = "heavy coat + hat"
    elif t <= 10:
        base = "warm coat"
    elif t <= 14:
        base = "light jacket"
    elif t <= 18:
        base = "light layer"
    elif t <= 23:
        base = "tee + layer handy"
    else:
        base = "tee, sun ok"
    extras = []
    if rained:
        extras.append("brolly")
    try:
        if float(wind) >= 30:
            extras.append("windproof")
    except (TypeError, ValueError):
        pass
    return base + (" + " + " + ".join(extras) if extras else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spot", action="append", required=True,
                    help="Label:lat,lon[:Timezone], repeatable")
    ap.add_argument("--wear", action="append", default=[],
                    help="label getting a wear hint, repeatable (e.g. Home)")
    args = ap.parse_args()
    wear_set = {w.strip().lower() for w in args.wear}
    for spec in args.spot:
        try:
            label, lat, lon, tz = parse_spot(spec)
            print(fetch_spot(label, lat, lon, tz, wear=label.lower() in wear_set))
        except Exception as e:
            print(f"{spec.split(':')[0]} n/a \u2014 fetch failed: {e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
