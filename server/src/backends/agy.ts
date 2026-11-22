/** Antigravity CLI (`agy`) adapter.
 *
 *  Same shape as the Claude adapter: one `agy` process per Terminus turn,
 *  newline-delimited `stream-json`, the final `result` event as completion
 *  authority, `--conversation <id>` for follow-ups.
 *
 *  Documented surface this relies on (antigravity.google/docs/cli/headless):
 *  - `--input-format stream-json --output-format stream-json`: one
 *    `{"event":"user","message":{"content":"…"}}` line on stdin per turn;
 *    closing stdin ends the process after the turn. The prompt therefore never
 *    rides argv (no size cap, no leading-dash trap).
 *  - Events: `init`, `step_update` (text_delta / tool steps), `result`
 *    (status SUCCESS|ERROR|CANCELED|INTERRUPTED|INVALID|WAITING|RUNNING).
 *    Every event carries `conversation_id`: the first one seen is canonical.
 *  - NEVER `--continue`: it resumes the newest conversation in the cwd and
 *    would collide across chats. Always `--conversation <id>`.
 *
 *  Explicit v1 policy (mirrors the Claude adapter):
 *  - Subscription login is authoritative: GEMINI_API_KEY / GOOGLE_API_KEY
 *    are stripped from the child env. Credentials are never read or copied.
 *  - No interactive approvals in headless mode: `--dangerously-skip-permissions`
 *    is passed by default (owner's call on their own machine — no permission
 *    blocks); set TERMINUS_AGY_YOLO=0 to opt back into approvals. Grants live
 *    in agy's own settings.json (`permissions.allow`).
 *  - Optional TERMINUS_AGY_MODE (accept-edits | plan | auto) maps to --mode.
 *  - No system-prompt flag exists: the system prompt is prepended to every
 *    turn as a delimited block (resumed turns need it too).
 *  - Sessions list / history read the local transcript store
 *    (~/.gemini/antigravity-cli/brain/<id>/.system_generated/logs/transcript.jsonl,
 *    verified on this machine): listing is scoped to conversations Terminus
 *    started (ids recorded server-side, since the flat store has no cwd).
 */
import { watchStall } from "../lifecycle.js";
import { spawn, type ChildProcess } from "node:child_process";
import { execFile } from "node:child_process";
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { findBin } from "./bin.js";
import type {
  BackendAdapter,
  BackendContext,
  BackendDescriptor,
  BackendEvent,
  BackendStatus,
  CommandOption,
  EmitBackendEvent,
  InterruptResult,
  InterruptTurnArgs,
  ModelOption,
  ResumeSessionArgs,
  ResumeSessionResult,
  SessionArgs,
  StartTurnArgs,
  StartTurnResult,
  StatusArgs,
  StreamArgs,
  HistoryArgs,
  HistoryResult,
  RemoteSession,
  UsageInfo,
} from "./types.js";
import { BackendError, classifyProviderError, safeMessage, type ErrorRule } from "./errors.js";
import { promptWithAttachments } from "./attachments.js";
import { LineBuffer } from "./codex-rpc.js";
import { locationDir } from "../opencode-proxy.js";
import { runDir } from "../paths.js";
import { AGENTBOOK_HINT } from "../agentbook.js";
import { recordAgyLine } from "../context-usage.js";
import { agyArgv, agyEnv, agyInput, agyLine, agyOutcome, parseAgyModels, agyBrainDir, agyIdOk, agyTranscriptPath, agyTranscriptMessages, listAgySessions, agyDbTitle, setAgyDbTitle, agyTranscriptFirstTitle, type AgyResultLine } from "./agy-core.js";

/** Locate agy: PATH, well-known dirs, then ~/.local/bin (server-owned;
 *  never client-controlled). */
export function agyBin(): string | null {
  return findBin("agy", [join(homedir(), ".local", "bin")]);
}

/** Best-effort login evidence: agy's config dir exists. The credential
 *  store itself is never read. False = no evidence (descriptor stays honest
 *  until a real turn succeeds). */
export function agyLoginPresent(): boolean {
  try { return existsSync(join(homedir(), ".gemini", "antigravity-cli")); } catch { return false; }
}

