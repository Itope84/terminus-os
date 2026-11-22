/** Sync planning for non-opencode chats (pure, unit-tested).
 *
 *  A pending turn whose adapter has no record of it (the server restarted, the record was pruned) or
 *  says it already finished without leaving a result can never settle on its own: it spins, and Retry
 *  keeps answering "still running". After a grace period (a turn accepted moments ago may not have its
 *  adapter record yet) such turns are reported lost so the server can settle them with an honest error.
 *  A status that could not be read is never treated as lost. */
export const SYNC_GRACE_MS = 30_000;

export type SyncState = "accepted" | "working" | "waiting" | "completed" | "interrupted" | "failed" | "unknown";

export function lostTurns(
  pending: Array<{ turnId: string; at: number }>,
  states: Map<string, SyncState>,
  now: number,
  graceMs: number = SYNC_GRACE_MS,
): string[] {
  const out: string[] = [];
  for (const p of pending) {
    if (now - p.at <= graceMs) continue;
    const s = states.get(p.turnId);
    if (s === undefined) continue;
    if (s === "unknown" || s === "completed" || s === "failed" || s === "interrupted") out.push(p.turnId);
  }
  return out;
}
