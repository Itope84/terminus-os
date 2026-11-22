/** Whole-tree process termination (no new deps).
 *
 *  Signalling only the direct child orphans tool subprocesses (a shell
 *  tool's grandchildren survive the parent's death and keep running —
 *  spending quota, holding locks). So: collect the tree via pgrep, signal
 *  leaves-first, escalate SIGTERM → SIGKILL on a deadline.
 *
 *  Secrets note: pids and signals only — never command lines, env, or
 *  output. Nothing here touches payloads.
 */
import { execFile } from "node:child_process";
import { sleep } from "./util.js";

export interface TreeKillResult {
  /** Pids signalled (direct child first, then descendants). */
  signalled: number[];
  /** True when every known pid was gone after the force pass. */
  allGone: boolean;
}

function execFileAsync(
  file: string,
  args: string[],
  timeoutMs: number,
): Promise<string> {
  return new Promise((resolve) => {
    execFile(file, args, { timeout: timeoutMs }, (err, stdout) => {
      resolve(err ? "" : String(stdout ?? ""));
    });
  });
}

/** Direct children of a pid (empty when none, or when pgrep is missing). */
export async function childPids(pid: number): Promise<number[]> {
  if (!Number.isInteger(pid) || pid <= 0) return [];
  const out = await execFileAsync("/usr/bin/pgrep", ["-P", String(pid)], 3000);
  return out
    .split(/[\s,]+/)
    .map((s) => parseInt(s, 10))
    .filter((n) => Number.isInteger(n) && n > 0 && n !== pid);
}

/** Full descendant tree, leaves-first (children before parents so a
 *  dying parent can't orphan a not-yet-signalled child). Cycle-safe. */
export async function treePids(rootPid: number): Promise<number[]> {
  const seen = new Set<number>([rootPid]);
  const ordered: number[] = [];
  const walk = async (pid: number): Promise<void> => {
    for (const child of await childPids(pid)) {
      if (seen.has(child)) continue;
      seen.add(child);
      await walk(child);
      ordered.push(child);
    }
  };
  await walk(rootPid);
  return ordered;
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * Terminate a process tree: SIGTERM to child + descendants, wait
 * `gracefulMs`, SIGKILL to survivors, wait `forceMs`, report.
 * Never throws (cleanup paths must not fail the caller).
 */
export async function terminateTree(
  pid: number | undefined,
  opts: { gracefulMs?: number; forceMs?: number } = {},
): Promise<TreeKillResult> {
  const result: TreeKillResult = { signalled: [], allGone: true };
  if (!Number.isInteger(pid) || (pid as number) <= 0) return result;
  const root = pid as number;
  try {
    const tree = await treePids(root);
    const targets = [root, ...tree];
    for (const p of targets) {
      try {
        process.kill(p, "SIGTERM");
        result.signalled.push(p);
      } catch { /* already gone */ }
    }
    await sleep(opts.gracefulMs ?? 2000);
    const survivors = targets.filter(alive);
    for (const p of survivors) {
      try {
        process.kill(p, "SIGKILL");
      } catch { /* already gone */ }
    }
    await sleep(opts.forceMs ?? 1000);
    result.allGone = targets.every((p) => !alive(p));
    return result;
  } catch {
    return result;
  }
}
