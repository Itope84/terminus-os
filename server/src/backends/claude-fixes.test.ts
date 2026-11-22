import test from "node:test";
import { EventEmitter } from "node:events";
import assert from "node:assert/strict";
import { ClaudeAdapter, claudeError, claudeLine } from "./claude.js";

test("sub-agent text and thinking never reach the chat reply", () => {
  const sub = { type: "stream_event", parent_tool_use_id: "toolu_1", event: { delta: { type: "text_delta", text: "inner" } } };
  assert.deepEqual(claudeLine(sub, "t").events, []);
  const subThink = { type: "stream_event", parent_tool_use_id: "toolu_1", event: { delta: { type: "thinking_delta", thinking: "inner" } } };
  assert.deepEqual(claudeLine(subThink, "t").events, []);
  const main = { type: "stream_event", parent_tool_use_id: null, event: { delta: { type: "text_delta", text: "outer" } } };
  assert.equal(claudeLine(main, "t").events.length, 1);
});

test("error codes ignore stray numbers and common words, real shapes still map", () => {
  assert.equal(claudeError(new Error("wrote 4031 bytes"), "turn").code, "provider_error");
  assert.equal(claudeError(new Error("file closed by user script"), "turn").code, "provider_error");
  assert.equal(claudeError(new Error("API Error: 401 invalid token"), "turn").code, "not_authenticated");
  assert.equal(claudeError(new Error("Not logged in · Please run /login"), "turn").code, "not_authenticated");
  assert.equal(claudeError(new Error("claude exited code=1 signal=none"), "turn").code, "backend_unavailable");
  assert.equal(claudeError(new Error("usage limit reached"), "turn").code, "usage_limit");
});

test("the prompt travels on stdin, never in argv", async () => {
  const calls: string[][] = [];
  const written: string[] = [];
  const spawnFn = ((_b: string, argv: string[]) => {
    calls.push(argv);
    const ee: any = new EventEmitter();
    ee.stdin = { on() {}, end(t: string) { written.push(t); } };
    ee.stdout = new EventEmitter();
    ee.stderr = new EventEmitter();
    ee.kill = () => {};
    setImmediate(() => {
      ee.stdout.emit("data", Buffer.from(JSON.stringify({ type: "system", subtype: "init", session_id: "123e4567-e89b-12d3-a456-426614174000" }) + "\n"));
      ee.emit("exit", 0);
    });
    return ee;
  }) as any;
  const prev = process.env.TERMINUS_CLAUDE_PERSIST;
  process.env.TERMINUS_CLAUDE_PERSIST = "0"; // the one-process-per-turn path
  try {
    const a = new ClaudeAdapter("claude", spawnFn);
    await a.startTurn({ chatId: "c", turnId: "t", text: "secret prompt" } as any).catch(() => {});
    assert.ok(calls.length >= 1);
    assert.ok(!calls[0].includes("secret prompt"));
    assert.deepEqual(written, ["secret prompt"]);
  } finally {
    if (prev === undefined) delete process.env.TERMINUS_CLAUDE_PERSIST; else process.env.TERMINUS_CLAUDE_PERSIST = prev;
  }
});

test("compaction start and end ride the phone's compaction row events", () => {
  const started = claudeLine({ type: "system", subtype: "status", status: "compacting" }, "t");
  assert.deepEqual(started.events, [{ type: "compaction", turnId: "t", status: "started" }]);
  const ended = claudeLine({ type: "system", subtype: "compact_boundary", compact_metadata: { trigger: "auto", pre_tokens: 90000 } }, "t");
  assert.deepEqual(ended.events, [{ type: "compaction", turnId: "t", status: "ended", reason: "auto" }]);
  assert.deepEqual(claudeLine({ type: "system", subtype: "status", status: null }, "t").events, []);
});
