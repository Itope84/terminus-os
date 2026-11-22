package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxPreviewTest {
    @Test fun `brief preview is the day line plus money when there is one`() {
        val b = "# 🌅 Wed 28 Oct\nDry and mild; standup 09:00, Budget day.\n\n**Money:** Trade MRNA at open, exit +8% · Trim nothing\n\n## ☀️ YOUR DAY"
        assertEquals("Dry and mild; standup 09:00, Budget day. · 💷 Trade MRNA at open, exit +8% · Trim nothing", inboxPreview(b))
    }

    @Test fun `quiet money day shows only the day line`() {
        assertEquals("Showers; nothing on until 13:00.", inboxPreview("# 🌅 Tue 27 Oct\nShowers; nothing on until 13:00.\n\n## ☀️ YOUR DAY"))
    }

    @Test fun `other deliveries are untouched`() {
        assertEquals("Disk 91% full", inboxPreview("Disk 91% full"))
    }
}
