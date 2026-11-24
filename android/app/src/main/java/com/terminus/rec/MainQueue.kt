package com.terminus.rec

import android.view.View
import java.io.File

/** Waiting turns, steering and send-now. Extension functions of [MainActivity], moved out of it unchanged. */

/** A reply is running and this chat can Send-now a waiting message.
 *  Opencode queueing is app-side (hold + local release), so it needs no
 *  server capability; other backends read the catalog flag. */
internal fun MainActivity.canSteer(id: String): Boolean {
    if (!hasFreshTurn(id)) return false
    val b = try { chatStore.get(id)?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
    if (Prefs.cannonBackend(b) == Prefs.BACKEND_OPENCODE) return true
    return capsForBackend(backendCatalog, b).steer
}

/** Turns waiting behind the running reply, oldest first. App-held
 *  entries (opencode sends behind a running reply) count everywhere;
 *  posted turns count only where the server queues them. */
internal fun MainActivity.waitingTurnIds(id: String): List<String> {
    return try {
        val msgs = chatStore.get(id)?.messages ?: return emptyList()
        val b = try { chatStore.get(id)?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
        waitingTurnIdsOf(
            msgs,
            Prefs.cannonBackend(b) == Prefs.BACKEND_OPENCODE,
            backendWaitingTurns[id]?.toList().orEmpty(),
            waitQueue[id]?.map { it.turnId }.orEmpty(),
            steeredTurnIds,
        )
    } catch (_: Exception) { emptyList() }
}

/** Send-now and discard controls under each message waiting behind the running reply. */
internal fun MainActivity.refreshSendNowLinks() {
    if (!threadWebReady || !threadPageLoaded) return
    val id = activeSessionId
    // Nothing is running here, so nothing is waiting: forget this chat's folded turns.
    if (id != null && steeredTurnIds.isNotEmpty() && !hasFreshTurn(id)) {
        try { chatStore.get(id)?.messages?.forEach { steeredTurnIds.remove(it.turnId) } } catch (_: Exception) {}
    }
    val waiting = if (id != null && isThreadOpen() && canSteer(id)) waitingTurnIds(id) else emptyList()
    val key = (id ?: "") + "|" + waiting.joinToString(",")
    if (key == sendNowKey) return
    sendNowKey = key
    val turns = org.json.JSONArray(waiting).toString()
    val chat = org.json.JSONObject.quote(id ?: "")
    threadWeb.evaluateJavascript(
        "(function(w,c){try{" +
            "document.querySelectorAll('.row.user.queued-message').forEach(function(r){if(w.indexOf(r.getAttribute('data-turn'))<0){r.classList.remove('queued-message');var old=r.querySelector('.sendnow-undo');if(old)old.remove();}});" +
            "document.querySelectorAll('.sendnow').forEach(function(e){if(w.indexOf(e.getAttribute('data-turn'))<0)e.remove();});" +
            "w.forEach(function(t){var r=document.querySelector('.row.user[data-turn=\"'+t+'\"]');if(!r)return;" +
            "r.classList.add('queued-message');if(!r.querySelector('.sendnow-undo')){var u=document.createElement('a');u.className='sendnow-undo';u.href='terminus://discardqueued?chat='+encodeURIComponent(c)+'&turn='+encodeURIComponent(t);u.setAttribute('aria-label','Discard queued message');u.setAttribute('title','Discard queued message');u.innerHTML='<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M6 6l12 12M18 6L6 18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>';r.appendChild(u);}" +
            "var n=r.nextElementSibling;if(n&&n.classList.contains('sendnow'))return;" +
            "var d=document.createElement('div');d.className='sendnow';d.setAttribute('data-turn',t);" +
            "var a=document.createElement('a');a.href='terminus://sendnow?chat='+encodeURIComponent(c)+'&turn='+encodeURIComponent(t);" +
            "a.textContent='Send now';d.appendChild(a);r.parentNode.insertBefore(d,r.nextSibling);});" +
            "}catch(e){}})($turns,$chat);",
        null,
    )
}

/** Discard one waiting message. Local /wait rows are removed immediately;
 *  backend-held turns are removed only after the backend confirms they
 *  were still queued, so tapping × cannot interrupt a turn that started. */
internal fun MainActivity.discardQueuedTurn(chat: String, turn: String) {
    val local = waitQueue[chat]
    val idx = local?.indexOfFirst { it.turnId == turn } ?: -1
    if (idx >= 0 && local != null) {
        local.removeAt(idx)
        if (local.isEmpty()) waitQueue.remove(chat)
        try { WaitStore(this).put(chat, local.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }, it.turnId) }) } catch (_: Exception) {}
        chatStore.discardQueuedTurn(chat, turn)
        afterDiscardQueued(chat)
        return
    }
    val backend = chatBackendOf(chat)
    Thread {
        val cancelled = try { OpencodeApi.postCancelQueued(prefs.serverUrl(), prefs.token(), chat, turn, backend) }
            catch (e: Exception) {
                android.util.Log.d("terminus", "discard queued failed: ${e.message}")
                null
            }
        runOnUiThread {
            if (cancelled == null) {
                toast("Couldn't reach the server; message is still queued")
                return@runOnUiThread
            }
            if (!cancelled) {
                toast("That message has already started and can't be discarded")
                return@runOnUiThread
            }
            chatStore.discardQueuedTurn(chat, turn)
            pendingTurns[chat]?.remove(turn)
            markDeliveredBulk(chat, listOf(turn))
            turnRestore.remove(turn)
            dropTurnBuf(turn)
            afterDiscardQueued(chat)
        }
    }.start()
}

