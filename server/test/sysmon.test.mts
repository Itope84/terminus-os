import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
const m: any = await import("../src/sysmon.ts");

// --- macOS fixtures ---
const vm = `Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                               21000.
Pages active:                            400000.
Pages inactive:                          380000.
Pages speculative:                         5000.
Pages throttled:                              0.
Pages wired down:                        150000.
Pages purgeable:                          30000.
"Translation faults":                 123456789.
Pages occupied by compressor:            120000.
File-backed pages:                       300000.
Anonymous pages:                         500000.
Pages stored in compressor:              260000.
`;
const total = 16 * 1024 ** 3;
const b = m.parseVmStat(vm, total);
assert.equal(b.app, (500000 - 30000) * 16384);
assert.equal(b.wired, 150000 * 16384);
assert.equal(b.compressed, 120000 * 16384);
assert.equal(b.cached, Math.min((300000 + 30000) * 16384, total - b.used));
assert.equal(b.used, b.app + b.wired + b.compressed);
assert.ok(b.free >= 0 && Math.abs(b.used + b.cached + b.free - total) <= 1);
assert.equal(m.parseVmStat("garbage", total), null);

assert.deepEqual(m.parseSwapUsage("total = 2048.00M  used = 1023.25M  free = 1024.75M  (encrypted)"), { total: 2048 * 1024 ** 2, used: 1023.25 * 1024 ** 2 });
assert.equal(m.parseSwapUsage("nope"), null);

const ns = `Name       Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
lo0        16384 <Link#1>                          100     0      10000      100     0      10000     0
lo0        16384 127           127.0.0.1           100     -      10000      100     -      10000     -
en0        1500  <Link#6>    a4:83:e7:11:22:33  5000     0    9000000     4000     0    3000000     0
en0        1500  192.168.1   192.168.1.5         5000     -    9000000     4000     -    3000000     -
utun3      1380  <Link#14>                         10     0       2000       10     0       2000     0
awdl0      1500  <Link#12>   aa:bb:cc:dd:ee:ff     1     0        100        1     0        100     0
en1*       1500  <Link#7>    a4:83:e7:11:22:44    20     0       5000       10     0       2500     0
`;
const n = m.parseNetstat(ns);
assert.deepEqual(n, { rx: 9000000 + 5000, tx: 3000000 + 2500 });
assert.equal(m.parseNetstat("x"), null);

const dev = `Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
  eth0: 5000 50 0 0 0 0 0 0 7000 70 0 0 0 0 0 0
docker0: 99 1 0 0 0 0 0 0 99 1 0 0 0 0 0 0
`;
assert.deepEqual(m.parseProcNetDev(dev), { rx: 5000, tx: 7000 });

assert.deepEqual(m.parsePmsetBatt("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=4653155)\t82%; discharging; 3:12 remaining present: true\n"), { pct: 82, state: "discharging", remaining: "3:12" });
assert.equal(m.parsePmsetBatt("Now drawing from 'AC Power'\n")?.pct, undefined);
assert.equal(m.parsePmsetBatt("Now drawing from 'AC Power'\n"), null);
assert.equal(m.parsePmsetBatt(" -InternalBattery-0 (id=1)\t100%; charged; 0:00 remaining present: true")?.state, "charged");
assert.equal(m.parsePmsetBatt(" -InternalBattery-0 (id=1)\t55%; discharging; (no estimate) present: true")?.pct, 55);

// --- ps + grouping ---
const ps = `  101     1   2.5  1.2  204800 501 /Applications/Google Chrome.app/Contents/MacOS/Google Chrome
  102   101  10.0  3.0  512000 501 /Applications/Google Chrome.app/Contents/Frameworks/Google Chrome Framework.framework/Versions/1/Helpers/Google Chrome Helper (Renderer).app/Contents/MacOS/Google Chrome Helper (Renderer)
  200     1   0.1  0.1    1024 0 /usr/libexec/launchd
  300     1   3.8  6.1  900000 501 /home/user/.opencode/bin/opencode
`;
const procs = m.parsePs(ps);
assert.equal(procs.length, 4);
assert.equal(procs[1].name, "Google Chrome");
assert.equal(procs[3].name, "opencode");
const groups = m.groupProcs(procs);
const chrome = groups.find((g: any) => g.name === "Google Chrome");
assert.equal(chrome.count, 2); assert.equal(chrome.cpu, 12.5); assert.equal(chrome.procs[0].pid, 102);
assert.equal(m.appNameOf("/usr/bin/node /srv/app.js --x"), "node");

