/** Terminus backend contract (multi-backend spec M2).
 *
 *  The provider is a replaceable execution engine. Terminus owns chat, turn,
 *  ordering, persistence, SSE, retry, and UI semantics; adapters only execute
 *  turns and report normalized events. No ACP in v1: this shape stays
 *  ACP-compatible (sessions, turns, updates, cancel, permissions,
 *  capabilities) so ACP can arrive later as just another adapter.
 *
 *  Rules enforced here by construction:
 *  - BackendId is a closed set: never an arbitrary executable/command.
 *  - turnId is Terminus-canonical (client-generated, preserved on retry).
 *    Provider ids are metadata, never lifecycle identity.
 *  - Model ids are opaque backend-local strings (no `/` assumption).
 */

export type BackendId = "opencode" | "codex" | "claude" | "agy";

export const BACKEND_IDS: readonly BackendId[] = ["opencode", "codex", "claude", "agy"];

export function isBackendId(v: unknown): v is BackendId {
  return v === "opencode" || v === "codex" || v === "claude" || v === "agy";
}

export interface BackendCapabilities {
  streaming: boolean;
  models: boolean;
  commands: boolean;
  sessionsList: boolean;
  history: boolean;
  resume: boolean;
  interruptTurn: boolean;
  killProcess: boolean;
  compact: boolean;
  title: boolean;
  deleteSession: boolean;
  fork: boolean;
  approvals: boolean;
  attachments: boolean;
  overlappingTurns: boolean;
  /** Honours a per-turn reasoning effort (low/medium/high). The app shows the picker only then. */
  effort: boolean;
  /** Reports token usage the server can read: the app shows the context ring only then. */
  usage: boolean;
  /** Supports Send-now on a waiting message: fold it into the running
   *  turn (codex turn/steer) or release it immediately where sends queue
   *  client-side. The app shows the links only then. */
  steer: boolean;
}

export interface BackendDescriptor {
  id: BackendId;
  label: string;
  shortLabel: string;
  available: boolean;
  authenticated: boolean;
  capabilities: BackendCapabilities;
}

export interface ModelOption {
  /** Backend-local id (opaque: never split on `/`). */
  id: string;
  label: string;
  provider?: string;
  description?: string;
  deprecated?: boolean;
}

export interface CommandOption {
  /** Must begin with `/`. */
  name: string;
  description: string;
  available: boolean;
  /** A Terminus skill, not a backend command: the phone sends it as a prompt asking for the skill. */
  kind?: "skill";
}

export interface UsageInfo {
  inputTokens?: number;
  outputTokens?: number;
  costUsd?: number;
}

export interface BackendAttachment {
  name: string;
  mime: string;
  base64: string;
}

export interface BackendContext {
  chatId: string;
  backend: BackendId;
  /** Provider-native remote session/thread id (null before first turn). */
  remoteSessionId: string | null;
  /** Server-validated working directory. Never client-controlled raw. */
  cwd: string;
  model: string | null;
  signal?: AbortSignal;
}

export interface StartTurnArgs {
  chatId: string;
  /** Canonical Terminus identity: generated once, preserved through retries. */
  turnId: string;
  remoteSessionId?: string | null;
  text: string;
  files: BackendAttachment[];
  model?: string | null;
  title?: string | null;
  systemPrompt?: string | null;
  /** Reasoning effort low/medium/high (backends with capabilities.effort; others ignore). */
  effort?: string | null;
  /** Working directory for this turn (codex thread/turn cwd). Server-
   *  validated against the Desktop workspace; blank = workspace root. */
  cwd?: string | null;
  /** Auto-compact ceiling in tokens (claude, codex). 0/absent = the backend's own default. */
  compactAt?: number | null;
  /** "Send now": fold this message into the running turn when the backend can (capabilities.steer).
   *  Absent/false = the normal strict turn-by-turn queue. */
  steer?: boolean;
  /** Fail instead of entering the local backend queue if the requested steer cannot happen. */
  steerRequired?: boolean;
}

export interface StartTurnResult {
  accepted: true;
  remoteSessionId: string;
  remoteTurnId?: string | null;
  /** True when the message was folded into the running turn instead of starting or queueing its own. */
  steered?: boolean;
}

export interface InterruptTurnArgs {
  chatId: string;
  turnId: string;
  remoteSessionId?: string | null;
}

export interface InterruptResult {
  interrupted: boolean;
  reason?: string;
}

export interface StatusArgs {
  chatId: string;
  turnId: string;
  remoteSessionId?: string | null;
}

export type BackendTurnState =
  | "accepted"
  | "working"
  | "waiting"
  | "completed"
  | "interrupted"
  | "failed"
  | "unknown";

export interface BackendStatus {
  state: BackendTurnState;
  detail?: string;
}

