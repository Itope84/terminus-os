package com.terminus.rec

import android.content.Context
import java.io.File
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException

/** What one upload attempt means for the queue. */
internal enum class UploadResult {
    /** Stored (or the server already had it). */
    OK,
    /** Network/server trouble or auth: keep it queued and stop the drain, try again later. */
    RETRY,
    /** The server refused this file for good (too big, bad request): never retry automatically. */
    REJECT,
}

internal object UploadHttp {
    /** Stable per file revision, so a retry after a lost response is recognised server-side. */
    fun uploadId(f: File): String {
        val raw = "${f.name}|${f.length()}|${f.lastModified()}"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "a-" + h.take(32)
    }

    fun classify(code: Int): UploadResult = when {
        code in 200..299 -> UploadResult.OK
        code == 401 || code == 403 || code == 408 || code == 429 || code >= 500 -> UploadResult.RETRY
        else -> UploadResult.REJECT
    }

    fun post(
        client: OkHttpClient, base: String, token: String, path: String,
        f: File, mime: String,
    ): UploadResult {
        return try {
            val url = base.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments(path.trim('/'))
                ?.addQueryParameter("filename", f.name)
                ?.build() ?: return UploadResult.RETRY
            // Streamed from disk: no whole-file copy in memory.
            val body = f.asRequestBody(mime.toMediaType())
            val b = Request.Builder().url(url).post(body).header("X-Upload-Id", uploadId(f))
            if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
            client.newCall(b.build()).execute().use { classify(it.code) }
        } catch (_: IOException) {
            UploadResult.RETRY
        } catch (_: IllegalArgumentException) {
            UploadResult.RETRY
        }
    }
}

object Uploader {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    /** Server cap for /echo (audio chunks). A bigger file would only earn a 413. */
    private const val MAX_BYTES = 15L * 1024 * 1024
    /** Drain mutex: enqueue fires per chunk, per resume, and per manual
     *  retry — without this two threads can see the same queued chunk and
     *  upload it twice. The server also dedupes on X-Upload-Id. */
    private val drainLock = Any()

    /** [retryRejected] = manual retry: chunks the server refused earlier get another go. */
    fun enqueue(ctx: Context, retryRejected: Boolean = false) {
        Thread {
            synchronized(drainLock) {
            val prefs = Prefs(ctx.applicationContext)
            val store = ChunkStore(ctx.applicationContext)
            val dir = MainActivity.recordingsDir(ctx.applicationContext)
            if (retryRejected) store.requeueRejected()
            for (item in store.all().filter { it.state == ChunkStore.STATE_QUEUED }) {
                val f = File(dir, item.name)
                if (!f.exists()) { store.markMissing(item.name); continue }
                if (f.length() > MAX_BYTES) { store.markRejected(item.name); continue }
                when (UploadHttp.post(client, prefs.serverUrl(), prefs.token(), "echo", f, "audio/mp4")) {
                    UploadResult.OK -> store.markUploaded(item.name)
                    UploadResult.REJECT -> store.markRejected(item.name)   // one bad chunk must not block the rest
                    UploadResult.RETRY -> break
                }
            }
            pruneOld(dir, store)
            }
        }.start()
    }

    private fun pruneOld(dir: File, store: ChunkStore) {
        // keep newest 40 uploaded chunks on-device, delete older to save space
        val uploaded = store.all().filter { it.state == ChunkStore.STATE_UPLOADED }
        if (uploaded.size <= 40) return
        uploaded.dropLast(40).forEach {
            File(dir, it.name).delete()
            store.forget(it.name)
        }
    }
}
