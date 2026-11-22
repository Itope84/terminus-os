# Chat mirror — stream/display verifier

> Last verified: 2026-09-30 — server unit 93/93, android unit 39/39, mirror
> P1–P4 ok, edge P5–P9 ok (live). If the mirror page's build stamp differs
> from HEAD, rebuild + restart + re-run before trusting it.
>
> Verification tiers (never conflate): unit = pure logic in CI; live = real
> model turns via mirror/edge scripts; restart = process-death recovery;
> device = on-hardware UI proof. A tier is green only when its own runs pass —
> unit green never implies device green. Per-tier status lives in logbook.md
> hints; this file's contract section states expected behavior, not proof.

The app is hard to inspect on-device. Verify every chat/stream/display issue
here first, not by guessing from screenshots.

## The mirror

- Page: `GET /debug/chat` (static shell, `Accept: text/html`). The shell
  itself is unauthenticated and carries no data; every API call the page
  makes still requires the bearer token, which lives only in page memory
  (password field, forget button, never stored).
- It replays the app's exact stream handling (`MainActivity.kt` stream
  section): pending turns, per-turn live placeholders, delivered-dedupe,
  duplicate-done drop, merged turns settling as pointer rows, `idle`
  clearing stop, and the stop-visible rule
  (fresh live placeholder under 90s, cleared by server idle).
- Rendered-reply panel mirrors display: live text paints per turn as deltas
  land, `done` settles canonically by turnId; steps panel mirrors the
  thinking/tool rows including reasoning growth.
- Scenario controls: connect/disconnect (reconnect replay), send probe,
  back-to-back sends (overlapping turns), resend same turnId (send-once
  check), drop-next-done (LOCAL render-path simulation only — it drops the
  event in the page, which is not the same as losing the network: for a
  true disconnect/reconnect replaying the server's retained result, close
  the stream mid-turn and reconnect), stop turn (clears local live state
  like the app), poll progress (diagnostic only — the app
  settles exclusively from `done`s).

## Manual end-to-end check

- `server/test/chat-mirror-verify.mjs` (NOT `*.test.mjs`, never auto-runs),
  phases on throwaway chats, sessions deleted afterwards: P1 first-turn
  streaming (stream opens BEFORE the first POST with a blank session id,
  exactly like Android — asserts intermediate delta/step events arrive
  before `done`, reasoning optional); P2 double-send race (two concurrent
  POSTs on a fresh chat must return the SAME sessionID; both dones must
  arrive); P3 lost-completion recovery (POST with no stream open, poll
  `GET /api/turn` until settled, reconnect and assert the original turnId
  (+messageID) replays exactly once on that connection). Device-test
  responsibilities (not covered by the mirror): cross-chat stream
  ownership, real WebView scroll behavior, process-death rehydrate.
- Run: `TERMINUS_BEARER=$(cat ~/.config/terminus/browser/token) node test/chat-mirror-verify.mjs`
  from `server/`. It prints one JSON line plus `mirror verify: ok`.

## Verified lifecycle facts (do not re-derive)

- `done` is per-turn completion, NOT stream close. The stream stays open on
  heartbeats past `done`; `end` fires only at the 30-min cap.
- `step ended` closes one tool step only, never the reply.
- `idle` fires on opencode `session.idle` (may lag `done` by a lot or not
  arrive in short windows — observed absent 80s+ after a settled done).
- Every new stream connection replays settled dones once per connection;
  the client dedupes by turnId/messageID (send-once equivalent).
- Overlapping back-to-back turns each emit their own `done`, but each
  turn's text follows the latest assistant message: on 2026-09-25 a
  DOUBLE-A/DOUBLE-B pair settled BOTH turns with "DOUBLE-B". Triple-send
  the same day: 3 distinct turnIds, 3 dones, zero duplicates, arrival in
  acceptance order sharing one messageID — all three settled with the
  newest text ("TRIPLE-C"). Merge rule (reactive, uses the server's own
  message identity, no new protocol): one opencode reply renders exactly
  one row. A turn whose `done` carries an already-settled messageID drops
  its placeholder silently; distinct messageIDs settle independently
  (opencode genuinely replying twice is fair game and renders twice).
