/** Phone dictation through saywhat on this Mac (local speech-to-text, streaming partials).
 *
 *  The phone posts its microphone as raw 16 kHz mono s16le PCM every ~250 ms; each response carries the
 *  text saywhat produced since the last one. No long-lived connection, so a flaky link only delays text.
 *
 *    POST /api/listen?id=<session>       body: PCM bytes (may be empty)  -> { events: [...] }
 *    POST /api/listen/stop?id=<session>  closes the input, waits for the last utterance -> { events, done }
 *
 *  503 means saywhat or its model is not installed here: the phone falls back to its own recogniser.
 *  A session nobody posts to for IDLE_MS is killed. */
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import { homedir } from "node:os";
import { join } from "node:path";

type Json = (res: ServerResponse, status: number, body: unknown) => void;
export type ListenEvent = { type: "partial" | "final"; t: number; dur?: number; text: string };

type Session = {
  proc: ChildProcessWithoutNullStreams;
  events: ListenEvent[];
  buf: string;
  exited: Promise<void>;
  lastSeen: number;
  error: string;
};

const sessions = new Map<string, Session>();
const IDLE_MS = 30_000;
const MAX_BODY = 512 * 1024; // ~16 s of audio per post: far more than one 250 ms chunk
const ID = /^[A-Za-z0-9_-]{6,64}$/;

export function saywhatBin(): string | null {
  const cands = [process.env.SAYWHAT_BIN, join(homedir(), ".local/bin/saywhat"), "/usr/local/bin/saywhat", "/opt/homebrew/bin/saywhat"];
  return cands.find((p): p is string => !!p && existsSync(p)) ?? null;
}

function hasModel(): boolean {
  const dir = process.env.SAYWHAT_MODELS || join(homedir(), ".local/share/saywhat/models");
  try { return readdirSync(dir).some((f) => f.endsWith(".gguf")); } catch { return false; }
}

/** Pure: complete JSON lines out of a stdout buffer; the unfinished tail stays in `rest`. */
export function takeLines(buf: string): { events: ListenEvent[]; rest: string } {
  const parts = buf.split("\n");
  const rest = parts.pop() ?? "";
  const events: ListenEvent[] = [];
  for (const line of parts) {
    try {
      const o = JSON.parse(line) as ListenEvent;
      if ((o.type === "partial" || o.type === "final") && typeof o.text === "string") events.push(o);
    } catch { /* not ours */ }
  }
  return { events, rest };
}

function start(bin: string): Session {
  const proc = spawn(bin, ["--stdin", "--json"], { stdio: ["pipe", "pipe", "pipe"] });
  const s: Session = { proc, events: [], buf: "", exited: Promise.resolve(), lastSeen: Date.now(), error: "" };
  s.exited = new Promise((resolve) => proc.on("close", () => resolve()));
  proc.stdout.setEncoding("utf8");
  proc.stdout.on("data", (d: string) => {
    const r = takeLines(s.buf + d);
    s.buf = r.rest;
    s.events.push(...r.events);
  });
  proc.stderr.setEncoding("utf8");
  proc.stderr.on("data", (d: string) => { if (/error/i.test(d)) s.error = d.trim().slice(0, 300); });
  proc.stdin.on("error", () => { /* saywhat gone: the next post reports it */ });
  return s;
}

function readRaw(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let n = 0;
    req.on("data", (c: Buffer) => {
      n += c.length;
      if (n > MAX_BODY) { reject(new Error("too large")); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function drain(s: Session): ListenEvent[] {
  return s.events.splice(0, s.events.length);
}

export async function handleListen(req: IncomingMessage, res: ServerResponse, json: Json, stop = false): Promise<void> {
  const id = new URL(req.url || "", "http://x").searchParams.get("id") || "";
  if (!ID.test(id)) return json(res, 400, { error: "bad id" });
  let audio: Buffer;
  try { audio = await readRaw(req); } catch { return json(res, 413, { error: "body too large" }); }

  let s = sessions.get(id);
  if (!s) {
    if (stop) return json(res, 200, { events: [], done: true });
    const bin = saywhatBin();
    if (!bin || !hasModel()) return json(res, 503, { error: "saywhat not installed on the Mac" });
    s = start(bin);
    sessions.set(id, s);
  }
  s.lastSeen = Date.now();
  if (s.proc.exitCode !== null) {
    sessions.delete(id);
    return json(res, 502, { error: s.error || "saywhat exited", events: drain(s) });
  }
  if (audio.length) s.proc.stdin.write(audio);
  if (!stop) return json(res, 200, { events: drain(s) });

  // Stop: end the input; saywhat decodes the utterance in progress and exits.
  s.proc.stdin.end();
  await Promise.race([s.exited, new Promise((r) => setTimeout(r, 15_000))]);
  if (s.proc.exitCode === null) s.proc.kill("SIGKILL");
  sessions.delete(id);
  return json(res, 200, { events: drain(s), done: true });
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) {
    if (now - s.lastSeen > IDLE_MS) {
      // Abandoned (phone went away mid-dictation): close its input, then make sure it is gone.
      sessions.delete(id);
      const p = s.proc;
      try { p.stdin.end(); p.kill("SIGTERM"); } catch { /* gone */ }
      setTimeout(() => { if (p.exitCode === null && p.signalCode === null) p.kill("SIGKILL"); }, 5_000).unref();
    }
  }
}, 10_000).unref();

/** Live saywhat processes (tests, /api/busy style checks). */
export function listenSessions(): number {
  return sessions.size;
}
