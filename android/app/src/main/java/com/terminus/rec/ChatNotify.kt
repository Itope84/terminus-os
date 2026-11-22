package com.terminus.rec

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Local notifications, no Firebase: opencode turns completing while the app
 *  is backgrounded + Echo recording milestones, all on the dedicated
 *  "Chat" channel (IMPORTANCE_DEFAULT so they ping + peek). */
object ChatNotify {
    const val CHANNEL_ID = "chat"
    /** Something is waiting on the person (browser hand-off, approval, failed run): peeks over other apps. */
    const val NEEDS_YOU_CHANNEL = "needs_you"
    /** Per-chat ids live in [TURN_ID, TURN_ID + 900000): clear of the fixed ids (browser 1000, milestone
     *  1002, wake alarm 2001) that a chat's hash used to be able to collide with. */
    const val TURN_ID = 100_000
    const val MILESTONE_ID = 1002
    /** Agent browser needs the person (hand-off / approval). Below TURN_ID's range, above clearAll's floor. */
    const val BROWSER_ID = 1000
    /** Intent extra carrying the chat session id (tap → open thread). */
    const val EXTRA_CHAT_ID = "chatId"
    const val REMOTE_REPLY_KEY = "terminus.notification.reply"

    fun hasPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ActivityCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Stored tone (content:// uri, null = system default). */
    fun soundUri(ctx: Context): Uri? {
        val raw = Prefs(ctx).chatSound()
        if (raw.isBlank()) return null
        return try { Uri.parse(raw) } catch (_: Exception) { null }
    }

    /** Pure: does the Chat channel need (re)creating? Only when it is missing or the tone the APP stored
     *  changed since the channel was last built. It must never compare against the channel's own sound:
     *  with no custom tone the channel reports the system default URI, which never equals "no tone", so
     *  the old check deleted and rebuilt the channel on EVERY notification. Deleting a channel dismisses
     *  its notifications still in the tray and resets whatever the person set in system settings.
     *  Unit-tested. */
    internal fun channelNeedsBuild(exists: Boolean, applied: String?, want: String): Boolean =
        !exists || (applied != null && applied != want)

    /** Create the channel if missing; delete + re-create only when the stored
     *  tone changed (channel sound is immutable after creation). */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val want = soundUri(ctx)
        val wantRaw = want?.toString() ?: ""
        val sp = ctx.getSharedPreferences("chat_notify", Context.MODE_PRIVATE)
        val cur = try { nm.getNotificationChannel(CHANNEL_ID) } catch (_: Exception) { null }
        val applied = sp.getString("appliedSound", null)
        if (cur != null && applied == null) {
            // Built by an older version (which rebuilt it constantly, so it already matches): adopt it.
            sp.edit().putString("appliedSound", wantRaw).apply()
            return
        }
        if (!channelNeedsBuild(cur != null, applied, wantRaw)) return
        if (cur != null) try { nm.deleteNotificationChannel(CHANNEL_ID) } catch (_: Exception) {}
        sp.edit().putString("appliedSound", wantRaw).apply()
        val ch = NotificationChannel(CHANNEL_ID, "Chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Chat replies and recording milestones"
            if (want != null) {
                setSound(want, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
        }
        try { nm.createNotificationChannel(ch) } catch (_: Exception) {}
    }

    /** Create the needs-you channel once (louder than Chat; the person can still mute it in system settings). */
    fun ensureNeedsYouChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NEEDS_YOU_CHANNEL) != null) return
        val ch = NotificationChannel(NEEDS_YOU_CHANNEL, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent is waiting on you: browser hand-offs, approvals, failed jobs"
        }
        try { nm.createNotificationChannel(ch) } catch (_: Exception) {}
    }

    private fun base(
        ctx: Context, title: String, text: String, chatId: String? = null,
        channel: String = CHANNEL_ID, withReply: Boolean = true,
    ): NotificationCompat.Builder {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            if (!chatId.isNullOrBlank()) putExtra(EXTRA_CHAT_ID, chatId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, chatId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)

        if (withReply && !chatId.isNullOrBlank()) {
            // Reply without opening the app (inline on the phone, by voice in Android Auto): a broadcast, so
            // the action needs no screen. NotificationReplyReceiver sends it and updates this notification.
            val replyPendingIntent = PendingIntent.getBroadcast(
                ctx,
                chatId.hashCode() xor 0x52504C59,
                Intent(ctx, NotificationReplyReceiver::class.java).apply {
                    action = NotificationReplyReceiver.ACTION_REPLY
                    putExtra(EXTRA_CHAT_ID, chatId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = androidx.core.app.RemoteInput.Builder(REMOTE_REPLY_KEY)
                .setLabel("Reply")
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_reply_notification, "Reply", replyPendingIntent
            ).addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
            builder.addAction(replyAction)
            val readPendingIntent = PendingIntent.getBroadcast(
                ctx,
                chatId.hashCode() xor 0x52454144,
                Intent(ctx, NotificationReplyReceiver::class.java).apply {
                    action = NotificationReplyReceiver.ACTION_MARK_READ
                    putExtra(EXTRA_CHAT_ID, chatId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addInvisibleAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notif, "Mark as read", readPendingIntent)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )
            // Messaging style: what Android Auto reads aloud, and how the phone groups a conversation.
            val me = androidx.core.app.Person.Builder().setName("You").build()
            val them = androidx.core.app.Person.Builder().setName(title.ifBlank { "Terminus" }).build()
            builder.setStyle(
                NotificationCompat.MessagingStyle(me)
                    .setConversationTitle(title)
                    .addMessage(text, System.currentTimeMillis(), them)
            )
        }
        return builder
    }

    /** After a reply sent from the notification: replace its line ("Sent: ...", "Waiting to send ...")
     *  quietly, so the reply spinner stops and the car reads the outcome. */
    fun notifyReplyState(ctx: Context, chatId: String, chatTitle: String, line: String) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        val title = chatTitle.replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Terminus" }
        try {
            NotificationManagerCompat.from(ctx).notify(
                turnNotifId(chatId),
                base(ctx, title, line, chatId).setOnlyAlertOnce(true).setSilent(true).build(),
            )
        } catch (_: Exception) {}
    }

    /** The answer to a message sent from the car or a notification: the whole reply, already cleaned for
     *  speech, as a fresh alert (the old one is cancelled first so Android Auto treats it as new and reads
     *  it). No quiet hours and no once-a-minute limit: it answers something you just asked. */
    fun notifyReply(ctx: Context, chatId: String, chatTitle: String, text: String) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        val title = chatTitle.replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Terminus" }
        try {
            val nm = NotificationManagerCompat.from(ctx)
            nm.cancel(turnNotifId(chatId))
            nm.notify(turnNotifId(chatId), base(ctx, title, text, chatId).build())
        } catch (_: Exception) {}
    }

    private val lastTurnPing = HashMap<String, Long>()

    /** True the first time a chat's reply is announced within [windowMs]; later callers stay quiet. */
    internal fun firstTurnPing(chatId: String, now: Long = System.currentTimeMillis(), windowMs: Long = 60_000): Boolean =
        synchronized(lastTurnPing) {
            val prev = lastTurnPing[chatId]
            if (prev != null && now - prev < windowMs) return false
            lastTurnPing[chatId] = now
            if (lastTurnPing.size > 200) lastTurnPing.entries.removeAll { now - it.value > windowMs }
            true
        }

    /** Per-chat notification id: stable, non-negative, inside clearAll's
     *  range (raw hashCode can be negative, which used to dodge clearing). */
    private fun turnNotifId(chatId: String?): Int =
        TURN_ID + Math.floorMod(chatId?.hashCode() ?: 0, 100000)

    /** Opening a chat retires its tray ping: the in-app thread is now the
     *  reader, so a stale notification must not re-assert unread. In-app
     *  unread clears separately via ChatStore.markRead. */
    fun clearChat(ctx: Context, chatId: String?) {
        try {
            NotificationManagerCompat.from(ctx).cancel(turnNotifId(chatId))
        } catch (_: Exception) {}
    }

    /** A long opencode turn finished while the app was backgrounded.
     *  Tap deep-links straight into the chat thread (EXTRA_CHAT_ID).
     *  Per-chat ID so concurrent job pings don't overwrite each other. */
    fun notifyTurn(ctx: Context, preview: String, chatId: String? = null, chatTitle: String = "") {
        if (!hasPermission(ctx)) return
        // A reply to a car or notification send is announced by ReplyFollow, in full; don't say it twice.
        if (ReplyFollow.owns(chatId)) return
        // The app's own stream and LiveLinkService can both see the same reply finish: one ping per chat a minute.
        if (!firstTurnPing(chatId ?: "")) return
        ensureChannel(ctx)
        val text = notifPreview(preview).ifBlank { "Your reply is ready." }
        // Name the chat: "Chat reply ready" told you nothing about which one.
        val title = chatTitle.replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Reply ready" }
        try {
            NotificationManagerCompat.from(ctx)
                .notify(turnNotifId(chatId), base(ctx, title, text, chatId).build())
        } catch (_: Exception) {}
    }

    /** Schedule/system delivery landed in a job chat. Same channel (so the
     *  cog-picked tone plays), tap deep-links into the job thread.
     *  Per-chat ID so concurrent job pings don't overwrite each other. */
    fun notifyInbox(ctx: Context, job: String, preview: String, chatId: String? = null) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        val text = preview.replace(Regex("\\s+"), " ").trim().take(160)
            .ifBlank { "New delivery." }
        val title = if (job.isBlank()) "Terminus" else "Terminus · $job"
        try {
            NotificationManagerCompat.from(ctx)
                .notify(turnNotifId(chatId), base(ctx, title, text, chatId).build())
        } catch (_: Exception) {}
    }

    /** An agent is waiting on the person. Tap deep-links into the chat that needs them (the chat id is the
     *  same one clearChat retires when the thread opens). No reply box: the answer is an action, not text. */
    fun notifyNeedsYou(ctx: Context, chatTitle: String, reason: String, chatId: String) {
        if (!hasPermission(ctx)) return
        ensureNeedsYouChannel(ctx)
        val text = reason.replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "Open the chat to continue." }
        val title = if (chatTitle.isBlank()) "Needs you" else "Needs you \u00b7 $chatTitle"
        try {
            NotificationManagerCompat.from(ctx).notify(
                turnNotifId(chatId),
                base(ctx, title, text, chatId, NEEDS_YOU_CHANNEL, withReply = false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build()
            )
        } catch (_: Exception) {}
    }

    /** Clear our notifications (turn/inbox/milestone pings) without touching
     *  the recorder's foreground notification (id 1). Ours live ≥ 1000. */
    fun clearAll(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            for (n in nm.activeNotifications) {
                if (n.id >= 1000) nm.cancel(n.id)
            }
        } catch (_: Exception) {}
    }

    /** Echo recording milestone (chunk roll / stop). */
    fun notifyMilestone(ctx: Context, title: String, text: String) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx)
                .notify(MILESTONE_ID, base(ctx, title, text).build())
        } catch (_: Exception) {}
    }

    /** The agent browser needs the person while the app is in the background. A direct local ping: no chat
     *  message, no inbox round trip. Tap opens the app (the browser view is already raised). */
    fun notifyBrowser(ctx: Context, title: String, text: String, chatId: String? = null) {
        if (!hasPermission(ctx)) return
        ensureNeedsYouChannel(ctx)
        val body = text.replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "Open Terminus to continue." }
        try {
            NotificationManagerCompat.from(ctx).notify(
                BROWSER_ID,
                base(ctx, title, body, chatId?.takeIf { it.isNotBlank() }, NEEDS_YOU_CHANNEL, withReply = false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build()
            )
        } catch (_: Exception) {}
    }

    fun clearBrowser(ctx: Context) {
        try { NotificationManagerCompat.from(ctx).cancel(BROWSER_ID) } catch (_: Exception) {}
    }

    /** Deep link to this app's Chat channel in system settings. */
    fun channelSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
}

/** Pure: a reply's markdown as plain notification text (no asterisks, backticks, heading marks or link
 *  syntax in the tray), one line, capped. Unit-tested. */
internal fun notifPreview(raw: String, max: Int = 160): String {
    var t = raw
        .replace(Regex("```[a-zA-Z0-9]*"), " ")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*>\\s?"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        .replace(Regex("(\\*\\*|__|`)"), "")
    t = t.replace(Regex("\\s+"), " ").trim()
    return if (t.length > max) t.take(max - 1).trimEnd() + "…" else t
}

/** Push preview for inbox deliveries is defined in ThreadUi.kt (day line + money).
 *  Kept here for history: old Trade:/Trim: form removed with the life-first brief. */
