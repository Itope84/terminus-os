/** Which chat does a background event belong to? Pure helpers, no I/O.
 *
 *  An agent in chat X drives the phone browser (or asks for approval). The
 *  notice that follows must land IN chat X, not in a generic "phone-browser"
 *  thread, so tapping it opens the conversation that needs you.
 *
 *  The agent tags its browser calls with a `tab` id it picks itself, so the
 *  server cannot read the chat from it directly. Resolution order:
 *    1. `tab` (or an explicit `chat`) is itself a known chat id → that chat.
 *    2. `tab` was seen before while exactly one chat had a turn running → remembered.
 *    3. No tab, or an unseen one, and exactly one chat has a turn running → that chat.
 *    4. Otherwise null: the caller falls back to the shared Alerts thread.
 *  Two chats running at once is ambiguous on purpose: a wrong guess would put
 *  one chat's page in front of another chat's thread. */

export const CHAT_ID_RE = /^[A-Za-z0-9_:-]{1,80}$/;

export type OriginInput = {
  tab?: string;
  /** Chats with at least one turn in flight right now. */
  running: string[];
  /** Every chat id the server has seen (running or settled), for rule 1. */
  known?: ReadonlySet<string>;
  /** tab → chat memory (mutated by the caller through `learn`). */
  remembered: Map<string, { chat: string; at: number }>;
  now?: number;
};

const TAB_TTL_MS = 60 * 60 * 1000;

export function resolveOriginChat(i: OriginInput): { chat: string | null; learn?: [string, string] } {
  const now = i.now ?? Date.now();
  const tab = (i.tab ?? "").trim();
  if (tab && CHAT_ID_RE.test(tab) && (i.known?.has(tab) || i.running.includes(tab))) return { chat: tab };
  if (tab) {
    const hit = i.remembered.get(tab);
    if (hit && now - hit.at < TAB_TTL_MS) return { chat: hit.chat };
  }
  const running = [...new Set(i.running)];
  if (running.length === 1) {
    return tab ? { chat: running[0], learn: [tab, running[0]] } : { chat: running[0] };
  }
  return { chat: null };
}

/** Inbox entry kinds. `event` = something needs the person (not a message to read). */
/** "reply": a message the chat's own agent wrote on its own (a background job's result woke it), shown as a
 *  normal reply with a reply notification. */
export type InboxKind = "event" | "reply";
export type InboxPriority = "high";

export function cleanKind(v: unknown): InboxKind | undefined {
  return v === "event" || v === "reply" ? v : undefined;
}
export function cleanPriority(v: unknown): InboxPriority | undefined {
  return v === "high" ? "high" : undefined;
}
