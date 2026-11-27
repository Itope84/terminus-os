// One chat's thread: what's been said (the backend's history + inbox deliveries), what's streaming
// now (/api/stream), and what's waiting to go (outbox). Mirrors the Android app's rules:
//  - the stream is opened before the first send; sends are fast-accept POSTs, outcomes arrive on it
//  - `done` is canonical and settles a turn once (deduped by turnId); deltas only paint until then
//  - a send that can't leave the phone waits and goes when the Mac is reachable again
import { create } from 'zustand';
import { ApiError, call } from './api';
import { newTurnId } from './ids';
import { openSse } from './sse';
import { markRead, putChat, toast, updateChat, useApp } from './store';
import type { Chat, Msg, Step } from './types';

export interface ThreadState {
  msgs: Msg[];
  loaded: boolean;
  loading: boolean;
  loadError?: string;
  /** The chat's stream is up. */
  connected: boolean;
  /** "Thinking · 250 tokens", "Preparing Bash…": the newest status line while a reply runs. */
  status: string;
  /** Newest settle time applied, the stream's replay floor. */
  lastDoneAt: number;
}

const empty = (): ThreadState => ({ msgs: [], loaded: false, loading: false, connected: false, status: '', lastDoneAt: 0 });

interface ThreadsStore {
  threads: Record<string, ThreadState>;
}
export const useThreads = create<ThreadsStore>(() => ({ threads: {} }));

export const threadOf = (id: string): ThreadState => useThreads.getState().threads[id] ?? empty();

function patch(id: string, fn: (t: ThreadState) => Partial<ThreadState>) {
  const cur = threadOf(id);
  const next = { ...cur, ...fn(cur) };
  useThreads.setState((s) => ({ threads: { ...s.threads, [id]: next } }));
  saveCache(id, next);
}

const sortMsgs = (msgs: Msg[]) => [...msgs].sort((a, b) => a.at - b.at);

// ---- cache: a thread opens instantly and offline ----

