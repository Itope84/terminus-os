package com.terminus.rec

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView

/** Opening, closing and rendering a chat thread, and patching its page in place. Extension functions of [MainActivity], moved out of it unchanged. */

/** Thread UI is open for a persisted session OR an unpersisted draft. */
internal fun MainActivity.isThreadOpen(): Boolean = activeSessionId != null || draftOpen

/** Expand the title rightward into the model/context/attachment space. */
internal fun MainActivity.setThreadTitleExpanded(expanded: Boolean) {
    if (!threadTitleReady || threadTitleExpanded == expanded) return
    threadTitleExpanded = expanded
    updateThreadTitleDescription()
    val params = threadTitle.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
    if (!expanded) {
        expandedHeaderControlVisibility?.forEach { (view, visibility) -> view.visibility = visibility }
        expandedHeaderControlVisibility = null
        params.width = 0
        params.weight = 1f
        params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        threadTitle.layoutParams = params
        threadTitle.background = null
        threadTitle.elevation = 0f
        threadTitle.translationZ = 0f
        threadTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        threadTitle.setPadding(0, 0, 0, 0)
        return
    }
    val covered = listOf(
        R.id.modelChip,
        R.id.contextRing,
        R.id.attachListBtn,
    )
        .mapNotNull { findViewById<View>(it) }
    expandedHeaderControlVisibility = covered.map { it to it.visibility }
    covered.forEach { it.visibility = View.GONE }
    params.width = 0
    params.weight = 1f
    params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    threadTitle.layoutParams = params
    threadTitle.background = null
    threadTitle.elevation = 0f
    threadTitle.translationZ = 0f
    threadTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    threadTitle.setPadding(0, 0, 0, 0)
}

internal fun MainActivity.updateThreadTitleDescription() {
    if (!threadTitleReady) return
    val action = if (threadTitleExpanded) "tap to collapse name" else "tap to expand name"
    threadTitle.contentDescription = "${threadTitle.text}, $action"
}

/** Draft thread: full thread UI, NO stored session. Persist happens only
 *  on first send or first model pick; < backs out with zero trace. */
internal fun MainActivity.openDraft() {
    setThreadTitleExpanded(false)
    activeSessionId = null
    draftOpen = true
    pingServer()
    syncNavForThread()
    draftText = ""
    sessionPull.visibility = View.GONE
    if (sessControlsReady) sessControls.visibility = View.GONE
    sessionSearch.visibility = View.GONE
    chatEmpty.visibility = View.GONE
    chatHero.visibility = View.GONE
    heroModelBtn.visibility = View.GONE
    threadHeader.visibility = View.VISIBLE
    modelChip.visibility = View.VISIBLE
    contextChat = null
    contextInfo = null
    paintContextRing()
    threadDivider.visibility = View.VISIBLE
    threadWeb.visibility = View.VISIBLE
    threadFrame.visibility = View.VISIBLE
    setThreadLoading(false)
    composerRow.visibility = View.VISIBLE
    composerInput.setText("")
    composerInput.hint = "Message (/ for commands)"
    pendingAttaches.clear()
    hideAttachStrip()
    pendingVoice = null
    clearQuote()
    if (voicePreviewReady) voicePreview.visibility = View.GONE
    lastThreadHtml = ""
    threadPageLoaded = false
    lastFullMsgSig = ""
    pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
    pendingStepPatch = null
    renderDraft()
    updateChatRecUI()
}

/** Empty-thread render for drafts (no session row exists yet). */
internal fun MainActivity.renderDraft() {
    val draftBackend = prefs.defaultBackend()
    val model = prefs.defaultModel(draftBackend).ifBlank { ChatStore.defaultModel() }
    threadTitle.text = "new chat"
    updateThreadTitleDescription()
    paintModelChip(draftBackend, model)
    val html = ChatMarkdown.threadHtml("new chat", model, emptyList(), LinkToken.forUrl(this@renderDraft, prefs), dark = isDark())
    if (html == lastThreadHtml) return
    lastThreadHtml = html
    threadPageLoaded = false
    lastFullMsgSig = "draft"
    threadWeb.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
}

/** Persist the draft on demand (first send / first model pick),
 *  carrying composer text + quote + staged attachment into the session. */
internal fun MainActivity.ensureDraftSession(model: String? = null): String {
    val existing = activeSessionId
    if (existing != null) return existing
    val typed = composerInput.text.toString()
    val hint = composerInput.hint.toString()
    val staged = pendingAttaches.toList()
    val stagedQuotes = quotes.map { it.copy() }
    val cfg = newChatConfig()
    val s = chatStore.create(cfg.backend, model ?: cfg.model)
    draftOpen = false
    openThread(s.id)
    if (typed.isNotEmpty()) {
        composerInput.setText(typed)
        composerInput.setSelection(typed.length)
    }
    pendingAttaches.addAll(staged)
    if (stagedQuotes.isNotEmpty()) { quotes.clear(); quotes.addAll(stagedQuotes); renderQuotes() }
    if (hint.isNotBlank()) composerInput.hint = hint
    return s.id
}

