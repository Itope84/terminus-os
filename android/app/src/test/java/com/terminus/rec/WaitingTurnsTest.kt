package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Which turns get a "Send now" link: only those waiting behind the running reply. */
class WaitingTurnsTest {

    private fun placeholder(turn: String, settled: Boolean = false) =
        ChatMessage("assistant", "…", turnId = turn, settled = settled, ts = 1_000_000_000L)

    @Test fun `server-queued backend - every running placeholder after the first waits`() {
        val msgs = listOf(placeholder("t1"), placeholder("t2"), placeholder("t3"))
        val out = waitingTurnIdsOf(msgs, appHeldBackend = false, backendWaiting = listOf("t2", "t3"), held = emptyList(), steered = emptySet())
        assertEquals(listOf("t2", "t3"), out)
    }

    @Test fun `a single running turn has nothing waiting`() {
        val out = waitingTurnIdsOf(listOf(placeholder("t1")), appHeldBackend = false, backendWaiting = emptyList(), held = emptyList(), steered = emptySet())
        assertTrue(out.isEmpty())
    }

    @Test fun `app-held backend ignores posted placeholders and lists only held turns`() {
        val msgs = listOf(placeholder("t1"), placeholder("t2"), placeholder("h1"), placeholder("h2"))
        val out = waitingTurnIdsOf(msgs, appHeldBackend = true, backendWaiting = listOf("t2"), held = listOf("h1", "h2"), steered = emptySet())
        assertEquals(listOf("h1", "h2"), out)
    }

    @Test fun `folded turns are no longer waiting`() {
        val msgs = listOf(placeholder("t1"), placeholder("t2"), placeholder("t3"))
        val out = waitingTurnIdsOf(msgs, appHeldBackend = false, backendWaiting = listOf("t2", "t3"), held = emptyList(), steered = setOf("t2"))
        assertEquals(listOf("t3"), out)
    }

    @Test fun `held entries without a turn id never get a link`() {
        val out = waitingTurnIdsOf(listOf(placeholder("h1")), appHeldBackend = true, backendWaiting = emptyList(), held = listOf("", "h1"), steered = emptySet())
        assertEquals(listOf("h1"), out)
    }

    @Test fun `settled placeholders do not count as running or waiting`() {
        val msgs = listOf(placeholder("t1", settled = true), placeholder("t2"), placeholder("t3"))
        val out = waitingTurnIdsOf(msgs, appHeldBackend = false, backendWaiting = listOf("t1", "t3"), held = emptyList(), steered = emptySet())
        assertEquals(listOf("t3"), out)
    }
}