const cacheKey = (id: string) => `terminus.thread.${id}`;
const CACHE_MSGS = 150;
const timers: Record<string, ReturnType<typeof setTimeout>> = {};
function saveCache(id: string, t: ThreadState) {
  clearTimeout(timers[id]);
  timers[id] = setTimeout(() => {
    try {
      localStorage.setItem(cacheKey(id), JSON.stringify({ msgs: t.msgs.slice(-CACHE_MSGS), lastDoneAt: t.lastDoneAt }));
    } catch {
      /* full: fine, the Mac has it */
    }
  }, 400);
}
function readCache(id: string): Pick<ThreadState, 'msgs' | 'lastDoneAt'> | null {
  try {
    const raw = localStorage.getItem(cacheKey(id));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
export function forgetThread(id: string) {
  try {
    localStorage.removeItem(cacheKey(id));
  } catch {
    /* ignore */
  }
  useThreads.setState((s) => {
    const threads = { ...s.threads };
    delete threads[id];
    return { threads };
  });
}

// ---- loading ----

interface InboxEntry {
  job: string;
  title: string;
  body: string;
  at: number;
  chat?: string;
  kind?: string;
}

/** Pure: put history, inbox deliveries and what only this phone knows into one ordered thread. */
export function mergeThread(history: Msg[], inbox: Msg[], cached: Msg[]): Msg[] {
  const out = [...history, ...inbox];
  // What history can't know: steps, turn ids and models of replies seen live here.
  for (const h of history) {
    if (h.role !== 'assistant') continue;
    const seen = cached.find((c) => c.role === 'assistant' && c.text === h.text && !c.status);
    if (seen) Object.assign(h, { steps: seen.steps, turnId: seen.turnId, model: seen.model });
  }
  for (const c of cached) {
    if (c.role === 'error' || c.role === 'note') {
      out.push(c);
      continue;
    }
    if (c.role === 'user') {
      if (c.status === 'waiting' || c.status === 'failed') {
        out.push(c);
        continue;
      }
      if (c.status === 'sending' || c.status === 'queued') {
        const inHistory = history.some((h) => h.role === 'user' && h.text.trim() === c.text.trim() && h.at >= c.at - 120_000);
        if (!inHistory) out.push(c);
      }
      continue;
    }
    if (c.role === 'assistant' && c.status === 'live') {
      const landed = history.some((h) => h.role === 'assistant' && h.at >= c.at - 5_000 && c.text && h.text.startsWith(c.text.slice(0, 40)));
      if (!landed) out.push(c);
    }
  }
  return sortMsgs(out);
}

async function fetchInbox(chat: Chat): Promise<Msg[]> {
  const job = chat.id.startsWith('schedule:') ? chat.id.slice('schedule:'.length) : '';
  const q = job ? `?job=${encodeURIComponent(job)}&since=0` : '?since=0';
  const r = await call<{ entries: InboxEntry[] }>('GET', `/api/inbox${q}`);
  return r.entries
    .filter((e) => (job ? !e.chat || e.chat === chat.id : e.chat === chat.id))
    .map((e) => ({
      id: `in-${e.job}-${e.at}`,
      role: 'inbox' as const,
      title: e.title,
      text: e.body,
      at: e.at,
    }));
}

async function fetchHistory(chat: Chat): Promise<Msg[]> {
  if (!chat.sessionID || chat.kind === 'inbox') return [];
  try {
    const r = await call<{ messages: { role: string; text: string; ts: number }[] }>(
      'GET',
      `/api/history?backend=${encodeURIComponent(chat.backend)}&sessionID=${encodeURIComponent(chat.sessionID)}`,
    );
    return r.messages
      .filter((m) => m.text && (m.role === 'user' || m.role === 'assistant'))
      .map((m, i) => ({ id: `h-${i}-${m.ts}`, role: m.role as 'user' | 'assistant', text: m.text, at: m.ts || 0 }));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return [];
    throw e;
  }
}

export async function loadThread(chat: Chat) {
  const id = chat.id;
  if (!threadOf(id).loaded) {
    const cached = readCache(id);
    if (cached) patch(id, () => ({ msgs: cached.msgs, lastDoneAt: cached.lastDoneAt }));
  }
  patch(id, () => ({ loading: true, loadError: undefined }));
  try {
    const [history, inbox] = await Promise.all([fetchHistory(chat), fetchInbox(chat)]);
    patch(id, (t) => ({ msgs: mergeThread(history, inbox, t.msgs), loaded: true, loading: false }));
  } catch (e) {
    patch(id, () => ({ loading: false, loaded: true, loadError: (e as Error).message }));
  }
}

// ---- the live stream ----

function liveIndex(msgs: Msg[], turnId: string): number {
  return msgs.findIndex((m) => m.role === 'assistant' && m.turnId === turnId);
}

/** The reply a step or status belongs to: the newest live one (steps carry no turn id). */
function newestLive(msgs: Msg[]): number {
  for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].status === 'live') return i;
  return -1;
}

function ensureLive(msgs: Msg[], turnId: string, at = Date.now()): { msgs: Msg[]; i: number } {
  let i = liveIndex(msgs, turnId);
  if (i !== -1) return { msgs, i };
  const next = [...msgs, { id: `a-${turnId}`, role: 'assistant' as const, text: '', at, turnId, status: 'live' as const, steps: [] }];
  return { msgs: next, i: next.length - 1 };
}

interface Done {
  text: string;
  model?: string | null;
  turnId: string;
  at?: number;
  messageID?: string;
  error?: string;
  code?: string;
}

export function applyDone(id: string, d: Done) {
  patch(id, (t) => {
    let msgs = [...t.msgs];
    const i = liveIndex(msgs, d.turnId);
    const already = i !== -1 && !msgs[i].status && msgs[i].text === d.text;
    if (already) return {};
    // The user's message for this turn went through.
    msgs = msgs.map((m) => (m.role === 'user' && m.turnId === d.turnId && m.status !== 'sent' ? { ...m, status: 'sent' } : m));
    const at = d.at ?? Date.now();
    if (d.error) {
      const err: Msg = { id: `e-${d.turnId}`, role: 'error', text: d.error, at, turnId: d.turnId, error: d.code };
      if (i !== -1) msgs[i] = { ...err, steps: msgs[i].steps };
      else if (!msgs.some((m) => m.id === err.id)) msgs.push(err);
    } else if (i !== -1) {
      msgs[i] = { ...msgs[i], text: d.text, status: undefined, model: d.model ?? msgs[i].model };
    } else {
      msgs.push({ id: `a-${d.turnId}`, role: 'assistant', text: d.text, at, turnId: d.turnId, model: d.model ?? undefined });
    }
    const stillLive = msgs.some((m) => m.status === 'live');
    return { msgs: sortMsgs(msgs), lastDoneAt: Math.max(t.lastDoneAt, at), status: stillLive ? t.status : '' };
  });
  if (useApp.getState().openChat === id && document.visibilityState === 'visible') void markRead(id);
}

