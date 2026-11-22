/** Background jobs, kept per chat on the server so the phone never depends on being connected when one ends.
 *
 *  Every start and finish (opencode shells, claude backgrounded tasks) is recorded here as it happens, with a
 *  per-chat sequence number, whether or not a turn or a phone is attached. The live stream carries the same
 *  record (seq included); the app remembers the last seq it applied and fetches the rest with
 *  GET /api/background?chatId=&after=<seq> when a chat opens or the app returns. Finishes are paired with their
 *  start by id (claude reports the same id at both ends), else the oldest open start, so the elapsed time
 *  comes from here. Store: .run/background.json (capped, 2-day retention, no command output beyond the excerpt
 *  the rows already show). */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import { runDir } from "./paths.js";
import type { Json } from "./util.js";

export type BackgroundStatus = "started" | "done" | "failed";
export type BackgroundRecord = {
  seq: number;
  at: number;
  status: BackgroundStatus;
  id: string;
  label: string;
  detail: string;
  full?: string;
  /** Finishes only: time since the matching start, when one was seen. */
  elapsedMs?: number;
};

type ChatLog = { seq: number; events: BackgroundRecord[] };

const PER_CHAT = 60;
const KEEP_MS = 2 * 24 * 3600_000;
/** A start with no finish stops counting as running after this (a missed finish must not pin it forever). */
export const STALE_START_MS = 4 * 3600_000;

let file = join(runDir(), "background.json");
let logs: Map<string, ChatLog> | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;

/** Tests point the store elsewhere. */
export function _setBackgroundFile(path: string): void {
  file = path;
  logs = null;
}

function load(): Map<string, ChatLog> {
  if (logs) return logs;
  logs = new Map();
  try {
    const raw = JSON.parse(readFileSync(file, "utf8")) as Record<string, ChatLog>;
    for (const [k, v] of Object.entries(raw)) if (v && Array.isArray(v.events)) logs.set(k, { seq: v.seq || 0, events: v.events });
  } catch { /* first run */ }
  return logs;
}

function saveSoon(): void {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      mkdirSync(join(file, ".."), { recursive: true, mode: 0o700 });
      writeFileSync(file, JSON.stringify(Object.fromEntries(load())), { mode: 0o600 });
    } catch { /* bookkeeping never breaks a turn */ }
  }, 500);
  saveTimer.unref?.();
}

/** Flush now (tests, shutdown). */
export function flushBackgroundLog(): void {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  try {
    mkdirSync(join(file, ".."), { recursive: true, mode: 0o700 });
    writeFileSync(file, JSON.stringify(Object.fromEntries(load())), { mode: 0o600 });
  } catch { /* ignore */ }
}

/** Starts still open in a chat (no finish yet, not stale). */
export function openStarts(events: BackgroundRecord[], now = Date.now()): BackgroundRecord[] {
  const open: BackgroundRecord[] = [];
  for (const e of events) {
    if (e.status === "started") { open.push(e); continue; }
    const i = open.findIndex((s) => s.id && s.id === e.id);
    if (i >= 0) open.splice(i, 1);
    else if (open.length) open.shift();
  }
  return open.filter((s) => now - s.at < STALE_START_MS);
}

/** Record one event; returns the stored record (with seq, and elapsed for a finish), or null for a
 *  duplicate (same id and status already logged: a redelivered finish). */
export function noteBackground(
  chatId: string,
  ev: { status: string; id?: string; label?: string; detail?: string; full?: string },
  now = Date.now(),
): BackgroundRecord | null {
  const status = ev.status === "started" || ev.status === "done" || ev.status === "failed" ? ev.status : null;
  if (!chatId || !status) return null;
  const store = load();
  const log = store.get(chatId) ?? { seq: 0, events: [] };
  const id = (ev.id || "").slice(0, 64);
  if (id && log.events.some((e) => e.id === id && e.status === status)) return null;
  const rec: BackgroundRecord = {
    seq: log.seq + 1, at: now, status, id,
    label: (ev.label || "background job").replace(/\s+/g, " ").slice(0, 200),
    detail: (ev.detail || "").slice(0, 200),
    ...(ev.full ? { full: ev.full.slice(0, 4000) } : {}),
  };
  if (status !== "started" && id && stoppedIds.has(id)) {
    // Stopped from the phone: the line says so instead of showing the killed job's output.
    stoppedIds.delete(id);
    rec.status = "failed";
    rec.detail = "stopped";
    delete rec.full;
  }
  if (status !== "started") {
    const open = openStarts(log.events, now);
    const byId = id ? open.find((s) => s.id === id) : undefined;
    const start = byId ?? open[0];
    if (start) rec.elapsedMs = now - start.at;
    // The finish keeps the job's own name (the app pairs a job's two lines by it); a backend's closing
    // summary ("Background command … completed") moves to the detail.
    if (byId && byId.label && rec.label !== byId.label) {
      if (!rec.detail && ev.label) rec.detail = rec.label.slice(0, 200);
      rec.label = byId.label;
    }
  }
  log.seq = rec.seq;
  log.events.push(rec);
  log.events = log.events.filter((e) => now - e.at < KEEP_MS).slice(-PER_CHAT);
  store.set(chatId, log);
  saveSoon();
  return rec;
}

