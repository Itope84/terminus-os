package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    @Test
    fun onlyFailuresBeforeTheRequestLeftCountAsWaiting() {
        assertTrue(Outbox.neverSent(java.net.ConnectException("refused")))
        assertTrue(Outbox.neverSent(java.io.IOException("wrap", java.net.UnknownHostException("x"))))
        assertFalse(Outbox.neverSent(java.net.SocketTimeoutException("may have reached the server")))
        assertFalse(Outbox.neverSent(java.io.IOException("chat: http 500")))
        assertFalse(Outbox.neverSent(null))
    }

    @Test
    fun reasons() {
        assertEquals("you're offline", Outbox.reasonFor(hasNetwork = false, vpnUp = false, serverOnTailnet = true))
        assertEquals("Tailscale is off", Outbox.reasonFor(hasNetwork = true, vpnUp = false, serverOnTailnet = true, tailscaleInstalled = true))
        // No Tailscale app: never blame Tailscale (the server may be reached over a LAN or anything else).
        assertEquals("server unreachable", Outbox.reasonFor(hasNetwork = true, vpnUp = false, serverOnTailnet = true, tailscaleInstalled = false))
        assertEquals("server unreachable", Outbox.reasonFor(hasNetwork = true, vpnUp = true, serverOnTailnet = true, tailscaleInstalled = true))
        assertEquals("server unreachable", Outbox.reasonFor(hasNetwork = true, vpnUp = false, serverOnTailnet = false, tailscaleInstalled = true))
    }

    @Test
    fun tailnetAddresses() {
        assertTrue(Outbox.onTailnet("http://100.64.1.2:9009"))
        assertTrue(Outbox.onTailnet("https://mac.example.ts.net"))
        assertFalse(Outbox.onTailnet("http://192.168.1.20:9009"))
        assertFalse(Outbox.onTailnet("http://100.200.1.1:9009"))
    }

    @Test
    fun marker() {
        val fe = Outbox.PREFIX + "Tailscale is off"
        assertTrue(Outbox.isWaiting(fe))
        assertEquals("Tailscale is off", Outbox.reasonOf(fe))
        assertFalse(Outbox.isWaiting("http 500"))
    }
}
