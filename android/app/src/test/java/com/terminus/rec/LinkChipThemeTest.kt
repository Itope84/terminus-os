package com.terminus.rec

import org.junit.Assert.assertTrue
import org.junit.Test

class LinkChipThemeTest {
    @Test fun `link chips are styled in both themes, icon text-sized`() {
        for (dark in listOf(false, true)) {
            val h = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("assistant", "see https://example.com", settled = true)), dark = dark)
            assertTrue("dark=$dark", h.contains("a.lk img.lkf{width:1em;height:1em;max-width:1em"))
        }
    }

    @Test fun `long page titles are cut to fit a sentence`() {
        val h = ChatMarkdown.linkChipHtml("https://example.com/x", "https://example.com/x", "a".repeat(120))
        assertTrue(h.contains("a".repeat(69) + "…"))
        assertTrue(!h.contains("a".repeat(70)))
    }
}
