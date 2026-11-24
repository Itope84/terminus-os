package com.terminus.rec

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** Intents into the app: chat deep links, shares and selected text. Extension functions of [MainActivity], moved out of it unchanged. */

/** Background inbox backstop (free, no Firebase): WorkManager runs
 *  InboxWorker every 15 min (platform minimum) with the app dead.
 *  KEEP = schedule once, survives reboot, no duplicate workers. */
internal fun MainActivity.scheduleInboxWorker() {
    try {
        val req = androidx.work.PeriodicWorkRequestBuilder<InboxWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build())
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "inbox", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    } catch (_: Exception) {}
}

/** Notifications open their target chat. Ordinary app launches stay on
 *  the home/session list. */
internal fun MainActivity.handleChatIntent(intent: Intent?) {
    val requestedChat = intent?.getStringExtra(ChatNotify.EXTRA_CHAT_ID)
        ?.takeIf { it.isNotBlank() }
    val deep = requestedChat?.takeIf { chatStore.get(it) != null }
    val notificationReply = try {
        intent?.let { androidx.core.app.RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(ChatNotify.REMOTE_REPLY_KEY)?.toString()?.trim()
    } catch (_: Exception) { null }
    if (deep != null) {
        showChat()
        openThread(deep)
        if (!notificationReply.isNullOrBlank()) {
            composerInput.post {
                if (activeSessionId != deep) return@post
                composerInput.setText(notificationReply)
                composerInput.setSelection(notificationReply.length)
                sendChat()
            }
        }
    } else {
        // Plain re-launch (launcher icon, no notification): do nothing.
        // The open thread stays exactly as it was.
        if (!notificationReply.isNullOrBlank() && deep == null) {
            toast("That chat has been deleted")
        }
        return
    }
}

/** Share-sheet entry: SEND / SEND_MULTIPLE routed by the invoked alias.
 *  Terminus chat stages files on the composer; Terminus upload posts
 *  straight to the gallery (queued + retried when locked/offline). */
internal fun MainActivity.handleShareIntent(intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_SEND && action != "android.intent.action.SEND_MULTIPLE") return
    val alias = intent.component?.className ?: ""
    val toUpload = alias.endsWith(".ShareUpload")
    if (!toUpload && !alias.endsWith(".ShareChat")) return
    val uris = mutableListOf<android.net.Uri>()
    if (action == Intent.ACTION_SEND) {
        intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        intent.data?.let { if (it.scheme == "content") uris += it }
    } else {
        intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
    }
    // Shares often carry the same content in EXTRA_STREAM and intent.data:
    // dedupe or one photo stages (and sends) twice with a "2 files" label.
    val distinctUris = uris.distinct()
    // Text/link shares carry no stream: route the text, not silence.
    val sharedText = (intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() })?.trim()
    if (uris.isEmpty() && sharedText.isNullOrBlank()) return
    intent.action = null
    setIntent(intent)
    Thread {
        val files = distinctUris.take(5).mapNotNull {
            try { MediaFiles.saveAttach(this, it) } catch (_: Exception) { null }
        }
        if (files.isEmpty() && sharedText.isNullOrBlank()) {
            runOnUiThread {
                android.widget.Toast.makeText(this, "Couldn't read that file", android.widget.Toast.LENGTH_SHORT).show()
            }
            return@Thread
        }
        runOnUiThread {
            val omitted = distinctUris.size - files.size
            if (omitted > 0) toast(
                if (distinctUris.size > 5) "Using the first 5 of ${distinctUris.size} shared items"
                else "$omitted shared item${if (omitted == 1) "" else "s"} couldn't be read")
            if (toUpload) {
                if (files.isEmpty()) {
                    android.widget.Toast.makeText(this, "Text can't go in the gallery",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                GalleryUploader.queue(this, files)
                // The upload runs in the background; pending() right after queue() is always >0.
                android.widget.Toast.makeText(this,
                    "sending ${files.size} to gallery…", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                // A share launched from another app always asks for its
                // destination, even when a chat happened to be open.
                showShareTargetSheet(files, sharedText)
            }
        }
    }.start()
}

/** Text-selection entry (Ask Terminus): selected text from any app
 *  stages straight into the composer, no share sheet. Never clobbers:
 *  an open thread keeps its draft, otherwise a fresh draft opens. */
internal fun MainActivity.handleProcessText(intent: Intent?) {
    if (intent?.action != "android.intent.action.PROCESS_TEXT") return
    val selected = (intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT")
        ?: intent.getStringExtra(Intent.EXTRA_TEXT))?.toString()?.trim()
    if (selected.isNullOrBlank()) return
    intent.action = null
    setIntent(intent)
    showChat()
    if (!isThreadOpen()) openDraft()
    applySharePayload(emptyList(), selected)
    composerInput.hint = "Ask about this"
}

/** Stage a share into the open thread: files onto the attach strip,
 *  text appended to the composer without clobbering a draft. Caller
 *  opens the thread first; this only lands the payload. */
internal fun MainActivity.applySharePayload(files: List<java.io.File>, sharedText: String?) {
    if (chatPane.visibility != View.VISIBLE) nav.selectedItemId = R.id.tabChat
    if (files.isNotEmpty()) {
        pendingAttaches.addAll(files)
        showAttach()
    }
    // Shared text (link, quote, caption) lands in the
    // composer; never clobbers a draft already there.
    if (!sharedText.isNullOrBlank()) {
        val cur = composerInput.text.toString()
        composerInput.setText(if (cur.isBlank()) sharedText else "$cur\n$sharedText")
    }
    composerInput.hint = "caption or send"
    composerInput.requestFocus()
}

/** Always ask where an external share belongs. Shows the incoming media,
 *  searchable chat history, and an explicit new-chat action. */
internal fun MainActivity.showShareTargetSheet(files: List<java.io.File>, sharedText: String?) {
    nav.selectedItemId = R.id.tabChat
    val chats = try { chatStore.list() } catch (_: Exception) { emptyList() }
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val content = layoutInflater.inflate(R.layout.sheet_share_target, null)
    sheet.setContentView(content)
    val file = files.firstOrNull()
    val payloadName = when {
        files.size > 1 -> "${files.size} attachments"
        file != null -> file.name
        else -> "Text share"
    }
    content.findViewById<TextView>(R.id.sharePayloadName).text = payloadName
    val mediaMeta = when {
        files.size == 1 && file != null -> attachmentMeta(file.name, "file://${file.absolutePath}")
        files.size > 1 -> files.take(3).joinToString(" · ") { attachmentTypeMark(it.name) } +
            if (files.size > 3) " · +${files.size - 3}" else ""
        else -> ""
    }
    val textMeta = sharedText?.replace(Regex("\\s+"), " ")?.take(64).orEmpty()
    content.findViewById<TextView>(R.id.sharePayloadMeta).text =
        listOf(mediaMeta, textMeta).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Message" }
    val payloadThumb: ImageView = content.findViewById(R.id.sharePayloadThumb)
    val payloadType: TextView = content.findViewById(R.id.sharePayloadType)
    payloadType.text = if (file == null) "TEXT" else if (files.size > 1) "${files.size}" else attachmentTypeMark(file.name)
    if (file != null && SessionAdapter.isImagePath(file.name)) {
        bindAttachmentThumbnail("file://${file.absolutePath}", payloadThumb, payloadType)
    }

    var chosen = false
    fun finishWith(action: () -> Unit) {
        chosen = true
        sheet.dismiss()
        action()
    }
    content.findViewById<View>(R.id.shareNewChat).setOnClickListener {
        finishWith {
            showChat()
            openDraft()
            applySharePayload(files, sharedText)
        }
    }

    val list: android.widget.ListView = content.findViewById(R.id.shareChatList)
    list.divider = null
    val empty = content.findViewById<TextView>(R.id.shareChatEmpty)
    list.emptyView = empty
    val visible = chats.toMutableList()
    val adapter = object : android.widget.ArrayAdapter<ChatSession>(this, 0, visible) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_share_target, parent, false)
            val chat = getItem(position) ?: return row
            val title = chat.title.takeIf { it.isNotBlank() } ?: "Untitled chat"
            val avatar = row.findViewById<TextView>(R.id.shareChatAvatar)
            avatar.text = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            val tileColors = intArrayOf(R.color.tile_0, R.color.tile_1, R.color.tile_2, R.color.tile_3,
                R.color.tile_4, R.color.tile_5, R.color.tile_6, R.color.tile_7)
            val color = androidx.core.content.ContextCompat.getColor(this@showShareTargetSheet,
                tileColors[(chat.id.hashCode() and Int.MAX_VALUE) % tileColors.size])
            avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            row.findViewById<TextView>(R.id.shareChatName).text = title
            val backendBadge = row.findViewById<ImageView>(R.id.shareChatBackend)
            backendBadge.setImageResource(agentIcon(chat.backend))
            backendBadge.contentDescription = backendDisplayName(chat.backend)
            val preview = chat.messages.asReversed().firstOrNull { it.text.isNotBlank() }?.text
                ?.replace(Regex("\\s+"), " ")?.take(72)
                .orEmpty()
            row.findViewById<TextView>(R.id.shareChatPreview).text = when {
                chat.unread > 0 -> "$preview${if (preview.isNotBlank()) " · " else ""}${chat.unread} unread"
                preview.isNotBlank() -> preview
                else -> chat.backend.replaceFirstChar { it.uppercase() }
            }
            row.findViewById<TextView>(R.id.shareChatTime).text = SessionAdapter.smartDate(chat.updatedAt)
            return row
        }
    }
    list.adapter = adapter
    list.setOnItemClickListener { _, _, position, _ ->
        val chat = adapter.getItem(position) ?: return@setOnItemClickListener
        finishWith {
            showChat()
            openThread(chat.id)
            applySharePayload(files, sharedText)
        }
    }
    content.findViewById<android.widget.EditText>(R.id.shareChatSearch)
        .addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (query.isBlank()) chats else chats.filter { chat ->
                    chat.title.lowercase().contains(query) || chat.backend.lowercase().contains(query) ||
                        chat.messages.asReversed().take(4).any { it.text.lowercase().contains(query) }
                }
                adapter.clear()
                adapter.addAll(filtered)
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    sheet.setOnDismissListener {
        if (!chosen) files.forEach { try { it.delete() } catch (_: Exception) {} }
    }
    sheet.setOnShowListener {
        val height = (resources.displayMetrics.heightPixels * 0.86f).toInt()
        val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottom?.layoutParams = bottom?.layoutParams?.apply { this.height = height }
        try {
            sheet.behavior.peekHeight = height
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        } catch (_: Exception) {}
    }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}
