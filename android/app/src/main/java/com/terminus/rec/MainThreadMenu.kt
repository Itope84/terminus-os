package com.terminus.rec

import android.content.Intent
import java.io.File

/** The thread menu: export, fork, stopping and settling turns. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.threadMenuDialog() {
    val id = activeSessionId ?: return
    val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return
    val goalState = goalCache?.let { GoalUi.manageSub(it) } ?: ""
    showSelectSheet(s.title.ifBlank { "untitled" }, listOf(
        SelectRow("Rename", s.title.ifBlank { "untitled" }.take(60), action = { renameDialog(s) }),
        SelectRow(
            if (s.pinned) "Unpin" else "Pin",
            "keep at top",
            checked = s.pinned,
            action = {
                chatStore.setPinned(s.id, !s.pinned)
                renderSessions()
                renderThread(s.id)
            },
        ),
        SelectRow("Export chat", "save as a text file", action = { exportChat(s) }),
        SelectRow("Fork", "copy history to a new chat", action = { forkChat(s.id, null) }),
        SelectRow("Set goal", goalState.ifBlank { "loop until done" }, action = { goalDialog() }),
        SelectRow("Delete", action = { deleteSessionNow(s) }),
    ))
}

/** Plain-text export: one block per user, assistant or error message, handed to the share sheet as a .txt. */
internal fun MainActivity.exportChat(s: ChatSession) {
    pruneShareExports()
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    val who = backendDisplayName(s.backend)
    val body = buildString {
        append(s.title.ifBlank { "untitled" }).append('\n')
        append("Backend: ").append(who).append(" · Model: ").append(s.model.ifBlank { "default" }).append('\n')
        append("Exported: ").append(stamp.format(java.util.Date())).append("\n\n")
        val kept = s.messages.filter { it.role == "user" || it.role == "assistant" || it.role == "error" }
        for (m in kept) {
            val text = m.text.trim()
            if (text.isEmpty() && m.allAttachments.isEmpty()) continue
            val label = when (m.role) {
                "user" -> "You"
                "assistant" -> who
                else -> "Error"
            }
            append("[").append(stamp.format(java.util.Date(m.ts))).append("] ").append(label).append(":\n")
            if (text.isNotEmpty()) append(text).append('\n')
            for (a in m.allAttachments) append("(attachment: ").append(a.substringAfterLast('/')).append(")\n")
            append('\n')
        }
    }
    Thread {
        try {
            val dir = File(cacheDir, "share-out").apply { mkdirs() }
            val safe = s.title.ifBlank { "chat" }.take(60).replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "chat" }
            val file = File(dir, "${System.currentTimeMillis()}_$safe.txt")
            file.writeText(body)
            runOnUiThread {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, s.title)
                        clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Export chat").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, "Couldn't open the share sheet", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                android.widget.Toast.makeText(this, "Couldn't write the export", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

internal fun MainActivity.forkChat(id: String, messageID: String?) {
    val s = chatStore.get(id) ?: return
    if (!capsForBackend(backendCatalog, s.backend).fork) {
        val note = ChatMessage("tool", "${backendFailurePrefix(s.backend)}fork is unavailable")
        chatStore.addMessage(id, note)
        if (activeSessionId == id) renderThread(id)
        renderSessions()
        return
    }
    val sid = s.remoteId
    val fork = chatStore.create(s.backend, s.model)
    chatStore.rename(fork.id, s.title + " (fork)", locked = true)
    s.messages.filter { !(it.role == "assistant" && it.turnId.isNotBlank() && !it.settled) }.forEach { chatStore.addMessage(fork.id, it.copy()) }
    // The twin must exist on disk before it opens, not after a debounce.
    try { chatStore.flushNow() } catch (_: Exception) { }
    openThread(fork.id)
    if (sid.isBlank()) {
        val local = ChatMessage("tool",
            "fork is local-only (nothing sent yet), history copied, agent starts fresh")
        chatStore.addMessage(fork.id, local)
        if (activeSessionId == fork.id && !appendRowDom(fork.id, local)) renderThread(fork.id)
        return
    }
    Thread {
        try {
            val forkSid = OpencodeApi.postFork(prefs.serverUrl(), prefs.token(), sid, messageID, s.backend, fork.id)
            runOnUiThread {
                if (forkSid.isNotBlank()) chatStore.setRemoteId(fork.id, forkSid)
                postNote(fork.id, "event", "forked with agent context")
            }
        } catch (e: Exception) {
            runOnUiThread {
                val local = ChatMessage("tool",
                    "fork is local-only (${e.message ?: "server fork failed"}), history copied, agent starts fresh")
                chatStore.addMessage(fork.id, local)
                if (activeSessionId == fork.id && !appendRowDom(fork.id, local)) renderThread(fork.id)
            }
        }
    }.start()
}

/** Settle verified-dead turns without a remote kill. onlyStale=false is
 *  the explicit user tap or an authoritative 404: partials rescue,
 *  blanks drop, late dones barred like a successful stop, note posted.
 *  onlyStale=true (automatic idle/ticker paths) never amputates: server
 *  idle plus 90s silence is NOT evidence of death for long coding turns
 *  in quiet tool gaps — amputating pours the turn into delivered and
 *  drops its placeholder, so the eventual reply has nowhere to land.
 *  Instead each target reconciles by exact turnId: settled ones finish
 *  normally, everything else stands untouched (no note, no pour, no
 *  drop) with retry available. */
internal fun MainActivity.settleDeadTurns(id: String, onlyStale: Boolean, note: String) {
    val live = try { chatStore.liveTurns(id) } catch (_: Exception) { emptyList() }
    // Automatic paths arrive pre-verified (working == false); the tap
    // path verified via the 404. Either way the target decision is the
    // unit-tested pure rule, never ad-hoc filtering at the call site.
    val targets = TurnActivity.autoSettleTargets(live, System.currentTimeMillis(), false, onlyStale)
    if (targets.isEmpty()) return
    if (onlyStale) {
        for (t in targets) reconcileTurn(id, t)
        return
    }
    markDeliveredBulk(id, targets.toList())
    targets.forEach { dropTurnBuf(it) }
    pendingTurns[id]?.clear()
    for (t in targets) {
        val partial = try { chatStore.placeholderText(id, t) } catch (_: Exception) { null }
        if (partial != null) {
            try { chatStore.settleTurn(id, t, partial, stepsJson(progressLive[id] ?: emptyList())) } catch (_: Exception) {}
        } else {
            try { chatStore.dropPlaceholder(id, t) } catch (_: Exception) {}
        }
    }
    try { chatStore.flushAsync() } catch (_: Exception) {}
    settleProgress(id)
    val cleared = ChatMessage("tool", note)
    chatStore.addMessage(id, cleared)
    if (activeSessionId == id && !appendRowDom(id, cleared)) renderThread(id)
    renderSessions()
    updateStopBtn()
    updateStopBtn()
    if (!(waitQueue[id]?.isEmpty() ?: true)) drainWaitQueue(id)
}

/** One-shot dead-turn verification for the ticker: a confirmed-idle
 *  session means nothing will ever arrive, so stale orphans settle on
 *  their own. Working or unverifiable sessions keep observing. */
/** Deliver orphans already SETTLED server-side whose `done` never
 *  arrived (dead stream, locally-closed turn). Only registry-confirmed
 *  settled turns with real text route through the normal path; the
 *  caller still runs its stale-settle after (it re-reads live turns,
 *  so delivered ones are never double-touched). Safe off the UI
 *  thread; transport failures leave orphans for the settle. */
internal fun MainActivity.recoverOrphanTexts(id: String) {
    val orphans = try {
        chatStore.liveTurns(id).map { it.first }.filter { it.isNotBlank() }
    } catch (_: Exception) { return }
    for (t in orphans) {
        try {
            val r = OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), id, t, chatBackendOf(id))
            // Error-only settlements count too: the error note must reach
            // the thread, not dissolve into a generic receipt.
            if (r.settled && (r.text.isNotBlank() || r.error.isNotBlank())) {
                val ft = r.text; val fe = r.error; val fm = r.messageID
                runOnUiThread { finishStreamTurn(id, t, ft, fe, fm, r.code) }
            }
        } catch (_: Exception) {
            // Unknown/unreachable: left for the stale-settle.
        }
    }
}

