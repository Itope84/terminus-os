/** System monitor: history sampler, JSON API and the /system page.
 *
 *  - Samples every SYSMON_INTERVAL_MS (10s): total + per-core CPU, memory breakdown, load, network
 *    throughput. Keeps 3h in memory and persists it (atomic write, once a minute) so a server restart
 *    doesn't blank the charts.
 *  - GET  /api/sysmon?range=15m|1h|3h   current numbers, downsampled series, top processes by app
 *  - POST /api/sysmon/kill              {pid, name, force?} guarded quit of one of the user's own processes
 *  - GET  /system                       the monitor page (inline SVG charts, no dependencies)
 *
 *  macOS is the primary target (vm_stat, netstat, sysctl, pmset, ps); Linux works through /proc.
 *  Every parser is a pure exported function so it can be tested on captured output. A failed probe yields
 *  null for that metric and the page hides the card; it never throws into the request path. */
import type { IncomingMessage, ServerResponse } from "node:http";
import { cpus, loadavg, totalmem, freemem, uptime, hostname, platform, arch, release, userInfo, homedir } from "node:os";
import { statfsSync, readFileSync, writeFileSync, renameSync, mkdirSync } from "node:fs";
import { execFile } from "node:child_process";
import { dirname, join, basename } from "node:path";
import type { Json } from "./util.js";

const INTERVAL_MS = Math.max(200, Number(process.env.SYSMON_INTERVAL_MS) || 10_000);
const HISTORY_MS = 3 * 3600_000;
const MAX_SAMPLES = Math.ceil(HISTORY_MS / INTERVAL_MS);
const STORE = join(homedir(), ".config", "terminus", "sysmon.json");
const STARTED = Date.now();

// ---------- pure parsers (tested on fixtures) ----------

export type MemBreakdown = { total: number; app: number; wired: number; compressed: number; cached: number; free: number; used: number };

/** macOS `vm_stat`. "Memory used" follows Activity Monitor: app (anonymous minus purgeable) + wired + compressor;
 *  file-backed and purgeable pages count as cached, which is reclaimable, not pressure. */
export function parseVmStat(text: string, total: number): MemBreakdown | null {
  const ps = Number(/page size of (\d+) bytes/.exec(text)?.[1]);
  if (!ps) return null;
  const pages = (label: string): number => {
    const m = new RegExp("^" + label + ":\\s+(\\d+)", "mi").exec(text);
    return m ? Number(m[1]) : 0;
  };
  const anon = pages("Anonymous pages");
  const purg = pages("Pages purgeable");
  const app = Math.max(0, anon - purg) * ps;
  const wired = pages("Pages wired down") * ps;
  const compressed = pages("Pages occupied by compressor") * ps;
  if (!anon && !wired) return null;
  const used = Math.min(total, app + wired + compressed);
  // Counters overlap on a busy machine; clip so the segments can never add up to more than the RAM.
  const cached = Math.min((pages("File-backed pages") + purg) * ps, Math.max(0, total - used));
  const free = Math.max(0, total - used - cached);
  return { total, app, wired, compressed, cached, free, used };
}

/** Linux /proc/meminfo. */
export function parseMeminfo(text: string): { mem: MemBreakdown; swapTotal: number; swapUsed: number } | null {
  const kb = (k: string): number => {
    const m = new RegExp("^" + k + ":\\s+(\\d+) kB", "mi").exec(text);
    return m ? Number(m[1]) * 1024 : 0;
  };
  const total = kb("MemTotal");
  if (!total) return null;
  const avail = kb("MemAvailable") || kb("MemFree");
  const cached = kb("Cached") + kb("Buffers") + kb("SReclaimable");
  const used = Math.max(0, total - avail);
  const free = kb("MemFree");
  return {
    mem: { total, app: used, wired: 0, compressed: 0, cached: Math.min(cached, Math.max(0, total - used - free)), free, used },
    swapTotal: kb("SwapTotal"), swapUsed: Math.max(0, kb("SwapTotal") - kb("SwapFree")),
  };
}

/** macOS `sysctl -n vm.swapusage`: "total = 2048.00M  used = 1023.25M  free = ..." */
export function parseSwapUsage(text: string): { total: number; used: number } | null {
  const num = (k: string): number | null => {
    const m = new RegExp(k + "\\s*=\\s*([\\d.]+)([KMGT])", "i").exec(text);
    if (!m) return null;
    const mult: Record<string, number> = { K: 1024, M: 1024 ** 2, G: 1024 ** 3, T: 1024 ** 4 };
    return Number(m[1]) * mult[m[2].toUpperCase()];
  };
  const total = num("total"), used = num("used");
  return total == null || used == null ? null : { total, used };
}

const NET_SKIP = /^(lo\d*|utun\d*|awdl\d*|llw\d*|ap\d*|anpi\d*|gif\d*|stf\d*|XHC\d*|docker\d*|veth.*|br-.*|virbr.*|tailscale\d*|bridge\d*)$/;

/** macOS `netstat -ib`: cumulative bytes over physical interfaces. Columns are located from the right because
 *  rows without a MAC address have one fewer field. */
