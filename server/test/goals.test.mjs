/** Goals advisory: the check asks the chat's own model for one verdict word
 *  over objective + request + latest reply only, never history, session id or
 *  tools. Run: npm test   (builds first, then node --test)
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseVerdict, judgePrompt, excerptAnswer, continuationPrompt, accountTokens, goalReplyText, loopKillSwitch, DEFAULT_MAX_TURNS } from "../dist/goals.js";
import { askArgv } from "../dist/quick-ask.js";

describe("verdict parse", () => {
  it("accepts the three exact tokens", () => {
    assert.equal(parseVerdict("done"), "done");
    assert.equal(parseVerdict("stuck"), "stuck");
    assert.equal(parseVerdict("keep-going"), "keep-going");
  });

  it("reads the last line, so a model that explains first still counts", () => {
    assert.equal(parseVerdict("The tests pass and the APK is staged.\n\n**done**"), "done");
    assert.equal(parseVerdict("Needs the API key.\nstuck."), "stuck");
  });

  it("anything else is a failed check (null), never a guess", () => {
    assert.equal(parseVerdict("Done! The link is ready."), null);
    assert.equal(parseVerdict(""), null);
    assert.equal(parseVerdict("almost done"), null);
  });

  it("normalizes case on exact tokens", () => {
    assert.equal(parseVerdict("DONE"), "done");
    assert.equal(parseVerdict("  keep-going\n"), "keep-going");
  });
});

describe("judgePrompt", () => {
  it("carries objective, user request and reply with a strict instruction", () => {
    const p = judgePrompt("stage the link", "link the render", "render done, link below");
    assert.match(p, /stage the link/);
    assert.match(p, /link the render/);
    assert.match(p, /render done/);
    assert.match(p, /exactly one word/);
    assert.match(p, /narrowed to a subset/);
  });

  it("caps input lengths (head + close, where the report sits)", () => {
    const p = judgePrompt("x".repeat(900), "u".repeat(3000), "y".repeat(4000));
    assert.ok(p.length < 11_000);
    const long = judgePrompt("obj", "ask", "HEAD-" + "y".repeat(20_000) + "-TAIL");
    assert.match(long, /HEAD-/);
    assert.match(long, /-TAIL/);
    const longAsk = judgePrompt("obj", "Q-" + "u".repeat(5000) + "-END", "reply");
    assert.match(longAsk, /Q-/);
    assert.ok(!longAsk.includes("END"));
  });
});

describe("continuationPrompt", () => {
  it("carries the objective as escaped user data and the report rule", () => {
    const p = continuationPrompt("fix <the> build & ship", 1, 1);
    assert.match(p, /<objective>\nfix &lt;the&gt; build &amp; ship\n<\/objective>/);
    assert.match(p, /Auto-continuations used: 1 of 1\./);
    assert.match(p, /End every turn with a clear, factual statement of what is done, what was verified, and what remains/);
    assert.ok(!p.includes("<the>"));
  });

  it("states the token budget only when one is set", () => {
    const p = continuationPrompt("ship it", 2, 5, { used: 1_200, budget: 50_000 });
    assert.match(p, /Token budget: ~1200 used of 50000\./);
    assert.ok(!continuationPrompt("ship it", 1, 1).includes("Token budget"));
  });
});

describe("accountTokens", () => {
  it("adds context growth and banks a compacted segment", () => {
    assert.deepEqual(accountTokens({ used: 0, snapshot: 0 }, 12_000), { used: 12_000, snapshot: 12_000 });
    assert.deepEqual(accountTokens({ used: 12_000, snapshot: 12_000 }, 15_000), { used: 15_000, snapshot: 15_000 });
    // Compaction: the finished segment is already in the total (used holds
    // its growth), so it stays as is and counting resumes from the smaller
    // snapshot. The total never goes down and never double-counts.
    assert.deepEqual(accountTokens({ used: 15_000, snapshot: 15_000 }, 4_000), { used: 15_000, snapshot: 4_000 });
    assert.deepEqual(accountTokens({ used: 15_000, snapshot: 4_000 }, 6_000), { used: 17_000, snapshot: 6_000 });
  });

  it("ignores a missing or non-positive snapshot", () => {
    assert.deepEqual(accountTokens({ used: 100, snapshot: 50 }, 0), { used: 100, snapshot: 50 });
    assert.deepEqual(accountTokens({ used: 100, snapshot: 50 }, -5), { used: 100, snapshot: 50 });
  });
});

describe("loop teeth", () => {
  it("kill switch defaults off and honors the env flag", () => {
    delete process.env.GOALS_LOOP_OFF;
    assert.equal(loopKillSwitch(), false);
    process.env.GOALS_LOOP_OFF = "1";
    assert.equal(loopKillSwitch(), true);
    delete process.env.GOALS_LOOP_OFF;
  });

  it("continuation budget defaults to one nudge", () => {
    assert.equal(DEFAULT_MAX_TURNS, 1);
  });
});

describe("goalReplyText", () => {
  it("reads the wire shape: type assistant, text parts", () => {
    const data = [
      { id: "m1", type: "user", content: [{ type: "text", text: "hi" }] },
      { id: "m2", type: "assistant", content: [
        { type: "reasoning", text: "" },
        { type: "text", text: "keep-going" },
      ] },
    ];
    assert.equal(goalReplyText(data), "keep-going");
  });

  it("ignores reasoning-only and empty lists", () => {
    assert.equal(goalReplyText([
      { type: "assistant", content: [{ type: "reasoning", text: "" }] },
    ]), "");
    assert.equal(goalReplyText([]), "");
    assert.equal(goalReplyText(null), "");
  });

  it("still accepts legacy role shape", () => {
    assert.equal(goalReplyText([
      { role: "assistant", content: [{ type: "text", text: "done" }] },
    ]), "done");
  });
});

describe("quick-ask argv", () => {
  it("claude runs print mode with no tools, nothing saved, the chat's model and effort", () => {
    const a = askArgv({ backend: "claude", model: "opus", effort: "high" });
    assert.equal(a.bin, "claude");
    for (const f of ["-p", "--no-session-persistence", "--strict-mcp-config"]) assert.ok(a.args.includes(f));
    assert.equal(a.args[a.args.indexOf("--tools") + 1], "");
    assert.equal(a.args[a.args.indexOf("--model") + 1], "opus");
    assert.equal(a.args[a.args.indexOf("--effort") + 1], "high");
  });
  it("codex is ephemeral and read-only, with the chat's model", () => {
    const a = askArgv({ backend: "codex", model: "gpt-5.5" }, "/tmp/o.txt");
    for (const f of ["exec", "--ephemeral", "--skip-git-repo-check"]) assert.ok(a.args.includes(f));
    assert.equal(a.args[a.args.indexOf("-s") + 1], "read-only");
    assert.equal(a.args[a.args.indexOf("-m") + 1], "gpt-5.5");
  });
  it("antigravity uses read-only plan mode; opencode is not a CLI path; bad effort dropped", () => {
    assert.deepEqual(askArgv({ backend: "agy" }).args.slice(0, 2), ["--mode", "plan"]);
    assert.equal(askArgv({ backend: "opencode" }), null);
    assert.ok(!askArgv({ backend: "claude", effort: "max; rm" }).args.includes("--effort"));
  });
});
