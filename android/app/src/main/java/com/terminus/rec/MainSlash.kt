package com.terminus.rec

import android.view.View

/** Slash commands and their results. Extension functions of [MainActivity], moved out of it unchanged. */

/** Slash autocomplete combines Terminus actions with the active backend's
 *  native actions. Backend commands are fetched on demand and never leak
 *  across chats using different backends. */
internal fun MainActivity.mergedCommands(): List<SlashCommand> {
    val seen = LinkedHashMap<String, SlashCommand>()
    ChatStore.COMMANDS.forEach { seen[it.name.lowercase()] = it }
    if (liveCommandsBackend == activeBackend()) {
        liveCommands.forEach { seen.putIfAbsent(it.name.lowercase(), it) }
    }
    if (activeBackend() == "codex") {
        codexCommands.forEach { seen.putIfAbsent(it.name.lowercase(), it) }
    }
    return seen.values.toList()
}

/** Backend commands and skills, refetched when the backend changes or the list is 5 min old, so new
 *  skills show up without a restart. A failed fetch keeps the old list and is retried on the next "/". */
internal fun MainActivity.refreshCommands(then: (() -> Unit)? = null) {
    val backend = activeBackend()
    val fresh = liveCommandsBackend == backend && System.currentTimeMillis() - liveCommandsAt < COMMANDS_TTL_MS
    if (fresh) { then?.invoke(); return }
    if (commandsLoading && then == null) return
    commandsLoading = true
    Thread {
        val fetched = try { OpencodeApi.fetchCommands(prefs.serverUrl(), prefs.token(), backend) } catch (_: Exception) { null }
        runOnUiThread {
            if (fetched != null && activeBackend() == backend) {
                liveCommands = fetched
                liveCommandsBackend = backend
                liveCommandsAt = System.currentTimeMillis()
            }
            commandsLoading = false
            if (then != null) then() else renderSlashHints(composerInput.text.toString())
        }
    }.start()
}

internal fun MainActivity.renderSlashHints(t: String) {
    if (completingSlash) return
    if (!isThreadOpen() || !t.startsWith("/")) {
        slashList.visibility = View.GONE
        return
    }
    // Args started: the token is complete, hide the list instead of
    // cluttering while the user types arguments.
    if (t.contains(" ") || t.contains("\n")) {
        slashList.visibility = View.GONE
        return
    }
    val q = t.split(" ").first().lowercase()
    refreshCommands()
    val matches = mergedCommands().filter { it.name.lowercase().startsWith(q) }
    if (matches.isEmpty()) {
        slashList.visibility = View.GONE
        return
    }
    val local = matches.filter { localSlashCommands.contains(it.name.lowercase()) }
    val skills = matches.filter { it.skill && !localSlashCommands.contains(it.name.lowercase()) }
    val backend = matches.filterNot { it.skill || localSlashCommands.contains(it.name.lowercase()) }
    val rows = mutableListOf<String>()
    val rowCommands = mutableListOf<SlashCommand?>()
    if (local.isNotEmpty()) {
        rows += "APP COMMANDS"
        rowCommands += null
        local.forEach { cmd ->
            rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
            rowCommands += cmd
        }
    }
    val backendCommandsKnown = liveCommandsBackend == activeBackend()
    if (backend.isNotEmpty() || backendCommandsKnown) {
        val backendLabel = when (activeBackend()) {
            "codex" -> "CODEX COMMANDS"
            "opencode" -> "OPENCODE COMMANDS"
            else -> "${activeBackend().uppercase()} COMMANDS"
        }
        rows += backendLabel
        rowCommands += null
        if (backend.isEmpty()) {
            rows += if (activeBackend() == "opencode") "  No custom commands available" else "  No backend commands available"
            rowCommands += null
        } else {
            backend.forEach { cmd ->
                rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
                rowCommands += cmd
            }
        }
    }
    if (skills.isNotEmpty()) {
        rows += "SKILLS"
        rowCommands += null
        skills.forEach { cmd ->
            rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
            rowCommands += cmd
        }
    }
    // Tab/enter completes the first row the user can see, in list order.
    slashShown = rowCommands.filterNotNull()
    slashRowCommands = rowCommands
    slashAdapter.clear()
    slashAdapter.addAll(rows)
    slashAdapter.notifyDataSetChanged()
    slashList.visibility = View.VISIBLE
}

