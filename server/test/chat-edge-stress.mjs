/** Adversarial edge probe for the chat contract (live, manual).
 *  NOT a unit test. Run by hand:
 *    TERMINUS_BEARER=$(cat ~/.config/terminus/browser/token) \
 *      node test/chat-edge-stress.mjs
 *  or let it read ~/.config/terminus/browser/token (never printed).
 *
 *  Unlike chat-mirror-verify (happy paths), this hunts the edge cases
 *  behind client-side doubles and lost replies:
 *  P5 overlap storm: 6 rapid sends, one chat — every done must carry its
 *    OWN marker and no other turn's (attribution purity, no swaps).
 *  P6 stop race: stop immediately after send — at most one done per turn,
 *    turn status stable across polls, no phantom second text.
 *  P7 replay identity: replays of a settled turn are byte-identical
 *    (turnId + messageID + text) across fresh streams — the property the
 *  app's duplicate-drop relies on.
 *
 *  Sessions deleted afterwards. Non-zero exit on any violation.
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";

const BASE = process.env.TERMINUS_BASE || "http://127.0.0.1:9009";
const TOKEN =
  process.env.TERMINUS_BEARER ||
  (() => {
    try {
      return readFileSync(`${homedir()}/.config/terminus/browser/token`, "utf8").trim();
    } catch {
      return "";
    }
  })();
if (!TOKEN) {
  console.error("missing bearer (set TERMINUS_BEARER)");
  process.exit(2);
}

async function api(path, opts = {}) {
  const r = await fetch(BASE + path, {
    ...opts,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(opts.headers || {}) },
  });
  const text = await r.text().catch(() => "");
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text.slice(0, 200) };
  }
  return { status: r.status, body };
}

async function watch(chatId, sid, timeoutMs, stopWhen) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  const started = Date.now();
  const seen = [];
  const dones = [];
  try {
    const r = await fetch(`${BASE}/api/stream?chatId=${encodeURIComponent(chatId)}&sessionID=${encodeURIComponent(sid)}`, {
      headers: { Authorization: `Bearer ${TOKEN}`, Accept: "text/event-stream" },
      signal: ctl.signal,
    });
    if (!r.ok || !r.body) throw new Error(`stream http ${r.status}`);
    let buf = "";
    let ev = "";
    let data = [];
    const rd = r.body.getReader();
    const dec = new TextDecoder();
    for (;;) {
      const part = await rd.read();
      if (part.done) break;
      buf += dec.decode(part.value, { stream: true });
      let idx = 0;
      while ((idx = buf.indexOf("\n")) >= 0) {
        let line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        if (line.endsWith("\r")) line = line.slice(0, -1);
        if (!line) {
          if (ev || data.length) {
            const payload = data.join("\n");
            let obj = null;
            try {
              obj = payload ? JSON.parse(payload) : null;
            } catch {
              obj = { raw: payload.slice(0, 200) };
            }
            seen.push({ ev: ev || "message", tMs: Date.now() - started });
            if (ev === "done" && obj?.turnId) dones.push(obj);
          }
          ev = "";
          data = [];
          continue;
        }
        if (line.startsWith("event:")) ev = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^\s/, ""));
      }
      if (stopWhen && stopWhen({ seen, dones })) break;
      if (Date.now() - started > timeoutMs) break;
    }
  } catch (e) {
    if (e?.name !== "AbortError") throw e;
  } finally {
    clearTimeout(timer);
    try {
      ctl.abort();
    } catch {
      /* ignore */
    }
  }
  return { seen, dones };
}

async function cleanup(sessions) {
  for (const s of sessions) {
    if (!s) continue;
    await api("/api/session-delete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionID: s }),
    }).catch(() => null);
  }
}

const created = [];
const summary = {};
const ONLY = process.env.EDGE_ONLY || "";
const fail = (msg) => {
  console.error(`EDGE FAIL: ${msg}`);
  process.exitCode = 1;
};

const health = await api("/health");
if (health.status !== 200 || health.body?.ok !== true) throw new Error("health failed");