function onStreamEvent(chat: Chat, event: string, raw: string) {
  const id = chat.id;
  const d = JSON.parse(raw);
  switch (event) {
    case 'delta':
      patch(id, (t) => {
        const { msgs, i } = ensureLive([...t.msgs], d.turnId);
        if (msgs[i].status !== 'live') return {}; // settled already: deltas only paint live replies
        msgs[i] = { ...msgs[i], text: msgs[i].text + d.delta };
        return { msgs };
      });
      break;
    case 'status':
      patch(id, (t) => {
        if (d.turnId) {
          const { msgs, i } = ensureLive([...t.msgs], d.turnId);
          if (msgs[i].status !== 'live') return {};
          return { msgs, status: d.text };
        }
        return { status: d.text };
      });
      break;
    case 'step':
      patch(id, (t) => {
        const msgs = [...t.msgs];
        let i = newestLive(msgs);
        if (i === -1) {
          const r = ensureLive(msgs, `run-${d.at}`);
          i = r.i;
          msgs.splice(0, msgs.length, ...r.msgs);
        }
        const step: Step = { kind: d.kind, label: d.label, detail: d.detail, at: d.at, files: d.files };
        msgs[i] = { ...msgs[i], steps: [...(msgs[i].steps ?? []), step] };
        return { msgs };
      });
      break;
    case 'done':
      applyDone(id, d as Done);
      break;
    case 'idle':
      patch(id, (t) => ({
        status: '',
        // A live row with nothing in it and no turn the server knows of is a leftover.
        msgs: t.msgs.filter((m) => !(m.status === 'live' && !m.text && !(m.steps?.length) && m.turnId?.startsWith('run-'))),
      }));
      break;
    case 'inbox':
      void fetchInbox(chat)
        .then((inbox) =>
          patch(id, (t) => {
            const have = new Set(t.msgs.map((m) => m.id));
            const fresh = inbox.filter((m) => !have.has(m.id));
            return fresh.length ? { msgs: sortMsgs([...t.msgs, ...fresh]) } : {};
          }),
        )
        .catch(() => {});
      break;
    case 'compact':
      patch(id, (t) => ({ msgs: [...t.msgs, { id: `n-${Date.now()}`, role: 'note', text: 'Context compacted.', at: Date.now() }] }));
      break;
    default:
      break;
  }
}

/** Open the chat's stream; returns close(). Reconciles lost replies each time it (re)connects. */
export function connectThread(chatId: string): { close: () => void; kick: () => void } {
  const chatNow = () => useApp.getState().chats[chatId];
  const link = openSse(
    () => {
      const c = chatNow();
      const q = new URLSearchParams({ chatId });
      if (c?.sessionID) q.set('sessionID', c.sessionID);
      if (c?.backend && c.kind !== 'inbox') q.set('backend', c.backend);
      const since = threadOf(chatId).lastDoneAt;
      if (since) q.set('since', String(since));
      return `/api/stream?${q}`;
    },
    {
      onOpen: () => {
        patch(chatId, () => ({ connected: true }));
        void reconcile(chatId);
        void flushOutbox();
      },
      onDown: () => patch(chatId, () => ({ connected: false })),
      onEvent: (event, data) => {
        const c = chatNow();
        if (c) onStreamEvent(c, event, data);
      },
    },
    { stallMs: 75_000 },
  );
  return link;
}

