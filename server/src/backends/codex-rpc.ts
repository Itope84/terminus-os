/** Codex app-server JSON-RPC transport (multi-backend spec M7, section 15).
 *
 *  One long-lived `codex app-server` child per manager, newline-delimited
 *  JSON-RPC over stdio. Strict rules:
 *  - Never split stdout chunks naively: LineBuffer reassembles split lines
 *    and multiple messages per chunk.
 *  - Responses demux by `id`; everything else routes to notification /
 *    server-request handlers (async traffic interleaves freely — proven by
 *    remoteControl/status/changed arriving mid-request on 0.159.3).
 *  - Child env is an explicit allowlist (codexEnv): never a full clone.
 *  - No auto-resend, no turn timeouts: a dead child rejects pendings with
 *    process_exited and the owner restarts lazily on next use.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { findBin } from "./bin.js";

export interface RpcResponse {
  id: number | string;
  result?: unknown;
  error?: { code?: number; message?: string };
}

export interface RpcRequest {
  id: number | string;
  method: string;
  params?: unknown;
}

/** Reassemble newline-delimited JSON messages from arbitrary byte chunks. */
export class LineBuffer {
  private buf = "";
  private decoder = new TextDecoder();

  /** Feed a stdout chunk; returns complete lines (without newlines). */
  push(chunk: Buffer | string): string[] {
    this.buf += typeof chunk === "string" ? chunk : this.decoder.decode(chunk, { stream: true });
    const out: string[] = [];
    let idx = this.buf.indexOf("\n");
    while (idx >= 0) {
      const line = this.buf.slice(0, idx).replace(/\r$/, "");
      this.buf = this.buf.slice(idx + 1);
      if (line.trim()) out.push(line);
      idx = this.buf.indexOf("\n");
    }
    return out;
  }

  /** Hand over the unfinished last line (another reader takes the stream from here). */
  drain(): string {
    const s = this.buf;
    this.buf = "";
    return s;
  }
}

/** Explicit child env allowlist. HOME carries the user's own Codex login
 *  (managed credentials, never copied anywhere); PATH locates helpers.
 *  Everything else is dropped — no tokens, no server secrets. */
export function codexEnv(): Record<string, string> {
  const keep = ["PATH", "HOME", "LOGNAME", "USER", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME",
    // Build toolchain locations (paths, never secrets): Gradle + Android SDK + JDK.
    "ANDROID_HOME", "ANDROID_SDK_ROOT", "JAVA_HOME", "GRADLE_USER_HOME"];
  const out: Record<string, string> = {};
  for (const k of keep) {
    const v = process.env[k];
    if (typeof v === "string" && v) out[k] = v;
  }
  return out;
}

export { FALLBACK_BIN_DIRS } from "./bin.js";

/** Locate the codex binary: PATH first (server-owned; never client-controlled),
 *  then the well-known dirs. */
export function codexBin(): string | null {
  return findBin("codex");
}

export function codexAvailable(): boolean {
  return codexBin() !== null;
}

export type NotificationHandler = (method: string, params: unknown) => void;
/** Server→client requests (approvals, elicitation…). Return value becomes
 *  the result; throwing responds with a JSON-RPC error (fail closed). */
export type RequestHandler = (method: string, params: unknown) => unknown | Promise<unknown>;

const RPC_TIMEOUT_MS = 30_000;
const STDERR_TAIL_LIMIT = 8192;

