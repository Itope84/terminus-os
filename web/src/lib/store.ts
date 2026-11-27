// App-wide state: sign-in, the chat list, open approvals, backends. The chat list is the server's
// (/api/pwa/chats), kept live by /api/pwa/events (events.ts), and cached for an offline launch.
import { create } from 'zustand';
import { ApiError, call } from './api';
import type { Approval, Backend, Chat } from './types';

const CACHE_KEY = 'terminus.chats.v1';

function cachedChats(): Record<string, Chat> {
  try {
    const raw = JSON.parse(localStorage.getItem(CACHE_KEY) || '[]') as Chat[];
    return Object.fromEntries(raw.map((c) => [c.id, c]));
  } catch {
    return {};
  }
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
function saveChats(chats: Record<string, Chat>) {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify(Object.values(chats)));
    } catch {
      /* storage full: the server has it */
    }
  }, 300);
}

export interface Defaults {
  defaultBackend: string;
  defaultModels: Record<string, string>;
  systemPrompt?: string;
  compactAt?: number;
}

interface AppState {
  auth: 'unknown' | 'in' | 'out';
  /** The events stream is up (the Mac is reachable). */
  online: boolean;
  chats: Record<string, Chat>;
  approvals: Approval[];
  backends: Backend[];
  defaults: Defaults | null;
  /** The chat on screen, for presence (no push for what you're looking at). */
  openChat: string;
  toast: { text: string; at: number } | null;
  set: (p: Partial<AppState>) => void;
}

export const useApp = create<AppState>((set) => ({
  auth: 'unknown',
  online: false,
  chats: typeof localStorage === 'undefined' ? {} : cachedChats(),
  approvals: [],
  backends: [],
  defaults: null,
  openChat: '',
  toast: null,
  set: (p) => set(p),
}));

export const toast = (text: string) => useApp.getState().set({ toast: { text, at: Date.now() } });

export function putChat(chat: Chat) {
  const chats = { ...useApp.getState().chats, [chat.id]: chat };
  useApp.getState().set({ chats });
  saveChats(chats);
}

export function dropChat(id: string) {
  const chats = { ...useApp.getState().chats };
  delete chats[id];
  useApp.getState().set({ chats });
  saveChats(chats);
}

export async function loadChats() {
  const r = await call<{ chats: Chat[] }>('GET', '/api/pwa/chats');
  const chats = Object.fromEntries(r.chats.map((c) => [c.id, c]));
  useApp.getState().set({ chats });
  saveChats(chats);
}

/** Change a chat here at once and on the server. */
export async function updateChat(id: string, patch: Partial<Chat> & { touch?: boolean }) {
  const prev = useApp.getState().chats[id];
  const now = Date.now();
  const local: Chat = {
    ...(prev ?? { id, title: 'New chat', backend: 'claude', createdAt: now, updatedAt: now, kind: 'chat' }),
    ...patch,
    ...(patch.touch ? { updatedAt: now } : {}),
  } as Chat;
  putChat(local);
  try {
    const r = await call<{ chat: Chat }>('POST', '/api/pwa/chats', { id, ...patch });
    putChat(r.chat);
    return r.chat;
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 0) toast(`Couldn’t save the chat: ${(e as Error).message}`);
    return local;
  }
}

export async function deleteChat(chat: Chat) {
  dropChat(chat.id);
  try {
    // The backend's own session goes too (opencode/claude transcript), so it doesn't linger.
    if (chat.sessionID && chat.kind !== 'inbox') {
      await call('POST', '/api/session-delete', { chatId: chat.id, sessionID: chat.sessionID, backend: chat.backend }).catch(() => {});
    }
    await call('POST', '/api/pwa/chats/delete', { id: chat.id });
  } catch (e) {
    toast(`Couldn’t delete it on the Mac: ${(e as Error).message}`);
  }
}

export async function markRead(id: string) {
  const c = useApp.getState().chats[id];
  if (c && (c.unread || c.needsYou)) putChat({ ...c, unread: 0, needsYou: false });
  await call('POST', '/api/pwa/chats/read', { id }).catch(() => {});
}

export async function loadBackends() {
  try {
    const [b, d] = await Promise.all([
      call<{ backends: Backend[]; defaultBackend: string }>('GET', '/api/backends'),
      call<Defaults>('GET', '/api/defaults'),
    ]);
    useApp.getState().set({ backends: b.backends, defaults: d });
  } catch {
    /* shown as unavailable */
  }
}

/** The backend a new chat uses: the saved default when it's one this app offers, else Claude. */
export function defaultBackend(): string {
  const d = useApp.getState().defaults?.defaultBackend;
  return d === 'opencode' || d === 'claude' ? d : 'claude';
}
