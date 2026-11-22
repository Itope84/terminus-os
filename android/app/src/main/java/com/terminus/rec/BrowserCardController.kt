package com.terminus.rec

/** Lifecycle of the chat's browser card, extracted from MainActivity (behaviour unchanged).
 *
 *  - The session belongs to ONE chat, fixed when it goes live: the chat whose agent turn is running,
 *    else the open chat. The card is posted there and live updates only reach that chat's page.
 *  - When the session ends, the live card is replaced in that chat by a finished card carrying the
 *    step list (text only), titled after the sites visited.
 *  - Everything it needs from the app comes in as lambdas, so it holds no Activity.
 *
 *  Feed it every state snapshot from PhoneBrowser through [onState]. */
class BrowserCardController(
    /** The chat currently open (null when none). */
    private val activeChat: () -> String?,
    /** Chats with an agent turn in flight. */
    private val runningTurnChats: () -> Set<String>,
    /** When each chat last sent a turn (ms). */
    private val askedAt: () -> Map<String, Long>,
    private val store: () -> ChatStore,
    /** Add [card] to [chat]'s history and show it if that chat is open. */
    private val postRow: (chat: String, card: ChatMessage) -> Unit,
    /** Redraw [chat] if it is open. */
    private val rerender: (chat: String) -> Unit,
    /** Evaluate a script in the open chat page; a no-op while the page is not ready. */
    private val runJs: (String) -> Unit,
) {
    private var ownerChat: String? = null
    /** Timestamp of the live card row in the owner chat (0 = not posted yet). */
    private var cardTs = 0L
    /** Sites the session showed, in order; names the finished card. */
    private val hosts = LinkedHashSet<String>()

    /** True while [chat] owns the live card (a live session is bound to it). */
    fun owns(chat: String): Boolean = ownerChat == chat

    /** The chat that asked for the browser. The open chat is only the last resort: the person usually
     *  navigates away right after asking. */
    private fun pickOwner(): String? = chooseOwner(activeChat(), runningTurnChats(), askedAt(), System.currentTimeMillis())

    companion object {
        /** A send this recent still counts as "asked" when turn tracking has not caught up. */
        const val RECENT_MS = 2 * 60_000L

        /** One running chat owns it; among several, the most recently asked; none tracked, the chat asked
         *  within [RECENT_MS]; else the open chat. */
        fun chooseOwner(open: String?, running: Set<String>, asked: Map<String, Long>, now: Long): String? {
            if (running.size == 1) return running.first()
            if (running.size > 1) return running.maxByOrNull { asked[it] ?: 0L }
            return asked.entries.filter { now - it.value in 0..RECENT_MS }.maxByOrNull { it.value }?.key ?: open
        }
    }

    /** One state snapshot (JSON from PhoneBrowser.stateJson, or "null"). */
    fun onState(json: String) {
        try {
            val o = if (json == "null") null else org.json.JSONObject(json)
            val st = o?.optString("status") ?: ""
            if (o == null || st.isBlank() || st == "Done" || st == "Idle") {
                if (o != null && st == "Done") freeze(o)
                ownerChat = null
                cardTs = 0L
                hosts.clear()
                return
            }
            if (ownerChat == null) ownerChat = pickOwner()
            val owner = ownerChat ?: return
            o.optString("url").takeIf { it.isNotBlank() }?.let { u ->
                try { android.net.Uri.parse(u).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                    ?.let { if (hosts.size < 12) hosts.add(it) }
            }
            // A session with only the "Browser ready" seed step has done nothing: no card yet.
            // "Offline" is only the app pausing: the session continues, so no second card.
            if (st != "Offline" && o.optInt("n") > 1 && cardTs == 0L) {
                val card = ChatMessage("assistant", "[Browser](terminus://browser)")
                cardTs = card.ts
                postRow(owner, card)
            }
            if (activeChat() == owner) runJs("window.__pb&&window.__pb($json)")
        } catch (_: Exception) { /* card is decoration; steps still flow */ }
    }

    /** Session ended: replace the live card with a static one carrying the step list (no images). */
    private fun freeze(o: org.json.JSONObject) {
        val owner = ownerChat ?: return
        val ts = cardTs
        if (ts == 0L) return
        val src = o.optJSONArray("steps") ?: return
        val steps = org.json.JSONArray()
        for (i in 0 until src.length()) {
            val x = src.getJSONObject(i)
            val s = x.optString("s").let { if (it == "r" || it == "h") "x" else it }
            steps.put(org.json.JSONObject().put("id", x.optLong("id")).put("s", s)
                .put("t", x.optString("t")).put("ms", x.optInt("ms")).put("d", x.optString("d")))
        }
        val payload = org.json.JSONObject().put("n", o.optInt("n")).put("steps", steps).toString()
        // Hex keeps the link free of characters markdown or URLs could mangle.
        val hex = payload.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        // Name from what was done: the sites visited, else the first real step.
        val hostList = hosts.toList()
        val host = if (hostList.isEmpty()) null
            else hostList.take(2).joinToString(", ") + if (hostList.size > 2) " +${hostList.size - 2}" else ""
        val firstStep = (0 until src.length()).map { src.getJSONObject(it).optString("t") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Browser ready") }
        val name = (if (!host.isNullOrBlank()) "Browsed $host" else firstStep ?: "Browser session")
            .replace(Regex("[\\[\\]()<>&\"'`]"), "").trim().take(48).ifBlank { "Browser session" }
        val changed = store().replaceTextByTs(owner, ts, "[Browser](terminus://browser", "[$name](terminus://browser#f=$hex)")
        if (changed && activeChat() == owner) rerender(owner)
    }
}
