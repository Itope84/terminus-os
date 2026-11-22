package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Merged turns: a send answered by another turn's reply shows no label and no pointer row; order untouched. */
class PointerRenderTest {

    private fun u(text: String, turn: String) = ChatMessage("user", text, turnId = turn)
    private fun r(text: String, turn: String, mid: String) =
        ChatMessage("assistant", text, turnId = turn, settled = true, messageID = mid)

    @Test fun `thread shows no shared-reply label and no pointer row, order kept`() {
        val ptr = ChatMessage("tool", "answered together with the shared reply",
            turnId = "t2", settled = true, messageID = "mA")
        val msgs = listOf(u("q1", "t1"), r("a1", "t1", "mA"), u("q2", "t2"), ptr)
        val html = ChatMarkdown.threadHtml("t", "", msgs)
        assertFalse(html.contains("shared reply"))
        assertFalse(html.contains("<div class='ptr'>"))
        val u1 = html.indexOf("q1")
        val a1 = html.indexOf("a1")
        val u2 = html.indexOf("q2")
        assertTrue(u1 >= 0 && a1 > u1 && u2 > a1)
    }
}
