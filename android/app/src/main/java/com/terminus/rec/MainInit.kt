package com.terminus.rec

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.recyclerview.widget.LinearLayoutManager
import com.terminus.rec.MainActivity.Companion.recordingsDir

/** Start-up: web view setup, chat screen wiring, search. Extension functions of [MainActivity], moved out of it unchanged. */

/** Files on disk never registered (killed mid-record, app exit) → requeue. */
internal fun MainActivity.recoverOrphans() {
    try {
        val known = chunks.all().map { it.name }.toSet() + ChatVoiceStore(this).all()
        recordingsDir(this).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".m4a") && it.length() > 1024 && it.name !in known }
            ?.forEach { chunks.add(it.name) }
    } catch (_: Exception) {}
}

internal fun MainActivity.setupWeb(w: WebView) {
    tuneAuthWebView(w)
    w.settings.javaScriptEnabled = true
    w.settings.domStorageEnabled = true
    w.settings.allowFileAccess = true
    w.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    if (Build.VERSION.SDK_INT >= 29) w.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
    if (Build.VERSION.SDK_INT >= 33) w.settings.isAlgorithmicDarkeningAllowed = false
    w.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (url == lastLanRetry) lastLanRetry = null
        }
        // Deterministic in-app auth: every server request carries the
        // bearer header, cookie or no cookie. Null return falls back to
        // the default loader (cookie backup). Range forwarded so video
        // seeking keeps working through the interception.
        override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
            return authedResponse(req.url.toString(), req.requestHeaders, req.method)
        }
        override fun onReceivedError(
            view: WebView, request: android.webkit.WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val code = error.errorCode
            val connectish = code == WebViewClient.ERROR_HOST_LOOKUP ||
                code == WebViewClient.ERROR_CONNECT ||
                code == WebViewClient.ERROR_TIMEOUT ||
                code == WebViewClient.ERROR_UNKNOWN
            if (!connectish) return
            if (!OpencodeApi.LAN_FALLBACK_ENABLED) return
            val failing = request.url.toString()
            if (failing == lastLanRetry || failing.startsWith(OpencodeApi.LAN_BASE)) return
            if (!isServerUrl(failing)) return
            lastLanRetry = OpencodeApi.lanSwap(failing)
            OpencodeApi.reportLanGood()
            view.post { view.loadUrl(authedUrl(lastLanRetry!!)) }
        }
        override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
            val u = req.url.toString()
            // Phone and email links hand off to the dialer / mail app; the WebView cannot load them.
            if (isContactUrl(u)) {
                openContactLink(u)
                return true
            }
            // Text files read IN-APP (viewer sheet), never thread nav.
            if (isTextFile(u)) {
                openTextFile(if (u.startsWith("file://")) u else authedUrl(u))
                return true
            }
            // HTML previews in-app (viewer sheet with close), never bare
            // tab navigation (which strands tabs) nor external browser.
            val low = u.lowercase().substringBefore("?").substringBefore("#")
            if (low.endsWith(".html") || low.endsWith(".htm")) {
                openHtmlViewer(if (u.startsWith("file://")) u else authedUrl(u))
                return true
            }
            // Files the WebView can't render go straight to download:
            // loading them shows a blank page and no download fires.
            if (isDownloadFile(u)) {
                downloadFile(u, req.requestHeaders["User-Agent"] ?: "", null, null)
                return true
            }
            val base = prefs.serverUrl().trimEnd('/')
            if (u.startsWith(base) && !u.contains("mobile=1")) {
                val sep = if (u.contains("?")) "&" else "?"
                var target = "$u${sep}mobile=1"
                val tok = prefs.token()   // in-app pages upload/delete with this: master token, never the GET-only link token
                if (tok.isNotBlank() && !target.contains("token=")) {
                    target += "&token=" + android.net.Uri.encode(tok)
                }
                view.loadUrl(target)
                return true
            }
            return false
        }
    }
    w.webChromeClient = object : WebChromeClient() {
        // Fullscreen video: the thread player expands here. Without
        // this the fullscreen button silently does nothing.
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            try {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenDialog = android.app.Dialog(this@setupWeb, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    setContentView(view, android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                    setCancelable(true)
                    setOnDismissListener { onHideCustomView() }
                    show()
                }
            } catch (_: Exception) {
                try { callback.onCustomViewHidden() } catch (_: Exception) {}
            }
        }
        override fun onHideCustomView() {
            try { fullscreenDialog?.dismiss() } catch (_: Exception) {}
            fullscreenDialog = null
            fullscreenView = null
            try { fullscreenCallback?.onCustomViewHidden() } catch (_: Exception) {}
            fullscreenCallback = null
        }
        override fun onShowFileChooser(
            view: WebView, cb: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams
        ): Boolean {
            filePickerCallback?.onReceiveValue(null)
            filePickerCallback = cb
            try {
                filePickerLauncher.launch(params.createIntent())
                return true
            } catch (e: Exception) { /* fall through to generic picker */ }
            return try {
                val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }
                filePickerLauncher.launch(android.content.Intent.createChooser(i, "choose file"))
                true
            } catch (e: Exception) {
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = null
                false
            }
        }
    }
    w.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
        downloadFile(url, userAgent, contentDisposition, mime)
    }
}

