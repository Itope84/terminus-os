package com.terminus.rec

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** A message sent from outside the app (the car, a notification reply) is followed to its answer here, so the
 *  answer is always announced: it polls the server's turn registry until the turn settles, writes the reply
 *  into the chat, and posts it as the chat's notification in full, cleaned for speech (what Android Auto reads
 *  aloud). It does not depend on the app being alive or the live link being up, and ignores quiet hours (you
 *  just asked). While it owns a chat, the app's and the live link's own reply pings stand down, so the answer
 *  is spoken once. */
object ReplyFollow {
    private const val FOLLOW_MS = 30 * 60_000L
    /** After announcing, other sources stay quiet this long (they may see the same turn finish). */
    private const val AFTER_MS = 90_000L

    /** Follows running per chat, and when the last announcement's quiet window ends. */
    private val running = ConcurrentHashMap<String, Int>()
    private val quietUntil = ConcurrentHashMap<String, Long>()

    /** Set by MainActivity while alive: true when that chat is on screen (no ping needed). */
    @Volatile var isWatching: ((chatId: String) -> Boolean)? = null

    fun owns(chatId: String?, now: Long = System.currentTimeMillis()): Boolean =
        !chatId.isNullOrBlank() && ((running[chatId] ?: 0) > 0 || (quietUntil[chatId] ?: 0L) > now)

    fun start(ctx: Context, chatId: String, turnId: String) {
        val app = ctx.applicationContext
        running.merge(chatId, 1, Int::plus)
        Thread({
            try { follow(app, chatId, turnId) } finally { running.computeIfPresent(chatId) { _, n -> (n - 1).takeIf { it > 0 } } }
        }, "reply-follow").start()
    }

    private fun follow(ctx: Context, chatId: String, turnId: String) {
        val prefs = Prefs(ctx)
        val deadline = System.currentTimeMillis() + FOLLOW_MS
        var wait = 1_500L
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            val backend = try { ChatStore(ctx).get(chatId)?.backend?.ifBlank { null } } catch (_: Exception) { null }
            val r = try {
                OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), chatId, turnId, backend)
            } catch (_: Exception) {
                wait = (wait * 2).coerceAtMost(15_000L)
                continue
            }
            if (r.settled) {
                deliver(ctx, chatId, turnId, r.text, r.error, r.messageID)
                return
            }
            wait = 2_000L
        }
        // No answer in time: the chat goes back to the normal paths (the app or live link pings when it lands).
        announce(ctx, chatId, "Still working on that. I'll show the answer in the chat when it's ready.")
    }

    private fun deliver(ctx: Context, chatId: String, turnId: String, text: String, error: String, messageID: String) {
        val store = ChatStore(ctx)
        if (text.isNotBlank()) {
            try { store.settleTurn(chatId, turnId, text, messageID = messageID) } catch (_: Exception) {}
        } else {
            try { store.dropPlaceholder(chatId, turnId) } catch (_: Exception) {}
            try { store.setUserSendFailed(chatId, turnId, true, error.ifBlank { "no reply" }) } catch (_: Exception) {}
        }
        try { store.flushNow() } catch (_: Exception) {}
        val spoken = if (text.isNotBlank()) SpokenText.of(text) else "That reply failed. Open the chat to retry."
        quietUntil[chatId] = System.currentTimeMillis() + AFTER_MS
        announce(ctx, chatId, spoken)
    }

    private fun announce(ctx: Context, chatId: String, text: String) {
        if (try { isWatching?.invoke(chatId) == true } catch (_: Exception) { false }) return
        val title = try { ChatStore(ctx).get(chatId)?.title.orEmpty() } catch (_: Exception) { "" }
        ChatNotify.notifyReply(ctx, chatId, title, text)
    }
}
