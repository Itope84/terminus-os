package com.terminus.rec

import android.content.Context

data class ChunkItem(val name: String, val state: String)

class ChunkStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("chunks", Context.MODE_PRIVATE)

    fun all(): List<ChunkItem> {
        val out = mutableListOf<ChunkItem>()
        for ((k, v) in sp.all) out += ChunkItem(k, v as? String ?: STATE_QUEUED)
        return out.sortedBy { it.name }
    }

    fun add(name: String) = sp.edit().putString(name, STATE_QUEUED).apply()
    fun markUploaded(name: String) = sp.edit().putString(name, STATE_UPLOADED).apply()
    fun markRejected(name: String) = sp.edit().putString(name, STATE_REJECTED).apply()
    fun requeueRejected() {
        val e = sp.edit()
        for ((k, v) in sp.all) if (v == STATE_REJECTED) e.putString(k, STATE_QUEUED)
        e.apply()
    }
    fun markMissing(name: String) = sp.edit().remove(name).apply()
    fun forget(name: String) = sp.edit().remove(name).apply()

    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_UPLOADED = "uploaded"
        /** Server refused it for good (too big, bad request). Manual retry re-queues it. */
        const val STATE_REJECTED = "rejected"
    }
}
