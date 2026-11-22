package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

class WebAssetCacheTest {
    @Test fun `only dated artifact folders and chat attachments are cached`() {
        assertTrue(WebAssetCache.eligible("http://192.0.2.10:9009/terminus/2026-11-18/pic.jpg?token=abc"))
        assertTrue(WebAssetCache.eligible("http://192.0.2.10:9009/terminus/chat-attach/t-abc/1-x.jpg"))
        assertFalse(WebAssetCache.eligible("http://192.0.2.10:9009/terminus/shared/shared.png"))
        assertFalse(WebAssetCache.eligible("http://192.0.2.10:9009/terminus/notebook-data/x.png"))
        assertFalse(WebAssetCache.eligible("http://192.0.2.10:9009/api/chat?id=1"))
        assertFalse(WebAssetCache.eligible("http://192.0.2.10:9009/terminus/"))
        assertFalse(WebAssetCache.eligible("https://example.com/x.jpg"))
    }

    @Test fun `the key ignores the token and the host`() {
        val a = WebAssetCache.keyOf("http://192.0.2.10:9009/terminus/2026-11-18/p.jpg?token=one")
        val b = WebAssetCache.keyOf("http://mac.example.test:9009/terminus/2026-11-18/p.jpg?token=two")
        assertEquals(a, b)
        assertNotEquals(a, WebAssetCache.keyOf("http://192.0.2.10:9009/terminus/2026-11-18/q.jpg?token=one"))
    }
}