internal fun MainActivity.afterDiscardQueued(chat: String) {
    try { chatStore.flushNow() } catch (_: Exception) {}
    if (activeSessionId == chat) renderThread(chat)
    renderSessions()
    updateStopBtn()
    reconcileTurnState(chat)
    refreshSendNowLinks()
}

/** Release one explicitly queued message with a normal POST now. The row
 *  is reused in place (no second bubble): failure status, retry and edit
 *  attach like any direct send. */
internal fun MainActivity.releaseQueuedTurn(chat: String, turn: String, steer: Boolean = false): Boolean {
    val q = waitQueue[chat] ?: return false
    val idx = q.indexOfFirst { it.turnId == turn }
    if (idx < 0) return false
    val p = q.removeAt(idx)
    try { WaitStore(this).put(chat, q.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
    updateStopBtn()
    sendVisibleTurn(chat, p.text, p.files, displayText = p.text, appendUser = false, reuseUserTurnId = p.turnId, steer = steer)
    return true
}

/** Tap on "Send now": ask the server to fold that waiting message into the running reply. */
internal fun MainActivity.sendNowTap(chat: String, turn: String) {
    // The link goes at once: a tap means "go", never a second bubble.
    // If it didn't take, it comes back below.
    steeredTurnIds.add(turn)
    refreshSendNowLinks()
    // App-held queue: release locally with a normal POST now. Server-held
    // (codex): fold via /api/steer.
    if ((waitQueue[chat]?.any { it.turnId == turn }) == true) {
        val backend = chatBackendOf(chat)
        val steer = backend == Prefs.BACKEND_CODEX && canSteer(chat)
        runOnUiThread {
            if (!releaseQueuedTurn(chat, turn, steer)) steeredTurnIds.remove(turn)
            refreshSendNowLinks()
        }
        return
    }
    val backend = chatBackendOf(chat)
    Thread {
        val folded = try {
            OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), chat, turn, backend)
        } catch (e: Exception) {
            android.util.Log.d("terminus", "send now failed: ${e.message}")
            false
        }
        runOnUiThread {
            if (folded) steeredTurnIds.add(turn) else { steeredTurnIds.remove(turn); toast("Couldn't send now, it stays in line") }
            refreshSendNowLinks()
        }
    }.start()
}

internal fun MainActivity.markDelivered(id: String, turnId: String): Boolean {
    // Check-then-add, genuinely atomic (synchronized: the stream thread
    // and UI thread race duplicate redeliveries here — an unsynchronized
    // check passes twice and appends twin rows). A turnId replay is a
    // hard dupe; anything else proceeds — messageID twins are handled
    // downstream by the settledMessages merge rule. No nested locks
    // anywhere in this file, so no ordering hazard.
    if (turnId.isBlank()) return true
    synchronized(deliveredTurns) {
        val now = System.currentTimeMillis()
        val turns = deliveredTurns.getOrPut(id) { mutableMapOf() }
        turns.entries.removeAll { now - it.value > deliveredTtlMs }
        if (turns.containsKey(turnId)) return false
        turns[turnId] = now
        return true
    }
}

