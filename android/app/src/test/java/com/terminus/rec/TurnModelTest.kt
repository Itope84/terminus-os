package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** One rule decides "running": the phone still tracks the turn as pending,
 *  its placeholder is unsettled, and the server is not reporting idle.
 *  Time is not part of it: a quiet turn stays running. */
class TurnModelTest {

    private val old = 1_000_000_000L

    @Test fun `pending unsettled placeholder is running`() {
        val m = ChatMessage("assistant", "…", turnId = "t1", ts = old)
        assertEquals(listOf("t1"), runningTurnIds(listOf(m), setOf("t1"), serverIdle = false))
    }

    @Test fun `quiet turn for a long time is still running`() {
        val m = ChatMessage("assistant", "…", turnId = "t1", ts = old)
        assertEquals(listOf("t1"), runningTurnIds(listOf(m), setOf("t1"), serverIdle = false))
    }

    @Test fun `settled reply is not running`() {
        val m = ChatMessage("assistant", "done", turnId = "t1", settled = true, ts = old)
        assertTrue(runningTurnIds(listOf(m), setOf("t1"), serverIdle = false).isEmpty())
    }

    @Test fun `placeholder the phone no longer tracks is not running`() {
        val m = ChatMessage("assistant", "…", turnId = "t1", ts = old)
        assertTrue(runningTurnIds(listOf(m), emptySet(), serverIdle = false).isEmpty())
    }

    @Test fun `server idle vetoes running`() {
        val m = ChatMessage("assistant", "…", turnId = "t1", ts = old)
        assertTrue(runningTurnIds(listOf(m), setOf("t1"), serverIdle = true).isEmpty())
    }

    @Test fun `user and status rows never run`() {
        val msgs = listOf(
            ChatMessage("user", "hi", turnId = "t1", ts = old),
            ChatMessage("event", "interrupted", ts = old),
            ChatMessage("system", "queued", ts = old),
        )
        assertTrue(runningTurnIds(msgs, setOf("t1"), serverIdle = false).isEmpty())
    }
}