internal fun MainActivity.openThread(id: String) {
    // A chat that no longer exists (deleted, or a stale approval/inbox target) must not half-open: the state
    // below (active id, saved open chat, thread nav) is set before the chat is read, and bailing after it
    // left the app thinking a thread was open with nothing on screen.
    if (chatStore.get(id) == null) return
    catchUpBackground(id)
    pullReset()
    stopDictation()
    if (activeSessionId != id) {
        setThreadTitleExpanded(false)
        replyVoice.stop()
    }
    draftOpen = false
    draftText = ""
    // A thread switch abandons edit mode (its draft belongs to the old box).
    editingTurn = null
    editingStashByTurn.clear()
    activeSessionId = id
    pingServer()
    // One session = one chat: opening any other chat strips a stray live
    // placeholder left from the buggy era (frozen records are kept); the
    // owner chat keeps its live card.
    try {
        if (!browserCards.owns(id)) chatStore.removeLiveBrowserCard(id)
    } catch (_: Exception) {}
    syncNavForThread()
    prefs.setOpenChatId(id)
    val s = chatStore.get(id) ?: return
    // Capture where the unread run starts BEFORE markRead clears the counter: the thread opens there,
    // not at the bottom. A needs-you flag has no row of its own, so it keeps the bottom.
    val firstUnread = if (s.needsYou.isBlank()) firstUnreadIndex(s.messages, s.unread) else -1
    openAnchor = if (firstUnread >= 0) Triple(id, firstUnread, s.messages.size) else null
    openRestore = if (firstUnread < 0) (leftScrollY[id]?.takeIf { it >= 0 } ?: if (leftScrollY.containsKey(id)) null else ThreadScrollStore.get(id, s.messages.size))?.let { id to it } else null
    lastRenderScroll = true
    lastRenderKeepCssY = 0
    chatStore.markRead(id)
    try { ChatNotify.clearChat(this, id) } catch (_: Exception) {}
    renderBell()
    // The list is hidden while the thread is open, but refresh it
    // anyway: the stored unread is already zero, and the adapter's
    // snapshot must advance now or the badge survives off stale rows.
    renderSessions()
    sessionPull.visibility = View.GONE
    if (sessControlsReady) sessControls.visibility = View.GONE
    sessionSearch.visibility = View.GONE
    chatEmpty.visibility = View.GONE
    chatHero.visibility = View.GONE
    heroModelBtn.visibility = View.GONE
    threadHeader.visibility = View.VISIBLE
    modelChip.visibility = View.VISIBLE
    threadDivider.visibility = View.VISIBLE
    // Hidden until the new page commits (revealed in onPageFinished):
    // the old page paints until the new loadData lands, so a visible
    // view flashes the previous chat. about:blank can't win that race —
    // the blank load never paints before loadData cancels it. Blank
    // flash beats stale content.
    threadWeb.visibility = View.INVISIBLE
    setThreadLoading(true)
    if (threadFrameReady) threadFrame.visibility = View.VISIBLE
    composerRow.visibility = View.VISIBLE
    if (jumpLatestReady) jumpLatest.visibility = View.GONE
    composerInput.setText(s.draft)
    composerInput.hint = "Message (/ for commands)"
    pendingAttaches.clear()
    hideAttachStrip()
    pendingVoice = null
    clearQuote()
    if (voicePreviewReady) voicePreview.visibility = View.GONE
    // Invalidate the render cache and load the new document directly.
    // An about:blank navigation here races with loadData and can leave the
    // WebView empty if its late completion wins. The view stays invisible
    // until the new page commits (see onPageFinished), so no stale
    // pixels survive the switch.
    lastThreadHtml = ""
    threadVisible.remove(id)
    historyGrowing = false
    threadTouchScroll = false
    threadPageLoaded = false
    lastFullMsgSig = ""
    pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
    pendingStepPatch = null
    updateStopBtn()
    renderThread(id)
    // Outstanding turn (e.g. after process death): resume live steps.
    val tail = s.messages.lastOrNull()
    if (tail != null && tail.role == "assistant" && tail.turnId.isNotBlank() && !tail.settled) {
        ensureStream(id)
    }
    // Have the server close out anything finished or dead behind this chat, including a finished
    // opencode reply the app never received after the turn looked settled (no gesture, no layout
    // change; a no-op when there is nothing to settle).
    syncThread(id)
    // Providers can assign names after the first turn; adopt one when
    // available, while keeping the local first-message title as fallback.
    syncServerTitle(id)
    updateChatRecUI()
    syncInbox(minGapMs = 10_000)
    goalCache = null
    paintGoalStrip()
    refreshGoalStrip(id)
}

