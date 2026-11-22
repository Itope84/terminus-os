package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Bare links are marked so the page can turn them into titled chips; only public web links are asked about. */
class LinkChipTest {
    private fun html(text: String) = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("assistant", text)))

    @Test fun `a bare url is marked for a chip and keeps its href`() {
        val out = html("read https://example.com/story?a=1 now")
        assertTrue(out, out.contains("<a class=\"lk\" data-u=\"https://example.com/story?a=1\" href=\"https://example.com/story?a=1\">"))
    }

    @Test fun `an explicit markdown link keeps the authors label and is not a chip`() {
        val out = html("[the story](https://example.com/story)")
        assertTrue(out, out.contains(">the story</a>"))
        assertFalse(out, out.contains("data-u=\"https://example.com/story\""))
    }

    @Test fun `only public web links are asked about`() {
        assertTrue(LinkPreviewBridge.worthAsking("https://example.com/a", "http://192.0.2.10:9009"))
        assertFalse(LinkPreviewBridge.worthAsking("http://192.0.2.10:9009/terminus/x.png", "http://192.0.2.10:9009"))
        assertFalse(LinkPreviewBridge.worthAsking("ftp://example.com", ""))
        assertFalse(LinkPreviewBridge.worthAsking("https://example.com/" + "a".repeat(2100), ""))
    }

    @Test fun `the page script is injected into the thread`() {
        assertTrue(html("hi").contains("window.__setMeta"))
    }

    @Test fun `a link with a stored title is a chip on first paint, with the title as plain text`() {
        ChatMarkdown.linkMetaLookup = { u -> if (u == "https://example.com/a?x=1&y=2") LinkFetcher.Meta(u, "Headline <b>& more</b>", "s") else null }
        try {
            val out = html("read https://example.com/a?x=1&y=2 now and https://other.example/z")
            assertTrue(out, out.contains("data-lk=\"1\""))
            assertTrue(out, out.contains("<span>Headline &lt;b&gt;&amp; more&lt;/b&gt;</span>"))
            assertTrue(out, out.contains("href=\"https://example.com/a?x=1&amp;y=2\""))
            // the other link has no stored title: still a plain marked link for the page script
            assertTrue(out, out.contains("<a class=\"lk\" data-u=\"https://other.example/z\""))
        } finally { ChatMarkdown.linkMetaLookup = null }
    }

    @Test fun `stored titles expire and unknown links return null`() {
        LinkMetaStore.clear()
        LinkMetaStore.put("https://a.example/", "{\"title\":\"A\"}", now = 1_000L)
        assertEquals("{\"title\":\"A\"}", LinkMetaStore.get("https://a.example/", now = 2_000L))
        assertNull(LinkMetaStore.get("https://a.example/", now = 1_000L + 15L * 24 * 3600_000))
        assertNull(LinkMetaStore.get("https://never.example/", now = 2_000L))
    }
}
