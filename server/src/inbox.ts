/** Delivery inbox + SSE client registry (no new deps).
 *
 *  POST /api/inbox {job,title,body,chat?} → append {job,title,body,at,chat?} to
 *    ./.run/inbox/<job>.jsonl (0600, repo-local runtime dir, gitignored).
 *  `chat` (optional) is a session/thread id override: the app delivers the
 *  entry into that thread instead of the job thread. Validated by isChatId,
 *  bearer-gated like everything else.
 *  GET  /api/inbox?job=&since= → [{job,title,body,at,chat?}] oldest-first, cap 500.
 *  GET  /api/unread[?since=] → {counts:{job:n}, latest:{job:at}}.
 *
 *  Stream clients (GET /api/stream?chatId=) register here; appends
 *  broadcast an {inbox,job} ping to clients whose chat is the entry's
 *  thread (`chat` override or `schedule:<job>`) or the `alerts` thread.
 *  The app pulls the full entry via GET /api/inbox.
 */
import { wakeInbox } from "./wake.js";
import type { IncomingMessage, ServerResponse } from "node:http";
import { readBodyText as readBody, type Json } from "./util.js";
import { readFile, mkdir, readdir, appendFile, chmod } from "node:fs/promises";
import { join } from "node:path";
import { homedir } from "node:os";
import { cleanKind, cleanPriority, type InboxKind, type InboxPriority } from "./origin.js";

export type { Json } from "./util.js";

/** `kind: "event"` = something needs the person (a browser hand-off, an approval, a failed
 *  delivery), not a message to read. The app files it in the chat it belongs to and flags that
 *  thread "needs you" until it is opened. `priority: "high"` makes the phone ping louder. */
export type InboxEntry = {
  job: string; title: string; body: string; at: number; chat?: string;
  kind?: InboxKind; priority?: InboxPriority;
};

function withFlags(entry: InboxEntry, kind: unknown, priority: unknown): InboxEntry {
  const k = cleanKind(kind);
  const p = cleanPriority(priority);
  if (k) entry.kind = k;
  if (p) entry.priority = p;
  return entry;
}

/** Chat ids: legacy `s-<ts>` plus stable job threads `schedule:<name>`
 *  and the single `alerts` thread. Colon is the only addition. */
export const CHATID_RE = /^[A-Za-z0-9_:-]{1,80}$/;
export const isChatId = (s: string): boolean => CHATID_RE.test(s);

export const ALERTS_CHAT = "alerts";
export const scheduleChatOf = (job: string): string => `schedule:${job}`;

/** Repo-local runtime dir (gitignored): device/operational state lives here,
 *  never permission-walled. ONLY true secrets stay in ~/.config/terminus/secrets/. */
import { runDir } from "./paths.js";
const RUN_DIR = runDir();
const INBOX_DIR = join(RUN_DIR, "inbox");
// Created 0700 at startup if missing (fire-and-forget; the POST path ensures it too).
void mkdir(INBOX_DIR, { recursive: true, mode: 0o700 }).catch(() => {});
const INBOX_CAP = 500;
/** Body cap. Was 8000 and silently sliced mid-word (7 Oct brief arrived cut at "ligh").
 *  Now 16000, and an over-cap body is cut at a paragraph boundary with a visible marker. */
export const MAX_BODY = 16000;
export function clipBody(s: string, max = MAX_BODY): string {
  if (s.length <= max) return s;
  let cut = s.lastIndexOf("\n\n", max - 80);
  if (cut < max / 2) cut = max - 80;
  const hi = s.charCodeAt(cut - 1);
  if (hi >= 0xd800 && hi <= 0xdbff) cut -= 1; // never split a surrogate pair (emoji)
  return `${s.slice(0, cut).trimEnd()}\n\n[inbox clipped ${s.length - cut} chars]`;
}

/** Pure: job names are file stems — strict, no traversal. */
export function sanitizeJobName(job: unknown): string | null {
  if (typeof job !== "string") return null;
  const j = job.trim();
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(j)) return null;
  return j;
}