/** Remember where this chat is scrolled to, in memory and on disk (see ThreadScrollStore). */
internal fun MainActivity.rememberThreadScroll(id: String) {
    if (!threadPageLoaded) return
    val d = resources.displayMetrics.density.coerceAtLeast(1f)
    val y = threadWeb.scrollY
    val atBottom = y + threadWeb.height >= (threadWeb.contentHeight * d).toInt() - 48
    val css = if (atBottom) -1 else (y / d).toInt()
    leftScrollY[id] = css
    try { ThreadScrollStore.put(id, css, chatStore.get(id)?.messages?.size ?: -1) } catch (_: Exception) {}
}

internal fun MainActivity.closeThread() {
    pullReset()
    val id = activeSessionId
    setThreadTitleExpanded(false)
    stopDictation()
    replyVoice.stop()
    editingTurn = null
    editingStashByTurn.clear()
    // A debounced draft may hold older text: drop it, then persist the
    // composer's current text synchronously — close must never lose it.
    dropPendingDraft()
    if (id != null) chatStore.setDraft(id, composerInput.text.toString())
    // Replies that settled while this thread was open were read in place (no full render, so no markRead):
    // leaving must not hand them back as unread.
    if (id != null) try { chatStore.markRead(id) } catch (_: Exception) {}
    if (id != null) {
        try {
            rememberThreadScroll(id)
        } catch (_: Exception) {}
    }
    stopStream()
    activeSessionId = null
    pingServer()
    openAnchor = null
    openRestore = null
    syncNavForThread()
    historyGrowing = false
    threadTouchScroll = false
    goalCache = null
    try { if (goalStripBarReady) goalStripBar.visibility = View.GONE } catch (_: Exception) {}
    // Leaving must not strand in-flight turns: if anything is still
    // outstanding anywhere, wake a stream for it (same reaper rule as
    // socket death — the reply still settles + pings from the list).
    try {
        val outstanding = chatStore.chatsWithLive().lastOrNull()
        if (outstanding != null) {
            progressHandler.postDelayed({
                if (streamChat == null && chatStore.hasLive(outstanding)) startStream(outstanding)
            }, 1500)
        }
    } catch (_: Exception) {}
    // Draft close: nothing was ever stored — zero trace by construction.
    draftOpen = false
    syncNavForThread()
    draftText = ""
    composerInput.setText("")
    pendingAttaches.clear()
    hideAttachStrip()
    pendingVoice = null
    clearQuote()
    if (voicePreviewReady) voicePreview.visibility = View.GONE
    threadHeader.visibility = View.GONE
    modelChip.visibility = View.GONE
    if (contextRingReady) contextRing.visibility = View.GONE
    threadDivider.visibility = View.GONE
    threadWeb.visibility = View.GONE
    if (threadFrameReady) threadFrame.visibility = View.GONE
    composerRow.visibility = View.GONE
    if (jumpLatestReady) jumpLatest.visibility = View.GONE
    chatRecStatus.visibility = View.GONE
    slashList.visibility = View.GONE
    heroModelBtn.visibility = View.GONE
    lastThreadHtml = ""
    threadPageLoaded = false
    lastFullMsgSig = ""
    pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
    pendingStepPatch = null
    pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
    pendingThreadScroll = null
    renderSessions()
}

/** tel: opens the dialer with the number filled in (never places the call itself); mailto:/sms: open the
 *  matching app. */
internal fun MainActivity.openContactLink(u: String) {
    val action = if (u.startsWith("tel:")) android.content.Intent.ACTION_DIAL else android.content.Intent.ACTION_SENDTO
    try {
        startActivity(android.content.Intent(action, android.net.Uri.parse(u)))
    } catch (_: Exception) {
        toast("No app to open that")
    }
}

internal fun MainActivity.pageKey(
    id: String, s: ChatSession, shown: List<ChatMessage>, vis: Int, effScroll: Boolean, keepCssY: Int,
    anchorIdx: Int, dark: Boolean, token: String, doneSteps: Int,
): String =
    // A bottom-pinned page ignores keepCssY and an anchor only matters when pinning: normalise so the
    // background build and the real open agree on one key.
    "$id|${s.title.hashCode()}|${s.model}|${s.messages.size}|${shown.hashCode()}|$vis|$effScroll|" +
        "${if (effScroll) -1 else keepCssY}|${if (effScroll) anchorIdx else -1}|$dark|${token.hashCode()}|$doneSteps|" +
        // Alarm cards print armed/cancelled from prefs, and the browser-card assets arrive after startup: a
        // tap on a card or a late asset must not be answered with a page built before it.
        "${WakeAlarm.signature(this)}|${ChatMarkdown.pbScript.hashCode()}|${ChatMarkdown.pbCss.hashCode()}|" +
        "${ChatMarkdown.threadCardsCss.hashCode()}|${ChatMarkdown.threadUiJs.hashCode()}"

