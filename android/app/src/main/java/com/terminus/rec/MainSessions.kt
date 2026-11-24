package com.terminus.rec

import android.os.Build
import android.view.View
import android.widget.TextView

/** The chat list: rendering, selection, deleting, refreshing, importing. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.renderSessions() {
    if (!chatStoreReady) return
    // Backend scope: the list follows the selected backend (view-only,
    // never migration). Search/tabs compose inside the scope.
    // The car's chat shows whatever backend is selected: you look for it on the phone after a drive.
    val all = backendScoped(chatStore.list(), prefs.defaultBackend(), setOf(CarChatsScreen.askChatId(this)))
    // The list is hidden while a thread is open: skip the sort, the
    // message-body search scan and the adapter diff entirely. The
    // snapshot advances on closeThread's full render. Settles and sends
    // (the hot paths) only refresh the live set below, never diff.
    val inThread = isThreadOpen()
    val nowMs = System.currentTimeMillis()
    // Job threads follow the same rule: a retired job goes quiet and folds away, a live one keeps firing.
    val archived = { c: ChatSession -> SessionAdapter.isArchived(c, nowMs, false) }
    val tabbed = if (inThread) emptyList() else when (sessionFilter) {
        1 -> all.filter { it.unread > 0 }
        3 -> all.filter(archived)
        // Schedules and alerts (everything the system sends rather than a chat you started) live under
        // their own tab; Recent hides the quiet ones (unread or needs-you still surface there as normal).
        // Search (below) still scans everything on the Recent tab.
        2 -> all.filter { isSystemChat(it.id) && !archived(it) }
        else -> if (sessionQuery.isBlank()) all.filter { (!isSystemChat(it.id) || it.unread > 0 || it.waitingOnYou(nowMs)) && !archived(it) } else all
    }
    if (!inThread) {
        refreshScheduleFacts()
        sessionAdapter.archivedView = sessionFilter == 3
        sessionAdapter.archivedCount = when {
            sessionQuery.isNotBlank() -> 0
            sessionFilter == 0 -> all.count { !isSystemChat(it.id) && archived(it) }
            sessionFilter == 2 -> all.count { isSystemChat(it.id) && archived(it) }
            else -> 0
        }
    }
    val filtered = if (inThread || sessionQuery.isBlank()) tabbed
        else tabbed.filter {
            it.title.contains(sessionQuery, true) ||
            // Capped tail scan: full-history scan per keystroke stalled
            // the UI on 3000-message threads. Titles match uncapped.
            it.messages.takeLast(200).any { m -> m.text.contains(sessionQuery, true) }
        }
    // Merge server sessions without touching local state: sessions the
    // app already owns (its local remoteIds) already show as local rows.
    // The rest are not-yet-opened importable rows (All tab only, newest
    // first). The open thread is excluded explicitly too: its session
    // must never resurface as an import row beside itself.
    val bound = try { chatStore.remoteIds() } catch (_: Exception) { emptySet() }
    val buried = try { chatStore.tombstones() } catch (_: Exception) { emptySet() }
    val openId = activeSessionId
    val openRemote = openId?.let { oid ->
        try { chatStore.get(oid)?.remoteId.takeUnless { it.isNullOrBlank() } } catch (_: Exception) { null }
    }
    val shownImports = if (inThread || sessionFilter != 0) emptyList() else serverImports.filter { imp ->
        imp.id !in bound && imp.id !in buried && imp.id != openRemote &&
            (sessionQuery.isBlank() || imp.title.contains(sessionQuery, true))
    }.sortedByDescending { it.updatedAt }
    // "replying…" follows confirmed in-flight turns only (never
    // placeholder freshness) — set before update() so the diff sees it.
    // Hidden list: record the set, skip the diff (see above).
    sessionAdapter.liveChats = TurnActivity.replyingChats(pendingTurns)
    sessionAdapter.refreshDeleting(deletingIds.toSet())
    // Date sections and the brief card only on the plain newest-first list (no search, no tab filter).
    sessionAdapter.sections = sessionSort == "newest" && sessionQuery.isBlank() && sessionFilter == 0
    if (!inThread) sessionAdapter.update(SessionAdapter.rankSearch(filtered, sessionQuery, sessionSort), shownImports)
    val showHero = !inThread && all.isEmpty() && shownImports.isEmpty()
    if (chatPaneReady) {
        chatPane.setPadding(chatPane.paddingLeft, dp(if (showHero) 8 else 16),
            chatPane.paddingRight, dp(if (showHero) 4 else 8))
    }
    if (!inThread) schedulePrebuild()
    sessionPull.visibility = if (inThread || showHero) View.GONE else View.VISIBLE
    searchToggle.visibility = if (inThread) View.GONE else View.VISIBLE
    // A live query always keeps its field on screen (the filter is visibly applied, never hidden).
    if (!inThread && sessionQuery.isNotBlank()) searchOpen = true
    sessionSearch.visibility = if (!inThread && searchOpen) View.VISIBLE else View.GONE
    if (searchToggleReady && !inThread) searchIcon.setImageResource(if (searchOpen) R.drawable.ic_close else R.drawable.ic_search)
    if (sessControlsReady) {
        sessControls.visibility = if (inThread || showHero) View.GONE else View.VISIBLE
        syncTabStyles()
        // Single scope icon: the selected backend only. Name rides in the
        // frame's description (all versions) and tooltip (26+).
        if (iconBackendScopeReady) {
            val b = prefs.defaultBackend()
            val label = backendDisplayName(b)
            iconBackendScope.setImageResource(agentIcon(b))
            findViewById<View>(R.id.backendScopeBtn).apply {
                contentDescription = "backend scope: $label, tap to change"
                if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
            }
        }
        sortBtn.contentDescription = "sort chats: ${sortLabel()}, tap to change"
    }
    chatHero.visibility = if (showHero) View.VISIBLE else View.GONE
    heroModelBtn.visibility = if (showHero) View.VISIBLE else View.GONE
    if (!inThread) composerRow.visibility = if (showHero) View.VISIBLE else View.GONE
    val showNoMatch = !inThread && !showHero && filtered.isEmpty() && shownImports.isEmpty()
    chatEmpty.visibility = if (showNoMatch) View.VISIBLE else View.GONE
    if (showNoMatch) {
        chatEmpty.text = when {
            sessionQuery.isNotBlank() -> "No matches for \"$sessionQuery\""
            sessionFilter == 1 -> "You're all caught up"
            sessionFilter == 2 -> "No schedules yet"
            else -> "No ${backendDisplayName(prefs.defaultBackend())} chats yet"
        }
    }
    renderBell(all)
}

/** Ping on settle, never for the thread being watched. Logs the decision
 *  at debug so a stray ping says which guard failed. */
