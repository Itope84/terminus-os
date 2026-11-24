package com.terminus.rec

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import java.io.File

/** Authenticated downloads and the file and gallery viewers. Extension functions of [MainActivity], moved out of it unchanged. */

/** True for URLs served by our server (gallery/files/ops/notes/echo),
 *  on Tailnet (LAN fallback host only when the flag is on). */
internal fun MainActivity.isServerUrl(u: String): Boolean {
    if (!u.startsWith("http://") && !u.startsWith("https://")) return false
    val base = prefs.serverUrl().trimEnd('/')
    if (base.isNotBlank() && u.startsWith(base)) return true
    if (OpencodeApi.LAN_FALLBACK_ENABLED && u.startsWith(OpencodeApi.LAN_BASE)) return true
    return ChatMarkdown.serverLocalPath(u)
}

/** Route an attachment/file URL exactly like a thread tap: images in
 *  the viewer, server text in-app, everything else to the system. */
internal fun MainActivity.routeAttachmentUrl(raw: String): Boolean {
    val u = raw.trim()
    if (u.isBlank()) return false
    val low = u.lowercase().substringBefore("?").substringBefore("#")
    val isImg = low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
        low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
    if (isImg && (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://"))) {
        openViewer(if (u.startsWith("file://")) u else authedUrl(u))
        return true
    }
    if (isServerUrl(u)) {
        if (isTextFile(u)) openTextFile(authedUrl(u))
        else openExternal(authedUrl(u))
        return true
    }
    if (u.startsWith("file://")) {
        if (isTextFile(u)) openTextFile(u)
        else if (low.endsWith(".pdf")) openExternal(u) else openViewer(u)
        return true
    }
    if (u.startsWith("http://") || u.startsWith("https://")) {
        openExternal(u)
        return true
    }
    return false
}

/** URLs stay clean: in-app auth rides the cookie jar (see
 *  ensureServerCookie), never ?token=. External handoffs get the same
 *  plain URL — no secret minted anywhere in-app, by anyone. */
internal fun MainActivity.authedUrl(u: String): String = u

/** Cookie auth for in-app WebViews: the pass lives in the jar, never
 *  in URLs. Planted on boot and every token save; cleared with a
 *  blank token. Media requests from a local page can count
 *  third-party, so every WebView also gets tuneAuthWebView. */
internal fun MainActivity.ensureServerCookie() {
    try {
        val cm = android.webkit.CookieManager.getInstance()
        val base = prefs.serverUrl().trimEnd('/')
        if (base.isBlank()) return
        val tok = prefs.token()
        cm.setCookie(base, if (tok.isBlank()) "headless-token=; Max-Age=0; Path=/"
            else "headless-token=$tok; Path=/")
        try { cm.flush() } catch (_: Exception) {}
    } catch (_: Exception) {}
}

internal fun MainActivity.tuneAuthWebView(w: WebView) {
    try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (_: Exception) {}
    ensureServerCookie()
}

/** Extensions the WebView cannot render inline: tapping them must
 *  download, never loadUrl (an intercepted PDF renders as a blank
 *  page and the download listener never fires for it). */
internal fun MainActivity.isDownloadFile(u: String): Boolean {
    val low = u.lowercase().substringBefore("?").substringBefore("#")
    return low.endsWith(".pdf") || low.endsWith(".doc") || low.endsWith(".docx") ||
        low.endsWith(".xls") || low.endsWith(".xlsx") || low.endsWith(".ppt") ||
        low.endsWith(".pptx") || low.endsWith(".zip") || low.endsWith(".rar") ||
        low.endsWith(".7z") || low.endsWith(".gz") || low.endsWith(".apk") ||
        low.endsWith(".mp3") || low.endsWith(".wav") || low.endsWith(".ogg") ||
        low.endsWith(".m4a") || low.endsWith(".flac") || low.endsWith(".epub") ||
        low.endsWith(".csv")
}

/** DownloadManager with the bearer header (shares and gallery files
 *  carry cookie auth the download service doesn't have). */
internal fun MainActivity.downloadFile(url: String, userAgent: String, contentDisposition: String?, mime: String?) {
    try {
        val tok = try { prefs.token() } catch (_: Exception) { "" }
        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE)
            as android.app.DownloadManager
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime)
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
            if (tok.isNotBlank()) addRequestHeader("Authorization", "Bearer $tok")
            if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
            setTitle(name)
            setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS, name)
            allowScanningByMediaScanner()
        }
        dm.enqueue(req)
        android.widget.Toast.makeText(this, "downloading $name",
            android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { echoSub.text = "download blocked" }
}