internal fun MainActivity.cachedPage(key: String): String? {
    val hit = pageCache[key] ?: return null
    if (System.currentTimeMillis() - hit.at > PAGE_TTL_MS) { pageCache.remove(key); return null }
    return hit.html
}

internal fun MainActivity.schedulePrebuild() {
    threadScrollHandler.removeCallbacks(prebuildRun)
    threadScrollHandler.postDelayed(prebuildRun, 1500)
}

/** List on screen: build the pages the next taps are most likely to open (three most recently active chats),
 *  with the same plan openThread/renderThread will use, so the real open finds them ready. */
internal fun MainActivity.prebuildThreads() {
    if (!chatStoreReady || isThreadOpen() || draftOpen) return
    val dark = isDark()
    val token = LinkToken.forUrl(this@prebuildThreads, prefs)
    val recent = try {
        chatStore.list().filter { it.messages.isNotEmpty() && !isScheduleChat(it.id) }
            .sortedByDescending { it.updatedAt }.take(3)
    } catch (_: Exception) { return }
    for (c in recent) {
        val id = c.id
        if (pendingTurns[id]?.isNotEmpty() == true || checking.contains(id) || !progressLive[id].isNullOrEmpty()) continue
        val msgs = c.messages
        val total = msgs.size
        // Mirror of openThread's open plan (unread anchor, or the saved place).
        val firstUnread = if (c.needsYou.isBlank()) firstUnreadIndex(msgs, c.unread) else -1
        val restoreY = if (firstUnread < 0)
            (leftScrollY[id]?.takeIf { it >= 0 } ?: if (leftScrollY.containsKey(id)) null else ThreadScrollStore.get(id, total))
        else null
        val effScroll = restoreY == null
        val keepCssY = restoreY ?: 0
        val anchorIdx = if (effScroll && firstUnread >= 0) firstUnread else -1
        var vis = threadVisible[id] ?: threadPageSize
        if (anchorIdx >= 0 && total - anchorIdx > vis) vis = total - anchorIdx + 4
        val shown = if (total > vis) msgs.takeLast(vis) else msgs.toList()
        val done = progressDone[id] ?: emptyList()
        val key = pageKey(id, c, shown, vis, effScroll, keepCssY, anchorIdx, dark, token, done.size)
        if (cachedPage(key) != null) continue
        val title = c.title
        val model = c.model
        val doneCopy = done.toList()
        val rows = shown.toList()
        pageExec.execute {
            val html = try {
                ChatMarkdown.threadHtml(
                    title, model, rows, token, null, doneCopy,
                    autoScroll = effScroll,
                    keepScrollY = if (effScroll) -1 else keepCssY,
                    dark = dark,
                    hiddenCount = total - rows.size,
                    liveBox = false,
                    chatId = id,
                    baseIndex = total - rows.size,
                    anchorIdx = anchorIdx,
                )
            } catch (_: Throwable) { return@execute }
            runOnUiThread { pageCache[key] = PageBuild(html, System.currentTimeMillis()) }
        }
    }
}

/** Placeholder bars behind the hidden thread page while it builds (see threadSkeleton in the layout). */
internal fun MainActivity.setThreadLoading(on: Boolean) {
    val sk = findViewById<View?>(R.id.threadSkeleton) ?: return
    sk.animate().cancel()
    if (on) {
        sk.alpha = 0.7f
        sk.visibility = View.VISIBLE
    } else if (sk.visibility == View.VISIBLE) {
        // Short fade so the real thread replaces the placeholder without a pop.
        sk.animate().alpha(0f).setDuration(140).withEndAction { sk.visibility = View.GONE }.start()
    }
}

