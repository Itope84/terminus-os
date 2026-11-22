/** Generic turn runtime for non-opencode backends (multi-backend spec M9).
 *
 *  Opencode keeps its inline lifecycle in opencode-proxy (compatibility
 *  contract — never touched here). Codex/Claude turns run through this
 *  runtime, which reuses the shared Terminus lifecycle state so every
 *  existing guarantee holds for them too:
 *  - pendingByChat: indicator authority + exact-turn status ("running").
 *  - turnResults: at-least-once `done` replay on reconnect (shared
 *    flushDones path) + exact-turn lookup. Outcomes record WITHOUT any
 *    provider-message merge booking (opencode-only concept).
 *  - Same turnId retries are idempotent (in-flight acceptance map).
 *  - Stream close never settles; no timeouts settle turns.
 *
 *  Live fan-out: open streams register their SSE sender per chat; adapter
 *  events map to the stable SSE vocabulary (delta/step/done/error/idle).
 */
import { claudeBackgroundFields, publishBackground, setBackgroundFanout } from "./background-log.js";
import { splitReasoning } from "./reasoning-rows.js";
import type {
  BackendAdapter,
  BackendEvent,
  BackendId,
} from "./backends/types.js";
import { BackendError, backendHttpStatus, toPublicError } from "./backends/errors.js";
import { agentbookPrefix, noteCompacted } from "./agentbook.js";
import { getUsage } from "./context-usage.js";
import {
  mapGalleryUrlsForHost,
  pendingTurnIds,
  recordExternalResult,
  stepFiles,
  trackPending,
  untrackPending,
} from "./opencode-proxy.js";

export type SseSend = (event: string, data: string) => void;

const listeners = new Map<string, Set<SseSend>>();

/** Open streams register here (in addition to the opencode feed wiring);
 *  returns an unregister function. The connection owns execution nothing:
 *  closing only unregisters — turns keep running. */
export function addChatListener(chatId: string, send: SseSend): () => void {
  let set = listeners.get(chatId);
  if (!set) {
    set = new Set();
    listeners.set(chatId, set);
  }
  set.add(send);
  return () => {
    const s = listeners.get(chatId);
    if (!s) return;
    s.delete(send);
    if (!s.size) listeners.delete(chatId);
  };
}

function fanout(chatId: string, event: string, data: string): number {
  const set = listeners.get(chatId);
  if (!set) return 0;
  for (const send of set) {
    try { send(event, data); } catch { /* closed; unregister follows */ }
  }
  return set.size;
}
setBackgroundFanout(fanout);

/** One-line summary of a tool input for step rows (pure, unit-tested):
 *  command strings verbatim (truncated), file ops by path, reason text.
 *  Never secret values — inputs here are commands/paths, and the caller
 *  truncates. Unknown shapes yield "". */
export function summarizeToolInput(input: unknown): string {
  if (typeof input === "string") return input.trim();
  if (!input || typeof input !== "object") return "";
  const r = input as Record<string, unknown>;
  for (const k of ["command", "cmd", "path", "file", "reason", "description"]) {
    const v = r[k];
    if (typeof v === "string" && v.trim()) return v.trim().replace(/\s+/g, " ");
  }
  if (Array.isArray(r.fileChanges)) {
    const paths = (r.fileChanges as Array<Record<string, unknown>>)
      .map((f) => (typeof f.path === "string" ? f.path : typeof f.newPath === "string" ? f.newPath : ""))
      .filter(Boolean)
      .slice(0, 3);
    if (paths.length) return paths.join(", ");
  }
  return "";
}

/** Immediate-flush hooks (one per open stream, wrapping its flushDones):
 *  a terminal runtime event flushes every open connection at once, so
 *  codex/claude dones land without waiting for the safety tick. Per-
 *  connection doneSent dedupe keeps exactly-once per connection; reconnect
 *  replay still flows through flushDones. */
const flushers = new Map<string, Set<() => void>>();

export function addChatFlusher(chatId: string, flush: () => void): () => void {
  let set = flushers.get(chatId);
  if (!set) {
    set = new Set();
    flushers.set(chatId, set);
  }
  set.add(flush);
  return () => {
    const s = flushers.get(chatId);
    if (!s) return;
    s.delete(flush);
    if (!s.size) flushers.delete(chatId);
  };
}

function flushChat(chatId: string): void {
  const set = flushers.get(chatId);
  if (!set) return;
  for (const flush of set) {
    try { flush(); } catch { /* closed */ }
  }
}

