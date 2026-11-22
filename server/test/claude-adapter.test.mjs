/** Claude CLI adapter tests (multi-backend spec M8 + section 43.2).
 *  Fixture-driven stream-json parsing + policy guards. No live provider
 *  (login absent in this environment); live smoke stays explicitly blocked.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  CLAUDE_CATALOG,
  ClaudeAdapter,
  FALLBACK_BIN_DIRS,
  claudeArgv,
  claudeAvailable,
  claudeBin,
  claudeDescriptor,
  claudeEnv,
  claudeError,
  claudeLine,
  claudeOutcome,
} from "../dist/backends/claude.js";

describe("claude argv + env (M8)", () => {
  it("builds an arg array, never a shell string; prompt stays one element", () => {
    const argv = claudeArgv({ prompt: "hi; rm -rf /", model: "sonnet" });
    assert.ok(Array.isArray(argv));
    assert.equal(argv.filter((a) => a === "hi; rm -rf /").length, 1);
    assert.ok(argv.includes("-p"));
    assert.ok(argv.includes("stream-json"));
    assert.ok(argv.includes("dontAsk"));
    assert.ok(!argv.join(" ").includes("--bare"));
    assert.ok(!argv.join(" ").includes("skip-permissions"));
    assert.ok(argv.includes("--allowedTools"));
    assert.ok(argv.includes("--disallowedTools"));
    const dis = argv[argv.indexOf("--disallowedTools") + 1];
    assert.ok(dis.includes("Read(~/.config/terminus/secrets/**)"));
    assert.ok(dis.includes("Edit(**/.env*)"));
    assert.ok(dis.includes("Write(**/notebook/user.md)"));
  });
  it("resume + model + system prompt append exact flags", () => {
    const argv = claudeArgv({ prompt: "x", model: "opus", resume: "sid-1", systemPrompt: "be terse" });
    assert.deepEqual(argv.slice(argv.indexOf("--model"), argv.indexOf("--model") + 2), ["--model", "opus"]);
    assert.deepEqual(argv.slice(argv.indexOf("--resume"), argv.indexOf("--resume") + 2), ["--resume", "sid-1"]);
    assert.ok(argv.includes("--append-system-prompt"));
  });
  it("large or dash-led prompts travel on stdin, never argv", () => {
    const big = claudeArgv({ prompt: "x".repeat(200_000), viaStdin: true, model: "sonnet" });
    assert.ok(big.includes("-p"));
    assert.equal(big.filter((a) => a.length > 100_000).length, 0);
    const dash = claudeArgv({ prompt: "--help", viaStdin: true, model: "sonnet" });
    assert.ok(dash.includes("-p"));
    assert.ok(!dash.includes("--help"));
  });
  it("lookup honors PATH first, then well-known install dirs", () => {
    const dir = mkdtempSync(join(tmpdir(), "claude-bin-"));
    const saved = process.env.PATH;
    try {
      writeFileSync(join(dir, "claude"), "#!/bin/sh\n");
      process.env.PATH = dir;
      assert.equal(claudeBin(), join(dir, "claude"));
    } finally {
      process.env.PATH = saved;
      rmSync(dir, { recursive: true, force: true });
    }
    assert.ok(FALLBACK_BIN_DIRS.includes("/usr/local/bin"));
    assert.ok(FALLBACK_BIN_DIRS.includes("/opt/homebrew/bin"));
  });
  it("child env strips API keys (subscription login authoritative)", () => {
    const e = claudeEnv();
    assert.ok(e.PATH);
    assert.ok(e.HOME);
    assert.equal(e.ANTHROPIC_API_KEY, undefined);
  });
  it("catalog is the documented alias set", () => {
    assert.deepEqual(CLAUDE_CATALOG.map((m) => m.id), ["sonnet", "opus", "haiku", "fable"]);
  });
  it("login evidence is honest (no token here, no false claim)", async () => {
    const { claudeLoginPresent } = await import("../dist/backends/claude.js");
    const home = mkdtempSync(join(tmpdir(), "claude-home-"));
    const saved = process.env.HOME;
    try {
      process.env.HOME = home; // an empty home, whatever this machine has logged in
      assert.equal(claudeLoginPresent(), false);
    } finally {
      process.env.HOME = saved;
      rmSync(home, { recursive: true, force: true });
    }
  });
  it("binary resolves on this machine", () => {
    assert.equal(claudeAvailable(), true);
    assert.ok((claudeBin() ?? "").endsWith("/claude"));
  });
});