internal fun MainActivity.maybeNotifyTurn(id: String, text: String, wasBg: Boolean) {
    val watching = activeSessionId == id
    android.util.Log.d("terminus", "notifyTurn chat=$id watching=$watching wasBg=$wasBg")
    if (wasBg || !watching) ChatNotify.notifyTurn(this, text, id, try { chatStore.get(id)?.title ?: "" } catch (_: Exception) { "" })
}

/** Confirm-free, both-sides, id-bound delete: the captured id (never
 *  "current session") drives the server call + local wipe. Local state
 *  clears immediately; the server call runs detached. */
internal fun MainActivity.deleteSessionNow(s: ChatSession) {
    deleteSessionById(s.id, s.remoteId)
}

/** Confirm-free, both-sides, id-bound delete: the captured id (never
 *  "current session") drives the server call + local wipe. The row stays
 *  visible with a Deleting state until the server confirms: success
 *  removes it, failure restores it with an error toast (retry by
 *  deleting again). Never silently gone. */
internal fun MainActivity.deleteSessionById(id: String, remoteId: String) {
    if (remoteId.isBlank()) {
        // Local-only chat: nothing server side to confirm.
        chatStore.delete(id)
        if (activeSessionId == id) closeThread() else renderSessions()
        return
    }
    deletingIds.add(id)
    try { sessionAdapter.refreshDeleting(deletingIds.toSet()) } catch (_: Exception) {}
    chatStore.buryRemote(remoteId)
    val backend = try { chatStore.get(id)?.backend ?: "opencode" } catch (_: Exception) { "opencode" }
    val sid = remoteId
    // The catalog says whether the server can delete for this backend (Claude and Agy
    // cannot: their transcripts are the CLI's own files). Then the chat is removed here and the
    // remote id stays buried so the import list does not bring it back. Unknown = ask the server.
    val serverCanDelete = backendCatalog?.backends?.firstOrNull { it.id == backend }?.capabilities?.deleteSession ?: true
    Thread {
        var ok = false
        try {
            if (serverCanDelete) OpencodeApi.deleteSession(prefs.serverUrl(), prefs.token(), sid, backend, id)
            ok = true
        } catch (_: Exception) {}
        runOnUiThread {
            deletingIds.remove(id)
            try { sessionAdapter.refreshDeleting(deletingIds.toSet()) } catch (_: Exception) {}
            if (ok) {
                chatStore.delete(id)
                if (activeSessionId == id) closeThread() else renderSessions()
            } else {
                try { chatStore.unburyRemote(sid) } catch (_: Exception) {}
                renderSessions()
                android.widget.Toast.makeText(this, "Server kept the chat", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

/** Bulk delete from the selection bar: confirm-free like single deletes
 *  (product rule), one toast with the count. Selection clears after. */
internal fun MainActivity.deleteSelected() {
    if (!sessionAdapterReady) return
    val ids = sessionAdapter.selectedIds.toList()
    val remoteIds = sessionAdapter.selectedImports.toList()
    if (ids.isEmpty() && remoteIds.isEmpty()) return
    val byId = try { chatStore.list().associateBy { it.id } } catch (_: Exception) { emptyMap() }
    sessionAdapter.clearSelection()
    var n = 0
    for (id in ids) {
        val s = byId[id] ?: continue
        deleteSessionById(s.id, s.remoteId)
        n++
    }
    for (sid in remoteIds) {
        val backend = serverImports.firstOrNull { it.id == sid }?.backend ?: "opencode"
        try { chatStore.buryRemote(sid) } catch (_: Exception) {}
        serverImports = serverImports.filter { it.id != sid }
        n++
        Thread {
            try { OpencodeApi.deleteRemote(prefs.serverUrl(), prefs.token(), sid, backend) }
            catch (_: Exception) { try { chatStore.unburyRemote(sid) } catch (_: Exception) {} }
        }.start()
    }
    renderSessions()
    android.widget.Toast.makeText(this, if (n == 1) "deleted 1 chat" else "deleted $n chats",
        android.widget.Toast.LENGTH_SHORT).show()
}

/** Selection action bar under the session controls. */
internal fun MainActivity.updateSelBar(n: Int) {
    val bar = try { findViewById<View>(R.id.selBar) } catch (_: Exception) { null } ?: return
    bar.visibility = if (n > 0) View.VISIBLE else View.GONE
    if (n > 0) {
        try {
            findViewById<android.widget.TextView>(R.id.selCount).text =
                if (n == 1) "1 selected" else "$n selected"
        } catch (_: Exception) {}
    }
}

internal fun MainActivity.setSessionFilter(f: Int) {
    sessionFilter = f.coerceIn(0, 3)
    // The archive view is a detour from Recent, never where the app reopens.
    if (sessionFilter != 3) prefs.setSessionFilter(sessionFilter)
    syncTabStyles()
    renderSessions()
}

internal fun MainActivity.syncTabStyles() {
    if (!tabAllReady) return
    val tabs = listOf(tabAll, tabUnread, tabSchedules)
    tabs.forEachIndexed { i, t ->
        val on = i == sessionFilter
        // Underline-tab style (mock): no pills. Active = dark + bold +
        // underline, inactive = muted with touch ripple preserved.
        if (on) {
            t.setBackgroundResource(R.drawable.tab_bg_active)
        } else {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)
            t.setBackgroundResource(tv.resourceId)
        }
        t.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, if (on) R.color.t_ink else R.color.t_muted))
        t.setTypeface(t.typeface, if (on) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL)
    }
}

internal fun MainActivity.sortLabel(): String = when (sessionSort) {
    "activity" -> "⚡ activity"
    "name" -> "A–Z name"
    else -> "↓ newest"
}

/** Sort control: newest (recency) / activity (unread first) / name (A–Z). */
internal fun MainActivity.showSortMenu() {
    val opts = listOf(
        Triple("↓ newest", "newest", ""),
        Triple("⚡ activity", "activity", "unread first"),
        Triple("A–Z name", "name", ""),
    )
    showSelectSheet("Sort chats", opts.map { (label, key, sub) ->
        SelectRow(label, sub, checked = key == sessionSort, action = {
            sessionSort = key
            prefs.setSessionSort(sessionSort)
            renderSessions()
        })
    })
}

/** Bell dot: chats with genuinely delivered results the Activity sheet
 *  can open (settled background reply or inbox delivery, exactly once)
 *  — never a live placeholder or tool/status row. The unit is the
 *  reviewable chat, matching the sheet's one-row-per-chat section, so
 *  the count and the sheet can never disagree. Opening the chat clears
 *  it durably. */
internal fun MainActivity.renderBell(scoped: List<ChatSession>? = null) {
    if (!bellBtnReady || !chatStoreReady) return
    // renderSessions passes its already-scoped list: one sort per render,
    // not two. Standalone callers (openThread, notificatons) scan alone.
    val all = scoped ?: try { chatStore.list() } catch (_: Exception) { emptyList() }
    val chats = all.count { it.unread > 0 }
    val total = all.sumOf { it.unread }
    findViewById<View>(R.id.bellDot)?.visibility = if (total > 0) View.VISIBLE else View.GONE
    bellBtn.contentDescription = if (chats > 0) "activity, $chats unread chats" else "activity"
    // Per-tab bells share the same unread state.
    for (dotId in listOf(R.id.bellDotEcho, R.id.bellDotNotes, R.id.bellDotGallery, R.id.bellDotOps)) {
        try {
            findViewById<View>(dotId)?.visibility = if (total > 0) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }
}

/** Pull-to-refresh: fetch server sessions and merge without touching
 *  local state. Mapped chats keep everything (keyed by session id);
 *  unmapped rows render importable below. Failures end the spinner
 *  with a toast; the list is unchanged. */
internal fun MainActivity.refreshSessions() {
    if (sessionsLoading) return
    sessionsLoading = true
    Thread {
        try {
            val remote = OpencodeApi.fetchSessions(prefs.serverUrl(), prefs.token(), prefs.defaultBackend())
            runOnUiThread {
                sessionsLoading = false
                try { sessionPull.isRefreshing = false } catch (_: Exception) {}
                serverImports = remote
                renderSessions()
            }
        } catch (e: Exception) {
            runOnUiThread {
                sessionsLoading = false
                try { sessionPull.isRefreshing = false } catch (_: Exception) {}
                android.widget.Toast.makeText(this,
                    "Couldn't refresh your chats (${e.message ?: "no connection"})",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

/** Delete a server-only session (import row). Real provider delete
 *  where supported (opencode remove, codex thread/delete); anything
 *  else fails explicitly and the row comes back honestly.
 *  Tombstone before the request so a racing refresh can't resurrect it. */
internal fun MainActivity.deleteRemoteSession(imp: OpencodeApi.RemoteSession) {
    try { chatStore.buryRemote(imp.id) } catch (_: Exception) {}
    serverImports = serverImports.filter { it.id != imp.id }
    renderSessions()
    Thread {
        try {
            OpencodeApi.deleteRemote(prefs.serverUrl(), prefs.token(), imp.id, imp.backend)
            runOnUiThread {
                android.widget.Toast.makeText(this, "Deleted from the server",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            try { chatStore.unburyRemote(imp.id) } catch (_: Exception) {}
            runOnUiThread {
                refreshSessions()
                android.widget.Toast.makeText(this,
                    "Couldn't delete it from the server (${e.message ?: "no connection"})",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

/** Import a server session: fresh local chat adopting the session id as
 *  its remoteId — no server call (there is no map anymore). The
 *  transcript backfills from /api/history when the server still has it
 *  (the thread opens with its messages); otherwise the thread opens
 *  empty with agent context, as before. The double-open guard re-checks
 *  after the fetch so a double-tap can't twin the chat. */
internal fun MainActivity.importSession(imp: OpencodeApi.RemoteSession) {
    try {
        if (chatStore.remoteIds().contains(imp.id)) {
            android.widget.Toast.makeText(this, "That chat is already in your list",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
    } catch (_: Exception) {}
    Thread {
        val history = try {
            OpencodeApi.fetchHistory(prefs.serverUrl(), prefs.token(), imp.id, imp.backend)
        } catch (_: Exception) { emptyList() }
        runOnUiThread {
            try {
                if (chatStore.remoteIds().contains(imp.id)) {
                    android.widget.Toast.makeText(this, "That chat is already in your list",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
            } catch (_: Exception) {}
            // Preserve the backend that owns this remote session.
            val s = chatStore.create(imp.backend, prefs.defaultModel(imp.backend))
            if (imp.title.isNotBlank()) chatStore.rename(s.id, imp.title, locked = true)
            chatStore.setRemoteId(s.id, imp.id)
            chatStore.unburyRemote(imp.id)
            var n = 0
            for (h in history) {
                chatStore.addMessage(s.id, ChatMessage(
                    h.role, h.text, ts = h.ts, settled = true))
                n++
            }
            if (n == 0) chatStore.addMessage(s.id, ChatMessage("event", "imported with agent context"))
            try { chatStore.flushAsync() } catch (_: Exception) {}
            serverImports = serverImports.filter { it.id != imp.id }
            openThread(s.id)
            if (activeSessionId == s.id) renderThread(s.id)
            if (n > 0) {
                android.widget.Toast.makeText(this, "imported $n messages",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}
