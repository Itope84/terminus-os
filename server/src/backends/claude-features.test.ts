import test from "node:test";
import { EventEmitter } from "node:events";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ClaudeAdapter, claudeLine, claudeProjectDir } from "./claude.js";

test("claude extended thinking streams as reasoning, text still streams as the reply", () => {
  const think = claudeLine({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "thinking_delta", thinking: "weighing it" } } }, "t1");
  assert.deepEqual(think.events, [{ type: "reasoning.delta", turnId: "t1", text: "weighing it" }]);
  const text = claudeLine({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: "hi" } } }, "t1");
  assert.deepEqual(text.events, [{ type: "message.delta", turnId: "t1", text: "hi" }]);
  const empty = claudeLine({ type: "stream_event", event: { delta: { type: "thinking_delta", thinking: "" } } }, "t1");
  assert.deepEqual(empty.events, []);
});

test("claude live status: thinking token count, tool being composed, cleared when text starts", () => {
  const tok = claudeLine({ type: "system", subtype: "thinking_tokens", estimated_tokens: 310 }, "t1");
  assert.deepEqual(tok.events, [{ type: "status", turnId: "t1", text: "Thinking · 310 tokens" }]);
  assert.deepEqual(claudeLine({ type: "system", subtype: "thinking_tokens", estimated_tokens: 9, parent_tool_use_id: "x" }, "t1").events, []);
  const tool = claudeLine({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "tool_use", name: "Write" } } }, "t1");
  assert.deepEqual(tool.events, [{ type: "status", turnId: "t1", text: "Preparing Write…" }]);
  const text = claudeLine({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text", text: "" } } }, "t1");
  assert.deepEqual(text.events, [{ type: "status", turnId: "t1", text: "" }]);
});

test("claude advertises what it implements", () => {
  const caps = new ClaudeAdapter().descriptor().capabilities;
  assert.equal(caps.commands, true);
  assert.equal(caps.deleteSession, true);
  assert.equal(caps.compact, true);
  assert.equal(caps.title, true);
  assert.equal(caps.fork, true);
  assert.equal(caps.steer, true); // resident process takes a message mid-turn
});

test("deleteSession removes the transcript, is idempotent, and refuses odd ids", async () => {
  const cfg = mkdtempSync(join(tmpdir(), "cfg-"));
  const prev = process.env.CLAUDE_CONFIG_DIR;
  process.env.CLAUDE_CONFIG_DIR = cfg;
  try {
    const id = "123e4567-e89b-12d3-a456-426614174000";
    const dir = claudeProjectDir();
    mkdirSync(dir, { recursive: true });
    const file = join(dir, `${id}.jsonl`);
    writeFileSync(file, "{}\n");
    const a = new ClaudeAdapter();
    await a.deleteSession({ chatId: "c", remoteSessionId: id });
    assert.equal(existsSync(file), false);
    await a.deleteSession({ chatId: "c", remoteSessionId: id });
    await assert.rejects(a.deleteSession({ chatId: "c", remoteSessionId: "../../etc/passwd" }));
  } finally {
    if (prev === undefined) delete process.env.CLAUDE_CONFIG_DIR; else process.env.CLAUDE_CONFIG_DIR = prev;
  }
});

function fakeSpawn(calls: string[][], initId: string | null) {
  return ((_bin: string, argv: string[]) => {
    calls.push(argv);
    if (initId === null) throw new Error("no cli");
    const ee: any = new EventEmitter();
    ee.stdin = { on() {}, end() {} };
    ee.stdout = new EventEmitter();
    ee.stderr = new EventEmitter();
    ee.kill = () => {};
    setImmediate(() => {
      ee.stdout.emit("data", Buffer.from(JSON.stringify({ type: "system", subtype: "init", session_id: initId }) + "\n"));
      ee.emit("exit", 0);
    });
    return ee;
  }) as any;
}

async function withConfigDir<T>(fn: (dir: string) => Promise<T>): Promise<T> {
  const cfg = mkdtempSync(join(tmpdir(), "cfg-"));
  const prev = process.env.CLAUDE_CONFIG_DIR;
  process.env.CLAUDE_CONFIG_DIR = cfg;
  try {
    const dir = claudeProjectDir();
    mkdirSync(dir, { recursive: true });
    return await fn(dir);
  } finally {
    if (prev === undefined) delete process.env.CLAUDE_CONFIG_DIR; else process.env.CLAUDE_CONFIG_DIR = prev;
  }
}

const SRC = "123e4567-e89b-12d3-a456-426614174000";
const NEW = "323e4567-e89b-12d3-a456-426614174000";

test("fork resumes with --fork-session and uses the id the CLI reports", async () => {
  await withConfigDir(async (dir) => {
    writeFileSync(join(dir, `${SRC}.jsonl`), JSON.stringify({ type: "user", sessionId: SRC }) + "\n");
    const calls: string[][] = [];
    const { remoteSessionId } = await new ClaudeAdapter("claude", fakeSpawn(calls, NEW)).fork({ chatId: "c", remoteSessionId: SRC });
    assert.equal(remoteSessionId, NEW);
    assert.ok(calls[0].includes("--fork-session") && calls[0].includes(SRC));
    assert.equal(existsSync(join(dir, `${NEW}.jsonl`)), true); // the CLI left nothing on disk: copied under its id
  });
});

test("fork still works when the CLI is unavailable, and refuses a missing or odd source", async () => {
  await withConfigDir(async (dir) => {
    writeFileSync(join(dir, `${SRC}.jsonl`), JSON.stringify({ type: "user", sessionId: SRC }) + "\n");
    const a = new ClaudeAdapter("claude", fakeSpawn([], null));
    const { remoteSessionId } = await a.fork({ chatId: "c", remoteSessionId: SRC });
    assert.notEqual(remoteSessionId, SRC);
    assert.equal(existsSync(join(dir, `${remoteSessionId}.jsonl`)), true);
    await assert.rejects(a.fork({ chatId: "c", remoteSessionId: "223e4567-e89b-12d3-a456-426614174000" }));
    await assert.rejects(a.fork({ chatId: "c", remoteSessionId: "../x" }));
  });
});
