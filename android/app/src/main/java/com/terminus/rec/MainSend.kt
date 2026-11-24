package com.terminus.rec

import android.view.View
import java.io.File

/** Sending a message and starting a turn. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.sendChat(voiceSendCommand: Boolean = false, steer: Boolean = false) {
    stopDictation()
    // A note still open inline goes out with its quote, as if Done was tapped.
    if (editingQuote != null) saveQuoteNote()
    val text = composerInput.text.toString().trim()
    val attaches = pendingAttaches.toList()
    if (text.isEmpty() && attaches.isEmpty() && quotes.isEmpty()) return
    // Tap debounce (any content, 600ms): a finger bounce on send is one
    // physical tap, not intent. Never suppress on text equality — an
    // intentional repeat ("?", "retry") is its own turn with its own
    // turnId (the idempotency key); transport retries reuse that key and
    // the server dedupes them, so distinct sends stay distinct.
    val now = System.currentTimeMillis()
    if (TurnActivity.sendDebounced(now, lastSendAt)) {
        android.util.Log.d("ChatTurn", "send bounce debounced")
        return
    }
    lastSendAt = now
    // Edit mode: the box holds a failed turn's edited text — send
    // resends that turn instead of starting a new one.
    val et = editingTurn
    val openId = activeSessionId
    if (et != null && openId != null && et.first == openId && et.second.isNotBlank()) {
        if (text.isBlank()) {
            toast("Write a message first")
            return
        }
        saveEditAndResend(openId, et.second, text)
        return
    } else if (et != null) {
        cancelEditMode()
    }
    // Draft slash commands that need no session: never persist for these.
    if (activeSessionId == null && text.startsWith("/")) {
        val cmd = text.split("\\s+".toRegex(), limit = 2)[0].lowercase()
        val arg = text.split("\\s+".toRegex(), limit = 2).getOrNull(1)?.trim() ?: ""
        when (cmd) {
            "/new" -> {
                openDraft()
                slashList.visibility = View.GONE
                clearQuote()
                return
            }
            "/sessions" -> {
                closeThread()
                slashList.visibility = View.GONE
                clearQuote()
                return
            }
            "/search" -> {
                closeThread()
                searchOpen = true
                sessionSearch.setText(arg)
                sessionQuery = arg
                sessionSearch.visibility = View.VISIBLE
                searchIcon.setImageResource(R.drawable.ic_close)
                slashList.visibility = View.GONE
                clearQuote()
                renderSessions()
                return
            }
        }
    }
    // Empty-state composer: persist the draft on first real send only.
    if (activeSessionId == null) ensureDraftSession()
    val id = activeSessionId ?: return
    if (text.startsWith("/")) {
        // Commands bypass the send queue and act immediately.
        handleCommand(id, text)
        composerInput.setText("")
        slashList.visibility = View.GONE
        clearQuote()
        return
    }
    // Queue-by-default: a backlog that outlived its turn drains in
    // order before the new send; opencode sends behind a running
    // reply hold in the app queue instead of blasting through.
    if ((waitQueue[id]?.size ?: 0) > 0 && !isTurnActive(id)) {
        // Backlog exists but the turn already settled (e.g. failed path
        // kept it): drain in order, then send this one through too.
        drainWaitQueue(id)
    }
    val wireText = QuoteDraft.wire(quotes, text)
    // Queue-by-default on opencode: a send behind a running reply would
    // otherwise blast straight into it. Hold it in the app queue (row +
    // Send-now link); it drains in order or releases early via its link.
    if (Prefs.cannonBackend(chatBackendOf(id) ?: prefs.defaultBackend()) == Prefs.BACKEND_OPENCODE && isTurnActive(id)) {
        enqueueWait(id, wireText, attaches, text)
        if (activeSessionId == id) renderThread(id)
        renderSessions()
        return
    }
    // Codex sends can request turn/steer; OpenCode uses its normal chat
    // request and accepts subsequent messages without a special route.
    val steerNow = steer && canSteer(id)
    val behindRunning = isTurnActive(id) && Prefs.cannonBackend(chatBackendOf(id) ?: prefs.defaultBackend()) != Prefs.BACKEND_OPENCODE
    sendVisibleTurn(id, wireText, attaches, text, voiceSendCommand = voiceSendCommand, steer = steerNow)
    if (behindRunning) pollWaiting(id)
}

/** A server-queued send (codex, claude) becomes a Send-now row while the server says it is
 *  waiting. Keep asking while the reply runs, so the row clears when the queued turn starts
 *  (or the reply ends) instead of sticking until the next manual sync. */
