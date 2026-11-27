/** The web app's server side: chat index, live events, push, presence.
 *
 *  GET  /api/pwa/chats                → {chats}
 *  POST /api/pwa/chats                {id, ...fields, touch?} → {chat}
 *  POST /api/pwa/chats/read           {id}
 *  POST /api/pwa/chats/delete         {id}
 *  GET  /api/pwa/events               SSE: chat {chat}, approval {view}, approval_cancel {id}, inbox {...}
 *  POST /api/pwa/presence             {visible, chatId?}: a foreground app gets no push for what it shows
 *  GET  /api/pwa/approvals            → {approvals}: open requests (master credential only)
 *  GET  /api/pwa/push                 → {publicKey, devices}
 *  POST /api/pwa/push/subscribe       {subscription, label?}
 *  POST /api/pwa/push/unsubscribe     {endpoint}
 *  POST /api/pwa/push/test            → {devices, sent, errors}
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { onApproval, pendingApprovals } from "../approvals.js";
import { json, readJsonBody } from "../http.js";
import { scheduleChatOf } from "../inbox.js";
import type { Route } from "../routes.js";
import { onWake } from "../wake.js";
import { push } from "./push.js";
import { chatStore, previewOf, type PwaChat } from "./store.js";

interface EventClient { res: ServerResponse; visible: boolean; chatId: string; seen: number }
const clients = new Set<EventClient>();
/** Presence reports outlive a dropped stream briefly (iOS freezes a backgrounded page without a close). */
const PRESENCE_MS = 45_000;

function broadcast(event: string, data: unknown): void {
  const line = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const c of [...clients]) {
    try { c.res.write(line); } catch { clients.delete(c); }
  }
}

/** True when an app on screen is already showing this (any chat when chatId is empty). */
export function shownInForeground(chatId = "", now = Date.now()): boolean {
  for (const c of clients) {
    if (!c.visible || now - c.seen > PRESENCE_MS) continue;
    if (!chatId || c.chatId === chatId || c.chatId === "") return true;
  }
  return false;
}

const appUrl = (chatId: string): string => `/app/chat/${encodeURIComponent(chatId)}`;

function notify(chat: PwaChat | null, title: string, body: string, chatId: string): void {
  if (shownInForeground(chatId)) return;
  void push().notify({ title, body, url: chat ? appUrl(chat.id) : "/app/", tag: chatId || undefined }).catch(() => {});
}

let wired = false;
export function initPwa(): void {
  if (wired) return;
  wired = true;
  const store = chatStore();

  onWake((event, d) => {
    if (event === "done") {
      const chatId = String(d.chatId ?? "");
      if (!store.get(chatId)) return; // not a web-app chat
      const failed = Boolean(d.failed);
      const text = String(d.text ?? "");
      const chat = store.landed(chatId, { preview: failed ? "Reply failed" : text, needsYou: failed, unread: !shownInForeground(chatId) });
      if (chat) broadcast("chat", chat);
      notify(chat, chat?.title || "Terminus", failed ? "The reply failed. Open the chat to retry." : previewOf(text) || "Reply ready", chatId);
      return;
    }
    // inbox delivery
    const job = String(d.job ?? "");
    const target = typeof d.chat === "string" && d.chat ? d.chat : scheduleChatOf(job);
    const title = String(d.title ?? job);
    const body = String(d.body ?? "");
    const needsYou = d.kind === "event";
    const chat = store.landed(target, { preview: title + (body ? ` · ${body}` : ""), title: target.startsWith("schedule:") ? job : target === "alerts" ? "Alerts" : title, needsYou, inbox: true, unread: !shownInForeground(target) });
    if (chat) broadcast("chat", chat);
    broadcast("inbox", { job, chat: target, title, at: d.at, kind: d.kind });
    notify(chat, title, previewOf(body) || (chat?.title ?? "Terminus"), target);
  });

  onApproval((event, d) => {
    broadcast(event, d);
    if (event !== "approval") return;
    const v = d as { id: string; source: string; title: string; chat: string };
    if (v.chat) {
      const chat = store.landed(v.chat, { needsYou: true, unread: false });
      if (chat) broadcast("chat", chat);
    }
    notify(v.chat ? store.get(v.chat) ?? null : null, `${v.source} needs your approval`, v.title, v.chat);
  });
}

