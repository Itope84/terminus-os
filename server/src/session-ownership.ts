import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";

/** Session ids Terminus started, resumed or forked, per backend. The import list shows only these, so a user's
 *  own CLI sessions and scheduled runs stay out. One file per backend beside the Claude titles file. */
export function ownedFile(backend: string, home: string = homedir()): string {
  // node --test children must never write the real owned lists (fake ids from tests ended up in them).
  if (process.env.NODE_TEST_CONTEXT && home === homedir()) home = join(tmpdir(), "terminus-test-home");
  return join(home, ".config", "terminus", `${backend}-owned.json`);
}

const OWNED_CAP = 2000;

export function readOwned(backend: string, file: string = ownedFile(backend)): Set<string> {
  try {
    const o = JSON.parse(readFileSync(file, "utf8")) as unknown;
    if (!Array.isArray(o)) return new Set();
    return new Set(o.filter((v): v is string => typeof v === "string" && v !== ""));
  } catch { return new Set(); }
}

export function markOwned(backend: string, id: string, file: string = ownedFile(backend)): void {
  if (!id) return;
  const ids = readOwned(backend, file);
  if (ids.has(id)) return;
  ids.add(id);
  const all = [...ids];
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  writeFileSync(tmp, JSON.stringify(all.slice(Math.max(0, all.length - OWNED_CAP)))); // Set keeps insertion order: oldest drop first
  renameSync(tmp, file);
}