export function agyDescriptor(available: boolean, authenticated: boolean): BackendDescriptor {
  return {
    id: "agy",
    label: "Agy",
    shortLabel: "Agy",
    available,
    authenticated,
    capabilities: {
      streaming: true,
      models: true,
      commands: false,
      sessionsList: true,
      history: true,
      resume: true,
      interruptTurn: true,
      killProcess: true,
      compact: false,
      title: true,
      deleteSession: false,
      fork: false,
      approvals: false,
      attachments: true,
      overlappingTurns: false,
      effort: true,
      usage: true,
      steer: false,
    },
  };
}

const AGY_ERROR_RULES: readonly ErrorRule[] = [
  { re: /usage limit|quota|exhausted|limit.*reached|resource_exhausted/, code: "usage_limit", message: "Agy usage limit reached" },
  { re: /rate ?limit|429|overloaded|too many requests/, code: "rate_limited", message: "Agy rate limited", retryable: true },
  { re: /authentication required|not logged in|login|unauthenticated|unauthorized|401|403|sign-?in/, code: "not_authenticated", message: "Agy authentication required" },
  { re: /unknown model|model.*not found|invalid model/, code: "model_not_found", message: "Agy model not found" },
  { re: /conversation.*not found|no such conversation|unknown conversation|invalid.*conversation/, code: "session_not_found", message: "Agy conversation not found" },
  { re: /enoent|not running|exited|eof|closed|timeout|timed out/, code: "backend_unavailable", message: "Agy unavailable", retryable: true },
];

export function agyError(e: unknown, operation: string): BackendError {
  return classifyProviderError(e, operation, "agy", "Agy request failed", AGY_ERROR_RULES);
}

interface AgyTurn {
  terminusTurnId: string;
  chatId: string;
  child: ChildProcess | null;
  lines: LineBuffer;
  emit: EmitBackendEvent | null;
  stash: BackendEvent[];
  canonicalSessionId: string | null;
  result: AgyResultLine | null;
  seenTools: Set<string>;
  interruptedByUs: boolean;
  settled: boolean;
  queued: boolean;
  aborted: boolean;
  initWaiters: Array<{ resolve: (id: string) => void; reject: (e: Error) => void }>;
  done: Promise<void>;
  resolveDone: () => void;
  stderrTail: string;
  /** Last byte of output from the child (stdout or stderr): the hung-process clock. */
  lastEventAt: number;
  /** Ended by the hung-process watch: the failure says so instead of a bare exit code. */
  stalled: boolean;
}

export type AgySpawnFn = (
  bin: string,
  argv: string[],
  opts: { cwd: string; stdio: string[]; shell: false; env: Record<string, string> },
) => ChildProcess;

export class AgyAdapter implements BackendAdapter {
  readonly id = "agy" as const;
  private turns = new Map<string, AgyTurn>();
  private gates = new Map<string, Promise<void>>();
  private authed = false;
  private bin: string;
  private spawnFn: AgySpawnFn;
  private modelCache: { at: number; rows: ModelOption[] } | null = null;

  constructor(bin = "agy", spawnFn?: AgySpawnFn) {
    this.bin = bin;
    this.spawnFn = spawnFn ?? ((b, a, o) => spawn(b, a, {
      cwd: o.cwd,
      stdio: o.stdio as ["pipe", "pipe", "pipe"],
      shell: false,
      env: o.env,
    }));
  }

  descriptor(): BackendDescriptor {
    return agyDescriptor(agyBin() !== null, this.authed || agyLoginPresent());
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
    this.turns.clear();
    this.gates.clear();
  }

  private settleGate(rec: AgyTurn): void {
    try { rec.resolveDone(); } catch { /* already settled */ }
  }

  async listModels(_ctx: BackendContext): Promise<ModelOption[]> {
    const now = Date.now();
    if (this.modelCache && now - this.modelCache.at < 10 * 60_000) return this.modelCache.rows.map((m) => ({ ...m }));
    const bin = agyBin() ?? this.bin;
    const rows = await new Promise<ModelOption[]>((resolve) => {
      try {
        execFile(bin, ["models"], { env: agyEnv(), timeout: 10_000, maxBuffer: 256 * 1024, cwd: locationDir() },
          (err, stdout) => resolve(err ? [] : parseAgyModels(String(stdout))));
      } catch { resolve([]); }
    });
    // Cache successes only: a transient failure must not pin an empty picker for 10 min.
    if (rows.length) this.modelCache = { at: now, rows };
    return rows.map((m) => ({ ...m }));
  }

  async listCommands(_ctx: BackendContext): Promise<CommandOption[]> {
    return [];
  }

