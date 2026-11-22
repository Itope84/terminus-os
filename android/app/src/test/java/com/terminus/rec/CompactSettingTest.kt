package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactSettingTest {
    @Test fun `a server without the setting reads as default`() {
        assertEquals(0, parseDefaults("""{"defaultBackend":"opencode","systemPrompt":""}""").compactAt)
    }

    @Test fun `a stored ceiling comes through`() {
        assertEquals(80_000, parseDefaults("""{"defaultBackend":"claude","compactAt":80000}""").compactAt)
    }
}
