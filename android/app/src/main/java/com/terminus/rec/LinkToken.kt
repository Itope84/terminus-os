package com.terminus.rec

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The token that goes into WebView URLs (?token=): a short-lived, read-only stand-in minted by the
 *  server (POST /api/link-token with the master token). A copy that leaks from a URL, log or history
 *  can read gallery and repo files but not notes, and cannot call the API, chat or upload.
 *
 *  Seamless by design: until one has been fetched (first run after update, server too old, offline),
 *  [forUrl] returns the master token, exactly as before. Nothing waits on the network. */
object LinkToken {
    private const val PREFS = "link-token"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val fetching = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val MIN_LEFT_MS = 3 * 24 * 3600_000L   // refresh when under 3 days remain

    /** Ties the cached token to this server + master token, so changing either drops it. */
    private fun fingerprint(base: String, master: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$base|$master".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Token for URLs: the cached link token while valid, else the master token. */
    fun forUrl(ctx: Context, prefs: Prefs): String {
        val master = prefs.token()
        if (master.isBlank()) return master
        return try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val t = sp.getString("token", "") ?: ""
            val exp = sp.getLong("exp", 0L)
            val fp = sp.getString("fp", "") ?: ""
            if (t.isNotBlank() && exp > System.currentTimeMillis() + 60_000L && fp == fingerprint(prefs.serverUrl().trimEnd('/'), master)) t else master
        } catch (_: Exception) { master }
    }

    /** Fetch a fresh link token in the background when none is cached or it is close to expiring.
     *  Failures are silent: the master token keeps working meanwhile. */
    fun refresh(ctx: Context, prefs: Prefs) {
        val master = prefs.token()
        if (master.isBlank() || !fetching.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val base = prefs.serverUrl().trimEnd('/')
        Thread {
            try {
                val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val fp = fingerprint(base, master)
                val left = sp.getLong("exp", 0L) - System.currentTimeMillis()
                if (sp.getString("fp", "") == fp && left > MIN_LEFT_MS) return@Thread
                val req = Request.Builder().url("$base/api/link-token")
                    .header("Authorization", "Bearer $master")
                    .post(ByteArray(0).toRequestBody(null)).build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val o = org.json.JSONObject(res.body?.string() ?: return@use)
                    val t = o.optString("token")
                    val exp = o.optLong("expiresAt", 0L)
                    if (t.startsWith("l1.") && exp > System.currentTimeMillis())
                        sp.edit().putString("token", t).putLong("exp", exp).putString("fp", fp).apply()
                }
            } catch (_: Exception) {
            } finally {
                fetching.set(false)
            }
        }.start()
    }
}
