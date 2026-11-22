import test from "node:test";
import assert from "node:assert/strict";
import { lostTurns, type SyncState } from "./sync-plan.js";

const now = 1_000_000;
const old = now - 60_000;
const st = (o: Record<string, SyncState>) => new Map(Object.entries(o));

test("a turn the adapter no longer knows is lost once past the grace period", () => {
  assert.deepEqual(lostTurns([{ turnId: "a", at: old }], st({ a: "unknown" }), now), ["a"]);
});
test("a freshly accepted turn is never lost", () => {
  assert.deepEqual(lostTurns([{ turnId: "a", at: now - 5_000 }], st({ a: "unknown" }), now), []);
});
test("working and waiting turns are alive", () => {
  assert.deepEqual(lostTurns([{ turnId: "a", at: old }, { turnId: "b", at: old }], st({ a: "working", b: "waiting" }), now), []);
});
test("finished-but-still-pending turns are lost", () => {
  assert.deepEqual(lostTurns([{ turnId: "a", at: old }, { turnId: "b", at: old }], st({ a: "completed", b: "failed" }), now), ["a", "b"]);
});
test("an unreadable status is never treated as lost", () => {
  assert.deepEqual(lostTurns([{ turnId: "a", at: old }], st({}), now), []);
});
