package com.terminus.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reply straight from a notification without opening the app: inline on the phone, by voice in Android Auto
 *  (the car only offers replies that need no screen). The message lands in the chat like any send, the answer
 *  comes back in full as the next notification via ReplyFollow, and a send that cannot reach the Mac waits in the
 *  outbox. Text only; anything richer is a tap into the chat. */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val chatId = intent.getStringExtra(ChatNotify.EXTRA_CHAT_ID).orEmpty()
        if (intent.action == ACTION_MARK_READ) {
            if (chatId.isNotBlank()) try { ChatStore(ctx).markRead(chatId) } catch (_: Exception) {}
            ChatNotify.clearChat(ctx, chatId)
            return
        }
        val text = try {
            androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatNotify.REMOTE_REPLY_KEY)?.toString()?.trim()
        } catch (_: Exception) { null }
        if (chatId.isBlank() || text.isNullOrBlank()) return
        val pending = goAsync()
        Thread {
            try { sendFromOutside(ctx, chatId, text) } finally { pending.finish() }
        }.start()
    }

    companion object {
        const val ACTION_REPLY = "com.terminus.rec.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.terminus.rec.NOTIF_MARK_READ"

        /** Set by MainActivity while alive: follow a turn sent from a notification (stream + placeholder). */
        @Volatile var onExternalTurn: ((chatId: String, turnId: String) -> Unit)? = null

        /** Send [text] into [chatId] from outside the app (notification, car). Blocking: call off the main thread.
         *  [notify] updates the chat's notification with the outcome. */
        fun sendFromOutside(ctx: Context, chatId: String, text: String, notify: Boolean = true) {
            val store = ChatStore(ctx)
            val session = store.get(chatId) ?: run { if (notify) ChatNotify.notifyReplyState(ctx, chatId, "", "That chat was deleted"); return }
            val prefs = Prefs(ctx)
            val turnId = "t-${System.currentTimeMillis().toString(36)}-${(0..0xffffff).random().toString(36)}"
            store.appendUserTurn(chatId, ChatMessage("user", text, turnId = turnId))
            store.addMessage(chatId, ChatMessage("assistant", "…", turnId = turnId))
            try { store.markRead(chatId) } catch (_: Exception) {}
            val backend = session.backend.ifBlank { prefs.defaultBackend() }
            val model = if (backend == "opencode") session.model.takeIf { it.contains("/") } else session.model.ifBlank { null }
            try {
                val ok = OpencodeApi.postChat(
                    prefs.serverUrl(), prefs.token(), chatId = chatId, sessionID = session.remoteId.ifBlank { null },
                    title = null, text = text, model = model, turnId = turnId, backend = backend,
                    effort = session.effort.takeIf { it == "low" || it == "medium" || it == "high" },
                    cwd = session.cwd.ifBlank { null },
                )
                ok.sessionID?.takeIf { it.isNotBlank() && it != session.remoteId }?.let { store.setRemoteId(chatId, it) }
                if (notify) ChatNotify.notifyReplyState(ctx, chatId, session.title, "Sent: $text")
                // The answer is followed to the end and read back, whatever state the app is in.
                ReplyFollow.start(ctx, chatId, turnId)
                // The app, if it is alive, starts following the reply like one of its own sends.
                try { onExternalTurn?.invoke(chatId, turnId) } catch (_: Exception) {}
            } catch (e: Exception) {
                store.dropPlaceholder(chatId, turnId)
                if (Outbox.neverSent(e)) {
                    store.setUserSendFailed(chatId, turnId, true, Outbox.PREFIX + Outbox.reason(ctx, prefs.serverUrl()))
                    if (notify) ChatNotify.notifyReplyState(ctx, chatId, session.title, "Waiting to send: ${Outbox.reason(ctx, prefs.serverUrl())}")
                } else {
                    store.setUserSendFailed(chatId, turnId, true, e.message ?: "network error")
                    if (notify) ChatNotify.notifyReplyState(ctx, chatId, session.title, "Couldn't send. Open the chat to retry.")
                }
            }
            try { store.flushNow() } catch (_: Exception) {}
        }

    }
}
