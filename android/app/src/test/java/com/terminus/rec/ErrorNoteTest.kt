package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Failure notes are Claude-compact: provider errors lead verbatim,
 *  everything else is one quiet "Failed to send" line with per-turn actions. */
class ErrorNoteTest {

    @Test fun `provider error leads with native message`() {
        val s = TurnActivity.formatErrorNote("Step interrupted", "provider_error", "c1", "t1")
        assertEquals("Step interrupted. [Retry](terminus://retry?chat=c1&turn=t1) · [Edit](terminus://edit?chat=c1&turn=t1)", s)
    }

    @Test fun `quota and auth errors lead verbatim like provider errors`() {
        val q = TurnActivity.formatErrorNote("Individual quota reached. Resets in 167h", "usage_limit", "c1", "t1")
        assertTrue(q.startsWith("Individual quota reached. Resets in 167h. "))
        assertTrue(q.contains("terminus://retry?chat=c1&turn=t1"))
        val a = TurnActivity.formatErrorNote("Not logged in", "not_authenticated", "c1", "t1")
        assertTrue(a.startsWith("Not logged in. "))
        val r = TurnActivity.formatErrorNote("Slow down", "rate_limited", "c1", "t1")
        assertTrue(r.startsWith("Slow down. "))
    }

    @Test fun `harness error is one quiet line`() {
        val s = TurnActivity.formatErrorNote("ordering uncertain: earlier turn still running", "ordering_uncertain", "c1", "t1")
        assertEquals("Failed to send. [Retry](terminus://retry?chat=c1&turn=t1) · [Edit](terminus://edit?chat=c1&turn=t1)", s)
    }

    @Test fun `blank code is one quiet line`() {
        val s = TurnActivity.formatErrorNote("network error", "", "c1", "t1")
        assertTrue(s.startsWith("Failed to send. "))
        assertTrue(s.contains("terminus://retry?chat=c1&turn=t1"))
        assertTrue(s.contains("terminus://edit?chat=c1&turn=t1"))
    }

    @Test fun `blank provider message falls back to quiet line`() {
        val s = TurnActivity.formatErrorNote("  ", "provider_error", "c1", "t1")
        assertTrue(s.startsWith("Failed to send. "))
    }

    @Test fun `server-started turns get no actions`() {
        assertEquals("Failed to send.", TurnActivity.formatErrorNote("boom", "", "c1", "goal-abc"))
        assertEquals("Step interrupted", TurnActivity.formatErrorNote("Step interrupted", "provider_error", "c1", "bg-xyz"))
        assertFalse(TurnActivity.formatErrorNote("boom", "", "c1", "goal-abc").contains("terminus://"))
    }
}
