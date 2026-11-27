/** Allow/Deny prompts on the phone, for anything on the server that wants a
 *  human decision. Nothing here changes behaviour by itself: a caller must
 *  ask. Requests travel to the phone over the existing phone channel
 *  (phone-browser.ts) and a notification goes out through the normal inbox.
 *
 *  POST /api/approvals/request  {source,title,detail?,timeoutMs?,chat?} → {decision}
 *       (`chat` = the chat that is asking; the notification then opens that thread)
 *       (blocks until answered; allow | deny | timeout)
 *  POST /api/approvals/decide   {id,decision}  — phone only (master token)
 *  There is deliberately no list endpoint: ids reach only the phone. */
import type { IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { appendInbox, ALERTS_CHAT, isChatId } from "./inbox.js";
import { phoneSend, onPhoneConnect } from "./phone-browser.js";
import type { Json } from "./util.js";

type Body = (req: IncomingMessage, max?: number) => Promise<
  { ok: true; value: Record<string, unknown> } | { ok: false; status: number; error: string }>;
export type Decision = "allow" | "deny" | "timeout";

interface Pending {
  view: { id: string; source: string; title: string; detail: string; expiresAt: number; chat: string };
  resolve: (d: Decision) => void;
  timer: NodeJS.Timeout;
}

const DEFAULT_TIMEOUT_MS = 120_000;
const MAX_TIMEOUT_MS = 600_000;
const MAX_PENDING = 20;
const pending = new Map<string, Pending>();
let lastNotify = 0;

/** The web app (pwa/) hears every request and its end, and can list what's open. */
type ApprovalView = Pending["view"];
type ApprovalListener = (event: "approval" | "approval_cancel", data: ApprovalView | { id: string }) => void;
const approvalListeners: ApprovalListener[] = [];
export function onApproval(fn: ApprovalListener): void { approvalListeners.push(fn); }
function tellApproval(event: "approval" | "approval_cancel", data: ApprovalView | { id: string }): void {
  for (const fn of approvalListeners) {
    try { fn(event, data); } catch { /* never breaks the request */ }
  }
}
export function pendingApprovals(): ApprovalView[] {
  return [...pending.values()].map((p) => p.view);
}

const clip = (v: unknown, n: number): string => (typeof v === "string" ? v.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").slice(0, n) : "");

/** Ask the person. Resolves allow, deny, or timeout (callers treat timeout as deny). */
export function askPhone(opts: { source: string; title: string; detail?: string; timeoutMs?: number; chat?: string }): Promise<Decision> {
  if (pending.size >= MAX_PENDING) return Promise.resolve("deny");
  const ms = Math.min(Math.max(opts.timeoutMs ?? DEFAULT_TIMEOUT_MS, 5_000), MAX_TIMEOUT_MS);
  const id = randomUUID();
  const view = {
    id,
    source: clip(opts.source, 40) || "Agent",
    title: clip(opts.title, 160) || "Permission needed",
    detail: clip(opts.detail, 600),
    expiresAt: Date.now() + ms,
    chat: opts.chat && isChatId(opts.chat) ? opts.chat : "",
  };
  return new Promise<Decision>((resolve) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      phoneSend("approval_cancel", { id });
      tellApproval("approval_cancel", { id });
      resolve("timeout");
    }, ms);
    pending.set(id, { view, resolve, timer });
    const delivered = phoneSend("approval", view);
    tellApproval("approval", view);
    // Not connected (app closed): a notification brings the person in, and the
    // request is replayed when the phone channel connects. At most one per 20s.
    if (!delivered && Date.now() - lastNotify > 20_000) {
      lastNotify = Date.now();
      const chat = opts.chat && isChatId(opts.chat) ? opts.chat : ALERTS_CHAT;
      void appendInbox("approvals", `${view.source} needs your approval`, view.title, chat, { kind: "event", priority: "high" });
    }
  });
}

onPhoneConnect(() => {
  for (const p of pending.values()) phoneSend("approval", p.view);
});

export async function handleApprovalRequest(req: IncomingMessage, res: ServerResponse, json: Json, readBody: Body): Promise<void> {
  const body = await readBody(req, 16_000);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const title = clip(body.value.title, 160);
  if (!title) return json(res, 400, { error: "missing title" });
  const t = Number(body.value.timeoutMs);
  const decision = await askPhone({
    source: clip(body.value.source, 40) || "Agent",
    title,
    detail: clip(body.value.detail, 600),
    timeoutMs: Number.isFinite(t) && t > 0 ? t : undefined,
    chat: clip(body.value.chat, 80) || undefined,
  });
  if (res.destroyed) return;
  json(res, 200, { decision });
}

export async function handleApprovalDecide(req: IncomingMessage, res: ServerResponse, json: Json, readBody: Body): Promise<void> {
  const body = await readBody(req, 4_000);
  if (!body.ok) return json(res, body.status, { error: body.error });
  const id = String(body.value.id || "");
  const d = body.value.decision;
  if (d !== "allow" && d !== "deny") return json(res, 400, { error: "decision must be allow or deny" });
  const p = pending.get(id);
  if (!p) return json(res, 404, { error: "unknown or already answered" });
  clearTimeout(p.timer);
  pending.delete(id);
  phoneSend("approval_cancel", { id });
  tellApproval("approval_cancel", { id });
  p.resolve(d);
  json(res, 200, { ok: true });
}
