/** Host stats for the ops "system" tab: memory (Activity-Monitor style on macOS), pressure, swap, CPU load and
 *  the biggest apps grouped by name. Every probe is best-effort; a failing one just drops its line. */
import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { cpus, loadavg, platform, totalmem, freemem, uptime as hostUptime } from "node:os";

export interface ProcGroup { name: string; count: number; rssKb: number; cpu: number }
export interface SystemStats {
  memTotal: number;
  memUsed: number;
  pressure: "normal" | "warn" | "critical" | "unknown";
  swapUsed: number | null;
  load: [number, number, number];
  cores: number;
  hostUptimeSec: number;
  groups: ProcGroup[];
  at: number;
}

function run(file: string, args: string[]): Promise<string> {
  return new Promise((resolve) => {
    execFile(file, args, { timeout: 2500, maxBuffer: 4 * 1024 * 1024 }, (err, out) => resolve(err ? "" : String(out ?? "")));
  });
}

/** vm_stat → bytes in use the way Activity Monitor counts: app memory + wired + compressed. */
export function parseVmStat(text: string): number | null {
  const size = Number(text.match(/page size of (\d+) bytes/)?.[1]);
  if (!size) return null;
  const pages = (label: string): number => Number(text.match(new RegExp(`${label}:\\s+(\\d+)`))?.[1] ?? NaN);
  const anon = pages("Anonymous pages"), purge = pages("Pages purgeable");
  const wired = pages("Pages wired down"), comp = pages("Pages occupied by compressor");
  if ([anon, purge, wired, comp].some((n) => !Number.isFinite(n))) return null;
  return (Math.max(anon - purge, 0) + wired + comp) * size;
}

/** `sysctl vm.swapusage` → used bytes. */
export function parseSwap(text: string): number | null {
  const m = text.match(/used = ([\d.]+)([MGK])/);
  if (!m) return null;
  return Number(m[1]) * ({ K: 1024, M: 1024 ** 2, G: 1024 ** 3 } as Record<string, number>)[m[2]];
}

/** App/binary name for grouping: outermost .app bundle, otherwise the executable's name. */
export function groupName(comm: string): string {
  const app = comm.match(/([^/]+)\.app(?:\/|$)/);
  return (app ? app[1] : basename(comm)).trim() || "unknown";
}

export function parsePs(text: string): ProcGroup[] {
  const by = new Map<string, ProcGroup>();
  for (const line of text.split("\n")) {
    const m = line.match(/^\s*(\d+)\s+([\d.]+)\s+(.+?)\s*$/);
    if (!m) continue;
    const name = groupName(m[3]);
    const g = by.get(name) ?? { name, count: 0, rssKb: 0, cpu: 0 };
    g.count++; g.rssKb += Number(m[1]); g.cpu += Number(m[2]);
    by.set(name, g);
  }
  return [...by.values()].sort((a, b) => b.rssKb - a.rssKb);
}

let cache: { t: number; v: SystemStats } | null = null;

export async function collectSystemStats(): Promise<SystemStats> {
  if (cache && Date.now() - cache.t < 5000) return cache.v;
  const mac = platform() === "darwin";
  const [vm, memsize, pressureLevel, swap, ps] = await Promise.all([
    mac ? run("/usr/bin/vm_stat", []) : "",
    mac ? run("/usr/sbin/sysctl", ["-n", "hw.memsize"]) : "",
    mac ? run("/usr/sbin/sysctl", ["-n", "kern.memorystatus_vm_pressure_level"]) : "",
    mac ? run("/usr/sbin/sysctl", ["-n", "vm.swapusage"]) : "",
    run("/bin/ps", ["-axo", "rss=,pcpu=,comm="]),
  ]);
  let memTotal = Number(memsize.trim()) || totalmem();
  let memUsed = (mac ? parseVmStat(vm) : null) ?? NaN;
  if (!Number.isFinite(memUsed)) {
    let avail = freemem();
    try { avail = Number(readFileSync("/proc/meminfo", "utf8").match(/MemAvailable:\s+(\d+)/)?.[1]) * 1024 || avail; } catch { /* not linux */ }
    memUsed = Math.max(memTotal - avail, 0);
  }
  const lvl = Number(pressureLevel.trim());
  const pressure = lvl === 1 ? "normal" : lvl === 2 ? "warn" : lvl === 4 ? "critical" : "unknown";
  const l = loadavg();
  const v: SystemStats = {
    memTotal, memUsed, pressure, swapUsed: mac ? parseSwap(swap) : null,
    load: [l[0], l[1], l[2]], cores: cpus().length || 1, hostUptimeSec: hostUptime(),
    groups: parsePs(ps).slice(0, 6), at: Date.now(),
  };
  cache = { t: v.at, v };
  return v;
}

export function fmtDuration(sec: number): string {
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m} min`;
  if (m < 2880) return `${Math.floor(m / 60)} h ${m % 60} min`;
  return `${Math.floor(m / 1440)} days`;
}

const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const gb = (b: number) => (b >= 1024 ** 3 ? `${(b / 1024 ** 3).toFixed(1)} GB` : `${Math.round(b / 1024 ** 2)} MB`);

/** The live part of the system tab (also served alone at /ops-system for the page to poll). */
export function systemHtml(s: SystemStats): string {
  const pct = Math.min(100, Math.round((s.memUsed / s.memTotal) * 100));
  // Pressure is the honest signal on macOS (used% runs high by design); fall back to % when unknown.
  const memCls = s.pressure === "critical" || (s.pressure === "unknown" && pct >= 92) ? "bad"
    : s.pressure === "warn" || (s.pressure === "unknown" && pct >= 80) ? "warn" : "";
  const memLine = [`${gb(s.memUsed)} of ${gb(s.memTotal)} used`,
    s.pressure !== "unknown" ? `pressure ${s.pressure}` : "",
    s.swapUsed != null && s.swapUsed > 50 * 1024 ** 2 ? `swap ${gb(s.swapUsed)}` : ""].filter(Boolean).join(" · ");
  const load = Math.min(100, Math.round((s.load[0] / s.cores) * 100));
  const cpuCls = load >= 90 ? "bad" : load >= 70 ? "warn" : "";
  const t = new Date(s.at).toISOString().slice(11, 16);
  return `<div class="sub">server time ${t} UTC · live</div>` +
    `<h2>memory · ${pct}%</h2><div class="bar ${memCls}"><i style="width:${pct}%"></i></div><div class="m">${esc(memLine)}</div>` +
    `<h2>cpu · ${load}% of ${s.cores} cores</h2><div class="bar ${cpuCls}"><i style="width:${load}%"></i></div>` +
    `<div class="m">load ${s.load.map((x) => x.toFixed(1)).join(" · ")} (1 · 5 · 15 min)</div>` +
    (s.groups.length ? `<h2>top apps by memory</h2>` + s.groups.map((g) =>
      `<div class="proc"><div class="t">${esc(g.name)}${g.count > 1 ? `<span class="m"> ×${g.count}</span>` : ""}</div>` +
      `<div class="m">${gb(g.rssKb * 1024)} · ${g.cpu.toFixed(1)}% cpu</div></div>`).join("") : "");
}