internal fun MainActivity.verifyStopIdle(id: String) {
    try {
        val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
        if (sid.isBlank()) return
        val working = OpencodeApi.fetchProgressWorking(prefs.serverUrl(), prefs.token(), id, sid, chatBackendOf(id))
        if (working == false) {
            recoverOrphanTexts(id)
            runOnUiThread { settleDeadTurns(id, onlyStale = true, "cleared, nothing was running") }
        }
    } catch (_: Exception) {
    } finally {
        autoVerifying.remove(id)
    }
}

internal fun MainActivity.stopBackendTurn(id: String, onFinished: (() -> Unit)? = null) {
    val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
    if (sid.isBlank()) {
        postNote(id, "tool", "nothing running, nothing sent yet")
        onFinished?.invoke()
        return
    }
    Thread {
        try {
            OpencodeApi.postStop(prefs.serverUrl(), prefs.token(), sid, chatBackendOf(id), id)
            runOnUiThread {
                // Cancelled: drop every pending placeholder so no late
                // done can resurrect a reply; late dones are ignored.
                val inFlight = pendingTurns[id]?.toList() ?: emptyList()
                markDeliveredBulk(id, inFlight)
                inFlight.forEach { dropTurnBuf(it) }
                pendingTurns[id]?.clear()
                chatStore.dropAllPlaceholders(id)
                try { chatStore.flushNow() } catch (_: Exception) {}
                settleProgress(id)
                idleStreamHandoff(id)
                val stopped = ChatMessage("event", "interrupted")
                chatStore.addMessage(id, stopped)
                if (activeSessionId == id && !appendRowDom(id, stopped)) renderThread(id)
                renderSessions()
                updateStopBtn()
                updateStopBtn()
                onFinished?.invoke()
            }
        } catch (e: Exception) {
            runOnUiThread {
                val msg = e.message ?: "network error"
                if (msg.contains("no session", ignoreCase = true) ||
                    msg.contains("bad sessionid", ignoreCase = true)) {
                    settleDeadTurns(id, onlyStale = false, "nothing was running, cleared")
                    onFinished?.invoke()
                    return@runOnUiThread
                }
                postNote(id, "tool", "stop failed ($msg), still checking")
                updateStopBtn()
                onFinished?.invoke()
            }
        }
    }.start()
}
