/** Claude Code CLI adapter (multi-backend spec M8, section 16).
 *
 *  One `claude -p` invocation per Terminus turn (intentionally different
 *  from the Codex app-server): streaming newline-delimited `stream-json`,
 *  final `result` event as completion authority, `--resume` for follow-ups.
 *
 *  Explicit v1 policy (no guessing, per spec 16.8/16.9):
 *  - Non-bare mode (subscription OAuth): --bare would skip the subscription
 *    login and demand ANTHROPIC_API_KEY instead. Never pass --bare here.
 *  - ANTHROPIC_API_KEY is STRIPPED from the child env so the user's own
 *    subscription login is authoritative (no accidental API billing, no
 *    key confusion). API-key mode is a later deployment decision.
 *  - --permission-mode dontAsk: anything that would prompt is denied, and
 *    the run continues. --allowedTools pre-allows the broad toolset and
 *    --disallowedTools denies opencode's forbidden set (opencode parity,
 *    owner-explicit).
 *  - Yolo by default (owner's call, 2026-11): --dangerously-skip-permissions, no permission
 *    blocks and no deny list. TERMINUS_CLAUDE_YOLO=0 restores the guarded mode (dontAsk plus the
 *    allow/deny lists below).
 *  - Normal stop is SIGINT (clean turn end); SIGTERM is hard-kill only and
 *    never counts as success.
 *  - Canonical session id: the FIRST fresh-run init id. A resumed run's
 *    init id never overwrites it (spec 16.5 regression guard, unit-tested).
 */
