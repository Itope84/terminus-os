import test from "node:test";
import assert from "node:assert/strict";
import { replySettleOk } from "./reply-gate.js";

test("a session still active never settles, finished message or not", () => {
  assert.equal(replySettleOk({ waitReturned: true, working: true, stableRounds: 99, elapsedMs: 9e6, terminal: true }), false);
  assert.equal(replySettleOk({ waitReturned: true, working: true, stableRounds: 99, elapsedMs: 9e6, terminal: false }), false);
});
test("a wait that did not return never settles", () => {
  assert.equal(replySettleOk({ waitReturned: false, working: false, stableRounds: 9, elapsedMs: 9e6, terminal: true }), false);
});
test("confirmed idle settles a finished message at once, an unfinished one after a still round", () => {
  assert.equal(replySettleOk({ waitReturned: true, working: false, stableRounds: 0, elapsedMs: 5_000, terminal: true }), true);
  assert.equal(replySettleOk({ waitReturned: true, working: false, stableRounds: 0, elapsedMs: 5_000, terminal: false }), false);
  assert.equal(replySettleOk({ waitReturned: true, working: false, stableRounds: 1, elapsedMs: 5_000, terminal: false }), true);
});
test("unknown activity needs five frozen rounds past the first minute", () => {
  assert.equal(replySettleOk({ waitReturned: true, working: null, stableRounds: 4, elapsedMs: 90_000, terminal: true }), false);
  assert.equal(replySettleOk({ waitReturned: true, working: null, stableRounds: 5, elapsedMs: 30_000, terminal: true }), false);
  assert.equal(replySettleOk({ waitReturned: true, working: null, stableRounds: 5, elapsedMs: 90_000, terminal: true }), true);
  assert.equal(replySettleOk({ waitReturned: true, working: null, stableRounds: 5, elapsedMs: 90_000, terminal: false }), true);
});