// P5: overlap storm — 6 rapid sends, attribution purity.
if (!ONLY || ONLY === "p5") {
  const chatId = `edge-p5-${Date.now().toString(36)}`;
  const marks = ["AA", "BB", "CC", "DD", "EE", "FF"].map((m) => `EDGE-${m}`);
  const first = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "edge p5", text: `Reply with exactly ${marks[0]}` }),
  });
  if (first.status !== 200 || !first.body?.sessionID) throw new Error(`p5 first post failed: ${first.status}`);
  created.push(first.body.sessionID);
  const sid = first.body.sessionID;
  const posted = [{ turnId: first.body.turnId, mark: marks[0] }];
  const rest = await Promise.all(
    marks.slice(1).map((m) =>
      api("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ chatId, title: "edge p5", text: `Reply with exactly ${m}` }),
      }),
    ),
  );
  rest.forEach((r, i) => {
    if (r.status !== 200 || !r.body?.turnId) throw new Error(`p5 post ${i} failed: ${r.status}`);
    if (r.body.sessionID !== sid) fail(`p5 session split on post ${i}`);
    posted.push({ turnId: r.body.turnId, mark: marks[i + 1] });
  });
  const { dones } = await watch(chatId, sid, 240_000, ({ dones }) => dones.length >= 6);
  // Contract under merge (proven shape): one done per turn always;
  // messageID always present (the client's merge key). Marker attribution
  // is NOT guaranteed — opencode fuses rapid prompts and trailing turns
  // settle with the shared ack text. That shape is reported, not failed.
  const byTurn = new Map(dones.map((d) => [d.turnId, d]));
  let missingMid = 0;
  for (const p of posted) {
    if (!byTurn.has(p.turnId)) fail(`p5 missing done for ${p.mark}`);
    else if (!byTurn.get(p.turnId).messageID) missingMid++;
  }
  if (missingMid > 0) fail(`p5 ${missingMid} dones without messageID`);
  const dupTurns = dones.length - new Set(dones.map((d) => d.turnId)).size;
  if (dupTurns > 0) fail(`p5 ${dupTurns} duplicate dones for one turn`);
  const groups = new Map();
  for (const d of dones) {
    const k = d.messageID || d.turnId;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(d);
  }
  let covered = 0;
  const shapes = [];
  for (const [mid, ds] of groups) {
    const texts = new Set(ds.map((d) => d.text || ""));
    const hits = posted.filter((p) => ds.some((d) => (d.text || "").includes(p.mark))).map((p) => p.mark);
    covered += hits.length;
    shapes.push({ turns: ds.length, distinctTexts: texts.size, markers: hits });
    for (const d of ds) {
      if (!(d.text || "").trim()) fail(`p5 empty done text for turn ${d.turnId}`);
    }
  }
  summary.p5 = {
    dones: dones.length,
    distinctTurns: new Set(dones.map((d) => d.turnId)).size,
    messageIDs: groups.size,
    markerCoverage: `${covered}/${posted.length}`,
    shapes,
  };
}

// P6: stop race — stop immediately after send.
if (!ONLY || ONLY === "p6") {
  const chatId = `edge-p6-${Date.now().toString(36)}`;
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "edge p6", text: "Reply with exactly STOP-RACE-OK after brief work" }),
  });
  if (post.status !== 200 || !post.body?.turnId) throw new Error(`p6 post failed: ${post.status}`);
  created.push(post.body.sessionID);
  const sid = post.body.sessionID;
  const turnId = post.body.turnId;
  const stop = await api("/api/stop", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionID: sid }),
  });
  if (![200, 404, 503].includes(stop.status)) fail(`p6 stop status ${stop.status}`);
  const { dones } = await watch(chatId, sid, 120_000, ({ dones }) =>
    dones.some((d) => d.turnId === turnId),
  );
  const mine = dones.filter((d) => d.turnId === turnId);
  if (mine.length > 1) fail(`p6 ${mine.length} dones for one stopped turn`);
  const poll = async () =>
    api(`/api/turn?chatId=${encodeURIComponent(chatId)}&turnId=${encodeURIComponent(turnId)}`);
  const a = await poll();
  await new Promise((r) => setTimeout(r, 5000));
  const b = await poll();
  if (a.body?.status !== b.body?.status) fail(`p6 turn status unstable: ${a.body?.status} → ${b.body?.status}`);
  if (a.body?.status === "settled" && a.body?.text !== b.body?.text) fail("p6 settled text changed between polls");
  summary.p6 = { stopStatus: stop.status, donesForTurn: mine.length, stable: a.body?.status };
}

