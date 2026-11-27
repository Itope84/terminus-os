/** Web Push for the home-screen app: the phone gets a notification with the app closed, through
 *  Apple's (or the browser's) push service. Replaces the Android app's always-on /api/wake link.
 *
 *  VAPID keys live in ~/.config/terminus/secrets/vapid.json (0600), made once. Subscriptions live in
 *  .run/pwa/push.json. A subscription the push service says is gone (404/410) is dropped. */
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import webpush from "web-push";
import { pwaDir } from "./store.js";

export interface PushPayload {
  title: string;
  body: string;
  /** Path inside the app to open on tap, e.g. /app/chat/s-123. */
  url?: string;
  /** Same tag replaces an earlier notification (one per chat). */
  tag?: string;
}

interface Sub { endpoint: string; keys: { p256dh: string; auth: string }; addedAt: number; label?: string }

export interface SendResult { devices: number; sent: number; errors: string[] }

/** Apple refuses a localhost contact (BadJwtToken), so the default is a neutral real-looking address. */
const CONTACT = process.env.TERMINUS_PUSH_CONTACT || "mailto:push@example.com";

function keyFile(): string {
  return join(homedir(), ".config", "terminus", "secrets", "vapid.json");
}

function loadKeys(file: string): { publicKey: string; privateKey: string } {
  try {
    const k = JSON.parse(readFileSync(file, "utf8")) as { publicKey?: string; privateKey?: string };
    if (k.publicKey && k.privateKey) return { publicKey: k.publicKey, privateKey: k.privateKey };
  } catch { /* first run */ }
  const fresh = webpush.generateVAPIDKeys();
  mkdirSync(join(file, ".."), { recursive: true, mode: 0o700 });
  writeFileSync(file, JSON.stringify(fresh), { mode: 0o600 });
  return fresh;
}

type Sender = (sub: Sub, payload: string) => Promise<unknown>;

export class Push {
  readonly publicKey: string;
  private subs: Sub[] = [];
  private readonly send: Sender;
  constructor(private readonly subsFile: string, keysFile = keyFile(), sender?: Sender) {
    const keys = loadKeys(keysFile);
    this.publicKey = keys.publicKey;
    webpush.setVapidDetails(CONTACT, keys.publicKey, keys.privateKey);
    this.send = sender ?? ((sub, payload) => webpush.sendNotification(sub, payload, { TTL: 3600 }));
    try {
      const raw = JSON.parse(readFileSync(subsFile, "utf8")) as { subs?: Sub[] };
      this.subs = (raw.subs ?? []).filter((s) => s && typeof s.endpoint === "string" && s.keys?.p256dh && s.keys?.auth);
    } catch { /* none yet */ }
  }

  count(): number {
    return this.subs.length;
  }

  subscribe(input: unknown, label?: string): boolean {
    const s = input as Partial<Sub> | null;
    if (!s || typeof s.endpoint !== "string" || !/^https:\/\//.test(s.endpoint)) return false;
    if (typeof s.keys?.p256dh !== "string" || typeof s.keys?.auth !== "string") return false;
    this.subs = this.subs.filter((x) => x.endpoint !== s.endpoint);
    this.subs.push({ endpoint: s.endpoint, keys: { p256dh: s.keys.p256dh, auth: s.keys.auth }, addedAt: Date.now(), ...(label ? { label: label.slice(0, 60) } : {}) });
    this.save();
    return true;
  }

  unsubscribe(endpoint: string): boolean {
    const before = this.subs.length;
    this.subs = this.subs.filter((x) => x.endpoint !== endpoint);
    if (this.subs.length !== before) this.save();
    return this.subs.length !== before;
  }

  async notify(p: PushPayload): Promise<SendResult> {
    const r: SendResult = { devices: this.subs.length, sent: 0, errors: [] };
    const body = JSON.stringify({ title: p.title.slice(0, 120), body: p.body.slice(0, 300), url: p.url ?? "/app/", tag: p.tag });
    for (const s of [...this.subs]) {
      const host = (() => { try { return new URL(s.endpoint).host; } catch { return "?"; } })();
      try {
        await this.send(s, body);
        r.sent++;
      } catch (err) {
        const e = err as { statusCode?: number; body?: string; message?: string };
        if (e.statusCode === 404 || e.statusCode === 410) {
          this.unsubscribe(s.endpoint);
          r.errors.push(`${host}: subscription expired (removed); turn notifications on again`);
        } else {
          const why = `${host}: ${e.statusCode ?? ""} ${(e.body ?? "").trim() || e.message || "failed"}`.trim();
          console.log(`pwa push: ${why.slice(0, 200)}`);
          r.errors.push(why);
        }
      }
    }
    return r;
  }

  private save(): void {
    try {
      mkdirSync(join(this.subsFile, ".."), { recursive: true, mode: 0o700 });
      const tmp = `${this.subsFile}.tmp`;
      writeFileSync(tmp, JSON.stringify({ subs: this.subs }), { mode: 0o600 });
      renameSync(tmp, this.subsFile);
    } catch (e) {
      console.log(`pwa push: save failed ${String((e as Error).message).slice(0, 120)}`);
    }
  }
}

let shared: Push | null = null;
export function push(): Push {
  if (!shared) shared = new Push(join(pwaDir(), "push.json"));
  return shared;
}
