package com.terminus.rec

/** Confirmed-turn activity decisions (pure, no Android deps — unit-tested).
 *
 *  The chat list "replying…" row and the thread thinking box must reflect
 *  turns the app has positively tracked as in flight — never mere
 *  placeholder freshness. Freshness lies: one turn's step/delta activity
 *  used to refresh every unsettled placeholder in the chat, so a stale
 *  orphan looked live while its turn was long dead.
 *
 *  Authority chain:
 *  - startTurn() registers the turnId in the in-flight set (confirmed: we
 *    just POSTed it).
 *  - done / error / stop-success / server-confirmed idle remove it.
 *  - After process death the set is empty; reattach re-registers persisted
 *    placeholders only once the server confirms (reconcileAfterRestart).
 *  - Placeholders themselves are NEVER removed here — a late `done` still
 *    settles through ChatStore.settleTurn. Only the indicator follows this
 *    object; late-response recovery and overlap support are untouched. */
object TurnActivity {

    /** Row/box indicator: replying iff this chat has a confirmed in-flight turn. */
    fun isReplying(chatId: String, inFlight: Map<String, Set<String>>): Boolean =
        inFlight[chatId]?.isNotEmpty() == true

    /** Chats currently allowed to show "replying…". */
    fun replyingChats(inFlight: Map<String, Set<String>>): Set<String> =
        inFlight.filterValues { it.isNotEmpty() }.keys

    /** Server `idle` event reconciled against live session state:
     *  - working == false (server confirms idle/done) → indicator off now.
     *  - working == true (stale/early idle raced a new turn) → keep.
     *  - working == null (query failed) → keep: never clear on transport
     *    failure; the next event or sweep retries. */
    fun resolveIdle(inFlightForChat: Set<String>, working: Boolean?): Set<String>? =
        when (working) {
            false -> emptySet()
            true -> inFlightForChat
            null -> null
        }

    /** Process-restart reconcile for one chat's persisted live placeholders:
     *  server-confirmed settled turns drop out; the rest stay in flight
     *  only while the server confirms the session is still working.
     *  Unconfirmed (null) or idle/done sessions → no indicator. The
     *  placeholders themselves survive regardless for late-`done`
     *  settlement. */
    fun reconcileAfterRestart(
        persistedLive: Set<String>,
        settled: Set<String>,
        working: Boolean?,
    ): Set<String> =
        if (working == true) persistedLive - settled else emptySet()

    /** Exact-turn registry answer drops one turn from the in-flight set.
     *  Unknown (still-running or wiped registry) keeps it. */
    fun dropSettled(inFlightForChat: Set<String>, turnId: String, settled: Boolean): Set<String> =
        if (settled) inFlightForChat - turnId else inFlightForChat

    fun autoSettleTargets(
        live: List<Pair<String, Long>>,
        nowMs: Long,
        working: Boolean?,
        onlyStale: Boolean,
    ): Set<String> {
        if (working != false) return emptySet()
        return live
            .filter { (turn, ts) -> turn.isNotBlank() && (!onlyStale || nowMs - ts >= 90_000) }
            .map { it.first }
            .toSet()
    }

    /** Pure: tap debounce for send (unit-tested). Same 600ms window for any
     *  content — bounce protection without ever judging intent by text. */
    fun sendDebounced(nowMs: Long, lastSendMs: Long, windowMs: Long = 600L): Boolean =
        lastSendMs > 0L && nowMs - lastSendMs < windowMs

    /** Pure: which chats the ticker should verify this round (unit-tested).
     *  A chat qualifies with live turns, none fresh (a fresh turn racing a
     *  stale signal keeps running), and no verification already in flight.
     *  Usually zero or one — never a fan-out. */
    fun verifyCandidates(
        liveByChat: Map<String, List<Pair<String, Long>>>,
        nowMs: Long,
        verifying: Set<String>,
    ): Set<String> =
        liveByChat
            .filter { (id, live) ->
                id !in verifying && live.any { (turn, _) -> turn.isNotBlank() } &&
                    live.none { (turn, ts) -> turn.isNotBlank() && nowMs - ts < 90_000 }
            }
            .keys