internal fun MainActivity.pollWaiting(id: String, tries: Int = 0) {
    progressHandler.postDelayed({
        if (!isTurnActive(id)) {
            backendWaitingTurns.remove(id)
            if (activeSessionId == id) refreshSendNowLinks()
            return@postDelayed
        }
        Thread {
            val r = try {
                val rsid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                OpencodeApi.syncChat(prefs.serverUrl(), prefs.token(), id, rsid, chatBackendOf(id))
            } catch (_: Exception) { null }
            runOnUiThread {
                if (r == null) {
                    if (tries < POLL_WAITING_MAX) pollWaiting(id, tries + 1)
                    return@runOnUiThread
                }
                backendWaitingTurns[id] = r.waiting.toSet()
                for (t in (r.settled + r.lost).distinct()) reconcileTurn(id, t)
                if (activeSessionId == id) refreshSendNowLinks()
                if (r.waiting.isNotEmpty() && tries < POLL_WAITING_MAX) pollWaiting(id, tries + 1)
            }
        }.start()
    }, if (tries == 0) 700L else POLL_WAITING_GAP_MS)
}

/** Resend previously-failed/unknown inputs as a genuinely new turn. The
 *  original retires as superseded first (placeholder gone, late outcome
 *  drops); the persisted user row is reused, so no second user bubble
 *  and attachments ride along. */
internal fun MainActivity.resendNewTurn(chat: String, turn: String, wire: String, files: List<java.io.File>, display: String) {
    failedRestore.remove(turn)
    turnRestore.remove(turn)
    if (chatStore.get(chat) == null) {
        hideChecking(chat)
        return
    }
    pendingTurns[chat]?.remove(turn)
    dropTurnBuf(turn)
    chatStore.dropPlaceholder(chat, turn)
    // Its own failure line clears on the way out (Claude-style): if this
    // resend fails, a fresh note lands in its place via the normal path.
    // Both drops: trailing (usual case) and targeted (an older note for
    // this turn buried under later rows must not survive the success).
    try { chatStore.dropLastFailureNote(chat) } catch (_: Exception) {}
    try { chatStore.dropFailureNoteForTurn(chat, turn) } catch (_: Exception) {}
    supersededTurns.getOrPut(chat) { mutableMapOf() }[turn] = System.currentTimeMillis()
    hideChecking(chat)
    if (activeSessionId != chat) openThread(chat)
    val kept = try { composerInput.text.toString() } catch (_: Exception) { "" }
    dropPendingDraft()
    sendVisibleTurn(chat, wire, files, display, appendUser = false, reuseUserTurnId = turn)
    if (kept.isNotBlank() && kept != display) {
        composerInput.setText(kept)
        composerInput.setSelection(kept.length)
    }
    // An edit-mode resend resolves the edit here: restore the displaced
    // draft (if any), leave edit mode, restore the hint. The resend
    // already cleared the box.
    val editStash = editingStashByTurn.remove(turn) ?: ""
    val wasEditing = editingTurn?.first == chat && editingTurn?.second == turn
    if (wasEditing) {
        editingTurn = null
        if (editStash.isNotBlank()) {
            composerInput.setText(editStash)
            composerInput.setSelection(editStash.length)
        }
        composerInput.hint = "Message (/ for commands)"
    }
    renderSessions()
    updateStopBtn()
}

