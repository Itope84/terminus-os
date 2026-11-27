/** The web app's chat index: one small JSON file (.run/pwa/chats.json) so every device (the phone's
 *  home-screen app, a laptop browser) sees the same list. Messages themselves are not kept here: a
 *  chat's thread is the backend's own history (/api/history) plus its inbox entries (/api/inbox).
 *
 *  Writes are debounced and atomic (temp file + rename). The file is 0600 inside the 0700 run dir. */
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { runDir } from "../paths.js";

export interface PwaChat {
  id: string;
  title: string;
  /** opencode | claude (codex and agy still work, untested from the web app). */
  backend: string;
  sessionID?: string;
  model?: string;
  effort?: string;
  cwd?: string;
  createdAt: number;
  updatedAt: number;
  pinned?: boolean;
  archivedAt?: number;
  /** First words of the newest message. */
  preview?: string;
  /** Replies and deliveries since the person last opened the chat. */
  unread?: number;
  /** Something is waiting on the person (an approval, a browser hand-off, a failed reply). */
  needsYou?: boolean;
  /** schedule:<job>, alerts: inbox-only threads made by the server. */
  kind?: "chat" | "inbox";
}

const EDITABLE = ["title", "backend", "sessionID", "model", "effort", "cwd", "pinned", "archivedAt", "preview", "needsYou"] as const;
const ID_RE = /^[A-Za-z0-9_:-]{1,80}$/;
const PREVIEW = 140;

export function previewOf(text: string): string {
  return text.replace(/[`*_#>]+/g, "").replace(/\s+/g, " ").trim().slice(0, PREVIEW);
}

export class ChatStore {
  private chats = new Map<string, PwaChat>();
  private timer: NodeJS.Timeout | null = null;
  constructor(private readonly file: string, private readonly now: () => number = Date.now) {
    try {
      const raw = JSON.parse(readFileSync(file, "utf8")) as { chats?: PwaChat[] };
      for (const c of raw.chats ?? []) if (c && typeof c.id === "string" && ID_RE.test(c.id)) this.chats.set(c.id, c);
    } catch { /* first run */ }
  }

  list(): PwaChat[] {
    return [...this.chats.values()].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  get(id: string): PwaChat | undefined {
    return this.chats.get(id);
  }

  /** Create or update from the app. Unknown keys are ignored; id is required. */
  upsert(input: Record<string, unknown>): PwaChat | null {
    const id = typeof input.id === "string" ? input.id.trim() : "";
    if (!ID_RE.test(id)) return null;
    const t = this.now();
    const prev = this.chats.get(id);
    const next: PwaChat = prev ? { ...prev } : { id, title: "New chat", backend: "claude", createdAt: t, updatedAt: t, kind: "chat" };
    for (const k of EDITABLE) {
      if (!(k in input)) continue;
      const v = input[k];
      if (v === null) { delete (next as unknown as Record<string, unknown>)[k]; continue; }
      if (k === "pinned" || k === "needsYou") { if (typeof v === "boolean") next[k] = v; continue; }
      if (k === "archivedAt") { if (typeof v === "number" && Number.isFinite(v)) next.archivedAt = v; continue; }
      if (typeof v !== "string") continue;
      const s = k === "preview" ? previewOf(v) : v.trim().slice(0, k === "title" ? 120 : 300);
      (next as unknown as Record<string, unknown>)[k] = s;
    }
    if (input.touch === true) next.updatedAt = t;
    this.chats.set(id, next);
    this.save();
    return next;
  }

  /** The person opened the chat: clear its unread count and needs-you flag. */
  markRead(id: string): void {
    const c = this.chats.get(id);
    if (!c) return;
    if (!c.unread && !c.needsYou) return;
    c.unread = 0;
    c.needsYou = false;
    this.save();
  }

  /** A reply or delivery landed. Creates inbox threads on first use. */
  landed(id: string, opts: { preview?: string; title?: string; needsYou?: boolean; inbox?: boolean; unread?: boolean }): PwaChat | null {
    if (!ID_RE.test(id)) return null;
    const t = this.now();
    let c = this.chats.get(id);
    if (!c) {
      if (!opts.inbox) return null; // a chat the web app never made (e.g. the Android app's): not ours to list
      c = { id, title: opts.title || id, backend: "inbox", createdAt: t, updatedAt: t, kind: "inbox" };
      this.chats.set(id, c);
    }
    c.updatedAt = t;
    if (opts.preview !== undefined) c.preview = previewOf(opts.preview);
    if (opts.unread !== false) c.unread = (c.unread ?? 0) + 1;
    if (opts.needsYou) c.needsYou = true;
    if (c.archivedAt) delete c.archivedAt; // news brings a chat back
    this.save();
    return c;
  }

  delete(id: string): boolean {
    const had = this.chats.delete(id);
    if (had) this.save();
    return had;
  }

  private save(): void {
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, 200);
    this.timer.unref?.();
  }

  flush(): void {
    try {
      mkdirSync(join(this.file, ".."), { recursive: true, mode: 0o700 });
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, JSON.stringify({ chats: this.list() }), { mode: 0o600 });
      renameSync(tmp, this.file);
    } catch (e) {
      console.log(`pwa store: save failed ${String((e as Error).message).slice(0, 120)}`);
    }
  }
}

export const pwaDir = (): string => join(runDir(), "pwa");
let shared: ChatStore | null = null;
export function chatStore(): ChatStore {
  if (!shared) shared = new ChatStore(join(pwaDir(), "chats.json"));
  return shared;
}
