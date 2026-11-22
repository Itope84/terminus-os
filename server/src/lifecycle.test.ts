import test from "node:test";
import assert from "node:assert/strict";
import { idleProfileKeys, isStalled, shutdownTargets, watchStall } from "./lifecycle.js";

test("a stall needs the full quiet period, and 0 disables it", () => {
  assert.equal(isStalled(1000, 500, 400), true);
  assert.equal(isStalled(1000, 700, 400), false);
  assert.equal(isStalled(1e12, 0, 0), false);
});

test("watchStall fires once when the clock goes stale, and stops on request", async () => {
  let fired = 0;
  const stop = watchStall(() => 0, () => { fired++; }, 20);
  await new Promise((r) => setTimeout(r, 1300));
  assert.equal(fired, 1);
  stop();
  let fresh = 0;
  const stop2 = watchStall(() => Date.now(), () => { fresh++; }, 5_000);
  await new Promise((r) => setTimeout(r, 50));
  stop2();
  assert.equal(fresh, 0);
});

test("idle profiles expire by mode-specific timeout, while active operations stay pinned", () => {
  const now = 10_000_000;
  const idle = 30 * 60_000;
  const keys = idleProfileKeys([
    ["a", { headed: false, usedAt: now - idle - 1, activeOperations: 0 }],
    ["b", { headed: false, usedAt: now - 1000, activeOperations: 0 }],
    ["c", { headed: true, usedAt: 0, activeOperations: 0 }],
    ["d", { headed: false, usedAt: 0, activeOperations: 0 }],
  ], now, idle);
  assert.deepEqual(keys, ["a", "c", "d"]);
  assert.deepEqual(idleProfileKeys([["a", { headed: false, usedAt: 0, activeOperations: 0 }]], now, 0), []);
  assert.deepEqual(idleProfileKeys([
    ["e", { headed: true, usedAt: now - idle - 1, activeOperations: 0 }],
  ], now, idle, 60 * 60_000), []);
  assert.deepEqual(idleProfileKeys([
    ["e", { headed: true, usedAt: now - 61 * 60_000, activeOperations: 0 }],
  ], now, idle, 60 * 60_000), ["e"]);
  assert.deepEqual(idleProfileKeys([
    ["busy", { headed: false, usedAt: 0, activeOperations: 1 }],
  ], now, idle), []);
});

test("shutdown targets skip opencode and already-settled turns", () => {
  const pending: Array<[string, Array<{ turnId: string }>]> = [
    ["oc", [{ turnId: "t1" }]],
    ["cl", [{ turnId: "t2" }, { turnId: "t3" }]],
    ["cx", [{ turnId: "t4" }]],
  ];
  const backend = (c: string) => (c === "oc" ? "opencode" : c === "cl" ? "claude" : c === "cx" ? "codex" : null);
  const out = shutdownTargets(pending, backend, (_c, t) => t === "t3");
  assert.deepEqual(out, [{ chatId: "cl", turnId: "t2" }, { chatId: "cx", turnId: "t4" }]);
});
