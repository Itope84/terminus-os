package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Timeline events are ruled lines, not messages: no bubble, no tool
 *  header, no timestamp. One treatment for interrupted, compaction,
 *  cleared history, forks and imports. */
class EventRowTest {

    private fun html(vararg msgs: ChatMessage) = ChatMarkdown.threadHtml("t", "", msgs.toList())

    @Test fun `event renders the ruled divider, not a bubble`() {
        val h = html(ChatMessage("event", "interrupted"))
        assertTrue(h.contains("data-role=\"event\""))
        assertTrue(h.contains("cdiv evt"))
        assertTrue(h.contains("interrupted"))
        assertFalse(h.contains("row tool"))
        assertFalse(h.contains("row assistant"))
        assertFalse(h.contains("<div class='toolhead'>"))
    }

    @Test fun `event carries no timestamp`() {
        val h = html(ChatMessage("event", "history cleared"))
        val row = h.substringAfter("data-role=\"event\"").substringBefore("</div></div>")
        assertFalse(row.contains("etime"))
    }

    @Test fun `long event text is escaped, never raw html`() {
        val h = html(ChatMessage("event", "<script>alert(1)</script> compaction failed"))
        assertTrue(h.contains("&lt;script&gt;"))
        assertFalse(h.contains("<script>alert(1)"))
    }

    @Test fun `events are the only ruled lines when no goal nudge is present`() {
        val h = html(
            ChatMessage("user", "go"),
            ChatMessage("assistant", "working", turnId = "t1", settled = true),
            ChatMessage("event", "context compacted (auto), history kept"),
        )
        assertEquals(1, Regex("cdiv evt").findAll(h).count())
        assertTrue(h.contains("context compacted (auto), history kept"))
    }
}