// --- kill verdicts ---
const me = { pid: 999, user: "501" };
const P = (o: any) => ({ pid: 5, ppid: 1, cpu: 0, mem: 0, rss: 0, user: "501", cmd: "x", name: "x", ...o });
assert.match(m.killVerdict(undefined, "x", me, new Set()), /not found/);
assert.match(m.killVerdict(P({ pid: 1 }), "x", me, new Set()), /PID 1/);
assert.match(m.killVerdict(P({ pid: 999 }), "x", me, new Set()), /server itself/);
assert.match(m.killVerdict(P({ pid: 77 }), "x", me, new Set([77])), /launched/);
assert.match(m.killVerdict(P({ user: "0" }), "x", me, new Set()), /not your/);
assert.match(m.killVerdict(P({ name: "tailscaled" }), "tailscaled", me, new Set()), /protected/);
assert.match(m.killVerdict(P({ name: "other" }), "x", me, new Set()), /different process/);
assert.equal(m.killVerdict(P({}), "x", me, new Set()), null);
console.log("parsers + verdicts ok");

// --- live on this machine + HTTP ---
m.startSysmon();
await new Promise((r) => setTimeout(r, Number(process.env.SYSMON_WAIT || 4000)));
const json = (res: any, status: number, body: unknown) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
const server = http.createServer(async (req, res) => {
  const path = new URL(req.url!, "http://x").pathname;
  if (!(await m.handleSysmon(req, res, path, json))) { res.writeHead(404); res.end("nf"); }
});
await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${(server.address() as any).port}`;
const j: any = await (await fetch(base + "/api/sysmon?range=15m")).json();
assert.ok(j.ok); assert.ok(j.host.cores > 0); assert.ok(j.cur.mem.total > 0);
assert.ok(j.series.t.length >= 2, "series has samples: " + j.series.t.length);
assert.ok(j.procs.length > 3);
for (const g of j.procs) assert.ok(g.name && g.procs.length);
assert.ok(JSON.stringify(j).length < 60_000, "payload " + JSON.stringify(j).length);
console.log("api ok; samples", j.series.t.length, "procs", j.procs.length, "payload", JSON.stringify(j).length);
const html = await (await fetch(base + "/system")).text();
assert.ok(html.includes("<title>system</title>") && html.includes("sysmon"[0]));

// kill: needs header
let r = await fetch(base + "/api/sysmon/kill", { method: "POST", body: JSON.stringify({ pid: 2 }) });
assert.equal(r.status, 403);
const post = (body: any) => fetch(base + "/api/sysmon/kill", { method: "POST", headers: { "X-Terminus": "1", "Content-Type": "application/json" }, body: JSON.stringify(body) });
assert.equal((await post({ pid: 1 })).status, 409);
assert.equal((await post({ pid: process.pid })).status, 409);
assert.equal((await post({ pid: 99999999 })).status, 409);
assert.equal((await post({})).status, 400);
const child = spawn("sleep", ["300"], { stdio: "ignore" });
await new Promise((r) => setTimeout(r, 300));
r = await post({ pid: child.pid, name: "wrongname" });
assert.equal(r.status, 409, "name mismatch must refuse");
const exited = new Promise((res) => child.on("exit", (code, sig) => res(sig)));
r = await post({ pid: child.pid, name: "sleep" });
const rj: any = await r.json();
assert.ok(rj.ok, JSON.stringify(rj));
assert.equal(await exited, "SIGTERM");
console.log("kill guards ok");
server.close(); process.exit(0);
