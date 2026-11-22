package com.terminus.rec

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class RemoteModel(val id: String, val provider: String, val label: String, val description: String = "", val deprecated: Boolean = false) {
    val short: String get() = id.substringAfter("/")
}

/** Backend descriptor + capabilities from GET /api/backends. Capability
 *  flags gate features; the app never branches per vendor. Missing keys
 *  default closed (false) except the historically-opencode read paths. */
data class BackendCapabilities(
    val streaming: Boolean = true,
    val models: Boolean = true,
    val commands: Boolean = true,
    val sessionsList: Boolean = true,
    val history: Boolean = true,
    val resume: Boolean = true,
    val interruptTurn: Boolean = true,
    val killProcess: Boolean = true,
    val compact: Boolean = true,
    val title: Boolean = true,
    val deleteSession: Boolean = true,
    val fork: Boolean = true,
    val approvals: Boolean = false,
    val attachments: Boolean = true,
    val overlappingTurns: Boolean = false,
    /** Send-now support: gates the per-message Send-now links. Missing = closed. */
    val steer: Boolean = false,
    /** Honours the per-chat reasoning effort: gates the effort picker. Missing = closed. */
    val effort: Boolean = false,
    /** Reports usage: gates the context ring. Missing = open (older servers). */
    val usage: Boolean = true,
)

data class BackendInfo(
    val id: String,
    val label: String,
    val shortLabel: String,
    val available: Boolean,
    val authenticated: Boolean,
    val capabilities: BackendCapabilities,
)

data class BackendCatalog(val backends: List<BackendInfo>, val defaultBackend: String)

/** Pure catalog parsers (unit-tested): network stays in the fetchers, all
 *  shape decisions here. Model ids are opaque — no "/" requirement. */
internal fun parseModels(body: String): List<RemoteModel> {
    val arr: JSONArray = try {
        JSONObject(body).optJSONArray("models") ?: JSONArray()
    } catch (_: Exception) { return emptyList() }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id", "")
        if (id.isBlank()) null
        else {
            val prov = o.optString("provider", "").ifBlank {
                if ("/" in id) id.substringBefore("/") else ""
            }
            RemoteModel(id, prov, o.optString("label", id), o.optString("description", ""), o.optBoolean("deprecated", false))
        }
    }
}

internal fun parseListenEvents(arr: JSONArray?): List<OpencodeApi.ListenEvent> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val text = o.optString("text", "").trim()
        val type = o.optString("type", "")
        if (text.isEmpty() || (type != "partial" && type != "final")) null
        else OpencodeApi.ListenEvent(type == "final", o.optDouble("t", 0.0), text)
    }
}

internal fun parseCommands(body: String): List<SlashCommand> {
    val arr: JSONArray = try {
        JSONObject(body).optJSONArray("commands") ?: JSONArray()
    } catch (_: Exception) { return emptyList() }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name", "")
        if (!name.startsWith("/")) null
        else SlashCommand(name, o.optString("description", ""), skill = o.optString("kind", "") == "skill")
    }
}

internal fun parseBackends(body: String): BackendCatalog {
    val o = try { JSONObject(body) } catch (_: Exception) { return BackendCatalog(emptyList(), "opencode") }
    val arr = o.optJSONArray("backends") ?: JSONArray()
    val backends = (0 until arr.length()).mapNotNull { i ->
        val b = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = b.optString("id", "")
        if (id.isBlank()) null
        else {
            val c = b.optJSONObject("capabilities")
            BackendInfo(
                id,
                b.optString("label", id),
                b.optString("shortLabel", b.optString("label", id)),
                b.optBoolean("available", false),
                b.optBoolean("authenticated", false),
                BackendCapabilities(
                    streaming = c?.optBoolean("streaming", true) ?: true,
                    models = c?.optBoolean("models", true) ?: true,
                    commands = c?.optBoolean("commands", true) ?: true,
                    sessionsList = c?.optBoolean("sessionsList", true) ?: true,
                    history = c?.optBoolean("history", true) ?: true,
                    resume = c?.optBoolean("resume", true) ?: true,
                    interruptTurn = c?.optBoolean("interruptTurn", true) ?: true,
                    killProcess = c?.optBoolean("killProcess", true) ?: true,
                    compact = c?.optBoolean("compact", true) ?: true,
                    title = c?.optBoolean("title", true) ?: true,
                    deleteSession = c?.optBoolean("deleteSession", true) ?: true,
                    fork = c?.optBoolean("fork", true) ?: true,
                    approvals = c?.optBoolean("approvals", false) ?: false,
                    attachments = c?.optBoolean("attachments", true) ?: true,
                    overlappingTurns = c?.optBoolean("overlappingTurns", false) ?: false,
                    effort = c?.optBoolean("effort", false) ?: false,
                    steer = c?.optBoolean("steer", false) ?: false,
                    usage = c?.optBoolean("usage", true) ?: true,
                ),
            )
        }
    }
    return BackendCatalog(backends, o.optString("defaultBackend", "opencode").ifBlank { "opencode" })
}

internal fun parseDefaults(body: String): OpencodeApi.ChatDefaults {
    val o = try { JSONObject(body) } catch (_: Exception) { return OpencodeApi.ChatDefaults("", "", "") }
    val models = mutableMapOf<String, String>()
    val mo = o.optJSONObject("defaultModels")
    if (mo != null) {
        val it = mo.keys()
        while (it.hasNext()) {
            val k = it.next()
            models[k] = mo.optString(k, "")
        }
    }
    val backend = o.optString("defaultBackend", "").ifBlank { "opencode" }
    val model = o.optString("defaultModel", models[backend] ?: "")
    return OpencodeApi.ChatDefaults(model, o.optString("systemPrompt", ""), o.optString("serverDefault", ""), backend, models, o.optInt("compactAt", 0))
}