/** Pour known in-flight turns into dedupe (stop/settle paths pour, never
 *  check): same TTL + prune as the checked path. */
internal fun MainActivity.markDeliveredBulk(id: String, turnIds: List<String>) {
    synchronized(deliveredTurns) {
        val now = System.currentTimeMillis()
        val turns = deliveredTurns.getOrPut(id) { mutableMapOf() }
        for (t in turnIds) if (t.isNotBlank()) turns[t] = now
        turns.entries.removeAll { now - it.value > deliveredTtlMs }
    }
}

/** Claim a settled messageID for the merge rule (cap 50/chat). Shared
 *  by every settle path so late dones and replays see the same claims. */
internal fun MainActivity.claimMessageId(id: String, messageID: String) {
    if (messageID.isBlank()) return
    synchronized(settledMessages) {
        val sm = settledMessages.getOrPut(id) { mutableSetOf() }
        sm.add(messageID)
        while (sm.size > 50) { sm.remove(sm.first()); }
    }
}

/** Serial queue pipe: visible immediately, POSTs only when
 *  the running turn settles — one reply per message. */
internal fun MainActivity.enqueueWait(id: String, wireText: String, attaches: List<java.io.File>, displayText: String) {
    val files = attaches.toList()
    val turnId = newTurnId()
    // Visible now so ordering never slides behind the reply. The row
    // carries its turn id from birth so a Send-now link can release
    // exactly this message, and drain keeps the same id.
    chatStore.addMessage(id, ChatMessage("user", wireText, attachment = files.firstOrNull()?.absolutePath, moreAttachments = files.drop(1).map { it.absolutePath }, turnId = turnId))
    val s = chatStore.get(id)
    if (wireText.startsWith(">") && displayText.isNotBlank() && s?.title == "new chat" && !(s.titleLocked)) {
        chatStore.rename(id, displayText.trim().take(42).lineSequence().first())
    }
    if (displayText.isBlank() && files.isNotEmpty() && s?.title == "new chat") {
        chatStore.rename(id, "📎 ${AttachName.friendly(files.first().name).take(40)}")
    }
    waitQueue.getOrPut(id) { mutableListOf() }.add(PendingSend(wireText, files, "", turnId))
    try { WaitStore(this).put(id, waitQueue[id]!!.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
    clearQuote()
    composerInput.setText("")
    composerInput.hint = "Message (/ for commands)"
    dropPendingDraft()
    chatStore.setDraft(id, "")
    pendingAttaches.clear()
    hideAttachStrip()
    slashList.visibility = View.GONE
    if (activeSessionId == id) renderThread(id)
    renderSessions()
    updateStopBtn()
}

/** Single reconciler: a waiting backlog with no active turn must drain;
 *  hint + stop button always reflect the result. Call at the end of
 *  every settle/failure path so stale UI cannot survive a missed branch. */
internal fun MainActivity.reconcileTurnState(id: String) {
    if (!(waitQueue[id]?.isEmpty() ?: true) && !isTurnActive(id)) {
        drainWaitQueue(id)
    } else {
        updateStopBtn()
    }
}

/** Stop-flush: deliver the app-held backlog to the backend, then stop.
 *  Codex needs explicit steer acknowledgements because an accepted chat
 *  POST can otherwise sit in Terminus's queue and be canceled by Stop. */
internal fun MainActivity.flushWaitQueue(id: String, afterDispatch: ((Boolean) -> Unit)? = null) {
    val backend = chatBackendOf(id)
    val isCodex = backend == Prefs.BACKEND_CODEX
    val localIds = waitQueue[id]?.map { it.turnId }?.toSet().orEmpty()
    val postedCodexTurns = if (isCodex) waitingTurnIds(id).filter { it !in localIds } else emptyList()
    val pending = waitQueue.remove(id) ?: mutableListOf()
    try { WaitStore(this).clear(id) } catch (_: Exception) {}
    updateStopBtn()
    if (pending.isEmpty() && postedCodexTurns.isEmpty()) {
        afterDispatch?.invoke(true)
        return
    }
    if (chatStore.get(id) == null) {
        afterDispatch?.invoke(false)
        return
    }
    if (activeSessionId == id) renderThread(id)
    if (isCodex) {
        // Promote existing server-queued messages first, in transcript
        // order. Then submit each local item and steer it before moving
        // to the next so concurrent turn/steer calls cannot reorder them.
        Thread {
            var allSubmitted = true
            for (turn in postedCodexTurns) {
                val ok = try { OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), id, turn, backend) }
                catch (_: Exception) { false }
                if (ok) steeredTurnIds.add(turn) else {
                    allSubmitted = false
                    break
                }
            }
            fun sendAt(index: Int) {
                if (index >= pending.size) {
                    afterDispatch?.invoke(allSubmitted)
                    return
                }
                if (!allSubmitted) {
                    val remaining = pending.drop(index).toMutableList()
                    if (remaining.isNotEmpty()) {
                        waitQueue[id] = remaining
                        try { WaitStore(this@flushWaitQueue).put(id, remaining.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
                    }
                    runOnUiThread { updateStopBtn() }
                    afterDispatch?.invoke(false)
                    return
                }
                val p = pending[index]
                val turnId = if (p.turnId.isNotBlank()) p.turnId
                    else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
                try {
                    startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId,
                        onSubmitted = { accepted ->
                            if (!accepted) {
                                allSubmitted = false
                                sendAt(index + 1)
                            } else {
                                val steered = try { OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), id, turnId, backend) }
                                    catch (_: Exception) { false }
                                if (steered) steeredTurnIds.add(turnId) else allSubmitted = false
                                sendAt(index + 1)
                            }
                        })
                } catch (_: Exception) {
                    allSubmitted = false
                    sendAt(index + 1)
                }
            }
            sendAt(0)
        }.start()
    } else {
        val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
        val allSubmitted = java.util.concurrent.atomic.AtomicBoolean(true)
        val dispatchDone: (Boolean) -> Unit = { success ->
            if (!success) allSubmitted.set(false)
            if (remaining.decrementAndGet() == 0) afterDispatch?.invoke(allSubmitted.get())
        }
        for (p in pending) {
            val turnId = if (p.turnId.isNotBlank()) p.turnId
                else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
            try {
                startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId, onSubmitted = dispatchDone)
            } catch (_: Exception) { dispatchDone(false) }
        }
    }
    renderSessions()
}

