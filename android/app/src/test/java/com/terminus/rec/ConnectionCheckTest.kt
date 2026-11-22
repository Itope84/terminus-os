package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

class ConnectionCheckTest {
    @Test fun `normalizes and accepts http and https`() {
        assertEquals("http://192.168.1.5:9009", ConnectionCheck.normalizeUrl("  http://192.168.1.5:9009/ "))
        assertEquals("https://box.example.test", ConnectionCheck.normalizeUrl("https://box.example.test//"))
    }

    @Test fun `rejects things that are not addresses`() {
        for (bad in listOf("", "192.168.1.5:9009", "ftp://x", "http://", "http://a b", "just text")) {
            assertNull(bad, ConnectionCheck.normalizeUrl(bad))
        }
    }

    @Test fun `errors are plain language`() {
        assertEquals("wrong password", ConnectionCheck.describe(java.io.IOException("defaults: http 401")))
        assertEquals("that address is not a Terminus server", ConnectionCheck.describe(java.io.IOException("defaults: http 404")))
        assertEquals("address not found", ConnectionCheck.describe(java.net.UnknownHostException("x")))
        assertEquals("can't reach it", ConnectionCheck.describe(java.net.SocketTimeoutException("t")))
    }

    @Test fun `host for summaries`() {
        assertEquals("10.0.0.2:9009", ConnectionCheck.hostOf("http://10.0.0.2:9009/api"))
    }
}
