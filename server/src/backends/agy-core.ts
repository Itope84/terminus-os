/** Pure agy helpers (argv, env, stream-json parsing, outcome, model parsing).
 *  Type-only imports so the unit tests load this file directly. */
import type { BackendEvent, ModelOption, UsageInfo } from "./types.js";
import { asRecord, textOf } from "./values.js";
export { textOf };
import { homedir } from "node:os";
import { join } from "node:path";
import { closeSync, existsSync, openSync, readdirSync, readSync, statSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);


const AGY_MODES = new Set(["accept-edits", "plan", "auto"]);

/** Child env: explicit allowlist, no API keys (subscription authoritative). */
export function agyEnv(src: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const keep = ["PATH", "HOME", "LOGNAME", "USER", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR",
    "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "SHELL", "EDITOR",
    "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS"];
  const out: Record<string, string> = {};
  for (const k of keep) {
    const v = src[k];
    if (typeof v === "string" && v) out[k] = v;
  }
  return out;
}

/** argv for one turn (never a shell string). Pure, unit-tested. */
/** agy's --effort values (agy --help). The app sends low/medium/high. */
export const AGY_EFFORTS = new Set(["low", "medium", "high", "xhigh", "max"]);

export function agyArgv(args: {
  model?: string | null;
  conversation?: string | null;
  mode?: string | null;
  yolo?: boolean;
  effort?: string | null;
}): string[] {
  const argv = ["--input-format", "stream-json", "--output-format", "stream-json"];
  if (args.model) argv.push("--model", args.model);
  if (args.effort && AGY_EFFORTS.has(args.effort)) argv.push("--effort", args.effort);
  if (args.conversation) argv.push("--conversation", args.conversation);
  if (args.mode && AGY_MODES.has(args.mode)) argv.push("--mode", args.mode);
  if (args.yolo) argv.push("--dangerously-skip-permissions");
  return argv;
}

/** The single stdin line for a turn. Pure, unit-tested. */
export function agyInput(text: string, systemPrompt?: string | null): string {
  const sys = (systemPrompt ?? "").trim();
  const content = sys ? `<system-instructions>\n${sys}\n</system-instructions>\n\n${text}` : text;
  return JSON.stringify({ event: "user", message: { content } }) + "\n";
}

export interface AgyResultLine {
  status: string;
  text: string;
  error: string;
  conversationId: string;
  denied: string[];
  usage?: UsageInfo;
}

export interface AgyParsed {
  events: BackendEvent[];
  conversationId?: string;
  result?: AgyResultLine;
  unknown: boolean;
}

/** Parse one stream-json line. `seenTools` tracks step indexes already
 *  announced so DONE without ACTIVE still yields started+completed.
 *  Unknown shapes are ignored (the schema is closed-source: never crash on
 *  drift). Pure. */
export function agyLine(line: Record<string, unknown>, turnId: string, seenTools: Set<string>): AgyParsed {
  const ev = textOf(line.event);
  const payload = asRecord(line[ev]);
  const conversationId = textOf(payload.conversation_id) || textOf(line.conversation_id) || undefined;
  if (ev === "init") return { events: [], conversationId, unknown: false };
  if (ev === "step_update") {
    const st = payload;
    const type = textOf(st.step_type);
    const state = textOf(st.state);
    const idx = typeof st.step_index === "number" ? st.step_index : -1;
    const events: BackendEvent[] = [];
    if (type === "agent_response") {
      const d = textOf(st.text_delta);
      if (d) events.push({ type: "message.delta", turnId, text: d });
    } else if (type === "tool") {
      const info = asRecord(st.tool_info);
      const name = textOf(st.tool_name) || textOf(info.name) || "tool";
      const toolId = `${conversationId ?? "c"}:${idx}`;
      if (!seenTools.has(toolId)) {
        seenTools.add(toolId);
        events.push({ type: "tool.started", turnId, toolId, name, input: info.parameters });
      }
      if (state === "DONE") {
        const err = asRecord(info.error);
        events.push({
          type: "tool.completed", turnId, toolId,
          output: info.output,
          error: Object.keys(err).length ? (textOf(err.message) || "tool error") : undefined,
        });
      }
    }
    return { events, conversationId, unknown: false };
  }
  if (ev === "result") {
    const u = asRecord(payload.usage);
    const denied = (Array.isArray(u.denied_actions) ? u.denied_actions : [])
      .map((d) => textOf(asRecord(d).display_name) || textOf(asRecord(d).action))
      .filter(Boolean);
    const usage: UsageInfo | undefined = typeof u.input_tokens === "number" || typeof u.output_tokens === "number"
      ? { inputTokens: typeof u.input_tokens === "number" ? u.input_tokens : undefined, outputTokens: typeof u.output_tokens === "number" ? u.output_tokens : undefined }
      : undefined;
    const errRec = asRecord(payload.error);
    return {
      events: [],
      conversationId,
      result: {
        status: textOf(payload.status),
        text: textOf(payload.response),
        error: textOf(errRec.message) || textOf(payload.error),
        conversationId: conversationId ?? "",
        denied,
        usage,
      },
      unknown: false,
    };
  }
  return { events: [], unknown: true };
}

