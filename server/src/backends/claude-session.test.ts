import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { claudeCompactOutcome, claudeForkTranscript, claudeInitSessionId, claudeInitSlashCommands, claudeSessionTitle, readClaudeTitles, setClaudeTitle } from "./claude-session.js";
import { markOwned, readOwned } from "../session-ownership.js";
import { liveBin } from "./claude.js";

const A = "123e4567-e89b-12d3-a456-426614174000";
const B = "223e4567-e89b-12d3-a456-426614174000";

test("fork rewrites the session id, keeps everything else, drops a torn last line", () => {
  const raw = [
    JSON.stringify({ type: "user", sessionId: A, uuid: "u1", message: { content: "hi" } }),
    "not json at all",
    JSON.stringify({ type: "assistant", sessionId: A, uuid: "u2", parentUuid: "u1" }),
    JSON.stringify({ type: "x", sessionId: "other", uuid: "u3" }),
    '{"type":"user","sessionId":"' + A + '","uu',
  ].join("\n");
  const out = claudeForkTranscript(raw, A, B).split("\n");
  assert.equal(out.length, 5); // 4 lines + trailing newline
  assert.equal(JSON.parse(out[0]).sessionId, B);
  assert.equal(JSON.parse(out[0]).message.content, "hi");
  assert.equal(out[1], "not json at all");
  assert.equal(JSON.parse(out[2]).parentUuid, "u1");
  assert.equal(JSON.parse(out[3]).sessionId, "other");
  assert.equal(out[4], "");
});

test("title: a rename beats an AI title beats a summary; unknown shapes give nothing", () => {
  const l = (o: unknown) => JSON.stringify(o);
  assert.equal(claudeSessionTitle([l({ type: "summary", summary: "S" }), l({ type: "ai-title", aiTitle: "AI" })].join("\n")), "AI");
  assert.equal(claudeSessionTitle([l({ type: "ai-title", aiTitle: "AI" }), l({ type: "custom-title", customTitle: "Mine" })].join("\n")), "Mine");
  assert.equal(claudeSessionTitle([l({ type: "summary", summary: "Only summary" })].join("\n")), "Only summary");
  assert.equal(claudeSessionTitle([l({ type: "user", message: "title" }), "garbage title"].join("\n")), "");
});

test("phone-set titles are stored, replaced, and cleared", () => {
  const file = join(mkdtempSync(join(tmpdir(), "ct-")), "sub", "titles.json");
  assert.deepEqual(readClaudeTitles(file), {});
  setClaudeTitle(A, "  First   name ", file);
  setClaudeTitle(B, "Other", file);
  assert.deepEqual(readClaudeTitles(file), { [A]: "First name", [B]: "Other" });
  setClaudeTitle(A, "", file);
  assert.deepEqual(readClaudeTitles(file), { [B]: "Other" });
});

test("only sessions Terminus marked are owned; marking is idempotent", () => {
  const file = join(mkdtempSync(join(tmpdir(), "co-")), "sub", "owned.json");
  assert.equal(readOwned("claude", file).size, 0);
  markOwned("claude", A, file);
  markOwned("claude", A, file);
  markOwned("claude", B, file);
  assert.deepEqual([...readOwned("claude", file)], [A, B]);
});

test("compact counts only with a clean exit, no error result and Claude's own marker", () => {
  const marker = JSON.stringify({ type: "system", subtype: "compact_boundary" });
  const ok = JSON.stringify({ type: "result", is_error: false });
  const bad = JSON.stringify({ type: "result", is_error: true });
  assert.equal(claudeCompactOutcome([marker, ok].join("\n"), 0).ok, true);
  assert.equal(claudeCompactOutcome([marker, bad].join("\n"), 0).ok, false);
  assert.equal(claudeCompactOutcome([marker, ok].join("\n"), 1).ok, false);
  assert.equal(claudeCompactOutcome(ok, 0).ok, false); // answered as plain text: nothing compacted
  assert.equal(claudeCompactOutcome("", 0).ok, false);
});

test("compact with too little history reports Claude's own note, not a false success", () => {
  const note = JSON.stringify({ type: "result", is_error: false, result: "Not enough messages to compact." });
  const r = claudeCompactOutcome(note, 0);
  assert.equal(r.ok, false);
  assert.equal(r.reason, "Not enough messages to compact.");
});

test("init line: command names lose their slash, odd ones drop, the first init gives the session id", () => {
  assert.deepEqual(
    claudeInitSlashCommands({ slash_commands: ["/compact", "context", "bad name", 7, "compact", "plugin:run"] }),
    ["compact", "context", "plugin:run"],
  );
  assert.deepEqual(claudeInitSlashCommands({}), []);
  const out = [JSON.stringify({ type: "assistant" }), JSON.stringify({ type: "system", subtype: "init", session_id: A })].join("\n");
  assert.equal(claudeInitSessionId(out), A);
  assert.equal(claudeInitSessionId("nothing here"), "");
});

test("liveBin keeps a path that exists and re-resolves a missing one", () => {
  const dir = mkdtempSync(join(tmpdir(), "lb-"));
  const real = join(dir, "claude");
  writeFileSync(real, "");
  assert.equal(liveBin(real), real);
  const gone = join(dir, "gone", "claude");
  assert.notEqual(liveBin(gone), ""); // never empty: falls back to the PATH lookup or the remembered value
});
