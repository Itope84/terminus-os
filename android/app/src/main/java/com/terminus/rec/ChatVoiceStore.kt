package com.terminus.rec

import android.content.Context

/** Local-only voice captures made from chat. These are never Echo uploads. */
class ChatVoiceStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chat_voice_drafts", Context.MODE_PRIVATE)

    fun all(): List<String> = prefs.getStringSet(KEY_FILES, emptySet())?.toList()?.sorted().orEmpty()

    fun add(name: String) {
        val next = all().toMutableSet().apply { add(name) }
        prefs.edit().putStringSet(KEY_FILES, next).apply()
    }

    fun forget(name: String) {
        val next = all().toMutableSet().apply { remove(name) }
        prefs.edit().putStringSet(KEY_FILES, next).apply()
    }

    companion object { private const val KEY_FILES = "files" }
}