object OpencodeApi {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** LAN fallback when the phone is off Tailnet. Single central wrapper:
     *  every API call below goes through exec(), never per call-site.
     *  TKDR: tailnet-only for now — flag off, code paths kept. */
    const val LAN_FALLBACK_ENABLED = false
    const val LAN_BASE = "http://10.0.2.2:9009"
    private const val LAN_GOOD_MS = 10 * 60 * 1000L
    @Volatile private var lanGoodUntil = 0L
    @Volatile private var lanNotice = false

    fun isLanGood(): Boolean = LAN_FALLBACK_ENABLED && System.currentTimeMillis() < lanGoodUntil

    /** Base the UI should load WebViews from (LAN while the 10-min window holds). */
    fun effectiveBase(primaryBase: String): String =
        if (LAN_FALLBACK_ENABLED && isLanGood()) LAN_BASE else primaryBase.trimEnd('/').ifBlank { primaryBase }

    /** One-shot flag: true once per LAN-fallback episode. Caller shows the
     *  inline tool notice and the flag clears. */
    fun consumeLanNotice(): Boolean {
        val v = lanNotice
        lanNotice = false
        return v
    }

    fun reportLanGood() {
        lanGoodUntil = System.currentTimeMillis() + LAN_GOOD_MS
        lanNotice = true
    }