/** Tap / enter completion: swap the slash token, keep any typed args. */
internal fun MainActivity.completeSlash(cmd: SlashCommand) {
    val cur = composerInput.text.toString()
    val rest = if (cur.contains(" ")) cur.substringAfter(" ").trim() else ""
    val done = cmd.name + if (rest.isNotBlank()) " $rest" else " "
    completingSlash = true
    try {
        composerInput.setText(done)
        composerInput.setSelection(done.length)
    } finally {
        completingSlash = false
    }
    composerInput.requestFocus()
}

internal fun MainActivity.handleCommand(id: String, raw: String, retriedCommand: Boolean = false) {
    val parts = raw.split("\\s+".toRegex(), limit = 2)
    val cmd = parts[0].lowercase()
    val arg = parts.getOrNull(1)?.trim() ?: ""
    val s = chatStore.get(id)
    when (cmd) {
        "/new" -> openDraft()
        "/search" -> {
            closeThread()
            searchOpen = true
            sessionSearch.setText(arg)
            sessionQuery = arg
            sessionSearch.visibility = View.VISIBLE
            searchIcon.setImageResource(R.drawable.ic_close)
            if (arg.isBlank()) {
                sessionSearch.requestFocus()
                sessionSearch.post {
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(sessionSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            renderSessions()
        }
        "/fetch" -> {
            // No special fetch path: the URL goes to the agent as a
            // normal message, which fetches with its own tools.
            if (arg.isBlank()) {
                postNote(id, "tool", "Usage: /fetch <url>")
            } else {
                sendVisibleTurn(id, "Fetch and summarize this URL: $arg", emptyList(), "/fetch $arg")
            }
        }
        "/think" -> {
            // No separate careful mode: the question goes to the agent
            // as a normal message with an explicit thoroughness ask.
            if (arg.isBlank()) {
                postNote(id, "tool", "Usage: /think <question>")
            } else {
                sendVisibleTurn(id, "Think carefully and answer thoroughly: $arg", emptyList(), "/think $arg")
            }
        }
        "/clear" -> {
            // Terminal clear: settled history goes, the session and
            // live turns stay, server untouched. In-flight replies
            // land on their standing placeholders as normal.
            val live = try { pendingTurns[id]?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
            chatStore.clearHistory(id, live)
            postNote(id, "event", "history cleared")
            try { chatStore.markRead(id) } catch (_: Exception) {}
            if (activeSessionId == id) renderThread(id)
            renderSessions()
        }
        "/stop" -> {
            val hasLocalWaiting = !(waitQueue[id]?.isEmpty() ?: true)
            val hasPostedCodexWaiting = chatBackendOf(id) == Prefs.BACKEND_CODEX && waitingTurnIds(id).isNotEmpty()
            if (hasLocalWaiting || hasPostedCodexWaiting) {
                if (!stopInProgress.add(id)) return
                // Flush queued messages to the backend and wait for every
                // chat POST response before issuing the stop request.
                flushWaitQueue(id) { allSubmitted ->
                    runOnUiThread {
                        if (allSubmitted) {
                            stopBackendTurn(id) { stopInProgress.remove(id) }
                        } else {
                            stopInProgress.remove(id)
                            postNote(id, "tool", "some queued messages couldn't reach the backend; stop wasn't sent. Retry the failed messages, then stop")
                            updateStopBtn()
                        }
                    }
                }
                return
            }
            val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
            if (sid.isBlank()) {
                postNote(id, "tool", "nothing running, nothing sent yet")
            } else Thread {
                try {
                    OpencodeApi.postStop(prefs.serverUrl(), prefs.token(), sid, chatBackendOf(id), id)
                    runOnUiThread {
                        // Cancelled: drop every pending placeholder so no
                        // late `done` can resurrect a reply; late dones
                        // arrive with known turnIds and are ignored.
                        // Mark all known in-flight turns delivered so their
                        // late dones are ignored on arrival.
                        val inFlight = pendingTurns[id]?.toList() ?: emptyList()
                        markDeliveredBulk(id, inFlight)
                        inFlight.forEach { dropTurnBuf(it) }
                        pendingTurns[id]?.clear()
                        chatStore.dropAllPlaceholders(id)
                        // Explicit user kill: the drop must be durable now,
                        // not after the write-debounce.
                        try { chatStore.flushNow() } catch (_: Exception) {}
                        settleProgress(id)
                        // Shared handoff, not a bare stop: another chat
                        // with live turns adopts the stream, otherwise it
                        // sleeps. Stopping one chat must not strand the rest.
                        idleStreamHandoff(id)
                        val stopped = ChatMessage("event", "interrupted")
                        chatStore.addMessage(id, stopped)
                        if (activeSessionId == id && !appendRowDom(id, stopped)) renderThread(id)
                        renderSessions()
                        updateStopBtn()
                        updateStopBtn()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        val msg = e.message ?: "network error"
                        // Authoritative "nothing there" (server 404: the
                        // session is gone, e.g. killed by an opencode
                        // restart mid-turn): keeping the placeholder
                        // would stick Stop forever with no remote turn
                        // to stop. Settle orphans locally — partial
                        // text is kept as the reply — and bar late
                        // dones exactly like a successful stop.
                        // Anything else (503, transport) is unknown:
                        // keep observing for the eventual `done`.
                        if (msg.contains("no session", ignoreCase = true) ||
                            msg.contains("bad sessionid", ignoreCase = true)) {
                            settleDeadTurns(id, onlyStale = false, "nothing was running, cleared")
                            return@runOnUiThread
                        }
                        postNote(id, "tool", "stop failed ($msg), still checking")
                        updateStopBtn()
                    }
                }
            }.start()
        }
        "/compact" -> {
            val backend = chatBackendOf(id) ?: prefs.defaultBackend()
            if (backendCatalog == null && Prefs.cannonBackend(backend) != Prefs.BACKEND_OPENCODE) {
                // No catalog yet (app just opened, server was restarting): the fallback caps say "no" for
                // everything, which is not an answer. Ask the server once, then run the command.
                Thread {
                    val cat = try { OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token()) } catch (_: Exception) { null }
                    runOnUiThread {
                        if (cat != null) { backendCatalog = cat; handleCommand(id, raw) }
                        else postNote(id, "event", "can't reach the server to check compact, try again in a moment")
                    }
                }.start()
                return
            }
            if (!capsForBackend(backendCatalog, backend).compact) {
                postNote(id, "event", "${backendFailurePrefix(backend)}has no /compact (its sessions can't be summarised from here)")
                return
            }
            val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
            if (sid.isBlank()) {
                postNote(id, "event", "nothing to compact, nothing sent yet")
            } else {
                // Compaction emits no turn events, so the stream stays
                // quiet throughout — an optimistic row is the only feed.
                val working = ChatMessage("event", "compacting context…")
                chatStore.addMessage(id, working)
                if (activeSessionId == id && !appendRowDom(id, working)) renderThread(id)
                Thread {
                    var serverMsg = ""
                    var serverSummary = ""
                    var failWhy = ""
                    try {
                        val r = OpencodeApi.postCompact(prefs.serverUrl(), prefs.token(), sid, backend, id)
                        serverMsg = r.first
                        serverSummary = r.second
                    } catch (e: Exception) {
                        // The server says why (a reply still running, session gone, nothing to compact):
                        // show that instead of a bare "failed".
                        failWhy = (e.message ?: "").removePrefix("/api/compact:").trim().take(120)
                    }
                    runOnUiThread {
                        // Center-stage receipt: the full summary as its own
                        // divider + markdown row. No summary (older server
                        // or failed read-back) falls back to the short
                        // receipt tool row.
                        val done = if (serverSummary.isNotBlank()) ChatMessage("compact", serverSummary)
                            else ChatMessage("event", serverMsg.ifBlank { if (failWhy.isNotBlank()) "compaction failed ($failWhy)" else "compaction failed" })
                        // The live feed may have already posted its own
                        // outcome row; the manual receipt (with summary)
                        // replaces it rather than stacking.
                        chatStore.dropLastMatching(id, "compacting context…")
                        chatStore.dropLastMatching(id, "compacting context (auto)…")
                        chatStore.dropLastMatching(id, "compacting context (manual)…")
                        chatStore.dropLastMatching(id, "context compacted, history kept")
                        chatStore.dropLastMatching(id, "context compacted (auto), history kept")
                        chatStore.dropLastMatching(id, "context compacted (manual), history kept")
                        chatStore.addMessage(id, done)
                        if (activeSessionId == id && !appendRowDom(id, done)) renderThread(id)
                        renderSessions()
                    }
                }.start()
            }
        }
        "/delete" -> { chatStore.delete(id); closeThread() }
        "/sessions" -> closeThread()
        "/models" -> modelDialog(arg)
        "/model" -> {
            if (arg.isBlank()) {
                val cur = s?.model?.takeIf { it.contains("/") }
                chatStore.addMessage(id, ChatMessage("tool",
                    "Model for this chat: ${cur?.let { midId(it) } ?: "Default (server default)"}"))
                renderThread(id)
            } else if (s == null) {
                renderThread(id)
            } else {
                val target = s
                Thread {
                    val b = activeBackend()
                    var list = modelsForBackend(b)
                    var catalogUnavailable = false
                    if (list.isEmpty()) {
                        try {
                            list = OpencodeApi.fetchModels(prefs.serverUrl(), prefs.token(), b)
                        } catch (_: Exception) {
                            catalogUnavailable = true
                        }
                    }
                    val hit = list.firstOrNull { it.id.equals(arg, true) }
                        ?: list.firstOrNull { it.short.equals(arg, true) }
                    runOnUiThread {
                        if (catalogUnavailable) {
                            remoteModelsByBackend.remove(b)
                            unavailableModelCatalogs.add(b)
                        } else {
                            remoteModelsByBackend[b] = list
                            unavailableModelCatalogs.remove(b)
                        }
                        if (hit != null) {
                            chatStore.setModel(id, hit.id)
                            renderThread(id)
                        } else if (list.isNotEmpty()) {
                            chatStore.addMessage(id, ChatMessage("tool",
                                "Unknown model \"$arg\". Try /models to browse ${list.size} models."))
                            renderThread(id)
                        } else {
                            chatStore.addMessage(id, ChatMessage("tool",
                                if (unavailableModelCatalogs.contains(b))
                                    "Live model list unavailable for $b. Check the server, then try /models again."
                                else "No models are currently available for $b."))
                            renderThread(target.id)
                        }
                        renderSessions()
                    }
                }.start()
            }
        }
        "/current" -> {
            val cur = s?.model?.takeIf { it.contains("/") }
            chatStore.addMessage(id, ChatMessage("tool",
                "Model for this chat: ${cur?.let { midId(it) } ?: "Default (server default)"}"))
            renderThread(id)
        }
        "/cwd" -> {
            // Per-chat working directory for codex turns. Blank shows
            // current; anything else is any folder (the server expands ~
            // and re-resolves every turn).
            if (s == null) {
                renderThread(id)
            } else if (arg.isBlank()) {
                val cur = s.cwd.ifBlank { "~/Desktop" }
                chatStore.addMessage(id, ChatMessage("tool", "Working dir for this chat: $cur"))
                renderThread(id)
            } else {
                // Shape check only (~/..., or absolute); the server owns
                // home expansion.
                val want = arg.trim()
                if (!want.startsWith("~/") && !want.startsWith("/")) {
                    chatStore.addMessage(id, ChatMessage("tool",
                        "Working dir must start with ~/ or /, got: ${want.take(80)}"))
                    renderThread(id)
                } else {
                    chatStore.setCwd(id, want)
                    renderThread(id)
                    renderSessions()
                }
            }
        }
        "/fork" -> {
            if (s != null) forkChat(id, arg.trim().ifBlank { null })
        }
        "/usage" -> {
            if (chatBackendOf(id) == "codex") {
                if (arg.isNotBlank()) {
                    chatStore.addMessage(id, ChatMessage("tool", "Usage: /usage"))
                    renderThread(id)
                } else {
                    Thread {
                        val result = try {
                            OpencodeApi.postBackendCommand(prefs.serverUrl(), prefs.token(), "codex", cmd)
                        } catch (e: Exception) { "Codex command failed: ${e.message ?: "network error"}" }
                        runOnUiThread { appendCommandResult(id, result) }
                    }.start()
                }
            } else if (chatBackendOf(id) == "claude" && liveCommands.any { liveCommandsBackend == "claude" && it.name.equals(cmd, true) }) {
                // Claude's own /usage (plan windows, reset times) runs through the CLI like any native command.
                sendVisibleTurn(id, raw, emptyList(), raw)
            } else showChatUsageCommand(id, cmd)
        }
        "/cost", "/context" -> showChatUsageCommand(id, cmd)
        "/status" -> showBackendStatusCommand(id)
        "/resets", "/redeem" -> {
            if (chatBackendOf(id) != "codex") {
                chatStore.addMessage(id, ChatMessage("tool", "$cmd is available in Codex chats."))
                renderThread(id)
            } else if (cmd != "/redeem" && arg.isNotBlank()) {
                chatStore.addMessage(id, ChatMessage("tool", "Usage: $cmd"))
                renderThread(id)
            } else {
                Thread {
                    val result = try {
                        OpencodeApi.postBackendCommand(prefs.serverUrl(), prefs.token(), "codex", cmd, arg)
                    } catch (e: Exception) { "Codex command failed: ${e.message ?: "network error"}" }
                    runOnUiThread {
                        val message = ChatMessage("tool", result)
                        chatStore.addMessage(id, message)
                        if (activeSessionId == id && !appendRowDom(id, message)) renderThread(id)
                    }
                }.start()
            }
        }
        else -> {
            // A command typed before the list loaded (or after it went stale) waits for it once.
            if (liveCommandsBackend != chatBackendOf(id) && !retriedCommand && activeSessionId == id) {
                refreshCommands { handleCommand(id, raw, retriedCommand = true) }
                return
            }
            val native = liveCommands.firstOrNull { liveCommandsBackend == chatBackendOf(id) && it.name.equals(cmd, true) }
            if (native != null && native.skill) {
                // A Terminus skill on a backend without skill commands: ask for it in words, show what was typed.
                val name = native.name.removePrefix("/")
                sendVisibleTurn(id, "Use the $name skill." + if (arg.isNotBlank()) " $arg" else "", emptyList(), raw)
            } else if (native != null && chatBackendOf(id) == "opencode") {
                sendVisibleTurn(id, raw, emptyList(), raw, nativeCommandName = native.name.removePrefix("/"))
            } else if (native != null && chatBackendOf(id) == "claude") {
                // Claude runs its own slash commands and skills: the text goes through as a normal prompt.
                sendVisibleTurn(id, raw, emptyList(), raw)
            } else {
                chatStore.addMessage(id, ChatMessage("tool",
                    "Unknown command \"$cmd\". Type / to see this chat's commands and skills."))
                renderThread(id)
            }
        }
    }
    renderSessions()
}

/** App-side usage commands use the same telemetry as the context ring.
 *  Claude's cost is an API-price estimate, not a subscription invoice or quota meter. */
internal fun MainActivity.showChatUsageCommand(id: String, command: String) {
    val chat = try { chatStore.get(id) } catch (_: Exception) { null }
    if (chat == null) {
        appendCommandResult(id, "No chat is open.")
        return
    }
    Thread {
        val text = try {
            val c = OpencodeApi.fetchContext(prefs.serverUrl(), prefs.token(), id, chat.model, totals = true)
            val lines = mutableListOf<String>()
            if (command == "/context") {
                lines += if (c.used <= 0) "Context: nothing recorded yet"
                    else if (c.window > 0) "Context: ${fmtTokens(c.used)} of ${fmtTokens(c.window)} tokens (${100L * c.used / c.window}%)"
                    else "Context: ${fmtTokens(c.used)} tokens; window unknown"
                lines += "Last request: in ${fmtTokens(c.input)}, out ${fmtTokens(c.output)}, cache read ${fmtTokens(c.cacheRead)}, cache write ${fmtTokens(c.cacheWrite)}"
                lines += if (c.compactAt > 0) "Auto-compact threshold: ${fmtTokens(c.compactAt.toLong())} tokens" else "Auto-compact threshold: backend default"
            } else if (command == "/cost") {
                lines += when {
                    c.costUsd == null -> "Cost: not reported for this chat yet"
                    c.backend == "claude" -> String.format(java.util.Locale.US, "Cost estimate: $%.4f API-price equivalent (not your Claude subscription charge)", c.costUsd)
                    else -> String.format(java.util.Locale.US, "Cost: $%.4f", c.costUsd)
                }
                lines += "Tracked tokens: ${fmtTokens(c.totalTokens)}"
            } else {
                lines += "Tracked chat usage: ${fmtTokens(c.totalTokens)} tokens across ${c.backend.ifBlank { "unknown backend" }}"
                lines += if (c.backend == "claude") "Claude plan quota/reset usage is not exposed by this Terminus report." else "Latest context: ${fmtTokens(c.used)} tokens"
            }
            lines.joinToString("\n")
        } catch (e: Exception) { "${command.removePrefix("/")} failed: ${e.message ?: "network error"}" }
        runOnUiThread { appendCommandResult(id, text) }
    }.start()
}

internal fun MainActivity.appendCommandResult(id: String, text: String) {
    val message = ChatMessage("tool", text)
    chatStore.addMessage(id, message)
    if (activeSessionId == id && !appendRowDom(id, message)) renderThread(id)
}

internal fun MainActivity.showBackendStatusCommand(id: String) {
    val chat = try { chatStore.get(id) } catch (_: Exception) { null }
    val backend = chat?.backend ?: prefs.defaultBackend()
    Thread {
        val text = try {
            val info = OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token()).backends.firstOrNull { it.id == backend }
            if (info == null) "Status unavailable: $backend is not in the server backend catalog"
            else "${info.label}: ${if (info.available) "available" else "unavailable"}; ${if (info.authenticated) "authenticated" else "login status unknown or required"}; model ${chat?.model?.takeIf { it.isNotBlank() && it != ChatStore.defaultModel() } ?: "server default"}"
        } catch (e: Exception) { "Status check failed: ${e.message ?: "network error"}" }
        runOnUiThread { appendCommandResult(id, text) }
    }.start()
}
