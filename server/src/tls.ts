/** HTTPS listener for the home-screen web app. iOS only allows a service worker, Web Push and the
 *  microphone on HTTPS, so the server serves it itself with the Mac's Tailscale certificate
 *  (`tailscale cert`), the same files iomoney uses.
 *
 *    TERMINUS_TLS_CERT, TERMINUS_TLS_KEY   PEM file paths (both needed; unset = no HTTPS)
 *    TERMINUS_TLS_PORT                     default 9443
 *    TERMINUS_TLS_HOST                     default 0.0.0.0 (home Wi-Fi and Tailscale)
 *
 *  The files are checked hourly and reloaded without a restart when they change (renewals). A
 *  listener that can't start logs why; the plain-HTTP server keeps running. */
import { readFileSync, statSync } from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import { createServer } from "node:https";

type Handler = (req: IncomingMessage, res: ServerResponse) => unknown;

export function tlsConfig(env: NodeJS.ProcessEnv = process.env): { cert: string; key: string; port: number; host: string } | null {
  const cert = (env.TERMINUS_TLS_CERT || "").trim();
  const key = (env.TERMINUS_TLS_KEY || "").trim();
  if (!cert || !key) return null;
  const port = Number(env.TERMINUS_TLS_PORT || 9443);
  return { cert, key, port: Number.isFinite(port) && port > 0 ? port : 9443, host: (env.TERMINUS_TLS_HOST || "0.0.0.0").trim() };
}

export function startTls(handler: Handler): void {
  const cfg = tlsConfig();
  if (!cfg) return;
  let stamp = "";
  const read = () => {
    const s = `${statSync(cfg.cert).mtimeMs}:${statSync(cfg.key).mtimeMs}`;
    return { stamp: s, cert: readFileSync(cfg.cert), key: readFileSync(cfg.key) };
  };
  let first;
  try {
    first = read();
  } catch (e) {
    console.log(`https: not started, can't read the certificate (${String((e as Error).message).slice(0, 160)})`);
    return;
  }
  stamp = first.stamp;
  const srv = createServer({ cert: first.cert, key: first.key }, (req, res) => void handler(req, res));
  srv.on("error", (e: NodeJS.ErrnoException) => {
    console.log(`https: listener on ${cfg.host}:${cfg.port} failed: ${e.code ?? e.message}`);
  });
  srv.listen(cfg.port, cfg.host, () => console.log(`https listening on ${cfg.host}:${cfg.port} (web app at /app/)`));
  const timer = setInterval(() => {
    try {
      const next = read();
      if (next.stamp === stamp) return;
      srv.setSecureContext({ cert: next.cert, key: next.key });
      stamp = next.stamp;
      console.log("https: certificate reloaded");
    } catch { /* keep the current one */ }
  }, 3600_000);
  timer.unref();
}
