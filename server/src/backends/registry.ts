/** Backend registry (multi-backend spec M2+M9, section 4).
 *
 *  The ONLY place backend ids resolve to executable behavior. Never accept
 *  a command, binary path, or arg list from the client: the server owns
 *  which binary, which args, which env allowlist, and which cwd rules.
 *
 *  M9: all adapters registered (agy added later). NOTE: opencode TURNS never route
 *  through the opencode facade — the inline opencode-proxy path is the
 *  compatibility contract. The facade serves models/commands/descriptors.
 */
import type { BackendAdapter, BackendDescriptor, BackendId } from "./types.js";
import { BACKEND_IDS, isBackendId } from "./types.js";
import { BackendError } from "./errors.js";
import { CodexAdapter } from "./codex.js";
import { ClaudeAdapter } from "./claude.js";
import { AgyAdapter } from "./agy.js";
import { OpencodeAdapter } from "./opencode.js";

const OPENCODE_DESCRIPTOR: BackendDescriptor = {
  id: "opencode",
  label: "OpenCode",
  shortLabel: "OpenCode",
  available: true,
  authenticated: true,
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
    overlappingTurns: true,
    effort: false,
    usage: true,
    steer: true,
  },
};

/** M2: opencode only. codex/claude descriptors arrive with their adapters. */
const DESCRIPTORS: Record<BackendId, BackendDescriptor | null> = {
  opencode: OPENCODE_DESCRIPTOR,
  codex: null,
  claude: null,
  agy: null,
};

let codexAdapter: CodexAdapter | null = null;
let claudeAdapter: ClaudeAdapter | null = null;
let agyAdapter: AgyAdapter | null = null;

/** Adapter singletons (codex/claude hold process + turn state; opencode is
 *  stateless facade). Throws for unknown ids — never fabricate. */
export function getAdapter(id: BackendId): BackendAdapter {
  if (id === "opencode") return new OpencodeAdapter();
  if (id === "codex") {
    if (!codexAdapter) codexAdapter = new CodexAdapter();
    return codexAdapter;
  }
  if (id === "claude") {
    if (!claudeAdapter) claudeAdapter = new ClaudeAdapter();
    return claudeAdapter;
  }
  if (id === "agy") {
    if (!agyAdapter) agyAdapter = new AgyAdapter();
    return agyAdapter;
  }
  throw new BackendError("invalid_request", "unknown backend", false, undefined, "getAdapter");
}

/** Test seam: drop adapter singletons (no processes touched here;
 *  adapters own their shutdown). */
export function resetAdaptersForTests(): void {
  codexAdapter = null;
  claudeAdapter = null;
  agyAdapter = null;
}

export function listBackendIds(): BackendId[] {
  return [...BACKEND_IDS];
}

/** Descriptors for registered backends (M9: all three; availability and
 *  auth state are best-effort, never a model turn to probe). */
export function listBackends(): BackendDescriptor[] {
  const out: BackendDescriptor[] = [];
  const open = DESCRIPTORS.opencode;
  if (open) out.push(open);
  try { out.push(getAdapter("codex").descriptor()); } catch { /* adapter failed: omit, stay up */ }
  try { out.push(getAdapter("claude").descriptor()); } catch { /* adapter failed: omit, stay up */ }
  try { out.push(getAdapter("agy").descriptor()); } catch { /* adapter failed: omit, stay up */ }
  return out;
}

export function getDescriptor(id: BackendId): BackendDescriptor {
  if (id === "opencode") {
    const d = DESCRIPTORS.opencode;
    if (!d) throw new BackendError("backend_unavailable", `backend not registered: ${id}`, false, id);
    return d;
  }
  return getAdapter(id).descriptor();
}

/** Parse + validate a client-supplied backend value. Anything outside the
 *  closed set (including paths, commands, odd casing) is invalid_request.
 *  M9: all three ids are registered (adapters constructible); a missing
 *  binary or login surfaces later as an explicit backend error, never a
 *  fallback to another backend. */
export function parseBackendId(v: unknown): BackendId {
  if (!isBackendId(v)) {
    throw new BackendError("invalid_request", "unknown backend", false, undefined, "parseBackendId");
  }
  return v;
}