    /** Pure: tap-retry routing for one registry answer (unit-tested). settled
     *  → deliver, no second run. running → wait, resend nothing. unknown +
     *  recoverable inputs → fresh-turn resend (warned). unknown with nothing
     *  to resend → visible dead end, never silence. */
    fun retryOutcome(settled: Boolean, running: Boolean, hasInputs: Boolean, settledError: Boolean = false): RetryOutcome =
        when {
            // The server's recorded outcome IS the failure the person is retrying: re-delivering it
            // changes nothing on screen (the retry looked dead). With inputs on hand, send it again.
            settled && settledError && hasInputs -> RetryOutcome.RESEND
            settled -> RetryOutcome.SETTLE
            running -> RetryOutcome.WAIT
            hasInputs -> RetryOutcome.RESEND
            else -> RetryOutcome.DEADLINK
        }

    /** Pure: strip a "> quote" first line back to display text (unit-tested).
     *  Retry resends the full wire text but titles/restores the display form. */
    fun stripQuotePrefix(wire: String): String {
        if (!wire.startsWith(">")) return wire
        return wire.substringAfter("\n").trim().ifBlank { wire }
    }

    /** Pure: whitespace-normalized text compare (unit-tested). Late-done
     *  dedupe must survive trailing-space/newline variants or they twin. */
    fun normText(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /** Codes for a native provider failure (mirrors the server
     *  BackendErrorCode set). Any of these lets the provider's own message
     *  lead — everything else is harness-side and keeps the wrapper.
     *  The message renders as its own system notice row after the failed bubble
     *  (ChatMarkdown.errorNoticeHtml, red, compact-divider style), never inside the
     *  person's prompt bubble. */
    const val CODE_PROVIDER_ERROR = "provider_error"
    private val PROVIDER_CODES = setOf(
        "provider_error", "usage_limit", "rate_limited", "not_authenticated",
        "model_not_found", "session_not_found", "permission_denied",
        "backend_unavailable", "timeout", "unknown",
    )

    /** Pure: chat-level error note text (unit-tested). Claude-compact: one
     *  quiet line per failed turn — "Failed to send. Retry · Edit" — never
     *  a backend-named paragraph. A native provider failure still leads with
     *  the provider's message verbatim; every other failure hides the
     *  harness internals (they stay in the logs). Both actions resolve via
     *  the persisted user row, so they survive restarts. */
    fun formatErrorNote(msg: String, code: String, chatId: String, turnId: String): String {
        val clean = msg.trim().trimEnd('.')
        val providerLed = code in PROVIDER_CODES && clean.isNotBlank()
        // Server-started turns (goal loop, background wake) have no user row
        // to resend: plain note, no Retry/Edit links.
        if (turnId.startsWith("goal-") || turnId.startsWith("bg-")) {
            return if (providerLed) clean else "Failed to send."
        }
        val actions = "[Retry](terminus://retry?chat=$chatId&turn=$turnId) · [Edit](terminus://edit?chat=$chatId&turn=$turnId)"
        return if (providerLed) "$clean. $actions"
        else "Failed to send. $actions"
    }

    /** Late-`done` routing for an already-delivered turn (unit-tested).
     *  Claimed messageID (opencode fused this turn into a rendered reply)
     *  → DROP. A kept partial for this same turn → UPGRADE in place. This
     *  turn already rendered → DROP (true replay). No messageID and rendered
     *  text → DROP (probable replay). Anything else → APPEND: a new messageID
     *  never rendered is a genuine reply even when its text coincides with
     *  an old row — distinct messageIDs settle independently. */
    fun lateDoneRoute(
        messageIdClaimed: Boolean,
        upgradeApplies: Boolean,
        sameTurnRendered: Boolean,
        messageIdPresent: Boolean,
        textRendered: Boolean,
    ): LateDone =
        when {
            messageIdClaimed -> LateDone.DROP
            upgradeApplies -> LateDone.UPGRADE
            sameTurnRendered -> LateDone.DROP
            !messageIdPresent && textRendered -> LateDone.DROP
            else -> LateDone.APPEND
        }
}

/** Late-`done` outcome for an already-delivered turn. */
enum class LateDone { DROP, UPGRADE, APPEND }

/** Tap-retry outcome for one registry answer. */
enum class RetryOutcome { SETTLE, WAIT, RESEND, DEADLINK }
