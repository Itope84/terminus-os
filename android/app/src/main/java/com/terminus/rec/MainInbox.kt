package com.terminus.rec

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** The delivery inbox and activity sheet. Extension functions of [MainActivity], moved out of it unchanged. */

/** LAN fallback notice: disabled while tailnet-only (flag off = no-op).
 *  Code path kept for re-enable. */
internal fun MainActivity.noteLanFallback(id: String?) {
    if (!OpencodeApi.LAN_FALLBACK_ENABLED) return
    if (!OpencodeApi.consumeLanNotice()) return
    if (id == null || chatStore.get(id) == null) return
    chatStore.addMessage(id, ChatMessage("tool", "tailnet unreachable, using local wifi"))
    if (activeSessionId == id) renderThread(id)
    renderSessions()
}

// ---- Delivery inbox (schedules + alerts) ----

/** A local hand-off or approval is waiting on the person: flag the chat that asked (or clear the flag).
 *  A blank or unknown chat id is ignored. The flag clears when the chat opens (ChatStore.markRead). */
internal fun MainActivity.flagNeedsYou(chatId: String, reason: String, on: Boolean) {
    if (!chatStoreReady || chatId.isBlank() || chatStore.get(chatId) == null) return
    if (on) chatStore.setNeedsYou(chatId, reason, System.currentTimeMillis() + needsYouTtlMs("phone-browser"))
    else chatStore.clearNeedsYou(chatId)
    renderSessions()
}

/** Job → stable thread: per-job `schedule:<name>`, everything else
 *  (reminders, one-shots, system notes) → the single `alerts` thread.
 *  A valid `chat` override on the entry wins: targeted delivery lands
 *  in that exact session thread instead. */
internal fun MainActivity.targetChatFor(job: String, chat: String = ""): String {
    if (chat.matches(Regex("[A-Za-z0-9_:-]{1,80}"))) return chat
    return if (job == "alerts" || job == "system") "alerts" else "schedule:$job"
}

internal fun MainActivity.targetTitleFor(job: String): String =
    if (job == "alerts" || job == "system") "Alerts" else job

/** Quiet hours kill pings, dot remains. Default 22:00–07:00. */
internal fun MainActivity.inQuietHours(): Boolean {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val s = prefs.quietStart()
    val e = prefs.quietEnd()
    return if (s <= e) h in s until e else h >= s || h < e
}

internal fun MainActivity.fmtTime(at: Long): String = try {
    java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(at))
} catch (_: Exception) { "" }

/** `minGapMs` > 0 skips the pull when one ran that recently (used on chat open). Every other caller pulls now. */
internal fun MainActivity.syncInbox(minGapMs: Long = 0L) {
    if (!chatStoreReady) return
    val now = System.currentTimeMillis()
    if (minGapMs > 0 && now - lastInboxPullAt < minGapMs) return
    lastInboxPullAt = now
    Thread {
        val items = try {
            OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, prefs.lastInboxSync())
        } catch (_: Exception) { return@Thread }
        if (items.isEmpty()) return@Thread
        val fresh = mergeInboxStore(items)
        // Alarm links arm at sync time — no open, no tap, ever.
        // Idempotent per link; cancelled times stay dead.
        try {
            for ((_, e) in fresh) WakeAlarm.armFromText(this, "${e.title}\n${e.body}")
        } catch (_: Exception) {}
        runOnUiThread {
            for ((cid, e) in fresh) {
                if (activeSessionId == cid) {
                    renderThread(cid)
                } else if (!inQuietHours()) {
                    ensureNotifPermission()
                    val chat = chatStore.get(cid)
                    if (e.kind == "event" && chat?.waitingOnYou() == true) {
                        ChatNotify.notifyNeedsYou(this, chat.title, chat.needsYou, cid)
                    } else if (e.kind == "reply") {
                        ChatNotify.notifyTurn(this, e.body, cid, chat?.title.orEmpty())
                    } else {
                        ChatNotify.notifyInbox(this, e.job, inboxPreview(e.body.ifBlank { e.title }), cid)
                    }
                }
            }
            // Already looking at it: no dot for the visible tab.
            when (nav.selectedItemId) {
                R.id.tabNotes -> tabDots.remove("notes")
                R.id.tabGallery -> tabDots.remove("gallery")
            }
            paintTabDots()
            noteLanFallback(activeSessionId)
            renderSessions()
        }
    }.start()
}

