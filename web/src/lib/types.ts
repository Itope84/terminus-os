// Shapes the server answers with (server/src/pwa/*, opencode-proxy.ts).

export interface Chat {
  id: string;
  title: string;
  backend: string;
  sessionID?: string;
  model?: string;
  effort?: string;
  cwd?: string;
  createdAt: number;
  updatedAt: number;
  pinned?: boolean;
  archivedAt?: number;
  preview?: string;
  unread?: number;
  needsYou?: boolean;
  kind?: 'chat' | 'inbox';
}

export interface Approval {
  id: string;
  source: string;
  title: string;
  detail: string;
  expiresAt: number;
  chat: string;
}

export interface Backend {
  id: string;
  label: string;
  shortLabel: string;
  available: boolean;
  authenticated: boolean;
  capabilities: Record<string, boolean>;
}

export interface Model {
  id: string;
  label?: string;
  name?: string;
}

export interface Command {
  name: string;
  description?: string;
  available?: boolean;
}

export interface Step {
  kind: string;
  label: string;
  detail?: string;
  at: number;
  files?: string[];
}

export type MsgRole = 'user' | 'assistant' | 'inbox' | 'error' | 'note';

/** One row in a thread. Live replies are rows too (status 'live'). */
export interface Msg {
  id: string;
  role: MsgRole;
  text: string;
  at: number;
  turnId?: string;
  /** user: sending → sent; waiting = no connection (outbox); queued = behind a running reply;
   *  failed = the Mac refused it. assistant: live while streaming. */
  status?: 'sending' | 'sent' | 'waiting' | 'queued' | 'failed' | 'live';
  steps?: Step[];
  model?: string;
  title?: string;
  /** Attachments sent with a user message (names only; the bytes went to the Mac). */
  files?: string[];
  error?: string;
}

export interface ContextInfo {
  backend: string | null;
  model?: string | null;
  used: number;
  window: number | null;
  costUsd?: number;
  turns?: number;
  compactAt: number;
}

export interface Goal {
  objective?: string;
  text?: string;
  status?: string;
  loop?: boolean;
  [k: string]: unknown;
}

/** Backends this app offers. The server knows more (Codex, Agy); they still work if a chat names them. */
export const SUPPORTED_BACKENDS = ['claude', 'opencode'] as const;
