/** opencode proxy for the Android chat tab. Stateless: the app owns its
 *  chats and their opencode session ids (stored as the chat's remoteId);
 *  the server keeps no chat→session map. Every route takes the opencode
 *  sessionID directly and verifies it against opencode per call.
 *
 *  GET  /api/models → [{id:"provider/model", provider, label}]
 *  GET  /api/commands → [{name:"/cmd", description}]
 *  GET  /api/sessions → [{id, title, updatedAt, messageCount?}]
 *    (read-only opencode session.list, limit 200, desc; 503 when unreachable.
 *    The app hides ids it already owns via its local remoteIds.)
 *  GET  /api/bindings → {bindings:{chatId:sessionID}} (deprecated upgrade
 *    shim: read-only dump of the legacy map file for apps migrating to
 *    self-owned session ids; {} when the file is gone.)
 *  GET  /api/defaults → {defaultModel, systemPrompt, seedVersion}
 *  POST /api/defaults {defaultModel?, systemPrompt?} → {ok, defaultModel, systemPrompt, validated}
 *  POST /api/chat {chatId, sessionID?, title?, text, model?, files?, turnId?}
 *    → {accepted:true,turnId,sessionID}
 *    (turn starter: blank sessionID creates a fresh opencode session, seeded
 *    once at creation; a given sessionID is verified first (404 `no session`
 *    when opencode no longer has it — the app clears its remoteId and the
 *    next send creates fresh). chatId is only the stream/progress correlation
 *    key for the local thread. The outcome travels over GET /api/stream, with
 *    GET /api/progress as the polling fallback.)
 *  GET  /api/stream?chatId=&sessionID= → text/event-stream
 *  GET  /api/progress?chatId=&sessionID= → {status, steps}
 *  GET  /api/turn?chatId=&turnId= → {status:"settled"|"unknown", ...}
 *    (diagnostic mirror use; the app rides the stream only and settles
 *    exclusively from `done` events — progress carries steps, never text.)
 *  POST /api/stop|compact|title|title-set|session-delete|fork {sessionID,…}
 *    (interrupt + DELETE the old session; the app clears its id and the
 *    next send creates fresh. Restarts never orphan, so nothing retired
 *    can resurface as an import.)
 *
 *  GET  /api/stream?chatId=&sessionID=[&since=] → text/event-stream: `step` (live ProgressStep
 *    JSON), `delta` (assistant text growth), `done` ({text,model} full
 *    reply), `inbox` ({job,title,at} delivery ping), `: ping` heartbeat
 *    every 20s. Server-side opencode poll every 1.5s, throttled to new
 *    steps/deltas only. Stays open past done for inbox pings (30min cap).
 *  GET  /api/progress?chatId=&sessionID= → {status:"idle|working|done", steps:[{kind,label,detail?,at}]}
 *    (CURRENT turn's live steps: message.list limit 10 desc. Diagnostic
 *    mirror use only — the app settles exclusively from stream `done`s.)
 *
 *  Opencode connection comes from env only:
 *    OPENCODE_BASE_URL (default http://127.0.0.1:8093)
 *    OPENCODE_USER (default "opencode"), OPENCODE_PASS (fallback OPENCODE_SERVER_PASSWORD)
 *    OPENCODE_DIR (default: repo root — session location directory)
 *    OPENCODE_SYSTEM_PROMPT (default behavior-only seed, no identity claim;
 *      sent once per opencode session, folded into the first-turn body —
 *      v2 has no per-prompt system field. The seed folds into the first
 *      turn of every server-created session; no per-chat state is kept.)
 *
 *  Per-device chat defaults (defaultModel + custom systemPrompt) persist
 *  under ./.run/chat-defaults.json (0600, repo-local runtime dir, gitignored).
 *  defaultModel (a "provider/model" id, blank = server default) applies to
 *  NEW opencode sessions lacking a per-chat pick; the custom systemPrompt
 *  replaces the built-in seed for sessions created after save. Values are
 *  never logged.
 *
 *  chatId → opencode sessionID mappings persist under
 *  ./.run/chat-sessions.json (runtime state, gitignored, never committed).
 *  Secrets are never logged; unreachable opencode → 503 JSON, no stacks.
 */
if (typeof (Promise as any).withResolvers === "undefined") {
  (Promise as any).withResolvers = function <T>() {
    let resolve!: (value: T | PromiseLike<T>) => void;
    let reject!: (reason?: any) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
  };
}
import { wakeDone } from "./wake.js";
import { OpenCode } from "@opencode/client";
import type { IncomingMessage, ServerResponse } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { readFileSync, writeFileSync } from "node:fs";
import { join, sep } from "node:path";
import { homedir } from "node:os";
import { isChatId, streamRegister, streamUnregister } from "./inbox.js";
import { publishBackground } from "./background-log.js";
import { getAdapter, listBackends, parseBackendId } from "./backends/registry.js";
import { isBackendId } from "./backends/types.js";
import { replySettleOk } from "./reply-gate.js";
import { BackendError, backendHttpStatus, toPublicError } from "./backends/errors.js";
import type { BackendId, BackendTurnState } from "./backends/types.js";
import { lostTurns } from "./sync-plan.js";
import { getUsage, noteRow, recordOpencodeTurn, setOpencodeTotals } from "./context-usage.js";
import { cancelJudge, getGoal, lastTokensFor, noteCompacted, noteGoalSettled, pauseGoal, setChatBusyCheck, setContinuationSender, setJudgeTarget } from "./goals.js";
import { repoRoot, runDir } from "./paths.js";
import { skillCommands } from "./backends/claude-commands.js";
import type { CommandOption } from "./backends/types.js";
import { SEED_BASE, systemPromptBuiltin } from "./system-prompt.js";
import { shutdownTargets } from "./lifecycle.js";
import { markOwned, readOwned } from "./session-ownership.js";
import { atomicWriteFile, readBodyText as readBody, sleep, type Json } from "./util.js";

/** Goal mode is audited after every settled turn, and the audit only ever
 *  reads the final message: the first turn of a freshly armed goal is asked
 *  to close with a done/verified/remaining report. Empty for every other turn
 *  and for failed lookups; never throws. */
function goalModeReminder(chatId: string): string {
  try {
    const g = getGoal(chatId);
    if (!g || g.status !== "active") return "";
    if ((g.verdicts?.length ?? 0) > 0 || (g.continuations ?? 0) > 0 || (g.tokensUsed ?? 0) > 0) return "";
    return (
      "Goal mode is active for this session: a background check audits the goal after every turn, " +
      "and it only reads the final message. End every turn with a clear, factual statement of what is " +
      "done, what was verified, and what remains — or, if you cannot go on without the user, state the " +
      "exact blocking condition."
    );
  } catch {
    return "";
  }
}

/** Rewrite absolute artifacts paths in assistant text to downloadable gallery
 *  URLs, using the request Host so Tailnet + loopback both resolve. Anything
 *  outside the artifacts root is left untouched. */
function mapGalleryUrls(text: string, req: IncomingMessage): string {
  return mapGalleryUrlsForHost(text, String(req.headers.host || "127.0.0.1:9009"));
}

/** Host-parameterized variant for the generic turn runtime (no request in
 *  hand there — the host travels with the turn args from handleChat). */
export function mapGalleryUrlsForHost(text: string, host: string): string {
  const root = join(repoRoot(), "artifacts") + sep;
  const base = `http://${host}/terminus/`;
  const esc = root.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return text.replace(new RegExp(esc.replace(/\//g, "[\\/]") + "([^\\s)\"']+)", "g"),
    (_m, rel: string) => base + rel.split(sep).join("/"));
}

export type ModelEntry = { id: string; provider: string; label: string };


const MODELS_DEADLINE_MS = 30_000;
/** M9: per-backend model catalog cache (short TTL per spec 5.2 — never
 *  hit a provider per keystroke; never serve a foreign catalog). */
const MODELS_CACHE_TTL_MS = 120_000;
/** Last non-empty opencode model list: flaky empty pulls serve this stale. */
let lastGoodOpencodeModels: { at: number; models: import("./backends/types.js").ModelOption[] } | null = null;
const modelsCache = new Map<string, { at: number; models: import("./backends/types.js").ModelOption[] }>();
/** Repo-local runtime dir (gitignored): device/operational state lives here,
 *  never permission-walled. ONLY true secrets stay in ~/.config/terminus/secrets/. */
const RUN_DIR = runDir();
// Created 0700 at startup if missing (fire-and-forget; save paths ensure it too).
void mkdir(RUN_DIR, { recursive: true, mode: 0o700 }).catch(() => {});
/** Legacy map file: read-only upgrade shim (GET /api/bindings) until every
 *  app has migrated to self-owned session ids. Never written. */
const MAP_FILE = join(RUN_DIR, "chat-sessions.json");
/** Healed-session swaps: bricked backend session id → {chatId, healed session id}.
 *  Written by the heal tooling (see logbook poisoned-session); read on every turn.
 *  The app keeps sending the old id, the server silently adopts the healed copy, so a
 *  chat keeps its backend context after its session is bricked. One-shot per entry:
 *  the first adoption deletes it (the app learns the healed id from the turn result). */
const HEALED_FILE = join(RUN_DIR, "session-healed.json");

function healedSwapFor(sessionID: string): { chatId: string; healed: string } | null {
  try {
    const m = JSON.parse(readFileSync(HEALED_FILE, "utf8")) as Record<string, { chatId?: unknown; healed?: unknown }>;
    const e = m[sessionID];
    if (e && typeof e.healed === "string" && e.healed && typeof e.chatId === "string") {
      return { chatId: e.chatId, healed: e.healed };
    }
  } catch { /* no swaps recorded */ }
  return null;
}

function baseUrl(): string {
  return process.env.OPENCODE_BASE_URL || "http://127.0.0.1:8093";
}

function authHeaders(): Record<string, string> | undefined {
  const user = process.env.OPENCODE_USER || "opencode";
  const pass = process.env.OPENCODE_PASS ?? process.env.OPENCODE_SERVER_PASSWORD ?? "";
  if (!pass) return undefined;
  return { Authorization: `Basic ${Buffer.from(`${user}:${pass}`).toString("base64")}` };
}

/** Server-owned workspace root for provider working directories.
 *  Never client-controlled: adapters validate containment within it (M7+). */
export function locationDir(): string {
  return process.env.OPENCODE_DIR || repoRoot();
}

/** Per-device chat defaults. defaultModels holds one model id per backend
 *  (opaque backend-local ids; blank/missing = that backend's server default).
 *  defaultModel is the compat view: the effective default for defaultBackend,
 *  derived on load. Values are never logged. */
export type ChatDefaults = {
  defaultBackend: BackendId;
  defaultModels: Record<string, string>;
  defaultModel: string;
  systemPrompt: string;
  seedVersion: number;
  previousSystemPrompt: string;
  /** Auto-compact ceiling in tokens. 0 = default: each backend keeps its own behaviour. */
  compactAt: number;
};
export type DefaultsPatch = {
  compactAt?: number;
  defaultBackend?: BackendId;
  defaultModels?: Record<string, string>;
  defaultModel?: string;
  systemPrompt?: string;
  restorePrevious?: boolean;
};

const DEFAULTS_FILE = join(RUN_DIR, "chat-defaults.json");
const MAX_PROMPT_CHARS = 8000;
export const COMPACT_MIN = 20_000;
export const COMPACT_MAX = 1_000_000;

/** Stored compactAt: 0 (default) or a token count inside the allowed range. */
export function normCompactAt(v: unknown): number {
  const n = typeof v === "number" && Number.isFinite(v) ? Math.floor(v) : 0;
  return n >= COMPACT_MIN && n <= COMPACT_MAX ? n : 0;
}

async function loadDefaults(): Promise<ChatDefaults> {
  try {
    const raw = JSON.parse(await readFile(DEFAULTS_FILE, "utf8")) as {
      defaultBackend?: unknown;
      defaultModels?: unknown;
      defaultModel?: unknown;
      systemPrompt?: unknown;
      seedVersion?: unknown;
      previousSystemPrompt?: unknown;
      compactAt?: unknown;
    };
    const rb = raw?.defaultBackend;
    const backend: BackendId = isBackendId(rb) ? rb : "opencode";
    const models: Record<string, string> = {};
    if (raw?.defaultModels && typeof raw.defaultModels === "object" && !Array.isArray(raw.defaultModels)) {
      for (const [k, v] of Object.entries(raw.defaultModels as Record<string, unknown>)) {
        if (typeof v === "string") models[k] = v.trim().slice(0, 200);
      }
    }
    // Legacy file (defaultModel only) migrates to the opencode slot.
    const legacy = typeof raw?.defaultModel === "string" ? raw.defaultModel.trim().slice(0, 200) : "";
    if (!Object.keys(models).length && legacy) models.opencode = legacy;
    return {
      defaultBackend: backend,
      defaultModels: models,
      defaultModel: models[backend] ?? "",
      systemPrompt: typeof raw?.systemPrompt === "string" ? raw.systemPrompt.slice(0, MAX_PROMPT_CHARS) : "",
      seedVersion:
        typeof raw?.seedVersion === "number" && Number.isFinite(raw.seedVersion) && raw.seedVersion >= SEED_BASE
          ? Math.floor(raw.seedVersion)
          : SEED_BASE,
      previousSystemPrompt:
        typeof raw?.previousSystemPrompt === "string" ? raw.previousSystemPrompt.slice(0, MAX_PROMPT_CHARS) : "",
      compactAt: normCompactAt(raw?.compactAt),
    };
  } catch {
    return { defaultBackend: "opencode", defaultModels: {}, defaultModel: "", systemPrompt: "", seedVersion: SEED_BASE, previousSystemPrompt: "", compactAt: 0 };
  }
}

// Codex takes the ceiling as a launch argument and can be started by any call (model list, history),
// so seed it from the stored setting instead of waiting for a turn.
void loadDefaults()
  .then((d) => import("./backends/codex.js").then((m) => m.setCodexCompactAt(d.compactAt)))
  .catch(() => {});

async function saveDefaults(d: ChatDefaults): Promise<void> {
  await mkdir(RUN_DIR, { recursive: true, mode: 0o700 });
  await atomicWriteFile(DEFAULTS_FILE, JSON.stringify(d), 0o600);
}

export function effectiveSystemPrompt(defaults: ChatDefaults): string {
  if (defaults.systemPrompt.trim()) return defaults.systemPrompt;
  return process.env.OPENCODE_SYSTEM_PROMPT || systemPromptBuiltin();
}

/** opencode loads AGENTS.md into every request, so rules kept there outlive compaction (a seed
 *  folded into the first message does not). Once the marker below is present the opencode
 *  first-turn seed shrinks to a pointer; remove the marker to get the full seed back. */
const APP_RULES_MARKER = "<!-- terminus-app-rules -->";

function appRulesInAgentsMd(): boolean {
  for (const f of ["AGENTS.md", "AGENTS.app-rules.md"]) {
    try {
      if (readFileSync(join(locationDir(), f), "utf8").includes(APP_RULES_MARKER)) return true;
    } catch { /* missing/unreadable: try the next candidate */ }
  }
  return false;
}

function opencodeSeed(defaults: ChatDefaults): string {
  // Dynamic date line: computed at seed time, never stored — a baked date
  // would go stale for sessions spanning midnight.
  let today = "";
  try {
    const now = new Date();
    const day = now.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "local time";
    today = `Today is ${day} (${tz}).\n`;
  } catch { /* dateless seed is still usable */ }
  if (!defaults.systemPrompt.trim() && !process.env.OPENCODE_SYSTEM_PROMPT && appRulesInAgentsMd()) {
    return today + "This is the Terminus app chat. Its app rules are in AGENTS.md.";
  }
  return today + effectiveSystemPrompt(defaults);
}

/** Pure input check for POST /api/defaults, shared with tests.
 *  Missing keys = no change; present-but-blank = clear to server default. */
export function sanitizeDefaultsInput(
  body: unknown,
): { ok: true; value: DefaultsPatch } | { ok: false; error: string } {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "invalid json" };
  const b = body as Record<string, unknown>;
  const patch: DefaultsPatch = {};
  if ("defaultBackend" in b) {
    const bb = b.defaultBackend;
    if (typeof bb !== "string" || !isBackendId(bb))
      return { ok: false, error: "bad defaultBackend" };
    try {
      parseBackendId(bb);
    } catch {
      return { ok: false, error: "backend not available" };
    }
    patch.defaultBackend = bb;
  }
  if ("defaultModels" in b) {
    if (!b.defaultModels || typeof b.defaultModels !== "object" || Array.isArray(b.defaultModels))
      return { ok: false, error: "bad defaultModels" };
    const models: Record<string, string> = {};
    for (const [k, v] of Object.entries(b.defaultModels as Record<string, unknown>)) {
      if (!isBackendId(k)) return { ok: false, error: "bad defaultModels" };
      if (typeof v !== "string") return { ok: false, error: "bad defaultModels" };
      const s = v.trim().slice(0, 200);
      if (s && k === "opencode" && !toModelRef(s))
        return { ok: false, error: "bad model reference; want provider/model" };
      models[k] = s;
    }
    patch.defaultModels = models;
  }
  if ("defaultModel" in b) {
    if (typeof b.defaultModel !== "string") return { ok: false, error: "bad defaultModel" };
    const v = b.defaultModel.trim().slice(0, 200);
    // Legacy clients send this field for every backend. It is an OpenCode
    // provider/model reference only when the effective backend is OpenCode.
    if (v && (patch.defaultBackend ?? "opencode") === "opencode" && !toModelRef(v))
      return { ok: false, error: "bad model reference; want provider/model" };
    patch.defaultModel = v;
  }
  if ("systemPrompt" in b) {
    if (typeof b.systemPrompt !== "string") return { ok: false, error: "bad systemPrompt" };
    if (b.systemPrompt.length > MAX_PROMPT_CHARS)
      return { ok: false, error: `system prompt too long (max ${MAX_PROMPT_CHARS})` };
    patch.systemPrompt = b.systemPrompt;
  }
  if ("compactAt" in b) {
    const n = b.compactAt;
    if (typeof n !== "number" || !Number.isInteger(n) || (n !== 0 && (n < COMPACT_MIN || n > COMPACT_MAX)))
      return { ok: false, error: "bad compactAt" };
    patch.compactAt = n;
  }
  if ("restorePrevious" in b) {
    if (typeof b.restorePrevious !== "boolean") return { ok: false, error: "bad restorePrevious" };
    patch.restorePrevious = b.restorePrevious;
  }
  return { ok: true, value: patch };
}

/** Backend for catalog routes (?backend=): missing/blank means opencode
 *  (migration compat). Unknown ids are 400; known-but-unregistered backends
 *  are 501 — never another backend's catalog. */
export function backendFromQuery(req: IncomingMessage): BackendId {
  const raw = new URL(req.url || "", "http://x").searchParams.get("backend");
  if (!raw || !raw.trim()) return "opencode";
  return parseBackendId(raw.trim());
}

function backendRouteError(res: ServerResponse, json: Json, e: unknown, operation: string): boolean {
  if (e instanceof BackendError) {
    const status = e.code === "invalid_request" ? 400 : 501;
    json(res, status, toPublicError(e, e.backend, operation));
    return true;
  }
  return false;
}

/** GET /api/backends — registered backends + capabilities, plus the
 *  default backend for new chats. No probes, no turns, no secrets. */
export async function handleBackends(_req: IncomingMessage, res: ServerResponse, json: Json) {
  const d = await loadDefaults();
  json(res, 200, { backends: listBackends(), defaultBackend: d.defaultBackend });
}

/** Pure: fold a validated patch onto stored defaults. Legacy defaultModel
 *  targets the effective backend. Other backends' stored models are never
 *  touched (a patch for one backend must not erase another's). */
export function applyDefaultsPatch(stored: ChatDefaults, patch: DefaultsPatch): ChatDefaults {
  const backend = patch.defaultBackend ?? stored.defaultBackend;
  const models: Record<string, string> = { ...stored.defaultModels, ...(patch.defaultModels ?? {}) };
  if (patch.defaultModel !== undefined) models[backend] = patch.defaultModel;
  return {
    defaultBackend: backend,
    defaultModels: models,
    defaultModel: models[backend] ?? "",
    systemPrompt: patch.systemPrompt ?? stored.systemPrompt,
    seedVersion: stored.seedVersion,
    previousSystemPrompt: stored.previousSystemPrompt,
    compactAt: patch.compactAt ?? stored.compactAt,
  };
}

/** GET /api/defaults — stored device defaults (blank = server default).
 *  Also reports the serve-side effective default model name when known. */
export async function handleDefaultsGet(_req: IncomingMessage, res: ServerResponse, json: Json) {
  const d = await loadDefaults();
  let serverDefault = "";
  try {
    const client = makeClient();
    const cfg = (await withDeadline("config", MODELS_DEADLINE_MS, (signal) =>
      (client.config as unknown as { get: (o?: unknown, opts?: unknown) => Promise<unknown> }).get({}, { signal }),
    )) as { model?: unknown };
    if (typeof cfg?.model === "string" && cfg.model) serverDefault = cfg.model;
  } catch { /* best-effort: field stays blank */ }
  json(res, 200, { defaultBackend: d.defaultBackend, defaultModels: d.defaultModels, defaultModel: d.defaultModel, systemPrompt: d.systemPrompt, seedVersion: d.seedVersion, hasPrevious: d.previousSystemPrompt.length > 0, serverDefault, compactAt: d.compactAt });
}

/** POST /api/defaults — validate + persist. A non-blank defaultModel is
 *  checked against the live opencode model list; when opencode is
 *  unreachable the shape check stands and validated:false is returned. */
export async function handleDefaultsPost(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: unknown;
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const clean = sanitizeDefaultsInput(body);
  if (!clean.ok) {
    json(res, 400, { error: clean.error });
    return;
  }
  const stored = await loadDefaults();
  const next = applyDefaultsPatch(stored, clean.value);
  if (clean.value.systemPrompt !== undefined && clean.value.systemPrompt !== stored.systemPrompt) {
    next.seedVersion = stored.seedVersion + 1;
    next.previousSystemPrompt = stored.systemPrompt;
  }
  if (clean.value.restorePrevious === true && stored.previousSystemPrompt) {
    next.systemPrompt = stored.previousSystemPrompt;
    next.previousSystemPrompt = stored.systemPrompt;
    next.seedVersion = stored.seedVersion + 1;
  }
  let validated = true;
  // Validate the effective backend's model only; other backends keep their
  // stored values untouched (a transient outage must not erase settings).
  const effModel = next.defaultModels[next.defaultBackend] ?? "";
  if (effModel && next.defaultBackend === "opencode") {
    if (!toModelRef(effModel)) {
      json(res, 400, { error: "bad model reference; want provider/model" });
      return;
    }
    try {
      const client = makeClient();
      const models = await withDeadline("models", MODELS_DEADLINE_MS, (signal) =>
        client.model.list({ location: { directory: locationDir() } }, { signal }),
      );
      const ids = new Set(
        (((models as { data?: Array<{ providerID: string; modelID: string }> }).data ?? []) as Array<{
          providerID: string;
          modelID: string;
        }>)
          .filter((m) => m?.providerID && m?.modelID)
          .map((m) => `${m.providerID}/${canonicalModelID(m.providerID, m.modelID)}`),
      );
      if (!ids.has(effModel)) {
        json(res, 400, { error: "unknown model" });
        return;
      }
    } catch {
      // Opencode unreachable: accept the shape-valid value unvalidated.
      validated = false;
    }
  }
  if (effModel && next.defaultBackend !== "opencode") {
    // M9: validate against that backend's catalog (best-effort: outages
    // keep the stored value, validated=false — never erase settings).
    try {
      const { getAdapter } = await import("./backends/registry.js");
      const adapter = getAdapter(next.defaultBackend);
      const ids = new Set(
        (await adapter.listModels({
          chatId: "",
          backend: next.defaultBackend,
          remoteSessionId: null,
          cwd: locationDir(),
          model: null,
        })).map((m) => m.id),
      );
      if (!ids.has(effModel)) {
        json(res, 400, { error: "unknown model" });
        return;
      }
    } catch {
      validated = false;
    }
  }
  try {
    await saveDefaults(next);
  } catch {
    json(res, 500, { error: "save failed" });
    return;
  }
  console.log("opencode-proxy defaults: updated");
  void import("./backends/codex.js").then((m) => m.applyCodexCompactAt(next.compactAt)).catch(() => {});
  json(res, 200, { ok: true, defaultBackend: next.defaultBackend, defaultModels: next.defaultModels, defaultModel: next.defaultModel, systemPrompt: next.systemPrompt, compactAt: next.compactAt, validated });
}

function makeClient() {
  return OpenCode.make({ baseUrl: baseUrl(), headers: authHeaders() });
}

function toModelRef(model: string): { id: string; providerID: string } | undefined {
  const slash = model.indexOf("/");
  if (slash <= 0 || slash === model.length - 1) return undefined;
  return { providerID: model.slice(0, slash), id: model.slice(slash + 1) };
}

/** OpenCode v2 may include the provider prefix in modelID as well as
 * providerID. Keep app-facing refs in canonical provider/model form. */
function canonicalModelID(providerID: string, modelID: string): string {
  const prefix = `${providerID}/`;
  return modelID.startsWith(prefix) ? modelID.slice(prefix.length) : modelID;
}

async function withDeadline<T>(label: string, ms: number, fn: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(new Error(`${label} timed out`)), ms);
  const start = Date.now();
  try {
    return await fn(ctl.signal);
  } finally {
    clearTimeout(timer);
    const dt = Date.now() - start;
    if (dt > 5000) console.log(`opencode-proxy ${label} took ${Math.round(dt / 1000)}s`);
  }
}