/** Merge fetched deliveries into their job threads (store writes only,
 *  safe off the UI thread). Same text AND same server timestamp = replay
 *  → drop; same text with a newer timestamp = legit repeat → deliver.
 *  Returns the newly stored rows for the caller's UI/ping pass, and
 *  advances the sync cursor past everything seen. Shared by the backstop
 *  pull and by clear-all, which merges the full log before ACKing it —
 *  so entries this device never synced are kept locally, never lost. */
internal fun MainActivity.mergeInboxStore(items: List<OpencodeApi.InboxItem>): List<Pair<String, OpencodeApi.InboxItem>> {
    val fresh = mutableListOf<Pair<String, OpencodeApi.InboxItem>>()
    var maxAt = prefs.lastInboxSync()
    for (e in items) {
        maxAt = maxOf(maxAt, e.at)
        // Watcher entries never open job threads: they light per-tab
        // dots only (cursor still advances above). No ping, no thread.
        if (e.job == "watch") {
            tabDots.add(watchAreaOf(e.body.ifBlank { e.title }))
            continue
        }
        // Routing, dedupe and the needs-you flag live in landInboxEntry (shared with InboxWorker).
        val cid = landInboxEntry(chatStore, e) ?: continue
        fresh += cid to e
    }
    prefs.setLastInboxSync(maxAt)
    return fresh
}

/** Bell: plain read list — rolling last 24h, newest first. No clear
 *  button by design: old rows age out on their own, and everything
 *  already lives in the job threads as reviewable history. Tap a row
 *  to open the job chat at that message. */