internal fun MainActivity.renderThread(id: String, scroll: Boolean = true) {
    // Async history/turn callbacks can arrive after the user switches
    // chats. Never let an old chat overwrite the currently open WebView.
    if (activeSessionId != id) return
    alignScheduleChatBackend(id)
    val s = chatStore.get(id) ?: return
    // Fresh open backfills from the backend's own history (opencode
    // message rows), so existing chats paint real usage without
    // needing a new turn first. totals bypasses the 3s throttle.
    val freshContext = contextChat != id
    if (freshContext) { contextChat = id; contextInfo = null; paintContextRing() }
    refreshContext(id, s.model, totals = freshContext)
    chatStore.markRead(id)
    renderBell()
    val liveNow = try { chatStore.liveTurns(id).map { it.first }.toSet() } catch (_: Exception) { emptySet() }
    unknownSurfaced[id]?.retainAll(liveNow)
    val staleTurns = chatStore.sweepStalePlaceholders(id)
        .filter { !(unknownSurfaced[id]?.contains(it) ?: false) }
    if (staleTurns.isNotEmpty()) {
        // Timeout is not proof of failure: reconcile against the
        // server's exact-turnId registry FIRST. The unknown row only
        // appears when the turn is genuinely still unsettled — the old
        // order added the row unconditionally, leaving permanent noise
        // behind replies that landed a second later. While the check
        // runs, the checking indicator shows (same "…" shimmer as a
        // tap-check) so a quiet-but-alive turn visibly still works.
        for (turn in staleTurns) {
            if (unknownSurfaced[id]?.contains(turn) == true) continue
            val checkKey = "$id:$turn"
            // Auto-reconcile cooldown: one check per turn per minute.
            // Without this every render re-fired network + showChecking
            // + callback renders while a turn stayed stale — full HTML
            // rebuilds in a loop, the v143+ jank during long turns.
            // Tap-checks call reconcileTurn directly, never cooled.
            val now = System.currentTimeMillis()
            val last = lastAutoReconcileAt[checkKey] ?: 0L
            if (now - last < 60_000) continue
            if (!checkingTurns.add(checkKey)) continue
            lastAutoReconcileAt[checkKey] = now
            if (lastAutoReconcileAt.size > 100) {
                lastAutoReconcileAt.entries.sortedBy { it.value }.take(50).forEach { lastAutoReconcileAt.remove(it.key) }
            }
            showChecking(id)
            reconcileTurn(id, turn) { settled ->
                checkingTurns.remove(checkKey)
                if (settled) return@reconcileTurn
                if (unknownSurfaced[id]?.contains(turn) == true) return@reconcileTurn
                try {
                    val stillLive = try {
                        chatStore.liveTurns(id).map { it.first }.toSet()
                    } catch (_: Exception) { emptySet() }
                    if (turn !in stillLive) return@reconcileTurn
                } catch (_: Exception) { return@reconcileTurn }
                unknownSurfaced.getOrPut(id) { mutableSetOf() }.add(turn)
                // Terminal, not "still trying": the turn is dead, so say
                // so on its own bubble when there is one; otherwise a
                // single standalone note (replaced, never stacked).
                try { chatStore.dropAllUnknownRows(id) } catch (_: Exception) {}
                chatStore.dropLastFailureNote(id)
                try { chatStore.dropFailureNoteForTurn(id, turn) } catch (_: Exception) {}
                if (!chatStore.setUserSendFailed(id, turn, true)) {
                    chatStore.addMessage(id, ChatMessage("system",
                        TurnActivity.formatErrorNote("", "", id, turn)))
                }
                if (activeSessionId == id) renderThread(id)
                renderSessions()
            }
        }
        updateStopBtn()
    }
    threadTitle.text = s.title
    updateThreadTitleDescription()
    paintModelChip(s.backend, s.model)
    if (attachListBtnReady) {
        attachListBtn.visibility =
            if (s.messages.any { !it.attachment.isNullOrBlank() ||
                    (it.role == "assistant" && extractMentionedFiles(it.text).isNotEmpty()) })
                View.VISIBLE else View.GONE
    }
    val density = resources.displayMetrics.density.coerceAtLeast(1f)
    // A render that arrives while the previous reload is still loading must not re-read the live
    // scroll (it reads 0 then): repeat what that render asked for. See scrollPlan.
    val reloading = !threadPageLoaded && lastThreadHtml.isNotEmpty()
    val restore = openRestore?.takeIf { it.first == id }
    openRestore = null
    val (effScroll, keepCssY) =
        if (scroll && restore != null) false to restore.second
        else scrollPlan(scroll, reloading, lastRenderScroll, lastRenderKeepCssY, (threadWeb.scrollY / density).toInt())
    lastRenderScroll = effScroll
    lastRenderKeepCssY = keepCssY
    if (openAnchor?.let { it.first == id && it.third != s.messages.size } == true) openAnchor = null
    val anchorIdx = if (effScroll) openAnchor?.takeIf { it.first == id }?.second ?: -1 else -1
    var vis = threadVisible[id] ?: threadPageSize
    // The first unread row must be on the page: widen the window to include it.
    if (anchorIdx >= 0 && s.messages.size - anchorIdx > vis) {
        vis = s.messages.size - anchorIdx + 4
        threadVisible[id] = vis
    }
    val shown = if (s.messages.size > vis) s.messages.takeLast(vis) else s.messages
    val liveBoxNow = pendingTurns[id]?.isNotEmpty() == true || checking.contains(id)
    val tokenNow = LinkToken.forUrl(this@renderThread, prefs)
    val darkNow = isDark()
    val doneNow = progressDone[id] ?: emptyList()
    // Live progress changes by the second: only a quiet chat is served from (or stored in) the page cache.
    val cacheable = !liveBoxNow && progressLive[id].isNullOrEmpty()
    val pageK = if (cacheable) pageKey(id, s, shown, vis, effScroll, keepCssY, anchorIdx, darkNow, tokenNow, doneNow.size) else ""
    val buildT0 = android.os.SystemClock.uptimeMillis()
    val cachedHtml = if (cacheable) cachedPage(pageK) else null
    val html = cachedHtml ?: ChatMarkdown.threadHtml(
        s.title, s.model, shown, tokenNow,
        progressLive[id], doneNow,
        autoScroll = effScroll,
        keepScrollY = if (effScroll) -1 else keepCssY,
        dark = darkNow,
        hiddenCount = s.messages.size - shown.size,
        liveBox = liveBoxNow,
        chatId = id,
        baseIndex = s.messages.size - shown.size,
        anchorIdx = anchorIdx,
    ).also { if (cacheable) pageCache[pageK] = PageBuild(it, System.currentTimeMillis()) }
    android.util.Log.d("ThreadOpen", "page ${if (cachedHtml != null) "cache-hit" else "built"} ${android.os.SystemClock.uptimeMillis() - buildT0}ms rows=${shown.size} bytes=${html.length}")
    // Never reload identical HTML — skip loadData when the hash matches,
    // but still refresh the stop button (turn state isn't in the hash).
    // A requested bottom scroll (open-from-notification, fresh send)
    // still fires: identical content doesn't mean we're at the bottom.
    updateStopBtn()
    if (html == lastThreadHtml) {
        if (effScroll) { if (anchorIdx >= 0) anchorThreadScroll(anchorIdx) else scrollThreadToBottom() }
        // The open-thread hide-until-commit must never strand the view:
        // a skipped reload fires no onPageFinished, so reveal here.
        if (threadWeb.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
            threadWeb.visibility = View.VISIBLE
        }
        setThreadLoading(false)
        return
    }
    lastThreadHtml = html
    // Structural render (new messages / settle / expander): the page
    // reloads, so step-patch state resets until onPageFinished fires.
    threadPageLoaded = false
    lastFullMsgSig = threadMsgSig(id)
    // Stamp the sig cache with the just-rendered truth: the next live
    // patch must compare against this page, not a pre-render entry.
    msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
    lastStepPatched[id] = progressLive[id]?.size ?: 0
    lastStepPatchAt = android.os.SystemClock.uptimeMillis()
    pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
    pendingStepPatch = null
    threadWeb.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    if (effScroll) { if (anchorIdx >= 0) anchorThreadScroll(anchorIdx) else scrollThreadToBottom() }
    else restoreThreadScroll(keepCssY)
}

