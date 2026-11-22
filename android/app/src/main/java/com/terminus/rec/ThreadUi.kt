package com.terminus.rec

import java.util.Calendar

/** Pure helpers for the chat list, needs-you events and the morning-brief card.
 *  Plain Kotlin only (no Android types) so every rule here is unit-tested. */

// ---- Preview line ----

private val FENCE = Regex("(?s)```.*?```")
private val IMG_MD = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val LINK_MD = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val IMG_TAG = Regex("\\[image:[^\\]]*]")
private val BULLET = Regex("^(?:[-*+•]\\s+|\\d{1,2}[.)]\\s+|>+\\s*)+")
private val HEADING = Regex("^#{1,6}\\s+")

/** One clean line for a list row: markdown stripped, no heading hashes, no link targets, no
 *  asterisks. A body that opens with a heading ("# Thu 29 Oct" in a brief) previews the first real
 *  sentence after it; a heading-only body previews the heading. Cut at a word with an ellipsis. */
internal fun cleanPreview(raw: String, max: Int = 90): String {
    var t = raw.replace("\r", "")
    t = FENCE.replace(t, "code")
    t = IMG_MD.replace(t, "image")
    t = IMG_TAG.replace(t, "image")
    t = LINK_MD.replace(t) { it.groupValues[1].ifBlank { "link" } }
    val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
    var firstHeading: String? = null
    var picked: String? = null
    for (l in lines) {
        val isHeading = HEADING.containsMatchIn(l)
        val body = inline(BULLET.replace(HEADING.replace(l, ""), ""))
        if (body.isBlank()) continue
        if (isHeading) { if (firstHeading == null) firstHeading = body; continue }
        picked = body; break
    }
    val out = (picked ?: firstHeading ?: "").replace(Regex("\\s+"), " ").trim()
    if (out.length <= max) return out
    val cut = out.lastIndexOf(' ', max - 1)
    val at = if (cut >= max * 6 / 10) cut else max - 1
    return out.substring(0, at).trimEnd(',', ';', ':', '-', ' ') + "…"
}

private fun inline(s: String): String =
    s.replace("**", "").replace("__", "").replace("`", "").replace(Regex("(?<![\\w])[*_](?=\\S)|(?<=\\S)[*_](?![\\w])"), "")

// ---- Sections ----

internal enum class ListBucket(val label: String) {
    NEEDS_YOU("Needs you"), PINNED("Pinned"), TODAY("Today"), YESTERDAY("Yesterday"),
    THIS_WEEK("This week"), EARLIER("Earlier"),
}

/** Which section a chat row lives in. Needs-you outranks pinned outranks the date buckets. */
internal fun listBucket(needsYou: Boolean, pinned: Boolean, updatedAt: Long, now: Long): ListBucket {
    if (needsYou) return ListBucket.NEEDS_YOU
    if (pinned) return ListBucket.PINNED
    val start = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val day = 24L * 60 * 60 * 1000
    val s = start.timeInMillis
    return when {
        updatedAt >= s -> ListBucket.TODAY
        updatedAt >= s - day -> ListBucket.YESTERDAY
        updatedAt >= s - 6 * day -> ListBucket.THIS_WEEK
        else -> ListBucket.EARLIER
    }
}

/** Section headers only earn their space once there is a list to scan. */
internal const val MIN_ROWS_FOR_SECTIONS = 5

/** Ordered rows with their bucket; a header precedes the first row of each bucket. Input order is kept
 *  inside a bucket, so the caller's sort still decides row order. Returns (bucket or null for a row, index). */
internal fun sectionPlan(buckets: List<ListBucket>): List<Pair<ListBucket?, Int>> {
    if (buckets.size < MIN_ROWS_FOR_SECTIONS) return buckets.indices.map { null to it }
    val out = ArrayList<Pair<ListBucket?, Int>>()
    var last: ListBucket? = null
    buckets.forEachIndexed { i, b ->
        if (b != last) { out += b to -1; last = b }
        out += null to i
    }
    return out
}

// ---- Time clusters (chat view) ----

/** A quiet gap this long starts a new time cluster ("Today · 17:55"). */
internal const val CLUSTER_GAP_MS = 30L * 60 * 1000

private fun dayStamp(ts: Long): String =
    java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(ts))

/** True when ts opens a new cluster: the first row, a new day, or a pause of CLUSTER_GAP_MS. One centred
 *  time label per burst replaces a timestamp on every message. */
internal fun startsCluster(prevTs: Long, ts: Long): Boolean =
    prevTs <= 0 || dayStamp(prevTs) != dayStamp(ts) || ts - prevTs >= CLUSTER_GAP_MS

// ---- Needs-you events ----

/** How long an event keeps a thread flagged. A browser hand-off or approval is dead in minutes, so a
 *  flag outliving it would lie; a failed scheduled run can wait for the morning. */
internal fun needsYouTtlMs(job: String): Long = when (job) {
    "phone-browser", "approvals" -> 10L * 60 * 1000
    else -> 24L * 60 * 60 * 1000
}

internal fun needsYouActive(reason: String, until: Long, now: Long): Boolean =
    reason.isNotBlank() && until > now

/** Where an inbox entry lands. An event goes to the chat it names when that chat still exists; when the chat was
 *  deleted it goes to Alerts, so it never resurrects an empty shell. Everything else keeps the job routing. */
internal fun inboxTarget(job: String, chat: String, kind: String, exists: (String) -> Boolean): String {
    if (kind == "event") {
        val ok = chat.matches(Regex("[A-Za-z0-9_:-]{1,80}")) && exists(chat)
        return if (ok) chat else "alerts"
    }
    return inboxThreadFor(job, chat)
}

