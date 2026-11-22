/** Map tiles for the chat's map cards, fetched from OpenStreetMap by the Mac and cached, so the phone's IP and
 *  viewing pattern never reach the tile server (only the Mac's, once per tile). OSM's tile policy asks for an
 *  identifying User-Agent, caching, and light use: a personal app viewing a few maps fits.
 *
 *  GET /terminus/_tiles/<z>/<x>/<y>.png  (master or link token, like the gallery's images)
 *  Cache: .run/map-tiles/<z>/<x>/<y>.png, kept 30 days, at most MAX_TILES files (oldest dropped). */
import { mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { ServerResponse } from "node:http";
import { runDir } from "./paths.js";

const UPSTREAM = "https://tile.openstreetmap.org";
const UA = "terminus-personal-map/1.0 (single-user, cached)";
const KEEP_MS = 30 * 24 * 3600_000;
const MAX_TILES = 4000;
const MAX_ZOOM = 19;

let cacheDir = join(runDir(), "map-tiles");
export function _setTileDir(d: string): void { cacheDir = d; }

/** Pure: parse and bound-check a tile path; null when it isn't a valid tile. */
export function tileOf(path: string): { z: number; x: number; y: number } | null {
  const m = /^\/terminus\/_tiles\/(\d{1,2})\/(\d{1,7})\/(\d{1,7})\.png$/.exec(path);
  if (!m) return null;
  const z = +m[1], x = +m[2], y = +m[3];
  const n = 2 ** z;
  if (z > MAX_ZOOM || x >= n || y >= n) return null;
  return { z, x, y };
}

let prunedAt = 0;
function pruneSoon(): void {
  const now = Date.now();
  if (now - prunedAt < 3600_000) return;
  prunedAt = now;
  try {
    const files: Array<{ p: string; t: number }> = [];
    for (const z of readdirSync(cacheDir)) for (const x of readdirSync(join(cacheDir, z))) for (const y of readdirSync(join(cacheDir, z, x))) {
      const p = join(cacheDir, z, x, y);
      files.push({ p, t: statSync(p).mtimeMs });
    }
    files.sort((a, b) => a.t - b.t);
    const over = Math.max(0, files.length - MAX_TILES);
    files.forEach((f, i) => { if (i < over || now - f.t > KEEP_MS) try { unlinkSync(f.p); } catch { /* gone */ } });
  } catch { /* no cache yet */ }
}

const inflight = new Map<string, Promise<Buffer | null>>();

async function fetchTile(z: number, x: number, y: number): Promise<Buffer | null> {
  const key = `${z}/${x}/${y}`;
  const have = inflight.get(key);
  if (have) return have;
  const p = (async () => {
    try {
      const r = await fetch(`${UPSTREAM}/${key}.png`, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(10_000) });
      if (!r.ok) return null;
      return Buffer.from(await r.arrayBuffer());
    } catch { return null; } finally { inflight.delete(key); }
  })();
  inflight.set(key, p);
  return p;
}

export async function handleTile(path: string, res: ServerResponse): Promise<void> {
  const t = tileOf(path);
  if (!t) { res.writeHead(404); res.end(); return; }
  const file = join(cacheDir, String(t.z), String(t.x), `${t.y}.png`);
  let body: Buffer | null = null;
  try {
    if (Date.now() - statSync(file).mtimeMs < KEEP_MS) body = readFileSync(file);
  } catch { /* not cached */ }
  if (!body) {
    body = await fetchTile(t.z, t.x, t.y);
    if (body) {
      try { mkdirSync(dirname(file), { recursive: true, mode: 0o700 }); writeFileSync(file, body); } catch { /* serve anyway */ }
      pruneSoon();
    }
  }
  if (!body) { res.writeHead(502); res.end(); return; }
  res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "private, max-age=604800" });
  res.end(body);
}
