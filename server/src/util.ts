/** Small helpers shared across the server (kept dependency-free). */
import type { IncomingMessage, ServerResponse } from "node:http";
import { rename, rm, unlink, writeFile } from "node:fs/promises";

/** The JSON responder every route handler receives. */
export type Json = (res: ServerResponse, status: number, body: unknown) => void;

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

/** Read a request body as UTF-8 text, capped at maxBytes: rejects with
 *  "body too large" (and destroys the request) once the cap is crossed. */
export function readBodyText(req: IncomingMessage, maxBytes = 200_000): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > maxBytes) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

/** Write via a uniquely named temp file and rename, so a crash mid-write never leaves a
 *  truncated file at the final name and concurrent writers never share a temp file. If the
 *  rename is refused, the target is removed and the rename retried once. */
export async function atomicWriteFile(path: string, data: string | Buffer, mode?: number): Promise<void> {
  const tmp = `${path}.part-${process.pid}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  try {
    await writeFile(tmp, data, mode === undefined ? undefined : { mode });
    try {
      await rename(tmp, path);
    } catch {
      try { await unlink(path); } catch { /* ignore */ }
      await rename(tmp, path);
    }
  } catch (e) {
    await rm(tmp, { force: true }).catch(() => {});
    throw e;
  }
}