- Intermediate reasoning/display sequence for a tool turn (observed):
  `step started` → `delta` → `tool called` → `tool success` →
  `step ended` → … → `done`. Reasoning text (when the model produces
  enough of it) flushes as `step` rows of kind `reasoning` on the 5s
  tick; short turns may show none. The mirror's steps panel lists all of
  these with kind prefixes; the rendered panel paints live text per turn.
- Same-turnId POST retries await the original acceptance and replay its
  exact outcome (sessionID included) — server `inflightAccept`, settled
  outcomes kept 10min. No duplicate prompt is ever enqueued.
- Every accepted send publishes its session to the per-chat stream
  correlation (`lastSessionByChat`), create + reuse alike, so a stream
  opened before the first send narrows to the new session and the first
  turn streams live. Restart/delete/no-session clears it.
- `GET /api/turn?chatId=&turnId=` reads the exact retained result:
  `settled` (deliver via the normal done path) or `unknown` — never
  history-searches, so overlapping merged turns can't misattach.
- The app never deletes a live placeholder on timeout: stale turns keep
  their placeholder (settleable by a late `done`), surface one honest
  unknown row each, and reconcile by exact turnId. Placeholders die only
  on definitive cancel (/stop), failure, or user deletion.
- A restart mid-turn deletes the session, so the running turn settles as an
  error `done` ("session expired — send again for a fresh one") instead of
  a stale-context reply; the app shows it as a failed send and keeps the
  text for resend.
- The app reattaches streams for chats with live placeholders on resume
  (rehydrating turn tracking), so a process death mid-turn still settles
  when a reconnected `done` replays. A hung placeholder stays live and
  surfaces an unknown-outcome row with check-again / informed-retry
  actions instead of a false "never arrived".
- Step rows are send-once per chat+session across reconnects (server
  high-water mark): a reconnect replays unsettled `done`s (the app
  dedupes by turnId) but never replays old steps, so replayed history
  can't re-stamp a stuck turn as fresh. A genuinely stuck turn therefore
  ages out via the 90s stop rule / 10min sweep as designed.
- Away/stream ownership (app-only, single live stream): the stream adopts
  the most recent chat with live turns when the owner goes idle; closing
  or switching reaps outstanding turns without killing another live
  owner's stream; background-chat settles ping. The mirror is single-chat
  and covers per-turn rules; cross-chat adoption is verified by device
  test, not by the mirror.

## Mirror principle (canonical)

Mirror opencode as closely as possible; never invent client-side gating.
The ONLY explicit-wait primitive is `/wait` (alias `/w`): plain sends are
send-through (opencode inbox merges them), `/stop` is interrupt, stream
state (done/idle/steps) is observed, never polled into blocking. If a new
flow needs the UI to hold for a reply, it goes through `/wait` — no
second waiting mechanism.

## Parity rule

When changing app stream/settle/stop/title logic (`MainActivity.kt` stream
+ `ChatStore.kt` placeholders, or `opencode-proxy.ts` stream emission),
mirror the change in `/debug/chat` first and re-run the verify script.
Pointer comments live at both ends; this file is canonical for the
lifecycle facts above.

## Multi-backend (M9: opencode + codex + claude)

Contract additions — all existing opencode lifecycle facts above stay
canonical and unchanged:

- A chat owns one backend permanently (`backend` on the session, bound
  server-side on first POST /api/chat). Global default changes never
  migrate existing chats; mismatch is 409 `backend_mismatch`.
- Turn identity is always the Terminus `turnId`. Provider ids (opencode
  messageID, codex thread/turn, claude session) are metadata only.
  Codex/claude turns settle by turnId alone — no merge pointers.
- At-least-once `done` holds per backend: completions record into the
  shared turn registry and replay on reconnect; the client dedupes by
  turnId/messageID exactly as before. Per-connection doneSent keeps
  exactly-once per connection.
- Live vocabulary is unchanged (`delta`/`step`/`done`/`error`/`idle`):
  codex/claude deltas fan out on the runtime bus, terminal events flush
  every open connection at once. Stream close never settles; no timeout
  settles turns; same-turnId retries never run twice (P19).
- Progress stays non-authoritative; failures never fall back to another
  backend (explicit `[Backend]`-prefixed rows, mapped HTTP statuses).
- `/debug/chat` backend selector + per-backend probe controls are pending;
  until then multi-backend live proof runs through the unit suite, the
  codex smoke (thread/turn/resume), and device tests.