/** Fast backend probe for GET /health: true when opencode answers config
 *  inside a short deadline. Never throws; keeps health snappy so the app's
 *  online dot reflects the real send path, not just the node process. */
export async function backendOk(): Promise<boolean> {
  try {
    const client = makeClient();
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 4000);
    try {
      await (client.config as unknown as { get: (o?: unknown, opts?: unknown) => Promise<unknown> }).get({}, { signal: ctl.signal });
      return true;
    } finally {
      clearTimeout(timer);
    }
  } catch {
    return false;
  }
}

/** Pure: normalize model.list rows to provider/model refs (deduped, sorted).
 *  Shared by the /api/models handler and the opencode adapter (M3). */
export function mapOpencodeModels(
  raw: Array<{ providerID?: string; modelID?: string; name?: string }>,
): ModelEntry[] {
  const out: ModelEntry[] = [];
  const seen = new Set<string>();
  for (const m of raw ?? []) {
    if (!m?.providerID || !m?.modelID) continue;
    const modelID = canonicalModelID(m.providerID, m.modelID);
    if (!modelID) continue;
    const id = `${m.providerID}/${modelID}`;
    if (seen.has(id)) continue;
    seen.add(id);
    out.push({
      id,
      provider: m.providerID,
      label: typeof m.name === "string" && m.name ? m.name : modelID,
    });
  }
  out.sort((a, b) => a.id.localeCompare(b.id));
  return out;
}

/** Live opencode model fetch (throws on unreachable/slow/auth failure).
 *  M3 seam: the opencode adapter delegates here; the handler keeps its
 *  exact response shape below. provider.list is still fetched alongside
 *  model.list exactly as before. */
export async function fetchOpencodeModels(): Promise<ModelEntry[]> {
  const client = makeClient();
  const dir = locationDir();
  const [_providers, models] = await withDeadline("models", MODELS_DEADLINE_MS, (signal) => {
    const opts = { signal };
    return Promise.all([
      client.provider.list(undefined, opts),
      client.model.list({ location: { directory: dir } }, opts),
    ]);
  });
  void _providers;
  return mapOpencodeModels(
    (models as { data?: Array<{ providerID: string; modelID: string; name?: string }> }).data ?? [],
  );
}

/** GET /api/models — provider.list + model.list merged to provider/model refs.
 *  ?backend= selects the catalog (missing = opencode); any other backend is
 *  501, never a foreign catalog. Opencode keeps its exact shape plus
 *  backend/fetchedAt. */
export async function handleModels(req: IncomingMessage, res: ServerResponse, json: Json) {
  let backend: BackendId;
  try {
    backend = backendFromQuery(req);
  } catch (e) {
    if (!backendRouteError(res, json, e, "listModels")) json(res, 400, { error: "bad backend" });
    return;
  }
  if (backend !== "opencode") {
    // M9: adapter catalog (cached briefly per backend — never another
    // backend's list). Failures map explicitly; outages keep no stale lie:
    // the client falls back to its own same-backend cache.
    const { getAdapter } = await import("./backends/registry.js");
    const { backendErrorBody } = await import("./turn-runtime.js");
    const cached = modelsCache.get(backend);
    if (cached && Date.now() - cached.at < MODELS_CACHE_TTL_MS) {
      json(res, 200, { backend, models: cached.models, fetchedAt: cached.at });
      return;
    }
    try {
      const adapter = getAdapter(backend);
      const models = await adapter.listModels({
        chatId: "", backend, remoteSessionId: null, cwd: locationDir(), model: null,
      });
      modelsCache.set(backend, { at: Date.now(), models });
      json(res, 200, { backend, models, fetchedAt: Date.now() });
    } catch (e) {
      const { status, body } = backendErrorBody(e, backend, "listModels");
      json(res, status, body);
    }
    return;
  }
  try {
    const out = await fetchOpencodeModels();
    const providerIds = new Set(out.map((m) => m.provider));
    console.log(`opencode-proxy models: ${out.length} models across ${providerIds.size} providers`);
    if (out.length > 0) {
      lastGoodOpencodeModels = { at: Date.now(), models: out };
      json(res, 200, { backend, models: out, fetchedAt: Date.now() });
    } else if (lastGoodOpencodeModels) {
      // Flaky empty pull (e.g. mid-restart): serve the last good list
      // marked stale rather than an empty one that reads as unavailable.
      console.log("opencode-proxy models: empty pull, serving last good");
      json(res, 200, { backend, models: lastGoodOpencodeModels.models, fetchedAt: lastGoodOpencodeModels.at, stale: true });
    } else {
      json(res, 200, { backend, models: out, fetchedAt: Date.now() });
    }
  } catch {
    // Unreachable/slow opencode or auth failure: 503, no details, no secrets.
    console.log("opencode-proxy models: opencode unavailable");
    json(res, 503, { error: "opencode unavailable" });
  }
}

/** Pure: normalize command.list rows to slash commands (sorted).
 *  Shared by the /api/commands handler and the opencode adapter (M3). */
export function mapOpencodeCommands(
  raw: Array<{ name?: string; description?: string }>,
): Array<{ name: string; description: string }> {
  const out = (raw ?? [])
    .filter((c) => typeof c?.name === "string" && (c.name as string).startsWith("/"))
    .map((c) => ({
      name: c.name as string,
      description: typeof c.description === "string" ? c.description : "",
    }));
  out.sort((a, b) => a.name.localeCompare(b.name));
  return out;
}

/** Live opencode command fetch (throws on unreachable/slow/auth failure).
 *  M3 seam: the opencode adapter delegates here. */
export async function fetchOpencodeCommands(): Promise<Array<{ name: string; description: string }>> {
  const client = makeClient();
  const listed = await withDeadline("commands", MODELS_DEADLINE_MS, (signal) =>
    client.command.list({ location: { directory: locationDir() } }, { signal }),
  );
  return mapOpencodeCommands(
    (listed as { data?: Array<{ name?: string; description?: string }> }).data ?? [],
  );
}

/** Claude lists the skills itself; every other backend gets the Terminus skills after its own commands
 *  (its own win a name clash). */
function withSkills(own: CommandOption[]): CommandOption[] {
  const have = new Set(own.map((c) => c.name.toLowerCase()));
  return [...own, ...skillCommands(join(repoRoot(), "skills")).filter((c) => !have.has(c.name.toLowerCase()))];
}

/** GET /api/commands — opencode slash commands (global + directory scope).
 *  ?backend= selects the catalog (missing = opencode); any other backend is
 *  501 — never opencode commands in another backend's chat. */
export async function handleCommands(req: IncomingMessage, res: ServerResponse, json: Json) {
  let backend: BackendId;
  try {
    backend = backendFromQuery(req);

  } catch (e) {
    if (!backendRouteError(res, json, e, "listCommands")) json(res, 400, { error: "bad backend" });
    return;
  }
  if (backend !== "opencode") {
    // M9: adapter commands (codex/claude surface none in v1 → []).
    // Never opencode commands in another backend's chat.
    const { getAdapter } = await import("./backends/registry.js");
    const { backendErrorBody } = await import("./turn-runtime.js");
    try {
      const adapter = getAdapter(backend);
      const own = await adapter.listCommands({
        chatId: "", backend, remoteSessionId: null, cwd: locationDir(), model: null,
      });
      const commands = backend === "claude" ? own : withSkills(own);
      json(res, 200, { backend, commands, fetchedAt: Date.now() });
    } catch (e) {
      const { status, body } = backendErrorBody(e, backend, "listCommands");
      json(res, status, body);
    }
    return;
  }
  try {
    const out = await fetchOpencodeCommands();
    json(res, 200, { backend, commands: withSkills(out.map((c) => ({ ...c, available: true }))), fetchedAt: Date.now() });
  } catch {
    // Unreachable opencode or auth failure: 503, no details, no secrets.
    console.log("opencode-proxy commands: opencode unavailable");
    json(res, 503, { error: "opencode unavailable" });
  }
}

export type SessionEntry = { id: string; title: string; updatedAt: number; messageCount?: number };

/** Pure: normalize a session.list page (array or {data:[...]}) to
 *  SessionEntry rows. Unknown shapes degrade to [] or row omission. */
export function mapSessionEntries(raw: unknown): SessionEntry[] {
  const arr: unknown[] = Array.isArray(raw)
    ? raw
    : Array.isArray((raw as { data?: unknown } | null)?.data)
      ? (raw as { data: unknown[] }).data
      : [];
  const out: SessionEntry[] = [];
  for (const s of arr) {
    if (!s || typeof s !== "object") continue;
    const o = s as Record<string, unknown>;
    const id =
      typeof o.id === "string" && o.id
        ? o.id
        : typeof o.sessionID === "string" && o.sessionID
          ? (o.sessionID as string)
          : "";
    if (!id) continue;
    const title = typeof o.title === "string" ? (o.title as string).slice(0, 200) : "";
    const time = o.time as { created?: unknown; updated?: unknown } | undefined;
    const updatedAt =
      typeof time?.updated === "number" && Number.isFinite(time.updated)
        ? time.updated
        : typeof time?.created === "number" && Number.isFinite(time.created)
          ? time.created
          : typeof o.updatedAt === "number" && Number.isFinite(o.updatedAt)
            ? (o.updatedAt as number)
            : 0;
    const mc =
      typeof o.messageCount === "number" && Number.isFinite(o.messageCount)
        ? (o.messageCount as number)
        : typeof o.message_count === "number" && Number.isFinite(o.message_count)
          ? (o.message_count as number)
          : undefined;
    out.push(
      mc === undefined
        ? { id, title, updatedAt }
        : { id, title, updatedAt, messageCount: Math.floor(mc) },
    );
  }
  return out;
}

/** Pure: opencode session id from client input — trimmed, capped, blank
 *  when absent (blank = "create one"). Only the ses_-shaped ids opencode
 *  mints are accepted; anything else is a bad request, never a lookup. */
export function cleanSessionID(v: unknown): string {
  if (typeof v !== "string") return "";
  const t = v.trim().slice(0, 200);
  return /^ses_[A-Za-z0-9]+$/.test(t) ? t : "";
}
/** GET /api/sessions — read-only opencode session list (system-wide, limit 200, desc),
 *  mapped to [{id,title,updatedAt,messageCount?}]. The app hides ids it
 *  already owns (its local remoteIds). 503 when opencode is unreachable.
 *  ?backend=codex lists codex threads instead (mapped to the same shape);
 *  import UI only ever shows the requested backend. */
export async function handleSessions(req: IncomingMessage, res: ServerResponse, json: Json) {
  let backend: BackendId = "opencode";
  try {
    const raw = new URL(req.url || "", "http://x").searchParams.get("backend");
    if (raw && raw.trim()) backend = parseBackendId(raw.trim());
  } catch (e) {
    if (e instanceof BackendError) {
      json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "listSessions"));
      return;
    }
    throw e;
  }
  if (backend !== "opencode") {
    const { getAdapter } = await import("./backends/registry.js");
    const { backendErrorBody } = await import("./turn-runtime.js");
    try {
      const adapter = getAdapter(backend);
      const remote = await adapter.listSessions?.({ limit: 200 }) ?? [];
      json(res, 200, {
        backend,
        sessions: remote.map((s) => ({
          id: s.remoteSessionId,
          title: s.title ?? "",
          updatedAt: s.updatedAt ?? 0,
        })),
      });
    } catch (e) {
      const { status, body } = backendErrorBody(e, backend, "listSessions");
      json(res, status, body);
    }
    return;
  }
  try {
    const client = makeClient();
    const listed = await withDeadline("sessions", MODELS_DEADLINE_MS, (signal) =>
      client.session.list(
        { limit: 200, order: "desc" } as Parameters<
          typeof client.session.list
        >[0],
        { signal },
      ),
    );
    // Only sessions Terminus created or forked: the user's own opencode sessions and scheduled runs stay out.
    const owned = readOwned("opencode");
    json(res, 200, { sessions: mapSessionEntries(listed).filter((s) => owned.has(s.id)) });
  } catch {
    // Unreachable/slow opencode or auth failure: 503, no details, no secrets.
    console.log("opencode-proxy sessions: opencode unavailable");
    json(res, 503, { error: "opencode unavailable" });
  }
}

/** GET /api/bindings — deprecated upgrade shim. Read-only dump of the
 *  legacy chatId→session map ({bindings:{chatId:sessionID}}) so updated
 *  apps can adopt self-owned session ids once. {} when the file is gone.
 *  Never written; delete with the file once migration is confirmed. */
export async function handleBindings(_req: IncomingMessage, res: ServerResponse, json: Json) {
  try {
    const raw = JSON.parse(await readFile(MAP_FILE, "utf8")) as Record<string, { sessionID?: unknown }>;
    const bindings: Record<string, string> = {};
    for (const [chatId, entry] of Object.entries(raw ?? {})) {
      if (chatId && isChatId(chatId) && typeof entry?.sessionID === "string" && cleanSessionID(entry.sessionID)) {
        bindings[chatId] = (entry.sessionID as string).trim();
      }
    }
    json(res, 200, { bindings });
  } catch {
    json(res, 200, { bindings: {} });
  }
}

/** Opencode "no such session" shape: the v2 SDK rejects with plain decoded
 *  objects ({_tag, message, data.message}), not Errors — dig the message
 *  out before matching. Shared by every sessionID-keyed route. */
export function isNoSession(e: unknown): boolean {
  const raw = e as { message?: unknown; data?: { message?: unknown }; _tag?: unknown };
  const msgText = String(
    (typeof raw?.data?.message === "string" && raw.data.message) ||
      (typeof raw?.message === "string" && raw.message) ||
      (typeof raw?._tag === "string" && raw._tag) ||
      e,
  ).slice(0, 200);
  return /no such session|session not found|not.?found|does not exist|\bgone\b|\b404\b/i.test(msgText);
}

/** GET /api/commands — opencode slash commands (global + directory scope). */

type TextPart = { type: string; text?: string };

function partsText(parts: TextPart[]): string {
  return parts
    .filter((p) => p?.type === "text" && typeof p.text === "string")
    .map((p) => p.text as string)
    .join("")
    .trim();
}

/** Send-time prefix (`[25 Sep, 06:20] `) so the model can anchor
 *  reminders and late delivery. Server time (no explicit TZ, inherits
 *  host). Pure via `now` for tests. */
export function chatWhenPrefix(now = new Date()): string {
  const when = now.toLocaleString("en-GB", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
  return `[${when}] `;
}

/** One settled turn outcome (the unit of turnResults, replays and the durable store). */
export type TurnResult = { chatId: string; turnId: string; text: string; model: string | null; at: number; error?: string; code?: string; messageID?: string };

/** Settled turn outcomes, keyed by `${chatId}:${turnId}` (send-through-style
 *  send-through: overlapping prompts on one chat each settle independently).
 *  Written by the POST /api/chat background runner; consumed by
 *  GET /api/stream (`done` events carry turnId + messageID so the app can
 *  dedupe like a send-once registry). Kept briefly so reconnecting streams
 *  still settle; pruned after 10min. Memory-only. */
export const turnResults = new Map<
  string,
  TurnResult
>();
/** Reconnect replay window for done events (see handleStream/flushDones).
 *  A (re)connect used to replay the chat's entire retained history — since
 *  completions went durable (7 days on disk) that is a full-history storm
 *  every reconnect, and each replayed done runs the client's settle path on
 *  its UI thread. Connections without an explicit ?since= get the recent
 *  window only (pre-durable behavior); missed older dones heal through the
 *  exact-turnId registry (/api/turn) and the client's stale-turn reconcile.
 *  Exported for tests. */
export const DONE_REPLAY_WINDOW_MS = 15 * 60_000;
export function replayableDones(
  all: Iterable<TurnResult>,
  chatId: string,
  sinceMs: number,
): Array<TurnResult> {
  return [...all]
    .filter((tr) => tr.chatId === chatId && tr.at > sinceMs)
    .sort((a, b) => a.at - b.at);
}
/** Machine-readable failure codes for settled turn errors (the human text
 *  stays in `error`). Lets clients and logs classify without substring
 *  matching — the same discipline the other backends already use. */
export const TURN_CODES = {
  orderingUncertain: "ordering_uncertain",
  emptyReplyStable: "empty_reply_stable",
  noReplyRecorded: "no_reply_recorded",
  providerError: "provider_error",
  sessionExpired: "session_expired",
  backendUnavailable: "backend_unavailable",
} as const;
/** Unsettled turns per chat (oldest first): accepted but with no entry in
 *  turnResults yet. Lets live deltas find their owning turn by message id
 *  instead of assuming the newest turn. Removed on settle; TTL-pruned.
 *  Exported for turnStatus tests. */
export const pendingByChat = new Map<string, Array<{ turnId: string; at: number; text?: string }>>();
/** Replies still running across every chat (GET /api/busy): callers can wait for these to finish before a restart. */
export function busyTurns(): number {
  let n = 0;
  for (const turns of pendingByChat.values()) n += turns.length;
  return n;
}

/** Assistant-message owner: which chat+turn first produced this opencode
 *  message id. `certain` is false when the message first appeared while
 *  several turns were unsettled (a guess kept only for merge continuity) —
 *  live deltas for uncertain messages stay unemitted and the authoritative
 *  `done` settles every turn. TTL-pruned. */
const msgOwner = new Map<string, { chatId: string; turnId: string; at: number; certain: boolean }>();

export function trackPending(chatId: string, turnId: string, text?: string): void {
  // A newly accepted turn supersedes any verdict still being judged for this
  // chat: that verdict describes a turn the user has already moved past.
  try { cancelJudge(chatId); } catch { /* judging is advisory */ }
  const list = pendingByChat.get(chatId) ?? [];
  if (!list.some((p) => p.turnId === turnId)) list.push({ turnId, at: Date.now(), ...(text ? { text } : {}) });
  pendingByChat.set(chatId, list.slice(-20));
}

export function untrackPending(chatId: string, turnId: string): void {
  const list = pendingByChat.get(chatId);
  if (!list) return;
  const next = list.filter((p) => p.turnId !== turnId);
  if (next.length) pendingByChat.set(chatId, next);
  else pendingByChat.delete(chatId);
}

/** Let overlapping prompts run concurrently (OpenCode may intentionally fuse
 * them), but assign completed replies in send order. Without this small gate,
 * a later worker that reaches message.list first can claim the earlier reply
 * and transpose two otherwise distinct answers. Returns true when ordering
 * is established; false on timeout with earlier turns STILL pending —
 * callers must fail loudly (ordering uncertain), never attribute anyway.
 * A timeout is resource safety, not proof of order. */
export const ORDER_GATE_TIMEOUT_MS = 120_000;
export async function waitForEarlierTurns(
  chatId: string,
  turnId: string,
  timeoutMs: number = ORDER_GATE_TIMEOUT_MS,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const pending = pendingByChat.get(chatId) ?? [];
    const at = pending.findIndex((p) => p.turnId === turnId);
    if (at <= 0) return true;
    if (Date.now() >= deadline) return false;
    await sleep(25);
  }
}

/** Durable completion store: completions survive late readers (hours)
 *  and server restarts (which wipe the maps). Loaded lazily once;
 *  written through a single chain so concurrent settles can't clobber
 *  each other. File holds texts — same sensitivity as chat content. */
type PersistedResult = TurnResult;
let resultsLoaded = false;
let persistChain: Promise<void> = Promise.resolve();

function loadPersistedResults(): void {
  if (resultsLoaded) return;
  resultsLoaded = true;
  try {
    const raw = JSON.parse(readFileSync(RESULTS_FILE, "utf8")) as PersistedResult[];
    if (!Array.isArray(raw)) return;
    const cutoff = Date.now() - RESULT_TTL_MS;
    const sorted = raw
      .filter((r) => r && typeof r.turnId === "string" && typeof r.chatId === "string" && typeof r.at === "number" && r.at >= cutoff)
      .sort((a, b) => a.at - b.at)
      .slice(-RESULT_CAP);
    for (const r of sorted) {
      turnResults.set(turnKey(r.chatId, r.turnId), {
        chatId: r.chatId,
        turnId: r.turnId,
        text: typeof r.text === "string" ? r.text : "",
        model: typeof r.model === "string" ? r.model : null,
        at: r.at,
        ...(typeof r.error === "string" ? { error: r.error } : {}),
        ...(typeof r.code === "string" ? { code: r.code } : {}),
        ...(typeof r.messageID === "string" ? { messageID: r.messageID } : {}),
      });
    }
  } catch { /* missing/corrupt file: start empty */ }
}

function schedulePersistResults(): void {
  persistChain = persistChain.then(async () => {
    try {
      const all = [...turnResults.values()].sort((a, b) => a.at - b.at).slice(-RESULT_CAP);
      await atomicWriteFile(RESULTS_FILE, JSON.stringify(all.map((r) => ({
        chatId: r.chatId,
        turnId: r.turnId,
        text: r.text,
        model: r.model,
        at: r.at,
        ...(r.error ? { error: r.error } : {}),
        ...(r.code ? { code: r.code } : {}),
        ...(r.messageID ? { messageID: r.messageID } : {}),
      }))), 0o600);
    } catch { /* persistence is best-effort; memory stays canonical */ }
  }).catch(() => {});
}

/** Pure turn lifecycle read (unit-tested; handleTurn + clients share it):
 *  retained result → "settled"; accepted-but-unsettled → "running" (never
 *  resend); anything else → "unknown" (never-seen, pruned, or wiped —
 *  resend only with warning). Exported with pendingByChat for tests. */
export function turnStatus(chatId: string, turnId: string): "settled" | "running" | "unknown" {
  pruneTurnResults();
  if (readTurnResult(chatId, turnId)) return "settled";
  const pend = pendingByChat.get(chatId) ?? [];
  return pend.some((p) => p.turnId === turnId) ? "running" : "unknown";
}

/** Single writer for settled outcomes: keeps the pending set truthful and
 *  books each settled message id to its turn for overlap disambiguation. */
const cancelledTurnKeys = new Set<string>();

