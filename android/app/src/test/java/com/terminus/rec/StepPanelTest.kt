package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Activity panel math: per-step durations, wall totals, failure match. */
class StepPanelTest {

    private fun s(label: String, at: Long, detail: String = "") =
        OpencodeApi.ProgressStep("tool", label, detail, at)

    @Test fun `step duration spans to next step`() {
        val steps = listOf(s("Run command", 1000), s("Verify", 3400), s("Done", 5000))
        assertEquals("2.4s", ChatMarkdown.stepDur(steps, 0))
        assertEquals("1.6s", ChatMarkdown.stepDur(steps, 1))
        assertEquals("", ChatMarkdown.stepDur(steps, 2))
    }

    @Test fun `durations blank without timestamps`() {
        val steps = listOf(s("Run command", 0), s("Verify", 0))
        assertEquals("", ChatMarkdown.stepDur(steps, 0))
        assertEquals("", ChatMarkdown.stepsTotal(steps))
    }

    @Test fun `total spans first to last`() {
        val steps = listOf(s("a", 1000), s("b", 2500), s("c", 5300))
        assertEquals("4.3s", ChatMarkdown.stepsTotal(steps))
    }

    @Test fun `sub-second shows millis`() {
        assertEquals("800ms", ChatMarkdown.stepsTotal(listOf(s("a", 1000), s("b", 1800))))
    }

    @Test fun `failure matches provider words`() {
        assertTrue(ChatMarkdown.stepFailed("Run command", "exit 1: error boom"))
        assertTrue(ChatMarkdown.stepFailed("timed out waiting", ""))
        assertFalse(ChatMarkdown.stepFailed("Run command", "ok"))
        assertFalse(ChatMarkdown.stepFailed("", ""))
    }
}