internal fun MainActivity.showInbox() {
    Thread {
        val since = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val items = try {
            OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, since)
                .sortedByDescending { it.at }.take(30)
        } catch (_: Exception) { null }
        runOnUiThread {
            // Bell ≡ openable items: chats with unread replies (local,
            // settled results) head the sheet; server inbox entries
            // follow. Either source can light the bell, both open here —
            // a lit bell never leads to an empty sheet.
            val unreadChats = try {
                chatStore.list().filter { it.unread > 0 }
            } catch (_: Exception) { emptyList() }
            if (items == null && unreadChats.isEmpty()) {
                android.widget.Toast.makeText(this, "Activity unavailable", android.widget.Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            if ((items?.isEmpty() ?: true) && unreadChats.isEmpty()) {
                android.widget.Toast.makeText(this, "nothing in the last 24 hours", android.widget.Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val events = (items ?: emptyList())
            val rows = activityEntries(
                unreadChats.map { Triple(it.id, it.title, it.unread) },
                events.map { e ->
                    Triple(e.job,
                        (e.title.ifBlank { e.body }).replace(Regex("\\s+"), " ").trim().take(80),
                        e.at)
                },
            )
            showActivitySheet(rows) { which ->
                when (val r = rows[which]) {
                    is ActivityEntry.Header -> {}
                    is ActivityEntry.Chat -> {
                        showChat()
                        openThread(r.id)
                    }
                    is ActivityEntry.Event -> {
                        val e = events.getOrNull(r.index) ?: return@showActivitySheet
                        val cid = inboxTarget(e.job, e.chat, e.kind) { chatStore.get(it) != null }
                        chatStore.ensure(cid, targetTitleFor(e.job))
                        showChat()
                        openThread(cid)
                    }
                }
            }
        }
    }.start()
}

/** Structured Activity rows: section headers plus title/preview/time
 *  rows with an unread badge. Same tap behavior as before. */
internal fun MainActivity.activitySheetAdapter(rows: List<ActivityEntry>): android.widget.ListAdapter {
    val dens = resources.displayMetrics.density
    val ink = androidx.core.content.ContextCompat.getColor(this, R.color.t_ink)
    val muted = androidx.core.content.ContextCompat.getColor(this, R.color.t_muted)
    val chip = androidx.core.content.ContextCompat.getColor(this, R.color.t_chip)
    fun pad(v: View, h: Int, top: Int, bottom: Int) {
        v.setPadding((h * dens).toInt(), (top * dens).toInt(), (h * dens).toInt(), (bottom * dens).toInt())
    }
    fun line(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    fun headerView(reuse: View?, title: String): View {
        val tv = (reuse as? TextView) ?: line("", 12f, muted, true)
        (tv as TextView).text = title.uppercase()
        pad(tv, 16, 10, 4)
        return tv
    }
    fun chatRow(reuse: View?, title: String, unread: Int): View {
        val root = (reuse as? android.widget.LinearLayout)?.apply { removeAllViews() }
            ?: android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        pad(root, 16, 8, 8)
        root.minimumHeight = (52 * dens).toInt()
        val top = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val titleView = line(title, 15f, ink, true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val badge = line("$unread new", 13f, ink, true).apply {
            setBackgroundColor(chip)
            val bPad = (4 * dens).toInt()
            setPadding((8 * dens).toInt(), bPad, (8 * dens).toInt(), bPad)
        }
        top.addView(titleView)
        top.addView(badge)
        root.addView(top)
        root.addView(line(
            "$unread unread repl" + if (unread == 1) "y" else "ies", 13f, muted, false))
        return root
    }
    fun eventRow(reuse: View?, job: String, preview: String, at: Long): View {
        val root = (reuse as? android.widget.LinearLayout)?.apply { removeAllViews() }
            ?: android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        pad(root, 16, 8, 8)
        root.minimumHeight = (52 * dens).toInt()
        val top = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val jobView = line(job, 15f, ink, true).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(jobView)
        top.addView(line(fmtTime(at), 13f, muted, false))
        root.addView(top)
        if (preview.isNotBlank()) {
            root.addView(line(preview, 14f, muted, false).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        return root
    }
    return object : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(p: Int) = when (rows[p]) {
            is ActivityEntry.Header -> 0
            is ActivityEntry.Chat -> 1
            is ActivityEntry.Event -> 2
        }
        // Headers display only; rows below stay tappable.
        override fun isEnabled(p: Int) = rows[p] !is ActivityEntry.Header
        override fun getView(p: Int, reuse: View?, parent: android.view.ViewGroup): View =
            when (val r = rows[p]) {
                is ActivityEntry.Header -> headerView(reuse, r.title)
                is ActivityEntry.Chat -> chatRow(reuse, r.title, r.unread)
                is ActivityEntry.Event -> eventRow(reuse, r.job, r.preview, r.at)
            }
    }
}

internal fun MainActivity.showSelectSheet(title: String, rows: List<SelectRow>) {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val v = layoutInflater.inflate(R.layout.sheet_simple_menu, null)
    sheet.setContentView(v)
    val titleView: TextView = v.findViewById(R.id.menuTitle)
    titleView.text = title
    val list: android.widget.ListView = v.findViewById(R.id.menuList)
    list.divider = null
    val adapter = object : android.widget.ArrayAdapter<SelectRow>(this, R.layout.item_select, rows) {
        override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup): View {
            val row = convert ?: layoutInflater.inflate(R.layout.item_select, parent, false)
            val r = getItem(pos) ?: return row
            val name: TextView = row.findViewById(R.id.selectName)
            val sub: TextView = row.findViewById(R.id.selectSub)
            val check: TextView = row.findViewById(R.id.selectCheck)
            name.text = r.title
            if (r.sub.isBlank()) sub.visibility = View.GONE
            else { sub.visibility = View.VISIBLE; sub.text = r.sub }
            check.visibility = if (r.checked) View.VISIBLE else View.GONE
            val icon: android.widget.ImageView = row.findViewById(R.id.selectIcon)
            if (r.icon != 0) { icon.setImageResource(r.icon); icon.visibility = View.VISIBLE } else icon.visibility = View.GONE
            row.alpha = if (r.dim) 0.45f else 1f
            return row
        }
    }
    list.adapter = adapter
    list.setOnItemClickListener { _, _, pos, _ ->
        sheet.dismiss()
        try { rows[pos].action() } catch (_: Exception) {}
    }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}

internal fun MainActivity.showActivitySheet(rows: List<ActivityEntry>, onSelect: (Int) -> Unit) {
    // Lightweight inbox reads as a center modal, not a sheet: the bell
    // sits top, the list is short, glance-and-pick needs no thumb zone.
    val list = android.widget.ListView(this).apply {
        divider = null
        adapter = activitySheetAdapter(rows)
    }
    val dlg = AlertDialog.Builder(this, R.style.CreamDialog)
        .setTitle("Activity")
        .setView(list)
        .setNegativeButton("close", null)
        .create()
    list.setOnItemClickListener { _, _, position, _ ->
        if (rows.getOrNull(position) !is ActivityEntry.Header) {
            dlg.dismiss()
            onSelect(position)
        }
    }
    dlg.show()
}