/** Ask the server about turns that look unfinished here (pull-up "check again" does the same). */
export async function reconcile(chatId: string) {
  const chat = useApp.getState().chats[chatId];
  if (!chat || chat.kind === 'inbox') return;
  const t = threadOf(chatId);
  const open = t.msgs.filter((m) => m.status === 'live' || m.status === 'sending' || m.status === 'queued');
  try {
    const q = new URLSearchParams({ chatId, backend: chat.backend });
    if (chat.sessionID) q.set('sessionID', chat.sessionID);
    const s = await call<{ settled: string[]; lost: string[]; running: string[] }>('GET', `/api/sync?${q}`);
    // Replies still running on the Mac get a live row (so Stop shows), even after a reload.
    for (const turnId of s.running) patch(chatId, (th) => ({ msgs: ensureLive([...th.msgs], turnId).msgs }));
    const check = new Set([...s.settled, ...s.lost, ...open.map((m) => m.turnId).filter((x): x is string => !!x)]);
    for (const turnId of check) {
      if (s.running.includes(turnId)) continue;
      const r = await call<{ status: string } & Done>('GET', `/api/turn?chatId=${encodeURIComponent(chatId)}&turnId=${encodeURIComponent(turnId)}&backend=${encodeURIComponent(chat.backend)}`);
      if (r.status === 'settled') applyDone(chatId, r);
      else if (r.status === 'unknown') {
        // The Mac never saw it or forgot it: the row stops spinning and says so.
        patch(chatId, (th) => ({
          msgs: th.msgs.map((m) =>
            m.turnId === turnId && (m.status === 'live' || m.status === 'sending')
              ? m.role === 'user'
                ? { ...m, status: 'failed' as const, error: 'The Mac has no record of this. Send it again?' }
                : { ...m, status: undefined, text: m.text || '(no reply arrived)' }
              : m,
          ),
        }));
      }
    }
  } catch {
    /* offline: try again on the next connect */
  }
}

// ---- sending ----

export interface OutFile {
  name: string;
  mime: string;
  dataBase64: string;
}

function firstLine(text: string): string {
  const line = text.trim().split('\n')[0] ?? '';
  return line.length > 60 ? `${line.slice(0, 57)}…` : line || 'New chat';
}

/** Send a message. Returns when the Mac has it (or it's waiting in the outbox). */
export async function send(chatId: string, text: string, opts: { files?: OutFile[]; turnId?: string } = {}) {
  let chat = useApp.getState().chats[chatId];
  if (!chat) return;
  const turnId = opts.turnId ?? newTurnId();
  const busy = threadOf(chatId).msgs.some((m) => m.status === 'live');
  const at = Date.now();
  const mine: Msg = {
    id: `u-${turnId}`,
    role: 'user',
    text,
    at,
    turnId,
    status: 'sending',
    files: opts.files?.map((f) => f.name),
  };
  patch(chatId, (t) => ({ msgs: [...t.msgs.filter((m) => m.id !== mine.id), mine] }));
  if (chat.title === 'New chat' && text.trim()) chat = await updateChat(chatId, { title: firstLine(text) });
  void updateChat(chatId, { preview: text, touch: true });
  try {
    const r = await call<{ accepted: boolean; turnId: string; sessionID?: string }>('POST', '/api/chat', {
      chatId,
      sessionID: chat.sessionID || undefined,
      text,
      model: chat.model || undefined,
      backend: chat.backend,
      effort: chat.effort || undefined,
      cwd: chat.cwd || undefined,
      turnId,
      files: opts.files?.length ? opts.files : undefined,
    }, { timeoutMs: 60_000 });
    if (r.sessionID && r.sessionID !== chat.sessionID) {
      putChat({ ...useApp.getState().chats[chatId], sessionID: r.sessionID });
      void updateChat(chatId, { sessionID: r.sessionID });
    }
    patch(chatId, (t) => {
      const msgs = t.msgs.map((m) => (m.id === mine.id ? { ...m, status: busy ? ('queued' as const) : ('sent' as const) } : m));
      return { msgs: busy ? msgs : ensureLive(msgs, turnId, at + 1).msgs };
    });
    removeFromOutbox(turnId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 0) {
      patch(chatId, (t) => ({ msgs: t.msgs.map((m) => (m.id === mine.id ? { ...m, status: 'waiting' as const } : m)) }));
      if (!opts.files?.length) addToOutbox({ chatId, text, turnId, at });
      else toast('No connection: attachments can’t wait offline. Send again when you’re back.');
    } else {
      patch(chatId, (t) => ({ msgs: t.msgs.map((m) => (m.id === mine.id ? { ...m, status: 'failed' as const, error: (e as Error).message } : m)) }));
    }
  }
}

