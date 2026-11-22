package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Changed-files footer: settled replies show what they touched, and
 *  nothing else grows a footer. */
class ChangedFilesTest {

    @Test fun `shortPath keeps last two segments`() {
        assertEquals("src/a.ts", ChatMarkdown.shortPath("/w/src/a.ts"))
        assertEquals("a.ts", ChatMarkdown.shortPath("a.ts"))
        assertEquals("", ChatMarkdown.shortPath("  "))
    }

    @Test fun `shortPath middle-cuts long tails`() {
        val long = "/w/some-long-directory-name/another-long-bit/filename.ts"
        val s = ChatMarkdown.shortPath(long)
        assertTrue(s.length <= 28)
        assertTrue(s.endsWith("filename.ts"))
    }

    @Test fun `settled assistant row carries changed footer`() {
        val m = ChatMessage("assistant", "done", turnId = "t1", settled = true,
            changedFiles = listOf("/w/src/a.ts", "/w/b.ts"))
        val html = ChatMarkdown.rowHtml(m, 0)
        assertTrue(html.contains("changed:"))
        assertTrue(html.contains("src/a.ts"))
        assertTrue(html.contains("b.ts"))
    }

    @Test fun `footer caps at eight with more count`() {
        val files = (1..10).map { "/w/f$it.ts" }
        val m = ChatMessage("assistant", "done", turnId = "t1", settled = true,
            changedFiles = files)
        val html = ChatMarkdown.rowHtml(m, 0)
        assertTrue(html.contains("+2 more"))
        assertFalse(html.contains("f9.ts"))
    }

    @Test fun `user rows and bare rows stay clean`() {
        val u = ChatMessage("user", "hi", changedFiles = listOf("/w/a.ts"))
        assertFalse(ChatMarkdown.rowHtml(u, 0).contains("changed:"))
        val bare = ChatMessage("assistant", "done", turnId = "t1", settled = true)
        assertFalse(ChatMarkdown.rowHtml(bare, 1).contains("changed:"))
    }
}
