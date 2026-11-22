package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** The unread dot marks delivered results and failures, never status rows.
 *  The session-gone event is the one status line that still earns it: the
 *  turn will never answer, so a user who walked away must still be told. */
class UnreadDotTest {

    @Test fun `delivered result earns the dot`() {
        assertTrue(earnsUnread(ChatMessage("assistant", "here is the link")))
    }

    @Test fun `failure earns the dot`() {
        assertTrue(earnsUnread(ChatMessage("error", "provider error, retry")))
    }

    @Test fun `session gone event earns the dot`() {
        assertTrue(earnsUnread(ChatMessage("event", "session gone, starting fresh on your next message")))
    }

    @Test fun `ordinary status rows do not earn the dot`() {
        assertFalse(earnsUnread(ChatMessage("event", "interrupted")))
        assertFalse(earnsUnread(ChatMessage("event", "context compacted, history kept")))
        assertFalse(earnsUnread(ChatMessage("event", "history cleared")))
        assertFalse(earnsUnread(ChatMessage("system", "queued behind running reply, sends when it settles (/wait)")))
        assertFalse(earnsUnread(ChatMessage("tool", "nothing running, nothing sent yet")))
        assertFalse(earnsUnread(ChatMessage("user", "hi")))
    }

    @Test fun `turn-carrying rows settle through their own path, never here`() {
        // A live placeholder settles via settleTurn: exactly one unread.
        assertFalse(earnsUnread(ChatMessage("assistant", "…", turnId = "t1", settled = false)))
        assertFalse(earnsUnread(ChatMessage("assistant", "done", turnId = "t1", settled = true)))
    }
}
