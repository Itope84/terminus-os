#!/usr/bin/env python3
"""Monthly cleanup for schedule + day media. Reusable, safe by default.

Trims (keeps tail, never deletes whole file):
  fired.log / ticker.log / ticker.err.log
Prunes:
  day media folders older than N days, retired jobs older than N days.
Never touches: auth, config, secrets, notebook, active jobs, node_modules.

  python3 checks/cleanup.py [--dry-run]
Output is a short phone-readable summary with bytes freed.
"""
import argparse
import datetime
import json
import os
import tempfile

STORE = os.path.expanduser("~/.config/terminus/schedule")
JOBS_FILE = os.path.join(STORE, "jobs.json")


def media_root():
    return os.path.join(tempfile.gettempdir(), "terminus")


def trim_tail(path, keep_lines):
    """Keep last keep_lines lines. Returns (before, after) bytes."""
    try:
        if not os.path.exists(path):
            return 0, 0
        before = os.path.getsize(path)
        with open(path, "rb") as f:
            lines = f.read().splitlines(keepends=True)
        if len(lines) <= keep_lines:
            return before, before
        with open(path, "wb") as f:
            f.writelines(lines[-keep_lines:])
        return before, os.path.getsize(path)
    except OSError:
        return 0, 0


def prune_media(days, dry_run):
    root = media_root()
    freed = 0
    removed = 0
    try:
        names = os.listdir(root)
    except OSError:
        return 0, 0
    cutoff = datetime.date.today() - datetime.timedelta(days=days)
    for name in names:
        p = os.path.join(root, name)
        try:
            d = datetime.date.fromisoformat(name)
        except ValueError:
            continue
        if d < cutoff and os.path.isdir(p):
            size = 0
            for dp, _, fns in os.walk(p):
                for fn in fns:
                    try:
                        size += os.path.getsize(os.path.join(dp, fn))
                    except OSError:
                        pass
            if not dry_run:
                import shutil
                shutil.rmtree(p, ignore_errors=True)
            freed += size
            removed += 1
    return removed, freed


def purge_retired(days, dry_run):
    """Drop retired jobs older than days. Returns count removed."""
    try:
        with open(JOBS_FILE) as f:
            jobs = json.load(f).get("jobs", [])
    except (FileNotFoundError, json.JSONDecodeError):
        return 0
    cutoff = datetime.datetime.now() - datetime.timedelta(days=days)
    kept, dropped = [], 0
    for j in jobs:
        ra = j.get("retired_at")
        if not ra:
            kept.append(j)
            continue
        try:
            dt = datetime.datetime.fromisoformat(ra)
        except ValueError:
            kept.append(j)
            continue
        if dt < cutoff:
            dropped += 1
        else:
            kept.append(j)
    if dropped and not dry_run:
        tmp = JOBS_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"jobs": kept}, f, indent=2)
        os.replace(tmp, JOBS_FILE)
    return dropped


def fmt(n):
    for unit in ("B", "KB", "MB"):
        if n < 1024 or unit == "MB":
            return f"{n:.0f}{unit}" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024.0
    return f"{n:.1f}GB"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep-fired", type=int, default=500)
    ap.add_argument("--keep-ticker", type=int, default=200)
    ap.add_argument("--media-days", type=int, default=7)
    ap.add_argument("--retired-days", type=int, default=30)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    total_freed = 0
    lines = []
    for path, keep, tag in [
        (os.path.join(STORE, "fired.log"), a.keep_fired, "fired.log"),
        (os.path.join(STORE, "ticker.log"), a.keep_ticker, "ticker.log"),
        (os.path.join(STORE, "ticker.err.log"), a.keep_ticker, "ticker.err.log"),
    ]:
        if a.dry_run:
            try:
                size = os.path.getsize(path)
            except OSError:
                continue
            lines.append(f"{tag}: {fmt(size)} (would trim)")
        else:
            b, aft = trim_tail(path, keep)
            total_freed += max(0, b - aft)
    n_dirs, media_freed = prune_media(a.media_days, a.dry_run)
    if not a.dry_run:
        total_freed += media_freed
    n_jobs = purge_retired(a.retired_days, a.dry_run)

    mode = "DRY-RUN " if a.dry_run else ""
    print(f"{mode}Cleanup: freed {fmt(total_freed)}")
    for ln in lines:
        print(f"• {ln}")
    print(f"• media folders removed: {n_dirs} (older than {a.media_days}d)")
    print(f"• retired jobs purged: {n_jobs} (older than {a.retired_days}d)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