  /** Conversations Terminus started (persisted across restarts): the flat
   *  transcript store has no working directory, so this is what scopes the
   *  session list to our chats instead of every Agy project on the machine. */
  private knownPath(): string {
    return join(runDir(), "agy-conversations.json");
  }
  private knownConversations(): Set<string> {
    try {
      const raw = readFileSync(this.knownPath(), "utf8");
      const arr = JSON.parse(raw) as unknown;
      if (Array.isArray(arr)) return new Set(arr.filter((x): x is string => typeof x === "string"));
    } catch { /* none recorded yet */ }
    return new Set();
  }
  private recordConversation(id: string): void {
    try {
      const known = this.knownConversations();
      if (known.has(id)) return;
      known.add(id);
      mkdirSync(runDir(), { recursive: true });
      writeFileSync(this.knownPath(), JSON.stringify([...known].slice(-500)) + "\n");
    } catch { /* best-effort: listing stays unscoped, never fails a turn */ }
  }

  async listSessions(args: { limit?: number }): Promise<RemoteSession[]> {
    // Every conversation in the agy brain dir, not only the ones Terminus started.
    return listAgySessions(agyBrainDir(), null, args.limit ?? 200)
      .map((s) => ({ remoteSessionId: s.id, title: s.title, updatedAt: s.updatedAt }));
  }
  async history(args: HistoryArgs): Promise<HistoryResult> {
    if (!agyIdOk(args.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "agy", "history");
    }
    let raw: string;
    try { raw = readFileSync(agyTranscriptPath(agyBrainDir(), args.remoteSessionId), "utf8"); }
    catch { throw new BackendError("session_not_found", "no such session", false, "agy", "history"); }
    return { messages: agyTranscriptMessages(raw, args.limit ?? 200) };
  }

  async startTurn(args: StartTurnArgs): Promise<StartTurnResult> {
    if (args.files?.length) args = { ...args, text: (await promptWithAttachments(args.text, args.files, args.turnId)).text };
    const key = `agy:${args.chatId}`;
    const prevDone = this.gates.get(key) ?? Promise.resolve();
    let resolveDone!: () => void;
    const done = new Promise<void>((r) => { resolveDone = r; });
    this.gates.set(key, prevDone.then(() => done));
    const rec: AgyTurn = {
      terminusTurnId: args.turnId,
      chatId: args.chatId,
      child: null,
      lines: new LineBuffer(),
      emit: null,
      stash: [],
      canonicalSessionId: args.remoteSessionId ?? null,
      result: null,
      seenTools: new Set(),
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
    this.turns.set(args.turnId, rec);
    if (rec.canonicalSessionId) {
      void this.runSpawn(rec, args, prevDone);
      return { accepted: true, remoteSessionId: rec.canonicalSessionId, remoteTurnId: null };
    }
    await prevDone;
    if (rec.aborted) {
      this.forgetTurn(rec);
      throw new BackendError("turn_interrupted", "turn cancelled before start", false, "agy", "startTurn");
    }
    rec.queued = false;
    try {
      const child = this.spawnChild(args);
      this.wireChild(rec, child);
      const sessionId = await this.awaitInit(rec, child);
      return { accepted: true, remoteSessionId: sessionId, remoteTurnId: null };
    } catch (e) {
      this.forgetTurn(rec);
      throw e;
    }
  }

  private async runSpawn(rec: AgyTurn, args: StartTurnArgs, prevDone: Promise<void>): Promise<void> {
    await prevDone;
    if (rec.aborted) {
      this.forgetTurn(rec);
      return;
    }
    rec.queued = false;
    try {
      this.wireChild(rec, this.spawnChild(args));
    } catch (e) {
      const mapped = e instanceof BackendError ? e : agyError(e, "startTurn");
      rec.settled = true;
      this.settleGate(rec);
      this.deliver(rec, { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: mapped.message, retryable: mapped.retryable });
    }
  }

  private spawnChild(args: StartTurnArgs): ChildProcess {
    const argv = agyArgv({
      model: args.model,
      conversation: args.remoteSessionId ?? undefined,
      mode: (process.env.TERMINUS_AGY_MODE || "").trim() || undefined,
      yolo: process.env.TERMINUS_AGY_YOLO !== "0",
      effort: args.effort ?? undefined,
    });
    try {
      const child = this.spawnFn(agyBin() ?? this.bin, argv, {
        cwd: locationDir(),
        stdio: ["pipe", "pipe", "pipe"],
        shell: false,
        env: agyEnv(),
      });
      child.stdin?.on("error", () => { /* child gone: its exit reports it */ });
      // One user line, then close stdin: the process exits after the turn.
      // Same agentbook hint the claude and codex turns get (no system-prompt flag: it rides the turn).
      const sys = args.systemPrompt ? `${args.systemPrompt}\n\n${AGENTBOOK_HINT}` : AGENTBOOK_HINT;
      child.stdin?.end(agyInput(args.text, sys));
      return child;
    } catch (e) {
      throw agyError(e, "startTurn");
    }
  }

  private wireChild(rec: AgyTurn, child: ChildProcess): void {
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
      this.onDeath(rec, rec.result ? null : new Error(`agy exited code=${code} signal=${signal ?? "none"}`)),
    );
  }

