package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Regression tests: one turn's activity must never light up another
 *  turn's indicator, and unconfirmed work must never read as running. */
class TurnActivityTest {

    @Test fun `stale A plus active B - settling A keeps B replying`() {
        val inFlight = mapOf("A" to setOf("t-stale"), "B" to setOf("t-live"))
        assertTrue(TurnActivity.isReplying("A", inFlight))
        assertTrue(TurnActivity.isReplying("B", inFlight))
        val aAfter = TurnActivity.dropSettled(inFlight.getValue("A"), "t-stale", settled = true)
        assertTrue(aAfter.isEmpty())
        val kept = inFlight + ("A" to aAfter)
        assertFalse(TurnActivity.isReplying("A", kept))
        assertTrue(TurnActivity.isReplying("B", kept))
        assertEquals(setOf("B"), TurnActivity.replyingChats(kept))
    }

    @Test fun `unknown registry answer keeps the turn in flight`() {
        val after = TurnActivity.dropSettled(setOf("t-x"), "t-x", settled = false)
        assertEquals(setOf("t-x"), after)
    }

    @Test fun `confirmed idle clears indicator but transport failure keeps state`() {
        assertEquals(emptySet<String>(), TurnActivity.resolveIdle(setOf("t1"), working = false))
        assertEquals(setOf("t1"), TurnActivity.resolveIdle(setOf("t1"), working = true))
        assertNull(TurnActivity.resolveIdle(setOf("t1"), working = null))
    }

    @Test fun `restart - only server-confirmed work stays in flight`() {
        val persisted = setOf("t-done", "t-running")
        // Working session: settled turn drops, running turn keeps its row.
        assertEquals(
            setOf("t-running"),
            TurnActivity.reconcileAfterRestart(persisted, setOf("t-done"), working = true),
        )
        // Idle/done session: no indicator, placeholders survive elsewhere.
        assertEquals(
            emptySet<String>(),
            TurnActivity.reconcileAfterRestart(persisted, emptySet(), working = false),
        )
        // Unreachable server: honest unknown, never "replying".
        assertEquals(
            emptySet<String>(),
            TurnActivity.reconcileAfterRestart(persisted, emptySet(), working = null),
        )
    }

    @Test fun `concurrent turns - only chats with in-flight turns reply`() {
        val inFlight = mapOf("A" to setOf("t1", "t2"), "B" to emptySet<String>())
        assertEquals(setOf("A"), TurnActivity.replyingChats(inFlight))
        val oneLeft = TurnActivity.dropSettled(inFlight.getValue("A"), "t1", settled = true)
        assertEquals(setOf("t2"), oneLeft)
        assertTrue(TurnActivity.isReplying("A", inFlight + ("A" to oneLeft)))
    }

    @Test fun `empty set never replies`() {
        assertFalse(TurnActivity.isReplying("ghost", emptyMap()))
        assertTrue(TurnActivity.replyingChats(emptyMap()).isEmpty())
    }

    @Test fun `auto-settle needs confirmed idle`() {
        val live = listOf("t-old" to 0L)
        assertEquals(setOf("t-old"), TurnActivity.autoSettleTargets(live, 200_000L, working = false, onlyStale = true))
        assertTrue(TurnActivity.autoSettleTargets(live, 200_000L, working = true, onlyStale = true).isEmpty())
        assertTrue(TurnActivity.autoSettleTargets(live, 200_000L, working = null, onlyStale = false).isEmpty())
    }

    @Test fun `auto-settle spares fresh turns, tap takes all`() {
        val live = listOf("t-old" to 0L, "t-fresh" to 199_000L)
        assertEquals(
            setOf("t-old"),
            TurnActivity.autoSettleTargets(live, 200_000L, working = false, onlyStale = true),
        )
        assertEquals(
            setOf("t-old", "t-fresh"),
            TurnActivity.autoSettleTargets(live, 200_000L, working = false, onlyStale = false),
        )
    }

    @Test fun `send debounce ignores content, only timing`() {
        assertTrue(TurnActivity.sendDebounced(1000L, 900L))
        assertTrue(TurnActivity.sendDebounced(1000L, 1000L))
        assertFalse(TurnActivity.sendDebounced(2000L, 900L))
        assertFalse(TurnActivity.sendDebounced(1000L, 0L))
    }

    @Test fun `normText collapses whitespace`() {
        assertEquals("hello world", TurnActivity.normText("  hello   world\n"))
        assertEquals("", TurnActivity.normText("   "))
    }

    @Test fun `retry routing never resends settled or running`() {
        assertEquals(RetryOutcome.SETTLE, TurnActivity.retryOutcome(true, false, true))
        assertEquals(RetryOutcome.SETTLE, TurnActivity.retryOutcome(true, true, false))
        assertEquals(RetryOutcome.WAIT, TurnActivity.retryOutcome(false, true, true))
        assertEquals(RetryOutcome.WAIT, TurnActivity.retryOutcome(false, true, false))
        assertEquals(RetryOutcome.RESEND, TurnActivity.retryOutcome(false, false, true))
        assertEquals(RetryOutcome.DEADLINK, TurnActivity.retryOutcome(false, false, false))
    }

    @Test fun `stripQuotePrefix restores display text`() {
        assertEquals("real question", TurnActivity.stripQuotePrefix("> excerpt\nreal question"))
        assertEquals("plain", TurnActivity.stripQuotePrefix("plain"))
        assertEquals("> lone", TurnActivity.stripQuotePrefix("> lone"))
    }

    @Test fun `verifyCandidates covers stale chats, not just open`() {
        val live = mapOf(
            "c-stale" to listOf("t1" to 0L),
            "c-fresh" to listOf("t2" to 199_000L),
            "c-mixed" to listOf("t3" to 0L, "t4" to 199_000L),
            "c-blank" to listOf("" to 0L),
        )
        assertEquals(
            setOf("c-stale"),
            TurnActivity.verifyCandidates(live, 200_000L, emptySet()),
        )
    }

    @Test fun `verifyCandidates skips in-flight verifications`() {
        val live = mapOf("c1" to listOf("t1" to 0L), "c2" to listOf("t2" to 0L))
        assertEquals(
            setOf("c2"),
            TurnActivity.verifyCandidates(live, 200_000L, setOf("c1")),
        )
        assertTrue(TurnActivity.verifyCandidates(live, 200_000L, setOf("c1", "c2")).isEmpty())
        assertTrue(TurnActivity.verifyCandidates(emptyMap(), 200_000L, emptySet()).isEmpty())
    }

}