internal fun MainActivity.threadMsgSigCached(id: String): String {
    val now = android.os.SystemClock.uptimeMillis()
    val hit = msgSigCache[id]
    if (hit != null && now - hit.second < 1000) return hit.first
    val s = threadMsgSig(id)
    if (msgSigCache.size > 50) msgSigCache.clear()
    msgSigCache[id] = s to now
    return s
}

/** Structural signature for the open thread: settled message ids/roles/
 *  text + settled-step count. Live (streaming) text is excluded, so step
 *  patches and live-text patches skip full reloads while a turn runs. */
internal fun MainActivity.threadMsgSig(id: String): String {
    val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return "$id|none"
    return threadMsgSigFor(id, s.messages)
}

/** Same signature over an explicit message list: lets the incremental
 *  append verify the page matches everything *before* the new row. */
internal fun MainActivity.threadMsgSigFor(id: String, msgs: List<ChatMessage>): String {
    val done = progressDone[id]?.size ?: 0
    val live = msgs.count { it.role == "assistant" && it.turnId.isNotBlank() && !it.settled }
    return "$id|${msgs.size}|${msgs.filter { it.settled || it.role != "assistant" }.map { it.role + ":" + it.text.hashCode() + ":" + it.sendFailed }.hashCode()}|$done|$live"
}

/** Incremental append of one settled trailing row without a WebView
 *  reload. Returns false whenever the page can't take it (not loaded,
 *  paged window, first row over the empty state, non-trailing row,
 *  live row, or any drift) — the caller falls back to renderThread(),
 *  i.e. today's behavior. A failed JS insert self-heals the same way
 *  via the evaluate callback. */
