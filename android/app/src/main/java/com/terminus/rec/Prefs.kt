package com.terminus.rec

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("terminus", Context.MODE_PRIVATE)
    fun serverUrl(): String = sp.getString("serverUrl", "http://10.0.2.2:9009") ?: "http://10.0.2.2:9009"
    fun token(): String = sp.getString("token", "") ?: ""
    fun setServerUrl(v: String) = sp.edit().putString("serverUrl", v).apply()
    fun setToken(v: String) = sp.edit().putString("token", v).apply()
    /** Spoken send-command preference. */
    fun voiceSendEnabled(): Boolean = sp.getBoolean("voiceSendEnabled", true)
    fun setVoiceSendEnabled(v: Boolean) = sp.edit().putBoolean("voiceSendEnabled", v).apply()
    /** Link chips: the phone looks up a bare link's page title. Off = plain links, nothing fetched. */
    fun linkPreviews(): Boolean = sp.getBoolean("linkPreviews", true)
    fun setLinkPreviews(v: Boolean) = sp.edit().putBoolean("linkPreviews", v).apply()
    fun hotkeysEnabled(): Boolean = sp.getBoolean("hotkeysEnabled", true)
    fun setHotkeysEnabled(v: Boolean) = sp.edit().putBoolean("hotkeysEnabled", v).apply()
    fun voiceSendPhrase(): String = sp.getString("voiceSendPhrase", "send")?.trim().orEmpty().ifBlank { "send" }
    fun setVoiceSendPhrase(v: String) = sp.edit().putString("voiceSendPhrase", v.trim().ifBlank { "send" }).apply()
    /** Device default model ("provider/model", blank = server default).
     *  Mirrors the server /api/defaults value for instant UI. */
    fun defaultModel(): String = defaultModel(defaultBackend())
    fun setDefaultModel(v: String) = setDefaultModel(defaultBackend(), v)
    /** Per-backend default model (blank = that backend's server default).
     *  The legacy single "defaultModel" key is adopted once into the
     *  opencode slot, then removed — never read again. */
    fun defaultModel(backend: String): String {
        val b = cannonBackend(backend)
        val key = modelKeyFor(b)
        if (!sp.contains(key) && b == BACKEND_OPENCODE && sp.contains("defaultModel")) {
            val legacy = sp.getString("defaultModel", "") ?: ""
            sp.edit().putString(key, legacy).remove("defaultModel").apply()
            return legacy
        }
        return sp.getString(key, "") ?: ""
    }
    fun setDefaultModel(backend: String, model: String) {
        sp.edit().putString(modelKeyFor(cannonBackend(backend)), model).apply()
    }
    /** Global default backend for new chats. Unknown stored values fall
     *  back to opencode rather than crashing or stranding the UI. */
    fun defaultBackend(): String = cannonBackend(sp.getString("defaultBackend", BACKEND_OPENCODE))
    fun setDefaultBackend(v: String) =
        sp.edit().putString("defaultBackend", cannonBackend(v)).apply()
    /** Last backend before the current one (for instant toggle). Unknown
     *  or same-as-current values are ignored by the toggle logic. */
    fun prevBackend(): String = sp.getString("prevBackend", "") ?: ""
    fun setPrevBackend(v: String) =
        sp.edit().putString("prevBackend", cannonBackend(v)).apply()

    companion object {
        const val BACKEND_OPENCODE = "opencode"
        const val BACKEND_CODEX = "codex"
        const val BACKEND_CLAUDE = "claude"
        const val BACKEND_AGY = "agy"
        /** Closed backend set (mirrors server BackendId). Pure, unit-tested. */
        fun validBackend(id: String): Boolean =
            id == BACKEND_OPENCODE || id == BACKEND_CODEX || id == BACKEND_CLAUDE || id == BACKEND_AGY
        /** Canonical form: unknown ids become opencode, never crash. */
        fun cannonBackend(id: String?): String =
            if (id != null && validBackend(id)) id else BACKEND_OPENCODE
        /** Fixed per-backend pref key (never a JSON map). Pure, unit-tested. */
        fun modelKeyFor(backend: String): String = "defaultModel.$backend"
        /** User-facing backend label. Pure, unit-tested. */
        fun backendLabel(backend: String): String = when (cannonBackend(backend)) {
            BACKEND_CODEX -> "Codex"
            BACKEND_CLAUDE -> "Claude"
            BACKEND_AGY -> "Agy"
            else -> "OpenCode"
        }
    }
    /** Chat notification tone (content:// uri string, blank = system default).
     *  Applied to the "Chat" channel on save (channel re-created, sound is
     *  immutable after creation). */
    fun chatSound(): String = sp.getString("chatSound", "") ?: ""
    fun setChatSound(v: String) = sp.edit().putString("chatSound", v).apply()
    /** Open chat thread restored on cold start (process death safe).
     *  Set on openThread, cleared on closeThread (`<` / `/sessions`). */
    fun openChatId(): String? = sp.getString("openChatId", null)
    fun setOpenChatId(v: String) = sp.edit().putString("openChatId", v).apply()
    fun clearOpenChatId() = sp.edit().remove("openChatId").apply()
    /** Quiet hours kill pings (bell dot stays). Defaults 22:00–07:00. */
    fun quietStart(): Int = sp.getInt("quietStart", 22)
    fun quietEnd(): Int = sp.getInt("quietEnd", 7)
    fun setQuiet(startH: Int, endH: Int) =
        sp.edit().putInt("quietStart", startH.coerceIn(0, 23)).putInt("quietEnd", endH.coerceIn(0, 23)).apply()
    /** Inbox backstop cursor: newest delivery `at` already merged locally. */
    fun lastInboxSync(): Long = sp.getLong("lastInboxSync", 0L)
    fun setLastInboxSync(v: Long) = sp.edit().putLong("lastInboxSync", v).apply()
    /** Stream done high-water per chat: newest settled `at` applied locally.
     *  Sent as ?since= so reconnects replay only truly-new dones, across
     *  restarts. One key per chat; values only move forward. */
    fun doneHighWater(chatId: String): Long = sp.getLong("doneHighWater.$chatId", 0L)
    fun setDoneHighWater(chatId: String, v: Long) =
        sp.edit().putLong("doneHighWater.$chatId", v).apply()
    /** Session list filter tab: 0 = All, 1 = Pinned, 2 = Unread. */
    fun sessionFilter(): Int = sp.getInt("sessionFilter", 0)
    fun setSessionFilter(v: Int) = sp.edit().putInt("sessionFilter", v.coerceIn(0, 2)).apply()
    /** One-shot upgrade: adopt legacy server chatId→session bindings as
     *  local remoteIds (stateless server keeps no map). */
    fun migratedBindings(): Boolean = sp.getBoolean("migratedBindings", false)
    fun setMigratedBindings() = sp.edit().putBoolean("migratedBindings", true).apply()
    /** Session list sort: newest | activity | name. */
    fun sessionSort(): String = sp.getString("sessionSort", "newest") ?: "newest"
    fun setSessionSort(v: String) =
        sp.edit().putString("sessionSort", if (v in setOf("newest", "activity", "name")) v else "newest").apply()
    /** Appearance: system | light | dark. Applied via AppCompatDelegate. */
    fun appearance(): String = sp.getString("appearance", "system") ?: "system"
    fun setAppearance(v: String) =
        sp.edit().putString("appearance", if (v in setOf("system", "light", "dark")) v else "system").apply()
}
