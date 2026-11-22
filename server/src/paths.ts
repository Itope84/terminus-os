import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** Repo root: TERMINUS_DIR env wins, else the checkout this server runs
 *  from (server/dist or server/src → two levels up). No machine paths. */
export function repoRoot(): string {
  const env = (process.env.TERMINUS_DIR || "").trim();
  if (env) return resolve(env);
  return resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
}

export function runDir(): string {
  return join(repoRoot(), ".run");
}
