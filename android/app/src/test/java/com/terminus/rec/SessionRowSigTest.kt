package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** The list diff keys off sessionRowSig: any visible change must flip the
 *  signature, or DiffUtil skips the rebind and the row goes stale. */
class SessionRowSigTest {

    private fun session(unread: Int, text: String = "hello") = ChatSession(
        id = "s-1",
        title = "chat",
        updatedAt = 1000L,
        model = "",
        unread = unread,
        messages = mutableListOf(ChatMessage("assistant", text, settled = true)),
    )

    @Test fun `clearing unread flips the signature`() {
        val before = sessionRowSig(session(unread = 2), live = false, images = 0, files = 0)
        val after = sessionRowSig(session(unread = 0), live = false, images = 0, files = 0)
        assertNotEquals(before, after)
    }

    @Test fun `live toggle flips the signature`() {
        val s = session(unread = 0)
        assertNotEquals(
            sessionRowSig(s, live = false, images = 0, files = 0),
            sessionRowSig(s, live = true, images = 0, files = 0),
        )
    }

    @Test fun `rename flips the signature`() {
        val a = session(unread = 0).copy(title = "old")
        val b = session(unread = 0).copy(title = "new")
        assertNotEquals(
            sessionRowSig(a, live = false, images = 0, files = 0),
            sessionRowSig(b, live = false, images = 0, files = 0),
        )
    }

    @Test fun `deleting toggle flips the signature`() {
        val s = session(unread = 0)
        assertNotEquals(
            sessionRowSig(s, live = false, images = 0, files = 0, deleting = false),
            sessionRowSig(s, live = false, images = 0, files = 0, deleting = true),
        )
    }

    @Test fun `identical state is stable`() {
        val a = session(unread = 1)
        val b = session(unread = 1)
        assertEquals(
            sessionRowSig(a, live = false, images = 0, files = 0),
            sessionRowSig(b, live = false, images = 0, files = 0),
        )
    }
}
