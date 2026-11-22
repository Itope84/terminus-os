/** Single auth authority: access token, upload key, request check.
 *
 *  Previously server.ts and media.ts each loaded the bearer independently —
 *  server.ts honored HEADLESS_TOKEN while media.ts read only the file, so one
 *  env value could make /api/chat work while gallery upload rejected the same
 *  bearer. Both now use this module. Secrets never logged (see loadToken).
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { IncomingMessage } from "node:http";
import { chmodSync } from "node:fs";
import { linkTokenPathOk } from "./nav-guard.js";

function tokenFile(): string {
  return join(homedir(), ".config", "terminus", "browser", "token");
}

function loadAccessToken(): string {
  if (process.env.HEADLESS_TOKEN) return process.env.HEADLESS_TOKEN;
  try {
    const saved = readFileSync(tokenFile(), "utf8").trim();
    if (saved) return saved;
  } catch { /* first boot */ }
  const fresh = randomBytes(32).toString("base64url");
  try {
    mkdirSync(join(homedir(), ".config", "terminus", "browser"), { recursive: true });
    writeFileSync(tokenFile(), fresh + "\n", { mode: 0o600 });
  } catch { /* ephemeral fallback */ }
  return fresh;
}

export const ACCESS_TOKEN = loadAccessToken();

const UPLOAD_TOKEN_FILE = join(homedir(), ".config", "terminus", "browser", "upload-token");

function loadUploadToken(): string {
  try {
    const t = readFileSync(UPLOAD_TOKEN_FILE, "utf8").trim();
    if (t) { try { chmodSync(UPLOAD_TOKEN_FILE, 0o600); } catch { /* best effort */ } return t; }
  } catch { /* generate below */ }
  const t = "up-" + randomBytes(18).toString("base64url");
  try {
    mkdirSync(UPLOAD_TOKEN_FILE.slice(0, UPLOAD_TOKEN_FILE.lastIndexOf("/")), { recursive: true });
    writeFileSync(UPLOAD_TOKEN_FILE, t + "\n", { mode: 0o600 });
  } catch { /* ephemeral fallback */ }
  console.log(`upload-token generated — enter once in the app settings: ${UPLOAD_TOKEN_FILE}`);
  return t;
}

export const UPLOAD_TOKEN = loadUploadToken();

const PB_TOKEN_FILE = join(homedir(), ".config", "terminus", "browser", "pb-token");

/** Narrow token for agents: opens only POST /api/pb/cmd and GET /api/pb/status,
 *  and POST /api/approvals/request (ask the person), so what agents are handed
 *  is not the master token. Deciding an approval needs the master token. */
function loadPbToken(): string {
  try {
    const t = readFileSync(PB_TOKEN_FILE, "utf8").trim();
    if (t) return t;
  } catch { /* generate below */ }
  const t = randomBytes(24).toString("base64url");
  try {
    mkdirSync(PB_TOKEN_FILE.slice(0, PB_TOKEN_FILE.lastIndexOf("/")), { recursive: true });
    writeFileSync(PB_TOKEN_FILE, t + "\n", { mode: 0o600 });
  } catch { /* ephemeral fallback */ }
  return t;
}

export const PB_TOKEN = loadPbToken();

/** True only for the phone-browser agent routes with the narrow header bearer. */
export function pbScopedAuthorized(req: IncomingMessage): boolean {
  const h = req.headers.authorization || "";
  if (!h.startsWith("Bearer ")) return false;
  let path = "";
  try { path = new URL(req.url || "", "http://x").pathname; } catch { return false; }
  const ok = (path === "/api/pb/cmd" && req.method === "POST") || (path === "/api/pb/status" && req.method === "GET")
    || (path === "/api/approvals/request" && req.method === "POST");
  if (!ok) return false;
  const a = Buffer.from(h.slice(7)), b = Buffer.from(PB_TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Constant-time bearer comparison (length-checked first). */
export function bearerMatches(supplied: string): boolean {
  const a = Buffer.from(supplied), b = Buffer.from(ACCESS_TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Link tokens: what in-app WebViews put in URLs instead of the master token. Stateless
 *  (`l1.<expiry>.<sig>`), signed with a key derived from the master token, so changing the master
 *  invalidates every one. They open GET requests outside /api/ only (gallery, notes, media, share
 *  listings): a copy that leaks from a URL, log or history cannot call the API, send chat or upload. */
export const LINK_TOKEN_TTL_MS = 30 * 24 * 3600_000;
function linkSig(exp: string): string {
  const key = createHmac("sha256", ACCESS_TOKEN).update("terminus-link-token-v1").digest();
  return createHmac("sha256", key).update("l1." + exp).digest("base64url").slice(0, 32);
}
export function mintLinkToken(now = Date.now()): { token: string; expiresAt: number } {
  const expiresAt = now + LINK_TOKEN_TTL_MS;
  const exp = expiresAt.toString(36);
  return { token: `l1.${exp}.${linkSig(exp)}`, expiresAt };
}
export function linkTokenValid(supplied: string, now = Date.now()): boolean {
  const m = /^l1\.([0-9a-z]{1,12})\.([A-Za-z0-9_-]{32})$/.exec(supplied);
  if (!m) return false;
  const exp = parseInt(m[1], 36);
  if (!Number.isFinite(exp) || exp < now) return false;
  const a = Buffer.from(m[2]), b = Buffer.from(linkSig(m[1]));
  return a.length === b.length && timingSafeEqual(a, b);
}
/** For the places that read a token out of a URL: master or link token. */
export function queryTokenOk(supplied: string): boolean {
  return bearerMatches(supplied) || linkTokenValid(supplied);
}

/** Constant-time compare for the upload key. */
export function uploadKeyMatches(supplied: string): boolean {
  const a = Buffer.from(supplied), b = Buffer.from(UPLOAD_TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}

function isApiPath(url: string): boolean {
  try {
    return new URL(url, "http://x").pathname.startsWith("/api/");
  } catch {
    return false;
  }
}

/** Header bearer → guarded cookie → query token. Query tokens exist for GET
 *  gallery/media links that browsers fetch without headers — /api/* never
 *  accepts them (automation uses header/cookie only, so secrets stay out of
 *  URLs, logs, and history). A malformed cookie fails closed, never throws. */
export function authorized(req: IncomingMessage): boolean {
  let supplied = (req.headers.authorization || "").startsWith("Bearer ") ? req.headers.authorization!.slice(7) : "";
  if (!supplied) {
    try {
      const m = (req.headers.cookie || "").match(/(?:^|;\s*)headless-token=([^;]*)/);
      if (m) supplied = decodeURIComponent(m[1]);
    } catch {
      supplied = "";
    }
  }
  if (!supplied && req.url && req.method === "GET" && !isApiPath(req.url)) {
    try {
      supplied = new URL(req.url, "http://x").searchParams.get("token") || "";
    } catch { /* ignore */ }
  }
  if (bearerMatches(supplied)) return true;
  // A link token (see above) works for plain GETs outside /api/, whichever way it arrives
  // (query, or the bearer header a page script sends back, such as the notes profile fetch).
  return !!supplied && req.method === "GET" && !!req.url && !isApiPath(req.url) && linkTokenPathOk(req.url) && linkTokenValid(supplied);
}
