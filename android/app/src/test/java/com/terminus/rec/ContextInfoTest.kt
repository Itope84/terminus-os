package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextInfoTest {
    @Test fun `a chat with no recorded usage reads as empty`() {
        val c = parseContext("""{"backend":null,"used":0,"window":null,"compactAt":0}""")
        assertEquals(0L, c.used)
        assertEquals(0L, c.window)
        assertNull(c.costUsd)
    }

    @Test fun `usage, window, cost and the compact ceiling come through`() {
        val c = parseContext(
            """{"backend":"claude","model":"claude-opus","used":92406,"window":1000000,"input":6,"output":400,
               "cacheRead":90000,"cacheWrite":2000,"totalTokens":304920,"costUsd":0.2,"compactAt":80000}""")
        assertEquals(92406L, c.used)
        assertEquals(1_000_000L, c.window)
        assertEquals(304920L, c.totalTokens)
        assertEquals(0.2, c.costUsd!!, 1e-9)
        assertEquals(80000, c.compactAt)
    }
}
