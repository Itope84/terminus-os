/** Generic turn runtime tests (multi-backend spec M9a + P11-P15 shapes).
 *  Stub adapters, no live providers. Shared lifecycle state is asserted
 *  through the real registry functions (exact-turn lookup, pending).
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  addChatFlusher,
  addChatListener,
  backendErrorBody,
  startBackendTurn,
  stopBackendTurns,
  summarizeToolInput,
  toBackendAttachments,
} from "../dist/turn-runtime.js";
import { readTurnResult, turnStatus } from "../dist/opencode-proxy.js";
import { BackendError } from "../dist/backends/errors.js";
import { join } from "node:path";
import { repoRoot } from "../dist/paths.js";

const caps = (over = {}) => ({
  streaming: true,
  models: true,
  commands: false,
  sessionsList: false,
  history: false,
  resume: true,
  interruptTurn: true,
  killProcess: true,
  compact: false,
  title: false,
  deleteSession: false,
  fork: false,
  approvals: false,
  attachments: false,
  overlappingTurns: false,
  ...over,
});

const stubAdapter = (hooks = {}) => {
  const calls = { start: 0, interrupt: 0 };
  const rec = { calls };
  const emits = new Map();
  const adapter = {
    id: "codex",
    descriptor: () => ({ id: "codex", label: "X", shortLabel: "X", available: true, authenticated: true, capabilities: caps(hooks.caps) }),
    listModels: async () => [],
    listCommands: async () => [],
    startTurn: async (args) => {
      calls.start++;
      if (hooks.failStart) throw hooks.failStart;
      hooks.onStart?.(args);
      return { accepted: true, remoteSessionId: "th-1", remoteTurnId: "cx-1" };
    },
    interruptTurn: async (args) => {
      calls.interrupt++;
      // Provider answers the interrupt with its terminal event — but only
      // when the test arms it (mirrors live: event follows the stop).
      if (hooks.interruptEmits && emits.has(args.turnId)) {
        emits.get(args.turnId)({ type: "turn.interrupted", turnId: args.turnId });
      }
      return { interrupted: true };
    },
    getStatus: async () => ({ state: "unknown" }),
    stream: async (args, emit) => {
      emits.set(args.turnId, emit);
      hooks.emit?.(args, emit, rec);
    },
  };
  return { adapter, calls, rec };
};

const base = (over = {}) => ({
  chatId: `c-m9-${Date.now()}-${Math.floor(Math.random() * 1e6)}`,
  backend: "codex",
  turnId: `t-m9-${Date.now()}`,
  remoteSessionId: null,
  text: "hi",
  files: [],
  model: null,
  title: null,
  systemPrompt: null,
  ...over,
});

describe("turn runtime (M9a)", () => {
  it("accepts with backend identity; same turnId never runs twice (P19)", async () => {
    const { adapter, calls } = stubAdapter();
    const a = base();
    const r1 = await startBackendTurn(adapter, a);
    assert.deepEqual([r1.accepted, r1.backend, r1.turnId, r1.sessionID], [true, "codex", a.turnId, "th-1"]);
    const r2 = await startBackendTurn(adapter, a);
    assert.equal(r2.sessionID, "th-1");
    assert.equal(calls.start, 1);
    assert.equal(turnStatus(a.chatId, a.turnId), "running");
  });
  it("rejects attachments when the backend lacks the capability", async () => {
    const { adapter } = stubAdapter();
    const a = base({ files: [{ name: "x.png", mime: "image/png", base64: "QQ==" }] });
    await assert.rejects(() => startBackendTurn(adapter, a), /attachments unsupported/);
  });
  it("fans out deltas live; completion settles exactly once (P11 shape)", async () => {
    const seen = [];
    let flushed = 0;
    const { adapter } = stubAdapter({
      emit: (args, emit) => {
        emit({ type: "message.delta", turnId: args.turnId, text: "hel" });
        emit({ type: "message.delta", turnId: args.turnId, text: "lo" });
        emit({ type: "turn.completed", turnId: args.turnId, text: "hello" });
        emit({ type: "turn.completed", turnId: args.turnId, text: "hello" });
      },
    });
    const a = base();
    const un = addChatListener(a.chatId, (ev, data) => seen.push([ev, JSON.parse(data).turnId]));
    const unflush = addChatFlusher(a.chatId, () => { flushed++; });
    await startBackendTurn(adapter, a);
    await new Promise((r) => setTimeout(r, 50));
    un();
    unflush();
    // Live vocabulary only (delta/step); dones flush per-connection.
    assert.deepEqual(seen.map((s) => s[0]), ["delta", "delta"]);
    assert.ok(seen.every((s) => s[1] === a.turnId));
    assert.ok(flushed >= 1, "terminal event flushes open streams");
    // Duplicate completion collapses: one settled record, client dedupes.
    assert.equal(turnStatus(a.chatId, a.turnId), "settled");
    const tr = readTurnResult(a.chatId, a.turnId);
    assert.equal(tr.text, "hello");
  });
  it("failed turns record errors; provider interrupts are durable, user stops are not", async () => {
    const { markUserStop } = await import("../dist/turn-runtime.js");
    const f = base();
    const { adapter: af } = stubAdapter({
      emit: (args, emit) => { emit({ type: "turn.failed", turnId: args.turnId, code: "usage_limit", message: "spent", retryable: false }); },
    });
    await startBackendTurn(af, f);
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(readTurnResult(f.chatId, f.turnId).error, "spent");
    // Provider-emitted interrupt (nobody asked): durable terminal result.
    const s = base();
    const { adapter: as } = stubAdapter({
      emit: (args, emit) => { emit({ type: "turn.interrupted", turnId: args.turnId }); },
    });
    await startBackendTurn(as, s);
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(turnStatus(s.chatId, s.turnId), "settled");
    assert.equal(readTurnResult(s.chatId, s.turnId).error, "interrupted");
    // User-requested stop: no durable result, no resurrection.
    const u = base();
    const { adapter: au } = stubAdapter({ interruptEmits: true });
    await startBackendTurn(au, u);
    const { stopBackendTurns } = await import("../dist/turn-runtime.js");
    await stopBackendTurns(au, u.chatId, "th-x", u.turnId);
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(readTurnResult(u.chatId, u.turnId), null);
  });
  it("stop fans out to named or all pending turns", async () => {
    const { adapter, calls } = stubAdapter();
    const a = base();
    await startBackendTurn(adapter, a);
    const one = await stopBackendTurns(adapter, a.chatId, "th-1", a.turnId);
    assert.deepEqual(one, { stopped: 1 });
    const all = await stopBackendTurns(adapter, a.chatId, "th-1");
    assert.equal(all.stopped, 1);
    assert.equal(calls.interrupt, 2);
    const none = await stopBackendTurns(adapter, "c-nothing-here", "th-x");
    assert.deepEqual(none, { stopped: 0 });
  });
  it("error bodies map codes without leaking internals", () => {
    assert.equal(backendErrorBody(new BackendError("usage_limit", "spent", false, "codex", "startTurn"), "codex", "startTurn").status, 429);
    assert.equal(backendErrorBody(new BackendError("not_authenticated", "login", false, "codex", "x"), "codex", "x").status, 401);
    assert.equal(backendErrorBody(new BackendError("session_not_found", "gone", false, "codex", "x"), "codex", "x").status, 404);
    assert.equal(backendErrorBody(new BackendError("invalid_request", "bad", false, "codex", "x"), "codex", "x").status, 400);
    assert.equal(backendErrorBody(new BackendError("backend_unavailable", "down", true, "codex", "x"), "codex", "x").status, 501);
    const unk = backendErrorBody(new Error("weird"), "claude", "startTurn");
    assert.equal(unk.status, 502);
    assert.equal(unk.body.backend, "claude");
  });
  it("gallery mapping rewrites artifacts paths, leaves the rest", async () => {
    const { mapGalleryUrlsForHost } = await import("../dist/opencode-proxy.js");
    const root = join(repoRoot(), "artifacts", "2026-10-07", "clip.mp4");
    const out = mapGalleryUrlsForHost(`see ${root} now`, "h:9009");
    assert.ok(out.includes("http://h:9009/terminus/2026-10-07/clip.mp4"), out);
    assert.ok(!out.includes(root));
    assert.equal(mapGalleryUrlsForHost("see /etc/passwd now", "h:9009"), "see /etc/passwd now");
  });
  it("runtime maps gallery URLs on codex completion", async () => {
    const seen = [];
    const root = join(repoRoot(), "artifacts", "x.png");
    const { adapter } = stubAdapter({
      emit: (args, emit) => { emit({ type: "turn.completed", turnId: args.turnId, text: `shot ${root}` }); },
    });
    const a = { ...base(), host: "h:9009" };
    const un = addChatListener(a.chatId, () => {});
    const { addChatFlusher: fl } = await import("../dist/turn-runtime.js");
    const unflush = fl(a.chatId, () => {
      const tr = readTurnResult(a.chatId, a.turnId);
      if (tr) seen.push(tr.text);
    });
    await startBackendTurn(adapter, a);
    await new Promise((r) => setTimeout(r, 50));
    un();
    unflush();
    assert.ok(seen.length > 0 && seen[0].includes("http://h:9009/terminus/"), JSON.stringify(seen).slice(0, 160));
  });
  it("summarizeToolInput says what runs, never secrets", () => {
    assert.equal(summarizeToolInput("  git status  "), "git status");
    assert.equal(summarizeToolInput({ command: "npm test  --silent" }), "npm test --silent");
    assert.equal(summarizeToolInput({ path: "/a/b.txt" }), "/a/b.txt");
    assert.equal(summarizeToolInput({ fileChanges: [{ path: "/a" }, { newPath: "/b" }] }), "/a, /b");
    assert.equal(summarizeToolInput({}), "");
    assert.equal(summarizeToolInput(null), "");
  });
  it("toBackendAttachments projects name/mime/base64", () => {
    assert.deepEqual(
      toBackendAttachments([{ name: "a/b.png", mime: "image/png", dataBase64: "QQ==" }]),
      [{ name: "b.png", mime: "image/png", base64: "QQ==" }],
    );
    assert.deepEqual(toBackendAttachments("x"), []);
  });
});