export function parseNetstat(text: string): { rx: number; tx: number } | null {
  const lines = text.split("\n");
  const head = lines[0]?.trim().split(/\s+/) ?? [];
  const ib = head.indexOf("Ibytes"), ob = head.indexOf("Obytes");
  if (ib < 0 || ob < 0) return null;
  let rx = 0, tx = 0, any = false;
  for (const line of lines.slice(1)) {
    const f = line.trim().split(/\s+/);
    if (f.length < 7 || !/^<Link#\d+>$/.test(f[2] ?? "")) continue;
    const name = f[0].replace(/\*$/, "");
    if (NET_SKIP.test(name)) continue;
    const r = Number(f[f.length - (head.length - ib)]), t = Number(f[f.length - (head.length - ob)]);
    if (Number.isFinite(r) && Number.isFinite(t)) { rx += r; tx += t; any = true; }
  }
  return any ? { rx, tx } : null;
}

/** Linux /proc/net/dev. */
export function parseProcNetDev(text: string): { rx: number; tx: number } | null {
  let rx = 0, tx = 0, any = false;
  for (const line of text.split("\n").slice(2)) {
    const m = /^\s*([^:\s]+):\s*(.*)$/.exec(line);
    if (!m || NET_SKIP.test(m[1])) continue;
    const f = m[2].trim().split(/\s+/).map(Number);
    if (f.length < 9 || !Number.isFinite(f[0]) || !Number.isFinite(f[8])) continue;
    rx += f[0]; tx += f[8]; any = true;
  }
  return any ? { rx, tx } : null;
}

/** macOS `pmset -g batt`. null on a desktop with no battery. */
export function parsePmsetBatt(text: string): { pct: number; state: string; remaining: string } | null {
  const m = /(\d+)%;\s*([^;]+?);\s*(?:([\d:]+) remaining|\(no estimate\)|[^;]*)/i.exec(text);
  if (!m || !/InternalBattery/i.test(text)) return null;
  return { pct: Number(m[1]), state: m[2].trim(), remaining: m[3] ?? "" };
}

export type Proc = { pid: number; ppid: number; cpu: number; mem: number; rss: number; user: string; cmd: string; name: string };

/** App/process label: the outermost "X.app" (so every Chrome helper groups under "Google Chrome"), else the basename. */
export function appNameOf(cmd: string): string {
  const app = /\/([^/]+)\.app(?:\/|$)/.exec(cmd);
  if (app) return app[1];
  const first = cmd.startsWith("/") ? cmd.split(/ (?=[-/])/)[0] : cmd.split(/\s+/)[0];
  return basename(first.trim()) || cmd;
}

/** `ps -A -o pid=,ppid=,pcpu=,pmem=,rss=,uid=,<comm|args>=` (user holds the numeric uid) */
export function parsePs(text: string): Proc[] {
  const out: Proc[] = [];
  for (const line of text.split("\n")) {
    const m = /^\s*(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)\s+(\S+)\s+(.+?)\s*$/.exec(line);
    if (!m) continue;
    const cmd = m[7];
    out.push({ pid: +m[1], ppid: +m[2], cpu: +m[3], mem: +m[4], rss: +m[5] * 1024, user: m[6], cmd, name: appNameOf(cmd) });
  }
  return out;
}

export type ProcGroup = { name: string; cpu: number; rss: number; count: number; procs: Proc[] };

export function groupProcs(procs: Proc[], limit = 14): ProcGroup[] {
  const by = new Map<string, ProcGroup>();
  for (const p of procs) {
    const g = by.get(p.name) ?? { name: p.name, cpu: 0, rss: 0, count: 0, procs: [] };
    g.cpu += p.cpu; g.rss += p.rss; g.count++; g.procs.push(p);
    by.set(p.name, g);
  }
  const groups = [...by.values()];
  for (const g of groups) g.procs.sort((a, b) => b.cpu - a.cpu || b.rss - a.rss);
  // Keep the heaviest by either measure so memory hogs and CPU hogs both surface.
  const byCpu = [...groups].sort((a, b) => b.cpu - a.cpu).slice(0, limit);
  const byMem = [...groups].sort((a, b) => b.rss - a.rss).slice(0, limit);
  return [...new Set([...byCpu, ...byMem])];
}

/** Names that must never be killed from a phone: they'd end the session, the network path or the machine's UI. */
const PROTECTED = /^(launchd|kernel_task|WindowServer|loginwindow|systemd|init|sshd|tailscaled|Tailscale|tailscale|configd|mDNSResponder|coreaudiod|powerd)$/;

export function killVerdict(p: Proc | undefined, expectName: string, me: { pid: number; user: string }, ancestors: Set<number>): string | null {
  if (!p) return "process not found (already gone?)";
  if (p.pid <= 1) return "refusing to touch PID 1";
  if (p.pid === me.pid) return "that's the Terminus server itself";
  if (ancestors.has(p.pid)) return "that process launched the Terminus server";
  if (p.user !== me.user) return "not your process";
  if (PROTECTED.test(p.name)) return p.name + " is protected";
  if (expectName && p.name !== expectName) return "that PID now belongs to a different process (" + p.name + "); refresh and try again";
  return null;
}

// ---------- sampling ----------

type Sample = { t: number; cpu: number; mem: number; load: number; rx: number; ty: number };
const ring: Sample[] = [];

type Snapshot = {
  cores: number[]; load: number[]; mem: MemBreakdown | null; swap: { total: number; used: number } | null;
  disk: { total: number; used: number } | null; net: { rx: number; tx: number } | null; battery: ReturnType<typeof parsePmsetBatt>;
  pressure: "normal" | "warn" | "critical" | "unknown";
};
const snap: Snapshot = { cores: [], load: [0, 0, 0], mem: null, swap: null, disk: null, net: null, battery: null, pressure: "unknown" };

function run(cmd: string, args: string[], timeout = 4000): Promise<string> {
  return new Promise((resolve) => {
    try {
      execFile(cmd, args, { timeout, maxBuffer: 8 * 1024 * 1024, windowsHide: true }, (err, stdout) => resolve(err ? "" : String(stdout)));
    } catch { resolve(""); }
  });
}
function readText(path: string): string { try { return readFileSync(path, "utf8"); } catch { return ""; } }

let prevCpu: { idle: number; total: number }[] | null = null;
let prevNet: { rx: number; tx: number; at: number } | null = null;
let lastBatt = 0;
let ticking = false;

function cpuTick(): { total: number; cores: number[] } {
  const now = cpus().map((c) => {
    const t = c.times;
    return { idle: t.idle, total: t.user + t.nice + t.sys + t.idle + t.irq };
  });
  let res = { total: 0, cores: now.map(() => 0) };
  if (prevCpu && prevCpu.length === now.length) {
    let idle = 0, tot = 0;
    const cores = now.map((c, i) => {
      const di = c.idle - prevCpu![i].idle, dt = c.total - prevCpu![i].total;
      idle += di; tot += dt;
      return dt > 0 ? Math.max(0, Math.min(100, 100 * (1 - di / dt))) : 0;
    });
    res = { total: tot > 0 ? Math.max(0, Math.min(100, 100 * (1 - idle / tot))) : 0, cores };
  }
  prevCpu = now;
  return res;
}

async function tick(): Promise<void> {
  if (ticking) return;
  ticking = true;
  try {
    const t = Date.now();
    const { total: cpu, cores } = cpuTick();
    const mac = platform() === "darwin";
    const wantBatt = mac && t - lastBatt > 60_000;
    if (wantBatt) lastBatt = t;
    const [vm, swap, net, batt, pressureLevel] = await Promise.all([
      mac ? run("vm_stat", []) : Promise.resolve(readText("/proc/meminfo")),
      mac ? run("/usr/sbin/sysctl", ["-n", "vm.swapusage"]) : Promise.resolve(""),
      mac ? run("netstat", ["-ib"]) : Promise.resolve(readText("/proc/net/dev")),
      wantBatt ? run("pmset", ["-g", "batt"]) : Promise.resolve(""),
      mac ? run("/usr/sbin/sysctl", ["-n", "kern.memorystatus_vm_pressure_level"]) : Promise.resolve(""),
    ]);
    const total = totalmem();
    let mem: MemBreakdown | null = null;
    let sw: Snapshot["swap"] = null;
    if (mac) { mem = parseVmStat(vm, total); sw = parseSwapUsage(swap); }
    else { const p = parseMeminfo(vm); if (p) { mem = p.mem; sw = p.swapTotal ? { total: p.swapTotal, used: p.swapUsed } : null; } }
    if (!mem) { const free = freemem(); mem = { total, app: total - free, wired: 0, compressed: 0, cached: 0, free, used: total - free }; }
    const cur = mac ? parseNetstat(net) : parseProcNetDev(net);
    let rx = 0, tx = 0;
    if (cur && prevNet) {
      const dt = (t - prevNet.at) / 1000;
      if (dt > 0) { rx = Math.max(0, (cur.rx - prevNet.rx) / dt); tx = Math.max(0, (cur.tx - prevNet.tx) / dt); }
    }
    if (cur) prevNet = { ...cur, at: t };
    let disk: Snapshot["disk"] = null;
    try {
      const s = statfsSync(homedir());
      const totalB = s.blocks * s.bsize;
      disk = { total: totalB, used: Math.max(0, totalB - s.bavail * s.bsize) };
    } catch { /* statfs unsupported */ }
    if (wantBatt) snap.battery = parsePmsetBatt(batt);
    if (mac) {
      const lvl = Number((pressureLevel || "").trim());
      snap.pressure = lvl === 1 ? "normal" : lvl === 2 ? "warn" : lvl === 4 ? "critical" : "unknown";
    }
    snap.cores = cores; snap.load = loadavg(); snap.mem = mem; snap.swap = sw; snap.disk = disk; snap.net = cur;
    ring.push({ t, cpu, mem: mem.total ? (100 * mem.used) / mem.total : 0, load: snap.load[0], rx, ty: tx });
    if (ring.length > MAX_SAMPLES) ring.splice(0, ring.length - MAX_SAMPLES);
  } catch { /* a bad probe must not stop sampling */ }
  finally { ticking = false; }
}

let lastSave = 0;
function persist(): void {
  const now = Date.now();
  if (now - lastSave < 60_000) return;
  lastSave = now;
  try {
    mkdirSync(dirname(STORE), { recursive: true });
    const tmp = STORE + ".tmp";
    writeFileSync(tmp, JSON.stringify({ v: 1, step: INTERVAL_MS, samples: ring }));
    renameSync(tmp, STORE);
  } catch { /* history is a convenience */ }
}
function restore(): void {
  try {
    const j = JSON.parse(readFileSync(STORE, "utf8")) as { v?: number; samples?: Sample[] };
    if (j.v !== 1 || !Array.isArray(j.samples)) return;
    const cutoff = Date.now() - HISTORY_MS;
    for (const s of j.samples) {
      if (s && typeof s.t === "number" && s.t > cutoff && [s.cpu, s.mem, s.load, s.rx, s.ty].every((x) => typeof x === "number" && Number.isFinite(x))) ring.push(s);
    }
  } catch { /* none yet */ }
}

let started = false;
export function startSysmon(): void {
  if (started) return;
  started = true;
  restore();
  void tick();
  const h = setInterval(() => { void tick().then(persist); }, INTERVAL_MS);
  h.unref?.();
}

// ---------- processes ----------

let procCache: { at: number; procs: Proc[] } | null = null;
async function listProcs(): Promise<Proc[]> {
  if (procCache && Date.now() - procCache.at < 3000) return procCache.procs;
  const col = platform() === "darwin" ? "comm=" : "args=";
  const text = await run("ps", ["-A", "-o", "pid=,ppid=,pcpu=,pmem=,rss=,uid=," + col], 6000);
  // Drop our own `ps` probe and kernel threads (no resident memory): noise, never actionable.
  const procs = parsePs(text).filter((p) => p.rss > 0 && !(p.ppid === process.pid && p.name === "ps"));
  if (procs.length) procCache = { at: Date.now(), procs };
  return procs;
}

function ancestorsOf(pid: number, procs: Proc[]): Set<number> {
  const by = new Map(procs.map((p) => [p.pid, p]));
  const out = new Set<number>();
  for (let cur = by.get(pid)?.ppid; cur && cur > 1 && !out.has(cur); cur = by.get(cur)?.ppid) out.add(cur);
  return out;
}

// ---------- API ----------

const RANGES: Record<string, number> = { "15m": 15 * 60_000, "1h": 3600_000, "3h": HISTORY_MS };

function seriesFor(range: string): Record<string, number[]> & { t: number[] } {
  const span = RANGES[range] ?? RANGES["15m"];
  const from = Date.now() - span;
  const pts = ring.filter((s) => s.t >= from);
  const target = 150;
  const size = Math.max(1, Math.ceil(pts.length / target));
  const out = { t: [] as number[], cpu: [] as number[], mem: [] as number[], load: [] as number[], rx: [] as number[], tx: [] as number[] };
  for (let i = 0; i < pts.length; i += size) {
    const b = pts.slice(i, i + size);
    const avg = (f: (s: Sample) => number) => Math.round((b.reduce((a, s) => a + f(s), 0) / b.length) * 100) / 100;
    out.t.push(b[b.length - 1].t);
    out.cpu.push(avg((s) => s.cpu)); out.mem.push(avg((s) => s.mem)); out.load.push(avg((s) => s.load));
    out.rx.push(Math.round(avg((s) => s.rx))); out.tx.push(Math.round(avg((s) => s.ty)));
  }
  return out;
}

async function readBody(req: IncomingMessage, max = 4096): Promise<Record<string, unknown> | null> {
  let size = 0;
  const chunks: Buffer[] = [];
  try {
    for await (const c of req) {
      const b = Buffer.isBuffer(c) ? c : Buffer.from(c as Uint8Array);
      size += b.length;
      if (size > max) return null;
      chunks.push(b);
    }
    const v: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch { return null; }
}

/** Returns true when the request was handled. Auth is the caller's job (handleMedia runs after requireAuth). */
export async function handleSysmon(req: IncomingMessage, res: ServerResponse, reqPath: string, json: Json): Promise<boolean> {
  if ((reqPath === "/system" || reqPath === "/system/") && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(systemHtml());
    return true;
  }
  if (reqPath === "/api/sysmon" && req.method === "GET") {
    startSysmon();
    const url = new URL(req.url || "/api/sysmon", "http://x");
    const range = url.searchParams.get("range") || "15m";
    const procs = groupProcs(await listProcs());
    const me = String(userInfo().uid);
    const c = cpus();
    json(res, 200, {
      ok: true, now: Date.now(), step: INTERVAL_MS, user: userInfo().username,
      host: {
        platform: platform(), arch: arch(), release: release(), name: hostname(), cores: c.length, model: c[0]?.model ?? "",
        uptime: uptime(), serverUptime: Math.round((Date.now() - STARTED) / 1000), serverPid: process.pid, serverRss: process.memoryUsage().rss,
      },
      cur: { cpu: ring.length ? ring[ring.length - 1].cpu : 0, cores: snap.cores, load: snap.load, mem: snap.mem, swap: snap.swap, disk: snap.disk, net: ring.length ? { rx: ring[ring.length - 1].rx, tx: ring[ring.length - 1].ty } : null, battery: snap.battery, pressure: snap.pressure },
      series: seriesFor(range),
      procs: procs.map((g) => ({
        name: g.name, cpu: Math.round(g.cpu * 10) / 10, rss: g.rss, count: g.count,
        procs: g.procs.slice(0, 8).map((p) => ({ pid: p.pid, cpu: p.cpu, rss: p.rss, user: p.user, cmd: p.cmd.slice(0, 160), mine: p.user === me && !PROTECTED.test(p.name) && p.pid > 1 && p.pid !== process.pid })),
      })),
    });
    return true;
  }
  if (reqPath === "/api/sysmon/kill" && req.method === "POST") {
    // A custom header makes a cross-site form post (cookie auth) unable to reach this.
    if (req.headers["x-terminus"] !== "1") return json(res, 403, { ok: false, error: "missing X-Terminus header" }), true;
    const body = await readBody(req);
    const pid = Number(body?.pid);
    if (!body || !Number.isInteger(pid)) return json(res, 400, { ok: false, error: "pid required" }), true;
    procCache = null;
    const procs = await listProcs();
    const target = procs.find((p) => p.pid === pid);
    const why = killVerdict(target, String(body.name ?? ""), { pid: process.pid, user: String(userInfo().uid) }, ancestorsOf(process.pid, procs));
    if (why) return json(res, 409, { ok: false, error: why }), true;
    try {
      process.kill(pid, body.force === true ? "SIGKILL" : "SIGTERM");
      procCache = null;
      json(res, 200, { ok: true, pid, signal: body.force === true ? "SIGKILL" : "SIGTERM" });
    } catch (e) {
      json(res, 500, { ok: false, error: e instanceof Error ? e.message : "kill failed" });
    }
    return true;
  }
  return false;
}

// ---------- page ----------

/** Client script: plain strings only (no backticks or ${} so it can live in a template literal). */
const CLIENT_JS = String.raw`
(function(){
var Q=new URLSearchParams(location.search), EMBED=Q.get('embed')==='1';
if(EMBED)document.body.classList.add('embed');
try{var qt=Q.get('token');if(qt)localStorage.setItem('terminus-token',qt.trim())}catch(e){}
function tok(){try{return Q.get('token')||localStorage.getItem('terminus-token')||''}catch(e){return Q.get('token')||''}}
function hdrs(extra){var h=extra||{};var t=tok();if(t)h['Authorization']='Bearer '+t;return h}
var range='15m', tab='cpu', sortBy='cpu', data=null, openGroup=null, failures=0, timer=null;
var $=function(id){return document.getElementById(id)};
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function bytes(n,d){if(n==null||!isFinite(n))return '-';var u=['B','KB','MB','GB','TB'],i=0;while(n>=1024&&i<4){n/=1024;i++}return (i>=3||n<10&&i>0?n.toFixed(d==null?1:d):Math.round(n))+' '+u[i]}
function rate(n){return bytes(n)+'/s'}
function dur(s){s=Math.floor(s);var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return d?d+'d '+h+'h':h?h+'h '+m+'m':m+' min'}
function pct(n){return (n<10?n.toFixed(1):Math.round(n))+'%'}
function lvl(p){return p>=90?'bad':p>=75?'warn':'ok'}
function clock(t){var d=new Date(t);return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)}

/* ---- charts ---- */
function spark(vals,color,max,h){
  h=h||36;var w=120;if(!vals||vals.length<2)return '<svg class="spark" viewBox="0 0 '+w+' '+h+'" preserveAspectRatio="none"></svg>';
  var mx=max||Math.max.apply(null,vals.concat([1e-9]));var pts=vals.map(function(v,i){return [i/(vals.length-1)*w,h-2-(Math.min(v,mx)/mx)*(h-6)]});
  var line=pts.map(function(p,i){return (i?'L':'M')+p[0].toFixed(1)+' '+p[1].toFixed(1)}).join('');
  return '<svg class="spark" viewBox="0 0 '+w+' '+h+'" preserveAspectRatio="none"><path d="'+line+'L'+w+' '+h+'L0 '+h+'Z" fill="'+color+'" opacity=".16"/><path d="'+line+'" fill="none" stroke="'+color+'" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/></svg>'}
function niceMax(v,floor){var m=Math.max(v,floor||1);var p=Math.pow(10,Math.floor(Math.log10(m)));var f=m/p;return (f<=1?1:f<=2?2:f<=5?5:10)*p}
var chart={};
function bigChart(){
  var s=data.series,el=$('chart');if(!s.t.length){el.innerHTML='<div class="empty">collecting samples…</div>';return}
  var W=340,H=170,L=(tab==='net'?50:34),R=8,T=8,B=20,iw=W-L-R,ih=H-T-B;
  var series,fmt,axfmt,color,floor,fixedMax=null;
  if(tab==='cpu'){series=[{v:s.cpu,c:'var(--c-cpu)'}];fixedMax=100;fmt=function(v){return Math.round(v)+'%'}}
  else if(tab==='mem'){series=[{v:s.mem,c:'var(--c-mem)'}];fixedMax=100;fmt=function(v){return Math.round(v)+'%'}}
  else if(tab==='load'){series=[{v:s.load,c:'var(--c-load)'}];floor=Math.max(1,data.host.cores/4);fmt=function(v){return v.toFixed(1)}}
  else{series=[{v:s.rx,c:'var(--c-rx)'},{v:s.tx,c:'var(--c-tx)'}];floor=1024;fmt=rate;axfmt=function(v){return v<1?'0':bytes(v,0)}}
  axfmt=axfmt||fmt;
  var mx=fixedMax||niceMax(Math.max.apply(null,series.reduce(function(a,x){return a.concat(x.v)},[])),floor);
  var n=s.t.length,x=function(i){return L+(n>1?i/(n-1):0.5)*iw},y=function(v){return T+ih-(Math.min(v,mx)/mx)*ih};
  var g='';for(var k=0;k<=4;k++){var gv=mx*k/4,gy=y(gv);g+='<line x1="'+L+'" x2="'+(W-R)+'" y1="'+gy+'" y2="'+gy+'" class="gl"/><text x="'+(L-5)+'" y="'+(gy+3.5)+'" class="ax" text-anchor="end">'+esc(axfmt(gv))+'</text>'}
  var tl=[0,Math.floor((n-1)/2),n-1];tl.forEach(function(i,j){if(i<0||i>=n)return;g+='<text x="'+x(i)+'" y="'+(H-5)+'" class="ax" text-anchor="'+(j===0?'start':j===2?'end':'middle')+'">'+clock(s.t[i])+'</text>'});
  series.forEach(function(se,si){var d=se.v.map(function(v,i){return (i?'L':'M')+x(i).toFixed(1)+' '+y(v).toFixed(1)}).join('');
    if(si===0||series.length===1)g+='<path d="'+d+'L'+x(n-1)+' '+(T+ih)+'L'+x(0)+' '+(T+ih)+'Z" fill="'+se.c+'" opacity="'+(series.length>1?'.10':'.16')+'"/>';
    g+='<path d="'+d+'" fill="none" stroke="'+se.c+'" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>'});
  g+='<g id="cur" style="display:none"><line id="cl" y1="'+T+'" y2="'+(T+ih)+'" class="cursor"/>'+series.map(function(se,i){return '<circle id="cd'+i+'" r="4" fill="'+se.c+'" stroke="var(--surface)" stroke-width="2"/>'}).join('')+'</g>';
  el.innerHTML='<svg viewBox="0 0 '+W+' '+H+'" id="bigsvg" role="img" aria-label="'+tab+' history">'+g+'</svg><div id="tip" class="tip" hidden></div>';
  chart={s:s,series:series,fmt:fmt,x:x,y:y,W:W,n:n};
  var svg=$('bigsvg');
  function at(ev){var r=svg.getBoundingClientRect();var px=((ev.touches?ev.touches[0].clientX:ev.clientX)-r.left)/r.width*W;var i=Math.round((px-L)/iw*(n-1));i=Math.max(0,Math.min(n-1,i));show(i,r)}
  function show(i,r){$('cur').style.display='';$('cl').setAttribute('x1',x(i));$('cl').setAttribute('x2',x(i));
    series.forEach(function(se,k){var c=$('cd'+k);c.setAttribute('cx',x(i));c.setAttribute('cy',y(se.v[i]))});
    var tip=$('tip');tip.hidden=false;var lab=series.length>1?'<b style="color:var(--c-rx)">↓ '+fmt(series[0].v[i])+'</b> <b style="color:var(--c-tx)">↑ '+fmt(series[1].v[i])+'</b>':'<b>'+fmt(series[0].v[i])+'</b>';
    tip.innerHTML=lab+'<span>'+clock(s.t[i])+'</span>';var left=x(i)/W*r.width;tip.style.left=Math.max(40,Math.min(r.width-40,left))+'px'}
  function hide(){$('cur').style.display='none';$('tip').hidden=true}
  svg.addEventListener('pointerdown',at);svg.addEventListener('pointermove',function(e){if(e.pointerType==='mouse'||e.buttons)at(e)});
  svg.addEventListener('pointerleave',hide);svg.addEventListener('pointerup',function(e){if(e.pointerType!=='mouse')setTimeout(hide,1500)});
}

/* ---- render ---- */
function card(id,label,big,sub,sp,cls){return '<div class="card stat '+(cls||'')+'" id="'+id+'"><div class="lbl">'+label+'</div><div class="big">'+big+'</div><div class="sub">'+sub+'</div>'+sp+'</div>'}
function render(){
  var d=data,c=d.cur,h=d.host,s=d.series;var out='';var alerts=[];
  var mem=c.mem,memPct=mem&&mem.total?100*mem.used/mem.total:0;
  var pressure=c.pressure&&c.pressure!=='unknown'?c.pressure:'';
  var memCls=pressure?(pressure==='critical'?'bad':pressure==='warn'?'warn':'ok'):lvl(memPct);
  var cpuHigh=s.cpu.slice(-6);if(cpuHigh.length>=6&&cpuHigh.every(function(v){return v>=90}))alerts.push('CPU has been above 90% for a minute');
  if(memPct>=90)alerts.push('Memory is at '+Math.round(memPct)+'%'+(c.swap&&c.swap.used>1<<30?' and swapping '+bytes(c.swap.used):''));
  if(c.disk&&100*c.disk.used/c.disk.total>=92)alerts.push('Disk is '+Math.round(100*c.disk.used/c.disk.total)+'% full');
  if(c.battery&&c.battery.pct<=15&&/discharg/i.test(c.battery.state))alerts.push('Battery at '+c.battery.pct+'%');
  $('alerts').innerHTML=alerts.map(function(a){return '<div class="alert">⚠ '+esc(a)+'</div>'}).join('');
  $('hostline').textContent=(h.platform==='darwin'?'macOS':h.platform)+' · '+h.arch+' · '+h.cores+' cores · up '+dur(h.uptime);
  var grid='';
  grid+=card('s-cpu','CPU',pct(c.cpu),'load '+c.load.map(function(x){return x.toFixed(1)}).join(' · '),spark(s.cpu,'var(--c-cpu)',100),lvl(c.cpu));
  if(mem)grid+=card('s-mem','Memory',pct(memPct),bytes(mem.used)+' of '+bytes(mem.total)+(pressure?' · pressure '+pressure:''),spark(s.mem,'var(--c-mem)',100),memCls);
  var nets=c.net||{rx:0,tx:0};
  grid+=card('s-net','Network','<span class="dn">↓ '+rate(nets.rx)+'</span>','<span class="up">↑ '+rate(nets.tx)+'</span>',spark(s.rx,'var(--c-rx)'),'');
  if(c.disk){var dp=100*c.disk.used/c.disk.total;grid+=card('s-disk','Disk',pct(dp),bytes(c.disk.total-c.disk.used)+' free of '+bytes(c.disk.total),'<div class="bar"><i style="width:'+dp.toFixed(1)+'%;background:var(--c-disk)"></i></div>',lvl(dp))}
  $('grid').innerHTML=grid;
  var bt=c.battery?'<span class="chip">🔋 '+c.battery.pct+'% · '+esc(c.battery.state)+(c.battery.remaining?' · '+esc(c.battery.remaining):'')+'</span>':'';
  $('chips').innerHTML=bt+'<span class="chip">server up '+dur(h.serverUptime)+' · '+bytes(h.serverRss)+'</span>';
  bigChart();
  /* cores */
  var cs=c.cores||[];$('cores').innerHTML=cs.length?cs.map(function(v,i){return '<div class="core" title="core '+(i+1)+': '+Math.round(v)+'%"><i class="'+lvl(v)+'" style="height:'+Math.max(3,v).toFixed(0)+'%"></i></div>'}).join(''):'<div class="empty">no per-core data</div>';
  /* memory breakdown */
  if(mem){var parts=[['App',mem.app,'var(--m-app)'],['Wired',mem.wired,'var(--m-wired)'],['Compressed',mem.compressed,'var(--m-comp)'],['Cached',mem.cached,'var(--m-cache)'],['Free',mem.free,'var(--m-free)']].filter(function(p){return p[1]>0});
    $('membar').innerHTML=parts.map(function(p){return '<i style="width:'+(100*p[1]/mem.total).toFixed(2)+'%;background:'+p[2]+'"></i>'}).join('');
    $('memleg').innerHTML=parts.map(function(p){return '<span><b style="background:'+p[2]+'"></b>'+p[0]+' '+bytes(p[1])+'</span>'}).join('')+(c.swap&&c.swap.total?'<span class="sw">swap '+bytes(c.swap.used)+' / '+bytes(c.swap.total)+'</span>':'');
    $('memcard').hidden=false}else $('memcard').hidden=true;
  /* load */
  var cores=h.cores||1;$('load').innerHTML=['1 min','5 min','15 min'].map(function(l,i){var v=c.load[i]||0,p=Math.min(100,100*v/cores);return '<div class="lrow"><span>'+l+'</span><div class="bar"><i class="'+lvl(p)+'" style="width:'+p.toFixed(0)+'%"></i></div><b>'+v.toFixed(2)+'</b></div>'}).join('')+'<div class="hint">'+cores+' cores · a load above '+cores+' means work is queueing</div>';
  renderProcs();
  var s2=document.body.getAttribute('data-stale');$('live').className='live'+(s2?' off':'');$('live').title=s2?'connection lost':'live';
  if(EMBED&&parent!==window){try{parent.postMessage({type:'sysmon-h',h:document.documentElement.scrollHeight},'*')}catch(e){}}
}
function renderProcs(){
  var gs=data.procs.slice().sort(function(a,b){return sortBy==='cpu'?b.cpu-a.cpu:b.rss-a.rss}).slice(0,12);
  var mxc=Math.max.apply(null,gs.map(function(g){return g.cpu}).concat([1])),mxm=Math.max.apply(null,gs.map(function(g){return g.rss}).concat([1]));
  $('procs').innerHTML=gs.map(function(g){var open=openGroup===g.name;var val=sortBy==='cpu'?g.cpu/mxc:g.rss/mxm;
    var rows=open?'<div class="plist">'+g.procs.map(function(p){return '<div class="prow"><div class="pm"><div class="pc" title="'+esc(p.cmd)+'">'+esc(p.cmd.split('/').pop().slice(0,48)||p.cmd)+'</div><div class="ps">PID '+p.pid+' · '+p.cpu.toFixed(1)+'% cpu · '+bytes(p.rss)+'</div></div>'+(p.mine?'<button class="quit" data-pid="'+p.pid+'" data-name="'+esc(g.name)+'">Quit</button>':'<span class="lock" title="protected or not yours">🔒</span>')+'</div>'}).join('')+(g.count>g.procs.length?'<div class="hint">+'+(g.count-g.procs.length)+' more</div>':'')+'</div>':'';
    return '<div class="proc'+(open?' open':'')+'" data-g="'+esc(g.name)+'"><div class="ph"><div class="pn">'+esc(g.name)+(g.count>1?' <em>×'+g.count+'</em>':'')+'</div><div class="pv">'+(sortBy==='cpu'?g.cpu.toFixed(1)+'%':bytes(g.rss))+'<small>'+(sortBy==='cpu'?bytes(g.rss):g.cpu.toFixed(1)+'%')+'</small></div></div><div class="bar thin"><i style="width:'+Math.max(2,100*val).toFixed(0)+'%;background:'+(sortBy==='cpu'?'var(--c-cpu)':'var(--c-mem)')+'"></i></div>'+rows+'</div>'}).join('')||'<div class="empty">no process data</div>';
}

/* ---- events ---- */
document.addEventListener('click',function(e){
  var t=e.target;if(!t.closest)return;
  var r=t.closest('[data-range]');if(r){range=r.getAttribute('data-range');document.querySelectorAll('[data-range]').forEach(function(x){x.classList.toggle('on',x===r)});load();return}
  var tb=t.closest('[data-tab]');if(tb){tab=tb.getAttribute('data-tab');document.querySelectorAll('[data-tab]').forEach(function(x){x.classList.toggle('on',x===tb)});if(data)bigChart();return}
  var sb=t.closest('[data-sort]');if(sb){sortBy=sb.getAttribute('data-sort');document.querySelectorAll('[data-sort]').forEach(function(x){x.classList.toggle('on',x===sb)});if(data)renderProcs();return}
  var q=t.closest('.quit');if(q){confirmQuit(+q.getAttribute('data-pid'),q.getAttribute('data-name'));return}
  var p=t.closest('.ph');if(p){var g=p.parentNode.getAttribute('data-g');openGroup=openGroup===g?null:g;renderProcs()}
});
function confirmQuit(pid,name){
  var dlg=$('dlg');$('dlgT').textContent='Quit '+name+'?';$('dlgB').textContent='Sends a normal quit request to PID '+pid+'. Unsaved work in it may be lost.';
  $('dlgForce').checked=false;$('dlgErr').textContent='';dlg.showModal();
  $('dlgGo').onclick=function(){$('dlgGo').disabled=true;
    fetch('/api/sysmon/kill',{method:'POST',headers:hdrs({'Content-Type':'application/json','X-Terminus':'1'}),body:JSON.stringify({pid:pid,name:name,force:$('dlgForce').checked})})
    .then(function(r){return r.json()}).then(function(j){$('dlgGo').disabled=false;if(j.ok){dlg.close();setTimeout(load,700)}else $('dlgErr').textContent=j.error||'failed'})
    .catch(function(){$('dlgGo').disabled=false;$('dlgErr').textContent='network error'})};
}
$('dlgNo').onclick=function(){$('dlg').close()};

/* ---- data loop ---- */
function load(){
  return fetch('/api/sysmon?range='+range,{headers:hdrs(),cache:'no-store'}).then(function(r){if(r.status===401)throw new Error('unauthorized');return r.json()}).then(function(j){
    if(!j.ok)throw new Error('bad response');data=j;failures=0;document.body.removeAttribute('data-stale');$('err').hidden=true;render()})
  .catch(function(e){failures++;if(failures>=2)document.body.setAttribute('data-stale','1');$('err').hidden=false;$('err').textContent=e.message==='unauthorized'?'Not signed in. Open this page from the app, or add ?token=…':'Can’t reach the server. Retrying…';if(data)render()});
}
function loop(){clearTimeout(timer);if(document.hidden)return;load().then(function(){timer=setTimeout(loop,5000)},function(){timer=setTimeout(loop,5000)})}
document.addEventListener('visibilitychange',function(){if(!document.hidden)loop()});
loop();
})();
`;

export function systemHtml(): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">` +
    `<meta name="color-scheme" content="light dark"><title>system</title><style>` + SYSTEM_CSS + `</style></head><body>` +
    `<header class="top"><div><h1>system</h1><div class="sub" id="hostline">&nbsp;</div></div><span id="live" class="live" title="live"></span></header>` +
    `<div id="err" class="alert info" hidden></div><div id="alerts"></div><div class="chips" id="chips"></div>` +
    `<section class="grid" id="grid"></section>` +
    `<section class="card"><div class="row"><h2>History</h2><div class="seg sm" role="tablist">` +
    `<button data-range="15m" class="on">15m</button><button data-range="1h">1h</button><button data-range="3h">3h</button></div></div>` +
    `<div class="seg" role="tablist"><button data-tab="cpu" class="on">CPU</button><button data-tab="mem">Memory</button><button data-tab="load">Load</button><button data-tab="net">Network</button></div>` +
    `<div class="chart" id="chart"><div class="empty">collecting samples…</div></div></section>` +
    `<section class="card"><h2>Cores</h2><div class="cores" id="cores"></div></section>` +
    `<section class="card" id="memcard" hidden><h2>Memory</h2><div class="membar" id="membar"></div><div class="memleg" id="memleg"></div></section>` +
    `<section class="card"><h2>Load average</h2><div id="load"></div></section>` +
    `<section class="card"><div class="row"><h2>Processes</h2><div class="seg sm"><button data-sort="cpu" class="on">CPU</button><button data-sort="mem">Memory</button></div></div><div id="procs"></div>` +
    `<div class="hint">Tap an app to see its processes. Quit sends a normal quit; protected and other users’ processes are locked.</div></section>` +
    `<dialog id="dlg"><h3 id="dlgT"></h3><p id="dlgB"></p><label class="force"><input type="checkbox" id="dlgForce"> Force quit (kill immediately)</label><div class="err" id="dlgErr"></div>` +
    `<div class="btns"><button id="dlgNo" class="ghost">Cancel</button><button id="dlgGo" class="danger">Quit</button></div></dialog>` +
    `<script>` + CLIENT_JS + `</script></body></html>`;
}

const SYSTEM_CSS = `
:root{--bg:#F4EFE3;--surface:#FBF8F0;--sunk:#EDE6D6;--line:#E2DAC8;--ink:#262220;--mute:#6B645A;--faint:#8A8272;--accent:#3E6B4F;--chip:#E9E2D1;
--ok:#3E8E5E;--warn:#C98A1B;--bad:#C5483B;--c-cpu:#2F7F73;--c-mem:#C47F2C;--c-load:#7A6AB8;--c-rx:#3F6FB5;--c-tx:#B5547A;--c-disk:#8C7B5E;
--m-app:#E2A257;--m-wired:#E58AAE;--m-comp:#A99AE0;--m-cache:#9DB7A5;--m-free:#DDD5C2;
--shadow:0 1px 2px rgba(38,34,32,.05),0 10px 24px -14px rgba(38,34,32,.22);--mono:ui-monospace,SFMono-Regular,Menlo,monospace}
@media (prefers-color-scheme:dark){:root{--bg:#1A1712;--surface:#24201B;--sunk:#2C2721;--line:#3A352E;--ink:#ECE7DA;--mute:#A89E8C;--faint:#8F8676;--accent:#8FC4A0;--chip:#35302A;
--ok:#6FBF8B;--warn:#E0A94A;--bad:#E37A6D;--c-cpu:#5BC0AE;--c-mem:#E2A257;--c-load:#A99AE0;--c-rx:#7FA8E8;--c-tx:#E58AAE;--c-disk:#B9A582;
--m-app:#E2A257;--m-wired:#E58AAE;--m-comp:#A99AE0;--m-cache:#5E7C69;--m-free:#3A352E;--shadow:0 1px 2px rgba(0,0,0,.4),0 12px 28px -16px rgba(0,0,0,.7)}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0 auto;max-width:720px;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
padding:max(16px,env(safe-area-inset-top)) max(16px,env(safe-area-inset-right)) max(48px,env(safe-area-inset-bottom)) max(16px,env(safe-area-inset-left));-webkit-font-smoothing:antialiased;-webkit-tap-highlight-color:transparent}
body.embed{padding-top:4px;background:transparent}body.embed .top{display:none}
h1{font-size:28px;font-weight:750;letter-spacing:-.02em;line-height:1.15;margin:4px 0 0}
h2{font-size:12px;font-weight:650;letter-spacing:.06em;text-transform:uppercase;color:var(--mute);margin:0 0 10px}
h3{margin:0 0 6px;font-size:18px}
.top{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:14px}
.sub{font-size:14px;color:var(--mute)}.hint{font-size:12px;color:var(--faint);margin-top:8px}
.live{width:10px;height:10px;border-radius:50%;background:var(--ok);box-shadow:0 0 0 4px color-mix(in srgb,var(--ok) 20%,transparent);margin-top:12px;animation:pulse 2.4s ease-in-out infinite}
.live.off{background:var(--bad);animation:none;box-shadow:0 0 0 4px color-mix(in srgb,var(--bad) 20%,transparent)}
@keyframes pulse{50%{box-shadow:0 0 0 7px color-mix(in srgb,var(--ok) 6%,transparent)}}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 14px}.chip{font-size:12.5px;font-weight:600;color:var(--mute);background:var(--chip);border-radius:999px;padding:5px 12px}
.alert{background:color-mix(in srgb,var(--bad) 14%,var(--surface));border:1px solid color-mix(in srgb,var(--bad) 40%,var(--line));color:var(--ink);border-radius:14px;padding:10px 14px;margin-bottom:10px;font-size:14px;font-weight:600}
.alert.info{background:var(--sunk);border-color:var(--line);color:var(--mute);font-weight:500}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:20px;box-shadow:var(--shadow);padding:16px;margin-bottom:12px}
.grid .card{margin:0}.stat{position:relative;overflow:hidden;min-height:132px}
.lbl{font-size:12px;font-weight:650;letter-spacing:.06em;text-transform:uppercase;color:var(--mute)}
.big{font-size:30px;font-weight:750;letter-spacing:-.02em;line-height:1.2;margin-top:2px;font-variant-numeric:tabular-nums}
.stat .sub{font-size:12.5px;font-variant-numeric:tabular-nums}
.stat.ok .big{color:var(--ink)}.stat.warn .big{color:var(--warn)}.stat.bad .big{color:var(--bad)}
.dn{color:var(--c-rx);font-size:20px}.up{color:var(--c-tx)}
.stat .big:has(.dn){font-size:20px;margin-top:6px}
.spark{display:block;width:100%;height:36px;margin-top:8px}
.row{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px}.row h2{margin:0}
.seg{display:flex;gap:4px;padding:4px;background:var(--sunk);border:1px solid var(--line);border-radius:999px;margin-bottom:10px}
.seg.sm{margin:0;padding:3px}.seg button{flex:1;border:0;background:transparent;color:var(--mute);font:inherit;font-size:13.5px;font-weight:600;min-height:40px;padding:0 14px;border-radius:999px;cursor:pointer}
.seg.sm button{min-height:34px;padding:0 12px;font-size:13px}
.seg button.on{background:var(--ink);color:var(--surface)}
.chart{position:relative}#bigsvg{display:block;width:100%;height:auto;touch-action:pan-y;cursor:crosshair}
.gl{stroke:var(--line);stroke-width:1}.ax{fill:var(--faint);font-size:9.5px;font-variant-numeric:tabular-nums}.cursor{stroke:var(--faint);stroke-width:1;stroke-dasharray:3 3}
.tip{position:absolute;top:0;transform:translateX(-50%);background:var(--ink);color:var(--surface);border-radius:10px;padding:5px 10px;font-size:13px;white-space:nowrap;pointer-events:none;display:flex;flex-direction:column;align-items:center;line-height:1.3}
.tip span{font-size:11px;opacity:.7}.tip[hidden]{display:none}.tip b{font-variant-numeric:tabular-nums}
.empty{color:var(--mute);text-align:center;padding:28px 12px;border:1px dashed var(--line);border-radius:14px;font-size:14px}
.cores{display:grid;grid-template-columns:repeat(auto-fill,minmax(14px,34px));gap:5px;height:76px;align-items:end}
.core{background:var(--sunk);border-radius:6px;height:100%;display:flex;align-items:flex-end;overflow:hidden}.core i{display:block;width:100%;border-radius:6px 6px 0 0;transition:height .4s ease}
i.ok{background:var(--ok)}i.warn{background:var(--warn)}i.bad{background:var(--bad)}
.bar{height:8px;border-radius:99px;background:var(--sunk);overflow:hidden;margin-top:10px}.bar.thin{height:5px;margin-top:6px}.bar i{display:block;height:100%;border-radius:99px;transition:width .4s ease}
.membar{display:flex;height:14px;border-radius:99px;overflow:hidden;background:var(--sunk);gap:2px}.membar i{display:block;height:100%}
.memleg{display:flex;flex-wrap:wrap;gap:6px 14px;margin-top:12px;font-size:12.5px;color:var(--mute)}.memleg b{display:inline-block;width:9px;height:9px;border-radius:3px;margin-right:6px}.memleg .sw{margin-left:auto;color:var(--faint)}
.lrow{display:grid;grid-template-columns:52px 1fr 48px;align-items:center;gap:10px;margin:6px 0;font-size:13.5px;color:var(--mute)}.lrow .bar{margin:0}.lrow b{text-align:right;color:var(--ink);font-variant-numeric:tabular-nums}
.proc{border-top:1px solid var(--line);padding:11px 0}.proc:first-child{border-top:0}
.ph{display:flex;justify-content:space-between;align-items:baseline;gap:10px;cursor:pointer;min-height:28px}
.pn{font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}.pn em{font-style:normal;font-weight:500;color:var(--faint);font-size:12.5px}
.pv{font-variant-numeric:tabular-nums;font-weight:650;white-space:nowrap}.pv small{color:var(--faint);font-weight:500;margin-left:8px;font-size:12px}
.plist{margin-top:8px;background:var(--sunk);border-radius:14px;padding:4px 12px}
.prow{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 0;border-top:1px solid var(--line)}.prow:first-child{border-top:0}
.pm{min-width:0}.pc{font-size:13.5px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ps{font-size:12px;color:var(--faint);font-family:var(--mono)}
.quit{border:1px solid color-mix(in srgb,var(--bad) 50%,var(--line));background:transparent;color:var(--bad);font:inherit;font-size:13px;font-weight:650;border-radius:999px;min-height:40px;padding:0 16px;cursor:pointer}
.quit:active{background:color-mix(in srgb,var(--bad) 14%,transparent)}.lock{opacity:.45;padding:0 8px}
dialog{border:1px solid var(--line);border-radius:22px;background:var(--surface);color:var(--ink);padding:20px;width:min(380px,calc(100vw - 32px));box-shadow:0 20px 60px #0006}
dialog::backdrop{background:#0007}dialog p{color:var(--mute);margin:0 0 12px;font-size:14px}
.force{display:flex;gap:8px;align-items:center;font-size:14px;margin-bottom:8px}.err{color:var(--bad);font-size:13px;min-height:18px}
.btns{display:flex;gap:10px;justify-content:flex-end;margin-top:8px}.btns button{font:inherit;font-weight:650;min-height:44px;padding:0 20px;border-radius:999px;border:1px solid var(--line);cursor:pointer}
.ghost{background:transparent;color:var(--ink)}.danger{background:var(--bad);color:#fff;border-color:var(--bad)}.danger:disabled{opacity:.5}
:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
`;