/** Fetch a server URL with the bearer header and hand WebView the bytes.
 *  GET-only: POSTs (gallery uploads) must reach the default loader
 *  untouched, otherwise the body dies here and the page reports a
 *  fake success. The page carries its own bearer header on uploads.
 *  Null for anything not ours (default loader handles it) and on any
 *  failure (falls back to cookie auth). Runs on a WebView thread;
 *  prefs reads are safe there. The original request headers ride along
 *  (Accept especially: the gallery shell serves HTML only to text/html),
 *  with Authorization ours. */
internal fun MainActivity.authedResponse(u: String, headers: Map<String, String>, method: String = "GET", allowCache: Boolean = true): android.webkit.WebResourceResponse? {
    try {
        if (!method.equals("GET", true)) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        if (!isServerUrl(u)) return null
        val tok = try { prefs.token() } catch (_: Exception) { "" }
        if (tok.isBlank()) return null
        // Pictures already fetched once come straight from disk: this path bypasses the WebView's own cache,
        // so every chat open re-downloaded every image over the tailnet (slow away from home wifi).
        val cacheable = allowCache && headers["Range"].isNullOrBlank() && WebAssetCache.eligible(u)
        if (cacheable) WebAssetCache.get(this, u)?.let { WebAssetCache.revalidate(this, u, tok); return it }
        val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        for ((k, v) in headers) {
            if (k.equals("Authorization", true)) continue
            if (k.equals("Range", true)) continue
            conn.setRequestProperty(k, v)
        }
        conn.setRequestProperty("Authorization", "Bearer $tok")
        headers["Range"]?.takeUnless { it.isBlank() }?.let { conn.setRequestProperty("Range", it) }
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()
        val code = conn.responseCode
        if (code != 200 && code != 206) return null
        val mime = conn.contentType?.substringBefore(";")?.trim()
            ?.takeIf { it.contains("/") }
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(u.substringAfterLast(".", "").substringBefore("?").lowercase())
            ?: "application/octet-stream"
        if (cacheable && code == 200 && mime.startsWith("image/")) {
            val len = conn.contentLength
            if (len in 1..WebAssetCache.MAX_FILE) {
                WebAssetCache.put(this, u, mime, conn.inputStream, len)?.let { return it }
                // The body was (partly) consumed by the failed store: fetch it again, uncached.
                try { conn.disconnect() } catch (_: Exception) {}
                return authedResponse(u, headers, method, allowCache = false)
            }
        }
        val headers = mutableMapOf<String, String>()
        conn.headerFields["Content-Range"]?.firstOrNull()?.let { headers["Content-Range"] = it }
        conn.headerFields["Accept-Ranges"]?.firstOrNull()?.let { headers["Accept-Ranges"] = it }
        conn.headerFields["Content-Length"]?.firstOrNull()?.let { headers["Content-Length"] = it }
        return android.webkit.WebResourceResponse(
            mime, null, code,
            if (code == 206) "Partial Content" else "OK",
            headers, conn.inputStream)
    } catch (_: Exception) {
        return null
    }
}

/** True for .txt/.md/.markdown taps (query/fragment ignored). */
internal fun MainActivity.isTextFile(u: String): Boolean {
    val low = u.lowercase().substringBefore("?").substringBefore("#")
    return low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".markdown")
}