export interface RuntimeStartArgs {
  chatId: string;
  backend: BackendId;
  turnId: string;
  remoteSessionId: string | null;
  text: string;
  files: Array<{ name: string; mime: string; base64: string }>;
  model?: string | null;
  title?: string | null;
  systemPrompt?: string | null;
  effort?: string | null;
  /** Working directory for this turn (codex cwd override). */
  cwd?: string | null;
  /** Request host for gallery-URL mapping (Tailnet vs loopback). */
  host?: string | null;
  /** Auto-compact ceiling in tokens; 0/absent = each backend's own default. */
  compactAt?: number | null;
  /** "Send now": fold into the running turn when the backend supports it. */
  steer?: boolean;
  /** Client contract only: the caller treats the accept as failure unless
   *  the result carries steered:true. Evaluated client-side from `steered`;
   *  the server reports honestly either way. */
  steerRequired?: boolean;
}

export interface RuntimeAccept {
  accepted: true;
  backend: BackendId;
  turnId: string;
  sessionID: string;
  remoteTurnId?: string | null;
  /** The message was folded into the running turn ("send now"), not queued. */
  steered?: boolean;
}

/** Pure: normalize POST files to backend attachments. Shape-mime-size
 *  rules already enforced by the shared validation loop; this only
 *  re-projects (dataBase64 → base64) with the same name hygiene. */
export function toBackendAttachments(
  rawFiles: unknown,
): Array<{ name: string; mime: string; base64: string }> {
  type InFile = { name?: unknown; mime?: unknown; dataBase64?: unknown };
  const list = Array.isArray(rawFiles) ? (rawFiles as InFile[]).slice(0, 3) : [];
  return list.map((f) => ({
    name:
      typeof f?.name === "string" && f.name.trim()
        ? f.name.trim().split("/").pop()!.split("\\").pop()!.replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 120)
        : `file-${Date.now()}`,
    mime: typeof f?.mime === "string" ? f.mime.trim().slice(0, 120) : "",
    base64: typeof f?.dataBase64 === "string" ? f.dataBase64 : "",
  }));
}

const inflightStarts = new Map<string, Promise<RuntimeAccept>>();

/** Acceptance memory (10min, mirrors turnResults TTL): a retry with the
 *  same turnId replays the original acceptance — sessionID included —
 *  instead of minting a second provider turn (P19). Covers sequential
 *  retries; concurrent ones share the in-flight promise above. */
const acceptResults = new Map<string, { accept: RuntimeAccept; at: number }>();
const ACCEPT_TTL_MS = 600_000;

function readAccept(chatId: string, turnId: string): RuntimeAccept | null {
  const key = `${chatId}:${turnId}`;
  const hit = acceptResults.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > ACCEPT_TTL_MS) {
    acceptResults.delete(key);
    return null;
  }
  return hit.accept;
}