    private fun isConnectFailure(e: IOException): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is UnknownHostException || t is SocketTimeoutException || t is ConnectException) return true
            t = t.cause
        }
        return false
    }

    /** Same path+query on the LAN host. */
    fun lanSwap(url: String): String {
        return try {
            val orig = url.toHttpUrl()
            val nb = LAN_BASE.toHttpUrl()
            orig.newBuilder().scheme(nb.scheme).host(nb.host).port(nb.port).build().toString()
        } catch (_: Exception) { url }
    }

    /** Central request wrapper: primary = Prefs.serverUrl(); on connect
     *  failure (UnknownHost/SocketTimeout/Connect, never on HTTP 401/400)
     *  retry once against LAN, preserving path+query. Tokens never logged. */
    private fun exec(primaryBase: String, buildReq: (String) -> Request): Response {
        val primary = primaryBase.trimEnd('/')
        val lanFirst = LAN_FALLBACK_ENABLED && isLanGood() && !primary.equals(LAN_BASE, true)
        val order = if (lanFirst) listOf(LAN_BASE, primary) else listOf(primary) + if (LAN_FALLBACK_ENABLED) listOf(LAN_BASE) else emptyList()
        var lastErr: IOException? = null
        for (base in order.distinct()) {
            val req = buildReq(base)
            try {
                val res = client.newCall(req).execute()
                if (base == LAN_BASE && !lanFirst) reportLanGood()
                else if (base == primary && lanFirst) lanGoodUntil = 0L
                return res
            } catch (e: IOException) {
                if (!isConnectFailure(e)) throw e
                lastErr = e
            }
        }
        throw lastErr ?: IOException("network error")
    }

    fun pingOk(primaryBase: String, backend: String = "opencode"): Boolean {
        return try {
            exec(primaryBase) { base ->
                // ?v= lets the server log which APK build is talking.
                Request.Builder().url("$base/health?backend=${java.net.URLEncoder.encode(backend, "UTF-8")}&v=${BuildConfig.VERSION_CODE}").get().build()
            }.use { res ->
                if (!res.isSuccessful) return false
                // Green only when the backend is reachable too — a live
                // node with a dead opencode must not show online.
                val body = try { res.body!!.string() } catch (_: Exception) { "" }
                if (body.isBlank()) return true
                try { JSONObject(body).optString("backend", "ok") == "ok" } catch (_: Exception) { true }
            }
        } catch (_: Exception) { false }
    }

    private fun authed(url: String, token: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    fun fetchModels(base: String, token: String, backend: String = "opencode"): List<RemoteModel> {
        exec(base) { b -> authed("$b/api/models?backend=$backend", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("models: http ${res.code}")
            return parseModels(res.body!!.string())
        }
    }

    /** Optional per-turn attachments (max 3, each ≤5MB raw). The proxy
     *  forwards them as SDK data-URI files; images should already be
     *  compressed (≤1600px JPEG80) by the caller. */
    data class ChatAttachment(val name: String, val mime: String, val base64: String)

    /** Live opencode slash commands via the terminus proxy. Throws on any
     *  failure — callers fall back to the static ChatStore.COMMANDS list. */
    fun fetchCommands(base: String, token: String, backend: String = "opencode"): List<SlashCommand> {
        exec(base) { b -> authed("$b/api/commands?backend=$backend", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("commands: http ${res.code}")
            return parseCommands(res.body!!.string())
        }
    }

    fun postBackendCommand(base: String, token: String, backend: String, command: String, argument: String = ""): String {
        val payload = JSONObject().put("backend", backend).put("command", command).put("argument", argument)
        val req = exec(base) { b ->
            authed("$b/api/backend-command", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
        req.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("error", "http ${res.code}") } catch (_: Exception) { "http ${res.code}" }
                throw IOException2("command: $msg")
            }
            return JSONObject(body).optString("text", "")
        }
    }

    fun postChat(base: String, token: String, chatId: String, sessionID: String?, title: String?, text: String, model: String?, attachments: List<ChatAttachment> = emptyList(), turnId: String? = null, backend: String? = null, effort: String? = null, cwd: String? = null, nativeCommandName: String? = null, steer: Boolean = false, steerRequired: Boolean = false): ChatAccept {
        val payload = JSONObject()
            .put("chatId", chatId)
            .put("text", text)
        if (!backend.isNullOrBlank()) payload.put("backend", backend)
        if (!effort.isNullOrBlank()) payload.put("effort", effort)
        if (!cwd.isNullOrBlank()) payload.put("cwd", cwd)
        if (!nativeCommandName.isNullOrBlank()) payload.put("nativeCommandName", nativeCommandName)
        if (steer) payload.put("steer", true)
        if (steerRequired) payload.put("steerRequired", true)
        if (!sessionID.isNullOrBlank()) payload.put("sessionID", sessionID)
        if (!title.isNullOrBlank()) payload.put("title", title)
        if (!model.isNullOrBlank()) payload.put("model", model)
        if (!turnId.isNullOrBlank()) payload.put("turnId", turnId)
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.take(3).forEach { a ->
                arr.put(JSONObject()
                    .put("name", a.name)
                    .put("mime", a.mime)
                    .put("dataBase64", a.base64))
            }
            payload.put("files", arr)
        }
        val req = exec(base) { b ->
            authed("$b/api/chat", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
        req.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("error", "http ${res.code}") } catch (_: Exception) { "http ${res.code}" }
                throw IOException2("chat: $msg")
            }
            val o = JSONObject(body)
            // Fast-accept turn starter; the outcome arrives over /api/stream.
            // turnId echoes the client's (or the server-minted one) so overlapping
            // send-through dones settle their own placeholder (send-once dedupe).
            // sessionID is the app-owned opencode session: blank-chat sends
            // adopt the created one; 404 `no session` means it died server-side.
            if (o.optBoolean("accepted", false)) return ChatAccept(true, null, null, o.optString("turnId", "").takeUnless { it.isBlank() }, o.optString("sessionID", "").takeUnless { it.isBlank() }, o.optBoolean("steered", false))
            // Legacy sync reply fallback (older server).
            val replyModel = if (o.isNull("model")) null else o.optString("model", "").takeUnless { it.isBlank() }
            return ChatAccept(false, o.optString("text", ""), replyModel, o.optString("turnId", "").takeUnless { it.isBlank() }, o.optString("sessionID", "").takeUnless { it.isBlank() }, false)
        }
    }

    /** Turn-starter acceptance. accepted=true → the outcome arrives over
     *  /api/stream (`done` with the same turnId); accepted=false carries a legacy sync reply.
     *  sessionID is the opencode session that took the turn (newly created
     *  when the chat had none) — the caller adopts it as the chat's remoteId. */
    data class ChatAccept(val accepted: Boolean, val text: String?, val model: String?, val turnId: String? = null, val sessionID: String? = null, val steered: Boolean = false)

    /** "Send now" for a waiting message: fold it into the running reply. True = folded. */
    fun postSteer(base: String, token: String, chatId: String, turnId: String, backend: String? = null): Boolean {
        val payload = JSONObject().put("chatId", chatId).put("turnId", turnId)
        if (!backend.isNullOrBlank()) payload.put("backend", backend)
        exec(base) { b ->
            authed("$b/api/steer", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) throw IOException2("steer: http ${res.code}")
            return try { JSONObject(body).optBoolean("steered", false) } catch (_: Exception) { false }
        }
    }

    /** Discard a backend-held queued turn without interrupting it if it has
     *  already started. True only when the backend canceled the queue entry. */
    fun postCancelQueued(base: String, token: String, chatId: String, turnId: String, backend: String? = null): Boolean {
        val payload = JSONObject().put("chatId", chatId).put("turnId", turnId)
        if (!backend.isNullOrBlank()) payload.put("backend", backend)
        exec(base) { b ->
            authed("$b/api/queued-cancel", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) throw IOException2("discard queued: http ${res.code}")
            return try { JSONObject(body).optBoolean("cancelled", false) } catch (_: Exception) { false }
        }
    }

    /** One saywhat event from the Mac's /api/listen. */
    data class ListenEvent(val final: Boolean, val t: Double, val text: String)
    /** [code] 200 ok, 503 saywhat not installed on the Mac, 502 saywhat died; events since the last post. */
    data class ListenReply(val code: Int, val events: List<ListenEvent>, val done: Boolean)

    // Short connect timeout: dictation falls back to the phone's recogniser instead of waiting.
    private val listenClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    }

    /** Posts [pcm] (16 kHz mono s16le) to the Mac's saywhat session [id]; [stop] ends it. Throws on network failure. */
    fun postListen(base: String, token: String, id: String, pcm: ByteArray, stop: Boolean = false): ListenReply {
        val path = if (stop) "/api/listen/stop" else "/api/listen"
        val req = authed("${base.trimEnd('/')}$path?id=$id", token)
            .post(pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        listenClient.newCall(req).execute().use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            return ListenReply(res.code, parseListenEvents(o.optJSONArray("events")), o.optBoolean("done", false))
        }
    }

    class IOException2(msg: String) : java.io.IOException(msg)

    private fun postSessionAction(base: String, token: String, path: String, sessionID: String, extras: Map<String, String> = emptyMap()): String {
        val payload = JSONObject().put("sessionID", sessionID)
        for ((k, v) in extras) payload.put(k, v)
        exec(base) { b ->
            authed(b + path, token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("error", "http ${res.code}") } catch (_: Exception) { "http ${res.code}" }
                throw IOException2("$path: $msg")
            }
            val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            return o.optString("message", "")
        }
    }

    /** Interrupt the running turn. Returns the server message (if any).
     *  Backend/chatId travel for binding validation; opencode semantics
     *  (whole-session interrupt) are unchanged. */
    fun postStop(base: String, token: String, sessionID: String, backend: String? = null, chatId: String? = null): String {
        val extras = mutableMapOf<String, String>()
        if (!backend.isNullOrBlank()) extras["backend"] = backend
        if (!chatId.isNullOrBlank()) extras["chatId"] = chatId
        return postSessionAction(base, token, "/api/stop", sessionID, extras)
    }

    /** Push a manual rename into the bound backend session. Blank titles never sent. */
    fun postTitleSet(base: String, token: String, sessionID: String, title: String, backend: String = "opencode", chatId: String? = null) {
        val payload = JSONObject().put("sessionID", sessionID).put("title", title).put("backend", backend)
        if (!chatId.isNullOrBlank()) payload.put("chatId", chatId)
        exec(base) { b ->
            authed("$b/api/title", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            if (!res.isSuccessful) throw IOException2("title: http ${res.code}")
        }
    }

    /** Session goal (loop teeth): arm, pause, resume, remove, read.
     *  loop=true opts into server continuations; false stays advisory.
     *  Null when the chat has no goal. Throws on transport failure. */
    data class GoalInfo(val chatId: String, val objective: String, val status: String, val stuck: Int, val continuations: Int, val loop: Boolean, val events: List<GoalEvent> = emptyList(), val maxTurns: Int = 1)
    data class GoalEvent(val at: Long, val kind: String, val text: String)

    private fun parseGoal(o: JSONObject): GoalInfo {
        val events = mutableListOf<GoalEvent>()
        val arr = o.optJSONArray("events")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                events += GoalEvent(e.optLong("at", 0L), e.optString("kind", ""), e.optString("text", ""))
            }
        }
        return GoalInfo(
            chatId = o.optString("chatId", ""),
            objective = o.optString("objective", ""),
            status = o.optString("status", ""),
            stuck = o.optInt("stuck", 0),
            continuations = o.optInt("continuations", 0),
            loop = o.optBoolean("loop", false),
            events = events,
        )
    }

    fun goalGet(base: String, token: String, chatId: String): GoalInfo? {
        exec(base) { b -> authed("$b/api/goals", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("goals: http ${res.code}")
            val arr = try { JSONObject(res.body!!.string()).optJSONArray("goals") } catch (_: Exception) { null }
                ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("chatId", "") == chatId) return parseGoal(o)
            }
            return null
        }
    }

    fun goalArm(base: String, token: String, chatId: String, objective: String, loop: Boolean): GoalInfo? {
        val payload = JSONObject().put("chatId", chatId).put("objective", objective).put("loop", loop)
        exec(base) { b ->
            authed("$b/api/goals/arm", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) throw IOException2("goals/arm: http ${res.code}")
            val g = try { JSONObject(body).optJSONObject("goal") } catch (_: Exception) { null }
            return g?.let { parseGoal(it) }
        }
    }

    fun goalControl(base: String, token: String, action: String, chatId: String): GoalInfo? {
        val payload = JSONObject().put("chatId", chatId)
        exec(base) { b ->
            authed("$b/api/goals/$action", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) throw IOException2("goals/$action: http ${res.code}")
            val g = try { JSONObject(body).optJSONObject("goal") } catch (_: Exception) { null }
            return g?.let { parseGoal(it) }
        }
    }

    data class ProgressStep(val kind: String, val label: String, val detail: String, val at: Long)    data class ProgressReply(val status: String, val steps: List<ProgressStep>)

    /** Summarize the session into compacted context, same session id.
     *  Returns (receipt line, full summary): the summary keeps its line
     *  breaks for the center-stage receipt row, blank when the read-back
     *  found nothing. */
    fun postCompact(base: String, token: String, sessionID: String, backend: String = "opencode", chatId: String = ""): Pair<String, String> {
        val payload = JSONObject().put("sessionID", sessionID).put("backend", backend)
        if (chatId.isNotBlank()) payload.put("chatId", chatId)
        exec(base) { b ->
            authed("$b/api/compact", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("error", "http ${res.code}") } catch (_: Exception) { "http ${res.code}" }
                throw IOException2("/api/compact: $msg")
            }
            val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            return o.optString("message", "") to o.optString("summary", "")
        }
    }

    /** Kill the session for good (chat delete + import-row delete).
     *  Backend-routed: opencode keeps interrupt+remove, codex really
     *  deletes the thread now, others 501 explicitly. */
    fun deleteSession(base: String, token: String, sessionID: String, backend: String? = null, chatId: String? = null): String {
        val extras = mutableMapOf<String, String>()
        if (!backend.isNullOrBlank()) extras["backend"] = backend
        if (!chatId.isNullOrBlank()) extras["chatId"] = chatId
        return postSessionAction(base, token, "/api/session-delete", sessionID, extras)
    }

    /** Delete a server-only session with no local chat (import-list delete).
     *  Throws on failure — the row stays (tombstone lifts honestly). */
    fun deleteRemote(base: String, token: String, sessionID: String, backend: String? = null) {
        val payload = JSONObject().put("sessionID", sessionID)
        if (!backend.isNullOrBlank()) payload.put("backend", backend)
        exec(base) { b ->
            authed("$b/api/session-delete", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.use { res ->
            if (!res.isSuccessful) throw IOException2("session-delete: http ${res.code}")
        }
    }

    data class RemoteSession(val id: String, val title: String, val updatedAt: Long, val messageCount: Int = 0, val backend: String = "opencode")
    data class HistoryMsg(val role: String, val text: String, val ts: Long)

    /** Readable transcript for import backfill (GET /api/history). Throws
     *  on failure — callers open the thread empty, as before. */
    fun fetchHistory(base: String, token: String, sessionID: String, backend: String = "opencode"): List<HistoryMsg> {
        exec(base) { b -> authed("$b/api/history?sessionID=${android.net.Uri.encode(sessionID)}&backend=${android.net.Uri.encode(backend)}", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("history: http ${res.code}")
            val arr: JSONArray = JSONObject(res.body!!.string()).optJSONArray("messages") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "")
                if (text.isBlank()) null
                else HistoryMsg(
                    if (o.optString("role", "") == "user") "user" else "assistant",
                    text, o.optLong("ts", 0L))
            }
        }
    }

    data class TurnResult(val settled: Boolean, val text: String = "", val error: String = "", val messageID: String = "", val running: Boolean = false, val backend: String = "", val code: String = "")

    /** Exact-turnId lookup in the server's retained result registry
     *  (GET /api/turn). settled=true carries the completion (deliver via
     *  the normal done path; messageID dedupe applies). running=true means
     *  accepted-but-unsettled: never resend, keep observing. Both false =
     *  unknown (never-seen, pruned, or wiped) — resend only with warning,
     *  never present as failure. Throws on transport failure — callers keep
     *  the unknown state. */
    fun fetchTurn(base: String, token: String, chatId: String, turnId: String, backend: String? = null): TurnResult {        exec(base) { b ->
            var url = "$b/api/turn?chatId=${android.net.Uri.encode(chatId)}&turnId=${android.net.Uri.encode(turnId)}"
            if (!backend.isNullOrBlank()) url += "&backend=${android.net.Uri.encode(backend)}"
            authed(url, token).get().build()
        }.use { res ->
            if (!res.isSuccessful) throw IOException2("turn: http ${res.code}")
            val o = try { JSONObject(res.body!!.string()) } catch (_: Exception) { JSONObject() }
            when (o.optString("status", "")) {
                "settled" -> return TurnResult(true, o.optString("text", ""), o.optString("error", ""), o.optString("messageID", ""), backend = o.optString("backend", backend ?: ""), code = o.optString("code", ""))
                "running" -> return TurnResult(false, running = true, backend = o.optString("backend", backend ?: ""))
                else -> return TurnResult(false, backend = o.optString("backend", backend ?: ""))
            }
        }
    }

    /** Server-side reconcile of a chat's unsettled turns (GET /api/sync, every backend). `settled` and
     *  `lost` name turns the server just closed out (read each with fetchTurn); `running` are turns
     *  genuinely still alive. Throws on transport failure; callers treat that as "no news". */
    data class SyncResult(val settled: List<String>, val lost: List<String>, val running: List<String>, val waiting: List<String>)
    fun syncChat(base: String, token: String, chatId: String, sessionID: String, backend: String? = null): SyncResult {
        exec(base) { b ->
            var url = "$b/api/sync?chatId=${android.net.Uri.encode(chatId)}&sessionID=${android.net.Uri.encode(sessionID)}"
            if (!backend.isNullOrBlank()) url += "&backend=${android.net.Uri.encode(backend)}"
            authed(url, token).get().build()
        }.use { res ->
            if (!res.isSuccessful) throw IOException2("sync: http ${res.code}")
            val o = try { JSONObject(res.body!!.string()) } catch (_: Exception) { JSONObject() }
            fun ids(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            return SyncResult(ids("settled"), ids("lost"), ids("running"), ids("waiting"))
        }
    }

    /** Live session state (GET /api/progress): true while the opencode
     *  session is still working, false on idle/done AND on authoritative
     *  death (404 no session — e.g. killed by an opencode restart, the
     *  exact orphan scenario). Null only on transport/503 unknown, which
     *  must never clear the in-flight set. */
    fun fetchProgressWorking(base: String, token: String, chatId: String, sessionID: String, backend: String? = null): Boolean? {
        return try {
            exec(base) { b ->
                var url = "$b/api/progress?chatId=${android.net.Uri.encode(chatId)}&sessionID=${android.net.Uri.encode(sessionID)}"
                if (!backend.isNullOrBlank()) url += "&backend=${android.net.Uri.encode(backend)}"
                authed(url, token).get().build()
            }.use { res ->
                val body = try { res.body!!.string() } catch (_: Exception) { "" }
                val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                progressWorkingOf(res.isSuccessful, res.code, o.optString("status", ""), o.optString("error", ""))
            }
        } catch (_: Exception) { null }
    }

    /** Server-side opencode sessions (GET /api/sessions). The app hides ids
     *  it already owns via its local remoteIds. Throws on failure —
     *  callers keep the spinner honest and leave local state untouched.
     *  Backend-scoped in M9: import lists one backend at a time (opencode
     *  until import UI filters per backend). */
    fun fetchSessions(base: String, token: String, backend: String = "opencode"): List<RemoteSession> {
        exec(base) { b -> authed("$b/api/sessions?backend=$backend", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("sessions: http ${res.code}")
            val arr: JSONArray = JSONObject(res.body!!.string()).optJSONArray("sessions") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isBlank()) null
                else RemoteSession(id, o.optString("title", ""), o.optLong("updatedAt", 0L), o.optInt("messageCount", 0), backend)
            }
        }
    }

    /** One-shot upgrade migration: legacy chatId→session bindings from the
     *  server (deprecated shim, {} when gone). The app adopts them as local
     *  remoteIds once, then never asks again. */
    fun fetchBindings(base: String, token: String): Map<String, String> {
        exec(base) { b -> authed("$b/api/bindings", token).get().build() }.use { res ->
            if (!res.isSuccessful) return emptyMap()
            val o = try { JSONObject(res.body!!.string()) } catch (_: Exception) { return emptyMap() }
            val b = o.optJSONObject("bindings") ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            val keys = b.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = b.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            return out
        }
    }

    /** Fork the opencode session (messageID optional) → the fork's session
     *  id, adopted as the twin chat's remoteId. Throws on failure — the
     *  caller keeps the local copy and notes the agent starts fresh. */
    fun postFork(base: String, token: String, sessionID: String, messageID: String? = null, backend: String = "opencode", chatId: String = ""): String {
        if (backend == "codex" || backend == "claude") {
            val payload = JSONObject().put("backend", backend).put("chatId", chatId).put("sessionID", sessionID)
            exec(base) { b ->
                authed("$b/api/backend-fork", token).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            }.use { res ->
                val body = res.body!!.string()
                if (!res.isSuccessful) throw IOException2("fork: ${JSONObject(body).optString("error", "http ${res.code}")}")
                return JSONObject(body).optString("sessionID", "")
            }
        }
        val payload = JSONObject().put("sessionID", sessionID)
        if (!messageID.isNullOrBlank()) payload.put("messageID", messageID)
        exec(base) { b ->
            authed("$b/api/fork", token).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        }.use { res ->
            val body = res.body!!.string()
            if (!res.isSuccessful) throw IOException2("fork: ${JSONObject(body).optString("error", "http ${res.code}")}")
            return JSONObject(body).optString("sessionID", "")
        }
    }

    // ---- Live stream (SSE over plain HTTP GET, no new deps) ----

    data class StreamEvent(val event: String, val data: String)

    /** Infinite-read client: heartbeats every 20s keep the socket alive. */
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeStreamCall: okhttp3.Call? = null
    /** Device wake stream (GET /api/wake) for LiveLinkService: `done` and `inbox` events, `: ping` every 90s.
     *  The read deadline is per read, so a dead socket fails within [stallSec]. Caller closes the response. */
    fun openWake(base: String, token: String, stallSec: Long = 240): Response {
        val wakeClient = streamClient.newBuilder().readTimeout(stallSec, TimeUnit.SECONDS).build()
        val primary = base.trimEnd('/')
        val order = listOf(primary) + if (LAN_FALLBACK_ENABLED) listOf(LAN_BASE) else emptyList()
        var lastErr: IOException? = null
        for (b in order.distinct()) {
            try {
                val res = wakeClient.newCall(authed("$b/api/wake?v=${BuildConfig.VERSION_CODE}", token).get().build()).execute()
                if (!res.isSuccessful) { res.close(); throw IOException2("wake: http ${res.code}") }
                return res
            } catch (e: IOException) {
                if (!isConnectFailure(e)) throw e
                lastErr = e
            }
        }
        throw lastErr ?: IOException("network error")
    }


    /** Cancel the in-flight stream read (thread may be blocked in read). */
    fun cancelStream() {
        try { activeStreamCall?.cancel() } catch (_: Exception) {}
        activeStreamCall = null
    }

    /** Blocking SSE read of GET /api/stream?chatId=&sessionID=. Runs on the caller's
     *  background thread; returns when the stream ends, errors, or
     *  isAlive() goes false. Throws on connect failure / HTTP error; the
     *  caller reconnects (settled dones replay, client dedupes). Heartbeat
     *  comments (`: ping`) reset the 75s stall deadline. sessionID narrows the
     *  server's event filter (it also learns it from each accepted send). */
    fun streamChat(
        base: String,
        token: String,
        chatId: String,
        sessionID: String,
        isAlive: () -> Boolean,
        onEvent: (StreamEvent) -> Unit,
        since: Long = 0
    ) {
        val enc = try { java.net.URLEncoder.encode(chatId, "UTF-8") } catch (_: Exception) { chatId }
        val senc = try { java.net.URLEncoder.encode(sessionID, "UTF-8") } catch (_: Exception) { sessionID }
        // Replay floor: the newest settle time this chat has already applied.
        // Sent only when known — omitted means the server's recent window,
        // which bounds every reconnect to fresh turns, never full history.
        val sinceQ = if (since > 0) "&since=$since" else ""
        val primary = base.trimEnd('/')
        val lanFirst = LAN_FALLBACK_ENABLED && isLanGood() && !primary.equals(LAN_BASE, true)
        val order = if (lanFirst) listOf(LAN_BASE, primary) else listOf(primary) + if (LAN_FALLBACK_ENABLED) listOf(LAN_BASE) else emptyList()
        var lastErr: IOException? = null
        for (b in order.distinct()) {
            if (!isAlive()) return
            val call = streamClient.newCall(
                authed("$b/api/stream?chatId=$enc&sessionID=$senc$sinceQ", token)
                    .header("Accept", "text/event-stream")
                    .get().build()
            )
            activeStreamCall = call
            try {
                call.execute().use { res ->
                    if (!res.isSuccessful) throw IOException2("stream: http ${res.code}")
                    if (b == LAN_BASE && !lanFirst) reportLanGood()
                    else if (b == primary && lanFirst) lanGoodUntil = 0L
                    val src = res.body!!.source()
                    try { src.timeout().timeout(75, TimeUnit.SECONDS) } catch (_: Exception) {}
                    var event = ""
                    val data = StringBuilder()
                    fun dispatch() {
                        if (event.isNotEmpty() || data.isNotEmpty()) {
                            val ev = event.ifBlank { "message" }
                            val d = data.toString()
                            try { onEvent(StreamEvent(ev, d)) } catch (_: Exception) {}
                        }
                        event = ""
                        data.clear()
                    }
                    while (isAlive()) {
                        val line = try {
                            src.readUtf8Line()
                        } catch (_: Exception) {
                            break
                        } ?: break
                        when {
                            line.startsWith(":") -> {
                                try { src.timeout().timeout(75, TimeUnit.SECONDS) } catch (_: Exception) {}
                            }
                            line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append("\n")
                                data.append(line.substringAfter("data:").trimStart())
                            }
                            line.isBlank() -> dispatch()
                        }
                    }
                    try { src.timeout().clearDeadline() } catch (_: Exception) {}
                }
                return
            } catch (e: IOException) {
                activeStreamCall = null
                if (!isAlive()) return
                // Connect-level failure → try the next base; anything else
                // (HTTP error, mid-stream stall) ends the attempt.
                var t: Throwable? = e
                var connectish = false
                while (t != null) {
                    if (t is UnknownHostException || t is SocketTimeoutException || t is ConnectException) {
                        connectish = true
                        break
                    }
                    t = t.cause
                }
                if (e is IOException2) throw e
                if (!connectish) return
                lastErr = e
            } finally {
                if (activeStreamCall === call) activeStreamCall = null
            }
        }
        throw lastErr ?: IOException("stream failed")
    }

    // ---- Delivery inbox ----

    /** `kind` "event" = something needs the person (hand-off, approval, failed run); `priority` "high" pings louder. */
    data class InboxItem(
        val job: String, val title: String, val body: String, val at: Long, val chat: String = "",
        val kind: String = "", val priority: String = "",
    )

    /** One background job event from the server's per-chat log (seq 0 = an older server without the log). */
    data class BackgroundEvent(val seq: Long, val status: String, val label: String, val detail: String, val full: String, val elapsedMs: Long)
    data class BackgroundLog(val events: List<BackgroundEvent>, val seq: Long, val running: Int)

    fun parseBackground(o: JSONObject): BackgroundEvent? {
        val status = o.optString("status", "")
        if (status.isBlank()) return null
        return BackgroundEvent(o.optLong("seq", 0L), status, o.optString("label", ""), o.optString("detail", ""),
            o.optString("full", ""), if (o.has("elapsedMs")) o.optLong("elapsedMs", -1L) else -1L)
    }

    /** Background events after a seq (GET /api/background). Throws. */
    fun fetchBackground(base: String, token: String, chatId: String, after: Long): BackgroundLog {
        val enc = java.net.URLEncoder.encode(chatId, "UTF-8")
        exec(base) { b -> authed("$b/api/background?chatId=$enc&after=$after", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("background: http ${res.code}")
            val o = JSONObject(res.body!!.string())
            val arr = o.optJSONArray("events") ?: JSONArray()
            val events = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseBackground(it) } }
            return BackgroundLog(events, o.optLong("seq", 0L), o.optInt("running", 0))
        }
    }

    /** Schedule facts per thread for the chat list's third line (GET /api/schedule-rows). Throws. */
    fun fetchScheduleRows(base: String, token: String): Map<String, ScheduleLine.Facts> {
        exec(base) { b -> authed("$b/api/schedule-rows", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("schedule-rows: http ${res.code}")
            val arr: JSONArray = JSONObject(res.body!!.string()).optJSONArray("rows") ?: JSONArray()
            val out = HashMap<String, ScheduleLine.Facts>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val chat = o.optString("chat", "")
                if (chat.isBlank()) continue
                out[chat] = ScheduleLine.Facts(
                    cron = o.optString("cron", "").ifBlank { null },
                    everyMin = if (o.has("everyMin")) o.optInt("everyMin") else null,
                    at = o.optString("at", "").ifBlank { null },
                    next = if (o.has("next")) o.optLong("next") else null,
                    retired = o.optBoolean("retired", false),
                )
            }
            return out
        }
    }

    /** Pull inbox entries (GET /api/inbox). job=null → all jobs. Throws. */
    fun fetchInbox(base: String, token: String, job: String?, since: Long): List<InboxItem> {
        val enc = if (!job.isNullOrBlank()) {
            "&job=" + try { java.net.URLEncoder.encode(job, "UTF-8") } catch (_: Exception) { job }
        } else ""
        exec(base) { b -> authed("$b/api/inbox?since=$since$enc", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("inbox: http ${res.code}")
            val arr: JSONArray = JSONObject(res.body!!.string()).optJSONArray("entries") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                InboxItem(
                    o.optString("job", ""),
                    o.optString("title", ""),
                    o.optString("body", ""),
                    o.optLong("at", 0L),
                    o.optString("chat", ""),
                    o.optString("kind", ""),
                    o.optString("priority", ""),
                )
            }.filter { it.job.isNotBlank() && it.at > 0 }
        }
    }

    /** Per-job entry counts + latest stamps (GET /api/unread). Throws. */
    fun fetchUnread(base: String, token: String): Map<String, Int> {
        exec(base) { b -> authed("$b/api/unread", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("unread: http ${res.code}")
            val o = JSONObject(res.body!!.string()).optJSONObject("counts") ?: JSONObject()
            val out = LinkedHashMap<String, Int>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optInt(k, 0)
            }
            return out
        }
    }

    data class ChatDefaults(
        val defaultModel: String,
        val systemPrompt: String,
        val serverDefault: String = "",
        val defaultBackend: String = "opencode",
        val defaultModels: Map<String, String> = emptyMap(),
        /** Auto-compact ceiling in tokens; 0 = each backend's own default. */
        val compactAt: Int = 0,
    )

    /** Device chat defaults persisted on the server. Throws on failure. */
    fun fetchDefaults(base: String, token: String): ChatDefaults {
        exec(base) { b -> authed("$b/api/defaults", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("defaults: http ${res.code}")
            return parseDefaults(res.body!!.string())
        }
    }

    /** One chat's context size, token totals and cost (server /api/context). */
    data class ContextInfo(
        val backend: String,
        val model: String,
        val used: Long,
        val window: Long,
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val totalTokens: Long,
        val costUsd: Double?,
        val compactAt: Int,
    )

    fun fetchContext(base: String, token: String, chatId: String, model: String, totals: Boolean): ContextInfo {
        val chat = java.net.URLEncoder.encode(chatId, "UTF-8")
        val mod = java.net.URLEncoder.encode(model, "UTF-8")
        exec(base) { b -> authed("$b/api/context?chatId=$chat&model=$mod${if (totals) "&totals=1" else ""}", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("context: http ${res.code}")
            return parseContext(res.body!!.string())
        }
    }

    /** Provider-side session title for syncing chat names. Blank when unset. */
    fun fetchTitle(base: String, token: String, sessionID: String, backend: String = "opencode", chatId: String? = null): String {
        val enc = try { java.net.URLEncoder.encode(sessionID, "UTF-8") } catch (_: Exception) { sessionID }
        val backendEnc = try { java.net.URLEncoder.encode(backend, "UTF-8") } catch (_: Exception) { backend }
        val chatEnc = try { java.net.URLEncoder.encode(chatId ?: "", "UTF-8") } catch (_: Exception) { chatId ?: "" }
        exec(base) { b -> authed("$b/api/title?sessionID=$enc&backend=$backendEnc&chatId=$chatEnc", token).get().build() }.use { res ->
            if (!res.isSuccessful) return ""
            return JSONObject(res.body!!.string()).optString("title", "")
        }
    }

    /** Save device chat defaults. Null args mean "leave unchanged".
     *  Returns the stored values. Throws on failure. */
    fun postDefaults(
        base: String,
        token: String,
        defaultModel: String?,
        systemPrompt: String?,
        defaultBackend: String? = null,
        defaultModels: Map<String, String>? = null,
        compactAt: Int? = null,
    ): ChatDefaults {
        val payload = JSONObject()
        if (compactAt != null) payload.put("compactAt", compactAt)
        if (defaultModel != null) payload.put("defaultModel", defaultModel)
        if (systemPrompt != null) payload.put("systemPrompt", systemPrompt)
        if (defaultBackend != null) payload.put("defaultBackend", defaultBackend)
        if (defaultModels != null) {
            val mo = JSONObject()
            for ((k, v) in defaultModels) mo.put(k, v)
            payload.put("defaultModels", mo)
        }
        val req = exec(base) { b ->
            authed("$b/api/defaults", token)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
        req.use { res ->
            val body = try { res.body!!.string() } catch (_: Exception) { "" }
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("error", "http ${res.code}") } catch (_: Exception) { "http ${res.code}" }
                throw IOException2("defaults: $msg")
            }
            val o = JSONObject(body)
            return parseDefaults(o.toString())
        }
    }

    /** Registered backends + capabilities. Throws on failure. */
    fun fetchBackends(base: String, token: String): BackendCatalog {
        exec(base) { b -> authed("$b/api/backends", token).get().build() }.use { res ->
            if (!res.isSuccessful) throw IOException2("backends: http ${res.code}")
            return parseBackends(res.body!!.string())
        }
    }
}

