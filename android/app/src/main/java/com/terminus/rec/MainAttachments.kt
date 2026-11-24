package com.terminus.rec

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** The attachments strip shown inside a chat. Extension functions of [MainActivity], moved out of it unchanged. */

/** 📎 in the thread header: every attachment in this chat, newest
 *  first. Button shows only when the chat actually has attachments. */
internal fun MainActivity.showChatAttachments(id: String) {
    // Field attachments (you sent) + files the assistant mentioned in
    // prose (it sent): same sheet, identical URL mapping. Retroactive —
    // old chats gain their agent artifacts with no migration.
    val atts = try {
        val msgs = chatStore.get(id)?.messages ?: emptyList()
        fun isAvailableAttachment(url: String): Boolean {
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) return true
            if (!url.startsWith("file://", true)) return false
            return try {
                File(java.net.URI(url)).isFile
            } catch (_: Exception) {
                // Older saved paths may not be URI-escaped (for example,
                // paths containing spaces); still check the actual path.
                File(url.removePrefix("file://")).isFile
            }
        }
        val fielded = msgs.flatMap { m ->
            m.allAttachments.mapNotNull { raw ->
                val a = raw.trim().takeUnless { it.isBlank() } ?: return@mapNotNull null
                val url = if (a.startsWith("http://") || a.startsWith("https://") || a.startsWith("file://")) a
                    else "file://$a"
                if (isAvailableAttachment(url)) Pair(fileNameOf(url), url) else null
            }
        }
        val mentioned = msgs
            .filter { it.role == "assistant" }
            .flatMap { extractMentionedFiles(it.text) }
            .mapNotNull { p ->
                val url = if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("file://")) p
                    else "file://$p"
                if (isAvailableAttachment(url)) Pair(fileNameOf(url), url) else null
            }
        (fielded + mentioned).distinctBy { it.second }.asReversed()
    } catch (_: Exception) { emptyList() }
    if (atts.isEmpty()) {
        android.widget.Toast.makeText(this, "no attachments in this chat", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val shownAtts = atts.toMutableList()
    fun openAttachment(which: Int) {
        val pair = shownAtts.getOrNull(which) ?: return
        val (name, url) = pair
        val low = url.lowercase().substringBefore("?").substringBefore("#")
        val previewable = isTextFile(url) || low.endsWith(".pdf") ||
            low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
            low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
        if (previewable) {
            // Images open in the gallery viewer (swipe across all chat
            // images); text reads in-app; PDFs try the system handler.
            if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
                low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")) {
                val imgs = atts.filter { (_, u) ->
                    val l = u.lowercase().substringBefore("?").substringBefore("#")
                    l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") ||
                        l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".avif")
                }
                val at = imgs.indexOfFirst { it.second == url }.coerceAtLeast(0)
                openViewerGallery(imgs, at)
            } else routeAttachmentUrl(url)
        } else {
            // No in-app preview and possibly no system handler (e.g. no
            // PDF reader): download first, system-open second.
            showSelectSheet(name, listOf(
                SelectRow("Share", "Send this file to another app", action = { shareAttachment(name, url) }),
                SelectRow("Download", "No in-app preview for this file type", action = { downloadUrl(name, url) }),
                SelectRow("Open with…", action = { routeAttachmentUrl(url) }),
            ))
        }
    }
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val content = layoutInflater.inflate(R.layout.sheet_simple_menu, null)
    sheet.setContentView(content)
    content.findViewById<TextView>(R.id.menuTitle).text = "Attachments (${atts.size})"
    val list: android.widget.ListView = content.findViewById(R.id.menuList)
    list.divider = null
    fun attachmentCategory(name: String): String {
        val mime = MediaFiles.mimeFor(name).lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            SessionAdapter.isImagePath(name) -> "Images"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            mime.startsWith("text/") || mime == "application/pdf" ||
                ext in setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv", "json") -> "Documents"
            else -> "Files"
        }
    }
    val categories = listOf("Images", "Video", "Audio", "Documents", "Files")
        .filter { category -> atts.any { attachmentCategory(it.first) == category } }
    var selectedCategory = if ("Images" in categories) "Images" else categories.first()
    var attachPick: (Pair<String, String>) -> Unit = {}
    val adapter = object : android.widget.ArrayAdapter<Pair<String, String>>(this, 0, shownAtts) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_attachment, parent, false)
            val (name, url) = getItem(position) ?: return row
            val image: ImageView = row.findViewById(R.id.attachmentThumb)
            val type: TextView = row.findViewById(R.id.attachmentType)
            row.findViewById<TextView>(R.id.attachmentName).text = name
            row.findViewById<TextView>(R.id.attachmentMeta).text = attachmentMeta(name, url)
            val item = Pair(name, url)
            row.setOnClickListener { attachPick(item) }
            row.findViewById<View>(R.id.attachmentShareButton).setOnClickListener {
                shareAttachment(name, url)
            }
            image.tag = null
            image.setImageDrawable(null)
            image.visibility = View.GONE
            type.visibility = View.VISIBLE
            type.text = attachmentTypeMark(name)
            if (SessionAdapter.isImagePath(url)) bindAttachmentThumbnail(url, image, type)
            return row
        }
    }
    list.adapter = adapter
    val tabs = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
    }
    val tabScroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(16), 0, dp(16), 0)
        addView(tabs, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    (content as android.widget.LinearLayout).addView(tabScroll, 1,
        android.widget.LinearLayout.LayoutParams(-1, -2))
    val title = content.findViewById<TextView>(R.id.menuTitle)
    val tabViews = linkedMapOf<String, TextView>()
    fun selectCategory(category: String) {
        selectedCategory = category
        shownAtts.clear()
        shownAtts.addAll(atts.filter { attachmentCategory(it.first) == category })
        adapter.notifyDataSetChanged()
        title.text = "Attachments · $category (${shownAtts.size})"
        tabViews.forEach { (label, view) ->
            val chosen = label == category
            view.setTextColor(getColor(if (chosen) R.color.t_ink else R.color.t_muted))
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(getColor(if (chosen) R.color.t_chip else R.color.t_surface))
                setStroke(dp(1), getColor(R.color.t_line))
            }
        }
    }
    categories.forEach { category ->
        val count = atts.count { attachmentCategory(it.first) == category }
        val tab = TextView(this).apply {
            text = "$category  $count"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { selectCategory(category) }
        }
        val lp = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        tabs.addView(tab, lp)
        tabViews[category] = tab
    }
    selectCategory(selectedCategory)
    fun pickAttachment(pick: Pair<String, String>) {
        sheet.dismiss()
        // Post so the sheet's window is gone before the viewer/dialog opens on top of it.
        // Main-thread handler, not list.post: the list is detached once the sheet dismisses, and a
        // view post on a detached view never runs.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { openAttachment(shownAtts.indexOf(pick).coerceAtLeast(0)) }
            catch (e: Exception) {
                android.widget.Toast.makeText(this, "couldn't open ${pick.first}",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Rows carry their own click: a focusable/clickable child (the share button) can swallow the
    // ListView's item click, so the row handles the tap itself and the list listener is only a fallback.
    attachPick = { pickAttachment(it) }
    list.setOnItemClickListener { _, _, position, _ -> shownAtts.getOrNull(position)?.let { pickAttachment(it) } }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}

internal fun MainActivity.attachmentTypeMark(name: String): String {
    val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase()
    return when {
        SessionAdapter.isImagePath(name) -> ext?.take(4) ?: "IMAGE"
        MediaFiles.mimeFor(name).startsWith("video/") -> "VIDEO"
        MediaFiles.mimeFor(name).startsWith("audio/") -> "AUDIO"
        ext != null -> ext.take(4)
        else -> "FILE"
    }
}

internal fun MainActivity.attachmentMeta(name: String, url: String): String {
    val mime = MediaFiles.mimeFor(name)
    val kind = when {
        SessionAdapter.isImagePath(name) -> "Image"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime == "application/pdf" -> "PDF document"
        mime.startsWith("text/") -> "Text document"
        else -> "File"
    }
    val size = try {
        val file = if (url.startsWith("file://")) File(url.removePrefix("file://")) else null
        file?.takeIf { it.isFile }?.length()?.let { bytes ->
            if (bytes < 1024L) "$bytes B" else if (bytes < 1024L * 1024L)
                "${bytes / 1024L} KB" else "${bytes / (1024L * 1024L)} MB"
        }
    } catch (_: Exception) { null }
    return if (size == null) kind else "$kind · $size"
}

/** Small, bounded thumbnail reads for the attachment sheet. Server
 *  credentials are sent only to the configured Terminus server. */
internal fun MainActivity.bindAttachmentThumbnail(url: String, image: ImageView, type: TextView) {
    image.tag = url
    attachmentPreviewCache.get(url)?.let { bitmap ->
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        type.visibility = View.GONE
        return
    }
    attachmentPreviewExec.execute {
        val bitmap = loadAttachmentThumbnail(url)
        if (bitmap != null) attachmentPreviewCache.put(url, bitmap)
        image.post {
            if (image.tag != url) return@post
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                type.visibility = View.GONE
            }
        }
    }
}

internal fun MainActivity.loadAttachmentThumbnail(url: String): android.graphics.Bitmap? {
    var connection: java.net.HttpURLConnection? = null
    return try {
        val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
            connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                if (isServerUrl(url)) {
                    val token = prefs.token()
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
            }
            if (connection!!.responseCode !in 200..299) return null
            connection!!.inputStream.use { readPreviewBytes(it) } ?: return null
        } else {
            val file = File(url.removePrefix("file://"))
            if (!file.isFile || file.length() <= 0 || file.length() > 12L * 1024 * 1024) return null
            file.inputStream().use { readPreviewBytes(it) } ?: return null
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256 && sample < 32) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

internal fun MainActivity.readPreviewBytes(input: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > 12 * 1024 * 1024) return null
        out.write(buffer, 0, count)
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

internal fun MainActivity.shareAttachment(name: String, url: String) {
    pruneShareExports()
    Thread {
        var export: File? = null
        var downloadConnection: java.net.HttpURLConnection? = null
        try {
            val dir = File(cacheDir, "share-out").apply { mkdirs() }
            val safeName = name.ifBlank { "attachment" }.takeLast(120)
                .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
            export = File(dir, "${System.currentTimeMillis()}_$safeName")
            val input = when {
                url.startsWith("file://") -> File(url.removePrefix("file://")).inputStream()
                !url.startsWith("http://") && !url.startsWith("https://") -> File(url).inputStream()
                else -> {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        if (isServerUrl(url)) {
                            val token = prefs.token()
                            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                    downloadConnection = conn
                    if (conn.responseCode !in 200..299) {
                        conn.disconnect()
                        downloadConnection = null
                        throw java.io.IOException("download failed")
                    }
                    conn.inputStream
                }
            }
            try {
                input.use { source ->
                    export!!.outputStream().use { sink ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > 50L * 1024 * 1024) throw java.io.IOException("file is over 50 MB")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                downloadConnection?.disconnect()
                downloadConnection = null
            }
            if (export!!.length() <= 0) throw java.io.IOException("empty file")
            runOnUiThread {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.files", export!!)
                    val mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                        ?: MediaFiles.mimeFor(name)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime.ifBlank { "application/octet-stream" }
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        clipData = android.content.ClipData.newUri(contentResolver, name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share attachment").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    val cleanup = export!!
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { try { cleanup.delete() } catch (_: Exception) {} },
                        24L * 60 * 60 * 1000)
                } catch (_: Exception) {
                    try { export?.delete() } catch (_: Exception) {}
                    toast("couldn't share attachment")
                }
            }
        } catch (e: Exception) {
            try { downloadConnection?.disconnect() } catch (_: Exception) {}
            try { export?.delete() } catch (_: Exception) {}
            runOnUiThread { toast(if (e.message?.contains("50 MB") == true) e.message!! else "couldn't prepare attachment") }
        }
    }.start()
}

/** Cache exports are temporary handoff copies, never attachment storage.
 *  Keep them available long enough for another app to consume the URI,
 *  then prune on launch and before the next share. */
internal fun MainActivity.pruneShareExports() {
    try {
        val dir = File(cacheDir, "share-out")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        files.filter { it.lastModified() < cutoff }.forEach {
            try { it.delete() } catch (_: Exception) {}
        }
        var kept = 0L
        files.filter { it.exists() && it.lastModified() >= cutoff }.forEach { file ->
            kept += file.length()
            if (kept > 100L * 1024 * 1024) try { file.delete() } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.shareIconButton(tint: Int): ImageButton = ImageButton(this).apply {
    setImageResource(R.drawable.ic_share)
    imageTintList = android.content.res.ColorStateList.valueOf(tint)
    contentDescription = "Share attachment"
    val value = android.util.TypedValue()
    if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
        setBackgroundResource(value.resourceId)
    } else setBackgroundColor(android.graphics.Color.TRANSPARENT)
    minimumWidth = dp(48)
    minimumHeight = dp(48)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    setPadding(dp(12), dp(12), dp(12), dp(12))
}
