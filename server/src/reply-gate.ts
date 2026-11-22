/** Reply-settle gate for the opencode wait loop (pure, unit-tested).
 *
 *  A listed assistant message is never enough on its own — not even a finished one. Opencode
 *  finishes preamble messages (finish "stop") while the run continues with tool calls, so the
 *  old rule "finished message settles at once" showed a short preamble as the whole reply while
 *  the real answer landed later in the backend session and never reached the app.
 *
 *  A picked reply settles only when the run is really over:
 *   - the wait returned, and
 *   - opencode's own session.active says the session is idle: a finished message settles at
 *     once, an unfinished one after the list holds still for one more round (it may still be
 *     streaming), or, when that reading is unavailable, the list has been frozen for five rounds
 *     past the first minute (same bar the empty-reply exit uses).
 *  A session still reported active never settles, finished message or not. */
export function replySettleOk(s: {
  waitReturned: boolean;
  /** session.active reading: true working, false idle, null unknown. */
  working: boolean | null;
  stableRounds: number;
  elapsedMs: number;
  /** The picked message itself finished (finish "stop", or completed after the prompt). */
  terminal: boolean;
}): boolean {
  if (!s.waitReturned) return false;
  if (s.working === true) return false;
  if (s.working === false) return s.terminal ? true : s.stableRounds >= 1;
  return s.stableRounds >= 5 && s.elapsedMs > 60_000;
}
