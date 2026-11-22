package com.terminus.rec

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** Instant notifications with the app closed, without any third-party push: a foreground service holds
 *  GET /api/wake open to the Mac over the tailnet. Android keeps a foreground service's network up in Doze,
 *  so a finished reply or a schedule delivery reaches the phone within seconds instead of on the next
 *  15-minute (Doze-deferred) InboxWorker pass. The worker stays as the backstop.
 *
 *  It notifies only while no Terminus screen is in front; the app's own stream handles the visible case,
 *  and ChatNotify keeps it to one ping per chat either way. */
class LiveLinkService : Service() {

    @Volatile private var running = false
    @Volatile private var reader: Thread? = null
    @Volatile private var call: okhttp3.Response? = null
    private val wake = Object()
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var shown = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val n = statusNotification(this, CONNECTING)
        shown = CONNECTING
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        // A new network (Wi-Fi <-> mobile, tunnel back up) reconnects at once instead of after the backoff.
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var last: Network? = null
                override fun onAvailable(network: Network) {
                    val changed = last != null && last != network
                    last = network
                    // The old socket rode the previous network and may be silently dead: drop it.
                    if (changed) try { call?.close() } catch (_: Exception) {}
                    kick()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            reader = Thread({ loop() }, "terminus-livelink").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { call?.close() } catch (_: Exception) {}
        kick()
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Cut a backoff sleep short (and drop a socket that may be dead after a network change). */
    private fun kick() {
        synchronized(wake) { wake.notifyAll() }
    }

    private fun loop() {
        var backoffMs = 5_000L
        var why = ""
        var downSince = 0L
        while (running) {
            val prefs = Prefs(this)
            if (prefs.token().isBlank()) { pause(60_000); continue }
            why = ""
            try {
                OpencodeApi.openWake(prefs.serverUrl(), prefs.token()).use { res ->
                    call = res
                    backoffMs = 5_000L
                    downSince = 0L
                    showState(CONNECTED)
                    try { onLinkUp?.invoke() } catch (_: Exception) {}
                    // Anything that landed while we were away (offline, Mac asleep) comes in now.
                    InboxWorker.pullInboxAndNotify(this)
                    val src = res.body!!.source()
                    var event = ""
                    val data = StringBuilder()
                    while (running) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (event.isNotEmpty() || data.isNotEmpty()) handle(event.ifEmpty { "message" }, data.toString())
                                event = ""; data.setLength(0)
                            }
                            line.startsWith(":") -> {}
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                        }
                    }
                }
            } catch (e: Exception) {
                // Offline, Mac asleep, tunnel down or a stall: wait, then try again.
                why = if (e.message.orEmpty().contains("http 401")) "token rejected, check settings" else ""
            } finally {
                call = null
            }
            if (!running) break
            // A short drop (a server restart, a network hand-over) is "Reconnecting…", not a diagnosis.
            if (downSince == 0L) downSince = System.currentTimeMillis()
            val brief = why.isEmpty() && System.currentTimeMillis() - downSince < 30_000L
            showState(if (brief) "Reconnecting…" else "Not connected · " + why.ifEmpty { Outbox.reason(this, Prefs(this).serverUrl()) })
            pause(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
        }
    }

    /** The ongoing notification reads like Tailscale's: whether the Mac is reachable right now. */
    private fun showState(text: String) {
        if (text == shown) return
        shown = text
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, statusNotification(this, text)) } catch (_: Exception) {}
    }

    private fun pause(ms: Long) {
        synchronized(wake) { try { wake.wait(ms) } catch (_: InterruptedException) {} }
    }

    private fun handle(event: String, data: String) {
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        when (event) {
            "inbox" -> InboxWorker.pullInboxAndNotify(this)
            // The Mac asks where the phone is (server location.ts): one fix, posted back.
            "location" -> o.optString("id").takeIf { it.isNotBlank() }?.let { PhoneLocation.answer(this, it) }
            "done" -> {
                if (appInFront(this)) return
                val chatId = o.optString("chatId")
                if (chatId.isBlank()) return
                val prefs = Prefs(this)
                if (InboxWorker.quiet(prefs)) return
                val title = try { ChatStore(this).get(chatId)?.title.orEmpty() } catch (_: Exception) { "" }
                val text = if (o.optBoolean("failed")) "That reply failed. Open the chat to retry." else o.optString("preview")
                ChatNotify.notifyTurn(this, text, chatId, title)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "livelink"
        private const val NOTIF_ID = 7317
        private const val CONNECTING = "Connecting…"
        private const val CONNECTED = "Connected"

        /** The Mac answered again (set by MainActivity: waiting sends go out). */
        @Volatile var onLinkUp: (() -> Unit)? = null

        /** Start (or keep) the link. Safe to call on every app open; a no-op without a server token. */
        fun start(ctx: Context) {
            if (Prefs(ctx).token().isBlank()) return
            try {
                val i = Intent(ctx, LiveLinkService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
                // Background start refused (Android 12+): the next app open or reboot starts it.
            }
        }

        /** True while a Terminus screen is visible; then the app's own stream owns notifications. */
        fun appInFront(ctx: Context): Boolean = try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.runningAppProcesses?.any {
                it.pid == android.os.Process.myPid() && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (_: Exception) { false }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(CHANNEL_ID, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps instant notifications working with the app closed. Safe to hide."
                setShowBadge(false)
            }
            try { nm.createNotificationChannel(ch) } catch (_: Exception) {}
        }

        private fun statusNotification(ctx: Context, state: String): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Terminus")
                .setContentText(state)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build()
        }
    }
}

/** Restart the link after a reboot or an app update, so notifications work before the app is next opened. */
class LiveLinkBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> LiveLinkService.start(context)
        }
    }
}
