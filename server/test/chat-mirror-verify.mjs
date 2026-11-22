/** Manual live verification for the chat mirror (same endpoints + same
 *  request ordering as the app).
 *
 *  This is NOT a unit test and must not match test/*.test.mjs. Run it by hand:
 *    TERMINUS_BEARER=$(cat ~/.config/terminus/browser/token) \
 *      node test/chat-mirror-verify.mjs
 *  or let it read ~/.config/terminus/browser/token (never printed).
 *
 *  Phases (each on a throwaway chat, sessions deleted afterwards):
 *  P1 first-turn streaming: stream opens BEFORE the first POST with a blank
 *    session id (exactly like Android). Asserts intermediate delta/step
 *    events arrive before `done`. Reasoning text is optional.
 *  P2 double-send race: two concurrent POSTs on a fresh chat must return
 *    the SAME sessionID (one chat, one session); both dones must arrive.
 *  P3 lost-completion recovery: POST with no stream open (disconnected
 *    client), poll GET /api/turn until settled, then connect and assert
 *    the original turnId (+messageID) replays exactly once.
 *
 *  Device-test responsibilities (NOT covered here): cross-chat stream
 *  ownership, real WebView scroll behavior, process-death rehydrate.
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";

const BASE = process.env.TERMINUS_BASE || "http://127.0.0.1:9009";
/** Drive another backend (e.g. TERMINUS_BACKEND=claude); unset = the server default. */
const BACKEND = process.env.TERMINUS_BACKEND || "";
const bk = () => (BACKEND ? { backend: BACKEND } : {});
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

/** Open a stream and collect events until stopWhen() or timeoutMs.
 *  Resolves { seen: [{ev, tMs}], dones: [obj...] }. */
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

const health = await api("/health");
if (health.status !== 200 || health.body?.ok !== true) throw new Error("health failed");

// P1: stream (blank session, pre-send) THEN post.
{
  const chatId = `mirror-p1-${Date.now().toString(36)}`;
  const watchP = watch(chatId, "", 150_000, ({ dones }) => dones.length >= 1);
  await new Promise((r) => setTimeout(r, 1500)); // let the stream establish
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...bk(), chatId, title: "mirror p1", text: "Reply with exactly this line, then two short sentences about rivers: MIRROR-STREAM-OK" }),
  });
  if (post.status !== 200 || !post.body?.turnId || !post.body?.sessionID) throw new Error(`p1 post failed: ${post.status}`);
  created.push(post.body.sessionID);
  const { seen, dones } = await watchP;
  if (dones.length < 1 || dones[0].turnId !== post.body.turnId) throw new Error("p1 done turn mismatch");
  if (!dones[0].text?.includes("MIRROR-STREAM-OK")) throw new Error("p1 done text mismatch");
  const doneT = seen.find((s) => s.ev === "done")?.tMs ?? Infinity;
  const early = seen.filter((s) => (s.ev === "delta" || s.ev === "step") && s.tMs < doneT);
  if (early.length < 1) throw new Error("p1 no intermediate delta/step before done");
  summary.p1 = { preDoneEvents: early.length, doneChars: dones[0].text.length, messageID: Boolean(dones[0].messageID) };
}

// P2: two concurrent posts on a fresh chat → one session, two dones.
{
  const chatId = `mirror-p2-${Date.now().toString(36)}`;
  const payload = (t) => JSON.stringify({ ...bk(), chatId, title: "mirror p2", text: t });
  const [a, b] = await Promise.all([
    api("/api/chat", { method: "POST", headers: { "Content-Type": "application/json" }, body: payload("Reply with exactly DOUBLE-VERIFY-A") }),
    api("/api/chat", { method: "POST", headers: { "Content-Type": "application/json" }, body: payload("Reply with exactly DOUBLE-VERIFY-B") }),
  ]);
  if (a.status !== 200 || b.status !== 200) throw new Error(`p2 posts failed: ${a.status}/${b.status}`);
  if (!a.body?.sessionID || a.body.sessionID !== b.body.sessionID) throw new Error("p2 session split across concurrent first sends");
  created.push(a.body.sessionID);
  const { dones } = await watch(chatId, a.body.sessionID, 180_000, ({ dones }) => dones.length >= 2);
  const ids = new Set(dones.map((d) => d.turnId));
  if (!ids.has(a.body.turnId) || !ids.has(b.body.turnId)) throw new Error("p2 missing done for a concurrent turn");
  summary.p2 = { sessionShared: true, dones: dones.length, oneMessageID: new Set(dones.map((d) => d.messageID || d.turnId)).size };
}

