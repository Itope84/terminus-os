import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dir = mkdtempSync(join(tmpdir(), "ab-"));
process.env.AGENTBOOK_HOME = dir;
const ab = await import("./agentbook.js");
const { claudeArgv, claudeEnv, claudeTranscript } = await import("./backends/claude.js");

test("the book is handed back once after compaction, with its contents", () => {
  mkdirSync(join(dir, "claude"), { recursive: true });
  writeFileSync(join(dir, "claude", "c1.md"), "## goal\n- ship steer\n");
  assert.equal(ab.agentbookPrefix("claude", "c1", 1000, 100_000), "");
  ab.noteCompacted("c1");
  const p = ab.agentbookPrefix("claude", "c1", 1000, 100_000);
  assert.match(p, /^<agentbook>\nContext was just compacted\. Your agentbook:\n## goal\n- ship steer/);
  assert.equal(ab.agentbookPrefix("claude", "c1", 1000, 100_000), "");
});

test("near-ceiling nudge fires once per compaction cycle", () => {
  assert.match(ab.agentbookPrefix("claude", "c2", 85_000, 100_000), /Compaction is near/);
  assert.equal(ab.agentbookPrefix("claude", "c2", 90_000, 100_000), "");
  ab.noteCompacted("c2");
  assert.match(ab.agentbookPrefix("claude", "c2", 85_000, 100_000), /just compacted[\s\S]*Compaction is near/);
  assert.equal(ab.agentbookPrefix("claude", "c3", 85_000, 0), "", "no ceiling set: no near nudge");
});

test("stale nudge needs many turns and real growth, and a write resets it", () => {
  for (let i = 0; i < 10; i++) assert.equal(ab.agentbookPrefix("claude", "c4", 1000, 0), "");
  assert.equal(ab.agentbookPrefix("claude", "c4", 1000, 0), "", "turns alone are not enough");
  assert.match(ab.agentbookPrefix("claude", "c4", 20_000, 0), /stale/);
  assert.equal(ab.agentbookPrefix("claude", "c4", 40_000, 0), "", "once");
});

test("the hidden block never shows in history and the env pins the chat", () => {
  const user = JSON.stringify({ type: "user", message: { role: "user", content: "<agentbook>\nnudge\n</agentbook>\n\nhello" } });
  assert.deepEqual(claudeTranscript(user).map((m) => m.text), ["hello"]);
  assert.equal(claudeEnv(0, "c9").TERMINUS_AGENTBOOK, "claude/c9");
  assert.equal(claudeEnv(0).TERMINUS_AGENTBOOK, undefined);
  const argv = claudeArgv({ prompt: "x", systemPrompt: "sp" });
  assert.match(argv[argv.indexOf("--append-system-prompt") + 1], /^sp\n\nAgentbook:/);
});

test("forgetBook removes the file", () => {
  const f = join(dir, "claude", "c1.md");
  assert.ok(existsSync(f));
  ab.forgetBook("claude", "c1");
  assert.ok(!existsSync(f));
});

test("books untouched for 60 days are swept, recent ones stay", () => {
  mkdirSync(join(dir, "claude"), { recursive: true });
  const old = join(dir, "claude", "old.md"), fresh = join(dir, "claude", "fresh.md");
  writeFileSync(old, "## goal\n"); writeFileSync(fresh, "## goal\n");
  const t = (Date.now() - 61 * 86_400_000) / 1000;
  utimesSync(old, t, t);
  assert.equal(ab.sweepOldBooks(), 1);
  assert.ok(!existsSync(old) && existsSync(fresh));
});