/** The short reason shown on the row and in the notification: the entry's title, else its first body line. */
internal fun needsYouReason(title: String, body: String): String {
    val t = title.trim().ifBlank { body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "" }
    return cleanPreview(t, 80)
}

/** "now", "5m ago", "2h ago", "3d ago" for the needs-you banner. */
internal fun needsYouAge(at: Long, now: Long): String {
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        else -> "${m / (60 * 24)}d ago"
    }
}

/** Message text stored for an event entry; rendered as a card by needsYouCardHtml. */
internal fun needsYouText(title: String, body: String): String =
    "needs you\n" + title.trim() + (if (body.isNotBlank()) "\n" + body.trim() else "")

/** True for event rows that should render as a needs-you card. */
internal fun isNeedsYouText(text: String): Boolean = text.startsWith("needs you\n")

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Card html for a needs-you event row (inline in the thread it belongs to). */
internal fun needsYouCardHtml(text: String, idx: Int): String {
    val parts = text.removePrefix("needs you\n").split("\n", limit = 2)
    val title = esc(parts.getOrElse(0) { "" })
    val body = esc(parts.getOrElse(1) { "" }).replace("\n", "<br>")
    return "<div class='row needcard' data-idx=\"$idx\" data-role=\"event\">" +
        "<span class='needpill'>Needs you</span><div class='needtitle'>$title</div>" +
        (if (body.isNotBlank()) "<div class='needbody'>$body</div>" else "") + "</div>"
}

// ---- Morning-brief card ----

private const val BRIEF_CHAT = "schedule:morning-brief"
private const val BRIEF_FRESH_MS = 24L * 60 * 60 * 1000

/** Pure: what a render asks of the scroll position. A render that lands while the previous
 *  reload is still in flight (`reloading`) must NOT re-read the live position: the reload reset the
 *  WebView to the top, so the live Y reads 0 and the "restore" threw the person to the top (open a
 *  chat, a stream/title/step render arrives before the page commits). It repeats what the in-flight
 *  render already asked for. Returns (scrollToBottomOrAnchor, keepCssY). Unit-tested. */
internal fun scrollPlan(scroll: Boolean, reloading: Boolean, lastScroll: Boolean, lastKeepCssY: Int, liveCssY: Int): Pair<Boolean, Int> =
    when {
        scroll -> true to liveCssY
        reloading -> lastScroll to lastKeepCssY
        else -> false to liveCssY
    }

/** Shown at the top of the list while the latest brief is unread and under a day old. Pure: the caller
 *  passes the thread's id, unread count, last assistant text and its timestamp. */
internal fun briefCardText(chatId: String, unread: Int, lastText: String, lastAt: Long, now: Long): String? {
    if (chatId != BRIEF_CHAT || unread <= 0 || lastText.isBlank()) return null
    if (now - lastAt > BRIEF_FRESH_MS || lastAt > now + 60_000) return null
    return inboxPreview(lastText).let { cleanPreview(it, 140) }.ifBlank { null }
}

// ---- Moved here (pure, shared): inbox routing and push preview ----

/** Job → thread, shared by the app and the background worker. A valid
 *  `chat` override wins: targeted delivery lands in that exact thread. */
internal fun inboxThreadFor(job: String, chat: String = ""): String {
    if (chat.matches(Regex("[A-Za-z0-9_:-]{1,80}"))) return chat
    return if (job == "alerts" || job == "system") "alerts" else "schedule:$job"
}

/** Push preview for inbox deliveries. A morning brief opens `# title`, then ONE line about the day, and
 *  (only when there is something to act on) a `**Money:**` line. The preview is the day line, plus the money
 *  line when present, so the notification reads like a human, not a trade ticket. Anything else passes through. */
internal fun inboxPreview(body: String): String {
    val lines = body.lines().map { it.replace("*", "").trim().trim('`') }
    if (lines.firstOrNull()?.startsWith("# ") != true) return body
    val day = lines.drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("Money:") && !it.startsWith("#") } ?: return body
    val money = lines.firstOrNull { it.startsWith("Money:") }
    return if (money == null) day else "$day · 💷 ${money.removePrefix("Money:").trim()}"
}

/** Pure: a finger drag UP that started at the bottom of the thread and travelled `dragPx` arms a
 *  manual sync on release. The page cannot scroll further there, so nothing moves under the finger;
 *  the drag has to be deliberate (past `thresholdPx`) and mostly vertical so ordinary reading never
 *  trips it. Unit-tested. */
internal fun pullUpArmed(startedAtBottom: Boolean, stillAtBottom: Boolean, dragPx: Float, driftPx: Float, thresholdPx: Float, heldMs: Long): Boolean =
    startedAtBottom && stillAtBottom && dragPx >= thresholdPx && kotlin.math.abs(driftPx) <= dragPx && heldMs >= PULL_MIN_HOLD_MS

internal const val PULL_DEADZONE_DP = 72f
internal const val PULL_THRESHOLD_DP = 220f
internal const val PULL_MIN_HOLD_MS = 350L
internal const val PULL_SHOW_AFTER_MS = 150L

/** Pure: how far the pull-up hint has emerged, 0..1. */
internal fun pullProgress(dragPx: Float, deadzonePx: Float, thresholdPx: Float): Float =
    if (thresholdPx <= deadzonePx) 0f else ((dragPx - deadzonePx) / (thresholdPx - deadzonePx)).coerceIn(0f, 1f)

/** Pure: the footer line after a manual sync. */
internal fun pullSyncMessage(updated: Int, stillRunning: Boolean): String = when {
    updated > 0 -> "Chat updated"
    stillRunning -> "A reply is still being written"
    else -> "You're up to date"
}
