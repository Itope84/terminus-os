import test from "node:test";
import { EventEmitter } from "node:events";
import assert from "node:assert/strict";
import { ClaudeAdapter, claudeArgv, claudePersist } from "./claude.js";

const SID = "123e4567-e89b-12d3-a456-426614174000";

function fakeResident() {
  const writes: string[] = [];
  const argvs: string[][] = [];
  let spawns = 0;
  const spawnFn = ((_b: string, argv: string[]) => {
    spawns++;
    argvs.push(argv);
    const ee: any = new EventEmitter();
    ee.stdout = new EventEmitter();
    ee.stderr = new EventEmitter();
    ee.kill = () => {};
    ee.stdin = {
      on() {},
      end() {},
      write(t: string) {
        writes.push(t);
        const m = JSON.parse(t);
        if (m.type !== "user") return;
        const lines = [
          { type: "system", subtype: "init", session_id: SID },
          { type: "result", subtype: "success", result: `re:${m.message.content}`, session_id: SID, is_error: false },
        ];
        setImmediate(() => ee.stdout.emit("data", Buffer.from(lines.map((l) => JSON.stringify(l)).join("\n") + "\n")));
      },
    };
    return ee;
  }) as any;
  return { spawnFn, writes, argvs, spawns: () => spawns };
}

async function done(a: ClaudeAdapter, turnId: string, tries = 200): Promise<string> {
  for (let i = 0; i < tries; i++) {
    const s = (await a.getStatus({ chatId: "c", turnId, remoteSessionId: SID } as any)).state;
    if (s === "completed" || s === "failed" || s === "interrupted") return s;
    await new Promise((r) => setTimeout(r, 5));
  }
  return "timeout";
}

test("persist is on by default and off with TERMINUS_CLAUDE_PERSIST=0", () => {
  assert.equal(claudePersist({}), true);
  assert.equal(claudePersist({ TERMINUS_CLAUDE_PERSIST: "0" }), false);
});

test("stream-input argv has no prompt and reads stream-json", () => {
  const argv = claudeArgv({ prompt: "", streamInput: true, yolo: true });
  assert.deepEqual(argv.slice(0, 3), ["-p", "--input-format", "stream-json"]);
});

test("resident mode: two turns, one process, session kept", async () => {
  const prev = process.env.TERMINUS_CLAUDE_PERSIST;
  process.env.TERMINUS_CLAUDE_PERSIST = "1";
  const f = fakeResident();
  const a = new ClaudeAdapter("claude", f.spawnFn);
  try {
    const r1 = await a.startTurn({ chatId: "c", turnId: "t1", text: "one" } as any);
    assert.equal(r1.remoteSessionId, SID);
    assert.equal(await done(a, "t1"), "completed");
    await a.startTurn({ chatId: "c", turnId: "t2", text: "two", remoteSessionId: SID } as any);
    assert.equal(await done(a, "t2"), "completed");
    assert.equal(f.spawns(), 1);
    assert.equal(f.writes.length, 2);
    assert.equal(JSON.parse(f.writes[1]).message.content, "two");
    assert.ok(!f.argvs[0].includes("one"));
  } finally {
    await a.shutdown();
    if (prev === undefined) delete process.env.TERMINUS_CLAUDE_PERSIST; else process.env.TERMINUS_CLAUDE_PERSIST = prev;
  }
});

test("resident mode: a changed model respawns, same settings reuse", async () => {
  const prev = process.env.TERMINUS_CLAUDE_PERSIST;
  process.env.TERMINUS_CLAUDE_PERSIST = "1";
  const f = fakeResident();
  const a = new ClaudeAdapter("claude", f.spawnFn);
  try {
    await a.startTurn({ chatId: "c", turnId: "t1", text: "one", model: "sonnet" } as any);
    await done(a, "t1");
    await a.startTurn({ chatId: "c", turnId: "t2", text: "two", model: "opus", remoteSessionId: SID } as any);
    await done(a, "t2");
    assert.equal(f.spawns(), 2);
    assert.ok(f.argvs[1].includes("--resume"));
  } finally {
    await a.shutdown();
    if (prev === undefined) delete process.env.TERMINUS_CLAUDE_PERSIST; else process.env.TERMINUS_CLAUDE_PERSIST = prev;
  }
});

