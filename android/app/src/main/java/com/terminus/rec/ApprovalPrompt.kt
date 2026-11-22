package com.terminus.rec

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Allow / Deny prompts for anything on the server that asks for a human
 *  decision (server/src/approvals.ts). Requests arrive on the phone channel
 *  while the app is open; one sheet shows at a time. Nothing is decided by
 *  dismissing: the sheet only closes on Allow, Deny, or when the server
 *  withdraws or times out the request, and an unanswered request is a no.
 *  While a request names its chat, that chat is flagged "needs you" and the
 *  sheet offers a link into it. */
class ApprovalPrompt(private val act: AppCompatActivity, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queue = ArrayDeque<JSONObject>()
    private val seen = LinkedHashSet<String>() // ids already shown, so a reconnect replay never asks twice
    private var showingId: String? = null
    private var showing: BottomSheetDialog? = null
    private var expiry: Runnable? = null
    private var tick: Runnable? = null
    private var showingChat: String = ""

    /** (chatId, reason, on): flag / clear the chat's needs-you state. */
    var onNeedsYou: ((String, String, Boolean) -> Unit)? = null
    /** Open a chat thread (the sheet's "Open chat" link). */
    var onOpenChat: ((String) -> Unit)? = null

    /** Any thread. [event] is "approval" or "approval_cancel". */
    fun onEvent(event: String, data: String) {
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        main.post {
            when (event) {
                "approval" -> enqueue(o)
                "approval_cancel" -> withdraw(o.optString("id"))
            }
        }
    }

    private fun enqueue(o: JSONObject) {
        val id = o.optString("id")
        if (id.isBlank() || id in seen) return
        if (o.optLong("expiresAt", 0L) in 1 until System.currentTimeMillis()) return
        seen.add(id)
        while (seen.size > 200) seen.remove(seen.first())
        queue.addLast(o)
        showNext()
    }

    private fun withdraw(id: String) {
        queue.removeAll { it.optString("id") == id }
        if (showingId == id) closeCurrent()
        showNext()
    }

    private fun showNext() {
        if (showing != null || queue.isEmpty() || act.isFinishing || act.isDestroyed) return
        val o = queue.removeFirst()
        val id = o.optString("id")
        val chat = o.optString("chat")
        val sheet = BottomSheetDialog(act, R.style.CreamBottomSheet)
        val v = LayoutInflater.from(act).inflate(R.layout.sheet_approval, null)
        sheet.setContentView(v)
        val source = o.optString("source", "Agent").ifBlank { "Agent" }
        v.findViewById<TextView>(R.id.apprSource).text = source
        v.findViewById<TextView>(R.id.apprTitle).text = o.optString("title").ifBlank { "Needs approval" }
        val detail = o.optString("detail")
        v.findViewById<View>(R.id.apprDetailWrap).visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.apprDetail).text = detail
        v.findViewById<MaterialButton>(R.id.apprAllow).setOnClickListener { answer(id, "allow") }
        v.findViewById<MaterialButton>(R.id.apprDeny).setOnClickListener { answer(id, "deny") }
        val open = v.findViewById<TextView>(R.id.apprOpen)
        if (chat.isNotBlank() && onOpenChat != null) {
            open.visibility = View.VISIBLE
            // Opening the chat leaves the sheet up: the answer is still owed.
            open.setOnClickListener { onOpenChat?.invoke(chat) }
        }
        sheet.setCancelable(false)
        sheet.setCanceledOnTouchOutside(false)
        showingId = id
        showing = sheet
        showingChat = chat
        try { sheet.show() } catch (_: Exception) { showing = null; showingId = null; showingChat = ""; return }
        try { sheet.behavior.isDraggable = false; sheet.behavior.isHideable = false } catch (_: Exception) {}
        if (chat.isNotBlank()) onNeedsYou?.invoke(chat, "$source needs approval", true)
        val expiresAt = o.optLong("expiresAt", 0L)
        val timer = v.findViewById<TextView>(R.id.apprTimer)
        if (expiresAt > 0) {
            val t = object : Runnable {
                override fun run() {
                    if (showingId != id) return
                    val left = (expiresAt - System.currentTimeMillis()) / 1000
                    if (left <= 0) { closeCurrent(); showNext(); return }
                    timer.text = "auto-deny in ${left / 60}:${String.format("%02d", left % 60)}"
                    main.postDelayed(this, 1000)
                }
            }
            tick = t
            t.run()
        }
    }

    private fun closeCurrent() {
        expiry?.let { main.removeCallbacks(it) }
        expiry = null
        tick?.let { main.removeCallbacks(it) }
        tick = null
        try { showing?.dismiss() } catch (_: Exception) {}
        showing = null
        showingId = null
        if (showingChat.isNotBlank()) onNeedsYou?.invoke(showingChat, "", false)
        showingChat = ""
    }

    private fun answer(id: String, decision: String) {
        closeCurrent()
        send(id, decision)
        showNext()
    }

    private fun send(id: String, decision: String) {
        Thread {
            try {
                val base = prefs.serverUrl().trimEnd('/')
                val tok = prefs.token()
                val body = JSONObject().put("id", id).put("decision", decision).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$base/api/approvals/decide").post(body)
                    .apply { if (tok.isNotBlank()) header("Authorization", "Bearer $tok") }.build()
                http.newCall(req).execute().use { }
            } catch (_: Exception) { /* the server times the request out: a no */ }
        }.start()
    }

    fun destroy() {
        onNeedsYou = null
        onOpenChat = null
        queue.clear()
        closeCurrent()
    }
}
