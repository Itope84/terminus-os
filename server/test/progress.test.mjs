/** Tests for GET /api/progress step derivation (pure, no servers).
 *  Run: npm test   (builds first, then node --test)
 *
 *  Contract: reply text is NEVER a step row (it travels via deltas/`done`);
 *  only reasoning snippets (truncated) and tool rows appear.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildProgressSteps, takeFreshSteps, toolStepOf } from "../dist/opencode-proxy.js";

const user = (at) => ({ type: "user", time: { created: at } });
const asst = (at, content) => ({ type: "assistant", time: { created: at }, content });

describe("buildProgressSteps", () => {
  it("returns [] with no messages", () => {
    assert.deepEqual(buildProgressSteps([]), []);
  });

  it("never emits reply text as rows", () => {
    const steps = buildProgressSteps([user(1), asst(2, [{ type: "text", text: "x".repeat(200) }])]);
    assert.deepEqual(steps, []);
  });

  it("truncates reasoning to 160 chars with no detail", () => {
    const steps = buildProgressSteps([user(1), asst(2, [{ type: "reasoning", text: "y".repeat(300) }])]);
    assert.equal(steps.length, 1);
    assert.equal(steps[0].kind, "think");
    assert.equal(steps[0].label, "y".repeat(160));
    assert.equal(steps[0].detail, undefined);
    assert.equal(steps[0].at, 2);
  });

  it("maps reasoning to think and tool name + input summary to tool", () => {
    const steps = buildProgressSteps([
      user(1),
      asst(2, [
        { type: "reasoning", text: "let me think about this" },
        { type: "tool", name: "read", state: { status: "running", input: { path: "/tmp/x", foo: 1 }, metadata: {} } },
        { type: "tool", name: "bash", state: { status: "streaming", input: "echo hi" } },
      ]),
    ]);
    assert.equal(steps.length, 3);
    assert.equal(steps[0].kind, "think");
    assert.equal(steps[0].label, "let me think about this");
    assert.equal(steps[1].kind, "tool");
    assert.equal(steps[1].label, "read");
    assert.match(steps[1].detail, /path=\/tmp\/x/);
    assert.equal(steps[2].label, "bash");
    assert.equal(steps[2].detail, "echo hi");
  });

  it("keeps only the current turn (after the last user message)", () => {
    const steps = buildProgressSteps([
      user(1),
      asst(2, [{ type: "reasoning", text: "old thought" }]),
      user(3),
      asst(4, [{ type: "reasoning", text: "new thought" }]),
    ]);
    assert.deepEqual(
      steps.map((s) => s.label),
      ["new thought"],
    );
  });

  it("orders desc input chronologically, most recent last", () => {
    const steps = buildProgressSteps([
      asst(4, [{ type: "reasoning", text: "second" }]),
      user(1),
      asst(2, [{ type: "reasoning", text: "first" }]),
    ]);
    assert.deepEqual(
      steps.map((s) => s.label),
      ["first", "second"],
    );
  });

  it("skips empty reasoning and unnamed tools", () => {
    const steps = buildProgressSteps([
      user(1),
      asst(2, [{ type: "reasoning", text: "   " }, { type: "tool" }, { type: "text", text: "kept" }]),
    ]);
    assert.deepEqual(steps, []);
  });

  it("caps at 20, keeping the most recent", () => {
    const parts = Array.from({ length: 25 }, (_, i) => ({ type: "reasoning", text: `m${i}` }));
    const steps = buildProgressSteps([user(1), asst(2, parts)]);
    assert.equal(steps.length, 20);
    assert.equal(steps[0].label, "m5");
    assert.equal(steps[19].label, "m24");
  });
});

describe("takeFreshSteps", () => {
  const mk = (labels) => labels.map((label, i) => ({ kind: "think", label, at: i }));

  it("sends everything on first sight, then only growth", () => {
    const a = takeFreshSteps("c-conn", "s-conn", mk(["one", "two"]));
    assert.deepEqual(a.fresh.map((s) => s.label), ["one", "two"]);
    assert.equal(a.newTurn, false);
    // Same list again (e.g. a reconnect re-fetch): nothing fresh.
    const b = takeFreshSteps("c-conn", "s-conn", mk(["one", "two"]));
    assert.deepEqual(b.fresh, []);
    assert.equal(b.newTurn, false);
    // Genuine growth: only the tail.
    const c = takeFreshSteps("c-conn", "s-conn", mk(["one", "two", "three"]));
    assert.deepEqual(c.fresh.map((s) => s.label), ["three"]);
  });

  it("a list of wholly unseen steps is a new turn: its steps are sent", () => {
    takeFreshSteps("c-reset", "s-reset", mk(["a", "b", "c"]));
    const r = takeFreshSteps("c-reset", "s-reset", mk(["x"]));
    assert.deepEqual(r.fresh.map((s) => s.label), ["x"]);
    assert.equal(r.newTurn, true);
    const n = takeFreshSteps("c-reset", "s-reset", mk(["x", "y"]));
    assert.deepEqual(n.fresh.map((s) => s.label), ["y"]);
    assert.equal(n.newTurn, false);
  });

  it("keeps sending past the 20-step window (the old cursor froze here)", () => {
    const window = (from, to) => Array.from({ length: to - from + 1 }, (_, i) => ({ kind: "tool", label: `s${from + i}`, at: from + i }));
    takeFreshSteps("c-long", "s-long", window(0, 19));
    const slid = takeFreshSteps("c-long", "s-long", window(1, 20));
    assert.deepEqual(slid.fresh.map((s) => s.label), ["s20"]);
    const later = takeFreshSteps("c-long", "s-long", window(2, 21));
    assert.deepEqual(later.fresh.map((s) => s.label), ["s21"]);
  });

  it("marks are per chat+session", () => {
    takeFreshSteps("c-iso", "s-one", mk(["a", "b"]));
    const otherSession = takeFreshSteps("c-iso", "s-two", mk(["a", "b"]));
    assert.equal(otherSession.fresh.length, 2);
    const otherChat = takeFreshSteps("c-other", "s-one", mk(["a", "b"]));
    assert.equal(otherChat.fresh.length, 2);
    const same = takeFreshSteps("c-iso", "s-one", mk(["a", "b"]));
    assert.deepEqual(same.fresh, []);
  });
});

describe("toolStepOf", () => {
  it("called carries the input summary, not bare event words", () => {
    const s = toolStepOf("session.tool.called", { input: { command: "grep -n foo bar" } });
    assert.equal(s.label, "grep -n foo bar");
    assert.match(s.detail, /command=/);
  });

  it("input.started carries the tool name", () => {
    const s = toolStepOf("session.tool.input.started", { name: "bash", id: "t1" });
    assert.equal(s.label, "bash");
  });

  it("success carries the output excerpt", () => {
    const s = toolStepOf("session.tool.success", { content: [{ type: "text", text: "  hello world  " }] });
    assert.equal(s.detail, "hello world");
  });

  it("failed carries the error message", () => {
    const s = toolStepOf("session.tool.failed", { error: { message: "boom x" } });
    assert.equal(s.label, "tool failed");
    assert.equal(s.detail, "boom x");
  });

  it("step ended carries finish reason", () => {
    const s = toolStepOf("session.step.ended", { finish: "stop", files: ["a.ts", "b.ts"] });
    assert.match(s.detail, /stop/);
  });
});

describe("stepFiles (mutating tool file targets)", () => {
  it("opencode edit exposes filePath, read stays silent", async () => {
    const { stepFiles } = await import("../dist/opencode-proxy.js");
    assert.deepEqual(stepFiles("edit", { filePath: "/w/a.ts", oldString: "x" }), ["/w/a.ts"]);
    assert.deepEqual(stepFiles("read", { filePath: "/w/a.ts" }), []);
    assert.deepEqual(stepFiles("bash", { command: "ls /w" }), []);
  });
  it("claude file_path and codex bare path string", async () => {
    const { stepFiles } = await import("../dist/opencode-proxy.js");
    assert.deepEqual(stepFiles("Edit", { file_path: "/w/b.ts" }), ["/w/b.ts"]);
    assert.deepEqual(stepFiles("edit", "/w/c.ts"), ["/w/c.ts"]);
    assert.deepEqual(stepFiles("shell", "ls /w"), []);
  });
  it("unknown tool names stay silent, step.ended passes files through", async () => {
    const { stepFiles, toolStepOf } = await import("../dist/opencode-proxy.js");
    assert.deepEqual(stepFiles("mcp mystery", { filePath: "/w/a.ts" }), []);
    const s = toolStepOf("session.step.ended", { finish: "stop", files: ["a.ts", "b.ts"] });
    assert.deepEqual(s.files, ["a.ts", "b.ts"]);
    assert.match(s.detail, /stop/);
  });
});
