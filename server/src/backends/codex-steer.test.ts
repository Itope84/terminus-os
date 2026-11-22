import test from "node:test";
import assert from "node:assert/strict";
import { CodexAdapter } from "./codex.js";
import type { CodexRpcClient } from "./codex-rpc.js";
import type { BackendEvent, StartTurnArgs } from "./types.js";

function harness(opts: { steerFails?: boolean; startDelayMs?: number } = {}) {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  let notify: (m: string, p: unknown) => void = () => {};
  let turnSeq = 0;
  const rpc = {
    started: false,
    async start() { this.started = true; },
    onNotification(cb: (m: string, p: unknown) => void) { notify = cb; return () => {}; },
    onRequest() {},
    onClose() { return () => {}; },
    async close() {},
    async request(method: string, params: Record<string, unknown>) {
      calls.push({ method, params });
      if (method === "thread/start") return { thread: { id: "th1" } };
      if (method === "turn/start") {
        if (opts.startDelayMs) await new Promise((r) => setTimeout(r, opts.startDelayMs));
        return { turn: { id: `ct${++turnSeq}` } };
      }
      if (method === "turn/steer") {
        if (opts.steerFails) throw new Error("no active turn");
        return { turnId: "ct1" };
      }
      return {};
    },
  };
  const adapter = new CodexAdapter(() => rpc as unknown as CodexRpcClient);
  const args = (turnId: string, text: string): StartTurnArgs =>
    ({ chatId: "c1", turnId, remoteSessionId: null, text, files: [], model: null, title: null, systemPrompt: null, effort: null, cwd: null, compactAt: null }) as StartTurnArgs;
  const events: Record<string, BackendEvent[]> = {};
  const attach = async (turnId: string) => {
    events[turnId] = [];
    await adapter.stream!({ chatId: "c1", turnId, remoteSessionId: "th1" } as never, (e) => { events[turnId].push(e); });
  };
  const tick = () => new Promise((r) => setTimeout(r, 15));
  const complete = (ctId: string, text: string) =>
    notify("turn/completed", { turn: { id: ctId, status: "completed", items: [{ text }] } });
  const count = (m: string) => calls.filter((c) => c.method === m).length;
  return { adapter, args, attach, events, tick, complete, count, calls };
}

const done = (evs: BackendEvent[]) => evs.find((e) => e.type === "turn.completed") as Extract<BackendEvent, { type: "turn.completed" }> | undefined;

test("a waiting message sent now folds into the running turn and shares its one reply", async () => {
  const h = harness();
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    assert.equal(h.count("turn/start"), 1, "B waits behind A");
    assert.equal(h.count("turn/steer"), 0, "a plain send never steers");
    await h.attach("A"); await h.attach("B");
    assert.equal(await h.adapter.steerQueued!("c1", "B"), true);
    const steer = h.calls.find((c) => c.method === "turn/steer")!;
    assert.equal(steer.params.expectedTurnId, "ct1");
    assert.equal(steer.params.threadId, "th1");
    assert.equal((steer.params.input as Array<{ text: string }>)[0].text, "second");
    assert.equal(h.count("turn/start"), 1, "B never starts a turn of its own");
    h.complete("ct1", "answer");
    await h.tick();
    const a = done(h.events.A)!, b = done(h.events.B)!;
    assert.equal(a.text, "answer");
    assert.equal(b.text, "answer");
    assert.equal(b.turnId, "B");
    assert.equal(a.remoteMessageId, "ct1");
    assert.equal(b.remoteMessageId, "ct1");
  } finally { await h.adapter.shutdown(); }
});

test("the queue behind a folded message moves on once the running turn ends", async () => {
  const h = harness();
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.adapter.startTurn(h.args("C", "third"));
    await h.tick();
    assert.equal(await h.adapter.steerQueued!("c1", "B"), true);
    assert.equal(h.count("turn/start"), 1, "C still waits for A");
    h.complete("ct1", "answer");
    await h.tick();
    assert.equal(h.count("turn/start"), 2, "C starts once A (and the folded B) are done");
    assert.equal((h.calls.filter((c) => c.method === "turn/start")[1].params.input as Array<{ text: string }>)[0].text, "third");
  } finally { await h.adapter.shutdown(); }
});

test("a folded message attached after the turn ended still settles", async () => {
  const h = harness();
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    assert.equal(await h.adapter.steerQueued!("c1", "B"), true);
    h.complete("ct1", "answer");
    await h.attach("B");
    assert.equal(done(h.events.B)?.text, "answer");
  } finally { await h.adapter.shutdown(); }
});

test("send now changes nothing when the message cannot be steered", async () => {
  const h = harness({ steerFails: true });
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    assert.equal(await h.adapter.steerQueued!("c1", "B"), false, "provider refused");
    assert.equal(await h.adapter.steerQueued!("c1", "A"), true, "A is already running: nothing left waiting");
    assert.equal(await h.adapter.steerQueued!("c1", "nope"), false, "unknown turn");
    h.complete("ct1", "one");
    await h.tick();
    assert.equal(h.count("turn/start"), 2, "B still starts normally after A");
  } finally { await h.adapter.shutdown(); }
});

test("CODEX_STEER=0 keeps strict turn-by-turn", async () => {
  const old = process.env.CODEX_STEER;
  process.env.CODEX_STEER = "0";
  const h = harness();
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    assert.equal(await h.adapter.steerQueued!("c1", "B"), false);
    assert.equal(h.count("turn/steer"), 0);
  } finally {
    await h.adapter.shutdown();
    if (old === undefined) delete process.env.CODEX_STEER; else process.env.CODEX_STEER = old;
  }
});

test("send now on a message that has already started reports success, with nothing to fold", async () => {
  const h = harness();
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.tick();
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    await h.attach("A"); await h.attach("B");
    h.complete("ct1", "one");
    await h.tick(); await h.tick();
    assert.equal(h.count("turn/start"), 2, "B started on its own after A");
    assert.equal(await h.adapter.steerQueued!("c1", "B"), true, "B is going: never 'stays in line'");
    assert.equal(h.count("turn/steer"), 0);
  } finally { await h.adapter.shutdown(); }
});

test("send now while the turn ahead is still starting waits for it, then folds in", async () => {
  const h = harness({ startDelayMs: 300 });
  try {
    await h.adapter.startTurn(h.args("A", "first"));
    await h.adapter.startTurn(h.args("B", "second"));
    await h.tick();
    assert.equal(h.count("turn/steer"), 0, "A has no provider turn yet");
    assert.equal(await h.adapter.steerQueued!("c1", "B"), true);
    assert.equal(h.count("turn/steer"), 1);
    assert.equal(h.count("turn/start"), 1, "B never got its own turn");
  } finally { await h.adapter.shutdown(); }
});