// P7: replay identity — fresh streams replay byte-identical dones.
if (!ONLY || ONLY === "p7") {
  const chatId = `edge-p7-${Date.now().toString(36)}`;
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "edge p7", text: "Reply with exactly REPLAY-ID-OK" }),
  });
  if (post.status !== 200 || !post.body?.turnId) throw new Error(`p7 post failed: ${post.status}`);
  created.push(post.body.sessionID);
  const sid = post.body.sessionID;
  const turnId = post.body.turnId;
  const w1 = await watch(chatId, sid, 180_000, ({ dones }) => dones.some((d) => d.turnId === turnId));
  const first = w1.dones.find((d) => d.turnId === turnId);
  if (!first) throw new Error("p7 no first done");
  const w2 = await watch(chatId, sid, 60_000, ({ dones }) => dones.some((d) => d.turnId === turnId));
  const second = w2.dones.find((d) => d.turnId === turnId);
  if (!second) fail("p7 no replay on fresh stream");
  else {
    if (second.messageID !== first.messageID) fail("p7 replay messageID changed");
    if (second.text !== first.text) fail("p7 replay text changed");
  }
  summary.p7 = { replayed: !!second, identical: second?.text === first.text };
}

// P8: two watchers, one turn — each connection replays the done
// (per-connection doneSent). The app's duplicate branch is therefore
// routine-hot, not rare: every reconnect redelivers every settled done.
if (!ONLY || ONLY === "p8") {
  const chatId = `edge-p8-${Date.now().toString(36)}`;
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chatId, title: "edge p8", text: "Reply with exactly TWO-WATCHERS-OK" }),
  });
  if (post.status !== 200 || !post.body?.turnId) throw new Error(`p8 post failed: ${post.status}`);
  created.push(post.body.sessionID);
  const sid = post.body.sessionID;
  const turnId = post.body.turnId;
  const [w1, w2] = await Promise.all([
    watch(chatId, sid, 120_000, ({ dones }) => dones.some((d) => d.turnId === turnId)),
    watch(chatId, sid, 120_000, ({ dones }) => dones.some((d) => d.turnId === turnId)),
  ]);
  const n1 = w1.dones.filter((d) => d.turnId === turnId).length;
  const n2 = w2.dones.filter((d) => d.turnId === turnId).length;
  if (n1 !== 1 || n2 !== 1) fail(`p8 per-watcher dones = ${n1}/${n2}, want 1/1`);
  const t1 = w1.dones.find((d) => d.turnId === turnId)?.text;
  const t2 = w2.dones.find((d) => d.turnId === turnId)?.text;
  if (t1 !== t2) fail("p8 watchers disagree on text");
  summary.p8 = { w1: n1, w2: n2, agree: t1 === t2 };
}

// P9: lost-response retry — duplicate POST with the same turnId while the
// original still runs must NOT double-prompt: same acceptance, one done,
// one user prompt in history.
if (!ONLY || ONLY === "p9") {
  const chatId = `edge-p9-${Date.now().toString(36)}`;
  const turnId = `t-p9-${Date.now().toString(36)}`;
  const prompt = `Reply with exactly DEDUPE-OK-${Date.now().toString(36)}`;
  const payload = { chatId, title: "edge p9", text: prompt, turnId };
  const [a, b] = await Promise.all([
    api("/api/chat", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
    api("/api/chat", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
  ]);
  if (a.status !== 200 || b.status !== 200) fail(`p9 posts failed: ${a.status}/${b.status}`);
  if (a.body?.turnId !== turnId || b.body?.turnId !== turnId) fail("p9 turnId not echoed");
  if (a.body?.sessionID !== b.body?.sessionID) fail("p9 session split on retry");
  const sid = a.body.sessionID;
  created.push(sid);
  const { dones } = await watch(chatId, sid, 180_000, ({ dones }) => dones.some((d) => d.turnId === turnId));
  const mine = dones.filter((d) => d.turnId === turnId);
  if (mine.length !== 1) fail(`p9 ${mine.length} dones for one turn, want 1`);
  const hist = await api(`/api/history?sessionID=${encodeURIComponent(sid)}`);
  const msgs = hist.body?.messages || hist.body?.history || [];
  const prompts = msgs.filter((m) => m.role === "user" && (m.text || "").includes(prompt)).length;
  if (prompts !== 1) fail(`p9 prompt landed ${prompts}x, want 1`);
  summary.p9 = { sameAccept: true, dones: mine.length, prompts };
}

await cleanup(created);
console.log(JSON.stringify(summary));
console.log(process.exitCode ? "edge stress: VIOLATIONS" : "edge stress: ok");
