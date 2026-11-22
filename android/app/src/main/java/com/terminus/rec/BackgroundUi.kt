package com.terminus.rec

/** Background-work timeline rows. Pure, unit-tested. A background job runs
 *  detached from any turn, so its start and landing are durable `event` rows
 *  (never live-box steps, which need a running turn). Short labels, no
 *  em-dashes. Label is the job's command, already capped upstream. */
object BackgroundUi {
    /** Row text for one background lifecycle event. Blank label falls back to
     *  a generic phrase so a row never renders empty. Elapsed (e.g. "2m 14s")
     *  rides in the same parens as the output excerpt; the excerpt drops
     *  first when the row would run long. */
    fun rowText(status: String, label: String, detail: String, elapsed: String = "", full: String = ""): String {
        val cmd = label.trim().take(160).ifBlank { "background job" }
        val d = detail.trim().take(160)
        val e = elapsed.trim()
        fun tailed(base: String): String {
            val tail = listOf(d, e).filter { it.isNotBlank() }.joinToString(", ")
            if (tail.isBlank()) return base
            if (base.length + tail.length + 3 > 240 && d.isNotBlank() && e.isNotBlank()) return "$base ($e)"
            return "$base ($tail)"
        }
        val head = when (status) {
            "started" -> "backgrounded: $cmd"
            "done" -> tailed("background done: $cmd")
            "failed" -> tailed("background failed: $cmd")
            else -> "background: $cmd"
        }
        // Full output rides fenced at the end for the card expander (server
        // caps it at 4000 chars, newlines kept). Excerpt-only rows have none.
        val f = full.trim()
        return if (f.isBlank()) head else "$head\n```\n$f"
    }

    /** Short elapsed text for a finished job: 45s, 2m 14s, 1h 2m. Pure, unit-tested. */
    fun elapsedText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        if (s < 60) return "${s}s"
        val m = s / 60
        if (m < 60) return "${m}m ${s % 60}s"
        return "${m / 60}h ${m % 60}m"
    }

    /** Split a lifecycle row back into pill label + body + full output for
     *  card rendering (pure, unit-tested). The full output rides fenced at
     *  the end (server-capped, newlines kept); absent for excerpt-only rows.
     *  Null when the text is not a background lifecycle row. */
    fun cardParts(text: String): Triple<String, String, String>? {
        val t = text.trim()
        val p = listOf("backgrounded: ", "background done: ", "background failed: ")
            .firstOrNull { t.startsWith(it) } ?: return null
        val label = if (p == "backgrounded: ") "background" else p.dropLast(2)
        var rest = t.substring(p.length)
        var full = ""
        // Body is single-line (server flattens the excerpt), so the last
        // fence always opens our full-output trailer.
        val fi = rest.lastIndexOf("\n```\n")
        if (fi >= 0) {
            full = rest.substring(fi + 5).trim()
            rest = rest.substring(0, fi)
        }
        return Triple(label, rest.ifBlank { "background job" }, full)
    }

    /** One background job as a quiet line: what it was, how it went, how long it took. Pure, unit-tested.
     *  state: run | done | failed. The finish row's "(excerpt, 1m 24s)" tail splits into [elapsed] and the
     *  excerpt, which only shows behind "output" (with the full output when there is one). */
    /** How long a job line (and the chip) says "running" without a finish: 10 minutes. */
    const val SHOWN_RUNNING_MS = 10 * 60_000L

    data class Line(val state: String, val cmd: String, val elapsed: String, val output: String)

    private val CLAUDE_SUMMARY = Regex("^Background command \"(.+)\" (completed|failed|was stopped|killed)")
    private val ELAPSED = Regex("^\\d+(h|m|s)( \\d+(m|s))?$")

    fun line(text: String): Line? {
        val (label, body, full) = cardParts(text) ?: return null
        val state = when (label) { "background done" -> "done"; "background failed" -> "failed"; else -> "run" }
        var cmd = body
        var elapsed = ""
        var detail = ""
        if (state != "run" && body.endsWith(")")) {
            val open = body.lastIndexOf(" (")
            if (open > 0) {
                val parts = body.substring(open + 2, body.length - 1).split(", ")
                if (ELAPSED.matches(parts.last().trim())) {
                    elapsed = parts.last().trim()
                    detail = parts.dropLast(1).joinToString(", ")
                } else detail = parts.joinToString(", ")
                cmd = body.substring(0, open)
            }
        }
        // Older finishes carry Claude's closing summary as their name: Background command "X" completed (…).
        CLAUDE_SUMMARY.find(cmd.trim())?.let { cmd = it.groupValues[1] }
        return Line(state, cmd.trim().ifBlank { "background job" }, elapsed, full.ifBlank { detail }.trim())
    }

    /** Indexes of start rows whose job has since finished (a later finish row for the same command): the
     *  finish line stands for the whole job, so the start isn't shown. Pure, unit-tested. */
    fun finishedStarts(messages: List<ChatMessage>): Set<Int> = finishPairs(messages).keys

    /** Start row index → its finish row index. The finish is drawn where its start was, so a job stays with
     *  the reply that started it however late it ends. Pure, unit-tested. */
    fun finishPairs(messages: List<ChatMessage>): Map<Int, Int> {
        val open = HashMap<String, ArrayDeque<Int>>()
        val out = LinkedHashMap<Int, Int>()
        messages.forEachIndexed { i, m ->
            if (m.role != "event") return@forEachIndexed
            val l = line(m.text) ?: return@forEachIndexed
            if (l.state == "run") open.getOrPut(l.cmd) { ArrayDeque() }.addLast(i)
            else open[l.cmd]?.removeFirstOrNull()?.let { out[it] = i }
        }
        return out
    }
}

/** Which background jobs are still running, per chat. The chat's SSE stream
 *  is held open while any is live so the landing row is received after the
 *  turn settled. Start timestamps, not a bare count: entries older than
 *  staleAfterMs are treated as stale on every read, so a start whose finish
 *  was never seen cannot hold the stream forever (previously until restart).
 *  In-memory only: a restart loses the hint. */
class BackgroundJobs {
    /** Stale threshold. Long but bounded: real jobs take hours, so a missed
     *  landing must outlast any legitimate run before it stops holding. */
    internal var staleAfterMs = 4 * 60 * 60 * 1000L

    private val starts = HashMap<String, ArrayDeque<Long>>()

    /** Record a lifecycle event. `started` opens; any terminal status closes
     *  the oldest start (first-in-first-out) and returns its elapsed time
     *  in ms. Starts return -1, as does a terminal with no open start
     *  (never negative, never a phantom close). */
    @Synchronized
    fun note(chatId: String, status: String): Long {
        prune(chatId)
        if (status == "started") {
            starts.getOrPut(chatId) { ArrayDeque() }.addLast(System.currentTimeMillis())
            return -1
        }
        val q = starts[chatId] ?: return -1
        if (q.isEmpty()) {
            starts.remove(chatId)
            return -1
        }
        val startedAt = q.removeFirst()
        if (q.isEmpty()) starts.remove(chatId)
        return System.currentTimeMillis() - startedAt
    }

    @Synchronized
    fun any(chatId: String): Boolean {
        prune(chatId)
        return !starts[chatId].isNullOrEmpty()
    }

    private fun prune(chatId: String) {
        val q = starts[chatId] ?: return
        val cutoff = System.currentTimeMillis() - staleAfterMs
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
        if (q.isEmpty()) starts.remove(chatId)
    }
}
