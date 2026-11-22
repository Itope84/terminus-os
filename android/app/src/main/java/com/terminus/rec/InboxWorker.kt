package com.terminus.rec

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Store one inbox entry in the chat it belongs to; shared by the app and the worker so both route identically.
 *  An event (kind "event") lands in the chat that caused it as a needs-you card and flags that chat; when the
 *  chat is gone it lands in Alerts. Returns the chat id, or null when the entry was blank or already stored. */
internal fun landInboxEntry(store: ChatStore, e: OpencodeApi.InboxItem, now: Long = System.currentTimeMillis()): String? {
    val isEvent = e.kind == "event"
    val cid = inboxTarget(e.job, e.chat, e.kind) { store.get(it) != null }
    val plain = if (e.title.isBlank()) e.body else "${e.title}\n${e.body}"
    if (plain.isBlank()) return null
    val role = if (isEvent) "event" else "assistant"
    val text = if (isEvent) needsYouText(e.title, e.body) else plain
    val exists = store.get(cid)?.messages?.any { it.role == role && it.text == text && it.ts == e.at } ?: false
    if (exists) return null
    // Create the thread only when fresh content lands: ensuring up front resurrects deleted threads as empty shells.
    store.ensure(cid, if (cid == "alerts") "Alerts" else e.job)
    // kind "reply": the chat's own agent wrote this on its own (a background job's result woke it). A message
    // id marks it as the agent's own words, so it isn't sent back to the agent as an outside delivery.
    val mid = if (e.kind == "reply") "reply-${e.at}" else ""
    store.addMessage(cid, ChatMessage(role, text, ts = e.at, settled = role == "assistant", messageID = mid))
    if (isEvent) {
        val until = e.at + needsYouTtlMs(e.job)
        if (needsYouActive("x", until, now)) store.setNeedsYou(cid, needsYouReason(e.title, e.body), until)
    }
    return cid
}

/** Background inbox backstop — no Firebase, no paid push.
 *  WorkManager runs this every 15 min (platform minimum) even with the
 *  app dead: pull /api/inbox since the stored cursor, merge new entries
 *  into their job threads, advance the cursor, ping outside quiet hours.
 *  Same job→thread mapping as MainActivity.targetChatFor; keep in sync.
 *  ChatStore is synchronized-lock guarded, safe off the UI thread.
 *  Bell dot picks up unread on next open (renderBell sums unread). */
class InboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.token().isBlank()) return Result.success()
        // Periodic retry for anything that failed to upload while offline (both drains are
        // single-flight and cheap when nothing is pending).
        Uploader.enqueue(applicationContext)
        GalleryUploader.drain(applicationContext)
        // Transient (offline/tailnet down) — next 15-min window retries.
        pullInboxAndNotify(applicationContext)
        return Result.success()
    }

    companion object {
        private val pullLock = Any()

        /** Pull /api/inbox since the stored cursor, land each entry in its thread and notify outside quiet
         *  hours. Shared by this worker and LiveLinkService (instant path); serialized so the two never
         *  land or notify the same entry twice. False = the server could not be reached. */
        fun pullInboxAndNotify(ctx: Context): Boolean = synchronized(pullLock) {
            val prefs = Prefs(ctx)
            if (prefs.token().isBlank()) return true
            val items = try {
                OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, prefs.lastInboxSync())
            } catch (_: Exception) {
                return false
            }
            if (items.isEmpty()) return true
            val store = ChatStore(ctx)
            var maxAt = prefs.lastInboxSync()
            for (e in items) {
                maxAt = maxOf(maxAt, e.at)
                val cid = landInboxEntry(store, e) ?: continue
                // Same as the app's own sync: alarm links arm when the entry lands, even with the app dead.
                try { WakeAlarm.armFromText(ctx, "${e.title}\n${e.body}") } catch (_: Exception) {}
                if (quiet(prefs)) continue
                val chat = store.get(cid)
                if (e.kind == "event" && chat?.waitingOnYou() == true) {
                    ChatNotify.notifyNeedsYou(ctx, chat.title, chat.needsYou, cid)
                } else if (e.kind == "reply") {
                    ChatNotify.notifyTurn(ctx, e.body, cid, chat?.title.orEmpty())
                } else {
                    ChatNotify.notifyInbox(ctx, e.job, inboxPreview(e.body.ifBlank { e.title }), cid)
                }
            }
            prefs.setLastInboxSync(maxAt)
            return true
        }

        /** Mirror of MainActivity.inQuietHours (defaults 22:00–07:00). */
        fun quiet(prefs: Prefs): Boolean {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val s = prefs.quietStart()
            val e = prefs.quietEnd()
            return if (s <= e) h in s until e else h >= s || h < e
        }
    }
}
