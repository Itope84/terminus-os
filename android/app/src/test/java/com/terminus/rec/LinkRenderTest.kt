package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Markdown links: http, terminus actions, local paths, angle destinations. */
class LinkRenderTest {

    @Test fun `plain local path linkifies to file`() {
        val html = ChatMarkdown.bodyHtml("[Video](/Users/a/file.mp4)")
        assertTrue(html.contains("<a href=\"file:///Users/a/file.mp4\">Video</a>"))
    }

    @Test fun `angle destination strips brackets`() {
        val html = ChatMarkdown.bodyHtml("[Video](</Users/a/my file.mp4>)")
        assertTrue(html.contains("<a href=\"file:///Users/a/my file.mp4\">Video</a>"))
        assertFalse(html.contains("&lt;"))
    }

    @Test fun `http video links render inline player`() {
        val html = ChatMarkdown.bodyHtml("[v](https://x.test/a.mp4)", token = "")
        assertTrue(html.contains("<video controls preload=\"metadata\" src=\"https://x.test/a.mp4\">"))
        assertTrue(html.contains("<div class='vsrc'><a href=\"https://x.test/a.mp4\">a.mp4</a></div>"))
    }

    @Test fun `bare mp4 url renders inline player`() {
        val html = ChatMarkdown.bodyHtml("see https://x.test/a.mp4 ok", token = "secret")
        assertTrue(html.contains("<video controls preload=\"metadata\" src=\"https://x.test/a.mp4\">"))
        assertFalse(html.contains("secret"))
    }

    @Test fun `server urls carry token, external stay clean`() {
        val html = ChatMarkdown.bodyHtml("[v](https://srv/terminus/2026-10-14/a.mp4)", token = "secret")
        assertTrue(html.contains("token=secret"))
        val ext = ChatMarkdown.bodyHtml("see https://x.test/a.png ok", token = "secret")
        assertFalse(ext.contains("secret"))
    }

    @Test fun `terminus actions pass through`() {
        val html = ChatMarkdown.bodyHtml("[retry](terminus://retry?chat=c&turn=t)")
        assertTrue(html.contains("<a href=\"terminus://retry?chat=c&amp;turn=t\">retry</a>"))
    }

    @Test fun `non links stay literal`() {
        val html = ChatMarkdown.bodyHtml("[Video](not a path)")
        assertTrue(html.contains("[Video](not a path)"))
    }
}
