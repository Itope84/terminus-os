/** Process-lifecycle rules (pure, unit-tested): what to end, and when.
 *
 *  Three kinds of long-lived thing can outlive their usefulness: a hung provider process, an unused
 *  browser profile, and a turn whose process is about to die with the server. Each rule here is the
 *  decision only; the callers do the killing, so the rules stay testable. */

/** Hung-process stall: no output at all for `ms` (0 disables). A turn can be quiet for a while (a long
 *  build, a slow model), so the default is an hour; anything past it is a hung process, not work. */
export const STALL_MS = Number(process.env.TERMINUS_STALL_MS ?? 60 * 60_000);

export function isStalled(now: number, lastAt: number, ms: number = STALL_MS): boolean {
  return ms > 0 && now - lastAt >= ms;
}

/** Calls `onStall` once when `lastAt()` goes `ms` stale; returns a stop function. The timer never keeps
 *  the process alive. */
export function watchStall(lastAt: () => number, onStall: () => void, ms: number = STALL_MS): () => void {
  if (!(ms > 0)) return () => {};
  const t = setInterval(() => {
    if (isStalled(Date.now(), lastAt(), ms)) {
      clearInterval(t);
      try { onStall(); } catch { /* the watch must never throw */ }
    }
  }, Math.min(60_000, Math.max(1000, ms / 4)));
  t.unref?.();
  return () => clearInterval(t);
}

export interface ProfileUse { headed: boolean; usedAt: number; activeOperations: number }

/** Browser profiles nobody has used for a while. Headless ones go after `idleMs`; headed ones get longer
 *  (`headedIdleMs`) since a visible window may be watched. Active browser operations veto cleanup, while
 *  open pages alone do not. Logins live on disk: closing only frees the browser process, and the next use
 *  relaunches it. */
export function idleProfileKeys(entries: Array<[string, ProfileUse]>, now: number, idleMs: number, headedIdleMs: number = idleMs): string[] {
  if (!(idleMs > 0)) return [];
  const headMs = headedIdleMs > 0 ? headedIdleMs : idleMs;
  return entries.filter(([, p]) => p.activeOperations === 0 && now - p.usedAt >= (p.headed ? headMs : idleMs)).map(([k]) => k);
}

/** Turns that die with the server. Opencode runs outside this process and keeps going, so its turns are
 *  left for the sync to recover; every other backend's turn is a child of this process and is lost. */
export function shutdownTargets(
  pending: Iterable<[string, Array<{ turnId: string }>]>,
  backendOf: (chatId: string) => string | null,
  alreadySettled: (chatId: string, turnId: string) => boolean,
): Array<{ chatId: string; turnId: string }> {
  const out: Array<{ chatId: string; turnId: string }> = [];
  for (const [chatId, list] of pending) {
    if ((backendOf(chatId) ?? "opencode") === "opencode") continue;
    for (const p of list) if (!alreadySettled(chatId, p.turnId)) out.push({ chatId, turnId: p.turnId });
  }
  return out;
}
