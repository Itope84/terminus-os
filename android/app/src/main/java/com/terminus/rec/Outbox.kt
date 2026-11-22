package com.terminus.rec

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Sends that never reached the Mac wait in the thread and go out on their own once it is reachable again,
 *  instead of failing with the text dumped back in the box. Only a failure before the request left the
 *  phone counts (no network, refused, unknown host): a timeout may have reached the server, so it keeps the
 *  normal failed + retry path rather than risk sending twice. */
object Outbox {
    /** failError prefix that marks a waiting send; the rest is the reason shown in the bubble. */
    const val PREFIX = "waiting: "

    fun isWaiting(failError: String): Boolean = failError.startsWith(PREFIX)

    fun reasonOf(failError: String): String = failError.removePrefix(PREFIX)

    /** True when the request never left the phone, so resending cannot duplicate it. */
    fun neverSent(e: Throwable?): Boolean {
        var c = e
        var depth = 0
        while (c != null && depth < 6) {
            if (c is java.net.ConnectException || c is java.net.UnknownHostException || c is java.net.NoRouteToHostException) return true
            c = c.cause
            depth++
        }
        return false
    }

    /** Plain reason for the bubble, from what Android knows about the network right now. */
    fun reason(ctx: Context, serverUrl: String): String = reasonFor(
        hasNetwork = netCaps(ctx) != null,
        vpnUp = netCaps(ctx)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        serverOnTailnet = onTailnet(serverUrl),
        tailscaleInstalled = tailscaleInstalled(ctx),
    )

    /** Pure: which offline case this is. Nothing is assumed about how the server is reached: Tailscale is
     *  only blamed when it is installed, the server sits on a Tailscale address, and no VPN is up. */
    fun reasonFor(hasNetwork: Boolean, vpnUp: Boolean, serverOnTailnet: Boolean, tailscaleInstalled: Boolean = false): String = when {
        !hasNetwork -> "you're offline"
        serverOnTailnet && tailscaleInstalled && !vpnUp -> "Tailscale is off"
        else -> "server unreachable"
    }

    private fun tailscaleInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.tailscale.ipn", 0); true
    } catch (_: Exception) { false }

    /** Tailscale addresses: 100.64.0.0/10 or a *.ts.net name. */
    fun onTailnet(url: String): Boolean {
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (host.endsWith(".ts.net")) return true
        val p = host.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    private fun netCaps(ctx: Context): NetworkCapabilities? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    } catch (_: Exception) { null }
}
