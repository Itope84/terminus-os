package com.terminus.rec

import android.content.Context
import java.io.File

/** Straight-to-gallery uploads (share-sheet upload mode). Files land in the
 *  local media day folder first, then POST to /terminus. Anything the server
 *  can't take right now (locked uploads, offline) stays in a prefs outbox and
 *  retries on every app resume and on the periodic worker. A file the server
 *  refuses for good (too big, bad request) is dropped with a toast instead of
 *  blocking the files behind it. */
object GalleryUploader {
    private const val PREFS = "gallery-outbox"
    private const val KEY = "paths"
    /** Server cap for streamed raw uploads to /terminus (UPLOAD_RAW_MAX in media.ts). */
    private const val MAX_BYTES = 100L * 1024 * 1024

    /** Serializes every outbox read-modify-write (queue/drop/snapshot):
     *  share, resume, and retry paths race otherwise and resurrect or lose
     *  the same file twice. */
    private val outboxLock = Any()
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Set when a drain was requested while one was running, so that request is not lost. */
    private val again = java.util.concurrent.atomic.AtomicBoolean(false)

    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun box(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun queue(ctx: Context, files: List<File>) {
        synchronized(outboxLock) {
            val b = box(ctx)
            val cur = b.getStringSet(KEY, emptySet())!!.toMutableSet()
            files.forEach { cur.add(it.absolutePath) }
            b.edit().putStringSet(KEY, cur).apply()
        }
        drain(ctx)
    }

    fun pending(ctx: Context): Int =
        box(ctx).getStringSet(KEY, emptySet())!!.size

    fun drain(ctx: Context) {
        again.set(true)
        Thread {
            if (!draining.compareAndSet(false, true)) return@Thread   // the running drain will see `again`
            try {
                val app = ctx.applicationContext
                val prefs = Prefs(app)
                var stop = false
                while (!stop && again.getAndSet(false)) {
                    val tried = HashSet<String>()
                    while (!stop) {
                        val p = synchronized(outboxLock) {
                            box(app).getStringSet(KEY, emptySet())!!.firstOrNull { it !in tried }
                        } ?: break
                        tried += p
                        val f = File(p)
                        if (!f.exists()) { drop(app, p); continue }
                        if (f.length() > MAX_BYTES) { reject(app, p, f, "too large (max 100 MB)"); continue }
                        when (UploadHttp.post(client, prefs.serverUrl(), prefs.token(), "terminus", f, MediaFiles.mimeFor(f.name))) {
                            UploadResult.OK -> drop(app, p)
                            UploadResult.REJECT -> reject(app, p, f)
                            UploadResult.RETRY -> stop = true   // offline/locked: leave it for the next drain
                        }
                    }
                }
            } finally {
                draining.set(false)
                // A request that arrived between the last check and the flag reset must not be lost.
                if (again.get()) drain(ctx)
            }
        }.start()
    }

    private fun reject(ctx: Context, path: String, f: File, why: String = "refused by server") {
        drop(ctx, path)
        val app = ctx.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, "Upload $why: ${f.name}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun drop(ctx: Context, path: String) {
        synchronized(outboxLock) {
            val b = box(ctx)
            val cur = b.getStringSet(KEY, emptySet())!!.toMutableSet()
            cur.remove(path)
            b.edit().putStringSet(KEY, cur).apply()
        }
    }
}
