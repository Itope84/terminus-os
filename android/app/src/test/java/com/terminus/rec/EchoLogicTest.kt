package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

class EchoLogicTest {
    @Test fun `wave level is quiet-low, speech-high, always in range`() {
        assertEquals(0.04f, waveLevel(0), 0f)
        assertTrue(waveLevel(300) < 0.5f)
        assertTrue(waveLevel(12000) > 0.8f)
        assertEquals(1f, waveLevel(32767), 0.001f)
        for (a in listOf(-5, 1, 50, 3000, 40000)) assertTrue(waveLevel(a) in 0.04f..1f)
    }

    @Test fun `chunk line`() {
        assertEquals("Chunk 1 · uploads every 5 min", chunkLine(0, 0))
        assertEquals("Chunk 3 · 2 uploaded", chunkLine(3, 2))
    }

    @Test fun `only this session's uploaded chunks count`() {
        val names = listOf("rec-2026-10-31-09-00-01.m4a", "rec-2026-10-31-09-00-02-walk.m4a", "rec-2026-10-30-09-00-01.m4a")
        assertEquals(2, uploadedInSession(names, "2026-10-31-09-00"))
        assertEquals(0, uploadedInSession(names, ""))
    }
}
