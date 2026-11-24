package com.terminus.rec

import java.io.File

/** Editing, retrying and the offline outbox. Extension functions of [MainActivity], moved out of it unchanged. */

/** Tap a failed bubble to edit that message in the composer itself —
 *  no modal. Send resends the failed turn with the edited text; back
 *  cancels and restores whatever was typed. Only bubbles carrying the
 *  failure flag respond. */
/** Visible slice + its global base index, mirroring renderThread. */
internal fun MainActivity.visibleThreadRows(id: String): Pair<List<ChatMessage>, Int>? {
    val s = try { chatStore.get(id) } catch (_: Exception) { return null } ?: return null
    val all = s.messages
    val vis = threadVisible[id] ?: threadPageSize
    val base = if (all.size > vis) all.size - vis else 0
    return (if (all.size > vis) all.takeLast(vis) else all) to base
}

internal fun MainActivity.tapMessageToEdit(x: Float, y: Float) {
    val id = activeSessionId ?: return
    if (!threadPageLoaded) return
    try {
        threadWeb.evaluateJavascript("window.__rowAt($x,$y)") { res ->
            try {
                if (res == null || res == "null") return@evaluateJavascript
                val o = org.json.JSONObject(res)
                // Link taps navigate; only a bare failed bubble edits.
                if (o.optString("l") == "1") return@evaluateJavascript
                if (o.optString("r") != "user") return@evaluateJavascript
                val idx = o.optString("i").toIntOrNull() ?: return@evaluateJavascript
                val (msgs, base) = visibleThreadRows(id) ?: return@evaluateJavascript
                val m = msgs.getOrNull(idx - base) ?: return@evaluateJavascript
                if (m.role != "user" || m.turnId.isBlank() || !m.sendFailed) return@evaluateJavascript
                runOnUiThread { enterEditMode(id, m.turnId) }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.enterEditMode(chat: String, turn: String) {
    if (activeSessionId != chat) return
    val origWire = try { chatStore.userTurnFor(chat, turn)?.text } catch (_: Exception) { null }
    val display = if (!origWire.isNullOrBlank()) TurnActivity.stripQuotePrefix(origWire)
        else retryInputs(chat, turn)?.third
    if (display.isNullOrBlank()) {
        toast("Message lost — type it again")
        return
    }
    editingTurn = chat to turn
    editingStashByTurn[turn] = try { composerInput.text.toString() } catch (_: Exception) { "" }
    composerInput.setText(display)
    composerInput.setSelection(display.length)
    composerInput.hint = "Editing failed message — send to resend"
    composerInput.requestFocus()
    try {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(composerInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    } catch (_: Exception) {}
    toast("Send to resend, back to cancel")
}

/** Exit edit mode. Back restores the displaced draft; a resolved retry
 *  drops it (the resend already cleared the box). */
internal fun MainActivity.cancelEditMode() {
    val (chat, turn) = editingTurn ?: return
    editingTurn = null
    val stash = editingStashByTurn.remove(turn) ?: ""
    if (activeSessionId == chat) {
        if (stash.isNotBlank()) {
            composerInput.setText(stash)
            composerInput.setSelection(stash.length)
        }
        composerInput.hint = "Message (/ for commands)"
    }
}

/** Leave edit mode without resolving: the composer hint restores.
 *  Stashes stay — cancel/resend/re-enter/thread-switch consume them. */
internal fun MainActivity.clearEditState() {
    editingTurn = null
    try { composerInput.hint = "Message (/ for commands)" } catch (_: Exception) {}
}

internal fun MainActivity.saveEditAndResend(chat: String, turn: String, edited: String) {
    val e = edited.trim()
    if (e.isBlank()) {
        toast("Write a message first")
        return
    }
    val origWire = try { chatStore.userTurnFor(chat, turn)?.text } catch (_: Exception) { null }
    val quote = if (!origWire.isNullOrBlank() && origWire.startsWith(">") && "\n" in origWire)
        origWire.substringBefore("\n") + "\n" else ""
    val wire = quote + e
    try { chatStore.updateUserText(chat, turn, wire) } catch (_: Exception) {}
    try { failedRestore[turn] = wire } catch (_: Exception) {}
    if (activeSessionId == chat) renderThread(chat)
    renderSessions()
    retryTurn(chat, turn)
}

/** Tap-to-retry a failed turn. Reconcile-first with three outcomes:
 *  settled → deliver through the normal path (and say "already replied"
 *  when the text already renders); running → note it, resend nothing;
 *  unknown/unreachable → resend as a FRESH turn (warned may-run-twice)
 *  with the ORIGINAL wire inputs recovered from the persisted user row
 *  (attachments + quote survive restarts; memory stash is legacy
 *  fallback). The original retires as superseded first, so its late
 *  `done` drops instead of twinning. Every dead end answers visibly —
 *  links never go silent. */
internal fun MainActivity.retryTurn(chat: String, turn: String) {
    showChecking(chat)
    Thread {
        val inputs = retryInputs(chat, turn)
        // Reconcile the server's own view first (best effort, every backend): a turn stuck "running"
        // with nothing behind it, or a finished reply that never arrived, settles here instead of
        // leaving Retry to answer "still running" forever.
        try {
            val rsid = try { chatStore.get(chat)?.remoteId ?: "" } catch (_: Exception) { "" }
            OpencodeApi.syncChat(prefs.serverUrl(), prefs.token(), chat, rsid, chatBackendOf(chat))
        } catch (_: Exception) { /* no news: the exact lookup below decides */ }
        try {
            val r = OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), chat, turn, chatBackendOf(chat))
            when (TurnActivity.retryOutcome(r.settled, r.running, inputs != null, settledError = r.error.isNotBlank() && r.text.isBlank())) {
                RetryOutcome.SETTLE -> {
                    val rendered = r.text.isNotBlank() && try {
                        val norm = TurnActivity.normText(r.text)
                        chatStore.get(chat)?.messages?.any {
                            it.role == "assistant" && TurnActivity.normText(it.text) == norm
                        } ?: false
                    } catch (_: Exception) { false }
                    val ft = r.text; val fe = r.error; val fm = r.messageID
                    runOnUiThread {
                        hideChecking(chat)
                        clearEditState()
                        finishStreamTurn(chat, turn, ft, fe, fm, r.code)
                        if (rendered) toast("That message already has a reply")
                    }
                    return@Thread
                }
                RetryOutcome.WAIT -> {
                    runOnUiThread {
                        hideChecking(chat)
                        clearEditState()
                        // The server is still working on this: it never failed. Put the message back to
                        // "running" (no error, no instruction to stop it) and let the reply land when it
                        // does, like any other send.
                        try { chatStore.setUserSendFailed(chat, turn, false) } catch (_: Exception) {}
                        try { chatStore.dropAllUnknownRows(chat) } catch (_: Exception) {}
                        try { chatStore.dropLastFailureNote(chat) } catch (_: Exception) {}
                        unknownSurfaced[chat]?.remove(turn)
                        serverIdle[chat] = false
                        pendingTurns.getOrPut(chat) { mutableSetOf() }.add(turn)
                        val hasPlaceholder = try {
                            chatStore.get(chat)?.messages?.any { it.role == "assistant" && it.turnId == turn } == true
                        } catch (_: Exception) { false }
                        if (!hasPlaceholder) chatStore.addMessage(chat, ChatMessage("assistant", "…", turnId = turn))
                        if (activeSessionId == chat) renderThread(chat)
                        renderSessions()
                        ensureStream(chat)
                        toast("Still working on it")
                    }
                    return@Thread
                }
                RetryOutcome.DEADLINK -> {
                    runOnUiThread {
                        hideChecking(chat)
                        clearEditState()
                        toast("Message lost — type it again")
                    }
                    return@Thread
                }
                RetryOutcome.RESEND -> { /* fall through to resend below */ }
            }
        } catch (_: Exception) {
            // Unreachable registry: resend iff inputs exist (warned).
            if (inputs == null) {
                runOnUiThread {
                    hideChecking(chat)
                    clearEditState()
                    toast("Message lost — type it again")
                }
                return@Thread
            }
        }
        val ready = inputs ?: run {
            runOnUiThread {
                hideChecking(chat)
                clearEditState()
                toast("Message lost — type it again")
            }
            return@Thread
        }
        runOnUiThread {
            toast("Resending (may run twice)")
            resendNewTurn(chat, turn, ready.first, ready.second, ready.third)
        }
    }.start()
}

/** Try the waiting sends again soon, and keep trying every minute while any wait. */
internal fun MainActivity.scheduleOutboxRetry(delayMs: Long = 60_000) {
    outboxHandler.removeCallbacks(outboxTick)
    outboxHandler.postDelayed(outboxTick, delayMs)
}

/** Send every message that is waiting for a connection, oldest first, once the Mac answers. They never
 *  reached the server, so this sends them for the first time (no duplicate risk) and leaves the composer
 *  and the open chat alone. Triggered by a new network, the live link coming up, app resume and a timer. */
internal fun MainActivity.flushOutbox() {
    if (!chatStoreReady || outboxRunning) return
    val waiting = try {
        chatStore.list().flatMap { c ->
            c.messages.filter { it.role == "user" && it.sendFailed && Outbox.isWaiting(it.failError) && it.turnId.isNotBlank() }
                .map { Triple(c.id, it, it.ts) }
        }.sortedBy { it.third }
    } catch (_: Exception) { emptyList() }
    if (waiting.isEmpty()) return
    outboxRunning = true
    Thread {
        val up = OpencodeApi.pingOk(prefs.serverUrl(), "opencode") ||
            try { OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, System.currentTimeMillis()); true } catch (_: Exception) { false }
        runOnUiThread {
            outboxRunning = false
            if (!up) {
                // Still unreachable: refresh the reason (Tailscale back on but Mac asleep, ...) and wait.
                val why = Outbox.PREFIX + Outbox.reason(this, prefs.serverUrl())
                for ((chat, m, _) in waiting) if (m.failError != why) try { chatStore.setUserSendFailed(chat, m.turnId, true, why) } catch (_: Exception) {}
                activeSessionId?.let { a -> if (waiting.any { it.first == a }) renderThread(a) }
                scheduleOutboxRetry()
                return@runOnUiThread
            }
            for ((chat, m, _) in waiting) {
                val files = m.allAttachments.filter { it.isNotBlank() }.map { java.io.File(it) }.filter { it.exists() }
                val newId = newTurnId()
                try {
                    chatStore.setUserSendFailed(chat, m.turnId, false)
                    chatStore.rebindUserTurn(chat, m.turnId, newId)
                } catch (_: Exception) { continue }
                startTurn(chat, m.text, files, restoreText = TurnActivity.stripQuotePrefix(m.text), fixedTurnId = newId)
            }
            renderSessions()
        }
    }.start()
}

/** Recover resend inputs: persisted user row first (restart-safe,
 *  attachments + quote prefix intact), memory stash legacy fallback.
 *  Null = dead link. Triple = wire text, files, display text. */
internal fun MainActivity.retryInputs(chat: String, turn: String): Triple<String, List<java.io.File>, String>? {
    try {
        chatStore.userTurnFor(chat, turn)?.let { row ->
            val files = row.allAttachments.filter { it.isNotBlank() }
                .map { java.io.File(it) }.filter { it.exists() }
            return Triple(row.text, files, TurnActivity.stripQuotePrefix(row.text))
        }
    } catch (_: Exception) {}
    val stash = failedRestore[turn] ?: turnRestore[turn]
    if (!stash.isNullOrBlank()) return Triple(stash, emptyList(), stash)
    return null
}
