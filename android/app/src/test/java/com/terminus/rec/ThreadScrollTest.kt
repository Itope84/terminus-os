package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Opening a chat: no jump to the top, and unread chats open at the first unread message. */
class ThreadScrollTest {

    // ---- scrollPlan: the "opens at the top" race
    @Test fun `a render during a reload repeats the in-flight request, not the live Y of 0`() {
        // Open asked for the bottom; a stream render (scroll=false) lands before the page commits and the
        // live scroll reads 0. It must keep following the bottom, not restore to Y=0.
        assertEquals(true to 0, scrollPlan(scroll = false, reloading = true, lastScroll = true, lastKeepCssY = 0, liveCssY = 0))
        // The in-flight render was a position restore at Y=840: repeat that, not the transient 0.
        assertEquals(false to 840, scrollPlan(scroll = false, reloading = true, lastScroll = false, lastKeepCssY = 840, liveCssY = 0))
    }

    @Test fun `a settled page keeps the live position, and an explicit scroll always scrolls`() {
        assertEquals(false to 512, scrollPlan(scroll = false, reloading = false, lastScroll = true, lastKeepCssY = 0, liveCssY = 512))
        assertEquals(true to 512, scrollPlan(scroll = true, reloading = true, lastScroll = false, lastKeepCssY = 9, liveCssY = 512))
    }

    // ---- first unread
    private fun a(text: String, turn: String = "", settled: Boolean = true) =
        ChatMessage("assistant", text, turnId = turn, settled = settled)

    @Test fun `nothing unread keeps the bottom`() {
        assertEquals(-1, firstUnreadIndex(listOf(a("one"), a("two")), 0))
    }

    @Test fun `first unread is the oldest of the unread run, skipping other rows`() {
        val msgs = listOf(
            ChatMessage("user", "q1"), a("old reply", turn = "t1"),
            ChatMessage("user", "q2"), a("new 1", turn = "t2"),
            ChatMessage("tool", "step"), a("new 2", turn = "t3"),
        )
        assertEquals(3, firstUnreadIndex(msgs, 2))
        assertEquals(5, firstUnreadIndex(msgs, 1))
        assertEquals(1, firstUnreadIndex(msgs, 3))
    }

    @Test fun `delivered replies keep their turn id and still count`() {
        assertTrue(countsAsUnread(a("reply", turn = "t9")))
        assertFalse(countsAsUnread(a("typing", turn = "t9", settled = false)))
        assertFalse(countsAsUnread(ChatMessage("user", "hi")))
    }

    @Test fun `a counter that outruns the rows opens at the bottom`() {
        assertEquals(-1, firstUnreadIndex(listOf(ChatMessage("user", "q"), a("only", turn = "t1")), 3))
    }

    // ---- page script
    @Test fun `anchored open scrolls to the row and stops following the bottom`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("user", "q"), a("r", turn = "t1")),
            chatId = "c", anchorIdx = 1)
        assertTrue(html.contains("getElementById('m1')"))
        assertTrue(html.contains("window.__follow=false"))
        assertFalse(html.contains("function b(){"))
        assertTrue(html.contains("touchstart"))
    }

    @Test fun `normal open still pins to the bottom`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("user", "q")), chatId = "c")
        assertTrue(html.contains("window.__follow=true"))
        assertTrue(html.contains("function b(){"))
        assertFalse(html.contains("getElementById('m"))
    }
}
