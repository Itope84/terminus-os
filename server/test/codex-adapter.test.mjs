/** Codex adapter tests (multi-backend spec M7 + P13-P15 shapes).
 *  Fixture-driven protocol parsing + pure mapping. No live provider.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { FALLBACK_BIN_DIRS, LineBuffer, codexAvailable, codexBin, codexEnv } from "../dist/backends/codex-rpc.js";
import {
  CodexAdapter,
  codexApprovalEvent,
  codexDescriptor,
  codexError,
  codexEventsForNotification,
  codexTerminalEvent,
  codexUserInput,
  ensureWithinRoot,
  resolveChatCwd,
} from "../dist/backends/codex.js";

describe("codex transport framing (M7)", () => {
  it("LineBuffer reassembles splits and multiples, skips blanks/corrupt", () => {
    const lb = new LineBuffer();
    assert.deepEqual(lb.push('{"a":1}\n{"b":'), ['{"a":1}']);
    assert.deepEqual(lb.push('2}\n\n{"c":3}\n'), ['{"b":2}', '{"c":3}']);
    assert.deepEqual(lb.push(""), []);
  });
  it("codexEnv is an allowlist, never a full clone", () => {
    const e = codexEnv();
    assert.ok(e.PATH);
    assert.ok(e.HOME);
    assert.equal(e.ANTHROPIC_API_KEY, undefined);
    assert.equal(e.OPENCODE_SERVER_PASSWORD, undefined);
    assert.ok(Object.keys(process.env).length >= Object.keys(e).length);
  });
  it("binary resolves on this machine", { skip: !codexAvailable() && "codex CLI is not installed" }, () => {
    assert.equal(codexAvailable(), true);
    assert.ok((codexBin() ?? "").endsWith("/codex"));
  });
  it("lookup honors PATH first, then well-known install dirs", () => {
    const dir = mkdtempSync(join(tmpdir(), "codex-bin-"));
    const saved = process.env.PATH;
    try {
      writeFileSync(join(dir, "codex"), "#!/bin/sh\n");
      process.env.PATH = dir;
      assert.equal(codexBin(), join(dir, "codex"));
    } finally {
      process.env.PATH = saved;
      rmSync(dir, { recursive: true, force: true });
    }
    assert.ok(FALLBACK_BIN_DIRS.includes("/usr/local/bin"));
    assert.ok(FALLBACK_BIN_DIRS.includes("/opt/homebrew/bin"));
  });
  it("toolchain roots are workspace-scoped, secrets never in env", async () => {
    const { codexWritableRoots } = await import("../dist/backends/codex.js");
    const { codexEnv } = await import("../dist/backends/codex-rpc.js");
    const roots = codexWritableRoots();
    assert.equal(roots.length, 1);
    assert.ok(!roots[0].endsWith("Desktop"));
    const e = codexEnv();
    assert.equal(e.ANTHROPIC_API_KEY, undefined);
    assert.equal(e.OPENCODE_SERVER_PASSWORD, undefined);
  });
});

describe("codex event mapping (M7)", () => {
  const route = { terminusTurnId: "t1", threadId: "th1", codexTurnId: "c1" };
  it("agentMessage delta becomes message.delta", () => {
    const r = codexEventsForNotification("item/agentMessage/delta", { delta: "hi", turnId: "c1" }, route);
    assert.deepEqual(r, { events: [{ type: "message.delta", turnId: "t1", text: "hi" }], unknown: false });
  });
  it("reasoning deltas map, empty deltas emit nothing", () => {
    const r = codexEventsForNotification("item/reasoning/textDelta", { delta: "hmm" }, route);
    assert.equal(r.events[0].type, "reasoning.delta");
    assert.deepEqual(codexEventsForNotification("item/agentMessage/delta", { delta: "" }, route).events, []);
  });
  it("item started/completed become tool events", () => {
    const s = codexEventsForNotification("item/started", { item: { id: "i1", type: "commandExecution" } }, route);
    assert.deepEqual(s.events[0], { type: "tool.started", turnId: "t1", toolId: "i1", name: "shell", input: undefined });
    const c = codexEventsForNotification("item/completed", { item: { id: "i1" } }, route);
    assert.equal(c.events[0].type, "tool.completed");
  });
  it("turn/completed maps all terminal statuses; inProgress is silent", () => {
    const ok = codexTerminalEvent("t1", "completed", { items: [{ text: "done" }] });
    assert.deepEqual(ok, { type: "turn.completed", turnId: "t1", text: "done" });
    assert.deepEqual(codexTerminalEvent("t1", "interrupted", {}), { type: "turn.interrupted", turnId: "t1", reason: "interrupt" });
    const fail = codexTerminalEvent("t1", "failed", { error: { code: "x", message: "boom" } });
    assert.deepEqual(fail, { type: "turn.failed", turnId: "t1", code: "provider_error", message: "boom", retryable: false });
    assert.equal(codexTerminalEvent("t1", "inProgress", {}), null);
    const via = codexEventsForNotification("turn/completed", { turn: { status: "failed", error: { message: "m" } } }, route);
    assert.equal(via.events[0].type, "turn.failed");
  });
  it("unknown notifications are ignored, never crash", () => {
    const r = codexEventsForNotification("thread/realtime/sdp", { x: 1 }, route);
    assert.deepEqual(r, { events: [], unknown: true });
  });
  it("approval-shaped requests yield approval.requested; others null", () => {
    const ev = codexApprovalEvent("execCommandApproval", { approvalId: "a1", title: "run", turnId: "c1" }, "t1");
    assert.deepEqual(ev, {
      type: "approval.requested",
      turnId: "t1",
      approvalId: "a1",
      title: "run",
      description: "",
      tool: undefined,
    });
    assert.equal(codexApprovalEvent("turn/completed", {}, "t1"), null);
  });
});

describe("codex errors + policy (M7)", () => {
  it("maps usage/rate/auth/not-found shapes", () => {
    assert.equal(codexError(new Error("usageLimitExceeded"), "startTurn").code, "usage_limit");
    assert.equal(codexError(new Error("HTTP 429 too many"), "startTurn").code, "rate_limited");
    assert.equal(codexError(new Error("not logged in"), "listModels").code, "not_authenticated");
    assert.equal(codexError(new Error("unknown thread"), "history").code, "session_not_found");
    assert.equal(codexError(new Error("timeout"), "startTurn").code, "backend_unavailable");
    assert.equal(codexError(new Error("weird"), "x").code, "provider_error");
  });
  it("user input is plain text turns", () => {
    assert.deepEqual(codexUserInput("hi"), [{ type: "text", text: "hi" }]);
  });
  it("cwd stays in the workspace", () => {
    const root = resolveChatCwd("c");
    assert.equal(ensureWithinRoot(root, root), root);
    assert.throws(() => ensureWithinRoot("/etc", root), /outside workspace/);
  });
  it("resolveChatCwd defaults, expands ~, and contains (guarded opt-in)", async () => {
    const { resolveChatCwd, codexWorkspaceRoot } = await import("../dist/backends/codex.js");
    const root = codexWorkspaceRoot();
    assert.equal(resolveChatCwd("c"), root);
    assert.equal(resolveChatCwd("c", ""), root);
    assert.equal(resolveChatCwd("c", root + "/animations"), root + "/animations");
    // Yolo is the default (any folder); containment holds only when opted out.
    assert.equal(resolveChatCwd("c", "/etc"), "/etc");
    assert.throws(() => resolveChatCwd("c", "/etc", false), /outside workspace/);
    assert.throws(() => resolveChatCwd("c", "~/.ssh", false), /outside workspace/);
    assert.throws(() => resolveChatCwd("c", root + "/../x", false), /outside workspace/);
  });
  it("thread/start carries explicit policy (yolo by default, guarded on opt-out)", async () => {
    const { CodexAdapter, CODEX_SANDBOX, CODEX_APPROVAL_POLICY, codexInstructions, judgeCodexApproval } = await import("../dist/backends/codex.js");
    assert.equal(CODEX_SANDBOX, "workspace-write");
    assert.equal(CODEX_APPROVAL_POLICY, "untrusted");
    assert.ok(codexInstructions(null, "/w").includes("/w"));
    assert.ok(codexInstructions("sys", "/w").startsWith("sys"));
    assert.ok(!codexInstructions("sys", "/w").includes("Never read credentials"));
    assert.ok(codexInstructions("sys", "/w", false).includes("Never read credentials"));
    const seen = {};
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: () => {},
      onRequest: () => {},
      onClose: () => {},
      request: async (method, params) => {
        seen[method] = params;
        if (method === "thread/start") return { thread: { id: "th-pol" } };
        if (method === "turn/start") return { turn: { id: "cx-pol" } };
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    await a.startTurn({ chatId: "c", turnId: "t-pol", text: "hi", files: [] });
    assert.equal(seen["thread/start"].sandbox, "danger-full-access");
    assert.equal(seen["thread/start"].approvalPolicy, "never");
    assert.ok(typeof seen["thread/start"].cwd === "string" && seen["thread/start"].cwd.length > 0);
    await a.shutdown();
  });
  it("judge denies only the opencode deny set, allows everything else", async () => {
    const { judgeCodexApproval } = await import("../dist/backends/codex.js");
    const roots = { workspace: "/Users/t/Desktop", home: "/Users/t" };
    const deny = (method, params) => judgeCodexApproval(method, params, roots).allow;
    assert.equal(deny("execCommandApproval", { command: "cat ~/.config/terminus/secrets/x.b64" }), false);
    assert.equal(deny("execCommandApproval", { command: "ls ~/notebook/2026-W40.md" }), false);
    assert.equal(deny("execCommandApproval", { command: "export K=$(cat ~/.ssh/id_rsa)" }), false);
    assert.equal(deny("execCommandApproval", { command: "echo hi>~/.ssh/x" }), false);
    assert.equal(deny("execCommandApproval", { command: "cat ~/.ssh" }), false);
    assert.equal(deny("execCommandApproval", { command: "cat ~/.env" }), false);
    assert.equal(deny("execCommandApproval", { command: "cat ~/proj/.env.local" }), false);
    assert.equal(deny("execCommandApproval", { command: "cat ~/.codex/auth.json" }), false);
    assert.equal(deny("mysteryKind", { command: "ls" }), false);
    assert.equal(deny("execCommandApproval", {}), false);
    // Bare ".env" substring (prose, example names) is not an env file.
    assert.equal(deny("execCommandApproval", { command: "echo see .env.example" }), true);
    const allow = (method, params) => judgeCodexApproval(method, params, roots);
    // Opencode parity: outside-Desktop is no longer a reason to deny.
    assert.equal(allow("execCommandApproval", { command: "cat /etc/passwd" }).allow, true);
    assert.equal(allow("execCommandApproval", { command: "ls /tmp" }).allow, true);
    assert.equal(allow("execCommandApproval", { command: "ls 2>/dev/null" }).allow, true);
    assert.equal(allow("execCommandApproval", { command: "ls" }).allow, true);
    assert.equal(allow("execCommandApproval", { command: "npm test", cwd: "/Users/t/Desktop/x" }).allow, true);
    assert.equal(allow("item/commandExecution/requestApproval", { command: "git -C ~/Desktop/x status" }).allow, true);
    // Codex's own home: credential denied, skill mirrors allowed (parity).
    assert.equal(allow("item/fileChange/requestApproval", { grantRoot: "/Users/t/.codex/skills/recall" }).allow, true);
    assert.equal(allow("item/fileChange/requestApproval", { grantRoot: "/Users/t/Desktop/x" }).allow, true);
    assert.equal(allow("item/fileChange/requestApproval", { grantRoot: "/tmp/y" }).allow, true);
  });
  it("recall carve-out: db SELECTs allow, writes + auth deny", async () => {
    const { judgeCodexApproval } = await import("../dist/backends/codex.js");
    const roots = { workspace: "/Users/t/Desktop", home: "/Users/t" };
    const j = (command) => judgeCodexApproval("item/commandExecution/requestApproval", { command }, roots);
    assert.equal(j("sqlite3 ~/.local/share/opencode/opencode.db \"SELECT id FROM session_v2 LIMIT 1\"").allow, true);
    assert.equal(j("ls ~/.local/share/opencode/").allow, true);
    assert.equal(j("sqlite3 ~/.local/share/opencode/opencode.db \"DELETE FROM session_v2\"").allow, false);
    assert.equal(j("cat ~/.local/share/opencode/auth.json").allow, false);
    assert.equal(j("cp ~/.local/share/opencode/opencode.db /tmp/x.db").allow, false);
  });
  it("fileChange allows outside the deny set, markup never matters", async () => {
    const { judgeCodexApproval, pathTokens } = await import("../dist/backends/codex.js");
    const roots = { workspace: "/Users/t/Desktop", home: "/Users/t" };
    const j = (params) => judgeCodexApproval("item/fileChange/requestApproval", params, roots);
    assert.equal(j({ grantRoot: "/Users/t/Desktop/animations" }).allow, true);
    assert.equal(j({ grantRoot: "/tmp/y" }).allow, true);
    assert.equal(j({ grantRoot: "/Users/t/.ssh" }).allow, false);
    // Pathless fileChange is routine (patch tool omits per-file paths):
    // judged on what is present, then allowed — never "empty target".
    assert.equal(j({}).allow, true);
    assert.equal(j({ grantRoot: "/Users/t/Desktop/x", command: "see .env.example" }).allow, true);
    assert.deepEqual(pathTokens("run a/></rect><path d=x/> ok"), ["run", "a", "ok"]);
    assert.deepEqual(pathTokens("x /></blocks> y"), ["x", "y"]);
  });
  it("tokenizer never mangles relative paths (v9 regression)", async () => {
    const { judgeCodexApproval, pathTokens, resolveToken } = await import("../dist/backends/codex.js");
    const roots = { workspace: "/Users/t/Desktop", home: "/Users/t" };
    assert.deepEqual(pathTokens("cat animations/AGENTS.md; ls"), ["cat", "animations/AGENTS.md", "ls"]);
    assert.equal(resolveToken("animations/AGENTS.md", "/Users/t", "", "/Users/t/Desktop"), "/Users/t/Desktop/animations/AGENTS.md");
    assert.equal(resolveToken("ls", "/Users/t", "", "/Users/t/Desktop"), null);
    const j = (command) => judgeCodexApproval("item/commandExecution/requestApproval", { command }, roots);
    assert.equal(j("cat animations/AGENTS.md").allow, true);
    assert.equal(j("cat animations/AGENTS.md; cat package.json").allow, true);
    // Parity: outside-Desktop reads allow (only the deny set denies).
    assert.equal(j("cat ../../etc/passwd").allow, true);
  });
  it("descriptor declares serialized turns, approvals, no overlap", () => {
    const d = codexDescriptor(true, false);
    assert.equal(d.id, "codex");
    assert.equal(d.capabilities.overlappingTurns, false);
    assert.equal(d.capabilities.approvals, true);
    assert.equal(d.capabilities.commands, true);
    assert.equal(d.capabilities.attachments, true);
    assert.equal(d.capabilities.title, true);
  });
  it("renames a Codex thread through the app-server RPC", async () => {
    let call;
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: () => {},
      onRequest: () => {},
      onClose: () => {},
      request: async (method, params) => { call = { method, params }; return {}; },
    };
    const a = new CodexAdapter(() => stub);
    await a.setTitle({ chatId: "chat-1", remoteSessionId: "thread-1", title: "Renamed" });
    assert.deepEqual(call, {
      method: "thread/name/set",
      params: { threadId: "thread-1", name: "Renamed" },
    });
    await a.shutdown();
  });
  it("fork starts a fresh thread excluding prior turns", async () => {
    let call = null;
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: () => {},
      onRequest: () => {},
      onClose: () => {},
      request: async (method, params) => { call = { method, params }; return { thread: { id: "new-thread" } }; },
    };
    const a = new CodexAdapter(() => stub);
    const out = await a.fork({ chatId: "c", remoteSessionId: "t" });
    assert.deepEqual(call, {
      method: "thread/fork",
      params: { threadId: "t", excludeTurns: true },
    });
    assert.deepEqual(out, { remoteSessionId: "new-thread" });
    await a.shutdown();
  });
  it("compact starts native compaction on the thread", async () => {
    let call = null;
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: () => {},
      onRequest: () => {},
      onClose: () => {},
      request: async (method, params) => { call = { method, params }; return {}; },
    };
    const a = new CodexAdapter(() => stub);
    await a.compact({ chatId: "c", remoteSessionId: "t" });
    assert.deepEqual(call, {
      method: "thread/compact/start",
      params: { threadId: "t" },
    });
    await a.shutdown();
  });
  it("fast turns stash before attach and replay on stream (smoke race)", async () => {
    const { CodexAdapter } = await import("../dist/backends/codex.js");
    let notif = null;
    const turnCalls = [];
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: (cb) => { notif = cb; },
      onClose: () => {},
      onRequest: () => {},
      request: async (method) => {
        if (method === "thread/start") return { thread: { id: "th-race" } };
        if (method === "turn/start") { turnCalls.push(Date.now()); return { turn: { id: "cx-1" } }; }
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    const started = await a.startTurn({ chatId: "c", turnId: "t-race", text: "hi", files: [] });
    assert.equal(started.remoteSessionId, "th-race");
    // Provider execution runs in the background: wait until the adapter
    // reports working (mapped + started), not just until turn/start was
    // called — mapping lands in the adapter continuation after it.
    const a2 = a;
    for (let i = 0; i < 200; i++) {
      const st = await a2.getStatus({ chatId: "c", turnId: "t-race" });
      if (st.state === "working") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    assert.equal((await a.getStatus({ chatId: "c", turnId: "t-race" })).state, "working");
    // The turn finishes BEFORE stream() attaches (instant-reply race).
    notif("item/agentMessage/delta", { turnId: "cx-1", delta: "fast" });
    notif("turn/completed", { threadId: "th-race", turn: { id: "cx-1", status: "completed", items: [{ text: "fast" }] } });
    const got = [];
    await a.stream({ chatId: "c", turnId: "t-race", remoteSessionId: "th-race" }, (e) => got.push(e));
    assert.deepEqual(got.map((e) => e.type), ["turn.started", "message.delta", "turn.completed"]);
    assert.equal(got[2].text, "fast");
    const st = await a.getStatus({ chatId: "c", turnId: "t-race" });
    assert.equal(st.state, "completed");
    await a.shutdown();
  });
  it("serialization: B never reaches the provider until A terminals", async () => {
    const { CodexAdapter } = await import("../dist/backends/codex.js");
    let notif = null;
    const turnStarts = [];
    let releaseA = null;
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: (cb) => { notif = cb; },
      onClose: () => {},
      onRequest: () => {},
      request: async (method, params) => {
        if (method === "thread/start") return { thread: { id: "th-ser" } };
        if (method === "turn/start") {
          turnStarts.push(params.input[0].text);
          if (turnStarts.length === 1) {
            await new Promise((r) => { releaseA = r; });
            return { turn: { id: "cx-A" } };
          }
          return { turn: { id: "cx-B" } };
        }
        if (method === "thread/resume") return {};
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    const gotA = [];
    const gotB = [];
    // A starts and stays pending (provider turn open, no terminal yet).
    const pA = a.startTurn({ chatId: "ser", turnId: "tA", text: "A", files: [] });
    for (let i = 0; i < 200 && turnStarts.length < 1; i++) {
      await new Promise((r) => setTimeout(r, 10));
    }
    assert.deepEqual(turnStarts, ["A"]);
    const accA = await pA;
    await a.stream({ chatId: "ser", turnId: "tA", remoteSessionId: accA.remoteSessionId }, (e) => gotA.push(e));
    // B sends while A is still running: accepted fast, provider untouched.
    const accB = await a.startTurn({ chatId: "ser", turnId: "tB", remoteSessionId: accA.remoteSessionId, text: "B", files: [] });
    assert.equal(accB.remoteSessionId, accA.remoteSessionId);
    await a.stream({ chatId: "ser", turnId: "tB", remoteSessionId: accB.remoteSessionId }, (e) => gotB.push(e));
    await new Promise((r) => setTimeout(r, 100));
    assert.deepEqual(turnStarts, ["A"], "B reached the provider while A ran");
    assert.equal((await a.getStatus({ chatId: "ser", turnId: "tB" })).state, "waiting");
    // A terminals: B's provider turn starts, both settle exactly once.
    releaseA();
    for (let i = 0; i < 200; i++) {
      const st = await a.getStatus({ chatId: "ser", turnId: "tA" });
      if (st.state === "working") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    notif("turn/completed", { threadId: "th-ser", turn: { id: "cx-A", status: "completed", items: [{ text: "a-done" }] } });
    for (let i = 0; i < 200 && turnStarts.length < 2; i++) {
      await new Promise((r) => setTimeout(r, 10));
    }
    assert.deepEqual(turnStarts, ["A", "B"]);
    notif("turn/completed", { threadId: "th-ser", turn: { id: "cx-B", status: "completed", items: [{ text: "b-done" }] } });
    const terminal = (got) => got.filter((e) => e.type === "turn.completed");
    for (let i = 0; i < 200 && (terminal(gotA).length < 1 || terminal(gotB).length < 1); i++) {
      await new Promise((r) => setTimeout(r, 10));
    }
    assert.equal(terminal(gotA).length, 1);
    assert.equal(terminal(gotB).length, 1);
    assert.equal(terminal(gotA)[0].text, "a-done");
    assert.equal(terminal(gotB)[0].text, "b-done");
    await a.shutdown();
  });
  it("child death fails working turns explicitly (bug-4)", async () => {
    const { CodexAdapter } = await import("../dist/backends/codex.js");
    let closeCb = null;
    let notif = null;
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: (cb) => { notif = cb; },
      onClose: () => {},
      onRequest: () => {},
      onClose: (cb) => { closeCb = cb; },
      request: async (method) => {
        if (method === "thread/start") return { thread: { id: "th-die" } };
        if (method === "turn/start") return { turn: { id: "cx-die" } };
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    const acc = await a.startTurn({ chatId: "die", turnId: "t-die", text: "hi", files: [] });
    for (let i = 0; i < 200; i++) {
      const st = await a.getStatus({ chatId: "die", turnId: "t-die" });
      if (st.state === "working") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    const got = [];
    await a.stream({ chatId: "die", turnId: "t-die", remoteSessionId: acc.remoteSessionId }, (e) => got.push(e));
    // The child dies with no terminal notification: the turn must fail
    // explicitly, never sit "running" until the prune drops it silently.
    closeCb(new Error("codex app-server exited code=1 signal=none"));
    const terminal = got.filter((e) => e.type === "turn.failed");
    assert.equal(terminal.length, 1);
    assert.equal(terminal[0].code, "process_exited");
    assert.equal((await a.getStatus({ chatId: "die", turnId: "t-die" })).state, "failed");
    await a.shutdown();
  });
  it("deleteSession interrupts live turns, deletes the thread, drops state", async () => {
    const { CodexAdapter } = await import("../dist/backends/codex.js");
    const calls = [];
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: () => {},
      onRequest: () => {},
      onClose: () => {},
      request: async (method, params) => {
        calls.push(method);
        if (method === "thread/start") return { thread: { id: "th-del" } };
        if (method === "turn/start") return { turn: { id: "cx-del" } };
        if (method === "turn/interrupt") return {};
        if (method === "thread/delete") {
          assert.equal(params.threadId, "th-del");
          return {};
        }
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    const acc = await a.startTurn({ chatId: "del", turnId: "t-del", text: "hi", files: [] });
    for (let i = 0; i < 200; i++) {
      const st = await a.getStatus({ chatId: "del", turnId: "t-del" });
      if (st.state === "working") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    await a.deleteSession({ chatId: "del", remoteSessionId: acc.remoteSessionId });
    assert.ok(calls.includes("turn/interrupt"), "live turn interrupted first");
    assert.ok(calls.includes("thread/delete"), "thread actually deleted");
    assert.equal((await a.getStatus({ chatId: "del", turnId: "t-del" })).state, "unknown");
    await a.shutdown();
  });
  it("stop cancels queued turns before they ever reach the provider", async () => {
    const { CodexAdapter } = await import("../dist/backends/codex.js");
    let notif = null;
    const turnStarts = [];
    const stub = {
      started: true,
      start: async () => {},
      notify: () => {},
      close: async () => {},
      onNotification: (cb) => { notif = cb; },
      onClose: () => {},
      onRequest: () => {},
      request: async (method, params) => {
        if (method === "thread/start") return { thread: { id: "th-stop" } };
        if (method === "turn/start") {
          turnStarts.push(params.input[0].text);
          return { turn: { id: "cx-A" } };
        }
        if (method === "thread/resume") return {};
        throw new Error("unexpected " + method);
      },
    };
    const a = new CodexAdapter(() => stub);
    const pA = a.startTurn({ chatId: "stop", turnId: "tA", text: "A", files: [] });
    for (let i = 0; i < 200 && turnStarts.length < 1; i++) {
      await new Promise((r) => setTimeout(r, 10));
    }
    const accA = await pA;
    // B queues behind running A; stop cancels it pre-start.
    const accB = await a.startTurn({ chatId: "stop", turnId: "tB", remoteSessionId: accA.remoteSessionId, text: "B", files: [] });
    assert.equal((await a.getStatus({ chatId: "stop", turnId: "tB" })).state, "waiting");
    for (let i = 0; i < 200; i++) {
      const st = await a.getStatus({ chatId: "stop", turnId: "tA" });
      if (st.state === "working") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    assert.equal(await a.abortQueued("tB"), true);
    assert.equal(await a.abortQueued("tA"), false);
    // A terminals: B must still never start.
    notif("turn/completed", { threadId: "th-stop", turn: { id: "cx-A", status: "completed", items: [{ text: "a" }] } });
    await new Promise((r) => setTimeout(r, 100));
    assert.deepEqual(turnStarts, ["A"]);
    assert.equal((await a.getStatus({ chatId: "stop", turnId: "tB" })).state, "unknown");
    await a.shutdown();
    void accB;
  });
});
