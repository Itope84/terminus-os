#!/usr/bin/env python3
"""Reusable system snapshot for phone pushes. Stdlib + local cmds only.

Output (one widget line, phone-readable):
  RAM 73% · CPU 1.32 · Disk 80% free · up 147d · top hog opencode ≈0.7GB
"""
import os
import shutil
import subprocess
import time


def run(cmd):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=10)
        return (r.stdout or "").strip()
    except Exception:
        return ""


def ram_used_pct():
    """RAM used %. One fallback: memory_pressure header for the total."""
    try:
        mem_total = int(run("sysctl -n hw.memsize") or 0)
    except ValueError:
        mem_total = 0
    if not mem_total:
        # hw.physmem lies on Apple Silicon (32-bit truncation), so parse
        # "The system has 17179869184 (4194304 pages ...)" instead.
        # No further fallbacks: top says 83% and free % says 19% right
        # now, so a wrong-feeling number is worse than n/a.
        for line in run("memory_pressure").splitlines():
            if "system has" in line:
                try:
                    mem_total = int(line.split("system has")[1].split()[0])
                except (IndexError, ValueError):
                    pass
                break
    if not mem_total:
        return "n/a"
    out = run("vm_stat")
    if not out:
        return "n/a"
    free = inactive = spec = 0
    page = 0
    for line in out.splitlines():
        if "page size of" in line:
            try:
                page = int(line.split("page size of")[1].split()[0])
            except (IndexError, ValueError):
                pass
        for key in ("Pages free:", "Pages inactive:", "Pages speculative:"):
            if key in line:
                try:
                    v = int(line.split(":")[1].strip().rstrip("."))
                except (IndexError, ValueError):
                    continue
                if "free" in key:
                    free = v
                elif "inactive" in key:
                    inactive = v
                else:
                    spec = v
    if not page:
        try:
            page = int(run("pagesize") or 0)
        except ValueError:
            return "n/a"
    avail = (free + inactive + spec) * page
    if avail <= 0:
        return "n/a"
    return f"{max(0, min(100, round((1 - avail / mem_total) * 100)))}%"


def main():
    used_pct = ram_used_pct()
    try:
        load = f"{os.getloadavg()[0]:.2f}"
    except OSError:
        load = "n/a"
    disk_free = "n/a"
    try:
        total, used, free = shutil.disk_usage("/")
        disk_free = f"{round(free / total * 100)}% free"
    except OSError:
        pass
    up = run("sysctl -n kern.boottime").strip()
    up_str = "n/a"
    try:
        # kern.boottime: { sec = 1757..., usec = 0 } ...
        sec = int(up.split("sec =")[1].split(",")[0].strip())
        days = max(0, int((time.time() - sec) // 86400))
        up_str = f"{days}d"
    except (IndexError, ValueError):
        b = run("uptime").strip()
        up_str = b[:40] if b else "n/a"
    top = run("ps -ax -o %mem,rss,command | sort -rn | head -5")
    hog = "n/a"
    for line in top.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0].replace(".", "", 1).isdigit():
            try:
                gb = int(parts[1]) / 1024 / 1024
                name = os.path.basename(parts[2])[:20] or parts[2][:20]
                hog = f"{name} \u2248{gb:.1f}GB"
                break
            except (IndexError, ValueError):
                continue
    print(f"RAM {used_pct} \u00b7 CPU {load} \u00b7 Disk {disk_free} \u00b7 up {up_str} \u00b7 top hog {hog}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
