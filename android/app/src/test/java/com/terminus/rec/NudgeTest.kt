package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Loop nudge divider: goal role renders as a hairline divider, never a bubble. */
class NudgeTest {

    @Test fun `goal role renders divider not bubble`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("goal", "Continuing toward goal (2)", settled = true),
        ))
        assertTrue(html.contains("data-role=\"goal\""))
        assertTrue(html.contains("Continuing toward goal (2)"))
        assertFalse(html.contains("row assistant"))
    }

    @Test fun `blank nudge text falls back`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("goal", "", settled = true),
        ))
        assertTrue(html.contains("Continuing toward goal"))
    }
}