/** Visible send: composer clears first so the tap feels instant, then
 *  one store pass, then its own turn POSTs — even mid-turn. Resends pass
 *  appendUser=false to reuse the persisted user row (no second bubble). */
internal fun MainActivity.sendVisibleTurn(id: String, wireText: String, attaches: List<java.io.File>, displayText: String, appendUser: Boolean = true, reuseUserTurnId: String = "", voiceSendCommand: Boolean = false, nativeCommandName: String = "", steer: Boolean = false, steerRequired: Boolean = false) {
    val session = chatStore.get(id)
    // Heal stale stub values ("default"/"fast"/"pro" predate live models).
    // The "/" shape is opencode-only: other backends use opaque ids
    // (sonnet, gpt-5.x-codex), which this must never wipe. Blank model
    // means follow that backend's default (server resolves it).
    val backend = try { session?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
    val model = if (backend == "opencode") session?.model?.takeIf { it.contains("/") } else sendModelFor(backend, session?.model)
    if (session != null && backend == "opencode" && !session.model.isBlank() && model == null) chatStore.setModel(id, "")
    val effort = session?.effort?.takeIf { it == "low" || it == "medium" || it == "high" }
    clearQuote()
    composerInput.setText("")
    composerInput.hint = "Message (/ for commands)"
    if (voiceSendCommand) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(composerInput.windowToken, 0)
        composerInput.clearFocus()
    }
    // The send owns the draft from here (appendUserTurn clears it): a
    // debounced keystroke write must not land afterwards.
    dropPendingDraft()
    pendingAttaches.clear()
    hideAttachStrip()
    slashList.visibility = View.GONE
    val first = attaches.firstOrNull()
    val quoteTitle = if (wireText.startsWith(">") && displayText.isNotBlank()) displayText.trim().take(42).lineSequence().first() else null
    val attachTitle = if (displayText.isBlank() && first != null && session?.title == "new chat") "📎 ${AttachName.friendly(first.name).take(40)}" else null
    // The turn owns its id from birth: the user row carries it so a later
    // retry can recover the exact wire text + attachment (restart-safe —
    // user rows persist, the memory stash does not).
    val turnId = newTurnId()
    if (appendUser) {
        chatStore.appendUserTurn(id, ChatMessage("user", wireText, attachment = first?.absolutePath, moreAttachments = attaches.drop(1).map { it.absolutePath }, turnId = turnId, voiceSendCommand = voiceSendCommand), quoteTitle, attachTitle)
    } else if (reuseUserTurnId.isNotBlank()) {
        chatStore.rebindUserTurn(id, reuseUserTurnId, turnId)
    }
    startTurn(id, wireText, attaches,
        // A quoted send restores whole (quotes + notes as markdown) on failure, not just the typed part.
        restoreText = if (wireText.startsWith(">")) wireText else displayText, fixedTurnId = turnId, nativeCommandName = nativeCommandName, steer = steer, steerRequired = steerRequired)
}

/** Fresh turn id: millis + random, unique across restarts and queue drains. */
internal fun MainActivity.newTurnId(): String =
    "t-${System.currentTimeMillis().toString(36)}-${(0..0xffffff).random().toString(36)}"