export interface TitleArgs extends SessionArgs {
  /* read */
}
export interface SetTitleArgs extends SessionArgs {
  title: string;
}
export interface SessionArgs {
  chatId: string;
  remoteSessionId: string;
}
export interface ForkArgs extends SessionArgs {
  /* newTurnId allocation stays Terminus-side */
}
export interface ForkResult {
  remoteSessionId: string;
}
export interface ResumeSessionArgs extends SessionArgs {
  /* validate-only / reattach probes */
}
export interface ResumeSessionResult {
  ok: boolean;
  remoteSessionId: string;
}
export interface RemoteSession {
  remoteSessionId: string;
  title?: string;
  updatedAt?: number;
}
export interface HistoryArgs extends SessionArgs {
  limit?: number;
}
export interface HistoryResult {
  messages: Array<{ role: string; text: string; at?: number }>;
}

export type EmitBackendEvent = (e: BackendEvent) => void;

export interface StreamArgs {
  chatId: string;
  turnId: string;
  remoteSessionId: string;
}

/** Normalized provider events. Adapters emit these; the Terminus runtime
 *  maps them onto the stable app-facing SSE (delta/step/done/error/idle).
 *  `remoteMessageId`, when present, is metadata for provider-specific merge
 *  rules (opencode fused replies). It is never lifecycle identity. */
export type BackendEvent =
  | { type: "turn.started"; turnId: string; remoteSessionId: string; remoteTurnId?: string }
  | { type: "message.delta"; turnId: string; remoteMessageId?: string; text: string }
  | { type: "tool.started"; turnId: string; toolId?: string; name: string; input?: unknown }
  | { type: "tool.output"; turnId: string; toolId?: string; output?: unknown }
  | { type: "tool.completed"; turnId: string; toolId?: string; output?: unknown; error?: string }
  | { type: "reasoning.delta"; turnId: string; text: string }
  /** A job the model sent to the background (started) and its end (done/failed): durable thread rows.
   *  Chat-scoped: the end can land after the turn that started it has settled. */
  | { type: "background"; turnId: string; status: "started" | "done" | "failed"; id: string; label: string; toolId?: string; outputFile?: string; summary?: string }
  /** Transient "what is happening now" line (thinking token count, tool being composed); "" clears it. Never stored. */
  | { type: "status"; turnId: string; text: string }
  /** Context compaction lifecycle; the phone shows one durable started/ended/failed row. */
  | { type: "compaction"; turnId: string; status: "started" | "ended" | "failed"; reason?: "auto" | "manual"; detail?: string }
  | {
      type: "approval.requested";
      turnId: string;
      approvalId: string;
      title: string;
      description: string;
      tool?: string;
      expiresAt?: number;
    }
  | {
      type: "turn.completed";
      turnId: string;
      /** Same logical assistant message as an earlier event (opencode
       *  fusion). Absent/false for codex/claude: one result = one turn. */
      sameMessageAsPrior?: boolean;
      remoteMessageId?: string;
      text: string;
      usage?: UsageInfo;
      providerStatus?: string;
    }
  | { type: "turn.interrupted"; turnId: string; reason?: string }
  | { type: "turn.failed"; turnId: string; code: string; message: string; retryable: boolean }
  | {
      type: "session.status";
      turnId?: string;
      status: "working" | "waiting" | "idle" | "dead";
      detail?: string;
    };

/** Deliberately small core, explicit provider operations. stream() produces
 *  normalized events into the runtime; it never touches HTTP/SSE. */
export interface BackendAdapter {
  readonly id: BackendId;
  descriptor(): BackendDescriptor;
  listModels(ctx: BackendContext): Promise<ModelOption[]>;
  listCommands(ctx: BackendContext): Promise<CommandOption[]>;
  startTurn(args: StartTurnArgs): Promise<StartTurnResult>;
  interruptTurn(args: InterruptTurnArgs): Promise<InterruptResult>;
  killTurn?(args: InterruptTurnArgs): Promise<InterruptResult>;
  /** Cancel a queued (provider-not-started) turn. Absent = backend runs
   *  turns eagerly and stop means interrupt-only. */
  abortQueued?(turnId: string): Promise<boolean>;
  getStatus(args: StatusArgs): Promise<BackendStatus>;
  resumeSession?(args: ResumeSessionArgs): Promise<ResumeSessionResult>;
  stream?(args: StreamArgs, emit: EmitBackendEvent): Promise<void>;
  getTitle?(args: TitleArgs): Promise<string>;
  setTitle?(args: SetTitleArgs): Promise<void>;
  compact?(args: SessionArgs): Promise<void>;
  deleteSession?(args: SessionArgs): Promise<void>;
  fork?(args: ForkArgs): Promise<ForkResult>;
  /** "Send now" for a message already waiting behind the running turn: fold
   *  it into that turn. False = nothing changed (already started or ended,
   *  no turn running, or the provider refused). Absent = backend cannot. */
  steerQueued?(chatId: string, turnId: string): Promise<boolean>;
  listSessions?(args: { limit?: number }): Promise<RemoteSession[]>;
  history?(args: HistoryArgs): Promise<HistoryResult>;
}
