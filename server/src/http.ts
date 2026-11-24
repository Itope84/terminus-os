import type { IncomingMessage, ServerResponse } from "node:http";
import { authorized, pbScopedAuthorized } from "./auth.js";

export function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "Content-Type": "application/json", "Access-Control-Allow-Origin": process.env.HEADLESS_ORIGIN || "null" });
  res.end(JSON.stringify(body));
}

export function requireAuth(req: IncomingMessage, res: ServerResponse): boolean {
  if (authorized(req) || pbScopedAuthorized(req)) return true;
  json(res, 401, { error: "unauthorized" });
  return false;
}

/** Capped JSON body reader for automation endpoints (DoS guard): these
 *  payloads are small control messages, never uploads. */
export async function readJsonBody(req: IncomingMessage, max = 200_000): Promise<{ ok: true; value: Record<string, unknown> } | { ok: false; status: number; error: string }> {
  let size = 0;
  const chunks: Buffer[] = [];
  try {
    for await (const c of req) {
      const b = Buffer.isBuffer(c) ? c : Buffer.from(c as Uint8Array);
      size += b.length;
      if (size > max) return { ok: false, status: 413, error: "body too large" };
      chunks.push(b);
    }
  } catch {
    return { ok: false, status: 400, error: "unreadable body" };
  }
  try {
    const v: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
    if (!v || typeof v !== "object" || Array.isArray(v)) return { ok: false, status: 400, error: "invalid json" };
    return { ok: true, value: v as Record<string, unknown> };
  } catch {
    return { ok: false, status: 400, error: "invalid json" };
  }
}