function handleEvents(req: IncomingMessage, res: ServerResponse): void {
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" });
  const c: EventClient = { res, visible: true, chatId: "", seen: Date.now() };
  try {
    const q = new URL(req.url || "", "http://x").searchParams;
    c.chatId = q.get("chatId") ?? "";
  } catch { /* none */ }
  clients.add(c);
  res.write(`: hello ${Date.now()}\n\n`);
  for (const v of pendingApprovals()) res.write(`event: approval\ndata: ${JSON.stringify(v)}\n\n`);
  const beat = setInterval(() => {
    try { res.write(`: ping ${Date.now()}\n\n`); } catch { /* close below */ }
  }, 20_000);
  beat.unref?.();
  const drop = (): void => { clearInterval(beat); clients.delete(c); };
  req.on("close", drop);
  res.on("error", drop);
}

type Body = Record<string, unknown>;
const withBody = (fn: (b: Body, req: IncomingMessage, res: ServerResponse) => unknown) => async ({ req, res }: { req: IncomingMessage; res: ServerResponse }) => {
  const parsed = await readJsonBody(req, 20_000);
  if (!parsed.ok) return json(res, parsed.status, { error: parsed.error });
  return fn(parsed.value, req, res);
};

export const pwaRoutes: readonly Route[] = [
  { method: "GET", path: "/api/pwa/chats", handle: ({ res }) => json(res, 200, { chats: chatStore().list() }) },
  { method: "POST", path: "/api/pwa/chats", handle: withBody((b, _req, res) => {
    const chat = chatStore().upsert(b);
    if (!chat) return json(res, 400, { error: "bad chat id" });
    broadcast("chat", chat);
    return json(res, 200, { chat });
  }) },
  { method: "POST", path: "/api/pwa/chats/read", handle: withBody((b, _req, res) => {
    const id = String(b.id ?? "");
    chatStore().markRead(id);
    const chat = chatStore().get(id);
    if (chat) broadcast("chat", chat);
    return json(res, 200, { ok: true });
  }) },
  { method: "POST", path: "/api/pwa/chats/delete", handle: withBody((b, _req, res) => {
    const id = String(b.id ?? "");
    const ok = chatStore().delete(id);
    if (ok) broadcast("chat_deleted", { id });
    return json(res, 200, { ok });
  }) },
  { method: "GET", path: "/api/pwa/events", handle: ({ req, res }) => handleEvents(req, res) },
  { method: "POST", path: "/api/pwa/presence", handle: withBody((b, _req, res) => {
    // The newest stream stands for this device; good enough for one person.
    const last = [...clients].at(-1);
    if (last) {
      last.visible = b.visible !== false;
      last.chatId = typeof b.chatId === "string" ? b.chatId : "";
      last.seen = Date.now();
    }
    return json(res, 200, { ok: true });
  }) },
  { method: "GET", path: "/api/pwa/approvals", handle: ({ res }) => json(res, 200, { approvals: pendingApprovals() }) },
  { method: "GET", path: "/api/pwa/push", handle: ({ res }) => json(res, 200, { publicKey: push().publicKey, devices: push().count() }) },
  { method: "POST", path: "/api/pwa/push/subscribe", handle: withBody((b, _req, res) => {
    const ok = push().subscribe(b.subscription, typeof b.label === "string" ? b.label : undefined);
    return json(res, ok ? 200 : 400, ok ? { ok: true, devices: push().count() } : { error: "bad subscription" });
  }) },
  { method: "POST", path: "/api/pwa/push/unsubscribe", handle: withBody((b, _req, res) => {
    push().unsubscribe(String(b.endpoint ?? ""));
    return json(res, 200, { ok: true, devices: push().count() });
  }) },
  { method: "POST", path: "/api/pwa/push/test", handle: async ({ res }) => {
    const r = await push().notify({ title: "Terminus", body: "Notifications work.", url: "/app/" });
    return json(res, 200, r);
  } },
];