export type AgySignal = "SIGINT" | "SIGTERM" | null;

/** Turn outcome (pure, unit-tested). Interrupt by us always wins; WAITING /
 *  RUNNING at the end is a failure (no approver exists headless). */
export function agyOutcome(s: {
  interruptedByUs: boolean;
  signaled: AgySignal;
  result?: AgyResultLine;
}): { state: "completed" | "interrupted" | "failed"; text: string; code: string } {
  if (s.interruptedByUs || s.signaled === "SIGINT") {
    return { state: "interrupted", text: s.result?.text ?? "", code: "turn_interrupted" };
  }
  const r = s.result;
  if (r) {
    if (r.status === "SUCCESS") {
      const note = r.denied.length
        ? `\n\n_Agy blocked ${[...new Set(r.denied)].join(", ")} (not pre-approved in its settings)._`
        : "";
      return { state: "completed", text: r.text + note, code: "" };
    }
    if (r.status === "CANCELED" || r.status === "INTERRUPTED") {
      return { state: "interrupted", text: r.text, code: "turn_interrupted" };
    }
    if (r.status === "WAITING") return { state: "failed", text: "needs approval", code: "permission_denied" };
    return { state: "failed", text: r.error || r.text, code: "provider_error" };
  }
  return { state: "failed", text: "", code: "process_exited" };
}

/** `agy models` output is not a documented format: take the first token of
 *  each line that looks like a model id; skip headers/decoration. Anything
 *  unparseable yields [] (the picker then shows only the backend default).
 *  Pure, unit-tested. */
export function parseAgyModels(out: string): ModelOption[] {
  const seen = new Set<string>();
  const rows: ModelOption[] = [];
  for (const raw of out.split("\n")) {
    const line = raw.replace(/^[\s*\-•>✓]+/, "").trim();
    if (!line || /^(available|models?|name|id|usage|note)\b/i.test(line)) continue;
    const tok = line.split(/\s+/)[0].replace(/[,:]$/, "");
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$/.test(tok) || seen.has(tok)) continue;
    seen.add(tok);
    rows.push({ id: tok, label: tok });
  }
  return rows.slice(0, 40);
}

/** Conversation ids are transcript-folder stems — strict, no traversal. */
export function agyIdOk(id: unknown): boolean {
  return typeof id === "string" && /^[0-9a-fA-F-]{1,80}$/.test(id);
}

/** Where agy keeps per-conversation folders. Override with
 *  TERMINUS_AGY_BRAIN (tests, nonstandard installs). */
export function agyBrainDir(env: NodeJS.ProcessEnv = process.env, home = homedir()): string {
  const o = (env.TERMINUS_AGY_BRAIN || "").trim();
  if (o) return o;
  return join(home, ".gemini", "antigravity-cli", "brain");
}

export function agyTranscriptPath(brainDir: string, id: string): string {
  return join(brainDir, id, ".system_generated", "logs", "transcript.jsonl");
}

/** Strip the <USER_REQUEST>/<ADDITIONAL_METADATA> envelope agy wraps prompts in, and the
 *  <system-instructions> block agyInput() prepends to every turn (agy has no system-prompt flag),
 *  so session titles and history show what the person typed. */
export function agyUserText(content: string): string {
  const m = content.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/);
  return (m ? m[1] : content)
    .replace(/^\s*<system-instructions>[\s\S]*?<\/system-instructions>\s*/, "")
    .trim();
}

/** Session title = first non-blank line of the first user request. */
export function agySessionTitle(firstUserText: string): string {
  const line = firstUserText.split("\n").map((s) => s.trim()).find((s) => s.length) ?? "";
  return line.slice(0, 80);
}

export type AgyHistoryMessage = { role: string; text: string; at?: number };

/** transcript.jsonl → user/assistant rows. Tool-only steps (no text) are
 *  skipped; malformed lines never throw. Pure, unit-tested. */
