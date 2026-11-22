package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Echo session labels: sanitize, filename, and parser compatibility. */
class EchoNamingTest {

    @Test fun `sanitize keeps it filename-safe`() {
        assertEquals("my-walk", RecorderService.sanitizeLabel("My Walk!"))
        assertEquals("", RecorderService.sanitizeLabel(""))
        assertEquals("", RecorderService.sanitizeLabel("---"))
        assertEquals("a-b", RecorderService.sanitizeLabel("a__b"))
    }

    @Test fun `sanitize caps length`() {
        val long = RecorderService.sanitizeLabel("abcdefghijklmnopqrstuvwxyz123456")
        assertTrue(long.length <= 24)
        assertFalse(long.endsWith("-"))
    }

    @Test fun `unlabeled names unchanged`() {
        assertEquals(
            "rec-2026-09-30-10-15-01.m4a",
            RecorderService.chunkName("2026-09-30-10-15", 1, ""),
        )
    }

    @Test fun `label trails the chunk index`() {
        assertEquals(
            "rec-2026-09-30-10-15-01-walk.m4a",
            RecorderService.chunkName("2026-09-30-10-15", 1, "walk"),
        )
    }

    @Test fun `labeled names still parse day and slot`() {
        val name = RecorderService.chunkName("2026-09-30-10-15", 1, "walk")
        assertEquals("2026-09-30", ChunkAdapter.dayKey(name))
        assertEquals(10 * 60 + 15, ChunkAdapter.slotStartMin(name))
    }

    @Test fun `chunkLabel extracts trailing label only`() {
        assertEquals("walk", ChunkAdapter.chunkLabel("rec-2026-09-30-10-15-01-walk.m4a"))
        assertEquals("my-mark", ChunkAdapter.chunkLabel("rec-2026-09-30-10-15-01-my-mark.m4a"))
        assertEquals("", ChunkAdapter.chunkLabel("rec-2026-09-30-10-15-01.m4a"))
    }
}
