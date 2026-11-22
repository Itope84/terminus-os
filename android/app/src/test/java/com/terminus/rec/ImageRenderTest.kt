package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Markdown image syntax embeds; plain links stay links. */
class ImageRenderTest {

    @Test fun `explicit image syntax embeds without extension`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("assistant", "![Lara resort strip](https://example.com/photo?id=1)"),
        ))
        assertTrue(html.contains("<img"))
        assertFalse(html.contains("!Lara"))
    }

    @Test fun `explicit image syntax embeds with extension`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("assistant", "![Torba](https://example.com/bay.jpg)"),
        ))
        assertTrue(html.contains("<img"))
        assertTrue(html.contains("bay.jpg"))
    }

    @Test fun `plain links stay links`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("assistant", "[docs](https://example.com/guide)"),
        ))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("docs</a>") || html.contains(">docs<"))
    }

    @Test fun `legacy image tag still works`() {
        val html = ChatMarkdown.threadHtml("t", "", listOf(
            ChatMessage("assistant", "[image:https://example.com/x.png]"),
        ))
        assertTrue(html.contains("<img"))
    }
}
