package com.terminus.rec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable /wait backlog (chatId → ordered [{text, files[]}]). The in-memory
 *  waitQueue dies with the process, but the ⏳ marker row in the transcript
 *  survives — without this, a recovered row looks sent while never reaching
 *  OpenCode. Entries are written on enqueue, rewritten on every drain
 *  put-back, cleared on drain; startup rehydrates into waitQueue and the
 *  normal reconciler drains when idle. File paths stay valid: attachments
 *  live in filesDir/media, never tmp. */
class WaitStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("waitqueue", Context.MODE_PRIVATE)

    /** turnId keeps the row's turn id across a restart (blank in entries saved before it existed). */
    data class Entry(val text: String, val files: List<String>, val turnId: String = "")

    fun all(): Map<String, List<Entry>> {
        val out = mutableMapOf<String, List<Entry>>()
        for ((k, v) in sp.all) {
            val arr = try { JSONArray(v as? String ?: "[]") } catch (_: Exception) { JSONArray() }
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text", "")
                if (text.isBlank()) continue
                val fa = o.optJSONArray("files") ?: JSONArray()
                val files = mutableListOf<String>()
                for (j in 0 until fa.length()) {
                    val p = fa.optString(j, "")
                    if (p.isNotBlank()) files.add(p)
                }
                list.add(Entry(text, files, o.optString("turnId", "")))
            }
            if (list.isNotEmpty()) out[k] = list
        }
        return out
    }

    fun put(id: String, entries: List<Entry>) {
        if (entries.isEmpty()) { sp.edit().remove(id).apply(); return }
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("text", e.text)
                .put("files", JSONArray(e.files.take(3)))
                .put("turnId", e.turnId))
        }
        sp.edit().putString(id, arr.toString()).apply()
    }

    fun clear(id: String) = sp.edit().remove(id).apply()
}
