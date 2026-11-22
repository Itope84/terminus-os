package com.terminus.rec

import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The chat page's side of the ✕ on a running background job (window.TerminusJobs). The line only knows the
 *  job's name; the server finds the job by name in the open chat and stops it (POST /api/background/stop).
 *  The answer comes back as window.__bgStopped(cmd, ok, message). */
class JobsBridge(private val prefs: Prefs, private val chatId: () -> String?, private val web: () -> WebView?) {
    private val http by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    @JavascriptInterface
    fun stop(cmd: String) {
        val chat = chatId() ?: return
        Thread {
            var ok = false
            var msg = ""
            try {
                val base = prefs.serverUrl().trimEnd('/')
                val req = Request.Builder().url("$base/api/background/stop")
                    .header("Authorization", "Bearer " + prefs.token())
                    .post(JSONObject().put("chatId", chat).put("label", cmd).toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { res ->
                    ok = res.isSuccessful
                    if (!ok) msg = try { JSONObject(res.body?.string() ?: "").optString("error") } catch (_: Exception) { "" }
                }
            } catch (_: Exception) { msg = "server unreachable" }
            val js = "window.__bgStopped&&window.__bgStopped(" + JSONObject.quote(cmd) + "," + ok + "," + JSONObject.quote(msg.take(160)) + ")"
            val w = web() ?: return@Thread
            w.post { try { w.evaluateJavascript(js, null) } catch (_: Exception) {} }
        }.start()
    }
}
