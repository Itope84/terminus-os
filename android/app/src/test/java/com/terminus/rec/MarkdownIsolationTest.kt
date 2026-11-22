package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Markdown never leaks across rows: fences, spans, and blocks are
 *  strictly per-message; adversarial input degrades inside its own row. */
class MarkdownIsolationTest {

    private fun html(vararg texts: Pair<String, String>): String {
        val msgs = texts.map { (role, t) -> ChatMessage(role, t) }
        return ChatMarkdown.threadHtml("t", "", msgs)
    }

    @Test fun `unterminated fence stays in its row`() {
        val out = html(
            "assistant" to "start\n```kotlin\nswallowed tail, no closer",
            "user" to "clean follow-up",
        )
        assertTrue(out.contains("clean follow-up"))
        assertFalse(out.contains("clean follow-up</code>"))
        // Exactly one pre block for the fenced row.
        assertEquals(1, out.split("<pre>").size - 1)
    }

    @Test fun `odd backticks render literally`() {
        val out = html("assistant" to "stray `tick and more text")
        assertFalse(out.contains("<code"))
        assertTrue(out.contains("tick"))
    }

    @Test fun `balanced spans stay local`() {
        val out = html(
            "assistant" to "use `code` here",
            "user" to "plain reply",
        )
        assertEquals(1, out.split("<code>").size - 1)
        val plain = out.substringAfter("plain reply").take(40)
        assertFalse(plain.contains("<code"))
    }

    @Test fun `unclosed bold never swallows the next row`() {
        val out = html(
            "assistant" to "begun **bold never closed",
            "user" to "next row intact",
        )
        assertTrue(out.contains("next row intact"))
    }

    @Test fun `a url inside a code span cannot unbalance the row`() {
        val out = html(
            "assistant" to "run `curl https://x.com/api` then `POST /y`",
            "user" to "next",
        )
        assertEquals(2, out.split("<code>").size - 1)
        assertEquals(2, out.split("</code>").size - 1)
        assertFalse(out.contains("href=\"https://x.com/api</code>"))
    }
}
