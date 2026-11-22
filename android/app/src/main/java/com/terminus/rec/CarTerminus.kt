package com.terminus.rec

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.app.Person
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Terminus in Android Auto: "Ask Terminus" (a standing chat for new questions) plus the most recent chats.
 *  Tap one and speak: the car's own reply action sends it like any message, and the answer is read aloud
 *  when it lands (the same notification path the phone uses). Nothing here is drivable-UI custom: the host
 *  draws the conversation list, playback and voice reply. */
class CarTerminusService : CarAppService() {
    // Sideloaded personal app: any Android Auto host may bind.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarChatsScreen(carContext)
    }
}

class CarChatsScreen(ctx: CarContext) : Screen(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var lastKey = ""
    // Replies land in ChatStore from the app or the live link: repaint when the visible content changes.
    private val poll = object : Runnable {
        override fun run() {
            val k = contentKey()
            if (k != lastKey) invalidate()
            main.postDelayed(this, 3_000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { main.post(poll) }
            override fun onStop(owner: LifecycleOwner) { main.removeCallbacks(poll) }
        })
    }

    private fun store() = ChatStore(carContext)

    private fun chats(): List<ChatSession> {
        val askId = askChatId(carContext)
        val recent = try {
            store().list().filter { it.id != askId && it.messages.any { m -> m.role == "user" || m.role == "assistant" } }
                .sortedByDescending { it.updatedAt }.take(MAX_RECENT)
        } catch (_: Exception) { emptyList() }
        val ask = try { store().get(askId) } catch (_: Exception) { null }
        return listOfNotNull(ask) + recent
    }

    private fun contentKey(): String = chats().joinToString("|") { c ->
        c.id + ":" + c.messages.size + ":" + (c.messages.lastOrNull()?.text?.hashCode() ?: 0)
    }

    override fun onGetTemplate(): Template {
        ensureAskChat(carContext)
        val me = Person.Builder().setName("Me").setKey("self").build()
        val items = ItemList.Builder()
        val list = chats()
        lastKey = contentKey()
        for (c in list) {
            val them = Person.Builder().setName(if (c.id == askChatId(carContext)) "Terminus" else c.title.ifBlank { "Terminus" }).setKey("chat:" + c.id).build()
            val msgs = carMessages(c, me, them)
            items.addItem(
                ConversationItem.Builder()
                    .setId(c.id)
                    .setTitle(CarText.create(if (c.id == askChatId(carContext)) ASK_TITLE else c.title.ifBlank { "Chat" }))
                    .setSelf(me)
                    .setMessages(msgs)
                    .setConversationCallback(object : ConversationCallback {
                        override fun onMarkAsRead() {
                            try { store().markRead(c.id) } catch (_: Exception) {}
                            ChatNotify.clearChat(carContext, c.id)
                        }
                        override fun onTextReply(replyText: String) {
                            val t = replyText.trim()
                            if (t.isEmpty()) return
                            Thread { NotificationReplyReceiver.sendFromOutside(carContext, c.id, t, notify = false) }.start()
                            main.postDelayed({ invalidate() }, 400)
                        }
                    })
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("Terminus").build())
            .setSingleList(items.build())
            .build()
    }

    /** Last few readable messages; the host wants at least one, so a fresh chat shows a prompt. */
    private fun carMessages(c: ChatSession, me: Person, them: Person): List<CarMessage> {
        val rows = c.messages.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() && it.text != "…" }
            .takeLast(MAX_MESSAGES)
        val unreadFrom = c.messages.size - c.unread.coerceAtLeast(0)
        val out = rows.map { m ->
            CarMessage.Builder()
                .setSender(if (m.role == "user") me else them)
                .setBody(CarText.create(carText(m.text)))
                .setReceivedTimeEpochMillis(if (m.ts > 0) m.ts else c.updatedAt)
                .setRead(m.role == "user" || c.messages.indexOf(m) < unreadFrom)
                .build()
        }
        if (out.isNotEmpty()) return out
        return listOf(
            CarMessage.Builder().setSender(them).setBody(CarText.create("Ask me anything. Tap reply and speak."))
                .setReceivedTimeEpochMillis(System.currentTimeMillis()).setRead(true).build()
        )
    }

    companion object {
        const val ASK_TITLE = "Ask Terminus"
        private const val MAX_RECENT = 5
        private const val MAX_MESSAGES = 5
        private const val PREF_ASK = "carAskChatId"

        /** Spoken text: markdown, links and code fences read badly aloud. */
        fun carText(md: String): String = SpokenText.of(md, 1200)

        fun askChatId(ctx: android.content.Context): String =
            ctx.getSharedPreferences("terminus_car", 0).getString(PREF_ASK, "") ?: ""

        /** The standing "Ask Terminus" chat, created on first use with the default backend and model. */
        fun ensureAskChat(ctx: android.content.Context) {
            val store = ChatStore(ctx)
            val id = askChatId(ctx)
            if (id.isNotBlank() && store.get(id) != null) return
            val prefs = Prefs(ctx)
            val backend = prefs.defaultBackend()
            val s = store.create(backend, prefs.defaultModel(backend))
            store.rename(s.id, ASK_TITLE)
            ctx.getSharedPreferences("terminus_car", 0).edit().putString(PREF_ASK, s.id).apply()
        }
    }
}
