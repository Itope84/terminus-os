package com.terminus.rec

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pure row signature for the list diff (unit-tested). Every visible
 *  attribute joins it — notably unread (badge) and live (replying…) — so
 *  the adapter's rendered-rows snapshot always detects badge and activity
 *  flips even though ChatStore mutates sessions in place. */
internal fun sessionRowSig(s: ChatSession, live: Boolean, images: Int, files: Int, deleting: Boolean = false): String {
    val last = s.messages.lastOrNull()
    return listOf(
        s.title, s.pinned, s.unread, s.updatedAt, s.messages.size, s.needsYou, s.needsYouUntil,
        s.backend, s.model,
        last?.role, last?.text?.take(80), last?.attachment, last?.durationSec,
        images, files, live, deleting,
    ).joinToString("|")
}

/** Session list rows: initial tile + optional image thumbnail, smart dates,
 *  attachment counts, unread badges, per-row ⋮ menu (rename/pin/delete).
 *  Delete is confirm-free + id-bound; the caller owns both-sides removal. */
class SessionAdapter(
    private var items: List<ChatSession>,
    private val onOpen: (ChatSession) -> Unit,
    private val onDelete: (ChatSession) -> Unit,
    private val onRename: (ChatSession) -> Unit,
    private val onFork: (ChatSession) -> Unit = {},
    private val onPin: (ChatSession) -> Unit = {},
    private val baseProvider: () -> String = { "" },
    private val tokenProvider: () -> String = { "" },
    private val onImport: (OpencodeApi.RemoteSession) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val thumbExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()
    private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(40) {
        override fun sizeOf(k: String, v: android.graphics.Bitmap): Int =
            (v.byteCount / 1024).coerceAtLeast(1)
    }
    private val thumbClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.sessThumb)
        val check: TextView = v.findViewById(R.id.sessCheck)
        val icon: TextView = v.findViewById(R.id.sessIcon)
        val title: TextView = v.findViewById(R.id.sessTitle)
        val preview: TextView = v.findViewById(R.id.sessPreview)
        val meta: TextView = v.findViewById(R.id.sessMeta)
        val sysBadge: TextView = v.findViewById(R.id.sessSysBadge)
        val time: TextView = v.findViewById(R.id.sessTime)
        val badge: View = v.findViewById(R.id.sessBadge)
        val pin: ImageView = v.findViewById(R.id.sessPin)
        val menu: TextView = v.findViewById(R.id.sessMenu)
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.sectionLabel)
    }

    class BannerVH(v: View) : RecyclerView.ViewHolder(v) {
        val kicker: TextView = v.findViewById(R.id.bannerKicker)
        val title: TextView = v.findViewById(R.id.bannerTitle)
        val sub: TextView = v.findViewById(R.id.bannerSub)
        val open: View = v.findViewById(R.id.bannerOpen)
    }

    class BriefVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.briefText)
    }

    /** One list entry: a chat row, an importable server row, a section header, or the morning-brief card. */
    private class Entry(
        val kind: Int,
        val session: ChatSession? = null,
        val imp: OpencodeApi.RemoteSession? = null,
        val label: String = "",
    )

    /** Date sections (Needs you, Pinned, Today, Yesterday, ...) and the brief card. The host turns this on only
     *  for the plain recent-sorted list: not while searching, filtering by tab, or sorting by name or activity. */
    var sections: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            refreshRows()
        }

    /** Bulk selection: long-press enters, tap toggles, ⋮ menu still works.
     *  The host observes onSelectionChanged for the action bar. */
    val selectedIds = mutableSetOf<String>()
    /** Selected server-only sessions (bulk delete kills them by sessionID). */
    val selectedImports = mutableSetOf<String>()
    var selectionMode: Boolean = false
        private set
    var onSelectionChanged: (Int) -> Unit = {}

    fun selectionCount(): Int = selectedIds.size + selectedImports.size

    fun clearSelection() {
        if (selectedIds.isEmpty() && selectedImports.isEmpty() && !selectionMode) return
        selectedIds.clear()
        selectedImports.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun toggleSelect(s: ChatSession) {
        if (selectedIds.remove(s.id)) {
            if (selectedIds.isEmpty() && selectedImports.isEmpty()) selectionMode = false
        } else {
            selectedIds.add(s.id)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectionCount())
    }

    private fun toggleSelectImport(imp: OpencodeApi.RemoteSession) {
        if (selectedImports.remove(imp.id)) {
            if (selectedIds.isEmpty() && selectedImports.isEmpty()) selectionMode = false
        } else {
            selectedImports.add(imp.id)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectionCount())
    }

    /** Not-yet-opened server row: title + time, muted. Tap imports; ⋮ deletes
     *  the server session (confirm-free, like local deletes). */
    var onDeleteImport: (OpencodeApi.RemoteSession) -> Unit = {}

    /** Archive: the host decides what counts as archived (pinned, unread and schedule rules live there). */
    var onArchive: (ChatSession) -> Unit = {}
    var archivedOf: (ChatSession) -> Boolean = { false }
    var onOpenArchived: () -> Unit = {}
    /** Archived chats hidden from this list; > 0 adds an "Archived · N" row at the bottom. */
    var archivedCount: Int = 0
        set(v) { if (field != v) { field = v; refreshRows() } }
    /** The list is the archive itself: a header names it. */
    var archivedView: Boolean = false
        set(v) { if (field != v) { field = v; refreshRows() } }
    private var imports: List<OpencodeApi.RemoteSession> = emptyList()

    /** Immutable snapshot of the last rendered row signatures. ChatStore
     *  and the adapter share mutable ChatSession references, so rebuilding
     *  "old" rows from this.items after an in-place rename/message append
     *  would already contain the new values and DiffUtil would skip the
     *  rebind. Diff against this snapshot instead, then replace it. */
    private var renderedRows: List<Row> = emptyList()

    private var entries: List<Entry> = emptyList()

    init {
        entries = buildEntries()
        renderedRows = rows()
    }

    /** Flat entries for the list: optional brief card, then rows with a header before each bucket, then imports. */
    private fun buildEntries(): List<Entry> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Entry>(items.size + imports.size + 6)
        var chats = items
        if (sections) {
            // The chat waiting on the person goes in a banner above everything; it is not repeated as a row.
            // Other waiting chats stay under the "Needs you" header.
            val urgent = items.firstOrNull { it.waitingOnYou(now) }
            if (urgent != null) {
                out += Entry(4, session = urgent, label = needsYouAge(urgent.updatedAt, now) + "\n" + urgent.needsYou)
                chats = items.filter { it.id != urgent.id }
            }
            var card: Pair<ChatSession, String>? = null
            for (s in chats) {
                val last = s.messages.lastOrNull { it.role == "assistant" } ?: continue
                val text = briefCardText(s.id, s.unread, last.text, last.ts, now) ?: continue
                card = s to text
                break
            }
            if (card != null) {
                out += Entry(3, session = card.first, label = card.second)
                chats = chats.filter { it.id != card.first.id }   // the card stands in for the row
            }
            val buckets = chats.map { listBucket(it.waitingOnYou(now), it.pinned, it.updatedAt, now) }
            for ((bucket, i) in sectionPlan(buckets)) {
                out += if (bucket != null) Entry(2, label = bucket.label) else Entry(0, session = chats[i])
            }
        } else {
            chats.forEach { out += Entry(0, session = it) }
        }
        imports.forEach { out += Entry(1, imp = it) }
        if (archivedView) out.add(0, Entry(2, label = "Archived"))
        else if (archivedCount > 0) out += Entry(5, label = "Archived · $archivedCount")
        return out
    }

    /** Re-diff after state other than the item lists changed (sections, live set, deleting set). */
    private fun refreshRows() {
        val newEntries = buildEntries()
        val old = renderedRows
        entries = newEntries
        val newRows = rows()
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(RowDiff(old, newRows))
        renderedRows = newRows
        diff.dispatchUpdatesTo(this)
    }

    fun update(items: List<ChatSession>, imports: List<OpencodeApi.RemoteSession> = emptyList()) {
        // Diff, don't rebind: update() fires from 40+ sites (search keys,
        // ticker, every settle) — a full rebind of every visible row each
        // time is the list-side jank. Selection paths below still rebind;
        // those are explicit user gestures, not hot paths.
        this.items = items
        this.imports = imports
        refreshRows()
    }

    /** Flat row identity + content signature for the diff. The live
     *  "replying…" state follows the host's confirmed in-flight set, so its
     *  membership joins the signature — otherwise the ticker's updates
     *  would diff clean and a stale typing row would stick. */
    private data class Row(val kind: Int, val id: String, val sig: String)

    /** Schedule facts by thread id (from /api/schedule-rows): a schedule row's third line says when it runs
     *  next and how often. Missing = the line falls back to the file count, or hides. */
    var scheduleFacts: Map<String, ScheduleLine.Facts> = emptyMap()
        set(v) { if (field != v) { field = v; refreshRows() } }

    /** Context fill (percent) per chat, as last measured by the open chat's ring; unknown = absent. */
    var contextPct: Map<String, Int> = emptyMap()
        set(v) { if (field != v) { field = v; refreshRows() } }

    /** The third line: schedule threads say when they run next; chats only say something worth acting on
     *  (waiting sends, a draft, context nearly full, files), else it hides. */
    private fun metaLine(s: ChatSession, files: Int, now: Long): String {
        if (isSystemChat(s.id)) {
            return scheduleFacts[s.id]?.let { ScheduleLine.line(it, now) }.orEmpty()
        }
        val waiting = s.messages.count { it.role == "user" && it.sendFailed && Outbox.isWaiting(it.failError) }
        return chatMetaLine(waiting, s.draft, contextPct[s.id] ?: 0, files)
    }

    /** Chats with a confirmed in-flight turn (host-owned pendingTurns,
     *  reconciled against the server). The "replying…" row follows this
     *  set only — never placeholder freshness, so one turn's activity
     *  can't light up an unrelated stale turn. */
    var liveChats: Set<String> = emptySet()

    /** Chats with a server delete in flight: dimmed rows with a Deleting
     *  state, never silently gone. Mirrors the liveChats pattern. */
    var deletingIds: Set<String> = emptySet()

    /** Refresh the activity set and re-diff visible rows. */
    fun refreshLiveChats(ids: Set<String>) {
        if (ids == liveChats) return
        liveChats = ids
        refreshRows()
    }

    /** Refresh the deleting set and re-diff visible rows. */
    fun refreshDeleting(ids: Set<String>) {
        if (ids == deletingIds) return
        deletingIds = ids
        refreshRows()
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>(entries.size)
        for (e in entries) {
            when (e.kind) {
                0 -> {
                    val s = e.session!!
                    val (images, files) = countAttachments(s)
                    out += Row(0, s.id, sessionRowSig(s, liveChats.contains(s.id), images, files, deletingIds.contains(s.id)) +
                        "|" + metaLine(s, files, System.currentTimeMillis()))
                }
                1 -> out += Row(1, e.imp!!.id, listOf(e.imp.title, e.imp.updatedAt, e.imp.messageCount).joinToString("|"))
                2 -> out += Row(2, "h:" + e.label, e.label)
                4 -> out += Row(4, "banner:" + e.session!!.id, e.label + "|" + e.session.title)
                5 -> out += Row(5, "archived", e.label)
                else -> out += Row(3, "brief:" + e.session!!.id, e.label)
            }
        }
        return out
    }

    private class RowDiff(val old: List<Row>, val cur: List<Row>) :
        androidx.recyclerview.widget.DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = cur.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].kind == cur[n].kind && old[o].id == cur[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o].sig == cur[n].sig
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(p.context)
        return when (vt) {
            2, 5 -> HeaderVH(inf.inflate(R.layout.item_section, p, false))
            3 -> BriefVH(inf.inflate(R.layout.item_brief, p, false))
            4 -> BannerVH(inf.inflate(R.layout.item_banner, p, false))
            else -> VH(inf.inflate(R.layout.item_session, p, false))
        }
    }

    override fun getItemCount() = entries.size

    override fun getItemViewType(pos: Int) = entries[pos].kind

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val e = entries[pos]
        when (e.kind) {
            2 -> {
                (holder as HeaderVH).label.text = e.label
                holder.itemView.setOnClickListener(null)
                holder.itemView.isClickable = false
            }
            5 -> {
                (holder as HeaderVH).label.text = e.label + "  ›"
                holder.itemView.setOnClickListener { onOpenArchived() }
            }
            3 -> {
                val s = e.session!!
                (holder as BriefVH).text.text = e.label
                holder.itemView.setOnClickListener { onOpen(s) }
            }
            4 -> {
                val s = e.session!!
                val (age, why) = e.label.split("\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val b = holder as BannerVH
                b.kicker.text = "Needs you · $age"
                b.title.text = s.title
                b.sub.text = why
                b.sub.visibility = if (why.isBlank()) View.GONE else View.VISIBLE
                b.open.setOnClickListener { onOpen(s) }
                holder.itemView.setOnClickListener { onOpen(s) }
            }
            1 -> bindImport(holder as VH, e.imp!!)
            else -> bindChat(holder as VH, e.session!!)
        }
    }

    private fun bindChat(h: VH, s: ChatSession) {
        val ctx = h.itemView.context
        val warn = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_warn)
        val ink = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_ink)
        val muted = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_muted)
        val waiting = s.waitingOnYou()
        val unread = s.unread > 0
        h.title.setTypeface(null, if (unread || waiting) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        // Recycled import rows run muted — restore full opacity + menu.
        h.itemView.alpha = 1f
        h.menu.visibility = View.VISIBLE
        h.itemView.contentDescription = null
        h.title.text = s.title
        h.pin.visibility = if (s.pinned) View.VISIBLE else View.GONE
        // Delete in flight: dimmed row with a Deleting state, menu hidden
        // so a second tap cannot re-fire. Takes precedence over the live
        // signal; a deleting row is leaving, not replying.
        val deleting = deletingIds.contains(s.id)
        if (deleting) {
            h.itemView.alpha = 0.5f
            h.menu.visibility = View.GONE
            h.preview.text = "Deleting…"
            h.preview.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.t_muted))
            return
        }
        // Live-turn signal (WhatsApp-style "typing…"): the host's confirmed
        // in-flight set says a reply is genuinely running. Placeholder
        // freshness is deliberately NOT consulted: an orphan keeps its
        // placeholder for late-`done` recovery but earns no row. Recycled
        // rows reset both branches.
        val live = liveChats.contains(s.id)
        if (waiting && !live) {
            h.preview.text = cleanPreview(s.needsYou, 90)
            h.preview.setTextColor(warn)
        } else if (live) {
            h.preview.text = "replying…"
            h.preview.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.t_ok))
        } else {
            h.preview.setTextColor(if (unread) ink else muted)
            // System rows (queued notes, retry hints) are status, not
            // conversation: they never drive the preview, and link markup
            // never leaks into it. Event rows (background jobs, compaction notes) are status too.
            val last = s.messages.lastOrNull { it.role != "system" && it.role != "event" && it.role != "tool" }
            h.preview.text = when {
                last == null -> s.messages.lastOrNull()?.text?.take(80)?.lineSequence()?.first()?.ifBlank { null }
                    ?: "no messages yet"
                last.attachment != null && last.attachment.lowercase().endsWith(".m4a") ->
                    "voice note" + if (last.durationSec > 0)
                        " · %d:%02d".format(last.durationSec / 60, last.durationSec % 60) else ""
                last.attachment != null -> "📎 ${last.text.ifBlank { "attachment" }}"
                else -> cleanPreview(last.text).ifBlank { "no messages yet" }
            }
        }
        // Attachment counts across the whole thread (voice notes excluded —
        // they get the waveform icon + preview line instead). Counts only;
        // backend identity lives in the top scope icons, so rows stay clean.
        // Empty metadata hides instead of showing placeholders.
        val (images, files) = countAttachments(s)
        if (waiting) {
            h.sysBadge.visibility = View.VISIBLE
            h.sysBadge.text = "needs you"
            h.sysBadge.setTextColor(warn)
            h.sysBadge.setBackgroundResource(R.drawable.needs_pill)
        } else if (isSystemChat(s.id)) {
            h.sysBadge.visibility = View.VISIBLE
            h.sysBadge.text = if (s.id == "alerts") "alerts" else "schedule"
            h.sysBadge.setTextColor(muted)
            h.sysBadge.setBackgroundResource(R.drawable.sys_pill)
        } else {
            h.sysBadge.visibility = View.GONE
        }
        // Third line: when a schedule runs next, or a chat's file count (images already show as the
        // thumbnail). Nothing to say hides it, so most chat rows stay two lines.
        val meta = metaLine(s, files, System.currentTimeMillis())
        h.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        h.meta.text = meta
        h.time.text = smartDate(s.updatedAt)
        h.time.setTextColor(if (waiting) warn else if (unread) ink else muted)
        if (s.unread > 0) {
            h.badge.visibility = View.VISIBLE
        } else {
            h.badge.visibility = View.GONE
        }
        bindLeadingArt(h, s)
        h.check.visibility = if (selectionMode && selectedIds.contains(s.id)) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener {
            if (selectionMode) toggleSelect(s) else onOpen(s)
        }
        h.itemView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedIds.add(s.id)
                notifyDataSetChanged()
                onSelectionChanged(selectedIds.size)
            } else showMenu(h.menu, s)
            true
        }
        h.menu.setOnClickListener { v -> showMenu(v, s) }
    }

    /** Not-yet-opened server row: title + time, muted, no menu/badge/thumb.
     *  Tap imports (fresh local chat bound to the session, opened empty). */
    private fun bindImport(h: VH, imp: OpencodeApi.RemoteSession) {
        h.itemView.alpha = 0.6f
        h.title.setTypeface(null, android.graphics.Typeface.NORMAL)
        h.title.text = imp.title.ifBlank { "untitled" }
        h.preview.text = if (imp.messageCount > 0)
            "${imp.messageCount} messages · tap to import"
        else "tap to import"
        h.meta.visibility = View.GONE
        h.sysBadge.visibility = View.GONE
        h.time.text = smartDate(imp.updatedAt)
        h.badge.visibility = View.GONE
        h.pin.visibility = View.GONE
        h.check.visibility = View.GONE
        h.icon.visibility = View.VISIBLE
        h.icon.text = "⤓"
        h.thumb.visibility = View.GONE
        h.thumb.setImageDrawable(null)
        h.thumb.setTag(R.id.tagAuthedUrl, null)
        h.menu.visibility = View.VISIBLE
        h.check.visibility = if (selectionMode && selectedImports.contains(imp.id)) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener {
            if (selectionMode) toggleSelectImport(imp) else onImport(imp)
        }
        h.itemView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedImports.add(imp.id)
                notifyDataSetChanged()
                onSelectionChanged(selectionCount())
            } else importMenu(h.menu, imp)
            true
        }
        h.menu.setOnClickListener { v -> importMenu(v, imp) }
        h.itemView.contentDescription = "import ${imp.title.ifBlank { "untitled" }}"
    }

    private fun importMenu(v: View, imp: OpencodeApi.RemoteSession) {
        showActionSheet(v, imp.title.ifBlank { "untitled" }, listOf(
            "Import" to { onImport(imp) },
            "Delete" to { onDeleteImport(imp) },
        ))
    }

    private fun showMenu(v: View, s: ChatSession) {
        showActionSheet(v, s.title.ifBlank { "untitled" }, listOf(
            "Rename" to { onRename(s) },
            (if (s.pinned) "Unpin" else "Pin") to { onPin(s) },
            (if (archivedOf(s)) "Move to Recent" else "Archive") to { onArchive(s) },
            "Fork" to { onFork(s) },
            "Delete" to { onDelete(s) },
        ))
    }

    private fun showActionSheet(anchor: View, title: String, actions: List<Pair<String, () -> Unit>>) {
        val ctx = anchor.context
        val sheet = BottomSheetDialog(ctx, R.style.CreamBottomSheet)
        val view = android.view.LayoutInflater.from(ctx).inflate(R.layout.sheet_simple_menu, null)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.menuTitle).text = title
        val list: android.widget.ListView = view.findViewById(R.id.menuList)
        list.divider = null
        list.adapter = object : android.widget.ArrayAdapter<Pair<String, () -> Unit>>(ctx, R.layout.item_select, actions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: android.view.LayoutInflater.from(ctx).inflate(R.layout.item_select, parent, false)
                val (label, _) = getItem(position) ?: return row
                row.findViewById<TextView>(R.id.selectName).apply { text = label }
                row.findViewById<TextView>(R.id.selectSub).visibility = View.GONE
                row.findViewById<TextView>(R.id.selectCheck).visibility = View.GONE
                return row
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            sheet.dismiss()
            actions[pos].second()
        }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    // ---- Leading art: initial tile first, image thumbnail over it ----

    /** Stable tile tint per chat: muted palette, same chat keeps its
     *  color across binds and themes (light/dark twins in colors.xml). */
    private val TILE_IDS = intArrayOf(
        R.color.tile_0, R.color.tile_1, R.color.tile_2, R.color.tile_3,
        R.color.tile_4, R.color.tile_5, R.color.tile_6, R.color.tile_7
    )

    private fun tileColor(ctx: android.content.Context, id: String): Int {
        val res = TILE_IDS[(id.hashCode() and Int.MAX_VALUE) % TILE_IDS.size]
        return androidx.core.content.ContextCompat.getColor(ctx, res)
    }

    private fun initialOf(title: String): String {
        val c = title.firstOrNull { it.isLetterOrDigit() } ?: return "•"
        return c.uppercase()
    }

    private fun bindLeadingArt(h: VH, s: ChatSession) {
        // Initial tile first: every row carries it, and a failed thumbnail
        // load falls back to it instead of a stale glyph. A resolved image
        // covers it below.
        h.icon.text = initialOf(s.title)
        h.icon.backgroundTintList = android.content.res.ColorStateList.valueOf(
            tileColor(h.itemView.context, s.id))
        h.icon.visibility = View.VISIBLE
        h.thumb.visibility = View.GONE
        h.thumb.setImageDrawable(null)
        val latest = latestImage(s)
        // Clear the tag FIRST so a recycled holder can never show a stale
        // bitmap, then re-tag only when there is something to load.
        h.thumb.setTag(R.id.tagAuthedUrl, null)
        if (latest == null) return
        thumbCache.get(latest)?.let { hit ->
            h.thumb.setTag(R.id.tagAuthedUrl, latest)
            h.thumb.setImageBitmap(hit)
            h.thumb.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
            return
        }
        h.thumb.setTag(R.id.tagAuthedUrl, latest)
        thumbExec.execute {
            try {
                val bmp = loadThumbBitmap(latest)
                if (bmp != null) thumbCache.put(latest, bmp)
                h.thumb.post {
                    if (h.thumb.getTag(R.id.tagAuthedUrl) != latest) return@post
                    if (bmp != null) {
                        h.thumb.setImageBitmap(bmp)
                        h.thumb.visibility = View.VISIBLE
                        h.icon.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** Latest image for this chat: newest image attachment first, then the
     *  newest inline image URL in message text ([image:..] / bare urls). */
    private fun latestImage(s: ChatSession): String? {
        for (m in s.messages.asReversed()) {
            m.allAttachments.lastOrNull { isImagePath(it) }?.let { return it }
        }
        for (m in s.messages.asReversed()) {
            extractImageUrl(m.text)?.let { return it }
        }
        return null
    }

    private fun loadThumbBitmap(ref: String): android.graphics.Bitmap? {
        return try {
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                val b = okhttp3.Request.Builder().url(withToken(ref)).get()
                val tok = tokenProvider()
                if (tok.isNotBlank() && serverLocalPath(ref)) {
                    b.header("Authorization", "Bearer $tok")
                }
                thumbClient.newCall(b.build()).execute().use { res ->
                    if (!res.isSuccessful) return null
                    val bytes = res.body?.bytes() ?: return null
                    if (bytes.isEmpty()) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, 160)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            } else {
                val f = File(ref.removePrefix("file://"))
                if (!f.exists() || f.length() <= 0) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, 160)
                }
                BitmapFactory.decodeFile(f.absolutePath, opts)
            }
        } catch (_: Exception) { null }
    }

    private fun sampleFor(w: Int, h: Int, want: Int): Int {
        var sample = 1
        val longEdge = maxOf(w, h)
        while (longEdge / sample > want * 2 && sample < 16) sample *= 2
        return sample
    }

    /** Identity since cookie auth: the header carries the pass, never
     *  the URL. Kept so the thumbnail loader call site keeps compiling. */
    private fun withToken(url: String): String = url

    private fun serverLocalPath(url: String): Boolean {
        val path = try { java.net.URI(url).path ?: "" } catch (_: Exception) { "" }
        return path.startsWith("/terminus/") || path == "/terminus" ||
            path.startsWith("/ops") || path.startsWith("/notes") || path.startsWith("/echo")
    }

    companion object {
        fun isImagePath(p: String?): Boolean {
            if (p.isNullOrBlank()) return false
            val l = p.lowercase().substringBefore("?").substringBefore("#")
            return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") ||
                l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".avif")
        }

        fun isVoicePath(p: String?): Boolean =
            !p.isNullOrBlank() && p.lowercase().endsWith(".m4a")

        /** Images vs files across a thread (voice notes excluded). */
        fun countAttachments(s: ChatSession): Pair<Int, Int> {
            var images = 0
            var files = 0
            for (m in s.messages) {
                for (a in m.allAttachments) {
                    if (isVoicePath(a)) continue
                    if (isImagePath(a)) images++ else files++
                }
            }
            return images to files
        }

        private val IMG_URL = Regex("""https?://[^\s<>"']+?\.(?:png|jpg|jpeg|gif|webp|avif)(?:\?[^\s<>"']*)?""", RegexOption.IGNORE_CASE)
        private val IMG_TAG = Regex("""\[image:(.+?)]""")

        fun extractImageUrl(text: String): String? {
            if (text.isBlank()) return null
            IMG_TAG.find(text)?.let {
                val p = it.groupValues[1].trim()
                if (p.isNotBlank()) return p
            }
            return IMG_URL.find(text)?.value
        }

        /** Smart date: today → HH:mm, yesterday → Yesterday,
         *  <7d → day name, same year → d MMM, else → d MMM yyyy. */
        fun smartDate(ts: Long, now: Long = System.currentTimeMillis()): String {
            if (ts <= 0) return ""
            val loc = Locale.getDefault()
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val startOfToday = cal.clone() as Calendar
            startOfToday.set(Calendar.HOUR_OF_DAY, 0)
            startOfToday.set(Calendar.MINUTE, 0)
            startOfToday.set(Calendar.SECOND, 0)
            startOfToday.set(Calendar.MILLISECOND, 0)
            val dayMs = 24 * 60 * 60 * 1000L
            return when {
                ts >= startOfToday.timeInMillis ->
                    SimpleDateFormat("HH:mm", loc).format(Date(ts))
                ts >= startOfToday.timeInMillis - dayMs -> "Yesterday"
                ts >= startOfToday.timeInMillis - 6 * dayMs ->
                    SimpleDateFormat("EEEE", loc).format(Date(ts))
                else -> {
                    val then = Calendar.getInstance().apply { timeInMillis = ts }
                    if (then.get(Calendar.YEAR) == cal.get(Calendar.YEAR))
                        SimpleDateFormat("d MMM", loc).format(Date(ts))
                    else
                        SimpleDateFormat("d MMM yyyy", loc).format(Date(ts))
                }
            }
        }

        const val AUTO_ARCHIVE_MS = 30L * 24 * 60 * 60 * 1000

        /** Pure (unit-tested): is this chat archived? Pinned, unread and needs-you never are; [isSchedule]
         *  exempts a thread when the caller wants that (the app applies the rule to job threads too, so retired
         *  jobs fold away). A hand archive holds until new activity; otherwise 30 quiet days archive it, counted
         *  from the later of its last activity and a hand "Move to Recent". */
        fun isArchived(s: ChatSession, now: Long, isSchedule: Boolean): Boolean {
            if (s.pinned || s.unread > 0 || isSchedule || s.waitingOnYou(now)) return false
            if (s.archivedAt > 0) return s.updatedAt <= s.archivedAt
            val since = maxOf(s.updatedAt, -s.archivedAt)
            return now - since > AUTO_ARCHIVE_MS
        }

        /** Sort helpers shared with the activity wiring. */
        fun sortSessions(
            all: List<ChatSession>,
            sort: String
        ): List<ChatSession> = when (sort) {
            "name" -> all.sortedWith(compareByDescending<ChatSession> { it.waitingOnYou() }
                .thenByDescending { it.pinned }
                .thenBy { it.title.lowercase() })
            "activity" -> all.sortedWith(compareByDescending<ChatSession> { it.waitingOnYou() }
                .thenByDescending { it.pinned }
                .thenByDescending { it.unread }
                .thenByDescending { it.updatedAt })
            else -> all.sortedWith(compareByDescending<ChatSession> { it.waitingOnYou() }
                .thenByDescending { it.pinned }
                .thenByDescending { it.updatedAt })
        }

        /** Search ranking (pure, unit-tested): title matches outrank
         *  message-body matches; each band keeps the current list sort.
         *  Blank query = plain sort. */
        fun rankSearch(
            all: List<ChatSession>,
            query: String,
            sort: String
        ): List<ChatSession> {
            if (query.isBlank()) return sortSessions(all, sort)
            val q = query.trim()
            // Title prefix first (surfaces before the word completes), then
            // title substring, then message hits. No stalling: the caller
            // caps the body scan, so each keystroke stays instant.
            val (prefix, rest1) = all.partition { it.title.startsWith(q, true) }
            val (titled, rest) = rest1.partition { it.title.contains(q, true) }
            return sortSessions(prefix, sort) + sortSessions(titled, sort) + sortSessions(rest, sort)
        }
    }
}
