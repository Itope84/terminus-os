package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

class WakeAlarmTest {
    @Test fun `past times roll to tomorrow`() {
        val (at, _) = WakeAlarm.parse("00:00 test")!!
        assertTrue(at > System.currentTimeMillis())
    }

    @Test fun `in-minutes parses`() {
        val before = System.currentTimeMillis()
        val (at, msg) = WakeAlarm.parse("in 20 min hello")!!
        assertTrue(at - before in 19 * 60_000..21 * 60_000)
        assertEquals("hello", msg)
    }

    @Test fun `am pm handled`() {
        val (_, m1) = WakeAlarm.parse("7:30pm gym")!!
        assertEquals("gym", m1)
        assertNull(WakeAlarm.parse("25:00"))
        assertNull(WakeAlarm.parse("cancel"))
    }

    @Test fun `alarm link renders as labeled card, never bare url`() {
        val url = "terminus://alarm?at=1792656000000&amp;msg=check%20on%20thing"
        val html = ChatMarkdown.rowHtml(
            ChatMessage("assistant", "[Ring at 9](terminus://alarm?at=1792656000000&msg=check%20on%20thing)"),
            0, chatId = "c",
        )
        assertTrue(html.contains("alarmcard"))
        assertTrue(html.contains("terminus://alarm?at=1792656000000"))
        assertFalse(html.contains(">terminus://alarm?at="))
        assertTrue(ChatMarkdown.alarmCardHtml(url, "Ring at 9").contains("check on thing"))
    }
}
