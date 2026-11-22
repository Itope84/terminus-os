package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotifyTest {
    // ---- channel rebuild rule (the delete-on-every-ping bug)
    @Test fun `an existing channel with the same stored tone is never rebuilt`() {
        assertFalse(ChatNotify.channelNeedsBuild(exists = true, applied = "", want = ""))
        assertFalse(ChatNotify.channelNeedsBuild(exists = true, applied = "content://t/1", want = "content://t/1"))
    }

    @Test fun `a missing channel is built`() {
        assertTrue(ChatNotify.channelNeedsBuild(exists = false, applied = "", want = ""))
        assertTrue(ChatNotify.channelNeedsBuild(exists = false, applied = null, want = ""))
    }

    @Test fun `changing the stored tone rebuilds once`() {
        assertTrue(ChatNotify.channelNeedsBuild(exists = true, applied = "", want = "content://t/2"))
    }

    // ---- ids
    @Test fun `chat ids stay clear of the fixed notification ids`() {
        val fixed = setOf(ChatNotify.BROWSER_ID, ChatNotify.MILESTONE_ID, WakeAlarm.NOTIF_ID)
        assertTrue(ChatNotify.TURN_ID > fixed.max())
    }

    // ---- preview text
    @Test fun `markdown is flattened for the tray`() {
        assertEquals("Done. See the file for details.", notifPreview("**Done.** See the `file` for details."))
        assertEquals("Plan • one • two", notifPreview("## Plan\n- one\n- two"))
        assertEquals("open docs now", notifPreview("open [docs](https://x.y/z) now"))
    }

    @Test fun `long previews are capped with an ellipsis`() {
        val p = notifPreview("a".repeat(400), max = 160)
        assertEquals(160, p.length)
        assertTrue(p.endsWith("…"))
    }

    @Test fun `blank stays blank`() {
        assertEquals("", notifPreview("  \n "))
    }
}