import { watchStall } from "../lifecycle.js";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { recordClaudeLine } from "../context-usage.js";
import { AGENTBOOK_HINT, forgetBook, stripAgentbook } from "../agentbook.js";
import type {
  BackendAdapter,
  BackendContext,
  BackendDescriptor,
  BackendEvent,
  BackendStatus,
  CommandOption,
  ForkArgs,
  ForkResult,
  HistoryArgs,
  HistoryResult,
  InterruptResult,
  InterruptTurnArgs,
  ModelOption,
  RemoteSession,
  ResumeSessionArgs,
  ResumeSessionResult,
  SessionArgs,
  StartTurnArgs,
  StartTurnResult,
  StatusArgs,
  StreamArgs,
  EmitBackendEvent,
} from "./types.js";
import { BackendError, classifyProviderError, safeMessage, type ErrorRule } from "./errors.js";
import { asRecord, textOf } from "./values.js";
import { promptWithAttachments } from "./attachments.js";
import { LineBuffer } from "./codex-rpc.js";
import { locationDir } from "../opencode-proxy.js";
import { closeSync, existsSync, openSync, readdirSync, readFileSync, readSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { findBin } from "./bin.js";
import { claudeCommandList } from "./claude-commands.js";
import { claudeCompactOutcome, claudeForkTranscript, claudeInitSessionId, claudeInitSlashCommands, claudeSessionTitle, readClaudeTitles, setClaudeTitle } from "./claude-session.js";
import { markOwned, readOwned } from "../session-ownership.js";
import { claudeBackgroundFields, publishBackground } from "../background-log.js";
import { appendInbox } from "../inbox.js";

export { FALLBACK_BIN_DIRS } from "./bin.js";

/** Locate the claude binary: PATH first (server-owned; never client-controlled),
 *  then the well-known dirs. */
export function claudeBin(): string | null {
  return findBin("claude");
}

/** Binary to spawn right now. The path is resolved when the adapter is built, but an upgrade swaps the
 *  versioned folder behind the symlink, and a server that started in that window would otherwise keep a
 *  bare `claude` (ENOENT) until restarted. Re-resolves only when the remembered path is gone. */
export function liveBin(remembered: string): string {
  try { if (existsSync(remembered)) return remembered; } catch { /* fall through */ }
  return claudeBin() ?? remembered;
}
export function claudeAvailable(): boolean {
  return claudeBin() !== null;
}

/** Best-effort login evidence (no inference turn to probe): stored OAuth
 *  credentials file, or an access token inside ~/.claude.json. False
 *  means "no evidence" — the descriptor stays honest instead of claiming
 *  either state until a real turn succeeds. */
let loginCache: { at: number; value: boolean } | null = null;
const LOGIN_TTL_MS = 5 * 60 * 1000;

export function claudeLoginPresent(): boolean {
  if (loginCache && Date.now() - loginCache.at < LOGIN_TTL_MS) return loginCache.value;
  let value = false;
  try {
    const home = homedir();
    if (existsSync(join(home, ".claude", ".credentials.json"))) value = true;
    else {
      const raw = readFileSync(join(home, ".claude.json"), "utf8");
      value = raw.includes("accessToken");
    }
  } catch {
    value = false;
  }
  if (!value) value = claudeCliLoggedIn();
  loginCache = { at: Date.now(), value };
  return value;
}

/** Keychain-backed logins leave no file behind; ask the CLI itself. */
function claudeCliLoggedIn(): boolean {
  const bin = claudeBin();
  if (!bin) return false;
  try {
    const out = execFileSync(bin, ["auth", "status"], { encoding: "utf8", timeout: 5000, stdio: ["ignore", "pipe", "ignore"] });
    return JSON.parse(out).loggedIn === true;
  } catch {
    return false;
  }
}

/** Deployment-known model aliases, exactly as `claude --help` documents
 *  on the installed CLI (2.1.285): aliases for the latest models. No
 *  invented full ids; --model takes these aliases. */
export const CLAUDE_CATALOG: ModelOption[] = [
  { id: "sonnet", label: "Sonnet" },
  { id: "opus", label: "Opus" },
  { id: "haiku", label: "Haiku" },
  { id: "fable", label: "Fable" },
];

export function claudeDescriptor(available: boolean, authenticated: boolean): BackendDescriptor {
  return {
    id: "claude",
    label: "Claude",
    shortLabel: "Claude",
    available,
    authenticated,
    capabilities: {
      streaming: true,
      models: true,
      commands: true,
      sessionsList: true,
      history: true,
      resume: true,
      interruptTurn: true,
      killProcess: true,
      compact: true,
      title: true,
      deleteSession: true,
      fork: true,
      approvals: false,
      attachments: true,
      overlappingTurns: false,
      effort: true,
      usage: true,
      steer: claudePersist(),
    },
  };
}

/** Opencode parity for the claude CLI (owner-explicit): pre-allow the broad
 *  toolset so dontAsk rarely bites, and deny the same forbidden set
 *  (secrets, keys, auth, notes, dotenv files) for Read/Edit/Write.
 *  Residual: Bash text patterns are best-effort (a script that opens a file itself is not
 *  matched), so file-tool path denies remain the strong boundary. No --dangerously-skip-permissions. */
/** Browser tiers for Claude (AGENTS.md, Web fetch routing). Off unless set:
 *    TERMINUS_CLAUDE_MCP_CONFIG=<file>  extra MCP servers (e.g. chrome-devtools) via --mcp-config;
 *                                       every server in it is allowed (mcp__<name>__*)
 *    TERMINUS_CLAUDE_CHROME=1           Claude in Chrome (--chrome): the Mac's signed-in Chrome */
export function claudeBrowserArgs(env: NodeJS.ProcessEnv = process.env): string[] {
  const out: string[] = [];
  const cfg = (env.TERMINUS_CLAUDE_MCP_CONFIG || "").trim();
  if (cfg) out.push("--mcp-config", cfg);
  if (env.TERMINUS_CLAUDE_CHROME === "1") out.push("--chrome");
  return out;
}

export function claudeBrowserTools(env: NodeJS.ProcessEnv = process.env, readFile: (p: string) => string = (p) => readFileSync(p, "utf8")): string[] {
  const tools: string[] = [];
  const cfg = (env.TERMINUS_CLAUDE_MCP_CONFIG || "").trim();
  if (cfg) {
    try {
      const servers = (JSON.parse(readFile(cfg)) as { mcpServers?: Record<string, unknown> }).mcpServers ?? {};
      for (const name of Object.keys(servers)) if (/^[\w-]+$/.test(name)) tools.push(`mcp__${name}__*`);
    } catch { /* unreadable: no extra tools; the CLI reports the bad file itself */ }
  }
  if (env.TERMINUS_CLAUDE_CHROME === "1") tools.push("mcp__claude-in-chrome__*");
  return tools;
}

export const CLAUDE_ALLOWED_TOOLS =
  "Bash Edit Write Read Glob Grep WebFetch WebSearch TodoWrite";
const CLAUDE_DENY_PATHS = [
  "~/.config/terminus/secrets/**",
  "~/.config/opencode/auth.json",
  "**/.env*",
  "**/notebook/20*-W*.md",
  "**/notebook/user.md",
  // Parity with the codex deny set (DENY_MARKERS): key homes and the CLI's own credential files.
  "~/.ssh/**",
  "~/.gnupg/**",
  "~/.codex/auth.json",
  "~/.claude/.credentials.json",
  "~/.claude.json",
];
/** Substring denies for shell commands, mirroring codex's marker judge. Read/Edit denies already
 *  cover file commands Claude Code recognises in Bash (cat, head, tail, sed, tee, redirects), but
 *  not scripts that open files themselves; these catch the common spellings. Best-effort text
 *  matching (see Claude Code permissions docs): no spaces, so they survive the space-joined flag. */
export const CLAUDE_DENY_BASH = [
  "*terminus/secrets*",
  "*opencode/auth.json*",
  "*.ssh/*",
  "*.gnupg/*",
  "*.codex/auth.json*",
  "*.claude/.credentials.json*",
].map((p) => `Bash(${p})`);
export const CLAUDE_DISALLOWED_TOOLS = [
  ...CLAUDE_DENY_PATHS.flatMap((p) => [
    `Read(${p})`,
    `Edit(${p})`,
    // Claude Code consults Edit(path) for every editing tool; Write(path) is accepted but never
    // matched. Kept so the list stays a superset of what older CLIs honoured.
    `Write(${p})`,
  ]),
  ...CLAUDE_DENY_BASH,
];
// Usage metrics stay on (no code, prompts or paths; turning them off also stops feature flags, so
// new Claude Code features would arrive late).
export const CLAUDE_PRIVACY_ENV: Readonly<Record<string, string>> = {
  DISABLE_ERROR_REPORTING: "1",
  DISABLE_FEEDBACK_COMMAND: "1",
  CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: "1",
};
/** Child env: explicit allowlist minus ANTHROPIC_API_KEY (subscription
 *  login stays authoritative). Pure, unit-tested. */
export function claudeEnv(compactAt?: number | null, chatId?: string | null): Record<string, string> {
  const keep = ["PATH", "HOME", "LOGNAME", "USER", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "SHELL", "EDITOR",
    // Headless-login + egress routes (passthrough of the server's own env):
    // setup-token logins provision via OAuth token, proxies/CA matter on
    // corporate boxes. Never ANTHROPIC_API_KEY (subscription stays
    // authoritative — see module doc).
    "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS"];
  const out: Record<string, string> = {};
  for (const k of keep) {
    const v = process.env[k];
    if (typeof v === "string" && v) out[k] = v;
  }
  delete out.ANTHROPIC_API_KEY;
  // Privacy: chats carry the owner's notebook and files. No error reports, feedback/bug/share uploads
  // or quality surveys (whose "yes" uploads the transcript). Model training is an account setting, not something set here.
  Object.assign(out, CLAUDE_PRIVACY_ENV);
  // The agentbook scripts take their chat from here, so an agent can only reach its own book.
  if (chatId) out.TERMINUS_AGENTBOOK = `claude/${chatId}`;
  // Compact as the context nears this many tokens; unset = Claude's own default.
  if (typeof compactAt === "number" && compactAt > 0) out.CLAUDE_CODE_AUTO_COMPACT_WINDOW = String(Math.floor(compactAt));
  return out;
}

/** Build the argv (never a shell string; prompt is one opaque element).
 *  Pure, unit-tested. */
/** Yolo unless TERMINUS_CLAUDE_YOLO=0. Pure, unit-tested. */
export function claudeYolo(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.TERMINUS_CLAUDE_YOLO !== "0";
}

export function claudeArgv(args: {
  prompt: string;
  model?: string | null;
  resume?: string | null;
  systemPrompt?: string | null;
  /** Reasoning effort (low/medium/high), user-picked per chat. Absent = the model's own default. */
  effort?: string | null;
  /** No permission gating at all (default). False = dontAsk with the allow/deny lists. */
  yolo?: boolean;
  /** Prompt travels on stdin (bare -p): too large for one argv element
   *  (Linux caps a single argument at 128 KB) or starting with "-". */
  viaStdin?: boolean;
  /** With resume: continue on a new session id and leave the source session as it was. */
  forkSession?: boolean;
  /** Long-lived process: user messages arrive on stdin as stream-json lines, one `result` per turn. */
  streamInput?: boolean;
}): string[] {
  const argv = [
    ...(args.streamInput ? ["-p", "--input-format", "stream-json"] : args.viaStdin ? ["-p"] : ["-p", args.prompt]),
    "--output-format", "stream-json",
    "--verbose",
    "--include-partial-messages",
    ...(args.yolo
      ? ["--dangerously-skip-permissions"]
      : [
          "--permission-mode", "dontAsk",
          "--allowedTools", [CLAUDE_ALLOWED_TOOLS, ...claudeBrowserTools()].join(" "),
          "--disallowedTools", CLAUDE_DISALLOWED_TOOLS.join(" "),
        ]),
  ];
  argv.push(...claudeBrowserArgs());
  if (args.model) argv.push("--model", args.model);
  if (args.resume) argv.push("--resume", args.resume);
  if (args.resume && args.forkSession) argv.push("--fork-session");
  argv.push("--append-system-prompt", args.systemPrompt ? `${args.systemPrompt}\n\n${AGENTBOOK_HINT}` : AGENTBOOK_HINT);
  if (args.effort === "low" || args.effort === "medium" || args.effort === "high") argv.push("--effort", args.effort);
  return argv;
}

/** Map provider failures to BackendError codes (pure, unit-tested). */
const CLAUDE_ERROR_RULES: readonly ErrorRule[] = [
  { re: /usage limit|usage_limit|weekly limit|quota|plan.*limit|limit.*reached/, code: "usage_limit", message: "Claude usage limit reached" },
  { re: /rate ?limit|429|overloaded|too many requests/, code: "rate_limited", message: "Claude rate limited", retryable: true },
  { re: /not logged in|\/login|log in|authenticat|unauthorized|forbidden|\b(?:status|http|error)[ :]*(?:401|403)\b|\b(?:401|403) |signin|sign-in|no auth|credit balance|billing/, code: "not_authenticated", message: "Claude authentication required" },
  { re: /no such session|session not found|unknown session|no conversation found|invalid.*resume/, code: "session_not_found", message: "Claude session not found" },
  { re: /enoent|not running|exited|\beof\b|connection closed|stream closed|socket hang up|econnreset|timeout|timed out/, code: "backend_unavailable", message: "Claude unavailable", retryable: true },
];

export function claudeError(e: unknown, operation: string): BackendError {
  return classifyProviderError(e, operation, "claude", "Claude request failed", CLAUDE_ERROR_RULES);
}

export type ClaudeSignal = "SIGINT" | "SIGTERM" | null;

export interface ClaudeResultLine {
  subtype: string;
  text: string;
  sessionId: string;
  isError: boolean;
  /** Short diagnostic (errors array, api status) for failed results with no text. */
  detail?: string;
}

/** Task ids Claude started in the foreground (not jobs); bounded. */
const foregroundTasks = new Set<string>();
function noteForeground(id: string): void {
  if (!id) return;
  foregroundTasks.add(id);
  if (foregroundTasks.size > 500) foregroundTasks.delete(foregroundTasks.values().next().value as string);
}

/** Parse one stream-json line into normalized events + session metadata.
 *  Unknown shapes are ignored (never crash on CLI drift). Pure. */
export function claudeLine(
  line: Record<string, unknown>,
  turnId: string,
): { events: BackendEvent[]; initSessionId?: string; result?: ClaudeResultLine; unknown: boolean } {
  const type = textOf(line.type);
  if (type === "system" && textOf(line.subtype) === "init") {
    const sid = textOf(line.session_id);
    return { events: [], initSessionId: sid || undefined, unknown: false };
  }
  if (type === "system" && textOf(line.subtype) === "status" && textOf(line.status) === "compacting") {
    return { events: [{ type: "compaction", turnId, status: "started" }], unknown: false };
  }
  if (type === "system" && textOf(line.subtype) === "compact_boundary") {
    // Claude compacted (auto or manual): the phone's compaction row, so it never happens silently.
    const trigger = textOf(asRecord(line.compact_metadata).trigger);
    return {
      events: [{ type: "compaction", turnId, status: "ended", ...(trigger === "auto" || trigger === "manual" ? { reason: trigger } : {}) }],
      unknown: false,
    };
  }
  // Background jobs (Bash/Agent with run_in_background): Claude reports both ends as system lines.
  // Foreground tasks (a subagent the reply waits on) report the same lines without is_backgrounded: they
  // are steps of the reply, not jobs, so their finish is dropped too (it used to land as a job with no start
  // and made the app close the chat's stream mid-reply). One moved to the background later counts from then.
  if (type === "system" && textOf(line.subtype) === "task_started") {
    if (line.is_backgrounded !== true) { noteForeground(textOf(line.task_id)); return { events: [], unknown: false }; }
    return { events: [{ type: "background", turnId, status: "started", id: textOf(line.task_id), label: textOf(line.description) || "background job", toolId: textOf(line.tool_use_id) || undefined }], unknown: false };
  }
  if (type === "system" && textOf(line.subtype) === "task_notification") {
    if (foregroundTasks.delete(textOf(line.task_id))) return { events: [], unknown: false };
    const st = textOf(line.status);
    return {
      events: [{
        type: "background", turnId, status: st === "completed" ? "done" : "failed", id: textOf(line.task_id),
        label: textOf(line.description) || "background job", toolId: textOf(line.tool_use_id) || undefined,
        outputFile: textOf(line.output_file) || undefined, summary: textOf(line.summary) || undefined,
      }],
      unknown: false,
    };
  }
  if (type === "system" && textOf(line.subtype) === "task_updated") {
    const id = textOf(line.task_id);
    const patch = asRecord(line.patch);
    if (patch.is_backgrounded === true && foregroundTasks.delete(id)) {
      return { events: [{ type: "background", turnId, status: "started", id, label: textOf(patch.description) || textOf(line.description) || "background job" }], unknown: false };
    }
    return { events: [], unknown: false };
  }
  if (type === "system" && ["background_tasks_changed", "task_progress"].includes(textOf(line.subtype))) {
    return { events: [], unknown: false };
  }
  if (type === "system" && textOf(line.subtype) === "thinking_tokens") {
    // Thinking text is redacted in -p mode; the running token estimate is the only live sign of it.
    if (line.parent_tool_use_id) return { events: [], unknown: false };
    const n = Number(line.estimated_tokens);
    return { events: Number.isFinite(n) && n > 0 ? [{ type: "status", turnId, text: `Thinking · ${Math.round(n)} tokens` }] : [], unknown: false };
  }
  if (type === "stream_event") {
    // A Task sub-agent's streamed text and thinking are not this chat's reply.
    if (line.parent_tool_use_id) return { events: [], unknown: false };
    const ev = asRecord(line.event);
    if (ev.type === "content_block_start") {
      // A tool call can take many seconds to compose (a big Write): say so before its row lands.
      const block = asRecord(ev.content_block);
      if (block.type === "tool_use") return { events: [{ type: "status", turnId, text: `Preparing ${textOf(block.name) || "tool"}…` }], unknown: false };
      if (block.type === "text") return { events: [{ type: "status", turnId, text: "" }], unknown: false };
      return { events: [], unknown: false };
    }
    const delta = asRecord(ev.delta);
    if (delta.type === "text_delta" && textOf(delta.text)) {
      return { events: [{ type: "message.delta", turnId, text: textOf(delta.text) }], unknown: false };
    }
    // Extended thinking streams as thinking_delta; the phone shows it in the reasoning box like codex's.
    if (delta.type === "thinking_delta" && textOf(delta.thinking)) {
      return { events: [{ type: "reasoning.delta", turnId, text: textOf(delta.thinking) }], unknown: false };
    }
    return { events: [], unknown: false };
  }
  if (type === "assistant") {
    const content = asRecord(line.message).content;
    const blocks = Array.isArray(content) ? content : [];
    const events: BackendEvent[] = [];
    for (const b of blocks) {
      const r = asRecord(b);
      if (r.type === "tool_use") {
        events.push({
          type: "tool.started",
          turnId,
          toolId: textOf(r.id) || undefined,
          name: textOf(r.name) || "tool",
          input: r.input,
        });
      }
    }
    return { events, unknown: false };
  }
  if (type === "user") {
    const content = asRecord(line.message).content;
    const blocks = Array.isArray(content) ? content : [];
    const events: BackendEvent[] = [];
    for (const b of blocks) {
      const r = asRecord(b);
      if (r.type === "tool_result") {
        events.push({
          type: "tool.completed",
          turnId,
          toolId: textOf(r.tool_use_id) || undefined,
          output: r.content ?? undefined,
          error: r.is_error ? "tool error" : undefined,
        });
      }
    }
    return { events, unknown: false };
  }
  if (type === "result") {
    return {
      events: [],
      result: {
        subtype: textOf(line.subtype),
        text: textOf(line.result),
        sessionId: textOf(line.session_id),
        isError: line.is_error === true,
        detail: JSON.stringify({ errors: line.errors, status: line.api_error_status, stop: line.stop_reason, turns: line.num_turns }),
      },
      unknown: false,
    };
  }
  return { events: [], unknown: true };
}

/** The provider's own error strings from a failed result's detail (e.g. "No conversation found with session ID"). */
function detailErrors(detail: string | undefined): string {
  if (!detail) return "";
  try {
    const errors = JSON.parse(detail).errors;
    return Array.isArray(errors) ? errors.filter((e) => typeof e === "string").join("; ") : "";
  } catch {
    return "";
  }
}

/** Classify the turn outcome (pure, unit-tested).
 *  - Interrupted by us (SIGINT) → interrupted, even if a result trickles in.
 *  - SIGTERM/death before any result → failed/process_exited (never success).
 *  - result success without error flag → completed; anything else → failed.
 */
export function claudeOutcome(s: {
  interruptedByUs: boolean;
  signaled: ClaudeSignal;
  result?: ClaudeResultLine;
}): { state: "completed" | "interrupted" | "failed"; text: string; code: string } {
  if (s.interruptedByUs || s.signaled === "SIGINT") {
    return { state: "interrupted", text: s.result?.text ?? "", code: "turn_interrupted" };
  }
  if (s.result && (s.result.subtype === "success" || s.result.subtype === "") && !s.result.isError) {
    return { state: "completed", text: s.result.text, code: "" };
  }
  if (s.signaled === "SIGTERM" || !s.result) {
    return { state: "failed", text: "", code: "process_exited" };
  }
  return { state: "failed", text: s.result?.text ?? "", code: "provider_error" };
}

interface ClaudeTurn {
  terminusTurnId: string;
  chatId?: string;
  child: ChildProcess | null;
  lines: LineBuffer;
  emit: EmitBackendEvent | null;
  stash: BackendEvent[];
  canonicalSessionId: string | null;
  initSeen: string | null;
  result: ClaudeResultLine | null;
  interruptedByUs: boolean;
  settled: boolean;
  /** queued until the previous turn on this session reaches terminal. */
  queued: boolean;
  aborted: boolean;
  /** Fresh-run acceptance: resolved with the canonical session id once
   *  stream-json init lands, rejected on spawn error / early death. */
  initWaiters: Array<{ resolve: (id: string) => void; reject: (e: Error) => void }>;
  /** Lifetime gate: the next turn's spawn awaits this. */
  done: Promise<void>;
  resolveDone: () => void;
  /** Last bytes of child stderr (capped). Server-log diagnosis on death
   *  only — never in client-facing errors. */
  stderrTail: string;
  /** Last byte of output from the child (stdout or stderr): the hung-process clock. */
  lastEventAt: number;
  /** Ended by the hung-process watch: the failure says so instead of a bare exit code. */
  stalled: boolean;
  /** Set when the turn runs inside a long-lived process (the default; see claudePersist). */
  persist?: ClaudePersist;
  stopWatch?: () => void;
  /** The start request, kept while queued so a waiting message can still be steered (steerQueued). */
  args?: StartTurnArgs;
  /** Messages folded into this running turn: they settle with it. */
  followers?: ClaudeTurn[];
  /** Set on a steered message's own record: the running turn it was folded into. */
  steeredInto?: ClaudeTurn;
  /** The terminal event, kept so a message steered at the last moment can still settle from it. */
  final?: BackendEvent;
}

/** One resident `claude` process per chat: later turns skip the CLI's startup. Flags that are fixed at
 *  spawn (model, effort, system prompt, compact window) live in `sig`; a change respawns it. */
interface ClaudePersist {
  chatKey: string;
  sig: string;
  child: ChildProcess;
  lines: LineBuffer;
  current: ClaudeTurn | null;
  sessionId: string | null;
  dead: boolean;
  stderrTail: string;
  idle: ReturnType<typeof setTimeout> | null;
  /** A turn Claude started on its own (a background job's result woke it) while no reply was running. */
  auto?: { text: string; at: number; active: boolean } | null;
}

/** Resident process per chat instead of one spawn per turn. On unless TERMINUS_CLAUDE_PERSIST=0. */
/** Newest mtime across the files Claude Code reads once at start: published skills and commands, the user and
 *  project CLAUDE.md. Part of the resident-process signature, so a `make sync` or an edit respawns it. */
export function instructionStamp(cwd?: string | null): number {
  const home = process.env.HOME ?? "";
  let newest = 0;
  const seen = (f: string): void => { try { newest = Math.max(newest, statSync(f).mtimeMs); } catch { /* absent */ } };
  const each = (dir: string, file: (n: string) => string | null): void => {
    try { for (const n of readdirSync(dir)) { const f = file(n); if (f) seen(f); } } catch { /* absent */ }
  };
  each(`${home}/.claude/skills`, (n) => `${home}/.claude/skills/${n}/SKILL.md`);
  each(`${home}/.claude/commands`, (n) => (n.endsWith(".md") ? `${home}/.claude/commands/${n}` : null));
  seen(`${home}/.claude/CLAUDE.md`);
  if (cwd) { seen(`${cwd}/CLAUDE.md`); seen(`${cwd}/AGENTS.md`); }
  return Math.floor(newest);
}

export function claudePersist(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.TERMINUS_CLAUDE_PERSIST !== "0";
}

/** Claude Code keeps each session as ~/.claude/projects/<cwd, every
 *  non-alphanumeric char as "-">/<uuid>.jsonl. Turns always run in
 *  locationDir(), so that is the only project dir listed. */
export function claudeProjectDir(cwd: string = locationDir(), home: string = homedir()): string {
  const root = process.env.CLAUDE_CONFIG_DIR?.trim() || join(home, ".claude");
  return join(root, "projects", cwd.replace(/[^A-Za-z0-9]/g, "-"));
}

/** Every Claude Code project folder on this machine, so a session started from any cwd lists here.
 *  The terminus folder comes first. */
export function claudeSessionDirs(home: string = homedir(), cwd: string = locationDir()): string[] {
  const root = process.env.CLAUDE_CONFIG_DIR?.trim() || join(home, ".claude");
  const projects = join(root, "projects");
  let names: string[];
  try { names = readdirSync(projects); } catch { return [claudeProjectDir(cwd, home)]; }
  const dirs = names.sort().map((n) => join(projects, n));
  return [...new Set([claudeProjectDir(cwd, home), ...dirs])];
}

/** The folder holding a session's transcript, or null. */
export function claudeSessionFile(sessionId: string, dirs: string[] = claudeSessionDirs()): string | null {
  for (const d of dirs) {
    const f = join(d, `${sessionId}.jsonl`);
    if (existsSync(f)) return f;
  }
  return null;
}

/** The cwd Claude Code recorded for a transcript: resume needs the same cwd, or it cannot find the
 *  session. Read from the head only; "" when no line carries one. */
export function claudeSessionCwd(file: string): string {
  let head: string;
  try {
    const buf = Buffer.alloc(65536);
    const fd = openSync(file, "r");
    try { head = buf.subarray(0, readSync(fd, buf, 0, buf.length, 0)).toString("utf8"); }
    finally { closeSync(fd); }
  } catch { return ""; }
  for (const ln of head.split("\n")) {
    try {
      const cwd = (JSON.parse(ln) as { cwd?: unknown }).cwd;
      if (typeof cwd === "string" && cwd) return cwd;
    } catch { /* partial or non-JSON line */ }
  }
  return "";
}

/** Where a turn on this session runs: the session's own cwd, so --resume finds it. New sessions
 *  and sessions whose cwd is gone run in locationDir(). */
export function sessionCwd(sessionId: string | null | undefined): string {
  if (!sessionId || !CLAUDE_SESSION_ID.test(sessionId)) return locationDir();
  const file = claudeSessionFile(sessionId);
  const cwd = file ? claudeSessionCwd(file) : "";
  return cwd && existsSync(cwd) ? cwd : locationDir();
}

const CLAUDE_SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function claudeLineText(content: unknown): string {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .map((b) => (b && typeof b === "object" && (b as Record<string, unknown>).type === "text" ? textOf((b as Record<string, unknown>).text) : ""))
    .filter(Boolean)
    .join("\n");
}

/** Readable user/assistant text rows from one session jsonl. Tool results,
 *  sidechains, meta rows and slash-command echoes are skipped. */
export function claudeTranscript(raw: string): HistoryResult["messages"] {
  const out: HistoryResult["messages"] = [];
  for (const ln of raw.split("\n")) {
    if (!ln) continue;
    let o: Record<string, unknown>;
    try { o = JSON.parse(ln) as Record<string, unknown>; } catch { continue; }
    if ((o.type !== "user" && o.type !== "assistant") || o.isSidechain === true || o.isMeta === true) continue;
    const text = (o.type === "user" ? stripAgentbook(claudeLineText(asRecord(o.message).content)) : claudeLineText(asRecord(o.message).content)).trim();
    if (!text || /^<(command-|local-command-)/.test(text)) continue;
    const at = Date.parse(textOf(o.timestamp));
    // Claude Code writes one jsonl line per content block, and tool calls/results between
    // text blocks are skipped here, so one reply arrives as several assistant rows: join them.
    const prev = out[out.length - 1];
    if (prev && prev.role === "assistant" && o.type === "assistant") { prev.text += "\n\n" + text; continue; }
    out.push({ role: o.type, text, ...(Number.isFinite(at) ? { at } : {}) });
  }
  return out;
}

/** Zero-token session listing: newest first, title = first user line from
 *  the head of the file (cheap; no full read of large transcripts). */
export function claudeSessionList(dir: string, limit: number): RemoteSession[] {
  let names: string[];
  try { names = readdirSync(dir); } catch { return []; }
  // Stat everything (cheap), keep the newest `limit`, and only then read heads: a project
  // with hundreds of sessions must not cost hundreds of synchronous 64 KB reads per request.
  const files: Array<{ id: string; file: string; mtime: number }> = [];
  for (const n of names) {
    const id = n.endsWith(".jsonl") ? n.slice(0, -6) : "";
    if (!CLAUDE_SESSION_ID.test(id)) continue;
    const file = join(dir, n);
    try { files.push({ id, file, mtime: Math.round(statSync(file).mtimeMs) }); } catch { /* vanished: skip */ }
  }
  files.sort((a, b) => b.mtime - a.mtime);
  const rows: RemoteSession[] = [];
  const buf = Buffer.alloc(65536);
  for (const { id, file, mtime } of files.slice(0, Math.max(0, limit))) {
    let title = "";
    try {
      const fd = openSync(file, "r");
      try {
        const n = readSync(fd, buf, 0, buf.length, 0);
        const head = buf.toString("utf8", 0, n);
        const first = claudeTranscript(head.slice(0, head.lastIndexOf("\n") + 1)).find((m) => m.role === "user");
        title = (first?.text ?? "").replace(/\s+/g, " ").slice(0, 80);
      } finally { closeSync(fd); }
    } catch { /* unreadable file: still listed, untitled */ }
    rows.push({ remoteSessionId: id, title, updatedAt: mtime });
  }
  return rows;
}

export type ClaudeSpawnFn = (
  bin: string,
  argv: string[],
  opts: { cwd: string; stdio: string[]; shell: false; env: Record<string, string> },
) => ChildProcess;

/** How long "Send now" waits for the turn ahead to come up when it is still starting. */
const STEER_GRACE_MS = 5_000;

export class ClaudeAdapter implements BackendAdapter {
  readonly id = "claude" as const;
  private turns = new Map<string, ClaudeTurn>();
  private gates = new Map<string, Promise<void>>();
  /** Tool ids of Monitor calls: their backgrounded watch is not a job of yours. */
  private monitorTools = new Set<string>();
  private persists = new Map<string, ClaudePersist>();
  /** The session a chat's first turn created, so a send that raced it joins that session instead of minting another. */
  private chatSessions = new Map<string, string>();
  /** `slash_commands` from the latest system/init: what Claude itself says runs here. */
  private slashCommands: string[] = [];
  private authed = false;
  private bin: string;
  private spawnFn: ClaudeSpawnFn;

  // Resolve from the server-owned PATH plus standard install dirs. The
  // supervised server PATH intentionally omits /usr/local/bin, so a bare
  // `claude` passed to spawn would fail with ENOENT despite the CLI being
  // installed and the descriptor correctly reporting it available.
  constructor(bin = claudeBin() ?? "claude", spawnFn?: ClaudeSpawnFn) {
    this.bin = bin;
    this.spawnFn = spawnFn ?? ((b, a, o) => spawn(liveBin(b), a, {
      cwd: o.cwd,
      stdio: o.stdio as ["ignore", "pipe", "pipe"],
      shell: false,
      env: o.env,
    }));
  }

  descriptor(): BackendDescriptor {
    return claudeDescriptor(claudeAvailable(), this.authed || claudeLoginPresent());
  }

  async shutdown(): Promise<void> {
    const { terminateTree } = await import("../tree-kill.js");
    for (const [, t] of this.turns) {
      try { t.resolveDone(); } catch { /* ignore */ }
      const pid = t.child?.pid;
      t.child = null;
      if (typeof pid === "number") {
        try { await terminateTree(pid); } catch { /* gone */ }
      }
    }
    for (const p of [...this.persists.values()]) {
      p.dead = true;
      if (p.idle) clearTimeout(p.idle);
      const pid = p.child.pid;
      if (typeof pid === "number") { try { await terminateTree(pid); } catch { /* gone */ } }
    }
    this.persists.clear();
    this.turns.clear();
    this.gates.clear();
  }

  /** Settle one turn's lifetime gate (result, death, interrupt, abort). */
  private settleGate(rec: ClaudeTurn): void {
    rec.stopWatch?.();
    if (rec.persist) this.touchPersist(rec.persist);
    try { rec.resolveDone(); } catch { /* already settled */ }
  }

  async listModels(_ctx: BackendContext): Promise<ModelOption[]> {
    return CLAUDE_CATALOG.map((m) => ({ ...m }));
  }

  /** Claude's own slash commands and skills (see claude-commands.ts); they run as ordinary prompts. */
  async listCommands(_ctx: BackendContext): Promise<CommandOption[]> {
    return claudeCommandList(locationDir(), undefined, undefined, this.slashCommands);
  }

  private newTurn(args: StartTurnArgs, done: Promise<void>, resolveDone: () => void): ClaudeTurn {
    return {
      terminusTurnId: args.turnId,
      chatId: args.chatId,
      child: null,
      lines: new LineBuffer(),
      emit: null,
      stash: [],
      canonicalSessionId: args.remoteSessionId ?? null,
      initSeen: null,
      result: null,
      interruptedByUs: false,
      settled: false,
      queued: true,
      aborted: false,
      initWaiters: [],
      done,
      resolveDone,
      stderrTail: "",
      lastEventAt: Date.now(),
      stalled: false,
    };
  }

  async startTurn(args: StartTurnArgs): Promise<StartTurnResult> {
    // Attachments become files on disk named in the prompt (only awaits when there are any, so
    // turns without files keep their synchronous gate order).
    if (args.files?.length) args = { ...args, text: (await promptWithAttachments(args.text, args.files, args.turnId)).text };
    const steered = this.trySteer(args);
    if (steered) return steered;
    if (args.steerRequired) {
      throw new BackendError("turn_interrupted", "the active Claude turn ended before this message could be steered", true, "claude", "steer");
    }
    // Lifetime gate per session: this turn's SPAWN awaits the previous
    // turn's terminal state — acceptance below stays fast. Keyed by chat
    // (stable across fresh/resumed), not by remote id.
    const key = `claude:${args.chatId}`;
    const prevDone = this.gates.get(key) ?? Promise.resolve();
    let resolveDone!: () => void;
    const done = new Promise<void>((r) => { resolveDone = r; });
    this.gates.set(key, prevDone.then(() => done));
    const rec = this.newTurn(args, done, resolveDone);
    rec.args = args;
    this.turns.set(args.turnId, rec);
    if (rec.canonicalSessionId) {
      // Session known: accept immediately, spawn in the background after
      // the previous turn terminals. The client already owns the id.
      const accept: StartTurnResult = { accepted: true, remoteSessionId: rec.canonicalSessionId, remoteTurnId: null };
      void this.runSpawn(rec, args, prevDone);
      return accept;
    }
    // Fresh: no session exists yet, so spawn now (gate is trivially open
    // — no previous turn can exist without a session id to resume).
    // Acceptance still waits only for stream-json init (process startup).
    await prevDone;
    if (rec.aborted) {
      this.forgetTurn(rec);
      throw new BackendError("turn_interrupted", "turn cancelled before start", false, "claude", "startTurn");
    }
    const known = this.chatSessions.get(key);
    if (known) {
      // A first send that raced another one: the session exists now, so continue it.
      args = { ...args, remoteSessionId: known };
      rec.args = args;
      rec.canonicalSessionId = known;
      void this.runSpawn(rec, args, Promise.resolve());
      return { accepted: true, remoteSessionId: known, remoteTurnId: null };
    }
    rec.queued = false;
    try {
      const child = this.launch(rec, args);
      const sessionId = await this.awaitInit(rec, child);
      return { accepted: true, remoteSessionId: sessionId, remoteTurnId: null };
    } catch (e) {
      this.forgetTurn(rec);
      throw e;
    }
  }

  /** Background spawn for the resume path: gate, abort check, spawn. */
  private async runSpawn(rec: ClaudeTurn, args: StartTurnArgs, prevDone: Promise<void>): Promise<void> {
    await prevDone;
    if (rec.steeredInto) return; // folded into the running turn while it waited: it never starts its own
    if (rec.aborted) {
      this.forgetTurn(rec);
      return;
    }
    rec.queued = false;
    try {
      this.launch(rec, args);
    } catch (e) {
      const mapped = e instanceof BackendError ? e : claudeError(e, "startTurn");
      console.log(`claude launch failed: turn=${rec.terminusTurnId} ${safeMessage(e instanceof Error ? e.stack || e.message : String(e)).slice(0, 500)}`);
      rec.settled = true;
      this.settleGate(rec);
      this.emitTo(rec, { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: mapped.message, retryable: mapped.retryable });
    }
  }

  private emitTo(rec: ClaudeTurn, e: BackendEvent): void {
    if (rec.emit) {
      try { rec.emit(e); } catch { /* local */ }
    } else if (rec.stash.length < 200) {
      rec.stash.push(e);
    }
  }

  /** Start the turn's process, or hand it to the chat's resident one. */
  private launch(rec: ClaudeTurn, args: StartTurnArgs): ChildProcess {
    if (!claudePersist()) {
      const child = this.spawnChild(args);
      this.wireChild(rec, child);
      return child;
    }
    const sig = JSON.stringify([args.model ?? "", args.effort ?? "", args.systemPrompt ?? "", args.compactAt ?? 0, claudeYolo(), instructionStamp(args.cwd)]);
    const chatKey = String(args.chatId);
    let p = this.persists.get(chatKey);
    if (p && (p.dead || p.sig !== sig || p.sessionId !== (args.remoteSessionId ?? null))) { this.dropPersist(p); p = undefined; }
    if (!p) {
      this.evictIdle();
      p = this.spawnPersist(chatKey, sig, args);
    }
    p.current = rec;
    rec.persist = p;
    rec.child = p.child;
    rec.lines = p.lines;
    rec.lastEventAt = Date.now();
    const child = p.child;
    rec.stopWatch = watchStall(() => rec.lastEventAt, () => {
      rec.stalled = true;
      const pid = child.pid;
      if (typeof pid === "number") void import("../tree-kill.js").then((m) => m.terminateTree(pid)).catch(() => {});
    });
    this.touchPersist(p);
    try {
      child.stdin?.write(JSON.stringify({ type: "user", message: { role: "user", content: args.text } }) + "\n");
    } catch (e) {
      throw claudeError(e, "startTurn");
    }
    return child;
  }

  private spawnPersist(chatKey: string, sig: string, args: StartTurnArgs): ClaudePersist {
    const argv = claudeArgv({
      prompt: "",
      model: args.model,
      resume: args.remoteSessionId ?? undefined,
      systemPrompt: args.systemPrompt ?? undefined,
      effort: args.effort ?? undefined,
      yolo: claudeYolo(),
      streamInput: true,
    });
    let child: ChildProcess;
    try {
      child = this.spawnFn(this.bin, argv, { cwd: sessionCwd(args.remoteSessionId), stdio: ["pipe", "pipe", "pipe"], shell: false, env: claudeEnv(args.compactAt, args.chatId) });
    } catch (e) {
      throw claudeError(e, "startTurn");
    }
    child.stdin?.on("error", () => { /* child gone: its exit reports it */ });
    const p: ClaudePersist = { chatKey, sig, child, lines: new LineBuffer(), current: null, sessionId: args.remoteSessionId ?? null, dead: false, stderrTail: "", idle: null };
    this.persists.set(chatKey, p);
    child.stdout?.on("data", (c: Buffer) => {
      const t = p.current;
      const live = !!t && !t.settled;
      // Claude's own turn keeps the stream until its result, even if a reply was sent meanwhile (Claude runs
      // the queued message after it); what follows the result belongs to that reply.
      if (!live || this.autoBusy(p)) {
        const rest = this.betweenTurns(p, args.chatId, c);
        if (rest && t && !t.settled) { t.lastEventAt = Date.now(); this.onData(t, Buffer.from(rest)); }
        return;
      }
      t!.lastEventAt = Date.now();
      this.onData(t!, c);
    });
    child.stderr?.on("data", (c: Buffer) => {
      if (p.current) p.current.lastEventAt = Date.now();
      p.stderrTail = (p.stderrTail + c.toString()).slice(-8192);
    });
    child.on("error", (e) => this.persistDied(p, e instanceof Error ? e : new Error(String(e))));
    child.on("exit", (code, signal) =>
      this.persistDied(p, code === 0 && p.current?.result ? null : new Error(`claude exited code=${code} signal=${signal ?? "none"}`)),
    );
    return p;
  }

  /** The resident process ended: forget it and settle whatever turn was on it. */
  private persistDied(p: ClaudePersist, err: Error | null): void {
    if (p.dead) return;
    p.dead = true;
    if (p.idle) clearTimeout(p.idle);
    if (this.persists.get(p.chatKey) === p) this.persists.delete(p.chatKey);
    const t = p.current;
    p.current = null;
    if (t && !t.settled) {
      t.stderrTail = p.stderrTail;
      this.onDeath(t, err);
    }
  }

  /** Close a resident process (idle, config changed, session compacted or deleted). */
  private dropPersist(p: ClaudePersist): void {
    if (p.dead) return;
    const busy = p.current && !p.current.settled;
    const pid = p.child.pid;
    this.persistDied(p, busy ? new Error("claude session ended") : null);
    try { p.child.stdin?.end(); } catch { /* gone */ }
    if (typeof pid === "number") void import("../tree-kill.js").then((m) => m.terminateTree(pid)).catch(() => {});
  }

  private dropPersistFor(sessionId: string): void {
    for (const p of [...this.persists.values()]) if (p.sessionId === sessionId) this.dropPersist(p);
  }

  /** Idle processes go after 10 min (TERMINUS_CLAUDE_IDLE_MS); a running turn is never touched. */
  private touchPersist(p: ClaudePersist): void {
    if (p.idle) clearTimeout(p.idle);
    const ms = Number(process.env.TERMINUS_CLAUDE_IDLE_MS) || 600_000;
    p.idle = setTimeout(() => { if (!p.current || p.current.settled) this.dropPersist(p); }, ms);
    p.idle.unref?.();
  }

  /** Cap on resident processes (TERMINUS_CLAUDE_MAX_PROCS, default 4): make room by closing idle ones, oldest first. */
  private evictIdle(): void {
    const max = Number(process.env.TERMINUS_CLAUDE_MAX_PROCS) || 4;
    for (const p of [...this.persists.values()]) {
      if (this.persists.size < max) break;
      if (!p.current || p.current.settled) this.dropPersist(p);
    }
  }

  private spawnChild(args: StartTurnArgs): ChildProcess {
    // Always stdin: the prompt never shows in `ps`, and size or a leading "-" can't break argv.
    const viaStdin = true;
    const argv = claudeArgv({
      prompt: args.text,
      model: args.model,
      resume: args.remoteSessionId ?? undefined,
      systemPrompt: args.systemPrompt ?? undefined,
      effort: args.effort ?? undefined,
      yolo: claudeYolo(),
      viaStdin,
    });
    try {
      const child = this.spawnFn(this.bin, argv, {
        cwd: sessionCwd(args.remoteSessionId),
        stdio: [viaStdin ? "pipe" : "ignore", "pipe", "pipe"],
        shell: false,
        env: claudeEnv(args.compactAt, args.chatId),
      });
      if (viaStdin) {
        child.stdin?.on("error", () => { /* child gone: its exit reports it */ });
        child.stdin?.end(args.text);
      }
      return child;
    } catch (e) {
      throw claudeError(e, "startTurn");
    }
  }

  private wireChild(rec: ClaudeTurn, child: ChildProcess): void {
    rec.child = child;
    // Hung-process watch: an hour with no output at all (TERMINUS_STALL_MS) ends the process tree and
    // fails the turn with a clear error, instead of leaving it "running" until someone taps Stop.
    rec.lastEventAt = Date.now();
    const stopWatch = watchStall(() => rec.lastEventAt, () => {
      rec.stalled = true;
      const pid = child.pid;
      if (typeof pid === "number") void import("../tree-kill.js").then((m) => m.terminateTree(pid)).catch(() => {});
    });
    child.on("exit", stopWatch);
    child.on("error", stopWatch);
    child.stdout?.on("data", (c: Buffer) => { rec.lastEventAt = Date.now(); this.onData(rec, c); });
    child.stderr?.on("data", (c: Buffer) => {
      rec.lastEventAt = Date.now();
      rec.stderrTail += c.toString();
      if (rec.stderrTail.length > 8192) rec.stderrTail = rec.stderrTail.slice(-8192);
    });
    child.on("error", (e) => this.onDeath(rec, e instanceof Error ? e : new Error(String(e))));
    child.on("exit", (code, signal) =>
      this.onDeath(rec, code === 0 && rec.result ? null : new Error(`claude exited code=${code} signal=${signal ?? "none"}`)),
    );
  }

  private awaitInit(rec: ClaudeTurn, child: ChildProcess): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        const pid = child.pid;
        if (typeof pid === "number") void import("../tree-kill.js").then((m) => m.terminateTree(pid)).catch(() => { try { child.kill("SIGTERM"); } catch { /* gone */ } });
        else { try { child.kill("SIGTERM"); } catch { /* gone */ } }
        // Never even printed its init line: the program itself did not start (macOS can leave one binary path
        // blocked, which hung every turn on 2026-11-18). Say so, instead of a generic timeout.
        reject(new BackendError("backend_unavailable", "Claude didn't start within 60s. The claude program itself may be stuck: run `claude --version` in a terminal; if that hangs, re-point /usr/local/bin/claude at a copy of the binary.", true, "claude", "startTurn"));
      }, 60_000);
      rec.initWaiters.push({
        resolve: (id) => { clearTimeout(timer); resolve(id); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      child.on("exit", () => {
        clearTimeout(timer);
        reject(claudeError(new Error("claude exited before init"), "startTurn"));
      });
    });
  }
  /** The running turn on this chat that can take another message: resident process, not ended, nothing queued
   *  ahead of it (order must hold). */
  private steerTarget(chatId: string, sessionId: string | null, self?: ClaudeTurn): ClaudeTurn | null {
    let target: ClaudeTurn | null = null;
    let behindSelf = false;
    for (const r of this.turns.values()) {
      if (r === self) { behindSelf = true; continue; }
      if (r.chatId !== chatId || r.steeredInto || r.settled || r.aborted) continue;
      // Only a message queued ahead blocks; ones behind `self` keep waiting their own turn.
      if (r.queued) { if (behindSelf) continue; return null; }
      if (r.persist && !r.persist.dead) target = r;
    }
    if (!target || (sessionId && target.canonicalSessionId !== sessionId)) return null;
    return target;
  }

  /** Write a message into the running turn; the CLI hands it to the model at the next tool boundary and the
   *  turn ends once, with both folded in. */
  private writeSteer(target: ClaudeTurn, text: string): boolean {
    try {
      target.persist?.child.stdin?.write(JSON.stringify({ type: "user", message: { role: "user", content: text } }) + "\n");
      target.lastEventAt = Date.now();
      return true;
    } catch {
      return false;
    }
  }

  private follow(target: ClaudeTurn, f: ClaudeTurn): void {
    f.steeredInto = target;
    f.queued = false;
    (target.followers ??= []).push(f);
    if (target.final) this.settleFollower(f, target.final);
  }

  /** Send a message into the turn running right now instead of queueing it. Null whenever steering is not asked
   *  for or not possible, and the caller takes the normal turn-by-turn path. */
  private trySteer(args: StartTurnArgs): StartTurnResult | null {
    if (!args.steer || !claudePersist()) return null;
    const target = this.steerTarget(args.chatId, args.remoteSessionId ?? null);
    if (!target || !this.writeSteer(target, args.text)) return null;
    const f = this.newTurn(args, Promise.resolve(), () => {});
    this.turns.set(args.turnId, f);
    this.follow(target, f);
    console.log(`claude steer: chat=${args.chatId} turn=${args.turnId} into=${target.terminusTurnId}`);
    return { accepted: true, remoteSessionId: target.canonicalSessionId ?? "", remoteTurnId: null, steered: true };
  }

  /** "Send now" on a message already waiting behind the running turn: fold it into that turn. True also when
   *  nothing is left waiting (it already started or was folded), so the app never says "stays in line" about
   *  a message that is going. A turn ahead that is still starting gets a short grace to come up. False = it
   *  is still queued. */
  async steerQueued(chatId: string, turnId: string): Promise<boolean> {
    const rec = this.turns.get(turnId);
    const no = (why: string) => { console.log(`claude steer refused: chat=${chatId} turn=${turnId} ${why}`); return false; };
    if (!rec || rec.chatId !== chatId) return no("unknown turn");
    if (rec.aborted) return no("aborted");
    if (!rec.queued || rec.steeredInto) return true;
    if (!claudePersist()) return no("persist off");
    if (!rec.args) return no("no start request");
    let target = this.steerTarget(chatId, rec.canonicalSessionId, rec);
    // Grace only for a turn ahead whose CLI is still spawning; a message queued ahead keeps its place at once.
    const starting = () => [...this.turns.values()].some((r) => r !== rec && r.chatId === chatId && !r.queued
      && !r.settled && !r.aborted && !r.steeredInto && !r.persist);
    for (let waited = 0; !target && starting() && waited < STEER_GRACE_MS; waited += 100) {
      await new Promise((r) => setTimeout(r, 100));
      if (rec.aborted) return no("aborted");
      if (!rec.queued || rec.steeredInto) return true;
      target = this.steerTarget(chatId, rec.canonicalSessionId, rec);
    }
    if (!target) return no(`no steerable turn (session=${rec.canonicalSessionId ?? "none"})`);
    if (!this.writeSteer(target, rec.args.text)) return no("stdin write failed");
    this.follow(target, rec);
    console.log(`claude steer: chat=${chatId} turn=${turnId} into=${target.terminusTurnId} (was waiting)`);
    return true;
  }

  /** A steered message ends exactly when the turn it was folded into ends, with the same outcome. */
  private settleFollower(f: ClaudeTurn, parentFinal: BackendEvent): void {
    if (f.settled) return;
    f.settled = true;
    const parent = f.steeredInto;
    f.result = parent?.result ?? null;
    f.interruptedByUs = parent?.interruptedByUs ?? false;
    const ev = { ...parentFinal, turnId: f.terminusTurnId } as BackendEvent;
    f.final = ev;
    this.deliver(f, ev);
    f.emit = null;
    this.settleGate(f); // a message promoted from the queue holds the chat's gate until it settles
    setTimeout(() => { if (this.turns.get(f.terminusTurnId) === f) this.turns.delete(f.terminusTurnId); }, 600_000).unref?.();
  }

  private settleFollowers(rec: ClaudeTurn, terminal: BackendEvent): void {
    rec.final = terminal;
    for (const f of rec.followers ?? []) this.settleFollower(f, terminal);
    rec.followers = undefined;
  }

  /** Cancel a queued (not-yet-spawned) turn: no process ever starts. */
  async abortQueued(turnId: string): Promise<boolean> {
    const rec = this.turns.get(turnId);
    if (!rec || !rec.queued) return false;
    rec.aborted = true;
    this.forgetTurn(rec);
    return true;
  }

  /** Forget a record and release its lifetime gate. */
  private forgetTurn(rec: ClaudeTurn): void {
    this.turns.delete(rec.terminusTurnId);
    this.settleGate(rec);
  }

  async stream(args: StreamArgs, emit: EmitBackendEvent): Promise<void> {
    const rec = this.turns.get(args.turnId);
    if (!rec) throw new BackendError("session_not_found", "unknown turn", false, "claude", "stream");
    rec.emit = emit;
    emit({ type: "turn.started", turnId: args.turnId, remoteSessionId: rec.canonicalSessionId ?? args.remoteSessionId });
    for (const e of rec.stash) {
      try { emit(e); } catch { /* local */ }
    }
    rec.stash = [];
    if (rec.settled) rec.emit = null;
  }

  /** Stop one background task in a chat: Claude's own stop_task control request on the chat's resident
   *  process (the CLI's task panel sends the same). Resolves with Claude's answer, or a reason. */
  async stopTask(chatId: string, taskId: string): Promise<{ ok: boolean; reason?: string }> {
    const p = this.persists.get(String(chatId));
    const stdin = p && !p.dead ? p.child.stdin : null;
    if (!p || !stdin) return { ok: false, reason: "Claude is no longer running for this chat" };
    const requestId = randomUUID();
    return new Promise((resolve) => {
      let buf = "";
      let finished = false;
      const done = (r: { ok: boolean; reason?: string }): void => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        p.child.stdout?.off("data", onData);
        resolve(r);
      };
      const onData = (c: Buffer): void => {
        buf = (buf + c.toString()).slice(-200_000);
        const at = buf.indexOf(requestId);
        if (at < 0) return;
        const end = buf.indexOf("\n", at);
        if (end < 0) return;
        let o: unknown;
        try { o = JSON.parse(buf.slice(buf.lastIndexOf("\n", at) + 1, end)); } catch { done({ ok: false, reason: "unreadable answer" }); return; }
        const r = asRecord(asRecord(o).response);
        if (textOf(r.subtype) === "success") done({ ok: true });
        else done({ ok: false, reason: (textOf(r.error) || "refused").slice(0, 160) });
      };
      const timer = setTimeout(() => done({ ok: false, reason: "no answer from Claude" }), 5000);
      timer.unref?.();
      p.child.stdout?.on("data", onData);
      try {
        stdin.write(JSON.stringify({ type: "control_request", request_id: requestId, request: { subtype: "stop_task", task_id: taskId } }) + "\n");
      } catch { done({ ok: false, reason: "could not reach Claude" }); }
    });
  }

  async interruptTurn(args: InterruptTurnArgs): Promise<InterruptResult> {
    const rec = this.turns.get(args.turnId);
    if (!rec || rec.settled) return { interrupted: false, reason: "unknown turn" };
    if (rec.steeredInto) return this.interruptTurn({ ...args, turnId: rec.steeredInto.terminusTurnId });
    if (!rec.child) {
      // Queued, never spawned: cancel without a signal.
      rec.interruptedByUs = true;
      this.forgetTurn(rec);
      return { interrupted: true };
    }
    rec.interruptedByUs = true;
    const p = rec.persist;
    if (p) {
      // A resident process must survive the stop: ask it to interrupt, and end it only if it never answers.
      try {
        p.child.stdin?.write(JSON.stringify({ type: "control_request", request_id: randomUUID(), request: { subtype: "interrupt" } }) + "\n");
      } catch { return { interrupted: false, reason: "signal failed" }; }
      setTimeout(() => {
        const pid = p.child.pid;
        if (!rec.settled && rec.persist === p && typeof pid === "number") void import("../tree-kill.js").then((m) => m.terminateTree(pid)).catch(() => {});
      }, 5000).unref?.();
      return { interrupted: true };
    }
    try {
      rec.child.kill("SIGINT");
      return { interrupted: true };
    } catch {
      return { interrupted: false, reason: "signal failed" };
    }
  }

  async killTurn(args: InterruptTurnArgs): Promise<InterruptResult> {
    const rec = this.turns.get(args.turnId);
    if (!rec || rec.settled) return { interrupted: false, reason: "unknown turn" };
    if (rec.steeredInto) return this.killTurn({ ...args, turnId: rec.steeredInto.terminusTurnId });
    if (!rec.child) {
      rec.interruptedByUs = true;
      this.forgetTurn(rec);
      return { interrupted: true, reason: "killed" };
    }
    // Whole-tree SIGTERM→SIGKILL escalation: the CLI's tool grandchildren
    // must not survive the turn. Never throws.
    try {
      const { terminateTree } = await import("../tree-kill.js");
      const pid = rec.child.pid;
      rec.child = null;
      if (typeof pid === "number") await terminateTree(pid);
      return { interrupted: true, reason: "killed" };
    } catch {
      return { interrupted: false, reason: "signal failed" };
    }
  }

  async getStatus(args: StatusArgs): Promise<BackendStatus> {
    const rec = this.turns.get(args.turnId);
    if (!rec) return { state: "unknown" };
    if (rec.queued) return { state: "waiting" };
    if (!rec.settled) return { state: "working" };
    const out = claudeOutcome({ interruptedByUs: rec.interruptedByUs, signaled: null, result: rec.result ?? undefined });
    return { state: out.state === "completed" ? "completed" : out.state === "interrupted" ? "interrupted" : "failed" };
  }

  async resumeSession(args: ResumeSessionArgs): Promise<ResumeSessionResult> {
    if (!args.remoteSessionId) {
      throw new BackendError("invalid_request", "missing session", false, "claude", "resumeSession");
    }
    // No spend-free resume probe exists; the id is validated when the next
    // turn runs with --resume (failure surfaces as turn.failed, fail-closed).
    return { ok: true, remoteSessionId: args.remoteSessionId };
  }

  async listSessions(args: { limit?: number }): Promise<RemoteSession[]> {
    const limit = args.limit ?? 200;
    // Only sessions Terminus started, resumed or forked; Claude Code's own and scheduled runs stay out.
    const owned = readOwned("claude");
    const rows = claudeSessionDirs()
      .flatMap((d) => claudeSessionList(d, limit))
      .filter((r) => owned.has(r.remoteSessionId))
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
      .slice(0, limit);
    const own = readClaudeTitles();
    return rows.map((r) => (own[r.remoteSessionId] ? { ...r, title: own[r.remoteSessionId] } : r));
  }
  async history(args: HistoryArgs): Promise<HistoryResult> {
    if (!CLAUDE_SESSION_ID.test(args.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "claude", "history");
    }
    const file = claudeSessionFile(args.remoteSessionId);
    if (!file) throw new BackendError("session_not_found", "no such session", false, "claude", "history");
    let raw: string;
    try { raw = readFileSync(file, "utf8"); }
    catch { throw new BackendError("session_not_found", "no such session", false, "claude", "history"); }
    return { messages: claudeTranscript(raw).slice(-(args.limit ?? 200)) };
  }
  /** A title set from the phone wins; else one Claude Code stored in the transcript; else "". */
  async getTitle(args: SessionArgs): Promise<string> {
    if (!CLAUDE_SESSION_ID.test(args.remoteSessionId)) return "";
    const own = readClaudeTitles()[args.remoteSessionId];
    if (own) return own;
    const file = claudeSessionFile(args.remoteSessionId);
    if (!file) return "";
    try { return claudeSessionTitle(readFileSync(file, "utf8")); }
    catch { return ""; }
  }
  /** Stored by Terminus beside its other state; Claude's own files are never edited. */
  async setTitle(args: SessionArgs & { title: string }): Promise<void> {
    if (!CLAUDE_SESSION_ID.test(args.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "claude", "setTitle");
    }
    try { setClaudeTitle(args.remoteSessionId, args.title); }
    catch { throw new BackendError("provider_error", "could not save the title", false, "claude", "setTitle"); }
  }
  /** Manual compact: one headless `/compact` on the resumed session. Refused while a reply is running. */
  async compact(args: SessionArgs): Promise<void> {
    if (!CLAUDE_SESSION_ID.test(args.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "claude", "compact");
    }
    for (const t of this.turns.values()) {
      if (!t.settled && !t.aborted && (t.canonicalSessionId === args.remoteSessionId || (args.chatId && t.chatId === args.chatId))) {
        throw new BackendError("invalid_request", "a reply is still running", false, "claude", "compact");
      }
    }
    this.dropPersistFor(args.remoteSessionId);
    const key = `claude:${args.chatId}`;
    const prevDone = args.chatId ? (this.gates.get(key) ?? Promise.resolve()) : Promise.resolve();
    let release!: () => void;
    const done = new Promise<void>((r) => { release = r; });
    if (args.chatId) this.gates.set(key, prevDone.then(() => done));
    try {
      await prevDone;
      await this.runCompact(args.remoteSessionId);
    } finally {
      release();
    }
  }
  private runCompact(sessionId: string): Promise<void> {
    const argv = claudeArgv({ prompt: "/compact", resume: sessionId, yolo: claudeYolo(), viaStdin: true });
    return new Promise<void>((resolve, reject) => {
      let child: ChildProcess;
      try {
        child = this.spawnFn(this.bin, argv, { cwd: sessionCwd(sessionId), stdio: ["pipe", "pipe", "pipe"], shell: false, env: claudeEnv() });
      } catch (e) {
        reject(claudeError(e, "compact"));
        return;
      }
      let out = "";
      child.stdin?.on("error", () => { /* child gone: its exit reports it */ });
      child.stdin?.end("/compact");
      child.stdout?.on("data", (c: Buffer) => { out = (out + c.toString()).slice(-1_000_000); });
      child.stderr?.on("data", () => { /* diagnosis only; never sent to the client */ });
      const timer = setTimeout(() => {
        try { child.kill("SIGTERM"); } catch { /* gone */ }
        reject(claudeError(new Error("compact timed out"), "compact"));
      }, 180_000);
      child.on("error", (e) => { clearTimeout(timer); reject(claudeError(e, "compact")); });
      child.on("exit", (code) => {
        clearTimeout(timer);
        const r = claudeCompactOutcome(out, code);
        if (r.ok) resolve();
        else reject(new BackendError("provider_error", r.reason, false, "claude", "compact"));
      });
    });
  }
  /** Forget a session by removing its transcript (the same file list and history read). Gone already = done. */
  async deleteSession(args: SessionArgs): Promise<void> {
    if (!CLAUDE_SESSION_ID.test(args.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "claude", "deleteSession");
    }
    this.dropPersistFor(args.remoteSessionId);
    if (args.chatId) forgetBook("claude", String(args.chatId));
    for (const [k, v] of this.chatSessions) if (v === args.remoteSessionId) this.chatSessions.delete(k);
    const file = claudeSessionFile(args.remoteSessionId);
    if (!file) return;
    try {
      unlinkSync(file);
    } catch (e) {
      if ((e as NodeJS.ErrnoException)?.code === "ENOENT") return;
      throw new BackendError("provider_error", "could not delete the session file", false, "claude", "deleteSession");
    }
  }
  /** Fork on a new session id, falling back to a transcript copy if the CLI doesn't persist its fork. */
  async fork(args: ForkArgs): Promise<ForkResult> {
    const src = args.remoteSessionId;
    if (!CLAUDE_SESSION_ID.test(src)) {
      throw new BackendError("invalid_request", "bad session id", false, "claude", "fork");
    }
    const srcFile = claudeSessionFile(src);
    if (!srcFile) throw new BackendError("session_not_found", "no such session", false, "claude", "fork");
    const dir = dirname(srcFile);
    let raw: string;
    try { raw = readFileSync(srcFile, "utf8"); }
    catch { throw new BackendError("session_not_found", "no such session", false, "claude", "fork"); }
    let id = await this.forkViaCli(src);
    if (!CLAUDE_SESSION_ID.test(id) || id === src) id = randomUUID();
    if (!existsSync(join(dir, `${id}.jsonl`))) {
      try { writeFileSync(join(dir, `${id}.jsonl`), claudeForkTranscript(raw, src, id), { flag: "wx" }); }
      catch { throw new BackendError("provider_error", "could not copy the session", false, "claude", "fork"); }
    }
    markOwned("claude", id);
    return { remoteSessionId: id };
  }
  private forkViaCli(sessionId: string): Promise<string> {
    const argv = claudeArgv({ prompt: "/context", resume: sessionId, yolo: claudeYolo(), viaStdin: true, forkSession: true });
    return new Promise<string>((resolve) => {
      let child: ChildProcess;
      try {
        child = this.spawnFn(this.bin, argv, { cwd: sessionCwd(sessionId), stdio: ["pipe", "pipe", "pipe"], shell: false, env: claudeEnv() });
      } catch { resolve(""); return; }
      let out = "";
      child.stdin?.on("error", () => { /* child gone */ });
      child.stdin?.end("/context");
      child.stdout?.on("data", (c: Buffer) => { out = (out + c.toString()).slice(-1_000_000); });
      child.stderr?.on("data", () => { /* diagnosis only */ });
      const timer = setTimeout(() => { try { child.kill("SIGTERM"); } catch { /* gone */ } resolve(""); }, 60_000);
      child.on("error", () => { clearTimeout(timer); resolve(""); });
      child.on("exit", () => { clearTimeout(timer); resolve(claudeInitSessionId(out)); });
    });
  }

  /** True while Claude is producing a turn of its own (bounded: a turn that never ends lets go after 10 min). */
  private autoBusy(p: ClaudePersist): boolean {
    const a = p.auto;
    if (!a) return false;
    if (Date.now() - a.at > 10 * 60_000) { p.auto = null; return false; }
    return a.active;
  }

  /** Output with no reply running: Claude woke itself, usually because a background job ended. Its job
   *  events go to the chat's background log; its reply lands in the chat as a message (inbox kind "reply",
   *  so the phone notifies like any reply, app open or not). Returns any output after that turn's result,
   *  which belongs to the reply that was sent meanwhile. */
  private betweenTurns(p: ClaudePersist, chatId: string | undefined, chunk: Buffer): string {
    const lines = p.lines.push(chunk);
    for (let i = 0; i < lines.length; i++) {
      let obj: Record<string, unknown>;
      try { obj = JSON.parse(lines[i]) as Record<string, unknown>; } catch { continue; }
      for (const e of claudeLine(obj, "").events) {
        if (e.type !== "background" || !chatId || (e.toolId && this.monitorTools.has(e.toolId))) continue;
        publishBackground(chatId, { status: e.status, id: e.id, ...claudeBackgroundFields(e) });
      }
      const type = textOf(obj.type);
      if (!p.auto) p.auto = { text: "", at: Date.now(), active: false };
      const own = !obj.parent_tool_use_id;
      if (own && (type === "assistant" || type === "stream_event") && !p.auto.active) { p.auto.active = true; p.auto.at = Date.now(); }
      if (own && type === "assistant") {
        const content = asRecord(obj.message).content;
        const text = Array.isArray(content) ? content.map((b) => (asRecord(b).type === "text" ? textOf(asRecord(b).text) : "")).join("") : "";
        if (text.trim()) p.auto.text = text;
      }
      if (type === "result") {
        const a = p.auto;
        p.auto = null;
        const final = (typeof obj.result === "string" && obj.result.trim() ? obj.result : a.text).trim();
        if (a.active && final && chatId && final !== "SILENT") {
          void appendInbox("reply", "", final, chatId, { kind: "reply" }).catch(() => null);
          console.log(`claude: own turn in chat=${chatId} delivered (${final.length} chars)`);
        }
        const rest = lines.slice(i + 1);
        const tail = p.lines.drain();
        return rest.length || tail ? (rest.length ? rest.join("\n") + "\n" : "") + tail : "";
      }
    }
    return "";
  }

  private onData(rec: ClaudeTurn, chunk: Buffer): void {
    for (const line of rec.lines.push(chunk)) {
      let obj: Record<string, unknown>;
      try {
        obj = JSON.parse(line) as Record<string, unknown>;
      } catch {
        continue;
      }
      const { events, initSessionId, result } = claudeLine(obj, rec.terminusTurnId);
      // Diagnostic: some background jobs reach the app with a finish but no start. Log task starts Claude
      // doesn't mark as backgrounded, to see what it sends in that case.
      if (obj.type === "system" && obj.subtype === "task_started" && obj.is_backgrounded !== true) {
        console.log(`claude task_started not backgrounded: chat=${rec.chatId ?? "?"} task=${textOf(obj.task_id)} type=${textOf(obj.task_type)} desc=${textOf(obj.description).slice(0, 60)}`);
      }
      const reported = claudeInitSlashCommands(obj);
      if (reported.length) this.slashCommands = reported;
      if (rec.chatId) recordClaudeLine(rec.chatId, obj as Record<string, unknown>);
      if (initSessionId) {
        rec.initSeen = initSessionId;
        markOwned("claude", initSessionId);
        // Fresh runs adopt the init id; resumed runs keep the stored one.
        // Late init (after resume) never overwrites the canonical id.
        if (!rec.canonicalSessionId) {
          rec.canonicalSessionId = initSessionId;
          if (rec.chatId) {
            this.chatSessions.set(`claude:${rec.chatId}`, initSessionId);
            if (this.chatSessions.size > 500) this.chatSessions.delete(this.chatSessions.keys().next().value as string);
          }
          this.authed = true;
        }
        const waiters = rec.initWaiters.splice(0);
        for (const w of waiters) {
          try { w.resolve(rec.canonicalSessionId); } catch { /* local */ }
        }
      }
      if (rec.persist && rec.canonicalSessionId) rec.persist.sessionId = rec.canonicalSessionId;
      if (result) {
        rec.result = result;
        if (result.sessionId && !rec.canonicalSessionId) rec.canonicalSessionId = result.sessionId;
        this.settle(rec);
        continue;
      }
      for (const e of events) {
        // Monitor watches are the agent waiting on its own job: no background row for them.
        if (e.type === "tool.started" && e.name === "Monitor" && e.toolId) {
          this.monitorTools.add(e.toolId);
          if (this.monitorTools.size > 500) this.monitorTools.delete(this.monitorTools.values().next().value as string);
        }
        if (e.type === "background" && e.toolId && this.monitorTools.has(e.toolId)) continue;
        this.deliver(rec, e);
      }
    }
  }

  private deliver(rec: ClaudeTurn, e: BackendEvent): void {
    // A job that ends after its turn settled has no turn to ride: log it for the chat directly.
    if (!rec.emit && e.type === "background" && rec.chatId) {
      publishBackground(rec.chatId, { status: e.status, id: e.id, ...claudeBackgroundFields(e) });
      return;
    }
    if (rec.emit) {
      try { rec.emit(e); } catch { /* local */ }
    } else if (rec.stash.length < 200) {
      rec.stash.push(e);
    }
  }

  private settle(rec: ClaudeTurn): void {
    if (rec.settled) return;
    rec.settled = true;
    this.settleGate(rec);
    const out = claudeOutcome({ interruptedByUs: rec.interruptedByUs, signaled: null, result: rec.result ?? undefined });
    let terminal: BackendEvent;
    if (out.state === "completed") {
      // One message id per reply: steered followers inherit it, so the app shows them as a pointer to the shared reply (as opencode does).
      terminal = { type: "turn.completed", turnId: rec.terminusTurnId, text: out.text, remoteMessageId: rec.terminusTurnId };
    } else if (out.state === "interrupted") {
      terminal = { type: "turn.interrupted", turnId: rec.terminusTurnId, reason: "interrupt" };
    } else {
      // Contract: code classifies, message is ALWAYS the provider's own words.
      // The app renders it inside the red .failtxt line — never substitute the
      // friendly summary here or failures show nowhere.
      // A failed result can carry no text (e.g. resume of an unknown session): the reason is on stderr.
      const why = out.text || rec.stderrTail?.trim() || detailErrors(rec.result?.detail) || "";
      const mapped = claudeError(new Error(why || "Claude turn failed"), "turn");
      console.log(`claude turn failed: turn=${rec.terminusTurnId} ${safeMessage(why || "(empty result)").slice(0, 500)} subtype=${rec.result?.subtype} isError=${rec.result?.isError} detail=${safeMessage(rec.result?.detail ?? "").slice(0, 400)}`);
      terminal = { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: safeMessage(why) || mapped.message, retryable: mapped.retryable };
    }
    this.deliver(rec, terminal);
    this.settleFollowers(rec, terminal);
    rec.emit = null;
    // Match the server turn-result TTL: forget the record 10min after settle.
    setTimeout(() => {
      if (rec.settled) this.turns.delete(rec.terminusTurnId);
    }, 600_000).unref?.();
  }

  /** Reject pending init waiters (fresh-run acceptance): a spawn error
   *  or early death fails fast instead of hanging out the 60s init timer. */
  private rejectInit(rec: ClaudeTurn, err: Error): void {
    const waiters = rec.initWaiters.splice(0);
    for (const w of waiters) {
      try { w.reject(err); } catch { /* local */ }
    }
  }

  private onDeath(rec: ClaudeTurn, err: Error | null): void {
    if (err) this.rejectInit(rec, err);
    if (rec.settled) return;
    if (err === null) {
      // exit 0 with a result already settled via onData; nothing to do.
      if (!rec.result) this.settle(rec);
      else this.settleGate(rec);
      return;
    }
    // Server-log diagnosis (redacted stderr tail). Client rows stay clean.
    try {
      const tail = rec.stderrTail?.trim();
      console.log(tail
        ? `claude died: ${err.message} | stderr: ${safeMessage(tail).slice(0, 500)}`
        : `claude died: ${err.message} (no stderr)`);
    } catch { /* logging must not fail the failover */ }
    // Death without success: classify without inventing a result.
    if (!rec.result) {
      // The exit message alone says nothing ("exited code=1"): classify with
      // the stderr tail so a bad resume, a login or a usage limit keep their
      // codes. The tail classifies AND reaches the row (redacted).
      const tail = rec.stderrTail?.trim();
      const mapped = claudeError(tail ? new Error(`${err.message} ${tail.slice(-2000)}`) : err, "turn");
      const raw = tail ? `${err.message} ${tail.slice(-2000)}` : err.message;
      const terminal: BackendEvent = rec.interruptedByUs
        ? { type: "turn.interrupted", turnId: rec.terminusTurnId, reason: "interrupt" }
        : rec.stalled
        ? { type: "turn.failed", turnId: rec.terminusTurnId, code: "timeout", message: "Claude stopped responding (no output for a long time) and was ended. Send it again.", retryable: true }
        : { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: safeMessage(raw) || mapped.message, retryable: mapped.retryable };
      rec.settled = true;
      this.settleGate(rec);
      this.deliver(rec, terminal);
      this.settleFollowers(rec, terminal);
      rec.emit = null;
      return;
    }
    this.settle(rec);
  }
}
