import test from "node:test";
import assert from "node:assert/strict";
import { getAdapter, getDescriptor } from "./registry.js";
import { steerQueuedTurn } from "../turn-runtime.js";

test("opencode claims steer: its queue lives app-side (hold + local release), so Send-now links apply", () => {
  assert.equal(getDescriptor("opencode").capabilities.steer, true);
});

test("steerQueuedTurn changes nothing for a backend without a fold RPC", async () => {
  assert.equal(await steerQueuedTurn(getAdapter("opencode"), "chat-1", "t-1"), false);
});