export class CodexRpcClient {
  private child: ChildProcess | null = null;
  private lines = new LineBuffer();
  private nextId = 1;
  private pending = new Map<number | string, { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  private notifHandlers = new Set<NotificationHandler>();
  private reqHandler: RequestHandler | null = null;
  private dead: Error | null = null;
  private readonly bin: string;
  /** Last bytes of child stderr (capped). Surfaced in server logs on
   *  unexpected death only — never in client-facing errors. */
  stderrTail = "";

  constructor(bin?: string, private readonly extraArgs: string[] = []) {
    const found = bin ?? codexBin();
    if (!found) throw new Error("codex binary not found");
    this.bin = found;
  }

  onNotification(cb: NotificationHandler): () => void {
    this.notifHandlers.add(cb);
    return () => { this.notifHandlers.delete(cb); };
  }

  onRequest(cb: RequestHandler): void {
    this.reqHandler = cb;
  }

  private closeHandlers = new Set<(cause: Error) => void>();

  /** Fires when the child dies or close() runs (bug-4 fix: the adapter
   *  fails every working turn here instead of leaving it "running" until
   *  the 10-minute prune silently drops it). */
  onClose(cb: (cause: Error) => void): () => void {
    this.closeHandlers.add(cb);
    return () => { this.closeHandlers.delete(cb); };
  }

  get started(): boolean {
    return this.child !== null && this.child.exitCode === null;
  }

  /** Start the child (idempotent while alive). Runs initialize + initialized. */
  async start(): Promise<void> {
    if (this.started) return;
    this.dead = null;
    this.lines = new LineBuffer();
    const child = spawn(this.bin, [...this.extraArgs, "app-server"], {
      stdio: ["pipe", "pipe", "pipe"],
      shell: false,
      env: codexEnv(),
    });
    this.child = child;
    child.stdout?.on("data", (c: Buffer) => this.onData(c));
    // Stderr ring buffer: the last 8KB are attached to
    // unexpected-death diagnostics. Redacted at log time; client-facing
    // error rows never carry it.
    child.stderr?.on("data", (c: Buffer) => {
      this.stderrTail += c.toString();
      if (this.stderrTail.length > STDERR_TAIL_LIMIT) {
        this.stderrTail = this.stderrTail.slice(-STDERR_TAIL_LIMIT);
      }
    });
    child.on("error", (e) => this.killAll(e instanceof Error ? e : new Error(String(e))));
    child.on("exit", (code, signal) =>
      this.killAll(new Error(`codex app-server exited code=${code} signal=${signal ?? "none"}`)),
    );
    await this.request("initialize", {
      clientInfo: { name: "terminus", title: "Terminus", version: "m7" },
    });
    this.notify("initialized");
  }

  async close(): Promise<void> {
    const c = this.child;
    this.child = null;
    this.killAll(new Error("codex rpc closed"));
    // Whole-tree SIGTERM→SIGKILL escalation: signalling
    // the direct pid only orphans tool grandchildren. Never throws.
    if (c && c.exitCode === null && typeof c.pid === "number") {
      try {
        const { terminateTree } = await import("../tree-kill.js");
        await terminateTree(c.pid);
      } catch { /* already gone */ }
    }
  }

  request<T = unknown>(method: string, params?: unknown, timeoutMs = RPC_TIMEOUT_MS): Promise<T> {
    if (!this.started || !this.child?.stdin) {
      return Promise.reject(new Error("codex app-server not running"));
    }
    const id = this.nextId++;
    const line = JSON.stringify(params === undefined ? { jsonrpc: "2.0", id, method } : { jsonrpc: "2.0", id, method, params });
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`codex rpc timeout: ${method}`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v as T); },
        reject: (e) => { clearTimeout(timer); reject(e); },
        timer,
      });
      try {
        this.child!.stdin!.write(line + "\n");
      } catch (e) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    });
  }

  notify(method: string, params?: unknown): void {
    if (!this.started || !this.child?.stdin) return;
    try {
      const base: Record<string, unknown> = { jsonrpc: "2.0", method };
      if (params !== undefined) base.params = params;
      this.child.stdin.write(JSON.stringify(base) + "\n");
    } catch { /* closed; shutdown paths tolerate */ }
  }

  private onData(chunk: Buffer): void {
    for (const line of this.lines.push(chunk)) {
      let msg: { id?: number | string; method?: string; params?: unknown; result?: unknown; error?: unknown };
      try {
        msg = JSON.parse(line) as typeof msg;
      } catch {
        continue; // corrupt line: ignore, never crash the transport
      }
      if (msg.id !== undefined && (msg.method === undefined)) {
        const p = this.pending.get(msg.id);
        if (!p) continue;
        this.pending.delete(msg.id);
        if (msg.error !== undefined && msg.error !== null) {
          const e = msg.error as { code?: number; message?: string };
          p.reject(new Error(`codex rpc error: ${e.message ?? JSON.stringify(msg.error)}`));
        } else {
          p.resolve(msg.result);
        }
        continue;
      }
      if (typeof msg.method === "string" && msg.id !== undefined) {
        void this.answerRequest(msg.id, msg.method, msg.params);
        continue;
      }
      if (typeof msg.method === "string") {
        for (const h of this.notifHandlers) {
          try { h(msg.method, msg.params); } catch { /* handler faults stay local */ }
        }
      }
    }
  }

  private async answerRequest(id: number | string, method: string, params: unknown): Promise<void> {
    const send = (payload: Record<string, unknown>) => {
      try {
        this.child?.stdin?.write(JSON.stringify({ jsonrpc: "2.0", id, ...payload }) + "\n");
      } catch { /* closed */ }
    };
    if (!this.reqHandler) {
      send({ error: { code: -32000, message: "no request handler (v1: approvals unsupported)" } });
      return;
    }
    try {
      const result = await this.reqHandler(method, params);
      send({ result: result ?? null });
    } catch (e) {
      send({ error: { code: -32000, message: e instanceof Error ? e.message.slice(0, 200) : "request refused" } });
    }
  }

  private killAll(cause: Error): void {
    this.dead = cause;
    for (const h of this.closeHandlers) {
      try { h(cause); } catch { /* handler faults stay local */ }
    }
    for (const [, p] of this.pending) p.reject(cause);
    this.pending.clear();
  }
}
