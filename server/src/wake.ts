/** GET /api/wake — one device-level stream that tells the phone something is ready, so it can notify with
 *  the app closed. The phone holds it open from a small foreground service over the tailnet; nothing goes
 *  through a third-party push service.
 *
 *  Events:
 *    `done`  {chatId, turnId, preview, failed}  a reply the person sent for finished (or failed)
 *    `inbox` {job, chat?, at, kind?}           a delivery landed; the phone pulls /api/inbox as usual
 *    `location` {id}                           send a location fix to POST /api/location/reply (location.ts)
 *  Heartbeat `: ping` every WAKE_BEAT_MS keeps NAT and the phone's stall timer happy. */
import type { IncomingMessage, ServerResponse } from "node:http";

export const WAKE_BEAT_MS = 90_000;
const PREVIEW_CHARS = 160;

const clients = new Set<ServerResponse>();

/** In-process listeners (the web app's push and chat index, pwa/): told about every wake event,
 *  whether or not a phone holds the stream. */
type WakeListener = (event: "done" | "inbox", data: Record<string, unknown>) => void;
const listeners: WakeListener[] = [];
export function onWake(fn: WakeListener): void { listeners.push(fn); }
function tell(event: "done" | "inbox", data: Record<string, unknown>): void {
  for (const fn of listeners) {
    try { fn(event, data); } catch { /* a listener never breaks delivery */ }
  }
}

export function wakeClientCount(): number {
  return clients.size;
}

function send(event: string, data: unknown): void {
  const line = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of [...clients]) {
    try {
      res.write(line);
    } catch {
      clients.delete(res);
    }
  }
}

/** Pure: the notification line for a finished reply (first words, whitespace folded). */
export function wakePreview(text: string): string {
  return text.replace(/\s+/g, " ").trim().slice(0, PREVIEW_CHARS);
}

/** A reply the person sent for has settled. Cancelled turns never call this. */
export function wakeDone(chatId: string, turnId: string, text: string, error?: string): void {
  tell("done", { chatId, turnId, text, failed: Boolean(error), error: error ?? "" });
  if (!clients.size) return;
  send("done", { chatId, turnId, preview: wakePreview(error ? "" : text), failed: Boolean(error) });
}

/** A delivery landed in the inbox. Content stays in /api/inbox; the phone pulls it. */
export function wakeInbox(entry: { job: string; chat?: string; at: number; kind?: string; title?: string; body?: string; priority?: string }): void {
  tell("inbox", { ...entry });
  if (!clients.size) return;
  send("inbox", { job: entry.job, ...(entry.chat ? { chat: entry.chat } : {}), at: entry.at, ...(entry.kind ? { kind: entry.kind } : {}) });
}

/** Ask the phone for a fresh location fix; it answers on POST /api/location/reply with the same id. */
export function wakeLocation(id: string): void {
  if (!clients.size) return;
  send("location", { id });
}

export function handleWake(req: IncomingMessage, res: ServerResponse): void {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-store",
    Connection: "keep-alive",
  });
  res.write(`: hello ${Date.now()}\n\n`);
  clients.add(res);
  const beat = setInterval(() => {
    try {
      res.write(`: ping ${Date.now()}\n\n`);
    } catch {
      /* close below */
    }
  }, WAKE_BEAT_MS);
  beat.unref?.();
  const drop = (): void => {
    clearInterval(beat);
    clients.delete(res);
  };
  req.on("close", drop);
  res.on("error", drop);
}