/** Events after a seq (the app's last applied), plus how many jobs are still running. */
export function backgroundAfter(chatId: string, after: number, now = Date.now()): { events: BackgroundRecord[]; seq: number; running: number } {
  const log = load().get(chatId);
  if (!log) return { events: [], seq: 0, running: 0 };
  return { events: log.events.filter((e) => e.seq > after), seq: log.seq, running: openStarts(log.events, now).length };
}

/** Ids the owner stopped from the phone; their finish is shown as "stopped". */
const stoppedIds = new Set<string>();
export function markStopped(id: string): void {
  stoppedIds.add(id);
  if (stoppedIds.size > 100) stoppedIds.delete(stoppedIds.values().next().value as string);
}

/** The still-running job a phone line names: the newest open start with that label (the line shows the
 *  label cut to 160 characters). Null when none is open. */
export function runningByLabel(chatId: string, label: string, now = Date.now()): BackgroundRecord | null {
  const log = load().get(chatId);
  const want = label.replace(/\s+/g, " ").trim().slice(0, 160);
  if (!log || !want) return null;
  const open = openStarts(log.events, now).filter((s) => s.id && s.label.slice(0, 160).trim() === want);
  return open.length ? open[open.length - 1] : null;
}

/** GET /api/background?chatId=&after= */
export function handleBackgroundGet(req: IncomingMessage, res: ServerResponse, json: Json): void {
  const q = new URL(req.url || "", "http://x").searchParams;
  const chatId = (q.get("chatId") || "").trim();
  if (!/^[A-Za-z0-9_:-]{1,80}$/.test(chatId)) return json(res, 400, { error: "bad chatId" });
  const after = Math.max(0, Number(q.get("after")) || 0);
  json(res, 200, backgroundAfter(chatId, after));
}

/** Live delivery to the chat's open streams, registered by turn-runtime (avoids an import cycle). */
let fanoutFn: ((chatId: string, event: string, data: string) => number | void) | null = null;
export function setBackgroundFanout(fn: (chatId: string, event: string, data: string) => number | void): void {
  fanoutFn = fn;
}

/** Record and deliver one event: the log first (so a phone that is away gets it later), then any open
 *  stream. A duplicate (already logged) is dropped, so two sources reporting the same job show it once. */
export function publishBackground(chatId: string, ev: { status: string; id?: string; label?: string; detail?: string; full?: string }): BackgroundRecord | null {
  const rec = noteBackground(chatId, ev);
  if (rec) {
    let streams: number | void = 0;
    try { streams = fanoutFn?.(chatId, "background", JSON.stringify(rec)); } catch { /* stream gone */ }
    // Whether the phone had this chat's stream open: with none, the line waits for the app's catch-up.
    console.log(`background ${rec.status} chat=${chatId} id=${rec.id || "-"} streams=${typeof streams === "number" ? streams : "?"}`);
  }
  return rec;
}

/** Claude's finish carries a summary and its output file: the row shows the summary and the output's tail.
 *  Only files under the system temp dirs are read (that is where Claude writes them). */
export function claudeBackgroundFields(e: { status: string; label: string; outputFile?: string; summary?: string }): { label: string; detail: string; full?: string } {
  let detail = "";
  let full = "";
  if (e.status !== "started" && e.outputFile && /^\/(private\/)?(tmp|var\/folders)\//.test(e.outputFile)) {
    try {
      const tail = readFileSync(e.outputFile, "utf8").replace(/\r\n?/g, "\n").trim().slice(-4000);
      detail = tail.replace(/\s+/g, " ").slice(-200);
      if (tail.length > 200) full = tail;
    } catch { /* gone: the label carries it */ }
  }
  const label = (e.status !== "started" && e.summary ? e.summary : e.label).replace(/\s+/g, " ").slice(0, 200);
  return { label, detail, ...(full ? { full } : {}) };
}
