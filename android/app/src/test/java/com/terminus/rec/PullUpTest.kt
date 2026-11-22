package com.terminus.rec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pull-up at the bottom arms only on a deliberate, mostly vertical upward drag that started there. */
class PullUpTest {
    private val th = 220f

    @Test fun `a long upward drag from the bottom arms`() {
        assertTrue(pullUpArmed(true, true, 240f, 20f, th, 400L))
    }

    @Test fun `a short drag does not arm`() {
        assertFalse(pullUpArmed(true, true, 190f, 5f, th, 400L))
    }

    @Test fun `a drag that did not start at the bottom does not arm`() {
        assertFalse(pullUpArmed(false, true, 400f, 0f, th, 400L))
    }

    @Test fun `leaving the bottom mid-drag does not arm`() {
        assertFalse(pullUpArmed(true, false, 400f, 0f, th, 400L))
    }

    @Test fun `a mostly sideways drag does not arm`() {
        assertFalse(pullUpArmed(true, true, 240f, 300f, th, 400L))
    }

    @Test fun `a quick flick does not arm`() {
        assertFalse(pullUpArmed(true, true, 300f, 0f, th, 200L))
    }
}

class PullFooterTest {
    @Test fun `progress follows the drag and clamps`() {
        org.junit.Assert.assertEquals(0f, pullProgress(0f, 72f, 220f), 0.001f)
        org.junit.Assert.assertEquals(0f, pullProgress(72f, 72f, 220f), 0.001f)
        org.junit.Assert.assertEquals(0.5f, pullProgress(146f, 72f, 220f), 0.001f)
        org.junit.Assert.assertEquals(1f, pullProgress(400f, 72f, 220f), 0.001f)
        org.junit.Assert.assertEquals(0f, pullProgress(50f, 72f, 72f), 0.001f)
    }

    @Test fun `the footer says what happened`() {
        org.junit.Assert.assertEquals("Chat updated", pullSyncMessage(2, true))
        org.junit.Assert.assertEquals("A reply is still being written", pullSyncMessage(0, true))
        org.junit.Assert.assertEquals("You're up to date", pullSyncMessage(0, false))
    }
}