function recordTurnResult(
  key: string,
  r: { chatId: string; turnId: string; text: string; model: string | null; at: number; error?: string; code?: string; messageID?: string; tokens?: number },
  sessionID?: string,
): void {
  turnResults.set(key, r);
  recordOpencodeTurn(r.chatId, r.messageID);
  const started = pendingByChat.get(r.chatId)?.find((p) => p.turnId === r.turnId);
  if (started) console.log(`turn settled chat=${r.chatId} turn=${r.turnId} ms=${Date.now() - started.at}`);
  untrackPending(r.chatId, r.turnId);
  const cancelled = cancelledTurnKeys.delete(key);
  if (cancelled) console.log(`turn cancelled chat=${r.chatId} turn=${r.turnId}, not judged`);
  if (started && !cancelled) wakeDone(r.chatId, r.turnId, r.text, r.error);
  schedulePersistResults();
  // Goals advisory hook: settled text judges active goals. Fire-and-forget,
  // never throws. A failed turn carries its error (the goal stops on it) and
  // a completed one carries its token counters (the goal's budget accounts
  // from them); both stay silent on the chat.
  if (!cancelled) {
    try {
      noteGoalSettled(r.chatId, r.text, {
        ...(r.error ? { error: r.error } : {}),
        ...(typeof r.tokens === "number" ? { tokens: r.tokens } : {}),
        ...(started?.text ? { userText: started.text } : {}),
      });
    } catch { /* advisory never breaks turns */ }
  }
  if (r.messageID && sessionID) {
    settledMsgTurn.set(`${sessionID}:${r.messageID}`, { turnId: r.turnId, at: Date.now() });
    if (settledMsgTurn.size > 2000) {
      const oldest = settledMsgTurn.keys().next();
      if (!oldest.done) settledMsgTurn.delete(oldest.value);
    }
  }
}

/** M9: device system prompt for provider instruction channels (codex
 *  developerInstructions, claude --append-system-prompt). Same effective
 *  prompt the opencode path folds into first turns. */
export async function deviceSystemPrompt(): Promise<string> {
  return effectiveSystemPrompt(await loadDefaults());
}

/** M9: record a non-opencode turn outcome for exact-turn lookup + done
 *  replay (shared flushDones path). Deliberately books NOTHING into
 *  settledMsgTurn/msgOwner: provider-message merge is opencode-only;
 *  codex/claude turns settle by turnId alone (one result = one turn). */
export function recordExternalResult(
  chatId: string,
  r: { turnId: string; text: string; model: string | null; error?: string; code?: string; messageID?: string },
): void {
  turnResults.set(turnKey(chatId, r.turnId), {
    chatId,
    turnId: r.turnId,
    text: r.text,
    model: r.model,
    at: Date.now(),
    ...(r.error ? { error: r.error } : {}),
    ...(r.error && r.code ? { code: r.code } : {}),
    ...(r.messageID ? { messageID: r.messageID } : {}),
  });
  const wasPending = (pendingByChat.get(chatId) ?? []).some((p) => p.turnId === r.turnId);
  untrackPending(chatId, r.turnId);
  const cancelled = cancelledTurnKeys.delete(turnKey(chatId, r.turnId));
  if (wasPending && !cancelled) wakeDone(chatId, r.turnId, r.text, r.error);
  schedulePersistResults();
  // Goals advisory hook (external backends): same judging, same silence.
  // A stopped turn is never judged; a failed one stops a looping goal.
  if (!cancelled) {
    try { noteGoalSettled(chatId, r.text, r.error ? { error: r.error } : undefined); } catch { /* advisory never breaks turns */ }
  }
}

/** M9: pending turn ids for a chat (stop fans out over them). */
export function pendingTurnIds(chatId: string): string[] {
  pruneTurnResults();
  return (pendingByChat.get(chatId) ?? []).map((p) => p.turnId);
}

/** messageID → owning turnId for one session (read-only view for pick). */
function settledTurnsOf(sessionID: string): Map<string, string> {
  const out = new Map<string, string>();
  const prefix = `${sessionID}:`;
  for (const [k, v] of settledMsgTurn) {
    if (k.startsWith(prefix)) out.set(k.slice(prefix.length), v.turnId);
  }
  return out;
}

/** M6: chat → bound backend (server-side guard against cross-provider
 *  remoteId reuse within a process). Bound on POST /api/chat only; reads
 *  never bind (checkRouteBackend). Lost on server restart — the client
 *  always sends backend, so rebinds are consistent (spec 31). */
const chatBackendById = new Map<string, BackendId>();

export function bindOrValidateBackend(
  chatId: string,
  backend: BackendId,
): { ok: true } | { ok: false; expected: BackendId } {
  const cur = chatBackendById.get(chatId);
  if (!cur) {
    chatBackendById.set(chatId, backend);
    return { ok: true };
  }
  return cur === backend ? { ok: true } : { ok: false, expected: cur };
}

export function checkRouteBackend(
  chatId: string,
  backend: BackendId,
): { ok: true } | { ok: false; expected: BackendId } {
  const cur = chatBackendById.get(chatId);
  if (!cur || cur === backend) return { ok: true };
  return { ok: false, expected: cur };
}

/** Model and effort of each chat's latest turn (memory only; goal checks use it). */
const turnConfigByChat = new Map<string, { model?: string; effort?: string }>();

export function backendOfChat(chatId: string): BackendId | undefined {
  return chatBackendById.get(chatId);
}

/** M6: resolve + validate the backend for turn-scoped routes. An explicit
 *  ?backend=/body value wins; otherwise the chat's bound backend, else
 *  opencode (compat). Reads never bind — mismatch with a bound backend is
 *  409, unknown ids 400, known-but-unregistered 501. Responds + null on
 *  rejection, so handlers continue unchanged on success. */
export function resolveRouteBackend(
  backendRaw: string | null | undefined,
  res: ServerResponse,
  json: Json,
  chatId: string,
  operation: string,
): BackendId | null {
  let backend: BackendId;
  if (backendRaw && backendRaw.trim()) {
    try {
      backend = parseBackendId(backendRaw.trim());
    } catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, operation));
        return null;
      }
      throw e;
    }
  } else {
    backend = backendOfChat(chatId) ?? "opencode";
  }
  const chk = checkRouteBackend(chatId, backend);
  if (!chk.ok) {
    json(res, 409, { error: "backend_mismatch", backend, expected: chk.expected, chatId });
    return null;
  }
  return backend;
}

/** Pure: may this live delta stream? Certain ownership (known-certain
 *  message, lone pending turn, post-restart continuity) streams; guessed
 *  messages under overlap stay silent until their authoritative `done`.
 *  Exported for tests. */
export function shouldEmitDelta(
  owned: { certain: boolean } | undefined,
  pendingCount: number,
): boolean {
  if (owned) return owned.certain;
  return pendingCount <= 1;
}

/** Pure: which turn owns a live delta for (chatId, sessionID, messageID)?
 *  Known message → recorded owner. Unknown message → the newest unsettled
 *  turn when exactly one exists, else null (caller falls back to the
 *  legacy newest-turn tag rather than dropping live text). Exported for tests. */
export function ownerTurnForDelta(
  pending: Array<{ turnId: string }>,
  owned: { chatId: string; turnId: string } | undefined,
): string | null {
  if (owned) return owned.turnId;
  if (pending.length === 1) return pending[0].turnId;
  if (pending.length > 1) return pending[pending.length - 1].turnId;
  return null;
}

/** Accepted POST outcome, shared with exact-turnId duplicates: a lost
 *  response retried with the same turnId replays this instead of running
 *  setup twice. Success records also carry the session for adoption. */
export type AcceptOutcome = { ok: true; sessionID: string } | { ok: false; status: number; error: string };
/** In-flight (and recently settled) acceptance per chat+turn. */
const inflightAccept = new Map<string, { at: number; done: Promise<AcceptOutcome> }>();
/** Fresh sessions minted per chat (double-send race): two concurrent first
 *  sends share one fresh session instead of minting two. Short-lived
 *  (30s) AND verified with session.get before adoption, so a
 *  restarted/deleted session can never become authoritative again — a
 *  dead id falls through to a fresh create. Prompts still run
 *  independently (send-through); only creation dedupes. */
const createdByChat = new Map<string, { id: string; at: number }>();
const CREATED_TTL_MS = 30_000;
/** Sessions compacted since the last seed: the next turn re-folds the
 *  effective system prompt, so long chats don't drift off-prompt. */
const reseedAfterCompact = new Set<string>();
const chatSetupLocks = new Map<string, Promise<void>>();

const TURN_TTL_MS = 10 * 60 * 1000;
/** Completed-turn retention: replies must outlive late readers (user opens
 *  the chat hours later) and server restarts. 7 days on disk + memory,
 *  capped (below) so the map can't grow without bound. Accept dedupe
 *  (inflightAccept) keeps the short TTL. Pending entries have their own
 *  horizon (PENDING_TTL_MS, past the long-turn cap): an unsettled turn is
 *  "running" until it settles or outlives the cap, never "unknown" at
 *  10 minutes into a healthy 3-hour run. */
const RESULT_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const RESULT_CAP = 1000;
const RESULTS_FILE = join(RUN_DIR, "turn-results.json");

function turnKey(chatId: string, turnId: string): string {
  return `${chatId}:${turnId}`;
}

function pruneTurnResults(): void {
  loadPersistedResults();
  const cutoff = Date.now() - TURN_TTL_MS;
  const resultCutoff = Date.now() - RESULT_TTL_MS;
  for (const [k, v] of turnResults) {
    if (v.at < resultCutoff) turnResults.delete(k);
  }
  // Cap: evict oldest completions first (map preserves insertion order,
  // but `at` is authoritative — reinsert out-of-order loads don't matter).
  if (turnResults.size > RESULT_CAP) {
    const ordered = [...turnResults.entries()].sort((a, b) => a[1].at - b[1].at);
    for (const [k] of ordered.slice(0, turnResults.size - RESULT_CAP)) turnResults.delete(k);
  }
  for (const [k, v] of inflightAccept) {
    if (v.at < cutoff) inflightAccept.delete(k);
  }
  for (const [chatId, list] of pendingByChat) {
    const next = list.filter((p) => p.at >= Date.now() - PENDING_TTL_MS);
    if (next.length) pendingByChat.set(chatId, next);
    else pendingByChat.delete(chatId);
  }
  for (const [k, v] of msgOwner) {
    if (v.at < cutoff) msgOwner.delete(k);
  }
  for (const [k, v] of settledMsgTurn) {
    if (v.at < cutoff) settledMsgTurn.delete(k);
  }
  for (const [k, v] of createdByChat) {
    if (Date.now() - v.at > CREATED_TTL_MS * 2) createdByChat.delete(k);
  }
}

