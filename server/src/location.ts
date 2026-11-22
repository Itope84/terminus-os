/** The phone's location, on request. GET /api/location asks the phone over its live link (/api/wake) for a
 *  fresh fix and waits for POST /api/location/reply. When the phone can't answer in time it falls back to the
 *  last fix it sent, with its age. The last fix lives in memory only: nothing about where the phone has been is
 *  written to disk or logged.
 *
 *  GET /api/location?wait=<s, default 20, max 60>&maxAge=<s>
 *    200 {lat, lng, accuracy, at, ageSec, fresh:true}            the phone answered
 *    200 {lat, lng, accuracy, at, ageSec, fresh:false, note}     no answer in time: last known
 *    409 {error:"permission_needed", note}                       location not allowed yet; the phone asked
 *    503 {error:"phone_unreachable"}                             no live link and nothing known
 *  maxAge: a last fix younger than this is returned at once, without asking the phone. */
import { randomBytes } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Json } from "./util.js";
import { wakeClientCount, wakeLocation } from "./wake.js";

export type Fix = { lat: number; lng: number; accuracy: number | null; at: number };
type Reply = Fix | { error: string };

let last: Fix | null = null;
const waiting = new Map<string, (r: Reply) => void>();

/** Pure: validate a phone reply body. */
export function parseLocationReply(v: Record<string, unknown>): { id: string; reply: Reply } | null {
  const id = typeof v.id === "string" ? v.id : "";
  if (!/^[a-f0-9]{8,32}$/.test(id)) return null;
  if (typeof v.error === "string" && v.error) return { id, reply: { error: v.error.slice(0, 60) } };
  const lat = Number(v.lat), lng = Number(v.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  const acc = Number(v.accuracy);
  const at = Number(v.at);
  return { id, reply: { lat, lng, accuracy: Number.isFinite(acc) && acc >= 0 ? Math.round(acc) : null, at: Number.isFinite(at) && at > 0 ? at : Date.now() } };
}

function view(f: Fix, fresh: boolean, now = Date.now()) {
  return { lat: f.lat, lng: f.lng, accuracy: f.accuracy, at: f.at, ageSec: Math.max(0, Math.round((now - f.at) / 1000)), fresh };
}

export async function handleLocationGet(req: IncomingMessage, res: ServerResponse, json: Json): Promise<void> {
  const q = new URL(req.url || "", "http://x").searchParams;
  const waitSec = Math.min(60, Math.max(3, Number(q.get("wait")) || 20));
  const maxAge = Number(q.get("maxAge"));
  if (last && Number.isFinite(maxAge) && maxAge > 0 && Date.now() - last.at <= maxAge * 1000) return json(res, 200, view(last, false));
  if (!wakeClientCount()) {
    if (last) return json(res, 200, { ...view(last, false), note: "phone not connected; last known position" });
    return json(res, 503, { error: "phone_unreachable" });
  }
  const id = randomBytes(8).toString("hex");
  const reply = await new Promise<Reply | null>((resolve) => {
    const t = setTimeout(() => { waiting.delete(id); resolve(null); }, waitSec * 1000);
    t.unref?.();
    waiting.set(id, (r) => { clearTimeout(t); waiting.delete(id); resolve(r); });
    wakeLocation(id);
  });
  if (reply && "error" in reply) {
    if (reply.error === "permission_needed") {
      return json(res, 409, { error: "permission_needed", note: "the phone is asking the owner to allow location; try again after they do" });
    }
    if (last) return json(res, 200, { ...view(last, false), note: `phone could not get a fix (${reply.error}); last known position` });
    return json(res, 503, { error: reply.error });
  }
  if (reply) {
    last = reply;
    return json(res, 200, view(reply, true));
  }
  if (last) return json(res, 200, { ...view(last, false), note: "phone did not answer in time; last known position" });
  return json(res, 503, { error: "phone_unreachable" });
}

export function handleLocationReply(res: ServerResponse, json: Json, body: Record<string, unknown>): void {
  const p = parseLocationReply(body);
  if (!p) return json(res, 400, { error: "bad reply" });
  if (!("error" in p.reply)) last = p.reply;
  waiting.get(p.id)?.(p.reply);
  json(res, 200, { ok: true });
}

/** Tests. */
export function _resetLocation(): void {
  last = null;
  waiting.clear();
}