describe("claude stream-json parsing (M8)", () => {
  it("init carries the session id; deltas stream; rest ignored", () => {
    const init = claudeLine({ type: "system", subtype: "init", session_id: "A" }, "t");
    assert.equal(init.initSessionId, "A");
    const d = claudeLine({ type: "stream_event", event: { delta: { type: "text_delta", text: "hi" } } }, "t");
    assert.deepEqual(d.events, [{ type: "message.delta", turnId: "t", text: "hi" }]);
    assert.deepEqual(claudeLine({ type: "system", subtype: "api_retry", attempt: 1 }, "t").events, []);
    assert.equal(claudeLine({ type: "mystery", x: 1 }, "t").unknown, true);
  });
  it("tool_use / tool_result map to tool events", () => {
    const u = claudeLine({
      type: "assistant",
      message: { content: [{ type: "tool_use", id: "tu1", name: "Bash", input: { c: "ls" } }] },
    }, "t");
    assert.deepEqual(u.events, [{ type: "tool.started", turnId: "t", toolId: "tu1", name: "Bash", input: { c: "ls" } }]);
    const r = claudeLine({
      type: "user",
      message: { content: [{ type: "tool_result", tool_use_id: "tu1", content: "ok" }] },
    }, "t");
    assert.equal(r.events[0].type, "tool.completed");
    assert.equal(r.events[0].toolId, "tu1");
  });
  it("result line parses success + error", () => {
    const ok = claudeLine({ type: "result", subtype: "success", result: "done", session_id: "A", is_error: false }, "t");
    const { detail, ...core } = ok.result;
    assert.deepEqual(core, { subtype: "success", text: "done", sessionId: "A", isError: false });
    assert.equal(typeof detail, "string");
    const bad = claudeLine({ type: "result", subtype: "error_usage", result: "", is_error: true }, "t");
    assert.equal(bad.result.isError, true);
  });
});

describe("claude outcome + errors (M8)", () => {
  it("interrupt-by-us wins; SIGTERM is never success; success completes", () => {
    const i = claudeOutcome({ interruptedByUs: true, signaled: "SIGINT", result: { subtype: "success", text: "x", sessionId: "A", isError: false } });
    assert.equal(i.state, "interrupted");
    const k = claudeOutcome({ interruptedByUs: false, signaled: "SIGTERM" });
    assert.deepEqual([k.state, k.code], ["failed", "process_exited"]);
    const c = claudeOutcome({ interruptedByUs: false, signaled: null, result: { subtype: "success", text: "y", sessionId: "A", isError: false } });
    assert.deepEqual([c.state, c.text], ["completed", "y"]);
    const f = claudeOutcome({ interruptedByUs: false, signaled: null, result: { subtype: "error_x", text: "", sessionId: "", isError: true } });
    assert.equal(f.state, "failed");
  });
  it("maps limit/auth/session shapes", () => {
    assert.equal(claudeError(new Error("usage limit reached"), "turn").code, "usage_limit");
    assert.equal(claudeError(new Error("not logged in"), "turn").code, "not_authenticated");
    assert.equal(claudeError(new Error("rate limited"), "turn").code, "rate_limited");
    assert.equal(claudeError(new Error("session not found"), "turn").code, "session_not_found");
  });
  it("descriptor is honest about v1 gaps", () => {
    const d = new ClaudeAdapter().descriptor();
    assert.equal(d.id, "claude");
    assert.equal(d.capabilities.overlappingTurns, false);
    assert.equal(d.capabilities.approvals, false);
    assert.equal(d.capabilities.attachments, true);
    // Proven since: zero-token listing + history from ~/.claude/projects.
    assert.equal(d.capabilities.sessionsList, true);
    assert.equal(d.capabilities.history, true);
    // Proven since: native command/skill listing, transcript delete, /compact.
    assert.equal(d.capabilities.commands, true);
    assert.equal(d.capabilities.deleteSession, true);
    assert.equal(d.capabilities.compact, true);
    // Proven since: titles (phone-set or Claude's own) and fork.
    assert.equal(d.capabilities.title, true);
    assert.equal(d.capabilities.fork, true);
  });
});

describe("claudeLine: background jobs", () => {
  it("a backgrounded task_started becomes a started background event", () => {
    const r = claudeLine({ type: "system", subtype: "task_started", task_id: "b22k", tool_use_id: "toolu_1", description: "Build the APK", is_backgrounded: true }, "t1");
    assert.deepEqual(r.events, [{ type: "background", turnId: "t1", status: "started", id: "b22k", label: "Build the APK", toolId: "toolu_1" }]);
    assert.equal(r.unknown, false);
  });
  it("a foreground task_started is ignored", () => {
    assert.deepEqual(claudeLine({ type: "system", subtype: "task_started", task_id: "x", is_backgrounded: false }, "t1").events, []);
  });
  it("task_notification ends it, with status, summary and output file", () => {
    const ok = claudeLine({ type: "system", subtype: "task_notification", task_id: "b22k", status: "completed", output_file: "/tmp/o", summary: "Build finished (exit 0)" }, "t1").events[0];
    assert.equal(ok.status, "done");
    assert.equal(ok.outputFile, "/tmp/o");
    assert.equal(ok.summary, "Build finished (exit 0)");
    assert.equal(claudeLine({ type: "system", subtype: "task_notification", task_id: "b", status: "failed" }, "t1").events[0].status, "failed");
  });
  it("task bookkeeping lines are known and silent", () => {
    for (const subtype of ["task_updated", "background_tasks_changed"]) {
      const r = claudeLine({ type: "system", subtype }, "t1");
      assert.deepEqual(r.events, []);
      assert.equal(r.unknown, false);
    }
  });
});