/** Pure: since= query param → ms epoch, 0 on garbage. */
export function parseSince(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

/** Pure: filter + sort + cap a merged entry list. */
export function selectInbox(all: InboxEntry[], job: string | null, since: number): InboxEntry[] {
  return all
    .filter((e) => e && typeof e.at === "number" && e.at > since && (!job || e.job === job))
    .sort((a, b) => a.at - b.at)
    .slice(-INBOX_CAP);
}

function inboxFile(job: string): string {
  return join(INBOX_DIR, `${job}.jsonl`);
}

async function readJobFile(job: string): Promise<InboxEntry[]> {
  let raw = "";
  try {
    raw = await readFile(inboxFile(job), "utf8");
  } catch {
    return [];
  }
  const out: InboxEntry[] = [];
  for (const line of raw.split("\n")) {
    const t = line.trim();
    if (!t) continue;
    try {
      const e = JSON.parse(t) as InboxEntry;
      if (e && typeof e.title === "string" && typeof e.body === "string" && typeof e.at === "number") {
        const clean: InboxEntry = withFlags(
          { job, title: e.title.slice(0, 200), body: clipBody(e.body), at: e.at }, e.kind, e.priority);
        if (typeof e.chat === "string" && isChatId(e.chat)) clean.chat = e.chat;
        out.push(clean);
      }
    } catch {
      /* skip corrupt lines */
    }
  }
  return out;
}

async function readAllJobs(): Promise<string[]> {
  try {
    return (await readdir(INBOX_DIR)).filter((f) => f.endsWith(".jsonl")).map((f) => f.slice(0, -6));
  } catch {
    return [];
  }
}

// ---- SSE client registry ----

type StreamClient = { chatId: string; res: ServerResponse };
const streamClients = new Set<StreamClient>();

export function streamRegister(chatId: string, res: ServerResponse): StreamClient {
  const c: StreamClient = { chatId, res };
  streamClients.add(c);
  return c;
}

export function streamUnregister(c: StreamClient): void {
  streamClients.delete(c);
}

/** Chat ids with a live stream right now (app open). Bearer-gated. */
export function streamChats(): string[] {
  return [...new Set([...streamClients].map((c) => c.chatId))];
}

function streamSend(c: StreamClient, event: string, data: string): void {
  try {
    c.res.write(`event: ${event}\ndata: ${data}\n\n`);
  } catch {
    streamClients.delete(c);
  }
}

/** Ping stream clients whose chat is the entry's thread (chat override
 *  or the job's thread) or Alerts. */
export function broadcastInbox(entry: InboxEntry): void {
  const targets = new Set([scheduleChatOf(entry.job), ALERTS_CHAT]);
  if (entry.chat) targets.add(entry.chat);
  const payload = JSON.stringify({ job: entry.job, title: entry.title, at: entry.at, kind: entry.kind, priority: entry.priority });
  wakeInbox(entry);
  for (const c of [...streamClients]) {
    if (targets.has(c.chatId)) streamSend(c, "inbox", payload);
  }
}

// ---- handlers (routes are bearer-gated in server.ts) ----

/** Internal append (watcher, loop events): same write + broadcast as the
 *  HTTP path, no request involved. Returns the entry or null when empty. */
export async function appendInbox(
  job: string, title: string, body: string, chat?: string,
  flags?: { kind?: InboxKind; priority?: InboxPriority },
): Promise<InboxEntry | null> {
  const j = sanitizeJobName(job);
  const t = (title || "").trim().slice(0, 200);
  const b = clipBody(body || "");
  if (!j || (!t && !b)) return null;
  const entry: InboxEntry = withFlags({ job: j, title: t, body: b, at: Date.now() }, flags?.kind, flags?.priority);
  if (typeof chat === "string" && isChatId(chat)) entry.chat = chat;
  try {
    await mkdir(INBOX_DIR, { recursive: true, mode: 0o700 });
    await appendFile(inboxFile(j), JSON.stringify(entry) + "\n", { mode: 0o600 });
    await chmod(inboxFile(j), 0o600).catch(() => {});
  } catch {
    return null;
  }
  broadcastInbox(entry);
  return entry;
}

/** POST /api/inbox {job,title,body,chat?,kind?,priority?} — internal append. */
export async function handleInboxPost(req: IncomingMessage, res: ServerResponse, json: Json) {
  let body: { job?: unknown; title?: unknown; body?: unknown; chat?: unknown; kind?: unknown; priority?: unknown };
  try {
    body = JSON.parse(await readBody(req, 100_000));
  } catch {
    json(res, 400, { error: "invalid json" });
    return;
  }
  const job = sanitizeJobName(body.job);
  const title = typeof body.title === "string" ? body.title.trim().slice(0, 200) : "";
  const text = typeof body.body === "string" ? clipBody(body.body) : "";
  if (!job) {
    json(res, 400, { error: "bad job" });
    return;
  }
  if (!title && !text) {
    json(res, 400, { error: "missing title/body" });
    return;
  }
  const entry: InboxEntry = withFlags({ job, title, body: text, at: Date.now() }, body.kind, body.priority);
  if (typeof body.chat === "string" && isChatId(body.chat)) entry.chat = body.chat;
  try {
    await mkdir(INBOX_DIR, { recursive: true, mode: 0o700 });
    await appendFile(inboxFile(job), JSON.stringify(entry) + "\n", { mode: 0o600 });
    await chmod(inboxFile(job), 0o600).catch(() => {});
  } catch {
    json(res, 500, { error: "append failed" });
    return;
  }
  broadcastInbox(entry);
  json(res, 200, { ok: true, at: entry.at });
}

/** GET /api/inbox?job=&since= — pull for sync/backstop. */
export async function handleInboxGet(req: IncomingMessage, res: ServerResponse, json: Json) {
  let q: URLSearchParams;
  try {
    q = new URL(req.url || "", "http://x").searchParams;
  } catch {
    json(res, 400, { error: "bad query" });
    return;
  }
  const rawJob = q.get("job");
  const job = rawJob ? sanitizeJobName(rawJob) : null;
  if (rawJob && !job) {
    json(res, 400, { error: "bad job" });
    return;
  }
  const since = parseSince(q.get("since"));
  try {
    const jobs = job ? [job] : await readAllJobs();
    const merged: InboxEntry[] = [];
    for (const j of jobs.slice(0, 200)) merged.push(...(await readJobFile(j)));
    json(res, 200, { entries: selectInbox(merged, job, since) });
  } catch {
    json(res, 500, { error: "read failed" });
  }
}

/** GET /api/unread[?since=] — per-job counts + latest stamps. The app
 *  keeps per-device read high-waters locally and diffs against this. */
export async function handleUnread(req: IncomingMessage, res: ServerResponse, json: Json) {
  let since = 0;
  try {
    since = parseSince(new URL(req.url || "", "http://x").searchParams.get("since"));
  } catch {
    /* 0 */
  }
  try {
    const counts: Record<string, number> = {};
    const latest: Record<string, number> = {};
    for (const j of await readAllJobs()) {
      const entries = await readJobFile(j);
      const fresh = entries.filter((e) => e.at > since);
      if (fresh.length) {
        counts[j] = fresh.length;
        latest[j] = fresh.reduce((m, e) => Math.max(m, e.at), 0);
      }
    }
    json(res, 200, { counts, latest });
  } catch {
    json(res, 500, { error: "read failed" });
  }
}