test("resident mode: stop asks the process to interrupt instead of killing it", async () => {
  const prev = process.env.TERMINUS_CLAUDE_PERSIST;
  process.env.TERMINUS_CLAUDE_PERSIST = "1";
  const writes: string[] = [];
  const spawnFn = ((_b: string, _a: string[]) => {
    const ee: any = new EventEmitter();
    ee.stdout = new EventEmitter();
    ee.stderr = new EventEmitter();
    ee.kill = () => { throw new Error("must not signal"); };
    ee.stdin = {
      on() {},
      end() {},
      write(t: string) {
        writes.push(t);
        const m = JSON.parse(t);
        const out = m.type === "user"
          ? [{ type: "system", subtype: "init", session_id: SID }]
          : [{ type: "result", subtype: "error_during_execution", result: "", session_id: SID, is_error: true }];
        setImmediate(() => ee.stdout.emit("data", Buffer.from(out.map((l) => JSON.stringify(l)).join("\n") + "\n")));
      },
    };
    return ee;
  }) as any;
  const a = new ClaudeAdapter("claude", spawnFn);
  try {
    await a.startTurn({ chatId: "c", turnId: "t1", text: "long" } as any);
    const r = await a.interruptTurn({ chatId: "c", turnId: "t1", remoteSessionId: SID } as any);
    assert.equal(r.interrupted, true);
    assert.equal(await done(a, "t1"), "interrupted");
    assert.equal(JSON.parse(writes[1]).request.subtype, "interrupt");
  } finally {
    await a.shutdown();
    if (prev === undefined) delete process.env.TERMINUS_CLAUDE_PERSIST; else process.env.TERMINUS_CLAUDE_PERSIST = prev;
  }
});

function steerHarness() {
  const writes: string[] = [];
  let spawns = 0;
  let out: EventEmitter | null = null;
  const spawnFn = ((_b: string, _a: string[]) => {
    spawns++;
    const ee: any = new EventEmitter();
    ee.stdout = new EventEmitter();
    ee.stderr = new EventEmitter();
    ee.kill = () => {};
    out = ee.stdout;
    ee.stdin = {
      on() {},
      end() {},
      write(t: string) {
        writes.push(t);
        // Only the first message opens the turn; the result is sent by the test.
        if (writes.length === 1) setImmediate(() => ee.stdout.emit("data", Buffer.from(JSON.stringify({ type: "system", subtype: "init", session_id: SID }) + "\n")));
      },
    };
    return ee;
  }) as any;
  const finish = (text: string) =>
    out!.emit("data", Buffer.from(JSON.stringify({ type: "result", subtype: "success", result: text, session_id: SID, is_error: false }) + "\n"));
  return { spawnFn, writes, spawns: () => spawns, finish };
}

test("steer: a message sent mid-turn folds into it and settles with it", async () => {
  const h = steerHarness();
  const a = new ClaudeAdapter("claude", h.spawnFn);
  try {
    await a.startTurn({ chatId: "c", turnId: "t1", text: "long job" } as any);
    const r = await a.startTurn({ chatId: "c", turnId: "t2", text: "also this", remoteSessionId: SID, steer: true } as any);
    assert.equal(r.steered, true);
    assert.equal(h.spawns(), 1);
    assert.equal(JSON.parse(h.writes[1]).message.content, "also this");
    assert.equal(await done(a, "t2", 20), "timeout"); // still running with its parent
    const seen: string[] = [];
    await a.stream({ chatId: "c", turnId: "t2", remoteSessionId: SID }, (e) => { if (e.type === "turn.completed") seen.push(`${e.turnId}:${e.text}`); });
    h.finish("both done");
    assert.equal(await done(a, "t1"), "completed");
    assert.equal(await done(a, "t2"), "completed");
    assert.deepEqual(seen, ["t2:both done"]);
  } finally {
    await a.shutdown();
  }
});