/** Drain backlog in order, all at once: every waiting message POSTs in
 *  quick succession instead of one-per-settle, then each settles in
 *  place on its own turn. A failed POST re-queues that message and the
 *  rest behind it. */
internal fun MainActivity.drainWaitQueue(id: String) {
    val pending = waitQueue.remove(id) ?: return
    try { WaitStore(this).clear(id) } catch (_: Exception) {}
    updateStopBtn()
    if (pending.isEmpty() || chatStore.get(id) == null) return
    if (isTurnActive(id)) {
        // Raced a new turn — put it back.
        waitQueue[id] = pending.toMutableList()
        try { WaitStore(this).put(id, pending.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
        updateStopBtn()
        return
    }
    if (activeSessionId == id) renderThread(id)
    for ((index, p) in pending.withIndex()) {
        // The queued bubble was stored without a turn id; stamp the drained
        // turn's id now so failure status, retry and edit attach to it like
        // any direct send — otherwise its failure note can never attach and
        // floats as a standalone row forever. Rows queued with an id already
        // (Send-now release targets) keep it.
        val turnId = if (p.turnId.isNotBlank()) p.turnId
            else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
        try {
            startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId)
        } catch (_: Exception) {
            val remaining = pending.drop(index).toMutableList()
            waitQueue[id] = remaining
            try { WaitStore(this).put(id, remaining.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
            updateStopBtn()
            return
        }
    }
    renderSessions()
}

internal fun MainActivity.sessionGate(id: String): java.lang.Object = synchronized(sessionGates) {
    sessionGates.getOrPut(id) { java.lang.Object() }
}