internal fun MainActivity.initChat() {
    chatStore = ChatStore(this)
    // A browser card left "live" by a killed app can never update again: close it out.
    try { chatStore.freezeStaleBrowserCards() } catch (_: Exception) {}
    chatPane = findViewById(R.id.chatPane)
    sessionList = findViewById(R.id.sessionList)
    sessionPull = findViewById(R.id.sessionPull)
    sessionPull.setOnRefreshListener { refreshSessions() }
    chatEmpty = findViewById(R.id.chatEmpty)
    sessionSearch = findViewById(R.id.sessionSearch)
    searchToggle = findViewById(R.id.searchToggle)
    searchIcon = findViewById(R.id.searchIcon)
    bellBtn = findViewById(R.id.bellBtn)
    bellBtn.setOnClickListener { showInbox() }
    chatHero = findViewById(R.id.chatHero)
    heroModelBtn = findViewById(R.id.heroModelBtn)
    threadHeader = findViewById(R.id.threadHeader)
    threadTitle = findViewById(R.id.threadTitle)
    threadTitle.setOnClickListener { setThreadTitleExpanded(!threadTitleExpanded) }
    updateThreadTitleDescription()
    threadWeb = findViewById(R.id.threadWeb)
    tuneAuthWebView(threadWeb)
    threadFrame = findViewById(R.id.threadFrame)
    threadDivider = findViewById(R.id.threadDivider)
    goalStrip = findViewById(R.id.goalStrip)
    goalStrip.setOnClickListener { goalManageDialog() }
    goalStripBar = findViewById(R.id.goalStripBar)
    // One-tap exit from watching: the same effect as the sheet's
    // "Remove goal", without a two-tap detour through the sheet.
    findViewById<View>(R.id.goalClear).setOnClickListener { goalClearGoal() }
    composerRow = findViewById(R.id.composerRow)
    composerInput = findViewById(R.id.composerInput)
    chatMic = findViewById(R.id.chatMic)
    chatMicIdleTint = chatMic.imageTintList
    chatRecStatus = findViewById(R.id.chatRecStatus)
    voicePreview = findViewById(R.id.voicePreview)
    voicePreviewLabel = findViewById(R.id.voicePreviewLabel)
    attachPreview = findViewById(R.id.attachPreview)
    attachPreviewLabel = findViewById(R.id.attachPreviewLabel)
    attachThumb = findViewById(R.id.attachThumb)
    findViewById<View>(R.id.attachDrop).setOnClickListener { dropAttach() }
    quoteBar = findViewById(R.id.quoteBar)
    quoteChips = findViewById(R.id.quoteChips)
    quoteEditor = findViewById(R.id.quoteEditor)
    quoteNote = findViewById(R.id.quoteNote)
    findViewById<View>(R.id.quoteEditCancel).setOnClickListener {
        val q = editingQuote
        if (q != null && editingQuoteIsNew) quotes.remove(q)
        closeQuoteEditor()
    }
    findViewById<View>(R.id.quoteDone).setOnClickListener { saveQuoteNote() }
    findViewById<View>(R.id.quoteClear).setOnClickListener { clearQuote() }
    findViewById<View>(R.id.voiceKeep).setOnClickListener { keepVoice() }
    findViewById<View>(R.id.voiceDrop).setOnClickListener { dropVoice() }
    slashList = findViewById(R.id.slashList)
    slashAdapter = android.widget.ArrayAdapter(this, R.layout.item_menu_row, mutableListOf())
    slashList.adapter = slashAdapter
    slashList.setOnItemClickListener { _, _, pos, _ ->
        slashRowCommands.getOrNull(pos)?.let { completeSlash(it) }
    }
    modelChip = findViewById(R.id.modelChip)
    try {
        modelChip.iconSize = dp(12)
        modelChip.iconPadding = dp(4)
        // Official marks carry their own colors; any inherited tint
        // blanks them against the chip (the blank-icon fault).
        modelChip.iconTint = null
    } catch (_: Exception) {}
    attachListBtn = findViewById(R.id.attachListBtn)
    contextRing = findViewById(R.id.contextRing)
    contextRing.setOnClickListener { showContextSheet() }
    findViewById<View>(R.id.threadMenu).setOnClickListener { threadMenuDialog() }
    findViewById<View>(R.id.attachListBtn).setOnClickListener {
        activeSessionId?.let { showChatAttachments(it) }
    }
    sessionList.layoutManager = LinearLayoutManager(this)
    // Tab map changed (Pinned removed: 0 Recent, 1 Unread, 2 Schedules).
    // Old saves: 1 was Pinned (gone → Recent), 2 was Unread (→ 1).
    sessionFilter = when (prefs.sessionFilter()) { 1 -> 0; 2 -> 1; else -> 0 }
    sessionSort = prefs.sessionSort()
    sessControls = findViewById(R.id.sessControls)
    tabAll = findViewById(R.id.tabAll)
    tabUnread = findViewById(R.id.tabUnread)
    tabSchedules = findViewById(R.id.tabSchedules)
    sortBtn = findViewById(R.id.sortBtn)
    iconBackendScope = findViewById(R.id.iconBackendScope)
    // Scope tap lives on the full 48dp frame, not the 20dp glyph:
    // edge taps on the glyph alone used to die silently (multi-tap feel).
    findViewById<View>(R.id.backendScopeBtn).setOnClickListener { backendDialog { renderSessions() } }
    tabAll.setOnClickListener { setSessionFilter(0) }
    tabUnread.setOnClickListener { setSessionFilter(1) }
    tabSchedules.setOnClickListener { setSessionFilter(2) }
    sortBtn.setOnClickListener { showSortMenu() }
    sessionAdapter = SessionAdapter(
        emptyList(),
        onOpen = { openThread(it.id) },
        onDelete = { deleteSessionNow(it) },
        onRename = { s -> renameDialog(s) },
        onFork = { s -> forkChat(s.id, null) },
        onPin = { s ->
            chatStore.setPinned(s.id, !s.pinned)
            renderSessions()
        },
        baseProvider = { prefs.serverUrl() },
        tokenProvider = { prefs.token() },
        onImport = { imp -> importSession(imp) }
    )
    sessionAdapter.onSelectionChanged = { n -> updateSelBar(n) }
    sessionAdapter.onDeleteImport = { imp -> deleteRemoteSession(imp) }
    sessionAdapter.archivedOf = { c -> SessionAdapter.isArchived(c, System.currentTimeMillis(), false) }
    sessionAdapter.onOpenArchived = { setSessionFilter(3) }
    sessionAdapter.onArchive = { c ->
        val was = sessionAdapter.archivedOf(c)
        chatStore.setArchived(c.id, !was)
        toast(if (was) "Moved to Recent" else "Archived")
        renderSessions()
    }
    findViewById<View>(R.id.selDelete).setOnClickListener { deleteSelected() }
    findViewById<View>(R.id.selCancel).setOnClickListener {
        if (sessionAdapterReady) sessionAdapter.clearSelection()
    }
    sessionList.adapter = sessionAdapter
    threadWeb.settings.javaScriptEnabled = true
    threadWeb.settings.allowFileAccess = true
    threadWeb.settings.domStorageEnabled = true // card expand/collapse state survives page reloads (sessionStorage)
    // Paint the View behind the page in the themed window color so loads
    // never flash white in dark mode (the HTML body paints over it).
    threadWeb.setBackgroundColor(
        androidx.core.content.ContextCompat.getColor(this, R.color.t_window))
    // Page itself never scrolls sideways; tables/code scroll internally.
    threadWeb.isHorizontalScrollBarEnabled = false
    // Native gesture handlers own thread messages (ChatMarkdown.__rowAt
    // is hit-test only). Quoting is select text → Quote, never a swipe. Long-press is left
    // to the WebView — native text selection with handles, then the
    // system copy/share menu. No JS gesture actions, no conflicts.
    threadWeb.isLongClickable = true
    // Quote joins the native selection menu (Copy/Share/Select all stay):
    // the subclass adds one item, the system owns the rest.
    threadWeb.onQuote = { text ->
        // Drop the handles so the next passage can be picked straight away.
        threadWeb.evaluateJavascript("(function(){var s=window.getSelection();if(s)s.removeAllRanges();})()", null)
        showQuote("", text)
    }
    val threadGestures = android.view.GestureDetector(this,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                // Tap-to-edit: a failed bubble edits in the composer, in
                // place — no modal. Link taps are excluded (they still
                // navigate), and the gesture never consumes the event.
                try { tapMessageToEdit(e.x, e.y) } catch (_: Exception) {}
                return false
            }
        })
    // Hold a link, number or email to copy it (the link's own text, never a token). Anything else keeps the
    // default long-press (text selection).
    fun copyLink(text: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
            toast("Copied $text".take(80))
        } catch (_: Exception) {}
        threadWeb.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
    threadWeb.setOnLongClickListener {
        val h = threadWeb.hitTestResult
        if (h.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            // A link wrapped around an image: the hit's extra is the IMAGE url (a chip's site icon, a gallery
            // thumbnail), not where the link goes. Ask the WebView for the link's own address.
            val img = h.extra.orEmpty()
            val reply = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                override fun handleMessage(msg: android.os.Message) {
                    val href = msg.data?.getString("url").orEmpty()
                    val text = copyTextFor(href.ifBlank { img })
                    if (text != null) copyLink(text)
                }
            }
            threadWeb.requestFocusNodeHref(reply.obtainMessage())
            true
        } else {
            val text = longPressCopy(h.type, h.extra.orEmpty())
            if (text == null) false else { copyLink(text); true }
        }
    }
    LinkMetaStore.init(this)
    ThreadScrollStore.init(this)
    // Alarm links arm the moment a reply or delivery lands, whatever screen is up (never at render time).
    chatStore.onLanded = { _, text ->
        val armed = WakeAlarm.armFromText(this, text)
        if (armed.isNotEmpty()) runOnUiThread {
            toast("alarm ${WakeAlarm.describeAt(armed.min())} armed" + if (WakeAlarm.canFullScreen(this)) "" else " (rings as a notification)")
        }
        else if (!WakeAlarm.canExact(this)) runOnUiThread { toast("couldn't arm the alarm: allow exact alarms, then tap its card") }
    }
    ChatMarkdown.alarmStateLookup = { at -> WakeAlarm.stateOf(this, at) }
    ChatMarkdown.linkMetaLookup = { url ->
        if (!prefs.linkPreviews()) null
        else LinkMetaStore.get(url)?.let { j ->
            try { org.json.JSONObject(j).let { LinkFetcher.Meta(it.optString("url"), it.optString("title"), it.optString("site")) }.takeIf { it.title.isNotBlank() } } catch (_: Exception) { null }
        }
    }
    threadWeb.addJavascriptInterface(LinkPreviewBridge(prefs) { if (threadWebReady) threadWeb else null }, "TerminusLinks")
    threadWeb.addJavascriptInterface(MapBridge(applicationContext, prefs) { if (threadWebReady) threadWeb else null }, "TerminusMap")
    threadWeb.addJavascriptInterface(JobsBridge(prefs, { activeSessionId }) { if (threadWebReady) threadWeb else null }, "TerminusJobs")
    threadWeb.webViewClient = object : WebViewClient() {
        /** First frame of the new page is ready: show it now. onPageFinished waits for every subresource
         *  (attachment images and videos fetched from the server, link icons), so on a slow connection the
         *  chat stayed hidden behind the loading bars until the last picture arrived. The text is already
         *  laid out here; images fill in as they land. */
        override fun onPageCommitVisible(view: WebView, url: String) {
            if (url == "about:blank") return
            if (view.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
                view.visibility = View.VISIBLE
            }
            setThreadLoading(false)
            // Same pending scroll as onPageFinished, so the reveal lands on the right place, not the top.
            pendingThreadScroll?.let { threadScrollHandler.post(it) }
        }
        override fun onPageFinished(view: WebView, url: String) {
            // openThread replaces the current document directly. Ignore
            // any late completion from an old blank/navigation page so
            // it cannot mark the new thread DOM as ready for JS patches.
            if (url == "about:blank") return
            threadPageLoaded = true
            sendNowKey = ""   // a rebuilt page has no links yet
            refreshSendNowLinks()
            // The page was rebuilt: give the browser card its live state back.
            phoneBrowser?.let { browserCards.onState(it.stateJson(full = true)) }
            // Rebuilt page shows idle speaker icons: restore the stop icon if a reply is still being read.
            replyVoice.restore()
            // First commit reveals a hidden thread view (openThread
            // keeps it invisible to kill the previous-chat flash).
            // Stale finishes after close stay hidden: no open thread.
            if (view.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
                view.visibility = View.VISIBLE
            }
            setThreadLoading(false)
            // loadData resets page scroll to top — replay the pending
            // scroll (bottom for new replies, saved Y for progress
            // updates) once content is laid out, plus one delayed
            // safety repost. Touch truce still wins: a user touch
            // clears pendingThreadScroll first.
            pendingThreadScroll?.let {
                threadScrollHandler.post(it)
                threadScrollHandler.postDelayed(it, 400)
            }
        }
        override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
            val u = req.url.toString()
            try { android.util.Log.d("TAPDBG", "thread-tap $u") } catch (_: Exception) {}
            // Phone and email links hand off to the dialer / mail app; the WebView cannot load them.
            if (isContactUrl(u)) {
                openContactLink(u)
                return true
            }
            // In-thread action (failure rows): retry a failed turn as a
            // fresh send — same handler as the slash command.
            if (u.startsWith("terminus://retry")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    retryTurn(chat, turn)
                } catch (_: Exception) {}
                return true
            }
            // Failed-turn edit: the failed message edits in the composer
            // itself (tap the failed bubble or this link), not a modal.
            // Save rewrites that turn's user row in place and retries it
            // — reconcile-first, so a meanwhile-settled turn delivers
            // instead of doubling.
            if (u.startsWith("terminus://edit")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    enterEditMode(chat, turn)
                } catch (_: Exception) {}
                return true
            }
            // Unknown-outcome recheck: ask the server's exact-turnId
            // registry again without resending anything.
            if (u.startsWith("terminus://reconcile")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    reconcileTurn(chat, turn, tap = true)
                } catch (_: Exception) {}
                return true
            }
            if (u.startsWith("terminus://sendnow")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    sendNowTap(chat, turn)
                } catch (_: Exception) {}
                return true
            }
            if (u.startsWith("terminus://discardqueued")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    discardQueuedTurn(chat, turn)
                } catch (_: Exception) {}
                return true
            }
            // Wake directive from the assistant ("wake me in 20 min"):
            // arm the device alarm the moment the link arrives. The app
            // is open by definition here, so arming is always safe.
            if (u == "terminus://browser") { phoneBrowser?.open(); return true }
            if (u.startsWith("terminus://alarm")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val at = uri.getQueryParameter("at")?.toLongOrNull()
                    val msg = uri.getQueryParameter("msg") ?: ""
                    if (at != null && at > System.currentTimeMillis()) {
                        if (!WakeAlarm.canExact(this@initChat)) {
                            val opened = WakeAlarm.requestExact(this@initChat)
                            toast(if (opened) "allow exact alarms in settings, then tap the alarm link again"
                                else "open system settings → apps → Terminus → allow exact alarms")
                        } else {
                            if (WakeAlarm.armExplicit(this@initChat, at, msg)) {
                                // Armed either way: without full-screen permission it rings as a notification.
                                val note = if (WakeAlarm.canFullScreen(this@initChat)) "" else " (rings as a notification)"
                                toast("alarm ${WakeAlarm.describeAt(at)} · ${msg.ifBlank { "Time to wake up" }}$note")
                                activeSessionId?.let { renderThread(it, scroll = false) }   // the card now says Armed
                            } else toast("couldn't arm the alarm: allow exact alarms in settings")
                        }
                    } else {
                        toast("that alarm time already passed")
                    }
                } catch (_: Exception) {}
                return true
            }
            // History paging: grow the rendered tail, anchored.
            if (u == "terminus://more" || u.startsWith("terminus://more?") || u.startsWith("terminus://more#")) {
                val id = activeSessionId ?: return true
                growHistory(id)
                return true
            }
            // Reply actions use the stable stored message index.
            if (u.startsWith("terminus://reply?")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val i = uri.getQueryParameter("i")?.toIntOrNull() ?: return true
                    val action = uri.getQueryParameter("do")
                    val msg = chatStore.get(chat)?.messages?.getOrNull(i) ?: return true
                    if (msg.role != "assistant") return true
                    if (action == "copy") {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", msg.text))
                        toast("reply copied")
                    } else if (action == "speak") replyVoice.speak("$chat:$i", msg.text)
                    else if (action == "more") replyMoreSheet(chat, i, msg.text)
                } catch (_: Exception) {}
                return true
            }
            // Code block copy button (thread-ui.js): the block's text rides in the link.
            if (u.startsWith("terminus://copy-code?")) {
                try {
                    val text = android.net.Uri.parse(u).getQueryParameter("t").orEmpty()
                    if (text.isNotBlank()) {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
                        toast("code copied")
                    }
                } catch (_: Exception) {}
                return true
            }
            // Step copy: settled command row → clipboard (label + detail).
            if (u.startsWith("terminus://copy-step?")) {
                try {
                    val uri = android.net.Uri.parse(u)
                    val chat = uri.getQueryParameter("chat") ?: return true
                    val turn = uri.getQueryParameter("turn") ?: return true
                    val i = uri.getQueryParameter("i")?.toIntOrNull() ?: return true
                    val msg = try { chatStore.get(chat)?.messages?.firstOrNull { it.turnId == turn } } catch (_: Exception) { null }
                    val steps = ChatMarkdown.stepsOf(msg?.stepsJson ?: "")
                    val s = steps.getOrNull(i) ?: return true
                    val text = listOf(s.label, s.detail).filter { it.isNotBlank() }.joinToString("\n")
                    if (text.isBlank()) return true
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("step", text))
                    runOnUiThread { toast("step copied") }
                } catch (_: Exception) {}
                return true
            }
            val low = u.lowercase().substringBefore("?").substringBefore("#")
            val isImg = low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
                low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
            // Images open in a full-screen pinch-zoom viewer, never as
            // a thread navigation (which 401s without the token anyway).
            if (isImg && (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://"))) {
                openViewer(if (u.startsWith("file://")) u else authedUrl(u))
                return true
            }
            // Server file/gallery links: text reads IN-APP, HTML previews
            // in-app (viewer sheet), video plays in the in-app viewer
            // (authed through the interceptor); PDFs and other files open
            // outside the thread (system handler). This kills the
            // 401-JSON-in-thread bug on tap.
            if (isServerUrl(u)) {
                if (isTextFile(u)) openTextFile(u)
                else if (low.endsWith(".html") || low.endsWith(".htm")) openHtmlViewer(u)
                else if (ChatMarkdown.isVideoUrl(low)) openViewer(u)
                else openExternal(u)
                return true
            }
            if (u.startsWith("file://")) {
                if (isTextFile(u)) openTextFile(u)
                else if (low.endsWith(".pdf")) openExternal(u) else openViewer(u)
                return true
            }
            // External links leave the thread for the system browser.
            if (u.startsWith("http://") || u.startsWith("https://")) {
                openExternal(u)
                return true
            }
            return false
        }
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // Dead renderer = blank thread until something reloads it.
            // Force a full re-render of whatever is open; the hash guard
            // is cleared so loadData always re-issues.
            try {
                threadPageLoaded = false
                lastThreadHtml = ""
                val id = activeSessionId
                if (id != null) renderThread(id, scroll = false)
                else if (draftOpen) renderDraft()
            } catch (_: Exception) {}
            return true
        }
    }

    var threadTouchDownY = 0f
    var threadTouchDownX = 0f
    var pullDownRawY = 0f
    var pullStartedAtBottom = false
    var pullArmed = false
    val threadTouchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    val pullThresholdPx = PULL_THRESHOLD_DP * resources.displayMetrics.density
    val pullDeadzonePx = PULL_DEADZONE_DP * resources.displayMetrics.density
    fun threadAtBottom(): Boolean = try {
        val d = resources.displayMetrics.density
        threadWeb.scrollY + threadWeb.height >= (threadWeb.contentHeight * d).toInt() - 24
    } catch (_: Exception) { false }
    threadWeb.setOnTouchListener { v, e ->
        try { threadGestures.onTouchEvent(e) } catch (_: Exception) {}
        // Pull up at the bottom = manual sync. Observes only (the listener still returns false, so
        // scrolling is untouched): arm on a deliberate upward drag, tick once, sync on release.
        try {
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    threadTouchDownX = e.rawX
                    pullDownRawY = e.rawY
                    pullStartedAtBottom = threadAtBottom()
                    pullArmed = false
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!pullChecking && pullStartedAtBottom && threadAtBottom()) {
                    val drag = pullDownRawY - e.rawY
                    val held = e.eventTime - e.downTime
                    val p = pullProgress(drag, pullDeadzonePx, pullThresholdPx)
                    val armed = pullUpArmed(true, true, drag, e.rawX - threadTouchDownX, pullThresholdPx, held)
                    if (p > 0.15f && held >= PULL_SHOW_AFTER_MS) pullShow(p, armed) else pullHide(animate = false)
                    if (armed && !pullArmed) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    pullArmed = armed
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val fire = pullArmed
                    pullArmed = false
                    if (fire) activeSessionId?.let { syncThread(it, manual = true) } else if (!pullChecking) pullHide()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    pullArmed = false
                    if (!pullChecking) pullHide()
                }
            }
        } catch (_: Exception) {}
        if (e.action == android.view.MotionEvent.ACTION_DOWN) {
            threadTouchDownY = e.y
            threadHistoryGestureUsed = false
        } else if (e.action == android.view.MotionEvent.ACTION_MOVE) {
            // Arm history paging only for a real downward finger drag
            // (content moving toward older messages), never a tap or a
            // programmatic scroll. One drag can trigger one page load.
            if (!threadHistoryGestureUsed && e.y - threadTouchDownY > threadTouchSlop) {
                threadTouchScroll = true
                threadHistoryGestureUsed = true
            }
        }
        if (e.action == android.view.MotionEvent.ACTION_DOWN ||
            e.action == android.view.MotionEvent.ACTION_MOVE) {
            // Touch truce with re-pin: cancel the stale scroll post but
            // immediately pin to the CURRENT position, so the next
            // loadData (which resets to top) replays the touched Y
            // instead of stranding the user at the top with nothing pending.
            val y = try { threadWeb.scrollY } catch (_: Exception) { 0 }
            openAnchor = null
            pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
            pendingThreadScroll = Runnable { try { threadWeb.scrollTo(0, y) } catch (_: Exception) {} }
        }
        false
    }

    jumpLatest = findViewById(R.id.jumpLatest)
    jumpLatest.setOnClickListener { scrollThreadToBottom() }
    threadWeb.setOnScrollChangeListener { v, _, scrollY, _, _ ->
        try {
            val h = v.height
            if (h <= 0) return@setOnScrollChangeListener
            val contentPx = (threadWeb.contentHeight * resources.displayMetrics.density).toInt()
            val gap = contentPx - scrollY - h
            jumpLatest.visibility = if (gap > 300) View.VISIBLE else View.GONE
            // Scroll-up auto-fetch: near the top with hidden history,
            // grow anchored. Guarded + cheap: one check per scroll event,
            // growHistory no-ops when nothing is hidden. Finger-only:
            // programmatic motion (open, bottom pin) never grows.
            if (scrollY <= 200 && threadTouchScroll && threadPageLoaded && !historyGrowing) {
                val id = activeSessionId
                if (id != null) {
                    val vis = threadVisible[id] ?: threadPageSize
                    val total = try { chatStore.get(id)?.messages?.size ?: 0 } catch (_: Exception) { 0 }
                    if (total > vis) growHistory(id)
                }
            }
        } catch (_: Exception) {}
    }

    findViewById<View>(R.id.newChatBtn).setOnClickListener {
        openDraft()
    }
    findViewById<View>(R.id.threadBack).setOnClickListener { closeThread() }
    findViewById<View>(R.id.hintSearch).setOnClickListener { hintTap("/search ") }
    findViewById<View>(R.id.hintFetch).setOnClickListener { hintTap("/fetch ") }
    findViewById<View>(R.id.hintThink).setOnClickListener { hintTap("/think ") }
    heroModelBtn.setOnClickListener {
        openDraft()
    }
    findViewById<View>(R.id.attachBtn).setOnClickListener {
        attachSheetDialog()
    }
    stopBtn = findViewById(R.id.stopBtn)
    stopBtn.setOnTouchListener { v, e ->
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(.88f).scaleY(.88f).setDuration(120L).setInterpolator(easeOut).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(easeOut).start()
        }
        false
    }
    stopBtn.setOnClickListener {
        val id = activeSessionId
        if (id != null) {
            // Same rule as visibility: any live placeholder is stoppable.
            // Interrupting a stale/unknown turn is meaningful (the remote
            // run may still be alive); success drops its placeholder.
            // Re-check under the tap: a stale-visible button corrects
            // itself here instead of acting on dead state.
            val liveNow = hasPlaceholder(id)
            updateStopBtn()
            if (liveNow) {
                handleCommand(id, "/stop")
            } else {
                android.widget.Toast.makeText(this, "No reply running", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    findViewById<View>(R.id.sendBtn).setOnTouchListener { v, e ->
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(.88f).scaleY(.88f).setDuration(120L).setInterpolator(easeOut).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(easeOut).start()
        }
        false
    }
    findViewById<View>(R.id.sendBtn).setOnClickListener { sendChat() }
    chatMic.setOnClickListener { onChatMic() }
    modelChip.setOnClickListener { modelDialog() }
    searchToggle.setOnClickListener { toggleSearch() }
    sessionSearch.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            sessionQuery = s?.toString()?.trim() ?: ""
            // Text in the box means the box is open. After the activity is recreated Android restores the
            // text but not our flag, which left the filter applied with its field hidden.
            if (sessionQuery.isNotBlank()) searchOpen = true
            // Debounced: the query scan walks message bodies per session,
            // so one render per pause, not one per keystroke.
            try { sessionSearch.removeCallbacks(searchRenderDebounce) } catch (_: Exception) {}
            try { sessionSearch.postDelayed(searchRenderDebounce, 200) } catch (_: Exception) { renderSessions() }
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    composerInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            val t = s?.toString() ?: ""
            val id = activeSessionId
            if (id == null && draftOpen) draftText = t
            renderSlashHints(t)
            updateComposerSlot()

            if (id != null && !t.startsWith("/")) {
                // Debounced: one store write per pause, not per character.
                // A send/close/pause always flushes or cancels first, so
                // the pending text here can never overwrite fresher state.
                dropPendingDraft()
                val save = Runnable {
                    pendingDraftSave = null
                    pendingDraftId = null
                    try { chatStore.setDraft(id, t) } catch (_: Exception) {}
                }
                pendingDraftSave = save
                pendingDraftId = id
                try { draftHandler.postDelayed(save, 350) } catch (_: Exception) { save.run() }
            }
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    composerInput.setOnEditorActionListener { _, actionId, _ ->
        if (slashList.visibility == View.VISIBLE && slashShown.isNotEmpty()) {
            completeSlash(slashShown[0]); true
        } else if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendChat(); true }
        else false
    }
    composerInput.setOnKeyListener { _, keyCode, e ->
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && e.action == android.view.KeyEvent.ACTION_DOWN &&
            slashList.visibility == View.VISIBLE && slashShown.isNotEmpty()) {
            completeSlash(slashShown[0]); true
        } else false
    }
    renderSessions()
    // Load backend capabilities at startup. Attachment choices depend on
    // this catalog; otherwise non-OpenCode chats were temporarily treated
    // as unsupported until the settings sheet happened to refresh it.
    refreshBackends()
    refreshModels()
}