internal fun MainActivity.fileNameOf(u: String): String = try {
    val p = u.substringBefore("?").substringBefore("#").substringAfterLast("/")
    java.net.URLDecoder.decode(p, "UTF-8").takeIf { it.isNotBlank() } ?: "file"
} catch (_: Exception) {
    u.substringBefore("?").substringBefore("#").substringAfterLast("/").takeIf { it.isNotBlank() } ?: "file"
}

/** In-app text sheet: fetch bytes (token auto-attached by caller),
 *  2MB cap (larger → offer system-open). txt = pre-wrapped monospace;
 *  md = ChatMarkdown thread CSS, read-only. ✕ closes, no thread nav. */
internal fun MainActivity.openTextFile(url: String) {
    Thread {
        try {
            val isLocal = url.startsWith("file://")
            val name = fileNameOf(url)
            val markdown = url.lowercase().substringBefore("?").substringBefore("#")
                .let { it.endsWith(".md") || it.endsWith(".markdown") }
            val bytes: ByteArray = if (isLocal) {
                val f = java.io.File(java.net.URI(url))
                if (f.length() > 2 * 1024 * 1024) {
                    runOnUiThread { offerSystemOpen(name, url) }
                    return@Thread
                }
                f.readBytes()
            } else {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.connect()
                val len = conn.contentLengthLong
                if (len > 2 * 1024 * 1024) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    runOnUiThread { offerSystemOpen(name, url) }
                    return@Thread
                }
                val cap = 2 * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                try {
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > cap) {
                                runOnUiThread { offerSystemOpen(name, url) }
                                return@Thread
                            }
                            out.write(buf, 0, n)
                        }
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
                out.toByteArray()
            }
            val text = String(bytes, Charsets.UTF_8)
            runOnUiThread { openTextViewer(name, markdown, text, url) }
        } catch (_: Exception) {
            runOnUiThread {
                android.widget.Toast.makeText(this, "can't open file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

/** In-app HTML viewer: bottom sheet with a sandboxed WebView + close.
 *  Scripts run (pages need them) but file access is off and no bridges
 *  are added; back/outside-tap dismisses, so a page can never trap a
 *  tab. Falls back to system-open on any failure. */
internal fun MainActivity.openHtmlViewer(url: String) {
    try {
        val ctx = this
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.CreamBottomSheet)
        val dens = resources.displayMetrics.density
        val ink = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_ink)
        val cream = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_surface)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(cream)
        }
        val header = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val pad = (12 * dens).toInt()
            setPadding(pad, pad, pad, (4 * dens).toInt())
        }
        val title = TextView(ctx).apply {
            text = fileNameOf(url)
            setTextColor(ink)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(ctx).apply {
            text = "✕ close"
            setTextColor(ink)
            textSize = 15f
            setPadding((12 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt())
            setOnClickListener { try { sheet.dismiss() } catch (_: Exception) {} }
        }
        val share = shareIconButton(ink).apply {
            setOnClickListener { shareAttachment(fileNameOf(url), url) }
        }
        header.addView(title)
        header.addView(share)
        header.addView(close)
        root.addView(header)
        val heightPx = (resources.displayMetrics.heightPixels * 0.75).toInt()
        val web = WebView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
        tuneAuthWebView(web)
        root.addView(web)
        sheet.setContentView(root)
        sheet.show()
        // The sheet must not steal vertical drags: WebView is not a
        // nested-scrolling child, so a draggable sheet would dismiss on
        // scroll-up instead of scrolling the page. ✕ and back dismiss.
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
        try { web.loadUrl(url) } catch (_: Exception) {
            try { sheet.dismiss() } catch (_: Exception) {}
            openExternal(url)
        }
    } catch (_: Exception) {
        try { openExternal(url) } catch (_: Exception) {}
    }
}

/** Save bytes to public Downloads (MediaStore, no permission needed on
 *  Q+). Toast confirms; failures never throw into callers. */
