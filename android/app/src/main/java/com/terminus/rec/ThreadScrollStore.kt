package com.terminus.rec

import android.content.Context
import org.json.JSONObject

/** Where each chat was left, kept across app restarts (the in-memory map in MainActivity is lost when Android kills
 *  the process). An entry is trusted only while the chat still has the same number of messages: if anything landed
 *  since, the saved offset points at the wrong place and the chat opens at the bottom (or its first unread). */
object ThreadScrollStore {
    private const val MAX = 100
    private val map = object : LinkedHashMap<String, Pair<Int, Int>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?) = size > MAX
    }
    private var sp: android.content.SharedPreferences? = null

    fun init(ctx: Context) = synchronized(map) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("thread_scroll", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(sp!!.getString("m", "{}") ?: "{}")
            for (k in o.keys()) { val e = o.optJSONObject(k) ?: continue; map[k] = e.optInt("y", -1) to e.optInt("n", -1) }
        } catch (_: Exception) {}
    }

    /** The saved css-px offset, or null: unknown, left at the bottom (-1), or the chat changed since. */
    fun get(chatId: String, messageCount: Int): Int? = synchronized(map) {
        val e = map[chatId] ?: return null
        if (e.first < 0 || e.second != messageCount) null else e.first
    }

    fun put(chatId: String, y: Int, messageCount: Int) = synchronized(map) {
        map[chatId] = y to messageCount
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("y", v.first).put("n", v.second))
        sp?.edit()?.putString("m", o.toString())?.apply()
    }

    fun clear() = synchronized(map) { map.clear(); sp?.edit()?.remove("m")?.apply() }
}