/** Empty-state hint row: open (or reuse) a thread, pre-fill the command, focus. */
internal fun MainActivity.hintTap(cmd: String) {
    if (activeSessionId == null && !draftOpen) openDraft()
    composerInput.setText(cmd)
    composerInput.setSelection(cmd.length)
    composerInput.requestFocus()
    composerInput.post {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(composerInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
}

internal fun MainActivity.showChat() {
    echoPane.visibility = View.GONE
    galleryPane.visibility = View.GONE
    opsPane.visibility = View.GONE
    notesPane.visibility = View.GONE
    chatPane.visibility = View.VISIBLE
    crossFade(chatPane)
    migrateBindingsOnce()
    // Cold start with nothing cached loads nothing on its own (imports
    // only refresh on pull, and the hero hides the pull control) — an
    // empty app could never bootstrap. Sync once when imports are empty;
    // failures toast and the list is unchanged.
    if (serverImports.isEmpty()) refreshSessions()
    renderSessions()
    val openId = activeSessionId
    if (openId != null) renderThread(openId) else if (draftOpen) renderDraft()
    syncNavForThread()
    syncInbox()
}

/** One-shot upgrade to self-owned session ids: adopt the legacy server
 *  bindings as local remoteIds (fills blanks only, never overwrites).
 *  Flag-first so it runs once even if the fetch fails; a ghost id
 *  self-heals on first send (404 → clear → fresh). */
internal fun MainActivity.migrateBindingsOnce() {
    if (!chatStoreReady) return
    try { if (prefs.migratedBindings()) return } catch (_: Exception) { return }
    try { prefs.setMigratedBindings() } catch (_: Exception) {}
    Thread {
        try {
            val got = OpencodeApi.fetchBindings(prefs.serverUrl(), prefs.token())
            if (got.isNotEmpty()) {
                for ((cid, sid) in got) {
                    try {
                        if (chatStore.get(cid)?.remoteId.isNullOrBlank()) chatStore.setRemoteId(cid, sid)
                    } catch (_: Exception) {}
                }
                runOnUiThread { renderSessions() }
            }
        } catch (_: Exception) {}
    }.start()
}

/** Header ⌕ toggles the search field (autofocused); ✕ clears + hides. */
internal fun MainActivity.toggleSearch() {
    if (isThreadOpen()) return
    if (searchOpen) hideSearch() else showSearch()
}

internal fun MainActivity.showSearch() {
    searchOpen = true
    sessionSearch.visibility = View.VISIBLE
    searchIcon.setImageResource(R.drawable.ic_close)
    sessionSearch.requestFocus()
    sessionSearch.post {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(sessionSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    renderSessions()
}

internal fun MainActivity.hideSearch() {
    searchOpen = false
    sessionQuery = ""
    sessionSearch.setText("")
    sessionSearch.visibility = View.GONE
    searchIcon.setImageResource(R.drawable.ic_search)
    try {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(sessionSearch.windowToken, 0)
    } catch (_: Exception) {}
    try { sessionSearch.removeCallbacks(searchRenderDebounce) } catch (_: Exception) {}
    renderSessions()
}