internal fun MainActivity.downloadBytes(name: String, bytes: ByteArray, mime: String) {
    Thread {
        try {
            if (bytes.isEmpty()) {
                runOnUiThread { android.widget.Toast.makeText(this, "nothing to download", android.widget.Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val safe = name.ifBlank { "download" }.replace(Regex("[/\\\\]"), "_")
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/terminus")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("media store refused")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw java.io.IOException("media store write failed")
            runOnUiThread { android.widget.Toast.makeText(this, "saved to Downloads/terminus", android.widget.Toast.LENGTH_SHORT).show() }
        } catch (_: Exception) {
            runOnUiThread { android.widget.Toast.makeText(this, "download failed", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }.start()
}

/** Fetch a file:// or http(s) URL (token auto-attached for server URLs),
 *  then save it to Downloads. 25MB cap. */
internal fun MainActivity.downloadUrl(name: String, url: String) {
    Thread {
        try {
            val bytes: ByteArray = if (url.startsWith("file://")) {
                java.io.File(java.net.URI(url)).readBytes()
            } else {
                val conn = java.net.URL(url).openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                // Cookie jar does not apply to raw connections: the pass
                // travels as a header, never in the URL.
                if (isServerUrl(url)) {
                    val tok = prefs.token()
                    if (tok.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $tok")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw java.io.IOException("http ${conn.responseCode}")
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 25 * 1024 * 1024) throw java.io.IOException("over 25MB")
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast(".", "").lowercase()) ?: ""
            downloadBytes(name, bytes, mime)
        } catch (_: Exception) {
            runOnUiThread { android.widget.Toast.makeText(this, "download failed", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }.start()
}

/** Over-2MB text: offer the system handler instead of in-app render. */
internal fun MainActivity.offerSystemOpen(name: String, url: String) {
    try {
        showSelectSheet(name, listOf(
            SelectRow("Share", "Send this file to another app", action = { shareAttachment(name, url) }),
            SelectRow("Open externally", "File is over 2 MB", action = { openExternal(url) }),
            SelectRow("Cancel"),
        ))
    } catch (_: Exception) {}
}

/** Text viewer sheet: title = filename, ✕ closes. Viewer WebView keeps
 *  text taps in-app; anything else leaves for the system handler. */
internal fun MainActivity.openTextViewer(name: String, markdown: Boolean, text: String, sourceUrl: String) {
    try {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this@openTextViewer, R.color.t_surface))
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val title = android.widget.TextView(this).apply {
            this.text = name
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@openTextViewer, R.color.t_ink))
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(16, 16, 8, 16)
        }
        val close = android.widget.Button(this).apply {
            this.text = "✕"
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@openTextViewer, R.color.t_ink))
            setOnClickListener { dlg.dismiss() }
        }
        val dl = android.widget.Button(this).apply {
            this.text = "⤓"
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@openTextViewer, R.color.t_ink))
            setOnClickListener { downloadBytes(name, text.toByteArray(), if (markdown) "text/markdown" else "text/plain") }
        }
        val share = shareIconButton(androidx.core.content.ContextCompat.getColor(
            this@openTextViewer, R.color.t_ink)).apply {
            setOnClickListener { shareAttachment(name, sourceUrl) }
        }
        bar.addView(title, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(share, android.widget.LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(dl, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(close, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        val w = WebView(this).apply {
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        tuneAuthWebView(w)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                return authedResponse(req.url.toString(), req.requestHeaders, req.method)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (isContactUrl(u)) {
                    openContactLink(u)
                    return true
                }
                if (isTextFile(u)) {
                    openTextFile(u)
                    return true
                }
                openExternal(u)
                return true
            }
        }
        root.addView(bar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(w, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f))
        dlg.setContentView(root)
        dlg.setCancelable(true)
        dlg.setOnDismissListener { try { w.destroy() } catch (_: Exception) {} }
        dlg.show()
        val html = if (markdown) {
            ChatMarkdown.threadHtml(name, "", listOf(ChatMessage("assistant", text)), LinkToken.forUrl(this@openTextViewer, prefs), dark = isDark())
        } else {
            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val bg = if (isDark()) "#1A1712" else "#F5F1E6"
            val fg = if (isDark()) "#ECE7DA" else "#262220"
            "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{font-family:monospace;font-size:15px;line-height:1.5;margin:0;padding:12px;" +
                "background:$bg;color:$fg;overflow-wrap:break-word;word-wrap:break-word}" +
                "pre{white-space:pre-wrap;word-break:break-word;margin:0;font-family:monospace}</style>" +
                "</head><body><pre>" + esc + "</pre></body></html>"
        }
        w.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    } catch (_: Exception) {}
}

/** Full-screen image viewer with swipe/‹ › across the chat's images,
 *  counter in the bar, download next to close. Single-url callers get
 *  a one-item gallery (no arrows, download still works). */
internal fun MainActivity.openViewer(url: String) {
    openViewerGallery(listOf(Pair(fileNameOf(url), url)), 0)
}

internal fun MainActivity.openViewerGallery(items: List<Pair<String, String>>, start: Int) {
    if (items.isEmpty()) return
    try {
        var idx = start.coerceIn(items.indices)
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun label(): String {
            val name = items[idx].first.ifBlank { "image" }
            return if (items.size > 1) "$name (${idx + 1}/${items.size})" else name
        }
        val title = android.widget.TextView(this).apply {
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(16, 16, 8, 16)
        }
        val w = WebView(this).apply {
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        tuneAuthWebView(w)
        fun show() {
            title.text = label()
            w.loadUrl(items[idx].second)
        }
        val prev = android.widget.Button(this).apply {
            text = "‹"
            setOnClickListener { if (idx > 0) { idx--; show() } }
        }
        val next = android.widget.Button(this).apply {
            text = "›"
            setOnClickListener { if (idx < items.size - 1) { idx++; show() } }
        }
        val dl = android.widget.Button(this).apply {
            text = "⤓"
            setOnClickListener { downloadUrl(items[idx].first, items[idx].second) }
        }
        val share = shareIconButton(android.graphics.Color.WHITE).apply {
            setOnClickListener { shareAttachment(items[idx].first, items[idx].second) }
        }
        val close = android.widget.Button(this).apply {
            text = "✕"
            setOnClickListener { dlg.dismiss() }
        }
        // Swipe across images: mostly-horizontal fling past 120px. Pinch
        // zoom still works — vertical or short gestures pass through.
        var downX = 0f
        var downY = 0f
        w.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (kotlin.math.abs(dx) > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                        if (dx < 0 && idx < items.size - 1) { idx++; show() }
                        else if (dx > 0 && idx > 0) { idx--; show() }
                    }
                }
            }
            false
        }
        // One client only: a second assignment silently replaces the first, which is what
        // adds the auth header to server images (they loaded blank without it).
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                return authedResponse(req.url.toString(), req.requestHeaders, req.method)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (isContactUrl(u)) {
                    openContactLink(u)
                    return true
                }
                if (isTextFile(u)) {
                    openTextFile(if (u.startsWith("file://")) u else authedUrl(u))
                    return true
                }
                return false
            }
        }
        bar.addView(prev)
        bar.addView(title, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(next)
        bar.addView(share, android.widget.LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(dl)
        bar.addView(close)
        // Single image: arrows would be dead buttons — hide them.
        if (items.size < 2) {
            prev.visibility = View.GONE
            next.visibility = View.GONE
        }
        root.addView(bar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(w, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f))
        dlg.setContentView(root)
        dlg.setCancelable(true)
        dlg.setOnDismissListener { try { w.destroy() } catch (_: Exception) {} }
        dlg.show()
        show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(this, "couldn't open image (${e.message ?: "viewer error"})",
            android.widget.Toast.LENGTH_LONG).show()
    }
}

/** PDFs and other files open outside the thread (system handler first;
 *  error notice when no handler exists — never thread navigation). */
internal fun MainActivity.openExternal(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) {
        android.widget.Toast.makeText(this, "no app to open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}
