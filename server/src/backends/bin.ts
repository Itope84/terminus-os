/** Locate a backend CLI binary (shared by every backend adapter). */
import { existsSync } from "node:fs";
import { join } from "node:path";

/** Well-known macOS install dirs, searched after PATH: launchd/supervised
 *  environments often carry an impoverished PATH without /usr/local/bin,
 *  which must not read as "backend missing". Exported for tests. */
export const FALLBACK_BIN_DIRS = ["/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin"];

/** PATH first (server-owned; never client-controlled), then the well-known dirs, then any
 *  backend-specific extraDirs. Only the given binary name is searched. */
export function findBin(name: string, extraDirs: string[] = []): string | null {
  const seen = new Set<string>();
  const dirs = [...(process.env.PATH || "").split(":").filter((d) => d !== ""), ...FALLBACK_BIN_DIRS, ...extraDirs];
  for (const dir of dirs) {
    if (seen.has(dir)) continue;
    seen.add(dir);
    try {
      const p = join(dir, name);
      if (existsSync(p)) return p;
    } catch { /* next */ }
  }
  return null;
}