export function agyTranscriptMessages(raw: string, limit = 200): AgyHistoryMessage[] {
  const out: AgyHistoryMessage[] = [];
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue;
    let o: Record<string, unknown>;
    try { o = JSON.parse(line) as Record<string, unknown>; } catch { continue; }
    const at = typeof o.created_at === "string" ? Date.parse(o.created_at) : NaN;
    const stamp = Number.isFinite(at) ? at : undefined;
    if (o.type === "USER_INPUT") {
      const text = agyUserText(String(o.content ?? ""));
      if (text) out.push({ role: "user", text, ...(stamp === undefined ? {} : { at: stamp }) });
    } else if (o.source === "MODEL" && typeof o.content === "string" && o.content.trim()) {
      out.push({ role: "assistant", text: o.content, ...(stamp === undefined ? {} : { at: stamp }) });
    }
  }
  return out.slice(-limit);
}

export type AgyListedSession = { id: string; title: string; updatedAt: number };

/** Read conversation summary title from conversation_summaries.db if present. */
export function agyDbTitle(conversationId: string, cliDir?: string): string | null {
  try {
    const { DatabaseSync } = require("node:sqlite") as typeof import("node:sqlite");
    const dir = cliDir || join(homedir(), ".gemini", "antigravity-cli");
    const dbPath = join(dir, "conversation_summaries.db");
    if (!existsSync(dbPath)) return null;
    const db = new DatabaseSync(dbPath, { open: true, readOnly: true });
    try {
      const row = db.prepare("SELECT title FROM conversation_summaries WHERE conversation_id = ?").get(conversationId) as { title?: unknown } | undefined;
      const t = typeof row?.title === "string" ? row.title.trim() : "";
      return t || null;
    } finally {
      try { db.close(); } catch { /* ignore */ }
    }
  } catch {
    return null;
  }
}

/** Update conversation summary title in conversation_summaries.db. */
export function setAgyDbTitle(conversationId: string, title: string, cliDir?: string): boolean {
  try {
    const { DatabaseSync } = require("node:sqlite") as typeof import("node:sqlite");
    const dir = cliDir || join(homedir(), ".gemini", "antigravity-cli");
    const dbPath = join(dir, "conversation_summaries.db");
    if (!existsSync(dbPath)) return false;
    const db = new DatabaseSync(dbPath, { open: true });
    try {
      const res = db.prepare("UPDATE conversation_summaries SET title = ? WHERE conversation_id = ?").run(title.trim(), conversationId);
      return (res?.changes ?? 0) > 0;
    } finally {
      try { db.close(); } catch { /* ignore */ }
    }
  } catch {
    return false;
  }
}

/** Read at most this much when hunting the first user line (it's step 0). */
const TITLE_HEAD_BYTES = 65536;

/** Extract first user text line from a transcript path. */
export function agyTranscriptFirstTitle(tp: string): string {
  let fd = -1;
  try {
    fd = openSync(tp, "r");
    const buf = Buffer.alloc(TITLE_HEAD_BYTES);
    const n = readSync(fd, buf, 0, TITLE_HEAD_BYTES, 0);
    const head = buf.subarray(0, n).toString("utf8");
    for (const line of head.split("\n")) {
      let o: Record<string, unknown>;
      try { o = JSON.parse(line); } catch { continue; }
      if (o.type !== "USER_INPUT") continue;
      return agySessionTitle(agyUserText(String(o.content ?? "")));
    }
  } catch { /* title stays empty */ } finally {
    try { if (fd >= 0) closeSync(fd); } catch { /* ignore */ }
  }
  return "";
}

/** Sessions from the brain folder, newest first. `known` scopes the list to
 *  conversations Terminus started (flat store has no cwd to filter on); null
 *  or empty set = everything. Every folder is independent: a missing or
 *  unparsable transcript skips that folder, never the whole list. */
export function listAgySessions(brainDir: string, known: Set<string> | null, limit = 200): AgyListedSession[] {
  let names: string[];
  try { names = readdirSync(brainDir); } catch { return []; }
  const out: AgyListedSession[] = [];
  for (const name of names) {
    if (!agyIdOk(name)) continue;
    if (known && known.size > 0 && !known.has(name)) continue;
    const tp = agyTranscriptPath(brainDir, name);
    let mtime = 0;
    try { mtime = statSync(tp).mtimeMs; } catch { continue; }
    let title = agyDbTitle(name);
    if (!title) {
      title = agyTranscriptFirstTitle(tp);
    }
    out.push({ id: name, title: title || name.slice(0, 8), updatedAt: Math.floor(mtime) });
  }
  out.sort((a, b) => b.updatedAt - a.updatedAt);
  return out.slice(0, limit);
}

