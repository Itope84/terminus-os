/** Whole-tree termination tests (live-validated pattern).
 *  Spawns real `sleep` processes only — no providers, no network. */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import {
  childPids,
  terminateTree,
  treePids,
} from "../dist/tree-kill.js";

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

describe("tree-kill", () => {
  it("invalid pids resolve quietly", async () => {
    assert.deepEqual(await terminateTree(undefined), { signalled: [], allGone: true });
    assert.deepEqual(await terminateTree(-1), { signalled: [], allGone: true });
    assert.deepEqual(await childPids(-5), []);
  });

  it("terminates a parent plus its children, none survive", async () => {
    // sh spawns two sleeps; killing sh alone would orphan them.
    const parent = spawn("sh", ["-c", "sleep 60 & sleep 60 & wait"], { stdio: "ignore" });
    await new Promise((r) => setTimeout(r, 300));
    assert.equal(alive(parent.pid), true);
    const tree = await treePids(parent.pid);
    assert.ok(tree.length >= 2, `want sleep children, got ${JSON.stringify(tree)}`);
    const res = await terminateTree(parent.pid, { gracefulMs: 300, forceMs: 300 });
    assert.ok(res.signalled.includes(parent.pid));
    assert.equal(res.allGone, true);
    assert.equal(alive(parent.pid), false);
    for (const p of tree) assert.equal(alive(p), false);
  });

  it("already-exited pid is a quiet no-op", async () => {
    const quick = spawn("true", [], { stdio: "ignore" });
    await new Promise((r) => quick.on("exit", r));
    const res = await terminateTree(quick.pid, { gracefulMs: 100, forceMs: 100 });
    assert.equal(res.allGone, true);
  });
});