/** A queued message (behind a running reply) moves ahead now (Claude: steer into the running turn). */
export async function sendNow(chatId: string, turnId: string) {
  const chat = useApp.getState().chats[chatId];
  try {
    const r = await call<{ steered: boolean }>('POST', '/api/steer', { chatId, turnId, backend: chat?.backend });
    if (!r.steered) toast('It can’t jump the queue on this backend. It goes when the current reply ends.');
  } catch (e) {
    toast((e as Error).message);
  }
}

export async function discardQueued(chatId: string, turnId: string) {
  const chat = useApp.getState().chats[chatId];
  try {
    const r = await call<{ cancelled: boolean }>('POST', '/api/queued-cancel', { chatId, turnId, backend: chat?.backend });
    if (r.cancelled) patch(chatId, (t) => ({ msgs: t.msgs.filter((m) => m.turnId !== turnId) }));
    else toast('It already started.');
  } catch (e) {
    toast((e as Error).message);
  }
}

export async function stop(chatId: string) {
  const chat = useApp.getState().chats[chatId];
  if (!chat) return;
  const live = threadOf(chatId).msgs.filter((m) => m.status === 'live');
  try {
    await call('POST', '/api/stop', { chatId, sessionID: chat.sessionID, backend: chat.backend, turnId: live.at(-1)?.turnId });
  } catch (e) {
    toast(`Couldn’t stop it: ${(e as Error).message}`);
    return;
  }
  // The server settles a stopped turn; until then the row says so.
  patch(chatId, (t) => ({ status: 'Stopping…', msgs: t.msgs }));
  setTimeout(() => void reconcile(chatId), 2500);
}

/** Remove a failed or waiting message, giving its text back for editing. */
export function takeBack(chatId: string, msgId: string): string {
  const m = threadOf(chatId).msgs.find((x) => x.id === msgId);
  if (!m) return '';
  if (m.turnId) removeFromOutbox(m.turnId);
  patch(chatId, (t) => ({ msgs: t.msgs.filter((x) => x.id !== msgId) }));
  return m.text;
}

export async function retry(chatId: string, msgId: string) {
  const text = takeBack(chatId, msgId);
  if (text) await send(chatId, text);
}

export function addNote(chatId: string, text: string) {
  patch(chatId, (t) => ({ msgs: [...t.msgs, { id: `n-${Date.now()}`, role: 'note', text, at: Date.now() }] }));
}

export function clearLocal(chatId: string) {
  patch(chatId, () => ({ msgs: [] }));
}

// ---- outbox: sends made with no connection ----

interface Waiting {
  chatId: string;
  text: string;
  turnId: string;
  at: number;
}
const OUTBOX = 'terminus.outbox.v1';
function readOutbox(): Waiting[] {
  try {
    return JSON.parse(localStorage.getItem(OUTBOX) || '[]');
  } catch {
    return [];
  }
}
function writeOutbox(list: Waiting[]) {
  try {
    localStorage.setItem(OUTBOX, JSON.stringify(list));
  } catch {
    /* ignore */
  }
}
function addToOutbox(w: Waiting) {
  writeOutbox([...readOutbox().filter((x) => x.turnId !== w.turnId), w]);
}
function removeFromOutbox(turnId: string) {
  const list = readOutbox();
  if (list.some((x) => x.turnId === turnId)) writeOutbox(list.filter((x) => x.turnId !== turnId));
}
export const outboxCount = () => readOutbox().length;

let flushing = false;
/** Send what waited, oldest first, with the same turn ids (the server dedupes a repeat). */
export async function flushOutbox() {
  if (flushing) return;
  flushing = true;
  try {
    for (const w of readOutbox().sort((a, b) => a.at - b.at)) {
      if (!useApp.getState().chats[w.chatId]) {
        removeFromOutbox(w.turnId);
        continue;
      }
      // Drop the waiting row; send() puts it back as sending, then sent.
      patch(w.chatId, (t) => ({ msgs: t.msgs.filter((m) => m.id !== `u-${w.turnId}`) }));
      await send(w.chatId, w.text, { turnId: w.turnId });
      if (readOutbox().some((x) => x.turnId === w.turnId)) break; // still offline
    }
  } finally {
    flushing = false;
  }
}