test("steer: not asked for, or nothing running, takes the normal path", async () => {
  const h = steerHarness();
  const a = new ClaudeAdapter("claude", h.spawnFn);
  try {
    const none = await a.startTurn({ chatId: "c", turnId: "t1", text: "first", steer: true } as any);
    assert.equal(none.steered, undefined); // nothing was running
    const queued = await a.startTurn({ chatId: "c", turnId: "t2", text: "second", remoteSessionId: SID } as any);
    assert.equal(queued.steered, undefined); // steer not requested
    assert.equal((await a.getStatus({ chatId: "c", turnId: "t2", remoteSessionId: SID } as any)).state, "waiting");
    await assert.rejects(a.startTurn({ chatId: "c2", turnId: "t3", text: "x", steer: true, steerRequired: true } as any));
  } finally {
    await a.shutdown();
  }
});

test("steerQueued: a waiting message is promoted into the running turn", async () => {
  const h = steerHarness();
  const a = new ClaudeAdapter("claude", h.spawnFn);
  try {
    await a.startTurn({ chatId: "c", turnId: "t1", text: "long job" } as any);
    await a.startTurn({ chatId: "c", turnId: "t2", text: "send now", remoteSessionId: SID } as any);
    assert.equal(await a.steerQueued("c", "t2"), true);
    assert.equal(JSON.parse(h.writes[1]).message.content, "send now");
    h.finish("merged");
    assert.equal(await done(a, "t1"), "completed");
    assert.equal(await done(a, "t2"), "completed");
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(h.spawns(), 1);
    assert.equal(h.writes.length, 2); // the promoted message never starts a turn of its own
    assert.equal(await a.steerQueued("c", "t2"), true); // already folded: nothing left waiting
  } finally {
    await a.shutdown();
  }
});

test("two first sends that race share one session", async () => {
  const f = fakeResident();
  const a = new ClaudeAdapter("claude", f.spawnFn);
  try {
    const [r1, r2] = await Promise.all([
      a.startTurn({ chatId: "c", turnId: "t1", text: "a" } as any),
      a.startTurn({ chatId: "c", turnId: "t2", text: "b" } as any),
    ]);
    assert.equal(r1.remoteSessionId, SID);
    assert.equal(r2.remoteSessionId, SID);
    assert.equal(await done(a, "t1"), "completed");
    assert.equal(await done(a, "t2"), "completed");
    assert.equal(f.spawns(), 1); // the second one joined the resident process
  } finally {
    await a.shutdown();
  }
});

test("instruction stamp moves when a skill file changes", async () => {
  const { mkdtempSync, mkdirSync, writeFileSync, utimesSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const { instructionStamp } = await import("./claude.js");
  const home = mkdtempSync(`${tmpdir()}/claude-stamp-`);
  const saved = process.env.HOME;
  try {
    process.env.HOME = home;
    mkdirSync(`${home}/.claude/skills/x`, { recursive: true });
    writeFileSync(`${home}/.claude/skills/x/SKILL.md`, "a");
    utimesSync(`${home}/.claude/skills/x/SKILL.md`, 1000, 1000);
    const before = instructionStamp();
    utimesSync(`${home}/.claude/skills/x/SKILL.md`, 2000, 2000);
    assert.notEqual(instructionStamp(), before);
  } finally {
    process.env.HOME = saved;
    rmSync(home, { recursive: true, force: true });
  }
});

test("steerQueued: several waiting, the one at the front can go and the rest keep their order", async () => {
  const h = steerHarness();
  const a = new ClaudeAdapter("claude", h.spawnFn);
  try {
    await a.startTurn({ chatId: "c", turnId: "t1", text: "long job" } as any);
    await a.startTurn({ chatId: "c", turnId: "t2", text: "first waiting", remoteSessionId: SID } as any);
    await a.startTurn({ chatId: "c", turnId: "t3", text: "second waiting", remoteSessionId: SID } as any);
    assert.equal(await a.steerQueued("c", "t3"), false); // t2 is ahead of it
    assert.equal(await a.steerQueued("c", "t2"), true); // t3 behind it does not block
    assert.equal(JSON.parse(h.writes[1]).message.content, "first waiting");
    assert.equal(await a.steerQueued("c", "t3"), true); // nothing ahead any more
    assert.equal(JSON.parse(h.writes[2]).message.content, "second waiting");
  } finally {
    await a.shutdown();
  }
});
