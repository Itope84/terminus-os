package com.terminus.rec

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The phone's location for the Mac, on request only. The server asks over the live link (event `location`),
 *  this gets one fix and posts it to /api/location/reply. Nothing is tracked or stored here beyond what Android
 *  itself keeps; no Play services (platform LocationManager only).
 *
 *  Permission is asked the first time it is needed: no permission yet → a notification "Allow location" opens
 *  [LocationPermissionActivity] and the server is told permission_needed. A request while the app is in the
 *  background also needs "Allow all the time"; the same notification leads there. */
object PhoneLocation {
    private const val NOTIF_ID = 7401
    private const val ASK_EVERY_MS = 10 * 60_000L
    @Volatile private var lastAskAt = 0L
    private val http by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val pool = Executors.newSingleThreadExecutor()

    fun hasForeground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Live-link request [id]: answer it off the main thread. */
    fun answer(ctx: Context, id: String) {
        val app = ctx.applicationContext
        pool.execute {
            val front = LiveLinkService.appInFront(app)
            val missing = when {
                !hasForeground(app) -> "foreground"
                !front && !hasBackground(app) -> "background"
                else -> null
            }
            if (missing != null) {
                // App on screen: Android's prompt right away. Otherwise a notification leads to it.
                if (front && missing == "foreground") askNow(app) else askByNotification(app, background = missing == "background")
                reply(app, JSONObject().put("id", id).put("error", "permission_needed"))
                return@execute
            }
            val fix = currentFix(app, 15_000)
            reply(app, if (fix == null) JSONObject().put("id", id).put("error", "no_fix") else fixJson(fix).put("id", id))
        }
    }

    fun fixJson(l: Location): JSONObject = JSONObject()
        .put("lat", l.latitude).put("lng", l.longitude)
        .put("accuracy", if (l.hasAccuracy()) l.accuracy.toDouble() else JSONObject.NULL)
        .put("at", if (l.time > 0) l.time else System.currentTimeMillis())

    /** One fix: a fresh one within [timeoutMs], else the newest Android already has. Null without permission. */
    fun currentFix(ctx: Context, timeoutMs: Long): Location? {
        if (!hasForeground(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val names = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { p -> try { p in lm.allProviders && lm.isProviderEnabled(p) } catch (_: Exception) { false } }
        val provider = names.firstOrNull()
        var got: Location? = null
        if (provider != null) {
            val latch = CountDownLatch(1)
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(provider, CancellationSignal(), Executors.newSingleThreadExecutor()) { l -> got = l; latch.countDown() }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { l -> got = l; latch.countDown() }, Looper.getMainLooper())
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: SecurityException) { return null } catch (_: Exception) { /* fall back below */ }
        }
        if (got != null) return got
        return try {
            lm.allProviders.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    private fun reply(ctx: Context, body: JSONObject) {
        val prefs = Prefs(ctx)
        val base = prefs.serverUrl().trimEnd('/')
        if (base.isBlank()) return
        try {
            val req = Request.Builder().url("$base/api/location/reply")
                .header("Authorization", "Bearer " + prefs.token())
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) { /* the server falls back to the last known fix */ }
    }

    private fun askNow(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, LocationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { askByNotification(ctx, background = false) }
    }

    /** "Allow location" notification, at most once per ten minutes. */
    private fun askByNotification(ctx: Context, background: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAskAt < ASK_EVERY_MS || !ChatNotify.hasPermission(ctx)) return
        lastAskAt = now
        ChatNotify.ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, LocationPermissionActivity::class.java).putExtra(LocationPermissionActivity.EXTRA_BACKGROUND, background)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (background) "A job asked where you are while the app was closed. Tap to allow location all the time."
            else "Something asked where you are. Tap to allow location."
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, NotificationCompat.Builder(ctx, ChatNotify.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Allow location for Terminus")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {}
    }
}
