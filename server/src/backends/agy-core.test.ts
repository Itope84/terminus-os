import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";
import { agyArgv, agyEnv, agyInput, agyLine, agyOutcome, parseAgyModels, agyIdOk, agyUserText, agySessionTitle, agyTranscriptMessages, listAgySessions, agyBrainDir, agyTranscriptPath, agyDbTitle, setAgyDbTitle } from "./agy-core.js";

const require = createRequire(import.meta.url);

const T = "turn-1";

test("argv: stdin stream-json, no prompt on argv, resume via --conversation never --continue", () => {
  const a = agyArgv({ model: "m1", conversation: "abc-123" });
  assert.deepEqual(a.slice(0, 4), ["--input-format", "stream-json", "--output-format", "stream-json"]);
  assert.ok(a.includes("--conversation") && a[a.indexOf("--conversation") + 1] === "abc-123");
  assert.ok(!a.includes("--continue") && !a.includes("-p") && !a.includes("--dangerously-skip-permissions"));
});

test("argv: mode allowlisted, yolo only when asked", () => {
  assert.ok(!agyArgv({ mode: "rm -rf" }).includes("--mode"));
  assert.deepEqual(agyArgv({ mode: "plan" }).slice(-2), ["--mode", "plan"]);
  assert.ok(agyArgv({ yolo: true }).includes("--dangerously-skip-permissions"));
});

test("env: no API keys, keeps HOME/PATH", () => {
  const e = agyEnv({ PATH: "/bin", HOME: "/h", GEMINI_API_KEY: "k", GOOGLE_API_KEY: "k", SECRET: "x" });
  assert.deepEqual(e, { PATH: "/bin", HOME: "/h" });
});

test("input: one JSON line, system prompt prefixed, dash-leading text safe", () => {
  const l = agyInput("-rf hi", " be brief ");
  assert.ok(l.endsWith("\n") && l.split("\n").length === 2);
  const o = JSON.parse(l);
  assert.equal(o.event, "user");
  assert.match(o.message.content, /^<system-instructions>\nbe brief\n<\/system-instructions>\n\n-rf hi$/);
  assert.equal(JSON.parse(agyInput("x")).message.content, "x");
});

test("init and step_update carry the conversation id", () => {
  const seen = new Set<string>();
  assert.equal(agyLine({ event: "init", init: { conversation_id: "c1", cwd: "/x" } }, T, seen).conversationId, "c1");
  const p = agyLine({ event: "step_update", step_update: { conversation_id: "c1", step_index: 1, state: "ACTIVE", step_type: "agent_response", text_delta: "hi" } }, T, seen);
  assert.deepEqual(p.events, [{ type: "message.delta", turnId: T, text: "hi" }]);
});

test("tool step: ACTIVE starts, DONE completes once; DONE alone gives both", () => {
  const seen = new Set<string>();
  const mk = (state: string, extra = {}) => ({ event: "step_update", step_update: { conversation_id: "c", step_index: 2, state, step_type: "tool", tool_name: "run_command", tool_info: { name: "run_command", parameters: { cmd: "ls" }, output: "ok", ...extra } } });
  assert.deepEqual(agyLine(mk("ACTIVE"), T, seen).events.map((e) => e.type), ["tool.started"]);
  assert.deepEqual(agyLine(mk("DONE"), T, seen).events.map((e) => e.type), ["tool.completed"]);
  assert.deepEqual(agyLine({ ...mk("DONE"), step_update: { ...mk("DONE").step_update, step_index: 9 } }, T, seen).events.map((e) => e.type), ["tool.started", "tool.completed"]);
  const bad = agyLine(mk("DONE", { error: { type: "x", message: "boom" } }), T, new Set(["c:2"]));
  assert.equal((bad.events[0] as { error?: string }).error, "boom");
});

test("result: status, response, denied actions, usage", () => {
  const p = agyLine({ event: "result", result: { conversation_id: "c", status: "SUCCESS", response: "done", usage: { input_tokens: 5, output_tokens: 2, denied_actions: [{ action: "write_file", display_name: "WriteToFile" }] } } }, T, new Set());
  assert.equal(p.result?.status, "SUCCESS");
  assert.deepEqual(p.result?.denied, ["WriteToFile"]);
  assert.deepEqual(p.result?.usage, { inputTokens: 5, outputTokens: 2 });
});

