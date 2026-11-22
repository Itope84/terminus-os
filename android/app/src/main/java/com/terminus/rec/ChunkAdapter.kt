package com.terminus.rec

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed interface QueueRow {
    data class Day(val key: String, val label: String, val count: Int) : QueueRow
    data class Item(val chunk: ChunkItem, val range: String, val length: String) : QueueRow
}

class ChunkAdapter(
    private var rows: List<QueueRow>,
    private val onRetry: () -> Unit,
    private val onPlay: (ChunkItem) -> Unit,
    private val onDelete: (ChunkItem) -> Unit,
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var lastItems: List<ChunkItem> = emptyList()
    private val collapsed = mutableSetOf<String>()
    /** Days the user explicitly opened: exempt from auto-collapse. */
    private val userOpen = mutableSetOf<String>()
    /** Real clip lengths by file name (ms); missing = assume a full slot. */
    private var durations: Map<String, Long> = emptyMap()

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.groupLabel)
        val count: TextView = v.findViewById(R.id.groupCount)
        val chev: TextView = v.findViewById(R.id.groupChev)
    }
    class I(v: View) : RecyclerView.ViewHolder(v) {
        val play: View = v.findViewById(R.id.chunkPlay)
        val name: TextView = v.findViewById(R.id.chunkName)
        val state: TextView = v.findViewById(R.id.chunkState)
        val more: TextView = v.findViewById(R.id.chunkMore)
    }

    fun update(next: List<ChunkItem>, durations: Map<String, Long> = emptyMap()) {
        lastItems = next
        if (durations.isNotEmpty()) this.durations = durations
        // Every refresh: all but the newest day collapse, except days the
        // user explicitly opened. First load behaves the same as later ones.
        val days = next.map { dayKey(it.name) }.distinct()
        val newest = days.firstOrNull()
        for (d in days) {
            if (d != newest && d !in userOpen) collapsed.add(d)
        }
        rebuild()
    }

    fun toggle(key: String) {
        if (collapsed.remove(key)) userOpen.add(key)
        else {
            collapsed.add(key)
            userOpen.remove(key)
        }
        rebuild()
    }

    fun expandAll() {
        collapsed.clear()
        userOpen.addAll(lastItems.map { dayKey(it.name) }.distinct())
        rebuild()
    }

    private fun rebuild() {
        rows = grouped(lastItems, collapsed, durations)
        notifyDataSetChanged()
    }

    override fun getItemViewType(pos: Int): Int = if (rows[pos] is QueueRow.Day) 0 else 1

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder =
        if (vt == 0) H(LayoutInflater.from(p.context).inflate(R.layout.item_group, p, false))
        else I(LayoutInflater.from(p.context).inflate(R.layout.item_chunk, p, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val r = rows[pos]) {
            is QueueRow.Day -> {
                val hh = h as H
                val shut = collapsed.contains(r.key)
                hh.label.text = r.label
                hh.count.text = "${r.count} chunk" + if (r.count == 1) "" else "s"
                hh.chev.text = if (shut) "﹀" else "︿"
                hh.itemView.setOnClickListener { onToggle(r.key) }
            }
            is QueueRow.Item -> {
                val ih = h as I
                val item = r.chunk
                ih.name.text = r.range
                val mark = chunkLabel(item.name)
                ih.state.text = r.length + " · " +
                    (when (item.state) {
                        ChunkStore.STATE_UPLOADED -> "synced"
                        ChunkStore.STATE_REJECTED -> "failed"
                        else -> "queued"
                    }) +
                    (if (mark.isNotBlank()) " · $mark" else "")
                ih.state.setTextColor(androidx.core.content.ContextCompat.getColor(ih.itemView.context, when (item.state) {
                    ChunkStore.STATE_UPLOADED -> R.color.t_muted
                    ChunkStore.STATE_REJECTED -> R.color.t_err
                    else -> R.color.t_warn
                }))
                ih.play.setOnClickListener { onPlay(item) }
                ih.itemView.setOnClickListener { onPlay(item) }
                ih.more.setOnClickListener { v ->
                    showChunkSheet(v, item)
                }
            }
        }
    }

    /** Themed bottom sheet replacing the dark PopupMenu: Play, Retry upload
     *  (unsynced only), Delete (surface bg, ink text, line dividers). */
    private fun showChunkSheet(anchor: View, item: ChunkItem) {
        val ctx = anchor.context
        val sheet = BottomSheetDialog(ctx, R.style.CreamBottomSheet)
        val view = LayoutInflater.from(ctx).inflate(R.layout.sheet_simple_menu, null)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.menuTitle).text = item.name
        val list = view.findViewById<android.widget.ListView>(R.id.menuList)
        // Retry only where there is something to retry: a synced recording has nothing left to send.
        val actions = listOfNotNull(
            "Play" to { onPlay(item) },
            ("Retry upload" to { onRetry() }).takeIf { item.state != ChunkStore.STATE_UPLOADED },
            "Delete" to { onDelete(item) },
        )
        list.divider = null
        list.adapter = object : android.widget.ArrayAdapter<Pair<String, () -> Unit>>(ctx, R.layout.item_select, actions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_select, parent, false)
                val (label, _) = getItem(position) ?: return row
                row.findViewById<TextView>(R.id.selectName).text = label
                row.findViewById<TextView>(R.id.selectSub).visibility = View.GONE
                row.findViewById<TextView>(R.id.selectCheck).visibility = View.GONE
                return row
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            sheet.dismiss()
            actions[position].second()
        }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    companion object {
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

        /** rec-2026-09-23-15-04-01.m4a → 2026-09-23. A trailing label
         *  (rec-…-01-walk.m4a) never moves indices 1-3. */
        fun dayKey(name: String): String {
            val parts = name.removeSuffix(".m4a").split("-")
            return if (parts.size >= 4) parts.subList(1, 4).joinToString("-") else name
        }

        /** rec-…-01-walk.m4a → walk; unlabeled → "". Labels may hold
         *  dashes, so everything past the chunk index (parts[6]) counts. */
        fun chunkLabel(name: String): String {
            val parts = name.removeSuffix(".m4a").split("-")
            return if (parts.size > 7) parts.subList(7, parts.size).joinToString("-") else ""
        }

        fun dayLabel(key: String): String {
            return try {
                val d = dayFmt.parse(key) ?: return key
                val cal = Calendar.getInstance()
                val today = dayFmt.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val yesterday = dayFmt.format(cal.time)
                val prefix = when (key) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> SimpleDateFormat("EEE", Locale.UK).format(d)
                }
                "$prefix · $key"
            } catch (_: Exception) { key }
        }

        /** rec-2026-09-23-15-04-01.m4a → slot start in minutes; end comes
         *  from the real clip length (short clips must not wear full slots). */
        fun slotStartMin(name: String): Int {
            val parts = name.removeSuffix(".m4a").split("-")
            if (parts.size < 7) return -1
            val hh = parts[4].toIntOrNull() ?: return -1
            val mm = parts[5].toIntOrNull() ?: return -1
            val idx = (parts[6].toIntOrNull() ?: 1) - 1
            return hh * 60 + mm + idx * 5
        }

        fun fmtDur(ms: Long): String {
            val s = (ms / 1000).toInt().coerceAtLeast(0)
            return "${s / 60}:${String.format("%02d", s % 60)}"
        }

        fun fmtClock(totalMin: Int): String =
            String.format("%02d:%02d", (totalMin / 60) % 24, totalMin % 60)

        fun grouped(items: List<ChunkItem>, collapsed: Set<String> = emptySet(), durations: Map<String, Long> = emptyMap()): List<QueueRow> {
            val rows = mutableListOf<QueueRow>()
            items.sortedByDescending { it.name }
                .groupBy { dayKey(it.name) }
                .forEach { (key, group) ->
                    rows += QueueRow.Day(key, dayLabel(key), group.size)
                    if (key !in collapsed) group.sortedByDescending { it.name }
                        .forEach {
                            val start = slotStartMin(it.name)
                            val dur = durations[it.name] ?: (5 * 60 * 1000L)
                            // Short clips wear start + length ("11:43 · 0:03"),
                            // full slots keep ranges ("15:04 – 15:09").
                            val range = if (start < 0) it.name.substringAfterLast("-")
                                else if (dur < 60_000) "${fmtClock(start)} · ${fmtDur(dur)}"
                                else "${fmtClock(start)} – ${fmtClock(start + (dur / 60000).toInt())}"
                            rows += QueueRow.Item(it, range, fmtDur(dur))
                        }
                }
            return rows
        }
    }
}
