package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** The browser card: live form, and the finished form that carries no "Open view". */
class BrowserCardTest {
    private val hex = "7b226e223a302c227374657073223a5b5d7d" // {"n":0,"steps":[]}

    @Test fun `live card has the open link`() {
        val html = ChatMarkdown.bodyHtml("[Browser](terminus://browser)")
        assertTrue(html.contains("pbopen"))
        assertFalse(html.contains("data-f="))
    }

    @Test fun `finished card carries its data and no open link or footer`() {
        val html = ChatMarkdown.bodyHtml("[Browsed a.com](terminus://browser#f=$hex)")
        assertTrue(html.contains("data-f='$hex'"))
        assertTrue(html.contains("Browsed a.com"))
        assertTrue(html.contains("pbidle pbc"))
        assertFalse(html.contains("pbopen"))
        assertFalse(html.contains("pbfoot"))
    }

    @Test fun `malformed finished card payload falls back to the live card`() {
        val html = ChatMarkdown.bodyHtml("[Browser](terminus://browser#f=zz'><script>)")
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("data-f='zz"))
    }
}

/** The GET-only link token must never reach pages that upload or delete. */
class LinkTokenScopeTest {
    private val link = "l1.abc.sig"

    @Test fun `link token goes on plain media files`() {
        assertTrue(ChatMarkdown.withToken("http://h:9009/terminus/2026-10-24/a.png", link).contains("token=$link"))
    }

    @Test fun `link token stays off the gallery page and other panes`() {
        for (u in listOf("http://h:9009/terminus/", "http://h:9009/terminus", "http://h:9009/terminus/2026-10-24/",
            "http://h:9009/ops", "http://h:9009/notes", "http://h:9009/echo")) {
            assertEquals(u, ChatMarkdown.withToken(u, link))
        }
    }

    @Test fun `master token behaves as before`() {
        assertTrue(ChatMarkdown.withToken("http://h:9009/terminus/", "master").contains("token=master"))
        assertTrue(ChatMarkdown.withToken("http://h:9009/ops", "master").contains("token=master"))
    }
}

/** Which chat the live browser card is posted in. */
class BrowserOwnerTest {
    private fun pick(open: String?, running: Set<String>, asked: Map<String, Long>, now: Long = 1_000_000L) =
        BrowserCardController.chooseOwner(open, running, asked, now)

    @Test fun `the chat that asked wins over the open chat`() {
        assertEquals("a", pick("b", setOf("a"), mapOf("a" to 999_000L)))
    }

    @Test fun `with several running the most recently asked wins, not the open one`() {
        assertEquals("a", pick("b", setOf("a", "b"), mapOf("a" to 999_000L, "b" to 100L)))
    }

    @Test fun `nothing tracked falls back to a fresh send, then the open chat`() {
        assertEquals("a", pick("b", emptySet(), mapOf("a" to 999_000L)))
        assertEquals("b", pick("b", emptySet(), mapOf("a" to 1L)))
    }
}
