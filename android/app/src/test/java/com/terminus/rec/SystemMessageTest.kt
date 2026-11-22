package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** System messages are their own treatment: a small muted right-aligned
 *  label, never a bubble, never a timestamp. The pending typing placeholder
 *  is an indicator, not an empty assistant bubble. */
class SystemMessageTest {

    private fun html(vararg msgs: ChatMessage) = ChatMarkdown.threadHtml("t", "", msgs.toList())

    @Test fun `system row renders the label, not a bubble`() {
        val h = html(ChatMessage("system", "queued behind running reply, sends when it settles (/wait)"))
        assertTrue(h.contains("sysmsg-lab"))
        assertTrue(h.contains("queued behind running reply"))
        assertFalse(h.contains("row assistant"))
    }

    @Test fun `system row carries no timestamp`() {
        val h = html(ChatMessage("system", "still trying"))
        val row = h.substringAfter("row sysmsg").substringBefore("</div></div>")
        assertFalse(row.contains("etime"))
    }

    @Test fun `system note can still carry a tap action`() {
        val h = html(ChatMessage("system", "no reply yet, still trying. [retry](terminus://retry?chat=c&turn=t)"))
        assertTrue(h.contains("terminus://retry?chat=c&amp;turn=t"))
    }

    @Test fun `bare placeholder renders no row, the activity box is the indicator`() {
        val h = html(ChatMessage("assistant", "…", turnId = "t1", settled = false))
        assertFalse(h.contains("data-role=\"assistant\""))
    }

    @Test fun `user queued body stays clean of the system note`() {
        val h = html(ChatMessage("user", "do the thing"))
        assertFalse(h.contains("queued behind"))
    }
}
