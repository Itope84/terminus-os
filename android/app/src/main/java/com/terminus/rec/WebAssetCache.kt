package com.terminus.rec

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Disk cache for pictures the thread WebView loads from our server (/terminus/... media files).
 *  The app intercepts those requests to add the bearer token, which bypasses the WebView's own HTTP cache, so
 *  every chat open re-downloaded every image. Media files are written once under distinct names, so a stored
 *  copy is safe to reuse; entries expire after [TTL_MS] and the folder is trimmed to [MAX_TOTAL]. */
object WebAssetCache {
    const val MAX_FILE = 8 * 1024 * 1024
    private const val MAX_TOTAL = 150L * 1024 * 1024
    private const val TTL_MS = 24L * 3600 * 1000

    private val DAY = Regex("^terminus/(\\d{4}-\\d{2}-\\d{2}|chat-attach)/.+")

    /** Only dated artifact folders and chat attachments: written once under distinct names. Persistent folders
     *  (shared, notebook-data) can be overwritten in place, so they are never cached. */
    fun eligible(url: String): Boolean {
        val path = url.substringBefore("?").substringAfter("://").substringAfter("/", "")
        return DAY.matches(path)
    }

    /** Cache key: the path alone (never the token, which can rotate). */
    fun keyOf(url: String): String {
        val path = url.substringBefore("?").substringAfter("://").substringAfter("/", "")
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "webassets").apply { mkdirs() }

    fun get(ctx: Context, url: String, now: Long = System.currentTimeMillis()): WebResourceResponse? {
        return try {
            val f = File(dir(ctx), keyOf(url))
            val meta = File(dir(ctx), keyOf(url) + ".mime")
            if (!f.isFile || !meta.isFile) return null
            if (now - f.lastModified() > TTL_MS) { f.delete(); meta.delete(); return null }
            val m = meta.readText().lines()
            respond(m.first(), f)
        } catch (_: Exception) { null }
    }

    /** A file replaced under the same name changes size almost always: compare the server's current size (one
     *  tiny ranged read, off the request path) and drop a stale copy so the next view fetches the new one. */
    fun revalidate(ctx: Context, url: String, token: String) {
        Thread {
            try {
                val key = keyOf(url)
                val meta = File(dir(ctx), "$key.mime")
                val f = File(dir(ctx), key)
                if (!meta.isFile || !f.isFile) return@Thread
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Range", "bytes=0-0")
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.connect()
                val total = c.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    ?: c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { c.responseCode == 200 }
                try { c.inputStream.close() } catch (_: Exception) {}
                if (total != null && total != f.length()) { f.delete(); meta.delete() }
            } catch (_: Exception) { /* offline: keep the copy */ }
        }.start()
    }

    /** Download [len] bytes into the cache, then serve them from the file. Null = could not cache (caller streams). */
    fun put(ctx: Context, url: String, mime: String, body: java.io.InputStream, len: Int): WebResourceResponse? {
        return try {
            val d = dir(ctx)
            val key = keyOf(url)
            val tmp = File(d, "$key.part-${Thread.currentThread().id}")
            var n = 0
            tmp.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = body.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    n += r
                    if (n > MAX_FILE) { tmp.delete(); return null }
                }
            }
            if (n != len) { tmp.delete(); return null } // truncated: never store half a picture
            val f = File(d, key)
            File(d, "$key.mime").writeText("$mime\n$len")
            if (!tmp.renameTo(f)) { tmp.delete(); return null }
            trim(d)
            respond(mime, f)
        } catch (_: Exception) { null }
    }

    private fun respond(mime: String, f: File): WebResourceResponse =
        WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Length" to f.length().toString()), FileInputStream(f))

    private fun trim(d: File) {
        val files = d.listFiles { x -> x.isFile && !x.name.endsWith(".mime") && !x.name.contains(".part-") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL * 3 / 4) break
            total -= f.length()
            f.delete()
            File(d, f.name + ".mime").delete()
        }
    }
}