/** Pure liveness mapping for GET /api/progress (unit-tested): working →
 *  true; idle/done → false; authoritative death (404 or "no session") →
 *  false — nothing runs there, so orphans may settle. Anything else
 *  (503, unparseable, unknown shapes) → null: keep observing. */
internal fun progressWorkingOf(ok: Boolean, code: Int, status: String, error: String): Boolean? {
    if (!ok) {
        if (code == 404 || error.contains("no session", ignoreCase = true)) return false
        return null
    }
    return when (status) {
        "working" -> true
        "idle", "done" -> false
        else -> null
    }
}

internal fun parseContext(body: String): OpencodeApi.ContextInfo {
    val o = JSONObject(body)
    fun num(k: String): Long = if (o.isNull(k)) 0L else o.optLong(k, 0L)
    return OpencodeApi.ContextInfo(
        backend = if (o.isNull("backend")) "" else o.optString("backend", ""),
        model = if (o.isNull("model")) "" else o.optString("model", ""),
        used = num("used"), window = num("window"),
        input = num("input"), output = num("output"),
        cacheRead = num("cacheRead"), cacheWrite = num("cacheWrite"),
        totalTokens = num("totalTokens"),
        costUsd = if (o.isNull("costUsd")) null else o.optDouble("costUsd"),
        compactAt = o.optInt("compactAt", 0),
    )
}
