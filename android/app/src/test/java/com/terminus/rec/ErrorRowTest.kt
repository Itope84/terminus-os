package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Error rows render quiet (Claude-style red line with actions), never as muted tool rows. */
class ErrorRowTest {

    @Test fun `error row carries error class without toolhead`() {
        val m = ChatMessage("error", "Failed to send.")
        val html = ChatMarkdown.rowHtml(m, 0)
        assertTrue(html.contains("row error"))
        assertFalse(html.contains("toolhead"))
        assertTrue(html.contains("Failed to send"))
    }

    @Test fun `error row keeps its timestamp`() {
        val m = ChatMessage("error", "failed.", ts = 1791700000000L)
        val html = ChatMarkdown.rowHtml(m, 0)
        assertTrue(html.contains("etime"))
    }

    @Test fun `tool rows unchanged`() {
        val m = ChatMessage("tool", "no reply yet, still trying.")
        val html = ChatMarkdown.rowHtml(m, 0)
        assertTrue(html.contains("toolhead"))
        assertFalse(html.contains("row error"))
    }

    @Test fun `failed send retry and time stay inside user bubble`() {
        val m = ChatMessage("user", "try this", ts = 1791700000000L, turnId = "turn 1", sendFailed = true)
        val html = ChatMarkdown.rowHtml(m, 0, chatId = "chat/1")
        assertTrue(html.contains("row user"))
        assertTrue(html.contains("send failed"))
        assertTrue(html.contains("terminus://retry?chat=chat%2F1&amp;turn=turn%201"))
        assertTrue(html.contains("<span>retry</span>").not())
        assertTrue(html.contains("class='sendfail'"))
        assertTrue(html.contains("</a></span><span>")) // timestamp shares the in-bubble footer
        assertFalse(html.contains("row error"))
    }

    @Test fun `normal user bubble has no failure footer`() {
        val m = ChatMessage("user", "all good", turnId = "turn 2")
        val html = ChatMarkdown.rowHtml(m, 0, chatId = "chat")
        assertFalse(html.contains("sendfail"))
        assertFalse(html.contains("send failed"))
    }

    @Test fun `provider text is a system notice row, not part of the prompt bubble`() {
        val m = ChatMessage("user", "try this", ts = 1791700000000L, turnId = "turn 1",
            sendFailed = true, failError = "Individual quota reached. Resets in 155h")
        val html = ChatMarkdown.rowHtml(m, 0, chatId = "chat")
        assertTrue(html.contains("send failed"))
        assertTrue(html.contains("Individual quota reached. Resets in 155h"))
        val bubble = html.substringBefore("data-role=\"notice\"")
        assertFalse(bubble.contains("Individual quota reached"))
        assertFalse(bubble.contains("<br>"))
        // its own row in the divider family, after the bubble
        assertTrue(html.contains("class='crow' data-role=\"notice\""))
        assertTrue(html.contains("cdiv evt err"))
    }

    @Test fun `notice text is escaped`() {
        val html = ChatMarkdown.errorNoticeHtml("<b>quota</b> & more")
        assertTrue(html.contains("&lt;b&gt;quota&lt;/b&gt; &amp; more"))
        assertFalse(html.contains("<b>"))
    }

    @Test fun `blank provider text renders no notice`() {
        assertEquals("", ChatMarkdown.errorNoticeHtml("   "))
    }

    @Test fun `failed bubble without provider text shows no extra line`() {
        val m = ChatMessage("user", "try this", ts = 1791700000000L, turnId = "turn 1", sendFailed = true)
        val html = ChatMarkdown.rowHtml(m, 0, chatId = "chat")
        assertTrue(html.contains("class='sendfail'"))
        assertFalse(html.contains("<br>"))
        assertFalse(html.contains("notice"))
    }

    @Test fun `server-started failure has no user bubble to attach to`() {
        // No user row carries the turn: the failure note stands alone.
        val note = ChatMessage("error", TurnActivity.formatErrorNote("boom", "", "c", "goal-abc"))
        val html = ChatMarkdown.threadHtml("t", "", listOf(note))
        assertTrue(html.contains("row error"))
        assertFalse(html.contains("class='sendfail'"))
    }
}