function attachStream(
  chatId: string,
  backend: BackendId,
  adapter: BackendAdapter,
  turnId: string,
  remoteSessionId: string,
  model: string | null,
  host: string | null,
): void {
  // tool.output/completed carry only a toolId on every backend: remember the name from tool.started
  // so the step row reads "done shell", not "done tool".
  const toolNames = new Map<string, string>();
  // Thinking chunks are buffered into whole rows (see reasoning-rows.ts); anything else, or a pause, flushes the rest.
  let reasonBuf = "";
  let reasonTimer: ReturnType<typeof setTimeout> | null = null;
  const sendReasoning = (rows: string[]) => {
    for (const label of rows) fanout(chatId, "step", JSON.stringify({ kind: "reasoning", label, detail: "", at: Date.now() }));
  };
  const flushReasoning = (final: boolean): void => {
    if (reasonTimer) { clearTimeout(reasonTimer); reasonTimer = null; }
    const r = splitReasoning(reasonBuf, final);
    reasonBuf = r.rest;
    sendReasoning(r.rows);
  };
  // Thinking token counts tick many times a second: at most one per 700ms. Other status lines always pass.
  let statusAt = 0;
  const emit = (e: BackendEvent): void => {
    if (e.type !== "reasoning.delta" && e.type !== "status") flushReasoning(true);
    switch (e.type) {
      case "status": {
        if (e.turnId !== turnId) return;
        const now = Date.now();
        const ticking = e.text.startsWith("Thinking");
        if (ticking && now - statusAt < 700) return;
        statusAt = ticking ? now : 0;
        fanout(chatId, "status", JSON.stringify({ text: e.text.slice(0, 120), turnId }));
        return;
      }
      case "message.delta":
        if (e.turnId !== turnId) return;
        fanout(chatId, "delta", JSON.stringify({ delta: e.text.slice(0, 4000), turnId }));
        return;
      case "reasoning.delta":
        if (e.turnId !== turnId) return;
        reasonBuf += e.text;
        flushReasoning(false);
        // A pause in the thinking: show what is there rather than hold it until the next chunk.
        reasonTimer = setTimeout(() => flushReasoning(true), 1500);
        reasonTimer.unref?.();
        return;
      case "background": {
        // Logged per chat and delivered to open streams (background-log.ts). Not turn-filtered: a job ends
        // whenever it ends. The claude adapter already drops its own Monitor watches.
        publishBackground(chatId, { status: e.status, id: e.id, ...claudeBackgroundFields(e) });
        return;
      }
      case "compaction":
        if (e.turnId !== turnId) return;
        if (e.status === "ended") { const id = bookId(backend, chatId, remoteSessionId); if (id) noteCompacted(id); }
        fanout(chatId, "compact", JSON.stringify({ status: e.status, reason: e.reason ?? "", detail: (e.detail ?? "").slice(0, 200) }));
        return;
      case "tool.started":
      case "tool.output":
      case "tool.completed": {
        if (e.turnId !== turnId) return;
        // Say WHAT is running, not just that something ran: the command
        // or file target rides in detail (truncated, workspace paths
        // shortened). This is the user's own working world, never secrets.
        const verb = e.type === "tool.started" ? "run" : e.type === "tool.output" ? "output" : "done";
        let name = "name" in e && typeof e.name === "string" && e.name ? e.name : "";
        if (e.toolId) {
          if (name) {
            toolNames.set(e.toolId, name);
            if (toolNames.size > 200) toolNames.delete(toolNames.keys().next().value as string);
          } else name = toolNames.get(e.toolId) ?? "";
        }
        if (!name) name = "tool";
        const input = "input" in e ? e.input : undefined;
        const row: { kind: string; label: string; detail: string; at: number; files?: string[] } = {
          kind: "tool",
          label: `${verb} ${name}`,
          detail: summarizeToolInput(input).slice(0, 160),
          at: Date.now(),
        };
        if (e.type === "tool.started") {
          const tf = stepFiles(name, input);
          if (tf.length) row.files = tf;
        }
        fanout(chatId, "step", JSON.stringify(row));
        return;
      }
      case "turn.completed":
        if (e.turnId !== turnId) return;
        recordExternalResult(chatId, {
          turnId,
          text: host ? mapGalleryUrlsForHost(e.text, host) : e.text,
          model,
          ...(e.remoteMessageId ? { messageID: e.remoteMessageId } : {}),
        });
        flushChat(chatId);
        return;
      case "turn.failed":
        if (e.turnId !== turnId) return;
        recordExternalResult(chatId, { turnId, text: "", model, error: e.message || e.code, code: e.code });
        flushChat(chatId);
        return;
      case "turn.interrupted":
        // User-requested stop (via stopBackendTurns below): placeholders
        // were already dropped client-side; no done is recorded, so nothing
        // resurrects — matches opencode stop semantics.
        // Provider-emitted interrupt (nobody asked): durable terminal
        // result, so exact-turn lookup never reports a bare unknown for a
        // turn the provider actually ended.
        untrackPending(chatId, turnId);
        if (consumeUserStop(chatId, turnId)) {
          flushChat(chatId);
          return;
        }
        recordExternalResult(chatId, { turnId, text: "", model, error: "interrupted" });
        flushChat(chatId);
        return;
      default:
        return;
    }
  };
  const streamFn = adapter.stream;
  if (!streamFn) {
    untrackPending(chatId, turnId);
    throw new BackendError("unsupported_operation", "streaming unsupported", false, backend, "stream");
  }
  void streamFn.call(adapter, { chatId, turnId, remoteSessionId }, emit).catch((err) => {
    const mapped = err instanceof BackendError ? err : new BackendError("provider_error", "stream attach failed", false, backend, "stream");
    recordExternalResult(chatId, { turnId, text: "", model, error: mapped.message });
  });
  void backend;
}

/** Start a turn on a non-opencode backend. Idempotent per turnId: a retry
 *  with the same id replays the original acceptance (or its exact error),
 *  never a second provider turn. */
/** Which book a chat's turns belong to. Claude's shell gets TERMINUS_AGENTBOOK (chat id); Codex's gets
 *  CODEX_THREAD_ID, so its books are keyed by thread. Other backends expose no id to scripts: no book. */
function bookId(backend: BackendId, chatId: string, remoteSessionId: string): string | null {
  if (backend === "claude") return chatId;
  if (backend === "codex") return remoteSessionId || null;
  return null;
}

