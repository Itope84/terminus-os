package com.terminus.rec

/** A turn is running while the phone still tracks it as pending (sent,
 *  not yet settled or stopped) and its placeholder is unsettled. Quiet
 *  turns stay running: silence is not a signal, so Stop never vanishes
 *  mid-turn. Orphans (lost `done`) are cleared by the stale sweep, not by
 *  a clock here. Pure; the stop control and the activity box both read it. */
internal fun runningTurnIds(messages: List<ChatMessage>, pending: Set<String>, serverIdle: Boolean): List<String> {
    if (serverIdle) return emptyList()
    return messages
        .filter { it.role == "assistant" && it.turnId.isNotBlank() && !it.settled && it.turnId in pending }
        .map { it.turnId }
}

/** Turns explicitly reported as waiting by the backend, plus app-held entries.
 *  Never infer queue state from row order: a later placeholder can already be running. */
internal fun waitingTurnIdsOf(
    messages: List<ChatMessage>,
    appHeldBackend: Boolean,
    backendWaiting: List<String>,
    held: List<String>,
    steered: Set<String>,
): List<String> {
    val validTurns = messages.asSequence().filter { it.role == "assistant" && !it.settled }.map { it.turnId }.toSet()
    val posted = if (appHeldBackend) emptyList() else backendWaiting
    return (posted + held).filter { it.isNotBlank() && it in validTurns && it !in steered }.distinct()
}