internal fun MainActivity.appendRowDom(id: String, m: ChatMessage): Boolean {
    try {
        if (activeSessionId != id || !threadPageLoaded) return false
        // Pointer rows never patch incrementally (they render as labels
        // on user rows): fall back to a full render so labels land.
        if (isPointer(m)) return false
        // Live rows ride the delta/step patch paths, never this one
        // (their ids + the one-indicator rule need a full render).
        if (m.role == "assistant" && m.turnId.isNotBlank() && !m.settled) return false
        val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return false
        val vis = threadVisible[id] ?: threadPageSize
        if (s.messages.size > vis) return false // paged: counts shift
        if (s.messages.size <= 1) return false // first row replaces the empty state
        val idx = s.messages.indexOfLast { it === m }
        if (idx < 0 || idx != s.messages.size - 1) return false
        // The loaded page must match everything before the new row.
        if (threadMsgSigFor(id, s.messages.dropLast(1)) != lastFullMsgSig) return false
        val prevTs = s.messages[idx - 1].ts
        val frag = org.json.JSONObject.quote(
            ChatMarkdown.daySepBetween(prevTs, m.ts) + ChatMarkdown.rowHtml(m, idx, LinkToken.forUrl(this@appendRowDom, prefs)))
        val js = "(function(){if(typeof window.__appendRow!=='function')return 'missing';" +
            "return window.__appendRow(" + frag + ");})();"
        threadWeb.evaluateJavascript(js) { res ->
            if (res == null || res == "null" || !res.contains("ok")) {
                runOnUiThread { if (activeSessionId == id) renderThread(id) }
            } else {
                // Page now carries the new row: resync so step/live
                // patches keep patching instead of reloading.
                lastFullMsgSig = threadMsgSig(id)
                msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
            }
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

/** In-place settle without a WebView reload: the live placeholder row
 *  for a turn is replaced by its settled html (same slot, no scroll
 *  jump). Twin of appendRowDom with the same discipline — any doubt
 *  falls back to a full render. Pointer rows and paged windows stay
 *  on the full path. */
internal fun MainActivity.settleRowDom(id: String, turnId: String, clearLive: Boolean): Boolean {
    try {
        if (activeSessionId != id || !threadPageLoaded) return false
        if (turnId.isBlank()) return false
        val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return false
        val vis = threadVisible[id] ?: threadPageSize
        if (s.messages.size > vis) return false // paged: positions shift
        val idx = s.messages.indexOfLast { it.role == "assistant" && it.turnId == turnId && it.settled }
        if (idx < 0) return false
        val m = s.messages[idx]
        if (isPointer(m)) return false
        val frag = org.json.JSONObject.quote(ChatMarkdown.rowHtml(m, idx, LinkToken.forUrl(this@settleRowDom, prefs)))
        val js = "(function(){if(typeof window.__settleRow!=='function')return 'missing';" +
            "return window.__settleRow(" + org.json.JSONObject.quote(turnId) + "," + frag + "," +
            (if (clearLive) "true" else "false") + ");})();"
        threadWeb.evaluateJavascript(js) { res ->
            if (res == null || res == "null" || !res.contains("ok")) {
                runOnUiThread { if (activeSessionId == id) renderThread(id) }
            } else {
                // Page now carries the settled row: resync so step/live
                // patches keep patching instead of reloading.
                lastFullMsgSig = threadMsgSig(id)
                msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
            }
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

/** Live step arrival: DOM-patch #prog when the page is loaded and only
 *  steps changed; any structural change falls back to full render. */
internal fun MainActivity.onLiveSteps(id: String) {
    updateStopBtn()
    if (activeSessionId != id) return
    if (!threadPageLoaded) {
        renderThread(id, scroll = false)
        return
    }
    if (threadMsgSigCached(id) != lastFullMsgSig) {
        renderThread(id, scroll = false)
        return
    }
    scheduleStepPatch(id)
}

/** Coalesce step bursts: at most one DOM patch per 800ms. */
internal fun MainActivity.scheduleStepPatch(id: String) {
    val now = android.os.SystemClock.uptimeMillis()
    val wait = STEP_PATCH_MIN_MS - (now - lastStepPatchAt)
    if (wait > 0) {
        if (pendingStepPatch == null) {
            val r = Runnable {
                pendingStepPatch = null
                patchStepsDom(id)
            }
            pendingStepPatch = r
            progressHandler.postDelayed(r, wait)
        }
        return
    }
    patchStepsDom(id)
}

internal fun MainActivity.escStepHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Append only the unseen step rows to #prog + pin the box to bottom.
 *  Falls back to a full render when the box is missing (page not ready
 *  or structural drift). Never reloads for step-only updates. */
internal fun MainActivity.patchStepsDom(id: String) {
    if (activeSessionId != id || !threadPageLoaded) {
        if (activeSessionId == id) renderThread(id, scroll = false)
        return
    }
    if (threadMsgSigCached(id) != lastFullMsgSig) {
        renderThread(id, scroll = false)
        return
    }
    val steps = progressLive[id] ?: emptyList()
    val from = lastStepPatched[id] ?: 0
    if (from >= steps.size) {
        lastStepPatchAt = android.os.SystemClock.uptimeMillis()
        return
    }
    val rows = StringBuilder()
    for (i in from until steps.size) {
        val st = steps[i]
        rows.append("<div class='prow'>▸ ")
            .append(escStepHtml(st.label.ifBlank { st.kind }))
            .append("</div>")
        if (st.detail.isNotBlank()) rows.append("<div class='pdet'>")
            .append(escStepHtml(st.detail)).append("</div>")
    }
    val htmlFrag = org.json.JSONObject.quote(rows.toString())
    val js = "(function(){var b=document.getElementById('prog');if(!b)return 'missing';" +
        "var sh=b.querySelector('.shimmer');if(sh)sh.remove();" +
        "var ps=document.getElementById('pstat');if(ps)ps.remove();" +
        "b.insertAdjacentHTML('beforeend'," + htmlFrag + ");" +
        "b.scrollTop=b.scrollHeight;return 'ok';})();"
    lastStepPatchAt = android.os.SystemClock.uptimeMillis()
    lastStepPatched[id] = steps.size
    try {
        threadWeb.evaluateJavascript(js) { res ->
            if (res != null && res.contains("missing")) {
                runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
            }
        }
    } catch (e: Exception) {
        android.util.Log.d("terminus", "patchStepsDom: ${e.message}")
        runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
    }
}

/** Live status row (see the "status" stream event): kept last in #prog, removed when blank. */
internal fun MainActivity.patchStatusDom(text: String) {
    val js = "(function(t){var b=document.getElementById('prog');var p=document.getElementById('pstat');" +
        "if(!t){if(p)p.remove();return;}if(!b)return;var sh=b.querySelector('.shimmer');if(sh)sh.remove();" +
        "if(!p){p=document.createElement('div');p.id='pstat';p.className='prow pstat';}b.appendChild(p);" +
        "p.textContent='\u25B8 '+t;b.scrollTop=b.scrollHeight;})(" + org.json.JSONObject.quote(text) + ");"
    try { threadWeb.evaluateJavascript(js, null) } catch (_: Exception) {}
}

/** Paint live reply text into the standing row without a reload.
 *  Falls back to a full render when the row is missing (page not ready
 *  or structural drift). Throttled by the caller. */
internal fun MainActivity.patchLiveTextDom(id: String, turnId: String, text: String) {
    if (activeSessionId != id || !threadPageLoaded) {
        if (activeSessionId == id) renderThread(id, scroll = false)
        return
    }
    if (threadMsgSigCached(id) != lastFullMsgSig) {
        renderThread(id, scroll = false)
        return
    }
    val frag = org.json.JSONObject.quote(
        "<div class='prow live'>" + escStepHtml(text.takeLast(8000)) + "</div>")
    val sel = org.json.JSONObject.quote("#live-$turnId")
    val js = "(function(){var el=document.querySelector($sel)||document.getElementById('live');if(!el)return 'missing';" +
        "el.classList.add('live');el.classList.remove('typing');" +
        "var atBottom=(window.innerHeight+window.scrollY)>=document.body.scrollHeight-40;" +
        "el.innerHTML=" + frag + ";" +
        "if(window.__follow!==false&&atBottom)window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));return 'ok';})();"
    try {
        threadWeb.evaluateJavascript(js) { res ->
            if (res != null && res.contains("missing")) {
                runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
            }
        }
    } catch (e: Exception) {
        android.util.Log.d("terminus", "patchLiveTextDom: ${e.message}")
        runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
    }
}

/** Title precedence: manual override > opencode title > first-line
 *  fallback. Legacy builds used one lock for both manual edits and
 *  promoted titles, so first-line locks are recognized and healed. */
internal fun MainActivity.syncServerTitle(id: String) {
    val before = chatStore.get(id) ?: return
    // Codex and Agy can expose a name for reading even though remote renaming
    // is unsupported; other backends use their title capability.
    val canWriteTitle = capsForBackend(backendCatalog, before.backend).title
    val canReadTitle = before.backend == "codex" || before.backend == "agy" || canWriteTitle
    if (!canReadTitle) return
    val localTitle = before.title
    val isFallback = localTitle == "new chat" || localTitle == "untitled" ||
        localTitle.startsWith("📎 ") || chatStore.autoTitleLines(id).contains(localTitle)

    // A locked title matching the generated fallback is a legacy false
    // lock; clear it so the server name can win. A genuine manual name
    // stays locked and is pushed too (migrates pre-v58 renames).
    if (before.titleLocked && !isFallback) {
        val sid = before.remoteId
        if (canWriteTitle && sid.isNotBlank()) Thread {
            try { OpencodeApi.postTitleSet(prefs.serverUrl(), prefs.token(), sid, localTitle, before.backend, id) } catch (_: Exception) {}
        }.start()
        return
    }
    if (before.titleLocked) chatStore.unlockTitle(id)
    val rsid = before.remoteId
    if (rsid.isBlank()) return
    Thread {
        try {
            val t = OpencodeApi.fetchTitle(prefs.serverUrl(), prefs.token(), rsid, before.backend, id).trim()
            if (t.isBlank()) return@Thread
            runOnUiThread {
                val current = chatStore.get(id) ?: return@runOnUiThread
                // A manual rename may land while this fetch is in flight.
                if (current.titleLocked) return@runOnUiThread
                val currentIsFallback = current.title == "new chat" || current.title == "untitled" ||
                    current.title.startsWith("📎 ") || chatStore.autoTitleLines(id).contains(current.title)
                if (current.title == localTitle || currentIsFallback) {
                    if (t != current.title) chatStore.setServerTitle(id, t)
                    renderSessions()
                    if (activeSessionId == id) renderThread(id)
                }
            }
        } catch (_: Exception) {}
    }.start()
}