function newTurnId(): string {
  return `t-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function cleanTurnId(v: unknown): string | undefined {
  if (typeof v !== "string") return undefined;
  const t = v.trim();
  return /^[A-Za-z0-9_-]{1,64}$/.test(t) ? t : undefined;
}

async function withChatSetupLock<T>(chatId: string, work: () => Promise<T>): Promise<T> {
  const previous = chatSetupLocks.get(chatId) ?? Promise.resolve();
  let release!: () => void;
  const current = new Promise<void>((resolve) => { release = resolve; });
  chatSetupLocks.set(chatId, current);
  await previous;
  try {
    return await work();
  } finally {
    release();
    if (chatSetupLocks.get(chatId) === current) chatSetupLocks.delete(chatId);
  }
}

/** POST /api/chat — turn STARTER. Stateless: takes the opencode sessionID
 *  the app owns (blank = create a fresh session, seeded once at creation).
 *  A given sessionID is verified per call: 404 `no session` when opencode
 *  no longer has it (the app clears it; the next send creates fresh).
 *  Replies {accepted:true,turnId,sessionID} fast. Accepts optional `turnId`
 *  (client-generated, else server mints one) and `files` (max 3, ≤5MB);
 *  empty text is allowed when files are present (fallback applied).
 *  Overlapping POSTs on one chat each run and each emit their own `done`
 *  (no per-chat clobber, no invisible queue). chatId is only the
 *  stream/progress correlation key for the local thread. */
export async function handleChat(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { chatId?: unknown; sessionID?: unknown; title?: unknown; text?: unknown; model?: unknown; files?: unknown; turnId?: unknown; backend?: unknown; effort?: unknown; cwd?: unknown; nativeCommandName?: unknown; steer?: unknown; steerRequired?: unknown };
  try {
    body = JSON.parse(await readBody(req, 25_000_000));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  const givenRaw = typeof body.sessionID === "string" ? body.sessionID.trim() : "";
  const given = cleanSessionID(givenRaw);
  const text = typeof body.text === "string" ? body.text : "";
  const title = typeof body.title === "string" && body.title.trim() ? body.title.trim().slice(0, 80) : undefined;
  const model = typeof body.model === "string" && body.model.trim() ? body.model.trim() : undefined;
  const turnId = cleanTurnId(body.turnId) ?? newTurnId();
  // Reasoning effort (codex only; validated opaque string, else dropped).
  const effort =
    typeof body.effort === "string" && ["low", "medium", "high"].includes(body.effort.trim()) ? body.effort.trim() : null;
  // Working directory (codex only; trimmed/capped here, resolved in
  // resolveChatCwd — any folder in yolo mode, Desktop-contained otherwise).
  const cwd = typeof body.cwd === "string" && body.cwd.trim() ? body.cwd.trim().slice(0, 300) : null;
  lastTurnByChat.set(chatId, turnId);
  if (!chatId) {
    json(res, 400, { error: "missing chatId" });
    return;
  }
  // Goal checks run on what this chat runs on: remember its model and effort.
  turnConfigByChat.set(chatId, { model, effort: effort ?? undefined });
  // M9: backend parses before any provider-shaped validation (a codex
  // thread id is not a ses_ id and must not trip the opencode checks).
  // Missing/blank = opencode (compat). No binding happens here.
  let backend: BackendId = "opencode";
  {
    const raw = typeof body.backend === "string" && body.backend.trim() ? body.backend.trim() : "opencode";
    try {
      backend = parseBackendId(raw);
    } catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "startTurn"));
        return;
      }
      throw e;
    }
  }
  let nativeCommandName: string | null = null;
  if (body.nativeCommandName !== undefined) {
    const requested = typeof body.nativeCommandName === "string" ? body.nativeCommandName.trim().replace(/^\//, "") : "";
    if (backend !== "opencode" || !requested || requested.length > 100 || !/^[\w.-]+$/.test(requested)) {
      json(res, 400, { error: "bad native command" });
      return;
    }
    try {
      const listed = await fetchOpencodeCommands();
      if (!listed.some((c) => c.name.replace(/^\//, "") === requested)) {
        json(res, 400, { error: "native command is not available" });
        return;
      }
    } catch {
      json(res, 503, { error: "opencode commands unavailable" });
      return;
    }
    nativeCommandName = requested;
  }
  // Provider-native remote id: ses_-shaped for opencode (verified per
  // call), opaque (trimmed, capped) for other backends — validated by
  // the adapter call itself, failure surfacing explicitly.
  const foreignId = givenRaw.trim().slice(0, 200);
  if (backend === "opencode" && givenRaw && !given) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  pruneTurnResults();
  // Validate optional file attachments: max 3, each ≤5MB decoded.
  type InFile = { name?: unknown; mime?: unknown; dataBase64?: unknown };
  const rawFiles = Array.isArray(body.files) ? (body.files as InFile[]).slice(0, 4) : [];
  if (Array.isArray(body.files) && body.files.length > 3) {
    json(res, 400, { error: "too many files (max 3)" });
    return;
  }
  const files: Array<{ uri: string; name: string }> = [];
  for (const f of rawFiles) {
    const name =
      typeof f?.name === "string" && f.name.trim()
        ? f.name.trim().split("/").pop()!.split("\\").pop()!.replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 120)
        : `file-${Date.now()}`;
    const mime = typeof f?.mime === "string" ? f.mime.trim().slice(0, 120) : "";
    const b64 = typeof f?.dataBase64 === "string" ? f.dataBase64 : "";
    if (!mime || !/^[\w.+-]+\/[\w.+-]+$/.test(mime)) {
      json(res, 400, { error: "bad file mime" });
      return;
    }
    if (!b64 || b64.length > 7_000_000) {
      json(res, 400, { error: "bad file data (max 5MB)" });
      return;
    }
    let size = 0;
    try {
      size = Buffer.from(b64, "base64").length;
    } catch {
      json(res, 400, { error: "bad file data" });
      return;
    }
    if (size === 0 || size > 5 * 1024 * 1024) {
      json(res, 400, { error: "bad file data (max 5MB)" });
      return;
    }
    files.push({ uri: `data:${mime};base64,${b64}`, name });
  }
  const mime0 = Array.isArray(body.files) && (body.files as InFile[])[0]?.mime;
  const firstMime = typeof mime0 === "string" ? mime0 : "";
  const effectiveText = text.trim()
    ? text
    : files.length
      ? firstMime.startsWith("audio/")
        ? "Transcribe this audio and answer any question about it."
        : firstMime.startsWith("image/")
          ? "Describe this image and answer any question about it."
          : "Summarize this file and answer any question about it."
      : "";
  if (!effectiveText.trim()) {
    json(res, 400, { error: "missing text" });
    return;
  }
  if (!isChatId(chatId)) {
    json(res, 400, { error: "bad chatId" });
    return;
  }
  // M6/M9: bind the chat to its backend. The binding is created here on
  // turn start — reads never bind.
  {
    const bound = bindOrValidateBackend(chatId, backend);
    if (!bound.ok) {
      json(res, 409, { error: "backend_mismatch", backend, expected: bound.expected, chatId });
      return;
    }
  }
  if (backend !== "opencode") {
    // M9: provider execution leaves the opencode path entirely — no
    // session verification, no model-shape gate, no opencode merge state.
    // Model ids are opaque backend-local strings; attachments gate in the
    // runtime via capabilities; failures map explicitly (never fallback).
    const { getAdapter } = await import("./backends/registry.js");
    const { startBackendTurn, backendErrorBody, toBackendAttachments } = await import("./turn-runtime.js");
    const adapter = getAdapter(backend);
    try {
      const prompt = await deviceSystemPrompt();
      const out = await startBackendTurn(adapter, {
        chatId,
        backend,
        turnId,
        remoteSessionId: foreignId || null,
        text: effectiveText,
        files: toBackendAttachments(body.files),
        model: model ?? null,
        title: title ?? null,
        systemPrompt: prompt,
        compactAt: (await loadDefaults()).compactAt,
        effort,
        cwd,
        steer: body.steer === true,
        steerRequired: body.steerRequired === true,
        host: String(req.headers.host || "127.0.0.1:9009"),
      });
      json(res, 200, out);
    } catch (e) {
      const { status, body: errBody } = backendErrorBody(e, backend, "startTurn");
      const detail = e instanceof BackendError ? `${e.code}: ${e.message}` : String(e instanceof Error ? e.message : e);
      console.log(`backend chat: ${backend} failed chat=${chatId} turn=${turnId} status=${status} remote=${(foreignId || "").slice(0, 8)} err=${detail.slice(0, 160)}`);
      json(res, status, errBody);
    }
    return;
  }
  let ref: { id: string; providerID: string } | undefined;
  if (model) {
    ref = toModelRef(model);
    if (!ref) {
      json(res, 400, { error: "bad model reference; want provider/model" });
      return;
    }
  }

  const acceptanceKey = turnKey(chatId, turnId);
  // Single in-flight acceptance record per chat+turn: a duplicate POST
  // (lost response retried with the same turnId) awaits the original
  // setup and replays its exact outcome — sessionID included — instead
  // of minting a second session or answering without one.
  const prior = inflightAccept.get(acceptanceKey);
  if (prior) {
    const out = await prior.done;
    if (out.ok) {
      json(res, 200, { accepted: true, backend, turnId, sessionID: out.sessionID });
    } else {
      if (out.status === 503) console.log("opencode-proxy chat: opencode request failed");
      json(res, out.status, { error: out.error });
    }
    return;
  }
  // Reserve synchronously before the async session lookup, so a racing
  // duplicate always finds this record.
  let resolveOutcome!: (o: AcceptOutcome) => void;
  const done = new Promise<AcceptOutcome>((r) => { resolveOutcome = r; });
  inflightAccept.set(acceptanceKey, { at: Date.now(), done });

  let sessionID = "";
  let seed: string | undefined;
  const outcome: AcceptOutcome = await (async (): Promise<AcceptOutcome> => {
    try {
      await withChatSetupLock(chatId, async () => {
        const client = makeClient();
        const dir = locationDir();
        const defaults = await loadDefaults();
        // Server default model: applies to NEW sessions lacking a per-chat pick.
        let createRef = ref;
        if (!createRef && defaults.defaultModel) {
          const dref = toModelRef(defaults.defaultModel);
          if (dref) createRef = dref;
        }
        if (given) {
          // Reuse the app-owned session, verified per call. A healed swap
          // redirects a bricked id to its clean copy (same chat, context kept).
          const swap = healedSwapFor(given);
          const sid = swap?.healed ?? given;
          await client.session.get({ sessionID: sid });
          if (swap) {
            console.log(`opencode-proxy chat: adopted healed session chat=${chatId} ${given} -> ${sid}`);
            try {
              const m = JSON.parse(readFileSync(HEALED_FILE, "utf8")) as Record<string, unknown>;
              delete m[given];
              writeFileSync(HEALED_FILE, JSON.stringify(m));
            } catch { /* one-shot best effort: the swap stays until it succeeds */ }
          }
          if (ref) await client.session.switchModel({ sessionID: sid, model: ref });
          sessionID = sid;
          markOwned("opencode", sid); // resumed chats count as Terminus's, so the import list keeps them
          // Reseed once after a compaction so the prompt survives it.
          if (reseedAfterCompact.delete(sid)) seed = opencodeSeed(defaults);
          else seed = undefined;
        } else {
          // Double-send race: a sibling first-send just minted a session
          // for this chat — adopt it (verified alive) instead of minting
          // a second. Stale/dead ids fall through to a fresh create.
          const fresh = createdByChat.get(chatId);
          if (fresh && Date.now() - fresh.at < CREATED_TTL_MS) {
            try {
              await client.session.get({ sessionID: fresh.id });
              sessionID = fresh.id;
              if (reseedAfterCompact.delete(fresh.id)) seed = opencodeSeed(defaults);
              else seed = undefined;
            } catch {
              createdByChat.delete(chatId);
            }
          }
          if (!sessionID) {
            const created = (await client.session.create({
              ...(title ? { title } : {}),
              location: { directory: dir },
              ...(createRef ? { model: createRef } : {}),
            } as Parameters<typeof client.session.create>[0])) as { id: string };
            sessionID = created.id;
            markOwned("opencode", sessionID);
            // Seeded once, here: the seed folds into this turn's prompt below,
            // so no per-chat seeded state is kept anywhere.
            seed = opencodeSeed(defaults);
            createdByChat.set(chatId, { id: sessionID, at: Date.now() });
          }
        }
      });
      // Publish the session for streams opened before this send (create +
      // reuse alike): without it a pre-send stream never narrows to the
      // new session and the first turn loses live events.
      lastSessionByChat.set(chatId, sessionID);
      return { ok: true, sessionID };
    } catch (e) {
      if (isNoSession(e)) {
        // Drop stale correlation so streams stop polling a dead session.
        if (lastSessionByChat.get(chatId) === given) lastSessionByChat.delete(chatId);
        return { ok: false, status: 404, error: "no session" };
      }
      return { ok: false, status: 503, error: "opencode unavailable" };
    }
  })();
  resolveOutcome(outcome);
  if (!outcome.ok) {
    if (outcome.status === 503) console.log("opencode-proxy chat: opencode request failed");
    json(res, outcome.status, { error: outcome.error });
    return;
  }

  // Enqueue the turn in the background; the reply arrives via /api/stream
  // (`done` with turnId + messageID) and /api/progress keeps working as the
  // polling fallback. Per-turn keys: overlapping send-through POSTs never
  // clobber each other. Every turn carries a `[dd Mon, HH:mm]` send-time
  // prefix (after the seed when present).
  const datedText = /^\[\d{2} \S/.test(effectiveText) ? effectiveText : `${chatWhenPrefix()}${effectiveText}`;
  // Goal mode speaks once, on the first turn of a freshly armed goal: the
  // audit only ever reads the turn's final message, so the report rule has to
  // reach the model before the audit does. Later turns get it from the
  // continuation prompt instead.
  const reminder = goalModeReminder(chatId);
  const withSeed = seed ? `${seed}\n\n${datedText}` : datedText;
  const promptText = reminder ? `${withSeed}\n\n${reminder}` : withSeed;
  const bgChatId = chatId;
  const bgTurnId = turnId;
  const bgSessionID = sessionID;
  const bgFiles = files;
  const bgModel = model ?? null;
  const bgNativeCommandName = nativeCommandName;
  const bgNativeCommandText = text.replace(/^\s*\/\S+\s*/, "").trim();
  const bgReq = req;
  trackPending(bgChatId, bgTurnId, effectiveText);
  setImmediate(() =>
    void (async () => {
      const key = turnKey(bgChatId, bgTurnId);
      let stage = "prompt";
      let inboxTime = 0;
      try {
        const client = makeClient();
        if (shouldCompactBefore((await loadDefaults()).compactAt, lastTokensFor(bgChatId))) {
          stage = "compact";
          try {
            await compactOpencodeSession(bgSessionID);
            noteCompacted(bgChatId);
          } catch { /* best effort: the turn goes ahead uncompacted */ }
          stage = "prompt";
        }
        const promptArgs = {
          sessionID: bgSessionID,
          text: promptText,
          ...(bgFiles.length ? { files: bgFiles } : {}),
        } as Parameters<typeof client.session.prompt>[0];
        const rc: RetryClient = {
          sessionPrompt: (args) =>
            client.session.prompt(args as Parameters<typeof client.session.prompt>[0]) as unknown as Promise<{
              time?: { created?: number };
            }>,
          sessionWait: (sid) => client.session.wait({ sessionID: sid }) as unknown as Promise<void>,
          messageList: async (sid, limit, type) => {
            const listed = (await client.message.list({
              sessionID: sid,
              limit,
              order: "desc",
              ...(type ? { type } : {}),
            } as Parameters<typeof client.message.list>[0])) as { data?: ListedMsg[] };
            return listed.data ?? [];
          },
        };
        // Bounded backoff with a landed-check before every resend: transient
        // blips self-heal here, and a retry can never double-prompt.
        if (bgNativeCommandName) {
          // Native commands can have side effects, so invoke once and never
          // apply prompt retry/backoff semantics to them.
          inboxTime = Date.now();
          await client.session.command({
            sessionID: bgSessionID,
            name: bgNativeCommandName,
            text: bgNativeCommandText,
          } as Parameters<typeof client.session.command>[0]);
        } else {
          inboxTime = await promptWithBackoff(rc, bgSessionID, promptArgs as Record<string, unknown>, promptText, bgChatId);
        }
        // The prompt landed: everything from here is wait/fetch, so a
        // failure reconciles (late reply may already exist) instead of
        // reporting blind. session.wait holds one long request open for
        // the whole run — its death mid-run is the common (prompt) case.
        // Long coding turns outlast any single wait: loop in rounds
        // (wait → check list → wait more) until a terminal reply lands,
        // the session dies, or the long-turn cap hits. Completion
        // authority is the message list, never one wait call.
        stage = "wait";
        const runStart = Date.now();
        let lastSig = "";
        let stableRounds = 0;
        for (;;) {
          // waitReturned = the wait call came back (session idle or a
          // provider-side wait failure), as opposed to a transport break
          // where the turn may be perfectly healthy. Error and empty
          // settles below only fire on waitReturned: settling while the
          // transport is broken would amputate live turns (and kill
          // transient retries before they resolve).
          let waitReturned = false;
          try {
            await sessionWaitWithRetry(rc, bgSessionID, bgChatId);
            waitReturned = true;
          } catch (e) {
            // Wait transport died (or session died): fall through to the
            // list check below, which distinguishes still-running (wait
            // more) from gone (fail). Session-gone throws out to catch.
            if (isSessionGone(errText(e))) throw e;
          }
          stage = "list";
          // Settled meanwhile (GET /api/sync reconciled it): nothing left to do.
          if (turnResults.has(key)) { untrackPending(bgChatId, bgTurnId); return; }
          const ordered = await waitForEarlierTurns(bgChatId, bgTurnId);
          if (!ordered) {
            recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: "ordering uncertain: earlier turn still running", code: TURN_CODES.orderingUncertain });
            return;
          }
          const listed = await rc.messageList(bgSessionID, 20, "assistant");
          const sig = assistantListSig(listed);
          stableRounds = sig === lastSig ? stableRounds + 1 : 0;
          lastSig = sig;
          const found = pickReplyFromList(listed, inboxTime, settledTurnsOf(bgSessionID), bgTurnId);
          // Nothing settles on the message's finished flag alone: opencode finishes preamble
          // messages while the run continues, and settling on one showed a short preamble as the
          // whole reply while the real answer never came. The run itself must be idle (replySettleOk).
          let settleFound = false;
          if (found && found.text) {
            const working = await sessionWorking(bgSessionID);
            settleFound = replySettleOk({ waitReturned, working, stableRounds, elapsedMs: Date.now() - runStart, terminal: found.terminal });
            if (!settleFound) console.log(`opencode-proxy chat: reply held chat=${bgChatId} turn=${bgTurnId} msg=${found.messageID ?? "?"} terminal=${found.terminal} working=${working} stable=${stableRounds} waitReturned=${waitReturned}`);
          }
          if (found && found.text && settleFound) {
            console.log(`opencode-proxy chat: reply picked chat=${bgChatId} turn=${bgTurnId} msg=${found.messageID ?? "?"} terminal=${found.terminal} chars=${found.text.length}`);
            const reply = mapGalleryUrls(found.text, bgReq);
            recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: reply, model: bgModel ?? null, at: Date.now(), ...(found.messageID ? { messageID: found.messageID } : {}), ...(found.tokens ? { tokens: found.tokens } : {}) }, bgSessionID);
            return;
          }
          // Provider-side failure (model rejection, bad parameters): the
          // message list carries it outside `text` parts, so the reply
          // picker above never matches. Settle with the provider's own
          // message on the error channel — but only on waitReturned, so
          // a transient failure mid-turn (retried by the provider) is
          // never mistaken for terminal. Errors settled to another turn
          // (merged/overlapping prompts) never settle this one.
          let errFound: { text: string; messageID?: string } | null = null;
          if ((!found || !found.text) && waitReturned) {
            errFound = pickErrorFromList(listed, inboxTime, settledTurnsOf(bgSessionID), bgTurnId);
            if (errFound && errFound.text) {
              recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: errFound.text.slice(0, 500), code: TURN_CODES.providerError, ...(errFound.messageID ? { messageID: errFound.messageID } : {}) }, bgSessionID);
              return;
            }
          }
          // Unknown failure shape: the extractors know nothing and the
          // list is frozen on an idle session. Settle honestly instead of
          // looping to the 3h cap on an empty spinner. Five consecutive
          // frozen reads past the first minute means dead, not slow: any
          // live turn (text, reasoning, tools, retries) moves the sig.
          if (waitReturned && (!found || !found.text) && (!errFound || !errFound.text) &&
              stableRounds >= 5 && Date.now() - runStart > 60_000) {
            recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: "assistant finished with no reply", code: TURN_CODES.emptyReplyStable });
            return;
          }
          if (Date.now() - runStart > LONG_TURN_CAP_MS) {
            recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: "no assistant reply recorded", code: TURN_CODES.noReplyRecorded, ...(found?.messageID ? { messageID: found.messageID } : {}) });
            return;
          }
          // Still running: back off before the next wait round so an
          // instantly-returning wait can't hot-loop the list.
          stage = "wait";
          await sleep(WAIT_ROUND_GAP_MS);
        }
      } catch (e) {
        const msg = errText(e);
        // Dead server-side session: clear error, no silent recovery.
        // The app shows it with a one-tap /restart.
        let expired = isSessionGone(msg);
        if (!expired && stage !== "prompt") {
          // The prompt landed; only the wait/fetch failed — reconcile once
          // before failing: the reply may be sitting there as a late `done`.
          try {
            await sleep(RECONCILE_DELAY_MS);
            const reordered = await waitForEarlierTurns(bgChatId, bgTurnId);
            if (!reordered) {
              recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: "ordering uncertain: earlier turn still running", code: TURN_CODES.orderingUncertain });
              return;
            }
            const lateList = await messageListOnce(bgSessionID, 20, "assistant");
            const late = pickReplyFromList(
              lateList,
              inboxTime,
              settledTurnsOf(bgSessionID),
              bgTurnId,
            );
            if (late?.text) {
              const reply = mapGalleryUrls(late.text, bgReq);
              console.log(`opencode-proxy chat: reconciled late reply chat=${bgChatId} turn=${bgTurnId}`);
              recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: reply, model: bgModel ?? null, at: Date.now(), ...(late.messageID ? { messageID: late.messageID } : {}), ...(late.tokens ? { tokens: late.tokens } : {}) }, bgSessionID);
              return;
            }
            // Same provider-error channel as the wait loop: a late failure
            // must reconcile to the provider's message, not the generic
            // unavailable note below. Overlap exclusion applies here too.
            const lateErr = pickErrorFromList(lateList, inboxTime, settledTurnsOf(bgSessionID), bgTurnId);
            if (lateErr?.text) {
              console.log(`opencode-proxy chat: reconciled late error chat=${bgChatId} turn=${bgTurnId}`);
              recordTurnResult(key, { chatId: bgChatId, turnId: bgTurnId, text: "", model: bgModel ?? null, at: Date.now(), error: lateErr.text.slice(0, 500), code: TURN_CODES.providerError, ...(lateErr.messageID ? { messageID: lateErr.messageID } : {}) }, bgSessionID);
              return;
            }
          } catch (re) {
            if (isSessionGone(errText(re))) expired = true;
          }
        }
        console.log(`opencode-proxy chat: background turn failed stage=${stage} err=${msg.slice(0, 500)} ${errShape(e)}`);
        recordTurnResult(key, {
          chatId: bgChatId,
          turnId: bgTurnId,
          text: "",
          model: bgModel ?? null,
          at: Date.now(),
          error: expired ? "session expired — send again for a fresh one" : `opencode unavailable (${stage})`,
          code: expired ? TURN_CODES.sessionExpired : TURN_CODES.backendUnavailable,
        });
      }
    })(),
  );
  json(res, 200, { accepted: true, backend, turnId, sessionID });
}

/** Transport-level failures (nothing reached opencode): safe to retry once. */
function isTransportError(e: unknown): boolean {
  const raw = e as { message?: unknown; data?: { message?: unknown }; _tag?: unknown; cause?: { code?: unknown }; code?: unknown; reason?: unknown };
  const msg = String(
    (typeof raw?.data?.message === "string" && raw.data.message) ||
      (typeof raw?.message === "string" && raw.message) ||
      (typeof raw?.reason === "string" && raw.reason) ||
      (typeof raw?._tag === "string" && raw._tag) ||
      e,
  );
  const code = String(
    (typeof raw?.cause?.code === "string" && raw.cause.code) ||
      (typeof raw?.code === "string" && raw.code) ||
      "",
  );
  return /transport|fetch failed|ECONNREFUSED|ECONNRESET|socket hang up|network|load failed/i.test(msg) ||
    /ECONNREFUSED|ECONNRESET|ETIMEDOUT/i.test(code);
}

/** Provider-side retryability: HTTP 429 + transient 5xx (500/502/503/504;
 *  501 means "not implemented" and never retries). Returns nothing when the
 *  error isn't a recognized provider rejection. The SDK surfaces these as
 *  decoded objects — dig status/headers/message out defensively. */
export function providerRetryDelayMs(e: unknown, attempt: number): number | null {
  const raw = e as {
    status?: unknown; statusCode?: unknown; code?: unknown;
    headers?: unknown; message?: unknown; data?: { message?: unknown; status?: unknown };
    _tag?: unknown;
  };
  const num = (v: unknown): number | null =>
    typeof v === "number" && Number.isFinite(v) ? v :
    typeof v === "string" && /^\d{3}$/.test(v.trim()) ? parseInt(v.trim(), 10) : null;
  const status =
    num(raw?.status) ?? num(raw?.statusCode) ??
    (typeof raw?.code === "number" || (typeof raw?.code === "string" && /^\d{3}$/.test((raw.code as string).trim())) ? num(raw.code) : null) ??
    num(raw?.data?.status);
  const msg = String(
    (typeof raw?.data?.message === "string" && raw.data.message) ||
    (typeof raw?.message === "string" && raw.message) ||
    (typeof raw?._tag === "string" && raw._tag) ||
    "",
  );
  const retryableStatus = status === 429 || status === 500 || status === 502 || status === 503 || status === 504;
  const retryableText = /\b429\b|rate.?limit|too many requests|\b5\d\d\b|internal server error|bad gateway|service unavailable|gateway timeout|overloaded/i.test(msg);
  if (!retryableStatus && !retryableText) return null;
  if (attempt >= PROMPT_RETRY_DELAYS_MS.length) return null;
  // Honor Retry-After (seconds or HTTP date), capped to the schedule.
  let afterMs: number | null = null;
  try {
    const h = raw?.headers as Record<string, unknown> | undefined;
    const ra = h ? (h["retry-after"] ?? h["Retry-After"]) : undefined;
    if (typeof ra === "string" && ra.trim()) {
      const secs = Number(ra.trim());
      if (Number.isFinite(secs) && secs >= 0) afterMs = Math.min(secs * 1000, 30_000);
      else {
        const at = Date.parse(ra.trim());
        if (Number.isFinite(at)) afterMs = Math.min(Math.max(at - Date.now(), 0), 30_000);
      }
    }
  } catch {
    /* ignore */
  }
  return afterMs ?? PROMPT_RETRY_DELAYS_MS[attempt] ?? null;
}

/** One-line error shape for the log: constructor + own keys + cause code,
 *  so terse throws like bare "Transport" stay identifiable. */
function errShape(e: unknown): string {
  let keys = "";
  try {
    keys = Object.keys(e as Record<string, unknown>).join(",");
  } catch {
    /* ignore */
  }
  const ctor = (e as { constructor?: { name?: unknown } })?.constructor?.name;
  return `kind=${typeof ctor === "string" ? ctor : typeof e} keys=${keys.slice(0, 120)}`;
}

const STOP_DEADLINE_MS = 15_000;

/** Independent "is opencode still running this session" reading (session.active), so an
 *  interim message is never mistaken for the end of a long tool-heavy run. null = unknown. */
async function sessionWorking(sessionID: string): Promise<boolean | null> {
  try {
    const client = makeClient();
    const active = await withDeadline("active", 8_000, (signal) => client.session.active({ signal } as never));
    if (active && typeof active === "object") return Object.keys(active as Record<string, unknown>).includes(sessionID);
    return null;
  } catch {
    return null;
  }
}

/** Single-line error text out of the SDK's assorted throw shapes. */
function errText(e: unknown): string {
  const _raw = e as { message?: unknown; data?: { message?: unknown }; _tag?: unknown };
  return String(
    (typeof _raw?.data?.message === "string" && _raw.data.message) ||
      (typeof _raw?.message === "string" && _raw.message) ||
      (typeof _raw?._tag === "string" && _raw._tag) ||
      e,
  );
}

/** Dead server-side session: distinct from transient failure, never retried. */
export function isSessionGone(msg: string): boolean {
  return /no such session|session not found|not.?found|gone|does not exist|\b404\b/i.test(msg);
}

/** Prompt retry backoff: initial try + one wait per entry (500ms → 1.5s → 4s).
 *  The placeholder stays up throughout (~6s worst case), so blips self-heal
 *  with zero user action. Exported for tests. */
export const PROMPT_RETRY_DELAYS_MS = [500, 1500, 4000];
/** One reconcile pause before declaring a landed prompt lost. */
const RECONCILE_DELAY_MS = 3000;

/** Minimal surface the retry/reconcile helpers need (real client or test fake). */
export interface RetryClient {
  sessionPrompt(args: Record<string, unknown>): Promise<{ time?: { created?: number } }>;
  sessionWait(sessionID: string): Promise<void>;
  messageList(sessionID: string, limit: number, type?: string): Promise<ListedMsg[]>;
}

/** Message-list row shape the retry/reconcile helpers read. */
export interface ListedMsg {
  id?: unknown;
  messageID?: unknown;
  messageId?: unknown;
  type?: string;
  finish?: unknown;
  text?: string;
  error?: unknown;
  summary?: unknown;
  recent?: unknown;
  time?: { created?: number; completed?: number };
  content?: Array<{ type: string; text?: string; name?: string; state?: unknown }>;
  /** Present on completed assistant rows: the turn's own token counters. */
  tokens?: { input?: number; output?: number; reasoning?: number; cache?: { read?: number; write?: number } };
  cost?: number;
}

/** A row's token total: the whole prompt it carried plus what it generated.
 *  Cache reads count (they are context re-read, the dominant cost of a long
 *  session). 0 when the row has no counters (in-flight or non-assistant). */
export function listedTokens(m: ListedMsg): number {
  const t = m?.tokens;
  if (!t || typeof t !== "object") return 0;
  const n = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v > 0 ? v : 0);
  return n(t.input) + n(t.output) + n(t.reasoning) + n(t.cache?.read);
}

/** Readable text of a listed message: user turns carry top-level `text`
 *  with empty `content`; assistant turns carry `text` content parts. */
export function listedText(m: ListedMsg): string {
  if (typeof m?.text === "string" && m.text.trim()) return m.text.trim();
  return partsText((m?.content ?? []) as TextPart[]);
}

/** Provider-side failure text of a listed message, or "". Reads the
 *  message-level error payload plus error-typed content parts (provider
 *  rejections surface outside `text` parts, so the text extractors never
 *  see them). Capped; never throws. */
export function providerErrorText(m: ListedMsg): string {
  const flat = (v: unknown, max = 500): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  const fromObj = (o: Record<string, unknown>): string => {
    for (const k of ["message", "text", "detail", "reason"]) {
      const s = flat(o[k]);
      if (s) return s;
    }
    return "";
  };
  try {
    const e = (m as { error?: unknown })?.error;
    if (typeof e === "string" && e.trim()) return flat(e);
    if (e && typeof e === "object") {
      const s = fromObj(e as Record<string, unknown>);
      if (s) return s;
    }
    const parts = Array.isArray(m?.content) ? (m.content as Array<{ type?: unknown; text?: unknown }>) : [];
    for (const p of parts) {
      const t = typeof p?.type === "string" ? (p.type as string).toLowerCase() : "";
      if (t.includes("error") && typeof p?.text === "string" && (p.text as string).trim()) {
        return flat(p.text);
      }
    }
  } catch {
    /* none */
  }
  return "";
}

/** Stability signature of an assistant message list: per message id,
 *  bounds, text/reasoning lengths, tool count, and error presence. Drives
 *  the idle-settle rule (a frozen list on an idle session means the turn
 *  is dead no matter which shape the failure took). Exported for tests. */
export function assistantListSig(data: ListedMsg[]): string {
  return data.map((m) => {
    const raw = m as unknown as { id?: unknown; messageID?: unknown; messageId?: unknown };
    const id =
      (typeof raw?.id === "string" && raw.id) ||
      (typeof raw?.messageID === "string" && raw.messageID) ||
      (typeof raw?.messageId === "string" && raw.messageId) ||
      "";
    const created = m?.time?.created ?? 0;
    const completed = m?.time?.completed ?? 0;
    const parts = Array.isArray(m?.content) ? m.content : [];
    let textLen = 0;
    let reasonLen = 0;
    let tools = 0;
    for (const p of parts as Array<{ type?: unknown; text?: unknown; name?: unknown }>) {
      if (typeof p?.text === "string" && (p.text as string)) {
        const t = typeof p?.type === "string" ? (p.type as string) : "";
        if (t === "reasoning") reasonLen += (p.text as string).length;
        else if (t === "text") textLen += (p.text as string).length;
      }
      if (typeof p?.type === "string" && (p.type as string) === "tool") tools++;
    }
    if (typeof (m as ListedMsg)?.text === "string") textLen += ((m as ListedMsg).text as string).length;
    const err = providerErrorText(m).length > 0 ? 1 : 0;
    return `${id}:${created}:${completed}:${textLen}:${reasonLen}:${tools}:${err}`;
  }).join("|");
}

/** Nearest in-window provider error for a turn's prompt window, or null.
 *  Same window rule as the reply picker (created >= since - 60s,
 *  forward-only nearest): a stale error from an older turn must never
 *  settle a fresh one. `excludeTurns` (messageID → owning turnId) skips
 *  errors already settled to another turn — one errored message shared by
 *  merged or overlapping prompts must not settle the wrong turn. Callers
 *  check replies first; errors settle fast instead of looping to the
 *  long-turn cap on an empty text. */
export function pickErrorFromList(
  data: ListedMsg[],
  since: number,
  excludeTurns?: Map<string, string>,
  ownTurnId?: string,
): { text: string; messageID?: string } | null {
  const windowed = data.filter(
    (m) => (m?.time?.created ?? 0) >= since - 60_000 && providerErrorText(m).length > 0,
  );
  if (windowed.length === 0) return null;
  const settledElsewhere = (m: ListedMsg): boolean => {
    if (!excludeTurns) return false;
    const id = msgIdOf(m);
    return !!id && excludeTurns.get(id) !== undefined && excludeTurns.get(id) !== ownTurnId;
  };
  const unclaimed = windowed.filter((m) => !settledElsewhere(m));
  const pool0 = unclaimed.length > 0 ? unclaimed : windowed;
  const after = pool0.filter((m) => (m?.time?.created ?? 0) >= since);
  const pool = after.length > 0 ? after : pool0;
  let best: ListedMsg | undefined;
  let bestDist = Infinity;
  for (const m of pool) {
    const d = Math.abs((m?.time?.created ?? 0) - since);
    if (d < bestDist) {
      bestDist = d;
      best = m;
    }
  }
  if (!best) return null;
  const text = providerErrorText(best);
  const messageID = msgIdOf(best);
  return messageID ? { text, messageID } : { text };
}

/** Compaction summary excerpt of a listed message, or "". Compaction
 *  rewrites session history outside any turn: the summary lives in
 *  `summary`/`recent` fields (never in `text` parts), so the text
 *  extractors never see it. Capped; never throws. Exported for tests. */
export function compactionText(m: ListedMsg, max = 300): string {
  try {
    if (m?.type !== "compaction") return "";
    const flat = (v: unknown): string =>
      (typeof v === "string" ? v.replace(/\s+/g, " ").trim() : "");
    const s = flat(m?.summary) || flat(m?.recent);
    return s.slice(0, max);
  } catch {
    return "";
  }
}

/** Full compaction summary for the center-stage receipt row: newlines kept
 *  (headings/lists must survive as markdown), only \r stripped and runs of
 *  blank lines collapsed. Defensive cap far beyond any real summary —
 *  normal ones are a few KB; this guards the receipt, never edits content. */
export function compactionSummary(m: ListedMsg, max = 20000): string {
  try {
    if (m?.type !== "compaction") return "";
    const clean = (v: unknown): string => {
      if (typeof v !== "string") return "";
      return v.replace(/\r\n?/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
    };
    const s = clean(m?.summary) || clean(m?.recent);
    return s.length > max ? s.slice(0, max) : s;
  } catch {
    return "";
  }
}

/** Did our prompt text land in the session since `since` (ms)? A transport
 *  error means the request failed — but the response may have been the only
 *  thing lost. Verify before resending, or a retry double-prompts. Returns
 *  the landed message's created time, or 0. */
export function promptLandedInList(data: ListedMsg[], promptText: string, since: number): number {
  const want = promptText.trim();
  if (!want) return 0;
  for (const m of data) {
    if (m?.type !== "user") continue;
    const at = m?.time?.created ?? 0;
    if (at < since) continue;
    const stored = listedText(m);
    if (stored === want || (stored.length > want.length && stored.includes(want))) return at;
  }
  return 0;
}

/** Message ids already settled to a turn in this session (messageID →
 *  turnId). Lets overlapping turns skip each other's replies: without it,
 *  A's worker can pick B's newer message and B picks it too. TTL-pruned. */
const settledMsgTurn = new Map<string, { turnId: string; at: number }>();

/** Pick the turn's reply out of a message list. A turn owns its FINAL reply:
 *  agentic turns emit interim text (finish "tool-calls") before the answer,
 *  so nearest-to-prompt alone picks the preamble instead of the response.
 *  Preference: nearest forward in-window terminal message (finish "stop")
 *  not settled to another turn (own reply, including A-finishing-after-B)
 *  → nearest forward in-window terminal settled elsewhere (opencode fused
 *  the prompts into one message: both turns settle with it and the client's
 *  messageID merge rule renders one row as a pointer) → nearest forward
 *  in-window unclaimed even if interim (no finish info, or the turn truly
 *  ended mid-tools) → nearest forward in-window even if shared → null.
 *  `nearest` is forward-only: messages created at/after `since` win over
 *  earlier ones (a pre-prompt preamble must not beat the post-prompt final).
 *  Out-of-window history is NEVER substituted: attaching an unrelated
 *  older/newer message ("wrong reply on the correct turn") is worse than an
 *  explicit miss, and callers already record a clear error on null.
 *  `excludeTurns` maps messageID → owning turnId for this session; the
 *  caller's own turnId is never excluded. `since` is the turn's prompt time
 *  (60s grace for clock skew on the window edge). Creation predates the
 *  final for in-place updates, so finish time is never guessed from created:
 *  terminal tiers additionally require time.completed >= since
 *  (isReplyCompletedAfter) — a pre-prompt final only wins when no
 *  post-prompt finished candidate exists. Rows without a
 *  numeric completed skip terminal tiers (never guessed from created) but
 *  stay eligible as plain text below, so fused replies without completion
 *  metadata still settle. */

/** Strict completion-time guard (spec 14.3, P10): a listed message only
 *  counts as finished after `since` when its own completion time says so.
 *  Missing/non-numeric completed is false: finish time is never guessed from
 *  creation time (creation predates the final for in-place updates). */
export function isReplyCompletedAfter(msg: ListedMsg, since: number): boolean {
  const completed = msg?.time?.completed;
  return typeof completed === "number" && Number.isFinite(completed) && completed >= since;
}

export function pickReplyFromList(
  data: ListedMsg[],
  since: number,
  excludeTurns?: Map<string, string>,
  ownTurnId?: string,
): { text: string; messageID?: string; tokens?: number; terminal: boolean } | null {
  const nonEmpty = (m: ListedMsg): boolean => listedText(m).length > 0;
  const inWindow = (m: ListedMsg): boolean => (m?.time?.created ?? 0) >= since - 60_000 && nonEmpty(m);
  const settledElsewhere = (m: ListedMsg): boolean => {
    if (!excludeTurns) return false;
    const id = msgIdOf(m);
    return !!id && excludeTurns.get(id) !== undefined && excludeTurns.get(id) !== ownTurnId;
  };
  const nearest = (ms: ListedMsg[]): ListedMsg | undefined => {
    if (ms.length === 0) return undefined;
    // Forward-only: post-prompt candidates beat pre-prompt ones; a pre-prompt
    // message wins only when nothing was created at/after `since`.
    const after = ms.filter((m) => (m?.time?.created ?? 0) >= since);
    const pool = after.length > 0 ? after : ms;
    let best: ListedMsg | undefined;
    let bestDist = Infinity;
    for (const m of pool) {
      const d = Math.abs((m?.time?.created ?? 0) - since);
      if (d < bestDist) {
        bestDist = d;
        best = m;
      }
    }
    return best;
  };
  const windowed = data.filter(inWindow);
  const unclaimed = windowed.filter((m) => !settledElsewhere(m));
  const terminal = (m: ListedMsg): boolean => (m as { finish?: unknown })?.finish === "stop";
  const finished = (m: ListedMsg): boolean => terminal(m) && isReplyCompletedAfter(m, since);
  const unclaimedTerminal = unclaimed.filter(finished);
  const sharedTerminal = windowed.filter((m) => settledElsewhere(m) && finished(m));
  const picked =
    nearest(unclaimedTerminal) ??
    nearest(sharedTerminal) ??
    nearest(unclaimed) ??
    nearest(windowed) ??
    null;
  if (!picked) return null;
  const text = listedText(picked);
  const messageID = msgIdOf(picked);
  const tokens = listedTokens(picked);
  noteRow(messageID, picked as unknown as Record<string, unknown>);
  // terminal = the picked row is a finished final answer (finish "stop", completed after the
  // prompt). Anything else is interim text from a run that may still be going.
  return { text, ...(messageID ? { messageID } : {}), ...(tokens > 0 ? { tokens } : {}), terminal: finished(picked) };
}

/** Best-effort message id read shared by pick + settle bookkeeping. */
function msgIdOf(m: ListedMsg): string | undefined {
  const rawId = m as unknown as { id?: unknown; messageID?: unknown; messageId?: unknown };
  return (
    (typeof rawId?.id === "string" && rawId.id) ||
    (typeof rawId?.messageID === "string" && rawId.messageID) ||
    (typeof rawId?.messageId === "string" && rawId.messageId) ||
    undefined
  );
}

/** One-shot message list on a fresh client (for the reconcile/history paths,
 *  where the task's client may be in a broken state). Pass explicit types:
 *  omitting `type` returns assistant-only rows, which silently drops user
 *  turns (and would blind the landed-check below). */
async function messageListOnce(sessionID: string, limit: number, type?: string): Promise<ListedMsg[]> {
  const client = makeClient();
  const listed = (await client.message.list({
    sessionID,
    limit,
    order: "desc",
    ...(type ? { type } : {}),
  } as Parameters<typeof client.message.list>[0])) as { data?: ListedMsg[] };
  return listed.data ?? [];
}

/** Prompt with bounded backoff. Transport failures retry (provably safe only
 *  after the landed-check below); anything else throws immediately. Returns
 *  the prompt's created time for the reply window. */
export async function promptWithBackoff(
  rc: RetryClient,
  sessionID: string,
  args: Record<string, unknown>,
  promptText: string,
  chatId: string,
): Promise<number> {
  const t0 = Date.now();
  for (let attempt = 0; ; attempt++) {
    try {
      const inbox = await rc.sessionPrompt(args);
      return inbox?.time?.created ?? Date.now();
    } catch (e) {
      const rateMs = providerRetryDelayMs(e, attempt);
      if (rateMs === null) {
        if (!isTransportError(e)) throw e;
        try {
          const landed = promptLandedInList(await rc.messageList(sessionID, 10, "user"), promptText, t0);
          if (landed) {
            console.log(`opencode-proxy chat: prompt landed despite transport error, waiting chat=${chatId}`);
            return landed;
          }
        } catch {
          /* reconcile unreadable — fall through to the scheduled retry */
        }
        const waitMs = PROMPT_RETRY_DELAYS_MS[attempt];
        if (waitMs === undefined) throw e;
        console.log(
          `opencode-proxy chat: prompt transport failed, retry ${attempt + 1}/${PROMPT_RETRY_DELAYS_MS.length} in ${waitMs}ms chat=${chatId}`,
        );
        await sleep(waitMs);
      } else {
        // Definite temporary provider rejection: resend only after proving
        // the earlier attempt didn't land (same safety as transport).
        try {
          const landed = promptLandedInList(await rc.messageList(sessionID, 10, "user"), promptText, t0);
          if (landed) {
            console.log(`opencode-proxy chat: prompt landed despite provider rejection, waiting chat=${chatId}`);
            return landed;
          }
        } catch {
          /* reconcile unreadable — fall through to the scheduled retry */
        }
        console.log(
          `opencode-proxy chat: provider busy, retry ${attempt + 1}/${PROMPT_RETRY_DELAYS_MS.length} in ${rateMs}ms chat=${chatId}`,
        );
        await sleep(rateMs);
      }
    }
  }
}

/** Wait retry: session.wait holds one long request open for the whole run,
 *  and its death mid-run is the common failure behind "opencode unavailable
 *  (wait)" rows — even when the agent already did the work. Re-waiting is
 *  side-effect free (waiting can't duplicate anything), so transport drops
 *  retry with backoff until the deadline, then the normal fetch decides.
 *  Dead sessions throw immediately; non-transport rejections return so the
 *  fetch can surface the run's real outcome. Exported for tests. */
export const WAIT_RETRY_DEADLINE_MS = 120_000;
export const WAIT_RETRY_DELAYS_MS = [2000, 5000, 10000];
/** Long-turn ceiling: coding runs outlast any single wait, so wait rounds
 *  repeat until a terminal reply lands or this hits. 3 hours: big agentic
 *  runs routinely pass 45 minutes, and a waiter loop is ~zero cost
 *  (one wait + one list check per round). Only then is "no reply" real. */
export const LONG_TURN_CAP_MS = 3 * 60 * 60 * 1000;
/** Pending-turn horizon: a turn can legitimately run to the long-turn cap,
 *  so its pending entry must outlive the cap (plus margin) — never the
 *  10-minute accept TTL. Pruning pending at the accept horizon made healthy
 *  long turns read "unknown" mid-run (reconcile rows on live turns, with
 *  may-run-twice resend offers). Still bounded: settles untrack at once. */
export const PENDING_TTL_MS = LONG_TURN_CAP_MS + 10 * 60 * 1000;
/** Breather between wait rounds so an instantly-returning wait can't
 *  hot-loop the message list. */
export const WAIT_ROUND_GAP_MS = 10_000;
export async function sessionWaitWithRetry(
  rc: RetryClient,
  sessionID: string,
  chatId: string,
  deadlineMs: number = WAIT_RETRY_DEADLINE_MS,
): Promise<void> {
  const t0 = Date.now();
  let attempt = 0;
  for (;;) {
    try {
      await rc.sessionWait(sessionID);
      return;
    } catch (e) {
      if (isSessionGone(errText(e))) throw e;
      if (!isTransportError(e)) return;
      if (Date.now() - t0 >= deadlineMs) throw e;
      const waitMs = WAIT_RETRY_DELAYS_MS[Math.min(attempt, WAIT_RETRY_DELAYS_MS.length - 1)];
      attempt++;
      console.log(`opencode-proxy chat: wait transport failed, retry in ${waitMs}ms chat=${chatId}`);
      await sleep(waitMs);
    }
  }
}

/** Shared: read a sessionID from a POST body. 400 when missing/malformed. */
/** A non-opencode remote session id (uuid-like): conservative charset and length, never a path. */
export function otherSessionID(v: unknown): { ok: true; sessionID: string } | { ok: false } {
  const t = typeof v === "string" ? v.trim() : "";
  return /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(t) ? { ok: true, sessionID: t } : { ok: false };
}

function needSessionID(body: Record<string, unknown>): { ok: true; sessionID: string } | { ok: false } {
  const sid = cleanSessionID(body.sessionID);
  return sid ? { ok: true, sessionID: sid } : { ok: false };
}

/** Drop stream correlation for a dead session: restarted/deleted ids must
 *  never keep a pre-send stream (or a later turn) polling them. */
/** Non-opencode sessions deleted through /api/session-delete. Opencode reports its own death with a 404;
 *  for codex/claude this set is how progress and stop read the same authoritative "gone". */
const deletedRemoteSessions = new Set<string>();
function markRemoteSessionDeleted(sessionID: string): void {
  deletedRemoteSessions.add(sessionID);
  if (deletedRemoteSessions.size > 500) deletedRemoteSessions.delete(deletedRemoteSessions.values().next().value as string);
}

function forgetSessionChats(sessionID: string): void {
  for (const [chatId, sid] of lastSessionByChat) {
    if (sid === sessionID) lastSessionByChat.delete(chatId);
  }
}

/** GET /api/title?sessionID=&backend=&chatId= — read a provider-assigned
 *  title for syncing app chat names. A blank title leaves the local fallback
 *  in place. */
export async function handleTitle(req: IncomingMessage, res: ServerResponse, json: Json) {
  let url: URL;
  try {
    url = new URL(req.url || "", "http://x");
  } catch {
    json(res, 400, { error: "bad title request" });
    return;
  }
  const rawSessionID = url.searchParams.get("sessionID") || "";
  const rawBackend = url.searchParams.get("backend");
  const chatId = (url.searchParams.get("chatId") || "").trim();
  let backend: BackendId = "opencode";
  if (chatId && !isChatId(chatId)) {
    json(res, 400, { error: "bad chatId" });
    return;
  }
  if (chatId) {
    const routed = resolveRouteBackend(rawBackend, res, json, chatId, "getTitle");
    if (!routed) return;
    backend = routed;
  } else if (rawBackend && rawBackend.trim()) {
    try {
      backend = parseBackendId(rawBackend.trim());
    } catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "getTitle"));
        return;
      }
      throw e;
    }
  }
  if (backend !== "opencode") {
    const sessionID = rawSessionID.trim().slice(0, 200);
    if (!sessionID || !chatId) {
      json(res, 400, { error: "bad sessionID or missing chatId" });
      return;
    }
    const { getAdapter } = await import("./backends/registry.js");
    const { backendErrorBody } = await import("./turn-runtime.js");
    try {
      const adapter = getAdapter(backend);
      const title = await adapter.getTitle?.({ chatId, remoteSessionId: sessionID }) ?? "";
      json(res, 200, { title: typeof title === "string" ? title : "" });
    } catch (e) {
      const { status, body } = backendErrorBody(e, backend, "getTitle");
      json(res, status, body);
    }
    return;
  }
  const sessionID = cleanSessionID(rawSessionID);
  if (!sessionID) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  try {
    const client = makeClient();
    const s = (await withDeadline("get", MODELS_DEADLINE_MS, (signal) =>
      client.session.get({ sessionID }, { signal }),
    )) as { title?: unknown };
    json(res, 200, { title: typeof s?.title === "string" ? s.title : "" });
  } catch (e) {
    if (isNoSession(e)) {
      json(res, 404, { error: "no session" });
      return;
    }
    json(res, 503, { error: "opencode unavailable" });
  }
}

/** POST /api/title {sessionID, title} — push a manual rename into the
 *  opencode session (session.update). Local rename already applied + locked
 *  by the app; this is best-effort propagation so /api/sessions and other
 *  readers show the same name. 400 on bad id or blank title (blank never
 *  clears a server title); 404 when opencode lost it. */
export async function handleTitleSet(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { sessionID?: unknown; title?: unknown; backend?: unknown; chatId?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const chk = needSessionID(body as Record<string, unknown>);
  const title = typeof body.title === "string" ? body.title.trim().slice(0, 120) : "";
  if (chk.ok !== true || !title) {
    json(res, 400, { error: "bad sessionID or blank title" });
    return;
  }
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  let backend: BackendId = "opencode";
  if (body.backend !== undefined) {
    try { backend = parseBackendId(String(body.backend)); }
    catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "setTitle"));
        return;
      }
      throw e;
    }
  }
  if (backend !== "opencode") {
    if (!chatId || !isChatId(chatId)) {
      json(res, 400, { error: "bad or missing chatId" });
      return;
    }
    const routed = resolveRouteBackend(backend, res, json, chatId, "setTitle");
    if (!routed) return;
    const { getAdapter } = await import("./backends/registry.js");
    const { backendErrorBody } = await import("./turn-runtime.js");
    try {
      const adapter = getAdapter(routed);
      if (!adapter.setTitle) {
        json(res, 501, { error: "unsupported_operation", backend: routed, operation: "setTitle" });
        return;
      }
      await adapter.setTitle({ chatId, remoteSessionId: chk.sessionID, title });
      json(res, 200, { ok: true });
    } catch (e) {
      const { status, body: errBody } = backendErrorBody(e, routed, "setTitle");
      json(res, status, errBody);
    }
    return;
  }
  try {
    const client = makeClient();
    await withDeadline("title-set", MODELS_DEADLINE_MS, (signal) =>
      (client.session.update as unknown as (
        args: Record<string, unknown>,
        opts?: Record<string, unknown>,
      ) => Promise<unknown>)(
        { sessionID: chk.sessionID, title },
        { signal },
      ),
    );
  } catch (e) {
    if (isNoSession(e)) {
      json(res, 404, { error: "no session" });
      return;
    }
    json(res, 503, { error: "opencode unavailable" });
    return;
  }
  json(res, 200, { ok: true });
}
/** POST /api/stop {sessionID} — interrupt the running opencode turn.
 *  404 when opencode lost the session. */
/** POST /api/stop {sessionID, chatId?, backend?, turnId?} — interrupt the
 *  opencode session (whole-session semantics preserved). chatId/backend,
 *  when present, are validated against the chat's bound backend (409 on
 *  mismatch); non-opencode backends are 501 until their adapters wire
 *  interrupt in M7/M8. */
export async function handleStop(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { sessionID?: unknown; chatId?: unknown; backend?: unknown; turnId?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  // Bug-1 fix: resolve the backend BEFORE session validation. Opencode
  // ids are ses_-shaped; codex/claude ids are opaque (UUIDs etc). A blank
  // id with a chat is stop-by-chat. Validating ses_ shape first 400s every
  // non-opencode stop before it reaches the backend.
  const stopChatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  const rawBackend = typeof body.backend === "string" && body.backend.trim() ? body.backend.trim() : null;
  let stopBackend = "opencode";
  if (rawBackend) {
    try {
      stopBackend = parseBackendId(rawBackend);
    } catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "interruptTurn"));
        return;
      }
      throw e;
    }
  } else if (stopChatId) {
    stopBackend = backendOfChat(stopChatId) ?? "opencode";
  }
  if (stopChatId && !isChatId(stopChatId)) {
    json(res, 400, { error: "bad chatId" });
    return;
  }
  // Stop ends the automation too: the loop pauses, one tap resumes it.
  if (stopChatId) pauseGoal(stopChatId);
  // A stopped turn is cancelled, not a result: when it finally settles on
  // the server it must not be judged or nudge the loop.
  if (stopChatId) {
    for (const p of pendingByChat.get(stopChatId) ?? []) cancelledTurnKeys.add(turnKey(stopChatId, p.turnId));
    if (typeof body.turnId === "string" && body.turnId) cancelledTurnKeys.add(turnKey(stopChatId, body.turnId));
  }
  if (stopChatId && stopBackend !== "opencode") {
    const chk = checkRouteBackend(stopChatId, stopBackend as BackendId);
    if (!chk.ok) {
      json(res, 409, { error: "backend_mismatch", backend: stopBackend, expected: chk.expected, chatId: stopChatId });
      return;
    }
  }
  const rawSid = typeof body.sessionID === "string" ? body.sessionID.trim().slice(0, 200) : "";
  const sessionID = stopBackend === "opencode" ? cleanSessionID(rawSid) : rawSid;
  if (stopBackend === "opencode" && rawSid && !sessionID) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  if (stopBackend !== "opencode") {
    // Turn-scoped interrupt via the runtime (named turn or every pending
    // turn of the chat). Needs the chat for pending lookup.
    if (!stopChatId) {
      json(res, 400, { error: "chatId required" });
      return;
    }
    if (rawSid && deletedRemoteSessions.has(rawSid)) {
      json(res, 404, { error: "no session" });
      return;
    }
    const { getAdapter } = await import("./backends/registry.js");
    const { stopBackendTurns, backendErrorBody } = await import("./turn-runtime.js");
    try {
      const turnId = typeof body.turnId === "string" && body.turnId.trim() ? body.turnId.trim() : undefined;
      const { stopped } = await stopBackendTurns(getAdapter(stopBackend as BackendId), stopChatId, sessionID, turnId);
      json(res, 200, { ok: true, stopped, backend: stopBackend });
    } catch (e) {
      const { status, body: errBody } = backendErrorBody(e, stopBackend as BackendId, "interruptTurn");
      json(res, status, errBody);
    }
    return;
  }
  if (!sessionID) {
    // Opencode stop with no session: nothing running to interrupt.
    json(res, 200, { ok: true, stopped: false });
    return;
  }
  try {
    const client = makeClient();
    await withDeadline("interrupt", STOP_DEADLINE_MS, (signal) =>
      client.session.interrupt({ sessionID }, { signal }),
    );
    json(res, 200, { ok: true, stopped: true });
  } catch (e) {
    if (isNoSession(e)) {
      json(res, 404, { error: "no session" });
      return;
    }
    console.log("opencode-proxy stop: opencode request failed");
    json(res, 503, { error: "opencode unavailable" });
  }
}

/** POST /api/session-delete {sessionID} — interrupt + delete the opencode
 *  session for good. Always ok; killed:false when opencode never had it. */
export async function handleSessionDelete(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { sessionID?: unknown; backend?: unknown; chatId?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const rawBackend = typeof body.backend === "string" && body.backend.trim() ? body.backend.trim() : null;
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  if (rawBackend || chatId) {
    // Backend-aware path (spec 31/32): explicit backend wins, else the
    // chat's bound backend, else opencode. Reads validate, never bind.
    let backend: BackendId = "opencode";
    try {
      backend = rawBackend ? parseBackendId(rawBackend) : (chatId ? backendOfChat(chatId) ?? "opencode" : "opencode");
    } catch (e) {
      if (e instanceof BackendError) {
        json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "deleteSession"));
        return;
      }
      throw e;
    }
    if (chatId && !isChatId(chatId)) {
      json(res, 400, { error: "bad chatId" });
      return;
    }
    if (chatId) {
      const chk = checkRouteBackend(chatId, backend);
      if (!chk.ok) {
        json(res, 409, { error: "backend_mismatch", backend, expected: chk.expected, chatId });
        return;
      }
    }
    if (backend !== "opencode") {
      // Provider-native remote id (codex thread, ...): opaque, validated
      // by the adapter call itself — never opencode shape-checked.
      const sessionID = typeof body.sessionID === "string" ? body.sessionID.trim().slice(0, 200) : "";
      if (!sessionID) {
        json(res, 400, { error: "bad sessionID" });
        return;
      }
      const { getAdapter } = await import("./backends/registry.js");
      const { backendErrorBody } = await import("./turn-runtime.js");
      try {
        const adapter = getAdapter(backend);
        if (!adapter.deleteSession) {
          throw new BackendError("unsupported_operation", "delete unproven", false, backend, "deleteSession");
        }
        await adapter.deleteSession({ chatId, remoteSessionId: sessionID });
        markRemoteSessionDeleted(sessionID);
        forgetSessionChats(sessionID);
        json(res, 200, { ok: true, killed: true, backend });
      } catch (e) {
        const { status, body: errBody } = backendErrorBody(e, backend, "deleteSession");
        json(res, status, errBody);
      }
      return;
    }
    // else: opencode with explicit binding context — fall through below.
  }
  const chk = needSessionID(body as Record<string, unknown>);
  if (chk.ok !== true) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  const target = chk.sessionID;
  try {
    const client = makeClient();
    await withDeadline("interrupt", STOP_DEADLINE_MS, (signal) =>
      client.session.interrupt({ sessionID: target }, { signal }),
    );
  } catch { /* best-effort */ }
  try {
    const client = makeClient();
    await withDeadline("remove", STOP_DEADLINE_MS, (signal) =>
      (client.session as unknown as {
        remove: (args: { sessionID: string }, opts?: { signal: AbortSignal }) => Promise<unknown>;
      }).remove({ sessionID: target }, { signal }),
    );
    forgetSessionChats(target);
    json(res, 200, { ok: true, killed: true });
  } catch {
    // Gone already (or unreachable after a successful interrupt): either
    // way nothing owns it now.
    forgetSessionChats(target);
    json(res, 200, { ok: true, killed: false });
  }
}
/** POST /api/fork {sessionID, messageID?} — fork the opencode session (at
 *  messageID if given, else latest) → {ok, sessionID:fork}. The app adopts
 *  the fork id as the twin chat's remoteId and duplicates visible history
 *  locally. Both chats then live independently. 400 on bad ids. */
export async function handleFork(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { sessionID?: unknown; messageID?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const chk = needSessionID(body as Record<string, unknown>);
  const messageID = typeof body.messageID === "string" ? body.messageID.trim().slice(0, 200) : "";
  if (chk.ok !== true) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  try {
    const client = makeClient();
    const forked = (await withDeadline("fork", MODELS_DEADLINE_MS, (signal) =>
      client.session.fork(
        {
          sessionID: chk.sessionID,
          ...(messageID ? { before: messageID } : {}),
        },
        { signal },
      ),
    )) as { id?: string };
    if (!forked?.id) throw new Error("fork failed");
    markOwned("opencode", forked.id);
    json(res, 200, { ok: true, sessionID: forked.id });
  } catch (e) {
    if (isNoSession(e)) {
      json(res, 404, { error: "no session" });
      return;
    }
    const msg = String((e as Error)?.message ?? e);
    if (/empty session/i.test(msg)) {
      json(res, 400, { error: "nothing to fork yet — send a message first" });
      return;
    }
    console.log("opencode-proxy fork: opencode request failed");
    json(res, 503, { error: "opencode unavailable" });
  }
}
/** POST /api/steer {chatId, turnId, backend?} — "send now" for a message already waiting behind the
 *  running reply: fold it into that reply. {steered:false} = nothing changed (already running, already
 *  done, or the backend cannot), and the message stays queued. */
export async function handleSteer(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { chatId?: unknown; turnId?: unknown; backend?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  const turnId = typeof body.turnId === "string" ? body.turnId.trim() : "";
  if (!chatId || !isChatId(chatId) || !turnId) {
    json(res, 400, { error: "chatId and turnId required" });
    return;
  }
  const rawBackend = typeof body.backend === "string" && body.backend.trim() ? body.backend.trim() : null;
  let backend: BackendId;
  try {
    backend = rawBackend ? parseBackendId(rawBackend) : backendOfChat(chatId) ?? "opencode";
  } catch (e) {
    if (e instanceof BackendError) {
      json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "steerQueued"));
      return;
    }
    throw e;
  }
  if (backend === "opencode") {
    json(res, 200, { ok: true, steered: false, backend });
    return;
  }
  const chk = checkRouteBackend(chatId, backend);
  if (!chk.ok) {
    json(res, 409, { error: "backend_mismatch", backend, expected: chk.expected, chatId });
    return;
  }
  const { getAdapter } = await import("./backends/registry.js");
  const { steerQueuedTurn } = await import("./turn-runtime.js");
  const steered = await steerQueuedTurn(getAdapter(backend), chatId, turnId);
  json(res, 200, { ok: true, steered, backend });
}

/** POST /api/queued-cancel {chatId, turnId, backend?}. This is intentionally
 *  queue-only: if execution has started, leave it alone and report false. */
export async function handleQueuedCancel(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { chatId?: unknown; turnId?: unknown; backend?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const chatId = typeof body.chatId === "string" ? body.chatId.trim() : "";
  const turnId = typeof body.turnId === "string" ? body.turnId.trim() : "";
  if (!chatId || !isChatId(chatId) || !turnId) {
    json(res, 400, { error: "chatId and turnId required" });
    return;
  }
  const rawBackend = typeof body.backend === "string" && body.backend.trim() ? body.backend.trim() : null;
  let backend: BackendId;
  try {
    backend = rawBackend ? parseBackendId(rawBackend) : backendOfChat(chatId) ?? "opencode";
  } catch (e) {
    if (e instanceof BackendError) {
      json(res, e.code === "invalid_request" ? 400 : 501, toPublicError(e, undefined, "abortQueued"));
      return;
    }
    throw e;
  }
  if (backend === "opencode") {
    json(res, 200, { ok: true, cancelled: false, backend });
    return;
  }
  const chk = checkRouteBackend(chatId, backend);
  if (!chk.ok) {
    json(res, 409, { error: "backend_mismatch", backend, expected: chk.expected, chatId });
    return;
  }
  const { getAdapter } = await import("./backends/registry.js");
  const { abortQueuedBackendTurn } = await import("./turn-runtime.js");
  const cancelled = await abortQueuedBackendTurn(getAdapter(backend), chatId, turnId);
  json(res, 200, { ok: true, cancelled, backend });
}
/** POST /api/compact {sessionID} — summarize the opencode session into a
 *  compacted context (server keeps the same session id). 400/404 on bad id
 *  or no mapping; the app posts a tool row and the next turn continues
 *  compacted. */
/** Model context windows from the catalog (opencode), cached. Empty when the catalog carries no limits. */
const windowCache = { at: 0, map: new Map<string, number>() };

async function opencodeWindow(ref: string): Promise<number | null> {
  if (Date.now() - windowCache.at > 10 * 60_000) {
    try {
      const client = makeClient();
      const dir = locationDir();
      const [providers, models] = await withDeadline("models", MODELS_DEADLINE_MS, (signal) => {
        const opts = { signal };
        return Promise.all([
          client.provider.list(undefined, opts),
          client.model.list({ location: { directory: dir } }, opts),
        ]);
      });
      const map = new Map<string, number>();
      const take = (providerID: string, modelID: string, m: unknown) => {
        const ctx = (m as { limit?: { context?: unknown } } | null)?.limit?.context;
        if (typeof ctx === "number" && ctx > 0) map.set(`${providerID}/${modelID}`, ctx);
      };
      const pr = providers as { all?: unknown; data?: { all?: unknown } };
      const all = (Array.isArray(pr.all) ? pr.all : Array.isArray(pr.data?.all) ? pr.data.all : []) as Array<{ id?: string; models?: Record<string, unknown> }>;
      for (const p of all) for (const [mid, m] of Object.entries(p.models ?? {})) take(p.id ?? "", mid, m);
      for (const m of ((models as { data?: Array<{ providerID: string; modelID: string }> }).data ?? [])) take(m.providerID, m.modelID, m);
      windowCache.map = map;
      windowCache.at = Date.now();
    } catch {
      windowCache.at = Date.now() - 9 * 60_000; // retry in a minute
    }
  }
  return windowCache.map.get(ref) ?? null;
}

/** GET /api/context?chatId=&model=&totals=1: context size, token totals and cost for one chat. */
export async function handleContext(req: IncomingMessage, res: ServerResponse, json: Json) {
  const q = new URL(req.url || "", "http://x").searchParams;
  const chatId = q.get("chatId") || "";
  if (!chatId) {
    json(res, 400, { error: "chatId required" });
    return;
  }
  const sessionID = lastSessionByChat.get(chatId);
  let u = getUsage(chatId);
  if (q.get("totals") === "1" && sessionID && (!u || u.backend === "opencode")) {
    try {
      const rows = (await messageListOnce(sessionID, 300)) as unknown as Array<Record<string, unknown>>;
      const newest = rows.find((r) => r.tokens && typeof r.tokens === "object");
      if (newest && !u) {
        const id = msgIdOf(newest as ListedMsg);
        noteRow(id, newest);
        recordOpencodeTurn(chatId, id);
      }
      setOpencodeTotals(chatId, rows);
      u = getUsage(chatId);
    } catch {
      // totals are best-effort; the ring still reads what the last turn recorded
    }
  }
  const compactAt = (await loadDefaults()).compactAt;
  if (!u) {
    json(res, 200, { backend: null, used: 0, window: null, compactAt });
    return;
  }
  const model = q.get("model") || u.model || "";
  const window = u.window ?? (u.backend === "opencode" && model ? await opencodeWindow(model) : null);
  json(res, 200, { ...u, model: model || null, window, compactAt });
}

/** opencode v2 answers a compact request when it accepts it, before the summary exists. */
const COMPACT_WAIT_MS = 120_000;

async function compactOpencodeSession(sessionID: string): Promise<void> {
  const client = makeClient();
  await withDeadline("compact", MODELS_DEADLINE_MS, (signal) =>
    client.session.compact({ sessionID }, { signal }),
  );
}

/** Catch it ahead: compact before a turn when the last completed one already carried the ceiling. */
export function shouldCompactBefore(compactAt: number, lastTokens: number | undefined): boolean {
  return compactAt > 0 && typeof lastTokens === "number" && lastTokens >= compactAt;
}

/** Summary of the newest compaction created at or after `since` (ms), or "" when that one has not
 *  landed yet. Never an older compaction's text: the list is newest first, so keeping the last
 *  non-empty entry used to return the oldest summary of the session. Exported for tests. */
export function newestCompactionSummary(listed: ListedMsg[], since: number): string {
  let best = "";
  let bestAt = -1;
  for (const m of listed) {
    const text = compactionSummary(m);
    const at = m?.time?.created ?? 0;
    if (text && at >= since && at > bestAt) {
      best = text;
      bestAt = at;
    }
  }
  return best;
}

export async function handleCompact(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { sessionID?: unknown; backend?: unknown; chatId?: unknown };
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  let backend: BackendId;
  try { backend = parseBackendId(body.backend ?? "opencode"); }
  catch { json(res, 400, { error: "bad backend" }); return; }
  // Only opencode ids look like ses_…; claude/codex ids are uuids. The adapter validates its own format.
  const chk = backend === "opencode" ? needSessionID(body as Record<string, unknown>) : otherSessionID(body.sessionID);
  if (chk.ok !== true) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  if (backend !== "opencode") {
    const adapter = getAdapter(backend);
    if (!adapter.descriptor().capabilities.compact || !adapter.compact) {
      json(res, 501, { error: `${backend} compact unavailable` });
      return;
    }
    try {
      // Which chat it is lets claude hold that chat's next turn until the compact run ends.
      const compactChat = typeof body.chatId === "string" && isChatId(body.chatId.trim()) ? body.chatId.trim() : "";
      await adapter.compact({ chatId: compactChat, remoteSessionId: chk.sessionID });
      json(res, 200, { message: backend === "claude" ? "context compacted, history kept" : "context compact requested", summary: "" });
    } catch (e) {
      const pub = toPublicError(e);
      json(res, backendHttpStatus(e instanceof BackendError ? e.code : "unknown"), { error: pub.message });
    }
    return;
  }
  const compactStart = Date.now();
  try {
    await compactOpencodeSession(chk.sessionID);
  } catch (e) {
    if (isNoSession(e)) {
      json(res, 404, { error: "no session" });
      return;
    }
    json(res, 503, { error: "opencode unavailable" });
    return;
  }
  // Return the summary with the receipt: the compact call itself yields no
  // content, and without this read-back the app shows a bare "compacted"
  // while the actual summary never reaches it. `message` stays a short
  // receipt line; `summary` carries the full text (line breaks kept) for
  // the center-stage receipt row. The 300-char excerpt lives on only in
  // history markers.
  let message = "context compacted, history kept";
  let summary = "";
  // The chat's old token count no longer describes this session: the next turn must not compact again.
  for (const [chatId, sid] of lastSessionByChat) if (sid === chk.sessionID) noteCompacted(chatId);
  // opencode only accepts the request here (it runs at the next safe point and the summary is written
  // afterwards), so wait for the compaction this call created instead of reporting done too early or
  // settling for an older summary.
  const waitUntil = Date.now() + COMPACT_WAIT_MS;
  for (;;) {
    try {
      summary = newestCompactionSummary(await messageListOnce(chk.sessionID, 10, "compaction"), compactStart - 2000);
    } catch {
      // Read-back is best-effort; the canned receipt still stands.
    }
    if (summary || Date.now() >= waitUntil) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  // Flag the session so the next turn re-folds the effective system prompt.
  reseedAfterCompact.add(chk.sessionID);
  json(res, 200, { ok: true, message, ...(summary ? { summary } : {}) });
}

const PROGRESS_DEADLINE_MS = 30_000;
const PROGRESS_STEP_CAP = 20;

export type ProgressKind = "think" | "tool" | "text";
export type ProgressStep = { kind: ProgressKind; label: string; detail?: string; at: number; files?: string[] };
export type ProgressStatus = "idle" | "working" | "done";

type ProgressPart = {
  type?: string;
  text?: string;
  name?: string;
  state?: { input?: unknown };
};
type ProgressMsg = {
  type?: string;
  time?: { created?: number };
  content?: ProgressPart[];
};

/** One-line summary of a tool-call input (string or object), capped. */
function summarizeInput(input: unknown, max = 200): string {
  if (typeof input === "string") return input.replace(/\s+/g, " ").trim().slice(0, max);
  if (input && typeof input === "object") {
    try {
      return (Object.entries(input as Record<string, unknown>)
        .map(([k, v]) => {
          const vs = typeof v === "string" ? v : (JSON.stringify(v) ?? "?");
          return `${k}=${String(vs).replace(/\s+/g, " ").trim().slice(0, 60)}`;
        })
        .join(" ")
        .slice(0, max));
    } catch {
      return "";
    }
  }
  return "";
}

/** Pure: live steps for the CURRENT turn — assistant content after the last
 *  user message, chronological. Reasoning parts label truncated; tool parts
 *  on tool name + input summary. Reply text is NEVER a row (it travels via
 *  deltas/`done`). Most recent last, capped at 20. */
export function buildProgressSteps(messages: ProgressMsg[]): ProgressStep[] {
  const chrono = [...messages].sort((a, b) => (a?.time?.created ?? 0) - (b?.time?.created ?? 0));
  let cut = 0;
  chrono.forEach((m, i) => {
    if (m?.type === "user") cut = i + 1;
  });
  const out: ProgressStep[] = [];
  for (const m of chrono.slice(cut)) {
    if (m?.type !== "assistant" || !Array.isArray(m.content)) continue;
    const at = typeof m.time?.created === "number" ? m.time.created : 0;
    for (const p of m.content) {
      if (!p || typeof p.type !== "string") continue;
      if (p.type === "reasoning" && typeof p.text === "string" && p.text.trim()) {
        const flat = p.text.replace(/\s+/g, " ").trim();
        const step: ProgressStep = { kind: "think", label: flat.slice(0, 160), at };
        out.push(step);
      } else if (p.type === "tool" && typeof p.name === "string" && p.name) {
        const step: ProgressStep = { kind: "tool", label: p.name.slice(0, 120), at };
        const d = summarizeInput(p.state?.input);
        if (d) step.detail = d;
        const tf = stepFiles(p.name, p.state?.input);
        if (tf.length) step.files = tf;
        out.push(step);
      }
    }
  }
  return out.slice(-PROGRESS_STEP_CAP);
}

/** GET /api/history?sessionID= — the session's readable transcript for
 *  import backfill (user + assistant-text rows, chronological, capped).
 *  404 when opencode lost it (import opens empty, as before). Pure mapping
 *  via historyFromMessages, tested. */
export interface HistoryMsg {
  role: string;
  text: string;
  ts: number;
}

const HISTORY_CAP = 200;

export function historyFromMessages(data: ListedMsg[]): HistoryMsg[] {
  const chrono = [...data].sort((a, b) => (a?.time?.created ?? 0) - (b?.time?.created ?? 0));
  const out: HistoryMsg[] = [];
  for (const m of chrono) {
    // Compaction markers survive as tool rows: without this, a compacted
    // session imports as if the summary never happened.
    if (m?.type === "compaction") {
      const excerpt = compactionText(m);
      out.push({
        role: "tool",
        text: excerpt ? `context compacted: ${excerpt}` : "context compacted",
        ts: m?.time?.created ?? 0,
      });
      continue;
    }
    if (m?.type !== "user" && m?.type !== "assistant") continue;
    const text = listedText(m);
    if (!text) continue;
    out.push({ role: m.type as string, text, ts: m?.time?.created ?? 0 });
  }
  return out.slice(-HISTORY_CAP);
}

export async function handleHistory(req: IncomingMessage, res: ServerResponse, json: Json) {
  let sessionID = "";
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    const raw = q.get("backend")?.trim();
    if (raw && raw !== "opencode") {
      // Non-opencode: ids are backend-native, so the adapter validates them.
      const backend = parseBackendId(raw);
      const { getAdapter } = await import("./backends/registry.js");
      const { backendErrorBody } = await import("./turn-runtime.js");
      try {
        const id = (q.get("sessionID") ?? "").trim().slice(0, 200);
        const h = await getAdapter(backend).history?.({ chatId: "", remoteSessionId: id, limit: HISTORY_CAP });
        json(res, 200, { messages: (h?.messages ?? []).map((m) => ({ role: m.role, text: m.text, ts: m.at ?? 0 })) });
      } catch (e) {
        const { status, body } = backendErrorBody(e, backend, "history");
        json(res, status, body);
      }
      return;
    }
    sessionID = cleanSessionID(q.get("sessionID"));
  } catch (e) {
    if (e instanceof BackendError) {
      json(res, 400, toPublicError(e, undefined, "history"));
      return;
    }
    /* malformed url → 400 below */
  }
  if (!sessionID) {
    json(res, 400, { error: "bad sessionID" });
    return;
  }
  try {
    // One call per role: the filter takes a single type, and omitting it
    // returns assistant-only rows (user turns would silently vanish).
    const [users, assistants] = await Promise.all([
      messageListOnce(sessionID, HISTORY_CAP, "user"),
      messageListOnce(sessionID, HISTORY_CAP, "assistant"),
    ]);
    const seen = new Set<string>();
    const merged = [...users, ...assistants].filter((m) => {
      const id = String(m?.id ?? "");
      if (id && seen.has(id)) return false;
      if (id) seen.add(id);
      return true;
    });
    json(res, 200, { messages: historyFromMessages(merged) });
  } catch (e) {
    if (/not found/i.test(String(e).slice(0, 200))) {
      json(res, 404, { error: "no session" });
      return;
    }
    console.log("opencode-proxy history: opencode request failed");
    json(res, 503, { error: "opencode unavailable" });
  }
}

/** Exact-turnId result read for GET /api/turn (pure over turnResults;
 *  the handler adds HTTP framing + input checks). Null = unknown: the
 *  turn may still be running — never presented as failure. */
export function readTurnResult(
  chatId: string,
  turnId: string,
): { text: string; model: string | null; messageID?: string; error?: string; code?: string } | null {
  pruneTurnResults();
  const tr = turnResults.get(turnKey(chatId, turnId));
  if (!tr) return null;
  return {
    text: tr.text,
    model: tr.model,
    ...(tr.messageID ? { messageID: tr.messageID } : {}),
    ...(tr.error ? { error: tr.error } : {}),
    ...(tr.code ? { code: tr.code } : {}),
  };
}

/** GET /api/turn?chatId=&turnId= — exact-turnId lookup in the retained
 *  result registry (10min TTL). {status:"settled",text,model?,messageID?,
 *  error?} when the turn completed: deliver it through the normal `done`
 *  path (messageID dedupe + merge rule apply). {status:"running"} when
 *  accepted but unsettled — do NOT resend. {status:"unknown"} otherwise:
 *  never-seen, pruned, or wiped — resend only with a may-run-twice warning,
 *  never present it as proof the turn did nothing. Deliberately never
 *  history-searches: with overlapping merged turns only the exact key is
 *  safe to attach. */
export function handleTurn(req: IncomingMessage, res: ServerResponse, json: Json) {
  pruneTurnResults();
  let chatId = "";
  let turnId: string | undefined;
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    chatId = q.get("chatId")?.trim() ?? "";
    turnId = cleanTurnId(q.get("turnId"));
  } catch {
    /* malformed url → 400 below */
  }
  if (!chatId || !isChatId(chatId) || !turnId) {
    json(res, 400, { error: "bad chatId or turnId" });
    return;
  }
  // M6: exact-turn lookup is backend-scoped (never history-guessed).
  // Reads validate against the bound backend but never bind.
  let backendRaw: string | null = null;
  try {
    backendRaw = new URL(req.url || "", "http://x").searchParams.get("backend");
  } catch { /* malformed url → fall back to binding/default */ }
  const backend = resolveRouteBackend(backendRaw, res, json, chatId, "getStatus");
  if (!backend) return;
  const st = turnStatus(chatId, turnId);
  if (st === "unknown") {
    json(res, 200, { status: "unknown", backend, turnId });
    return;
  }
  if (st === "running") {
    json(res, 200, { status: "running", backend, turnId });
    return;
  }
  const tr = readTurnResult(chatId, turnId);
  if (!tr) {
    json(res, 200, { status: "unknown", backend, turnId });
    return;
  }
  json(res, 200, {
    status: "settled",
    backend,
    turnId,
    text: tr.text,
    model: tr.model,
    ...(tr.messageID ? { messageID: tr.messageID } : {}),
    ...(tr.error ? { error: tr.error } : {}),
    ...(tr.code ? { code: tr.code } : {}),
  });
}

/** GET /api/sync?chatId=&sessionID=&backend= — reconcile this chat's unsettled turns against the
 *  backend's own truth, for every backend. Reads and settles only; never starts, resends or
 *  interrupts anything.
 *   opencode: once opencode reports the session idle, a pending turn whose finished reply (or provider
 *     error) is in the message list settles with it (same pick rules as the turn's own waiter), and an
 *     unclaimed finished reply with no turn is delivered as before.
 *   others: a turn pending for over 30s that its adapter no longer knows (or already finished without
 *     leaving a result) settles as an explicit error instead of spinning, and blocking retry, forever.
 *  Answers {backend, settled[], lost[], running[]}; the app then reads each turn's exact result. */
export async function handleSync(req: IncomingMessage, res: ServerResponse, json: Json) {
  let chatId = "";
  let sessionID = "";
  let backendRaw: string | null = null;
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    chatId = q.get("chatId")?.trim() ?? "";
    sessionID = q.get("sessionID")?.trim().slice(0, 200) ?? "";
    backendRaw = q.get("backend");
  } catch {
    /* malformed url → 400 below */
  }
  if (!chatId || !isChatId(chatId)) {
    json(res, 400, { error: "bad chatId" });
    return;
  }
  const backend = resolveRouteBackend(backendRaw, res, json, chatId, "getStatus");
  if (!backend) return;
  const settled: string[] = [];
  const lost: string[] = [];
  const waiting: string[] = [];
  const pending = [...(pendingByChat.get(chatId) ?? [])];
  try {
    if (backend === "opencode") {
      const sid = cleanSessionID(sessionID) || lastSessionByChat.get(chatId) || "";
      if (sid) {
        const host = String(req.headers.host || "127.0.0.1:9009");
        if (pending.length > 0 && (await sessionWorking(sid)) === false) {
          const list = await messageListOnce(sid, 20, "assistant");
          for (const p of pending) {
            const key = turnKey(chatId, p.turnId);
            if (turnResults.has(key)) continue;
            const found = pickReplyFromList(list, p.at, settledTurnsOf(sid), p.turnId);
            if (found && found.text && found.terminal) {
              recordTurnResult(key, { chatId, turnId: p.turnId, text: mapGalleryUrlsForHost(found.text, host), model: null, at: Date.now(), ...(found.messageID ? { messageID: found.messageID } : {}), ...(found.tokens ? { tokens: found.tokens } : {}) }, sid);
              settled.push(p.turnId);
              console.log(`opencode-proxy sync: settled chat=${chatId} turn=${p.turnId} msg=${found.messageID ?? "?"}`);
              continue;
            }
            const failed = pickErrorFromList(list, p.at, settledTurnsOf(sid), p.turnId);
            if (failed && failed.text) {
              recordTurnResult(key, { chatId, turnId: p.turnId, text: "", model: null, at: Date.now(), error: failed.text.slice(0, 500), code: TURN_CODES.providerError, ...(failed.messageID ? { messageID: failed.messageID } : {}) }, sid);
              settled.push(p.turnId);
            }
          }
        } else if (pending.length === 0) {
          const bgTurn = await settleUnownedReply(chatId, sid, host);
          if (bgTurn) settled.push(bgTurn);
        }
      }
    } else if (pending.length > 0) {
      const { getAdapter } = await import("./backends/registry.js");
      const adapter = getAdapter(backend);
      const states = new Map<string, BackendTurnState>();
      for (const p of pending) {
        try {
          const st = await adapter.getStatus({ chatId, turnId: p.turnId, remoteSessionId: sessionID || null });
          states.set(p.turnId, st.state);
          if (st.state === "waiting") waiting.push(p.turnId);
        } catch { /* unreadable: never treated as lost */ }
      }
      for (const turnId of lostTurns(pending, states, Date.now())) {
        if (turnResults.has(turnKey(chatId, turnId))) continue;
        recordExternalResult(chatId, { turnId, text: "", model: null, error: "this run is no longer active on the server (it restarted or the process ended); send it again" });
        lost.push(turnId);
        console.log(`sync: lost chat=${chatId} turn=${turnId} backend=${backend}`);
      }
    }
  } catch (e) {
    console.log(`sync: failed chat=${chatId} ${errText(e).slice(0, 200)}`);
  }
  json(res, 200, { backend, settled, lost, running: pendingTurnIds(chatId), waiting });
}

/** GET /api/progress?chatId=&sessionID= — CURRENT turn's live steps.
 *  chatId correlation-tags the reply; sessionID points at the opencode
 *  session (blank = nothing to poll → idle). Polls message.list (limit 30,
 *  desc) + session.active under a 30s deadline. 404 when opencode lost it. */
export async function handleProgress(req: IncomingMessage, res: ServerResponse, json: Json) {
  let chatId = "";
  let sessionID = "";
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    chatId = q.get("chatId")?.trim() ?? "";
    sessionID = cleanSessionID(q.get("sessionID"));
  } catch {
    /* malformed url → 400 below */
  }
  if (!chatId || !isChatId(chatId)) {
    json(res, 400, { error: "bad chatId" });
    return;
  }
  // M6: progress is backend-scoped (never completion authority — the
  // tri-state working/false/null reading is unchanged).
  let progressBackendRaw: string | null = null;
  try {
    progressBackendRaw = new URL(req.url || "", "http://x").searchParams.get("backend");
  } catch { /* malformed url → fall back to binding/default */ }
  const routeBackend = resolveRouteBackend(progressBackendRaw, res, json, chatId, "getStatus");
  if (!routeBackend) return;
  if (routeBackend !== "opencode") {
    const rawSid = new URL(req.url || "", "http://x").searchParams.get("sessionID")?.trim() ?? "";
    if (rawSid && deletedRemoteSessions.has(rawSid)) {
      json(res, 404, { error: "no session" });
      return;
    }
    // M9: Terminus-level truth — accepted-but-unsettled turns are working.
    // Never completion authority, never steps from another session.
    json(res, 200, {
      status: pendingTurnIds(chatId).length > 0 ? "working" : "idle",
      steps: [],
      backend: routeBackend,
    });
    return;
  }
  if (!sessionID) {
    json(res, 200, { status: "idle", steps: [], backend: routeBackend });
    return;
  }
  try {
    const client = makeClient();
    const [listed, active] = await withDeadline("progress", PROGRESS_DEADLINE_MS, (signal) => {
      const opts = { signal };
      return Promise.all([
        client.message.list({ sessionID, limit: 30, order: "desc" } as Parameters<
          typeof client.message.list
        >[0], opts),
        client.session.active(opts).catch(() => null),
      ]);
    });
    const data = ((listed as { data?: ProgressMsg[] }).data ?? []) as ProgressMsg[];
    const steps = buildProgressSteps(data);
    let working = false;
    if (active && typeof active === "object") working = Object.keys(active).includes(sessionID);
    const status: ProgressStatus = working ? "working" : steps.length > 0 ? "done" : "idle";
    json(res, 200, { status, steps, backend: routeBackend });
  } catch (e) {
    if (/not found/i.test(String(e).slice(0, 200))) {
      json(res, 404, { error: "no session" });
      return;
    }
    console.log("opencode-proxy progress: opencode request failed");
    json(res, 503, { error: "opencode unavailable" });
  }
}

const STREAM_BEAT_MS = 20_000;
const STREAM_MAX_MS = 30 * 60 * 1000;

/** Latest turn per chat, so live event fragments can carry the turnId the
 *  app needs to match them to the standing "…" placeholder. Set on every
 *  accepted POST /api/chat; deltas for overlapping turns key to the newest. */
const lastTurnByChat = new Map<string, string>();
/** Latest opencode session per chat, set on every accepted POST /api/chat.
 *  Memory-only correlation (like lastTurnByChat): lets a stream opened
 *  before the thread's first send narrow its event filter once the session
 *  exists. Ownership stays with the app; a server restart simply falls
 *  back to the session id on each stream/progress query. */
const lastSessionByChat = new Map<string, string>();

/** Unhandled opencode event types seen so far (bounded; log-once). */
const SEEN_EVENT_TYPES = new Set<string>();

/** A reply the agent produced on its own, with no phone turn to own it: a
 *  background job landed in the session and woke the agent. Called on
 *  session.idle from an open stream. Settles it as a synthetic turn so it
 *  rides the normal done path (phone row, notification, goal judging).
 *  Only a terminal reply completed after the chat's last settle that no turn
 *  has claimed, and only while no turn of the chat is pending. Best effort. */
async function settleUnownedReply(chatId: string, sessionID: string, host: string): Promise<string | null> {
  try {
    if (!sessionID || (pendingByChat.get(chatId)?.length ?? 0) > 0) return null;
    let floor = 0;
    for (const r of turnResults.values()) if (r.chatId === chatId && r.at > floor) floor = r.at;
    if (!floor) return null;
    const list = await messageListOnce(sessionID, 10, "assistant");
    if ((pendingByChat.get(chatId)?.length ?? 0) > 0) return null;
    const claimed = settledTurnsOf(sessionID);
    let best: ListedMsg | undefined;
    for (const m of list) {
      const id = msgIdOf(m);
      if (!id || claimed.has(id)) continue;
      if ((m as { finish?: unknown }).finish !== "stop" || !isReplyCompletedAfter(m, floor)) continue;
      if (!listedText(m)) continue;
      if (!best || (m?.time?.created ?? 0) > (best.time?.created ?? 0)) best = m;
    }
    const messageID = best ? msgIdOf(best) : undefined;
    if (!best || !messageID) return null;
    const turnId = `bg-${Date.now().toString(36)}`;
    const tokens = listedTokens(best);
    noteRow(messageID, best as unknown as Record<string, unknown>);
    recordTurnResult(
      turnKey(chatId, turnId),
      {
        chatId,
        turnId,
        text: mapGalleryUrlsForHost(listedText(best), host),
        model: null,
        at: Date.now(),
        messageID,
        ...(tokens ? { tokens } : {}),
      },
      sessionID,
    );
    return turnId;
  } catch { /* delivery of an unowned reply is best effort */ }
  return null;
}

/** Goals loop continuation sender (opencode path): re-prompts the chat's
 *  existing session with the loop text. Settles through recordTurnResult,
 *  so judging re-fires and the loop continues or stops on its own. Lean
 *  wait loop (prompt → wait rounds → reply pick, 10min cap); error settles
 *  still judge (a stuck reply is signal too). Gallery URL mapping is
 *  skipped here (no request scope); the next user turn re-maps. */
function sendGoalContinuation(chatId: string, text: string): boolean {
  if ((backendOfChat(chatId) ?? "opencode") !== "opencode") {
    console.log(`goal loop chat=${chatId} not an opencode chat, holding`);
    return false;
  }
  const sessionID = lastSessionByChat.get(chatId);
  if (!sessionID) {
    console.log(`goal loop chat=${chatId} no session, holding`);
    return false;
  }
  const turnId = `goal-${Date.now().toString(36)}`;
  trackPending(chatId, turnId);
  setImmediate(() =>
    void (async () => {
      const key = turnKey(chatId, turnId);
      try {
        const client = makeClient();
        await client.session.prompt({ sessionID, text } as never);
        const inboxTime = Date.now();
        const runStart = Date.now();
        let lastSig = "";
        let idleRounds = 0;
        for (;;) {
          let waitReturned = false;
          try {
            await client.session.wait({ sessionID } as never);
            waitReturned = true;
          } catch {
            /* transport break: fall through to the list check */
          }
          // Stopped while running: the stop already paused the goal. Drop
          // the turn quietly (a stop never becomes an error row).
          if (cancelledTurnKeys.delete(key)) {
            untrackPending(chatId, turnId);
            return;
          }
          const listed = (await client.message.list({
            sessionID,
            limit: 20,
            order: "desc",
          } as never)) as { data?: ListedMsg[] };
          const data = listed.data ?? [];
          const found = pickReplyFromList(data, inboxTime, settledTurnsOf(sessionID), turnId);
          let goalSettle = false;
          if (found && found.text) {
            goalSettle = replySettleOk({
              waitReturned,
              working: await sessionWorking(sessionID),
              stableRounds: idleRounds,
              elapsedMs: Date.now() - runStart,
              terminal: found.terminal,
            });
          }
          if (found && found.text && goalSettle) {
            recordTurnResult(
              key,
              {
                chatId,
                turnId,
                text: found.text,
                model: null,
                at: Date.now(),
                ...(found.messageID ? { messageID: found.messageID } : {}),
                ...(found.tokens ? { tokens: found.tokens } : {}),
              },
              sessionID,
            );
            return;
          }
          // No cap on this wait. The continuation is a real turn in the chat's
          // own session and may legitimately run long (renders, long tool
          // runs); cutting it off only invented a "timed out" row for work that
          // was still going. Only the judge side is deadline-bounded. Polling
          // backs off instead, so an idle session is never hammered.
          // It still ends when the session is finished and there is nothing
          // to wait for: a provider error, or a frozen list with no reply
          // (same exits as the chat's own wait loop). Without these a dead
          // continuation stays pending and blocks every later turn of the chat.
          if (waitReturned) {
            const failed = pickErrorFromList(data, inboxTime, settledTurnsOf(sessionID), turnId);
            if (failed && failed.text) {
              recordTurnResult(
                key,
                {
                  chatId,
                  turnId,
                  text: "",
                  model: null,
                  at: Date.now(),
                  error: failed.text.slice(0, 500),
                  code: TURN_CODES.providerError,
                  ...(failed.messageID ? { messageID: failed.messageID } : {}),
                },
                sessionID,
              );
              return;
            }
          }
          const sig = assistantListSig(data);
          if (sig === lastSig) {
            idleRounds += 1;
            if (waitReturned && idleRounds >= 5 && Date.now() - runStart > 60_000) {
              recordTurnResult(key, {
                chatId,
                turnId,
                text: "",
                model: null,
                at: Date.now(),
                error: "assistant finished with no reply",
                code: TURN_CODES.emptyReplyStable,
              });
              return;
            }
            await new Promise((r) => setTimeout(r, Math.min(5_000 * idleRounds, 60_000)));
          } else {
            idleRounds = 0;
          }
          lastSig = sig;
        }
      } catch (e) {
        try {
          recordTurnResult(key, {
            chatId,
            turnId,
            text: "",
            model: null,
            at: Date.now(),
            error: String(e).slice(0, 200),
          });
        } catch { /* never breaks the loop bookkeeping */ }
      }
    })(),
  );
  return true;
}
setContinuationSender(sendGoalContinuation);

/** Server-wide opencode watch for background jobs (started by server.ts once listening): the per-stream feeds
 *  only run while a phone is connected,
 *  so a job that ends with the app closed was never recorded. This one always runs, maps the session back to
 *  its chat, and logs both ends (background-log.ts dedupes against the stream feeds). */
export function watchOpencodeBackground(): void {
  const loop = async (): Promise<void> => {
    for (;;) {
      try {
        const feed = makeClient().event.subscribe({});
        for await (const raw of feed) {
          const ev = raw as { type?: string; data?: Record<string, unknown> };
          const data = ev.data ?? {};
          const t = ev.type ?? "";
          if (t !== "session.tool.called" && t !== "session.inbox.enqueued") continue;
          const sid = typeof data.sessionID === "string" ? data.sessionID : "";
          if (!sid) continue;
          let chatId = "";
          for (const [c, s] of lastSessionByChat) if (s === sid) { chatId = c; break; }
          if (!chatId) continue;
          const row = backgroundSpawnOf(t, data) ?? backgroundLandingOf(t, data);
          if (row) publishBackground(chatId, row);
        }
      } catch { /* opencode down or restarting */ }
      await new Promise((r) => setTimeout(r, 15_000).unref());
    }
  };
  void loop();
}
setJudgeTarget((chatId) => ({ backend: backendOfChat(chatId) ?? "opencode", ...turnConfigByChat.get(chatId) }));
setChatBusyCheck((c) => (pendingByChat.get(c)?.length ?? 0) > 0);

/** Step high-water marks per chat+session, shared across stream
 *  (re)connections. The old per-connection counter reset on every
 *  reconnect, replaying the full step history as fake-new activity —
 *  each replayed `step` re-stamped the app's live placeholder, so a
 *  stuck turn could never age out (stop button + 10min sweep both key
 *  off that timestamp). Keyed by session too, so a restart's fresh
 *  session starts unmarked. Bounded; oldest evicted past the cap. */
const stepSeenByStream = new Map<string, string[]>();
const STEP_MARK_CAP = 2000;
const STEP_SEEN_CAP = 200;
const stepSig = (s: ProgressStep) => `${s.at}|${s.kind}|${s.label}`;

export function takeFreshSteps(
  chatId: string,
  sessionID: string,
  steps: ProgressStep[],
): { fresh: ProgressStep[]; newTurn: boolean } {
  // Identity cursor, not a length: steps arrive as a sliding window (last
  // PROGRESS_STEP_CAP), so a length never grows past the cap and new steps
  // would stop. Seen-signatures also de-duplicate poll and event copies.
  const key = chatId + "\n" + sessionID;
  const prev = stepSeenByStream.get(key);
  const seen = new Set(prev ?? []);
  const fresh = steps.filter((s) => !seen.has(stepSig(s)));
  const newTurn = prev !== undefined && steps.length > 0 && fresh.length === steps.length;
  stepSeenByStream.set(key, [...(prev ?? []), ...fresh.map(stepSig)].slice(-STEP_SEEN_CAP));
  if (stepSeenByStream.size > STEP_MARK_CAP) {
    const oldest = stepSeenByStream.keys().next();
    if (!oldest.done) stepSeenByStream.delete(oldest.value);
  }
  return { fresh, newTurn };
}

/** Pure: file targets of a mutating tool call (unit-tested). Opencode uses
 *  filePath, Claude file_path, codex a bare path string; shell and
 *  read-only tools contribute nothing. Conservative by design: a tool the
 *  matcher doesn't recognize stays silent rather than mislabeling a read
 *  as a change. The step.ended files array (opencode-authoritative) travels
 *  separately — this covers the tool-call rows on every backend. */
export function stepFiles(name: string, input: unknown): string[] {
  if (!/edit|write|patch|apply|create|save|delete|remove|move|rename|mkdir|touch/i.test(name || "")) return [];
  const out: string[] = [];
  const push = (v: unknown): void => {
    if (typeof v !== "string") return;
    const s = v.trim();
    if (!s || s.length > 500 || s.includes("\n") || !s.includes("/")) return;
    if (out.length < 3 && !out.includes(s)) out.push(s);
  };
  if (typeof input === "string") {
    push(input);
    return out;
  }
  if (input && typeof input === "object") {
    const rec = input as Record<string, unknown>;
    for (const k of ["filePath", "file_path", "file", "path"]) push(rec[k]);
  }
  return out;
}

/** One step row for tool/step lifecycle events (thinking-box rows).
 *  Pure apart from the timestamp. Carries the useful bit of each event:
 *  tool name (via input.started) or input summary (called: command, file,
 *  …), output/error excerpt (success/failed), model/finish (steps). The
 *  live tool.called event carries no name in the opencode schema — only
 *  input — so the label falls back to the most telling input field rather
 *  than the bare event-type words. Exported for tests. */
export function toolStepOf(type: string, data: Record<string, unknown>): ProgressStep {
  const str = (v: unknown): string => (typeof v === "string" && v ? v : "");
  const pick = (...keys: string[]): string => {
    for (const k of keys) {
      const s = str(data[k]);
      if (s) return s;
    }
    return "";
  };
  const input = (data.input && typeof data.input === "object")
    ? (data.input as Record<string, unknown>)
    : null;
  const inputStr = (k: string): string => (input ? str(input[k]) : "");
  const flat = (v: unknown, max: number): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  if (type === "session.tool.called" || type === "session.tool.input.started") {
    const name = pick("label", "title", "tool", "name") || inputStr("command") || inputStr("file") || inputStr("path") || inputStr("url") || "tool called";
    const detail = input ? (summarizeInput(input) || "") : "";
    // The live opencode schema carries no tool name here, only input — so
    // the mutating gate sees just the name keys, never the fallbacks (a
    // command string must not qualify as a file target).
    const toolName = str(data.name) || str(data.tool);
    const step: ProgressStep = { kind: "tool", label: name.slice(0, 160), detail: detail.slice(0, 200), at: Date.now() };
    const tf = stepFiles(toolName, input ?? undefined);
    if (tf.length) step.files = tf;
    return step;
  }
  if (type === "session.tool.success") {
    const name = pick("label", "title", "tool", "name") || "tool done";
    let detail = "";
    const content = (data as { content?: unknown }).content;
    if (Array.isArray(content)) {
      for (const c of content) {
        if (c && typeof c === "object") {
          const rec = c as Record<string, unknown>;
          if (typeof rec.text === "string" && rec.text.trim()) {
            detail = flat(rec.text, 200);
            break;
          }
          if (typeof rec.uri === "string" && rec.uri) {
            detail = flat(rec.uri, 200);
            break;
          }
        }
      }
    }
    return { kind: "tool", label: name.slice(0, 160), detail, at: Date.now() };
  }
  if (type === "session.tool.failed") {
    const name = pick("label", "title", "tool", "name") || "tool failed";
    const err = (data as { error?: unknown }).error;
    const detail = (err && typeof err === "object" && typeof (err as Record<string, unknown>).message === "string")
      ? flat((err as Record<string, unknown>).message, 200)
      : flat(data.error, 200);
    return { kind: "tool", label: name.slice(0, 160), detail, at: Date.now() };
  }
  if (type === "session.step.started") {
    const model = str(data.model);
    const shortModel = model.includes("/") ? (model.split("/").pop() ?? model) : model;
    const agent = str(data.agent);
    const label = shortModel ? `step started · ${shortModel}` : "step started";
    return { kind: "tool", label: label.slice(0, 160), detail: agent.slice(0, 120), at: Date.now() };
  }
  if (type === "session.step.ended") {
    const finish = str(data.finish);
    // A failed step carries the provider error alongside the finish state;
    // without it the red native failure renders here as a bare "step ended".
    const er = (data as { error?: unknown }).error;
    const errBit = (er && typeof er === "object" && typeof (er as Record<string, unknown>).message === "string")
      ? flat((er as Record<string, unknown>).message, 160)
      : flat(data.error, 160);
    const detail = [finish, errBit].filter(Boolean).join(" · ").slice(0, 200);
    const step: ProgressStep = { kind: "tool", label: "step ended", detail, at: Date.now() };
    // Opencode-authoritative per-step file list (the thinking box never
    // shows it — detail stays finish + error; the structured list feeds the
    // settled reply's changed line).
    if (Array.isArray(data.files)) {
      const ef = (data.files as unknown[])
        .filter((v): v is string => typeof v === "string" && v.trim().length > 0)
        .map((v) => v.trim())
        .slice(0, 8);
      if (ef.length) step.files = ef;
    }
    return step;
  }
  const label = pick("label", "title", "command", "tool", "name", "file", "path") || type.replace(/^session\./, "").replace(/\./g, " ");
  return { kind: "tool", label: label.slice(0, 160), detail: "", at: Date.now() };
}

/** Live row for provider/model failure events (outside text parts).
 *  Extracts the failure message defensively across payload shapes;
 *  empty detail means the caller keeps silent. Exported for tests. */
export function errorStepOf(type: string, data: Record<string, unknown>): { kind: string; label: string; detail: string; at: number } {
  const flat = (v: unknown, max: number): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  const str = (v: unknown): string => (typeof v === "string" && v ? v : "");
  let detail = "";
  const err = (data as { error?: unknown }).error;
  if (typeof err === "string" && err.trim()) {
    detail = flat(err, 200);
  } else if (err && typeof err === "object") {
    const rec = err as Record<string, unknown>;
    for (const k of ["message", "text", "detail", "reason"]) {
      const s = flat(rec[k], 200);
      if (s) {
        detail = s;
        break;
      }
    }
  }
  if (!detail) {
    for (const k of ["message", "text", "detail", "reason", "delta"]) {
      const s = flat((data as Record<string, unknown>)[k], 200);
      if (s) {
        detail = s;
        break;
      }
    }
  }
  const name = str(data.label) || str(data.title) || str(data.tool) || str(data.name) || str(data.model);
  const label = name ? `assistant error · ${name}`.slice(0, 160) : "assistant error";
  return { kind: "tool", label, detail, at: Date.now() };
}

/** Durable row payload for context-compaction lifecycle events (started /
 *  ended / failed, manual or automatic). Exported for tests. */
export function compactionEventOf(type: string, data: Record<string, unknown>): { status: string; reason: string; detail: string } {
  const flat = (v: unknown, max = 200): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  const status = type.endsWith(".failed") ? "failed" : type.endsWith(".ended") ? "ended" : "started";
  let reason = flat((data as Record<string, unknown>).reason, 20);
  if (reason !== "auto" && reason !== "manual") reason = "";
  let detail = "";
  const err = (data as { error?: unknown }).error;
  if (typeof err === "string" && err.trim()) {
    detail = flat(err);
  } else if (err && typeof err === "object") {
    const rec = err as Record<string, unknown>;
    for (const k of ["message", "text", "detail", "reason"]) {
      const s = flat(rec[k]);
      if (s) {
        detail = s;
        break;
      }
    }
  }
  return { status, reason, detail };
}

/** Durable "backgrounded" row for a tool call the model asked to run detached:
 *  session.tool.called with input.background === true. Foreground calls carry
 *  no flag and stay ephemeral live-box steps. Returns null for anything that
 *  is not a background spawn. Exported for tests. */
export function backgroundSpawnOf(
  type: string,
  data: Record<string, unknown>,
): { status: string; id: string; label: string } | null {
  if (type !== "session.tool.called") return null;
  const input = (data.input && typeof data.input === "object")
    ? (data.input as Record<string, unknown>)
    : null;
  if (!input || input.background !== true) return null;
  const flat = (v: unknown, max: number): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  return {
    status: "started",
    id: flat(data.id, 64),
    label: flat(input.command, 200) || flat(input.description, 200) || "background job",
  };
}

/** S1 shape capture (logging only): truncated id + sorted input key names
 *  for a background spawn. Keys only, never values or commands. Null when
 *  this event is not a background spawn. Exported for tests. */
export function backgroundSpawnShape(
  type: string,
  data: Record<string, unknown>,
): { id: string; inputKeys: string[] } | null {
  if (backgroundSpawnOf(type, data) === null) return null;
  const input = data.input as Record<string, unknown>;
  const id = typeof data.id === "string" ? data.id.replace(/\s+/g, " ").trim().slice(0, 64) : "";
  return { id, inputKeys: Object.keys(input).sort() };
}

/** Durable landing row for a background job's completion. The completion
 *  reaches the agent as a synthetic inbox item (source "shell") whose payload
 *  text is `<shell id="…" state="…" command="…">\n<output>\n</shell>`. Returns
 *  null for every non-shell inbox item. Exported for tests. */
export function backgroundLandingOf(
  type: string,
  data: Record<string, unknown>,
): { status: string; id: string; label: string; detail: string; full?: string } | null {
  if (type !== "session.inbox.enqueued") return null;
  const item = (data.item && typeof data.item === "object")
    ? (data.item as Record<string, unknown>)
    : null;
  if (!item || item.type !== "synthetic") return null;
  const payload = (item.payload && typeof item.payload === "object")
    ? (item.payload as Record<string, unknown>)
    : null;
  const md = (payload?.metadata && typeof payload.metadata === "object")
    ? (payload.metadata as Record<string, unknown>)
    : null;
  if (!md || md.source !== "shell") return null;
  const flat = (v: unknown, max: number): string =>
    (typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, max) : "");
  const text = typeof payload?.text === "string" ? (payload.text as string) : "";
  const state = (text.match(/state="([^"]*)"/) || [])[1] || "completed";
  const label = flat(payload?.description, 200)
    || flat((text.match(/command="([^"]*)"/) || [])[1], 200)
    || "background job";
  let detail = "";
  // Full output for the card expander (newlines kept, capped): the excerpt
  // above stays short for the collapsed row; the expander shows this.
  // Short outputs ride the excerpt alone — no duplicate payload.
  let full = "";
  const open = text.indexOf(">");
  const close = text.indexOf("</shell>");
  if (open >= 0 && close > open) {
    detail = flat(text.slice(open + 1, close), 200);
    full = text.slice(open + 1, close).replace(/\r\n?/g, "\n").replace(/\n{3,}/g, "\n\n").trim().slice(0, 4000);
    if (full.length <= 200) full = "";
  }
  return {
    status: state === "completed" || state === "exited" ? "done" : "failed",
    id: flat(md.shellID, 64),
    label,
    detail,
    ...(full ? { full } : {}),
  };
}

/** S1 shape capture (logging only): source + sorted key names for a
 *  synthetic inbox item that is NOT a shell landing (the S4 candidate:
 *  subagent/task completions, if any). Keys only, never content. Null
 *  unless synthetic-but-unmatched. Exported for tests. */
export function unmatchedSyntheticShape(
  type: string,
  data: Record<string, unknown>,
): { source: string; itemKeys: string[]; payloadKeys: string[]; metadataKeys: string[] } | null {
  if (type !== "session.inbox.enqueued") return null;
  const item = (data.item && typeof data.item === "object")
    ? (data.item as Record<string, unknown>)
    : null;
  if (!item || item.type !== "synthetic") return null;
  if (backgroundLandingOf(type, data) !== null) return null;
  const payload = (item.payload && typeof item.payload === "object")
    ? (item.payload as Record<string, unknown>)
    : null;
  const md = (payload?.metadata && typeof payload.metadata === "object")
    ? (payload.metadata as Record<string, unknown>)
    : null;
  const keys = (v: unknown): string[] =>
    (v && typeof v === "object" ? Object.keys(v as Record<string, unknown>).sort() : []);
  const source = (md && typeof md.source === "string" && md.source) ? (md.source as string) : "?";
  return { source, itemKeys: keys(item), payloadKeys: keys(payload), metadataKeys: keys(md) };
}

/** Pure: id of the newest non-empty assistant message (chronological),
 *  or "". Lets the polling fallback attribute growth to the owning turn
 *  instead of the newest turn. Exported for tests. */
export function newestAssistantId(messages: ProgressMsg[]): string {
  const chrono = [...messages].sort((a, b) => (a?.time?.created ?? 0) - (b?.time?.created ?? 0));
  let id = "";
  for (const m of chrono) {
    if (m?.type !== "assistant" || !Array.isArray(m.content)) continue;
    const t = (m.content as ProgressPart[])
      .filter((p) => (p?.type === "text" || p?.type === "reasoning") && typeof p.text === "string")
      .map((p) => p.text as string)
      .join("");
    if (!t) continue;
    const raw = m as unknown as { id?: unknown; messageID?: unknown; messageId?: unknown };
    const mid =
      (typeof raw?.id === "string" && raw.id) ||
      (typeof raw?.messageID === "string" && raw.messageID) ||
      (typeof raw?.messageId === "string" && raw.messageId) ||
      "";
    if (mid) id = mid;
  }
  return id;
}

/** Pure: newest assistant text part in a message.list page (desc or asc). */
export function latestAssistantText(messages: ProgressMsg[]): string {
  const chrono = [...messages].sort((a, b) => (a?.time?.created ?? 0) - (b?.time?.created ?? 0));
  let cur = "";
  for (const m of chrono) {
    if (m?.type !== "assistant" || !Array.isArray(m.content)) continue;
    const t = (m.content as ProgressPart[])
      .filter((p) => (p?.type === "text" || p?.type === "reasoning") && typeof p.text === "string")
      .map((p) => p.text as string)
      .join("");
    if (t) cur = t;
  }
  return cur;
}

/** GET /api/stream?chatId= — SSE turn progress + delivery pings.
 *  Lifecycle contract is documented in server/CHAT_MIRROR.md (canonical);
 *  the /debug/chat mirror and the Android client both implement against it:
 *  `done` settles one turn (never closes the stream), `idle` marks the
 *  session idle, `step ended` is one tool step only.
 *  Live opencode event feed (text/reasoning fragments, tool activity) with
 *  `session.idle` triggering an immediate flush; a 5s safety tick replays
 *  settled `done`s and fills gaps if the feed drops. Each settled
 *  POST /api/chat background turn emits its own `done`
 *  {text,model,turnId,messageID?}. Delivery appends ping connected
 *  `schedule:<job>` / `alerts` chats via `inbox`. Heartbeat `: ping`
 *  every 20s; hard cap 30min per connection. */
export async function handleStream(req: IncomingMessage, res: ServerResponse) {
  let chatId = "";
  let sid = "";
  // Done-replay floor (ms epoch): the client sends the newest settle time
  // it has already applied (?since=). Absent = recent window only — a
  // reconnect must never replay the full retained history (see
  // DONE_REPLAY_WINDOW_MS). Present (even 0) = honor exactly.
  let sinceMs = 0;
  let sinceGiven = false;
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    chatId = q.get("chatId")?.trim() ?? "";
    // App-owned session id, passed per connection. Blank = thread never sent:
    // event matching runs loose until the first turn settles one.
    sid = cleanSessionID(q.get("sessionID"));
    const raw = q.get("since");
    if (raw != null && raw !== "") {
      const n = Number(raw);
      if (Number.isFinite(n) && n >= 0) {
        sinceMs = Math.floor(n);
        sinceGiven = true;
      }
    }
  } catch {
    /* malformed url → 400 below */
  }
  if (!chatId || !isChatId(chatId)) {
    res.writeHead(400, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "bad chatId" }));
    return;
  }
  // M6: the stream is a view of the chat's turns, scoped to its backend.
  // Mismatch with a bound backend is 409; reads never bind. The SSE
  // vocabulary itself is unchanged (spec 5.5).
  // M9: the opencode poll/event feed only serves opencode chats. Fresh
  // chats (unbound at open) keep it until their first send binds them —
  // its id filters never match foreign traffic, so it stays silent.
  // Done replay (flushDones) + the runtime bus below serve every backend.
  let streamBackend: BackendId | null = null;
  const feedOpencode = (): boolean => (backendOfChat(chatId) ?? streamBackend ?? "opencode") === "opencode";
  {
    let streamBackendRaw: string | null = null;
    try {
      streamBackendRaw = new URL(req.url || "", "http://x").searchParams.get("backend");
    } catch { /* malformed url → fall back to binding/default */ }
    const streamJson: Json = (r, status, body) => {
      r.writeHead(status, { "Content-Type": "application/json" });
      r.end(JSON.stringify(body));
    };
    streamBackend = resolveRouteBackend(streamBackendRaw, res, streamJson, chatId, "stream");
    if (!streamBackend) return;
  }
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-store",
    Connection: "keep-alive",
    "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null",
  });
  const reg = streamRegister(chatId, res);
  const send = (event: string, data: string): void => {
    try {
      res.write(`event: ${event}\ndata: ${data}\n\n`);
    } catch {
      /* closed; the req close handler cleans up */
    }
  };
  // M9: runtime bus for non-opencode live events (delta/step). Unregisters
  // on close; the connection owns nothing — turns keep running.
  let lastText = "";
  let baselined = false;
  const doneSent = new Set<string>();
  let closed = false;
  let eventOk = false;
  let reasonBuf = "";
  let lastReasonSent = 0;
  const started = Date.now();
  const eventCtl = new AbortController();
  try {
    res.write(`: connected ${started}\n\n`);
  } catch {
    /* ignore */
  }

  /** Emit settled-turn `done`s (proven delivery path, kept). Runs on every
   *  flush: event-idle triggers plus the 5s safety tick. */
  const flushDones = (): void => {
    pruneTurnResults();
    // Per-turn dones: every settled turn for this chat NEWER THAN the
    // replay floor emits once per connection (client dedupes across
    // reconnects by turnId/messageID, send-once equivalent). Sorted by
    // settle time so overlapping send-through replies land in completion
    // order, never clobbered. The floor defaults to the recent window —
    // full-history replay per reconnect was the Oct-09 UI storm.
    const floor = sinceGiven ? sinceMs : Date.now() - DONE_REPLAY_WINDOW_MS;
    const pending = replayableDones(turnResults.values(), chatId, floor);
    for (const tr of pending) {
      // Once per connection, as documented above; a re-settled turn (newer `at`) still goes out.
      const mark = `${tr.turnId}@${tr.at}`;
      if (doneSent.has(mark)) continue;
      doneSent.add(mark);
      console.log(`opencode-proxy stream: done chat=${tr.chatId} turn=${tr.turnId} chars=${tr.text.length}${tr.error ? ` error=${tr.error}` : ""}${tr.code ? ` code=${tr.code}` : ""}`);
      send(
        "done",
        JSON.stringify({ text: tr.text, model: tr.model, turnId: tr.turnId, at: tr.at, ...(tr.messageID ? { messageID: tr.messageID } : {}), ...(tr.error ? { error: tr.error } : {}), ...(tr.code ? { code: tr.code } : {}) }),
      );
      if (tr.text && tr.text !== lastText) lastText = tr.text;
    }
  };

  const flushFromList = async (): Promise<void> => {
    if (closed) return;
    flushDones();
    // M9: completions replay for every backend; the opencode list poll
    // below only runs for opencode chats (a foreign thread id must never
    // reach message.list).
    if (!feedOpencode()) return;
    try {
      // Latest session the app gave this chat (set on every accepted send),
      // falling back to the id on the stream query. Blank = never sent.
      const sessionID = lastSessionByChat.get(chatId) ?? sid;
      if (!sessionID) return;
      const client = makeClient();
      // message.list only: the old session.active call fed nothing visible
      // (its result was discarded), so it no longer rides every tick.
      const listed = await withDeadline("progress", PROGRESS_DEADLINE_MS, (signal) => {
        const opts = { signal };
        return client.message.list({ sessionID, limit: 10, order: "desc" } as Parameters<
          typeof client.message.list
        >[0], opts);
      });
      if (closed) return;
      const data = ((listed as { data?: ProgressMsg[] }).data ?? []) as ProgressMsg[];
      const steps = buildProgressSteps(data);
      // Cross-connection mark: reconnects send only genuinely new steps,
      // never a replay of history (which the app would read as fresh
      // activity and re-stamp a stuck turn with). A shorter list means a
      // new turn started — reset the text baseline like before.
      const { fresh, newTurn } = takeFreshSteps(chatId, sessionID, steps);
      for (const s of fresh) send("step", JSON.stringify(s));
      if (newTurn) {
        lastText = "";
      }
      // Poll-driven deltas only while the event feed is silent: once live
      // fragments flow, the list snapshot would double-send them. The first
      // flush per connection only establishes the baseline — emitting here
      // would replay the previous reply into the new turn as fake growth.
      if (!eventOk) {
        const cur = latestAssistantText(data);
        // Same ownership rule AND certainty gate as live deltas: poll
        // growth belongs to the turn that owns the newest assistant
        // message, never by default to the latest turn. Under overlap
        // (2+ pending) with a guessed owner, suppress — the done still
        // settles every turn with full text.
        const pollMid = newestAssistantId(data);
        const pollOwner = pollMid ? msgOwner.get(`${sessionID}:${pollMid}`) : undefined;
        const pending = pendingByChat.get(chatId) ?? [];
        const pollTag = (pollOwner ? pollOwner.turnId : null)
          ?? ownerTurnForDelta(pending, undefined)
          ?? lastTurnByChat.get(chatId) ?? "";
        const pollCertain = shouldEmitDelta(
          pollOwner ? { certain: pollOwner.certain } : undefined,
          pending.length,
        );
        if (!baselined) {
          baselined = true;
          if (cur) lastText = cur;
        } else if (pollCertain && cur && cur !== lastText) {
          if (cur.startsWith(lastText)) {
            const growth = cur.slice(lastText.length);
            if (growth) send("delta", JSON.stringify({ delta: growth.slice(0, 4000), turnId: pollTag }));
          } else if (!lastText || cur.length > lastText.length) {
            send("delta", JSON.stringify({ delta: cur.slice(-4000), turnId: pollTag }));
          }
          lastText = cur;
        } else if (cur) {
          // Still track text baseline even when suppressed, so the next
          // certain tick doesn't replay the entire accumulated gap.
          lastText = cur;
        }
      } else {
        const cur = latestAssistantText(data);
        if (cur) lastText = cur;
      }
    } catch {
      // Transient opencode failure: skip this tick, keep the stream open.
    }
  };

  /** Live opencode event feed: text fragments, reasoning, and tool activity
   *  stream in as they happen; `session.idle` triggers an immediate flush
   *  so the settled `done` lands without waiting for the safety tick. */
  const runEventsOnce = async (): Promise<void> => {
    // Same source as flushFromList, re-read per event: a stream opened
    // before the thread's first send starts loose and narrows once the
    // accepted send registers the fresh session.
    const currentSid = (): string => lastSessionByChat.get(chatId) ?? sid;
    try {
      const client = makeClient();
      const feed = client.event.subscribe({ signal: eventCtl.signal });
      for await (const raw of feed) {
        if (closed) return;
        if (!feedOpencode()) return;
        const ev = raw as { type?: string; data?: Record<string, unknown> };
        const data = ev.data ?? {};
        const esid = typeof data.sessionID === "string" ? data.sessionID : "";
        const want = currentSid();
        if (want && esid && esid !== want) continue;
        if (!want && esid) continue;
        switch (ev.type) {
          case "session.text.delta": {
            const frag = typeof data.delta === "string" ? data.delta : "";
            if (!frag) break;
            // Attribute by assistant message id, never by newest turn: an
            // older turn still streaming when a newer send lands keeps its
            // own deltas. First claimant wins (merged prompts share one
            // message). Unknown id with no pending turn falls back to the
            // legacy newest tag rather than dropping live text.
            const mid = typeof data.assistantMessageID === "string" ? data.assistantMessageID : "";
            const owner = mid ? msgOwner.get(`${esid}:${mid}`) : undefined;
            const pending = pendingByChat.get(chatId) ?? [];
            const knownOwner = owner ? { chatId: owner.chatId, turnId: owner.turnId } : undefined;
            const tag = ownerTurnForDelta(pending, knownOwner) ?? lastTurnByChat.get(chatId) ?? "";
            // Record first sight for merge continuity, flagging guesses.
            if (mid && esid && !owner && tag) {
              msgOwner.set(`${esid}:${mid}`, { chatId, turnId: tag, at: Date.now(), certain: pending.length <= 1 });
            }
            // Emit only when ownership is certain: a known-certain owner, a
            // lone pending turn, or post-restart continuity (nothing
            // pending). Guessed messages stay silent live; their `done`
            // still settles every turn with full text.
            const certain = shouldEmitDelta(
              owner ? { certain: owner.certain } : undefined,
              pending.length,
            );
            eventOk = true;
            lastText += frag;
            if (certain && tag) {
              send("delta", JSON.stringify({ delta: frag.slice(0, 4000), turnId: tag }));
            }
            break;
          }
          case "session.reasoning.delta": {
            const frag = typeof data.delta === "string" ? data.delta : "";
            if (frag) {
              eventOk = true;
              reasonBuf += frag;
            }
            break;
          }
          case "session.reasoning.ended": {
            const text = typeof data.text === "string" ? data.text : "";
            if (text) {
              eventOk = true;
              if (!reasonBuf.includes(text)) {
                reasonBuf += (reasonBuf.length > 0 && !reasonBuf.endsWith("\n") ? "\n" : "") + text;
              }
            }
            break;
          }
          case "session.tool.input.started":
          case "session.tool.called":
          case "session.tool.success":
          case "session.tool.failed": {
            eventOk = true;
            // A call the model asked to run detached is a background job: give
            // it a durable row at both ends instead of a live-box step that a
            // settled turn would wipe. Foreground calls stay steps.
            const spawn = backgroundSpawnOf(ev.type ?? "", data);
            if (spawn) {
              // S1 shape capture, logging only: id + input key names, never
              // values or commands. No behaviour change.
              try {
                const sh = backgroundSpawnShape(ev.type ?? "", data);
                if (sh) console.log(`background spawn id=${sh.id} input=[${sh.inputKeys.join(",")}]`);
              } catch { /* logging never breaks the feed */ }
              publishBackground(chatId, spawn);
            } else send("step", JSON.stringify(toolStepOf(ev.type, data)));
            break;
          }
          case "session.step.started":
          case "session.step.ended": {
            eventOk = true;
            send("step", JSON.stringify(toolStepOf(ev.type, data)));
            break;
          }
          case "session.error":
          case "message.error":
          case "session.step.error": {
            // Provider/model failures arrive outside text parts: forward
            // them as step rows so the live view shows the failure while
            // it happens (the settle path separately records them on the
            // turn's error channel with retry).
            eventOk = true;
            send("step", JSON.stringify(errorStepOf(ev.type ?? "session.error", data)));
            break;
          }
          case "session.inbox.enqueued": {
            // A background job's completion reaches the agent as a synthetic
            // inbox item. Surface it as a durable landing row so the result is
            // visible in the thread, not only in the agent's own turn.
            const landing = backgroundLandingOf(ev.type ?? "", data);
            if (landing) {
              eventOk = true;
              publishBackground(chatId, landing);
            } else {
              // S1 shape capture, logging only: a synthetic inbox item that
              // is not a shell landing (S4 candidate). Source + key names,
              // never content. No behaviour change.
              try {
                const sh = unmatchedSyntheticShape(ev.type ?? "", data);
                if (sh) console.log(`background inbox unmatched source=${sh.source} item=[${sh.itemKeys.join(",")}] payload=[${sh.payloadKeys.join(",")}] metadata=[${sh.metadataKeys.join(",")}]`);
              } catch { /* logging never breaks the feed */ }
            }
            break;
          }
          case "session.compaction.started":
          case "session.compaction.ended":
          case "session.compaction.failed": {
            // Context compaction rewrites history outside any turn (manual
            // /compact or automatic on overflow): without this, neither the
            // start nor the outcome ever reaches the app. A dedicated event
            // (not a step row) so it lands as a durable row even with no
            // turn live — step rows only paint inside the live box.
            eventOk = true;
            send("compact", JSON.stringify(compactionEventOf(ev.type ?? "", data)));
            break;
          }
          case "session.idle": {
            await flushFromList();
            // A reply the agent wrote on its own (a background job landed)
            // has no phone turn: settle it so it reaches the phone as a done.
            await settleUnownedReply(chatId, currentSid(), String(req.headers.host || "127.0.0.1:9009"));
            flushDones();
            send("idle", JSON.stringify({ sessionID: currentSid() }));
            break;
          }
          default: {
            // Forward-compatible: unknown failure-shaped events still
            // surface instead of vanishing (bare native failures with no
            // client row). Non-error types stay silent as before.
            const t = typeof ev.type === "string" ? ev.type : "";
            // First sight of each unhandled type is logged (name and data
            // keys only, never content) so new opencode events are visible.
            if (t && !SEEN_EVENT_TYPES.has(t) && SEEN_EVENT_TYPES.size < 200) {
              SEEN_EVENT_TYPES.add(t);
              console.log(`opencode event unhandled type=${t} keys=${Object.keys(data).slice(0, 12).join(",")}`);
            }
            if (/error|fail/i.test(t)) {
              const s = errorStepOf(t, data);
              if (s.detail || s.label !== "assistant error") {
                eventOk = true;
                send("step", JSON.stringify(s));
              }
            }
            break;
          }
        }
      }
    } catch {
      // Feed down (opencode restart, network): the safety tick below keeps
      // steps + dones flowing on the proven list path while runEvents
      // re-subscribes.
    }
  };
  /** opencode's feed is live-only: a source failure ends it with no replay
   *  and no automatic reconnect. Subscribe again (backoff) for as long as
   *  this stream is open; the safety tick covers the gap. */
  const runEvents = async (): Promise<void> => {
    let attempt = 0;
    while (!closed) {
      const t0 = Date.now();
      await runEventsOnce();
      if (closed || !feedOpencode()) return;
      attempt = Date.now() - t0 > 30_000 ? 0 : attempt + 1;
      await new Promise((r) => setTimeout(r, Math.min(1000 * 2 ** Math.min(attempt, 4), 15_000)));
    }
  };

  const SAFETY_POLL_MS = 5000;
  const safety = async (): Promise<void> => {
    if (closed) return;
    if (Date.now() - started > STREAM_MAX_MS) {
      send("end", JSON.stringify({ reason: "timeout" }));
      try {
        res.end();
      } catch {
        /* ignore */
      }
      return;
    }
    // Flush accumulated reasoning as follow-on rows (new growth only, so
    // rows partition the thinking without overlapping tails).
    if (reasonBuf.length > lastReasonSent) {
      const growth = reasonBuf.slice(lastReasonSent, lastReasonSent + 500);
      lastReasonSent += growth.length;
      send("step", JSON.stringify({ kind: "reasoning", label: growth, detail: "", at: Date.now() }));
    }
    await flushFromList();
  };
  const timer = setInterval(() => void safety(), SAFETY_POLL_MS);
  const beat = setInterval(() => {
    try {
      res.write(`: ping ${Date.now()}\n\n`);
    } catch {
      /* ignore */
    }
  }, STREAM_BEAT_MS);
  req.on("close", () => {
    closed = true;
    try {
      unlistenBus();
    } catch {
      /* ignore */
    }
    try {
      unlistenFlush();
    } catch {
      /* ignore */
    }
    try {
      eventCtl.abort();
    } catch {
      /* ignore */
    }
    clearInterval(timer);
    clearInterval(beat);
    streamUnregister(reg);
  });
  // M9: runtime bus (non-opencode live delta/step) + immediate-flush hook
  // (terminal runtime events flush every open connection at once).
  // Both unregister on close; the connection owns nothing.
  const { addChatListener, addChatFlusher } = await import("./turn-runtime.js");
  const unlistenBus = addChatListener(chatId, send);
  const unlistenFlush = addChatFlusher(chatId, flushDones);
  if (closed) {
    // Disconnected during the import above: unregister immediately instead
    // of leaking a dead sender + flusher pair forever.
    try { unlistenBus(); } catch { /* ignore */ }
    try { unlistenFlush(); } catch { /* ignore */ }
    return;
  }
  if (feedOpencode()) void runEvents();
  void safety();
}

/** Resolves once every queued results write has landed: a restart or crash must not cut the last
 *  settled outcomes off mid-write. */
export async function flushPersistedResults(): Promise<void> {
  schedulePersistResults();
  try { await persistChain; } catch { /* best effort */ }
}

/** The server is going down: every non-opencode turn still pending dies with its process, so record an
 *  explicit failure now instead of leaving the app to find out through "outcome unknown". Opencode turns
 *  keep running in opencode and are recovered by the sync. Returns how many were settled. */
export function settleDeadOnShutdown(reason: string): number {
  const targets = shutdownTargets(
    pendingByChat.entries(),
    (c) => backendOfChat(c) ?? null,
    (c, t) => turnResults.has(turnKey(c, t)),
  );
  for (const t of targets) {
    try { recordExternalResult(t.chatId, { turnId: t.turnId, text: "", model: null, error: reason }); } catch { /* exiting anyway */ }
  }
  return targets.length;
}
