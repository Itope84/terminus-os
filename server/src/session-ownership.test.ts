import test from "node:test";
import assert from "node:assert/strict";
import { homedir } from "node:os";
import { join } from "node:path";
import { ownedFile } from "./session-ownership.js";

test("under node --test the default owned file is never the real one", () => {
  assert.ok(process.env.NODE_TEST_CONTEXT, "runs inside node --test");
  assert.notEqual(ownedFile("codex"), join(homedir(), ".config", "terminus", "codex-owned.json"));
});

test("an explicit home is honoured", () => {
  assert.equal(ownedFile("claude", "/x"), "/x/.config/terminus/claude-owned.json");
});
