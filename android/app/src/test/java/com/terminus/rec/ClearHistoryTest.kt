package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Terminal-clear keeps only rows of turns still pending. */
class ClearHistoryTest {

    @Test fun `settled rows drop`() {
        val m = ChatMessage("assistant", "old reply", turnId = "t1", settled = true)
        assertFalse(clearKeeps(m, setOf("t1")))
        assertFalse(clearKeeps(m, emptySet()))
    }

    @Test fun `live placeholder of pending turn stays`() {
        val m = ChatMessage("assistant", "…", turnId = "t-live", settled = false)
        assertTrue(clearKeeps(m, setOf("t-live")))
    }

    @Test fun `orphan placeholder with no pending turn drops`() {
        val m = ChatMessage("assistant", "…", turnId = "t-dead", settled = false)
        assertFalse(clearKeeps(m, setOf("t-other")))
        assertFalse(clearKeeps(m, emptySet()))
    }

    @Test fun `unknown row of pending turn stays, others drop`() {
        // Emitted role is "system" now; rows persisted by older builds use
        // "tool". Both must survive a clear while their turn is pending.
        val live = ChatMessage("system", "no reply yet, still trying. [retry](terminus://retry?chat=c&turn=t1)")
        assertTrue(clearKeeps(live, setOf("t1")))
        assertFalse(clearKeeps(live, setOf("t2")))
        val legacy = ChatMessage("tool", "no reply yet, still trying. [retry](terminus://retry?chat=c&turn=t1)")
        assertTrue(clearKeeps(legacy, setOf("t1")))
        val user = ChatMessage("user", "hi")
        assertFalse(clearKeeps(user, setOf("t1")))
    }

    @Test fun `event rows drop on clear`() {
        val ev = ChatMessage("event", "context compacted, history kept")
        assertFalse(clearKeeps(ev, setOf("t1")))
        assertFalse(clearKeeps(ev, emptySet()))
    }
}