export async function startBackendTurn(
  adapter: BackendAdapter,
  args: RuntimeStartArgs,
): Promise<RuntimeAccept> {
  const key = `${args.chatId}:${args.turnId}`;
  const prior = inflightStarts.get(key);
  if (prior) return prior;
  const remembered = readAccept(args.chatId, args.turnId);
  if (remembered) return remembered;
  const work = (async (): Promise<RuntimeAccept> => {
    const caps = adapter.descriptor().capabilities;
    if (args.files.length > 0 && !caps.attachments) {
      throw new BackendError("attachment_unsupported", "attachments unsupported", false, args.backend, "startTurn");
    }
    trackPending(args.chatId, args.turnId);
    try {
      const book = bookId(args.backend, args.chatId, args.remoteSessionId ?? "");
      const note = book ? agentbookPrefix(args.backend, book, getUsage(args.chatId)?.used ?? 0, args.compactAt ?? 0) : "";
      const out = await adapter.startTurn({
        chatId: args.chatId,
        turnId: args.turnId,
        remoteSessionId: args.remoteSessionId,
        text: note + args.text,
        files: args.files,
        model: args.model ?? null,
        title: args.title ?? null,
        systemPrompt: args.systemPrompt ?? null,
        effort: args.effort ?? null,
        cwd: args.cwd ?? null,
        compactAt: args.compactAt ?? null,
        ...(args.steer && caps.steer ? { steer: true } : {}),
      });
      attachStream(args.chatId, args.backend, adapter, args.turnId, out.remoteSessionId, args.model ?? null, args.host ?? null);
      const accept: RuntimeAccept = {
        accepted: true,
        backend: args.backend,
        turnId: args.turnId,
        sessionID: out.remoteSessionId,
        ...(out.remoteTurnId ? { remoteTurnId: out.remoteTurnId } : {}),
        ...(out.steered ? { steered: true } : {}),
      };
      acceptResults.set(key, { accept, at: Date.now() });
      return accept;
    } catch (e) {
      untrackPending(args.chatId, args.turnId);
      throw e;
    }
  })();
  inflightStarts.set(key, work);
  try {
    return await work;
  } finally {
    inflightStarts.delete(key);
  }
}

/** Interrupt codex/claude turns: named turn or every pending turn of the
 *  chat. Queued (provider-not-started) turns are cancelled outright and
 *  never run — stop means stop for the whole chat. Returns how many
 *  accepted the interrupt/cancel. */
/** Turns the user explicitly stopped (chatId:turnId). Lets the
 *  interrupt handler tell user-requested stops (no durable result) from
 *  provider-emitted interrupts (durable terminal result). Bounded. */
const userStopped = new Set<string>();
const USER_STOPPED_CAP = 500;

export function markUserStop(chatId: string, turnId: string): void {
  const key = `${chatId}:${turnId}`;
  userStopped.add(key);
  if (userStopped.size > USER_STOPPED_CAP) {
    const oldest = userStopped.values().next();
    if (!oldest.done) userStopped.delete(oldest.value);
  }
}

function consumeUserStop(chatId: string, turnId: string): boolean {
  const key = `${chatId}:${turnId}`;
  if (!userStopped.has(key)) return false;
  userStopped.delete(key);
  return true;
}

export async function stopBackendTurns(
  adapter: BackendAdapter,
  chatId: string,
  remoteSessionId: string,
  turnId?: string,
): Promise<{ stopped: number }> {
  const ids = turnId ? [turnId] : pendingTurnIds(chatId);
  let stopped = 0;
  for (const id of ids) {
    markUserStop(chatId, id);
    try {
      if (adapter.abortQueued && (await adapter.abortQueued(id))) {
        untrackPending(chatId, id);
        stopped++;
        continue;
      }
    } catch { /* fall through to interrupt */ }
    try {
      const r = await adapter.interruptTurn({ chatId, turnId: id, remoteSessionId });
      if (r.interrupted) stopped++;
    } catch { /* one turn's failure never blocks the rest */ }
  }
  return { stopped };
}

/** Shape a backend error for HTTP without leaking provider internals. */
export function backendErrorBody(e: unknown, backend: BackendId, operation: string): { status: number; body: unknown } {
  if (e instanceof BackendError) {
    return { status: backendHttpStatus(e.code), body: toPublicError(e, e.backend ?? backend, e.operation ?? operation) };
  }
  return { status: 502, body: toPublicError(e, backend, operation) };
}

/** "Send now" for a message already waiting behind the running turn. False = nothing changed. */
export async function steerQueuedTurn(adapter: BackendAdapter, chatId: string, turnId: string): Promise<boolean> {
  if (!adapter.descriptor().capabilities.steer || !adapter.steerQueued) return false;
  try {
    return await adapter.steerQueued(chatId, turnId);
  } catch {
    return false;
  }
}

/** Cancel only if the backend still owns this as a queued, not-yet-started
 *  turn. Unlike stopBackendTurns, this never interrupts a running process. */
export async function abortQueuedBackendTurn(adapter: BackendAdapter, chatId: string, turnId: string): Promise<boolean> {
  if (!adapter.abortQueued) return false;
  try {
    const cancelled = await adapter.abortQueued(turnId);
    if (cancelled) untrackPending(chatId, turnId);
    return cancelled;
  } catch {
    return false;
  }
}
