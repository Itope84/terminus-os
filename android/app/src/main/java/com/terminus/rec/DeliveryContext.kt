package com.terminus.rec

/** Scheduled jobs and alerts deliver into a thread from a throwaway agent session, so the chat's own agent
 *  never saw them: replying "do that" to a brief reached an agent with no idea what "that" was. This
 *  carries the deliveries since your previous message along with the next one you send (wire text only,
 *  the bubble shows what you typed). Pure, unit-tested. */
object DeliveryContext {
    const val MAX_ITEMS = 3
    const val MAX_CHARS = 3000

    /** A delivery: an inbox entry or a needs-you event, never one of the agent's own replies (those carry a
     *  turn id or an opencode message id). */
    private fun isDelivery(m: ChatMessage): Boolean =
        m.text.isNotBlank() && m.text != "…" &&
            // Background-job rows aren't deliveries: the agent started those jobs and hears their results itself.
            BackgroundUi.cardParts(m.text) == null &&
            (m.role == "event" || (m.role == "assistant" && m.turnId.isBlank() && m.messageID.isBlank()))

    /** Deliveries after the previous user message (the newest user message, the one being sent, is skipped). */
    fun collect(messages: List<ChatMessage>, sendingTurnId: String): List<String> {
        var i = messages.size - 1
        while (i >= 0 && messages[i].role == "user" && (sendingTurnId.isBlank() || messages[i].turnId == sendingTurnId)) i--
        val found = ArrayList<String>()
        while (i >= 0 && messages[i].role != "user") {
            if (isDelivery(messages[i])) found += messages[i].text.trim()
            i--
        }
        return found.take(MAX_ITEMS).reversed()
    }

    /** The text to send: deliveries quoted first (newest kept whole when over the cap), then the message. */
    fun wrap(items: List<String>, text: String): String {
        if (items.isEmpty()) return text
        val per = MAX_CHARS / items.size
        val body = items.joinToString("\n---\n") { if (it.length > per) it.take(per) + "…" else it }
        return "[Delivered in this chat by a scheduled job or alert since my last message. I didn't write it; it's context for my reply]\n" +
            body + "\n[end of delivered context]\n\n" + text
    }
}