internal fun MainActivity.startTurn(id: String, wireText: String, files: List<java.io.File>, restoreText: String, fixedTurnId: String = "", nativeCommandName: String = "", steer: Boolean = false, steerRequired: Boolean = false, onSubmitted: ((Boolean) -> Unit)? = null) {
    val session = chatStore.get(id)
    // Heal stale stub values ("default"/"fast"/"pro" predate live models).
    // The "/" shape is opencode-only: other backends use opaque ids
    // (sonnet, gpt-5.x-codex), which this must never wipe. Blank model
    // means follow that backend's default (server resolves it).
    val backend = try { session?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
    val model = if (backend == "opencode") session?.model?.takeIf { it.contains("/") } else sendModelFor(backend, session?.model)
    if (session != null && backend == "opencode" && !session.model.isBlank() && model == null) chatStore.setModel(id, "")
    val effort = session?.effort?.takeIf { it == "low" || it == "medium" || it == "high" }
    val turnId = fixedTurnId.ifBlank { "t-${System.currentTimeMillis().toString(36)}-${(0..0xffffff).random().toString(36)}" }
    // Job/alert deliveries the chat's agent never saw ride along with this message (wire only).
    val sendText = DeliveryContext.wrap(DeliveryContext.collect(session?.messages ?: emptyList(), turnId), wireText)
    replyVoice.warmUp()   // warm the voice engine while the reply is generated
    serverIdle[id] = false
    turnRestore[turnId] = restoreText
    // A run that starts on an idle chat begins with an empty step list.
    if (pendingTurns[id].isNullOrEmpty()) progressLive[id] = emptyList()
    pendingTurns.getOrPut(id) { mutableSetOf() }.add(turnId)
    lastAskedAt[id] = System.currentTimeMillis()
    chatStore.addMessage(id, ChatMessage("assistant", "…", turnId = turnId))
    renderThread(id)
    renderSessions()
    ensureStream(id)
    ensureNotifPermission()
    // Live reply via the terminus opencode proxy. Input stays cleared on
    // success; on failure the error lands inline and the text is restored.
    // The attachment file already lives in filesDir/media (durable keep);
    // the base64 below is the transfer copy only — encoded off the UI
    // thread so image sends don't jank the tap.
    Thread {
        // Server takes at most 3 attachments per turn: send the first 3 that prepare, name the rest.
        val prepared = files.map { it to MediaFiles.prepareForUpload(it) }
        val okFiles = prepared.filter { it.second != null }
        val sent = okFiles.take(3)
        val unreadable = prepared.filter { it.second == null }.map { it.first.name }
        val overflow = okFiles.drop(3).map { it.first.name }
        val wireFiles = sent.map { val u = it.second!!; OpencodeApi.ChatAttachment(u.first, u.second, u.third) }
        if (unreadable.isNotEmpty() || overflow.isNotEmpty()) {
            val parts = mutableListOf<String>()
            if (unreadable.isNotEmpty()) parts += "not sent (unreadable or over 5MB): " + unreadable.joinToString(", ")
            if (overflow.isNotEmpty()) parts += "not sent (limit is 3 files per message): " + overflow.joinToString(", ")
            runOnUiThread {
                postNote(id, "tool", parts.joinToString("; ") + ". Kept on device." +
                    if (wireFiles.isEmpty()) " Message sent text-only." else "")
            }
        }
        // Single POST per turn (double-POST fix): exactly one retry, only
        // on socket-level aborts. turnId binds this POST to its own "…".
        // The chat owns its opencode session id (remoteId): blank means
        // "create", and the reply's sessionID is adopted. A 404 `no
        // session` means opencode lost it — clear, note it, resend once
        // fresh (same turnId; the failed attempt reserved nothing).
        var accept: OpencodeApi.ChatAccept? = null
        var failure: Exception? = null
        var sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
        // Only initial session acquisition serializes: a blank send
        // while another blank send is acquiring waits (≤30s) for the
        // adopted id, then posts normally into that same session — one
        // chat, one session. Post-acquisition sends stay concurrent.
        val gate = sessionGate(id)
        var creator = false
        synchronized(gate) {
            sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
            if (sid.isBlank()) {
                if (sessionAcquiring.add(id)) creator = true
                else {
                    var waited = 0
                    while (sessionAcquiring.contains(id) && waited < 30000) {
                        try { (gate as java.lang.Object).wait(1000) } catch (_: InterruptedException) {}
                        waited += 1000
                    }
                    sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                    if (sid.isBlank() && sessionAcquiring.add(id)) creator = true
                }
            }
        }
        try {
        for (attempt in 0..2) {
            try {
                // Title handoff: only a manual override travels. Auto stubs
                // stay local so opencode titles the fresh session itself;
                // the sync then adopts the genuine title. Sending the
                // fallback would stamp it server-side and we'd "follow"
                // our own echo forever.
                val lockedTitle = session?.title?.takeIf { session?.titleLocked == true }
                accept = OpencodeApi.postChat(
                    prefs.serverUrl(), prefs.token(),
                    chatId = id, sessionID = sid.ifBlank { null }, title = lockedTitle, text = sendText, model = model,
                    attachments = wireFiles, turnId = turnId,
                    backend = try { chatStore.get(id)?.backend } catch (_: Exception) { null },
                    effort = effort,
                    cwd = try { chatStore.get(id)?.cwd?.ifBlank { null } } catch (_: Exception) { null },
                    nativeCommandName = nativeCommandName.ifBlank { null },
                    steer = steer,
                    steerRequired = steerRequired)
                failure = null
                break
            } catch (e: Exception) {
                failure = e
                val m = (e.message ?: "").lowercase()
                // Dead remote session heals by resending fresh: opencode
                // "no session" and backend "session_not_found" alike.
                // The chat keeps its backend; only the dead remote id is
                // dropped (a mismatched id heals the same way).
                if (attempt == 0 && sid.isNotBlank() && ("no session" in m || "session_not_found" in m)) {
                    sid = ""
                    try { chatStore.setRemoteId(id, "") } catch (_: Exception) {}
                    continue
                }
                val retryable = attempt == 0 &&
                    (m.contains("abort") || m.contains("reset") || m.contains("broken pipe") ||
                        m.contains("econnreset") || m.contains("sockettimeout") || m.contains("timeout"))
                if (!retryable) break
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            }
        }
        val adopted = accept?.sessionID.takeUnless { it.isNullOrBlank() }
        if (adopted != null && adopted != sid) {
            sid = adopted
            try { chatStore.setRemoteId(id, adopted) } catch (_: Exception) {}
        }
        } finally {
            if (creator) synchronized(gate) { sessionAcquiring.remove(id); (gate as java.lang.Object).notifyAll() }
        }
        val ok = accept
        val accepted = ok != null && (ok.accepted || !ok.text.isNullOrBlank())
        onSubmitted?.invoke(accepted && (!steerRequired || ok?.steered == true))
        if (ok != null && (ok.accepted || !ok.text.isNullOrBlank())) {
            // Accepted: the outcome arrives over /api/stream (`done` with
            // this turnId) — startStream is already running. Legacy sync
            // reply (older server) settles immediately with the turnId.
            runOnUiThread { syncThread(id) }
            if (!ok.accepted) {
                val replyText = ok.text ?: ""
                runOnUiThread { finishStreamTurn(id, turnId, replyText, "", "") }
            }
        } else {
            val msg = failure?.message ?: "network error"
            // Never reached the Mac: the message waits in the thread and goes out by itself (Outbox).
            val waiting = Outbox.neverSent(failure) &&
                (try { chatStore.userTurnFor(id, turnId) } catch (_: Exception) { null }) != null
            val waitReason = if (waiting) Outbox.PREFIX + Outbox.reason(this, prefs.serverUrl()) else ""
            runOnUiThread {
                pendingTurns[id]?.remove(turnId)
                turnRestore.remove(turnId)
                dropTurnBuf(turnId)
                chatStore.dropPlaceholder(id, turnId)
                if (waiting && chatStore.setUserSendFailed(id, turnId, true, waitReason)) {
                    if (activeSessionId == id) renderThread(id)
                    renderSessions()
                    reconcileTurnState(id)
                    scheduleOutboxRetry()
                    return@runOnUiThread
                }
                if (!chatStore.setUserSendFailed(id, turnId, true, msg)) {
                    chatStore.addMessage(id, ChatMessage(
                        "tool", "send failed ($msg), your text was kept in the box"))
                }
                if (activeSessionId == id) {
                    composerInput.setText(restoreText)
                    renderThread(id)
                }
                renderSessions()
                reconcileTurnState(id)
            }
        }
    }.start()
}