  private awaitInit(rec: AgyTurn, child: ChildProcess): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        try { child.kill("SIGTERM"); } catch { /* gone */ }
        reject(agyError(new Error("agy init timeout"), "startTurn"));
      }, 60_000);
      rec.initWaiters.push({
        resolve: (id) => { clearTimeout(timer); resolve(id); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
    });
  }

  async abortQueued(turnId: string): Promise<boolean> {
    const rec = this.turns.get(turnId);
    if (!rec || !rec.queued) return false;
    rec.aborted = true;
    this.forgetTurn(rec);
    return true;
  }

  private forgetTurn(rec: AgyTurn): void {
    this.turns.delete(rec.terminusTurnId);
    this.settleGate(rec);
  }

  async stream(args: StreamArgs, emit: EmitBackendEvent): Promise<void> {
    const rec = this.turns.get(args.turnId);
    if (!rec) throw new BackendError("session_not_found", "unknown turn", false, "agy", "stream");
    rec.emit = emit;
    emit({ type: "turn.started", turnId: args.turnId, remoteSessionId: rec.canonicalSessionId ?? args.remoteSessionId });
    for (const e of rec.stash) {
      try { emit(e); } catch { /* local */ }
    }
    rec.stash = [];
    if (rec.settled) rec.emit = null;
  }

  async interruptTurn(args: InterruptTurnArgs): Promise<InterruptResult> {
    const rec = this.turns.get(args.turnId);
    if (!rec || rec.settled) return { interrupted: false, reason: "unknown turn" };
    rec.interruptedByUs = true;
    if (!rec.child) {
      this.forgetTurn(rec);
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
    rec.interruptedByUs = true;
    if (!rec.child) {
      this.forgetTurn(rec);
      return { interrupted: true, reason: "killed" };
    }
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
    const out = agyOutcome({ interruptedByUs: rec.interruptedByUs, signaled: null, result: rec.result ?? undefined });
    return { state: out.state };
  }

  async resumeSession(args: ResumeSessionArgs): Promise<ResumeSessionResult> {
    if (!args.remoteSessionId) {
      throw new BackendError("invalid_request", "missing session", false, "agy", "resumeSession");
    }
    // No spend-free probe: the id is validated by the next turn's --conversation.
    return { ok: true, remoteSessionId: args.remoteSessionId };
  }

  async getTitle(a: SessionArgs): Promise<string> {
    if (!agyIdOk(a.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "agy", "getTitle");
    }
    const db = agyDbTitle(a.remoteSessionId);
    if (db) return db;
    const tp = agyTranscriptPath(agyBrainDir(), a.remoteSessionId);
    const transcript = agyTranscriptFirstTitle(tp);
    return transcript || "";
  }
  async setTitle(a: SessionArgs & { title: string }): Promise<void> {
    if (!agyIdOk(a.remoteSessionId)) {
      throw new BackendError("invalid_request", "bad session id", false, "agy", "setTitle");
    }
    setAgyDbTitle(a.remoteSessionId, a.title);
  }

  private deliver(rec: AgyTurn, e: BackendEvent): void {
    if (rec.emit) {
      try { rec.emit(e); } catch { /* local */ }
    } else if (rec.stash.length < 200) {
      rec.stash.push(e);
    }
  }

  private onData(rec: AgyTurn, chunk: Buffer): void {
    for (const line of rec.lines.push(chunk)) {
      let obj: Record<string, unknown>;
      try { obj = JSON.parse(line) as Record<string, unknown>; } catch { continue; }
      const { events, conversationId, result } = agyLine(obj, rec.terminusTurnId, rec.seenTools);
      if (rec.chatId) try { recordAgyLine(rec.chatId, obj); } catch { /* bookkeeping */ }
      // First conversation id wins; a resumed run never overwrites the stored one.
      if (conversationId && !rec.canonicalSessionId) {
        rec.canonicalSessionId = conversationId;
        this.recordConversation(conversationId);
        this.authed = true;
      }
      if (rec.canonicalSessionId) {
        for (const w of rec.initWaiters.splice(0)) {
          try { w.resolve(rec.canonicalSessionId); } catch { /* local */ }
        }
      }
      if (result) {
        rec.result = result;
        this.authed = this.authed || result.status === "SUCCESS";
        this.settle(rec);
        continue;
      }
      for (const e of events) this.deliver(rec, e);
    }
  }

  private settle(rec: AgyTurn): void {
    if (rec.settled) return;
    // A fresh turn that ends without ever naming a conversation must fail
    // acceptance now, not hang out the 60s init timer.
    for (const w of rec.initWaiters.splice(0)) {
      try { w.reject(agyError(new Error(rec.result?.error || rec.result?.text || "agy ended before conversation id"), "startTurn")); } catch { /* local */ }
    }
    rec.settled = true;
    this.settleGate(rec);
    const out = agyOutcome({ interruptedByUs: rec.interruptedByUs, signaled: null, result: rec.result ?? undefined });
    let terminal: BackendEvent;
    if (out.state === "completed") {
      terminal = { type: "turn.completed", turnId: rec.terminusTurnId, text: out.text, usage: rec.result?.usage };
    } else if (out.state === "interrupted") {
      terminal = { type: "turn.interrupted", turnId: rec.terminusTurnId, reason: "interrupt" };
    } else if (out.code === "permission_denied") {
      terminal = { type: "turn.failed", turnId: rec.terminusTurnId, code: "permission_denied", message: "Agy needs approval for an action; pre-approve it in agy settings", retryable: false };
    } else {
      const mapped = agyError(new Error(out.text || "Agy turn failed"), "turn");
      // Contract: code classifies, message is ALWAYS the provider's own words.
      // The app renders it inside the red .failtxt line — never substitute the
      // friendly summary here or failures show nowhere.
      terminal = { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: safeMessage(out.text) || mapped.message, retryable: mapped.retryable };
    }
    this.deliver(rec, terminal);
    rec.emit = null;
    setTimeout(() => {
      if (rec.settled) this.turns.delete(rec.terminusTurnId);
    }, 600_000).unref?.();
  }

  private onDeath(rec: AgyTurn, err: Error | null): void {
    if (err) {
      // Classified (with the stderr tail) so fresh-run acceptance fails with a
      // real BackendError code (auth, limit, missing binary), not a raw Error.
      const t = rec.stderrTail?.trim();
      const mapped = agyError(t ? new Error(`${err.message} ${t.slice(-2000)}`) : err, "startTurn");
      for (const w of rec.initWaiters.splice(0)) {
        try { w.reject(mapped); } catch { /* local */ }
      }
    }
    if (rec.settled) { this.settleGate(rec); return; }
    if (err === null) {
      this.settle(rec);
      return;
    }
    try {
      const tail = rec.stderrTail?.trim();
      console.log(tail
        ? `agy died: ${err.message} | stderr: ${safeMessage(tail).slice(0, 500)}`
        : `agy died: ${err.message} (no stderr)`);
    } catch { /* logging must not fail the failover */ }
    const tail = rec.stderrTail?.trim();
    const mapped = agyError(tail ? new Error(`${err.message} ${tail.slice(-2000)}`) : err, "turn");
    // Provider's own words to the chat (redacted); the code keeps the class.
    const raw = tail ? `${err.message} ${tail.slice(-2000)}` : err.message;
    const terminal: BackendEvent = rec.interruptedByUs
      ? { type: "turn.interrupted", turnId: rec.terminusTurnId, reason: "interrupt" }
      : rec.stalled
        ? { type: "turn.failed", turnId: rec.terminusTurnId, code: "timeout", message: "Agy stopped responding (no output for a long time) and was ended. Send it again.", retryable: true }
        : { type: "turn.failed", turnId: rec.terminusTurnId, code: mapped.code, message: safeMessage(raw) || mapped.message, retryable: mapped.retryable };
    rec.settled = true;
    this.settleGate(rec);
    this.deliver(rec, terminal);
    rec.emit = null;
  }
}
