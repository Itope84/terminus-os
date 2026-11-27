/** Serves the built web app (web/dist, an Expo web export) at /app. Public on purpose: the files
 *  carry no data, and every API call the app makes still needs the credential. Unknown paths under
 *  /app get index.html, so deep links (/app/chat/<id>) open the app. */
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { extname, join, normalize, sep } from "node:path";
import { repoRoot } from "../paths.js";

const TYPES: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".webmanifest": "application/manifest+json",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".ttf": "font/ttf",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".map": "application/json",
};

export const appDist = (): string => process.env.TERMINUS_WEB_DIST || join(repoRoot(), "web", "dist");

/** Pure: the file under dist for a request path, or null when it escapes dist. */
export function appFileFor(dist: string, reqPath: string): string | null {
  let rel = reqPath.replace(/^\/app\/?/, "");
  try { rel = decodeURIComponent(rel); } catch { return null; }
  if (rel.includes("\0")) return null;
  const full = normalize(join(dist, rel));
  if (full !== dist && !full.startsWith(dist + sep)) return null;
  return full;
}

/** Answers GET /app and /app/*. Resolves false when the request isn't for the app. */
export async function handleApp(req: IncomingMessage, res: ServerResponse, reqPath: string): Promise<boolean> {
  if (req.method !== "GET" && req.method !== "HEAD") return false;
  if (reqPath !== "/app" && !reqPath.startsWith("/app/")) return false;
  const dist = appDist();
  let file = appFileFor(dist, reqPath);
  if (!file) {
    res.writeHead(400).end();
    return true;
  }
  let st = await stat(file).catch(() => null);
  if (!st || st.isDirectory()) {
    // A route, not a file: the single-page app answers it.
    if (extname(file) && extname(file) !== ".html") {
      res.writeHead(404, { "Content-Type": "text/plain" }).end("not found");
      return true;
    }
    file = join(dist, "index.html");
    st = await stat(file).catch(() => null);
    if (!st) {
      res.writeHead(503, { "Content-Type": "text/plain" }).end("The web app isn't built yet: cd web && pnpm build");
      return true;
    }
  }
  const name = file.slice(dist.length + 1);
  // Hashed bundles never change; the page, the worker and the manifest must always be fresh.
  const immutable = /^_expo\/static\//.test(name) || /^assets\//.test(name);
  res.writeHead(200, {
    "Content-Type": TYPES[extname(file)] ?? "application/octet-stream",
    "Content-Length": st.size,
    "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
    "X-Content-Type-Options": "nosniff",
    ...(name === "sw.js" ? { "Service-Worker-Allowed": "/app/" } : {}),
  });
  if (req.method === "HEAD") { res.end(); return true; }
  createReadStream(file).pipe(res);
  return true;
}