// P3: disconnected client → poll /api/turn → reconnect replays exactly once.
{
  const chatId = `mirror-p3-${Date.now().toString(36)}`;
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...bk(), chatId, title: "mirror p3", text: "Reply with exactly RECOVERY-VERIFY-OK" }),
  });
  if (post.status !== 200 || !post.body?.turnId || !post.body?.sessionID) throw new Error(`p3 post failed: ${post.status}`);
  created.push(post.body.sessionID);
  const turnId = post.body.turnId;
  let settled = null;
  for (let i = 0; i < 48; i++) {
    await new Promise((r) => setTimeout(r, 5000));
    const q = await api(`/api/turn?chatId=${encodeURIComponent(chatId)}&turnId=${encodeURIComponent(turnId)}`);
    if (q.status === 200 && q.body?.status === "settled") {
      settled = q.body;
      break;
    }
  }
  if (!settled) throw new Error("p3 turn never settled while disconnected");
  if (!settled.text?.includes("RECOVERY-VERIFY-OK")) throw new Error("p3 settled text mismatch");
  const { dones } = await (async () => {
    // Stay connected 12s past the first replay: a duplicate would surface
    // on this same connection, not a later one.
    let firstAt = 0;
    return watch(chatId, post.body.sessionID, 90_000, ({ dones }) => {
      if (dones.length >= 1 && !firstAt) firstAt = Date.now();
      return Boolean(firstAt && Date.now() - firstAt > 12000);
    });
  })();
  const mine = dones.filter((d) => d.turnId === turnId);
  if (mine.length !== 1) throw new Error(`p3 replay count = ${mine.length}, want exactly 1`);
  if (settled.messageID && mine[0].messageID !== settled.messageID) throw new Error("p3 messageID mismatch between /api/turn and replayed done");
  summary.p3 = { settledViaPoll: true, replayedOnce: true, messageID: Boolean(mine[0].messageID) };
}

// P4: session death mid-life → the exact contract dead-turn auto-settle
// needs. A completed turn on a then-deleted session must stay recoverable
// (retained done replays once) while liveness reads authoritative death:
// progress 404, stop 404, unknown turn stays unknown. The app delivers the
// retained done when its placeholder stands, else settles via the 404s.
{
  const chatId = `mirror-p4-${Date.now().toString(36)}`;
  const post = await api("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...bk(), chatId, title: "mirror p4", text: "Reply with exactly DEATH-VERIFY-OK" }),
  });
  if (post.status !== 200 || !post.body?.turnId || !post.body?.sessionID) throw new Error(`p4 post failed: ${post.status}`);
  const sid = post.body.sessionID;
  const turnId = post.body.turnId;
  const { dones } = await watch(chatId, sid, 180_000, ({ dones }) => dones.length >= 1);
  if (!dones[0].text?.includes("DEATH-VERIFY-OK")) throw new Error("p4 done text mismatch");
  await api("/api/session-delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...bk(), chatId, sessionID: sid }),
  });
  const prog = await api(`/api/progress?chatId=${encodeURIComponent(chatId)}&sessionID=${encodeURIComponent(sid)}${BACKEND ? `&backend=${BACKEND}` : ""}`);
  if (prog.status !== 404) throw new Error(`p4 progress after death = ${prog.status}, want 404`);
  const stop = await api("/api/stop", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...bk(), chatId, sessionID: sid }),
  });
  if (stop.status !== 404) throw new Error(`p4 stop after death = ${stop.status}, want 404`);
  const bogus = await api(`/api/turn?chatId=${encodeURIComponent(chatId)}&turnId=t-nope`);
  if (bogus.status !== 200 || bogus.body?.status !== "unknown") throw new Error("p4 bogus turn not unknown");
  const kept = await api(`/api/turn?chatId=${encodeURIComponent(chatId)}&turnId=${encodeURIComponent(turnId)}`);
  if (kept.status !== 200 || kept.body?.status !== "settled") throw new Error("p4 completed turn lost after session death");
  summary.p4 = { progress404: true, stop404: true, unknownStaysUnknown: true, retainedDoneSurvives: true };
}

await cleanup(created);
console.log(JSON.stringify(summary));
console.log("mirror verify: ok");
