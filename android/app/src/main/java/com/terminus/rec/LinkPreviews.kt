package com.terminus.rec

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** Page titles the phone has already looked up, kept on disk for two weeks: a link you have seen before is a chip
 *  on the very first paint of a thread (ChatMarkdown.linkMetaLookup) instead of popping in after a lookup. Only
 *  successful titles are stored, in the app's private preferences. */
object LinkMetaStore {
    private const val TTL_MS = 14L * 24 * 3600_000
    private const val MAX = 400
    private val map = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > MAX
    }
    private var sp: android.content.SharedPreferences? = null

    fun init(ctx: Context) = synchronized(map) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("link_meta", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(sp!!.getString("m", "{}") ?: "{}")
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                map[k] = e.optLong("a") to e.optString("j")
            }
        } catch (_: Exception) {}
    }

    /** The stored meta JSON for a link, or null when unknown or older than two weeks. */
    fun get(url: String, now: Long = System.currentTimeMillis()): String? = synchronized(map) {
        val e = map[url] ?: return null
        if (now - e.first > TTL_MS || e.second.isBlank()) { map.remove(url); return null }
        e.second
    }

    fun put(url: String, metaJson: String, now: Long = System.currentTimeMillis()) = synchronized(map) {
        map[url] = now to metaJson
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("a", v.first).put("j", v.second))
        sp?.edit()?.putString("m", o.toString())?.apply()
    }

    fun clear() = synchronized(map) { map.clear(); sp?.edit()?.remove("m")?.apply() }
}

/** Bridge for the thread page's link chips (ChatMarkdown.LINK_CHIP_JS): the page asks for a bare link's title,
 *  the phone fetches it itself (LinkFetcher), and the answer goes back through window.__setMeta. Answers are cached
 *  for the process, so a re-rendered thread paints its chips at once. Off in settings = plain links, no fetching. */
class LinkPreviewBridge(private val prefs: Prefs, private val web: () -> WebView?) {
    companion object {
        private const val MAX_URL = 2000
        private const val CACHE_MAX = 300
        /** url → meta JSON, or "" when the page had no usable title (this session only; hits also go to LinkMetaStore). */
        private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
        }
        private val inflight = HashSet<String>()
        /** At most three lookups at once, so a thread full of links never floods the connection. */
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

        /** Pure: only public web links get a preview; the server's own pages and anything odd stay plain. */
        internal fun worthAsking(url: String, serverUrl: String): Boolean {
            if (url.length > MAX_URL) return false
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            val base = serverUrl.trimEnd('/')
            return base.isBlank() || !url.startsWith(base)
        }
    }

    @JavascriptInterface
    fun want(url: String) {
        if (!prefs.linkPreviews() || !worthAsking(url, prefs.serverUrl())) { deliver(url, ""); return }
        val hit = synchronized(cache) { cache[url] } ?: LinkMetaStore.get(url)
        if (hit != null) { deliver(url, hit); return }
        synchronized(inflight) { if (!inflight.add(url)) return }
        pool.execute {
            val m = LinkFetcher.fetch(url)
            val meta = m?.let { JSONObject().put("url", it.url).put("title", it.title).put("site", it.site).toString() }
            synchronized(cache) { cache[url] = meta ?: "" }
            if (meta != null) LinkMetaStore.put(url, meta)
            synchronized(inflight) { inflight.remove(url) }
            deliver(url, meta ?: "")
        }
    }

    private fun deliver(url: String, metaJson: String) {
        val w = web() ?: return
        val js = "window.__setMeta(${JSONObject.quote(url)},${metaJson.ifBlank { "null" }})"
        w.post { try { w.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }
}