test("unknown events are ignored, never throw", () => {
  assert.equal(agyLine({ event: "mystery" }, T, new Set()).unknown, true);
  assert.equal(agyLine({}, T, new Set()).events.length, 0);
});

test("outcome mapping", () => {
  const r = (status: string, extra = {}) => ({ status, text: "t", error: "", conversationId: "c", denied: [] as string[], ...extra });
  assert.equal(agyOutcome({ interruptedByUs: false, signaled: null, result: r("SUCCESS") }).state, "completed");
  assert.equal(agyOutcome({ interruptedByUs: true, signaled: null, result: r("SUCCESS") }).state, "interrupted");
  assert.equal(agyOutcome({ interruptedByUs: false, signaled: null, result: r("CANCELED") }).state, "interrupted");
  assert.equal(agyOutcome({ interruptedByUs: false, signaled: null, result: r("ERROR", { error: "bad" }) }).state, "failed");
  assert.equal(agyOutcome({ interruptedByUs: false, signaled: null, result: r("WAITING") }).code, "permission_denied");
  assert.equal(agyOutcome({ interruptedByUs: false, signaled: null }).code, "process_exited");
  const d = agyOutcome({ interruptedByUs: false, signaled: null, result: r("SUCCESS", { denied: ["WriteToFile", "WriteToFile"] }) });
  assert.match(d.text, /blocked WriteToFile \(/);
  assert.equal((d.text.match(/WriteToFile/g) || []).length, 1);
});

test("models parse: ids only, headers and decoration skipped, junk gives []", () => {
  const rows = parseAgyModels("Available models:\n  * gemini-3-pro  (default)\n- flash-2.5 fast\n\nUSAGE x\n  gemini-3-pro dup\n");
  assert.deepEqual(rows.map((r) => r.id), ["gemini-3-pro", "flash-2.5"]);
  assert.deepEqual(parseAgyModels("!!\n--\n"), []);
});

test("transcript ids are strict folder stems", () => {
  assert.equal(agyIdOk("1bdcc42c-81cd-4e0a-9ceb-a10e6d328c36"), true);
  assert.equal(agyIdOk("../x"), false);
  assert.equal(agyIdOk(""), false);
  assert.equal(agyIdOk(null), false);
});

test("user envelope stripped, title is first line", () => {
  const raw = "<USER_REQUEST>\nlook at the padel app\nmore\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nx\n</ADDITIONAL_METADATA>";
  assert.equal(agyUserText(raw), "look at the padel app\nmore");
  assert.equal(agySessionTitle(agyUserText(raw)), "look at the padel app");
  assert.equal(agyUserText("plain"), "plain");
});

test("our system-instructions block is stripped from titles and history", () => {
  const sent = JSON.parse(agyInput("fix the login bug\nmore", "be terse")).message.content as string;
  const raw = `<USER_REQUEST>\n${sent}\n</USER_REQUEST>`;
  assert.equal(agyUserText(raw), "fix the login bug\nmore");
  assert.equal(agySessionTitle(agyUserText(raw)), "fix the login bug");
  assert.equal(agyUserText(sent), "fix the login bug\nmore");
});

test("transcript messages: user in, model text in, tool-only steps skipped", () => {
  const raw = [
    JSON.stringify({ type: "USER_INPUT", source: "USER_EXPLICIT", content: "<USER_REQUEST>\nhi\n</USER_REQUEST>", created_at: "2026-08-31T00:19:00Z" }),
    JSON.stringify({ type: "GENERIC", source: "MODEL", content: "working", tool_calls: [{ name: "x" }], created_at: "2026-08-31T00:19:01Z" }),
    JSON.stringify({ type: "GENERIC", source: "MODEL", tool_calls: [{ name: "y" }], created_at: "2026-08-31T00:19:02Z" }),
    "junk{",
    JSON.stringify({ type: "PLANNER_RESPONSE", source: "MODEL", content: "done", created_at: "2026-08-31T00:19:03Z" }),
  ].join("\n");
  const msgs = agyTranscriptMessages(raw);
  assert.deepEqual(msgs.map((m) => m.role), ["user", "assistant", "assistant"]);
  assert.equal(msgs[0].text, "hi");
  assert.equal(typeof msgs[1].at, "number");
});

test("brain listing: newest first, scoped to known, bad folders skipped", () => {
  const brain = mkdtempSync(join(tmpdir(), "agy-brain-"));
  const mk = (id: string, userLine: string, mtimeMs: number) => {
    const dir = join(brain, id, ".system_generated", "logs");
    mkdirSync(dir, { recursive: true });
    const p = join(dir, "transcript.jsonl");
    writeFileSync(p, JSON.stringify({ type: "USER_INPUT", source: "USER_EXPLICIT", content: `<USER_REQUEST>\n${userLine}\n</USER_REQUEST>`, created_at: "2026-08-31T00:00:00Z" }) + "\n");
    const t = new Date(mtimeMs);
    utimesSync(p, t, t);
  };
  const A = "aaaaaaaa-0000-4000-8000-000000000001";
  const B = "bbbbbbbb-0000-4000-8000-000000000002";
  mk(A, "alpha chat", 1000);
  mk(B, "beta chat", 2000);
  mkdirSync(join(brain, "not-a-uuid!"));
  mkdirSync(join(brain, B + "-empty", ".system_generated", "logs"), { recursive: true });
  const all = listAgySessions(brain, null);
  assert.deepEqual(all.map((s) => s.id), [B, A]);
  assert.equal(all[0].title, "beta chat");
  const scoped = listAgySessions(brain, new Set([A]));
  assert.deepEqual(scoped.map((s) => s.id), [A]);
  const empty = listAgySessions(join(brain, "missing"), null);
  assert.deepEqual(empty, []);
});

test("brain dir honors override, transcript path shape", () => {
  assert.equal(agyBrainDir({ TERMINUS_AGY_BRAIN: " /x " } as NodeJS.ProcessEnv, "/h"), "/x");
  assert.ok(agyBrainDir({}, "/h").endsWith(join(".gemini", "antigravity-cli", "brain")));
  assert.ok(agyTranscriptPath("/b", "abc-123").endsWith(join("abc-123", ".system_generated", "logs", "transcript.jsonl")));
});

/** node:sqlite ships with Node 22.5+. On older Node the title lookup degrades to "no title" at runtime. */
const hasSqlite = (() => { try { require("node:sqlite"); return true; } catch { return false; } })();

test("db title lookup and update", { skip: !hasSqlite && "needs Node 22.5+ (node:sqlite)" }, () => {
  const dir = mkdtempSync(join(tmpdir(), "agy-cli-"));
  const { DatabaseSync } = require("node:sqlite") as typeof import("node:sqlite");
  const db = new DatabaseSync(join(dir, "conversation_summaries.db"), { open: true });
  db.exec("CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, title TEXT)");
  db.prepare("INSERT INTO conversation_summaries (conversation_id, title) VALUES (?, ?)").run("c-123", "Padel app plan");
  db.close();

  assert.equal(agyDbTitle("c-123", dir), "Padel app plan");
  assert.equal(agyDbTitle("missing", dir), null);

  assert.equal(setAgyDbTitle("c-123", "Renamed plan", dir), true);
  assert.equal(agyDbTitle("c-123", dir), "Renamed plan");
});

test("agyArgv passes agy's own --effort values and drops anything else", () => {
  const a = agyArgv({ effort: "high" });
  assert.deepEqual(a.slice(a.indexOf("--effort"), a.indexOf("--effort") + 2), ["--effort", "high"]);
  assert.ok(!agyArgv({ effort: "turbo" }).includes("--effort"));
  assert.ok(!agyArgv({}).includes("--effort"));
});

test("recordAgyLine: a finished step's usage is the context, the result adds to totals", async () => {
  const { recordAgyLine, getUsage } = await import("../context-usage.js");
  const chat = "agy-usage-test";
  recordAgyLine(chat, { event: "step_update", step_update: { step_type: "agent_response", state: "DONE",
    usage: { input_tokens: 8560, output_tokens: 19, thinking_tokens: 18, cache_read_tokens: 4000 } } });
  const u = getUsage(chat)!;
  assert.equal(u.backend, "agy");
  assert.equal(u.used, 8560 + 19 + 18);
  recordAgyLine(chat, { event: "result", result: { usage: { total_tokens: 8579 } } });
  assert.equal(getUsage(chat)!.totalTokens, 8579);
  assert.equal(getUsage(chat)!.turns, 1);
});
