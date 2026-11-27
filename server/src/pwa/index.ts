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
 *  POST /api/pwa/transcribe           audio body → {text} (transcribe.ts)
 *  POST /api/pwa/signin               {site, url?, reason?, chatId?} → {id, target?}: an agent needs you to
 *                                     sign in to a site in the agents' Chrome; the phone is notified
 *  GET  /api/pwa/signin?id=&wait=S    → {request}: long-polls up to S seconds (max 600) for done/cancelled
 *  GET  /api/pwa/signins              → {requests}: open ones
 *  POST /api/pwa/signin/answer        {id, state: done|cancelled}
 *  /api/remote/*                      the agents' Chrome on the phone (remote.ts)
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { onApproval, pendingApprovals } from "../approvals.js";
import { json, readJsonBody } from "../http.js";
import { scheduleChatOf } from "../inbox.js";
import type { Route } from "../routes.js";
import { onWake } from "../wake.js";
import { push } from "./push.js";
import { openTab } from "./remote.js";
import { handleTranscribe } from "./transcribe.js";
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
  for (const r of openSignins()) res.write(`event: signin\ndata: ${JSON.stringify(r)}\n\n`);
  const beat = setInterval(() => {
    try { res.write(`: ping ${Date.now()}\n\n`); } catch { /* close below */ }
  }, 20_000);
  beat.unref?.();
  const drop = (): void => { clearInterval(beat); clients.delete(c); };
  req.on("close", drop);
  res.on("error", drop);
}

export interface SigninRequest {
  id: string; site: string; url: string; reason: string; chatId: string; target: string;
  at: number; state: "open" | "done" | "cancelled"; answeredAt?: number;
}
const signins = new Map<string, SigninRequest>();
const signinWaiters = new Map<string, Set<() => void>>();
const SIGNIN_TTL_MS = 6 * 3600_000;
export const openSignins = (): SigninRequest[] => [...signins.values()].filter((r) => r.state === "open" && Date.now() - r.at < SIGNIN_TTL_MS);
const remoteUrl = (r: SigninRequest): string => `/app/remote?req=${encodeURIComponent(r.id)}${r.target ? `&target=${encodeURIComponent(r.target)}` : ""}`;

/** Pure: validate an agent's sign-in request body. */
export function parseSigninBody(b: Record<string, unknown>): { site: string; url: string; reason: string; chatId: string } | { error: string } {
  const site = typeof b.site === "string" ? b.site.trim().slice(0, 80) : "";
  const url = typeof b.url === "string" ? b.url.trim() : "";
  if (!site && !url) return { error: "site or url required" };
  if (url && !/^https?:\/\//i.test(url)) return { error: "http(s) url only" };
  let name = site;
  if (!name) { try { name = new URL(url).hostname; } catch { return { error: "bad url" }; } }
  return { site: name, url, reason: typeof b.reason === "string" ? b.reason.trim().slice(0, 300) : "", chatId: typeof b.chatId === "string" ? b.chatId.slice(0, 200) : "" };
}

function answerSignin(id: string, state: "done" | "cancelled"): SigninRequest | null {
  const r = signins.get(id);
  if (!r) return null;
  if (r.state === "open") Object.assign(r, { state, answeredAt: Date.now() });
  broadcast("signin", r);
  for (const fn of signinWaiters.get(id) ?? []) fn();
  signinWaiters.delete(id);
  return r;
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
  { method: "POST", path: "/api/pwa/signin", handle: withBody(async (b, _req, res) => {
    const p = parseSigninBody(b);
    if ("error" in p) return json(res, 400, p);
    // One open request per site: asking again returns the same one.
    const dup = openSignins().find((r) => r.site === p.site);
    if (dup) return json(res, 200, { id: dup.id, target: dup.target, url: remoteUrl(dup) });
    let target = "";
    try { target = (await openTab(p.url || "about:blank")).id; } catch { /* Chrome down: still ask */ }
    const r: SigninRequest = { id: `si-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, ...p, target, at: Date.now(), state: "open" };
    signins.set(r.id, r);
    for (const [k, v] of signins) if (Date.now() - v.at > SIGNIN_TTL_MS) signins.delete(k);
    broadcast("signin", r);
    void push().notify({ title: `Sign in to ${r.site}`, body: r.reason || "An agent needs you to sign in on the Mac's browser.", url: remoteUrl(r), tag: r.id }).catch(() => {});
    return json(res, 200, { id: r.id, target, url: remoteUrl(r) });
  }) },
  { method: "GET", path: "/api/pwa/signin", handle: async ({ req, res }) => {
    const q = new URL(req.url || "", "http://x").searchParams;
    const r = signins.get(q.get("id") ?? "");
    if (!r) return json(res, 404, { error: "no such request" });
    const wait = Math.min(600, Math.max(0, Number(q.get("wait")) || 0));
    if (r.state === "open" && wait > 0) {
      await new Promise<void>((done) => {
        const fn = (): void => { clearTimeout(t); done(); };
        const t = setTimeout(() => { signinWaiters.get(r.id)?.delete(fn); done(); }, wait * 1000);
        if (!signinWaiters.has(r.id)) signinWaiters.set(r.id, new Set());
        signinWaiters.get(r.id)!.add(fn);
        req.on("close", fn);
      });
    }
    return json(res, 200, { request: r });
  } },
  { method: "GET", path: "/api/pwa/signins", handle: ({ res }) => json(res, 200, { requests: openSignins() }) },
  { method: "POST", path: "/api/pwa/signin/answer", handle: withBody((b, _req, res) => {
    const state = b.state === "cancelled" ? "cancelled" : "done";
    const r = answerSignin(String(b.id ?? ""), state);
    return r ? json(res, 200, { request: r }) : json(res, 404, { error: "no such request" });
  }) },
  { method: "POST", path: "/api/pwa/transcribe", handle: ({ req, res }) => handleTranscribe(req, res) },
  { method: "POST", path: "/api/pwa/push/test", handle: async ({ res }) => {
    const r = await push().notify({ title: "Terminus", body: "Notifications work.", url: "/app/" });
    return json(res, 200, r);
  } },
];
