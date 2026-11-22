package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Alarm cards say what is true: armed, waiting for a tap, cancelled, or passed. */
class AlarmCardTest {
    private val now = 1_000_000L
    private val at = now + 3_600_000L

    @Test fun `state follows what Android actually holds`() {
        assertEquals(WakeAlarm.State.PASSED, WakeAlarm.stateFor(now - 1, now, 0, emptySet()))
        assertEquals(WakeAlarm.State.ARMED, WakeAlarm.stateFor(at, now, at, emptySet()))
        assertEquals(WakeAlarm.State.READY, WakeAlarm.stateFor(at, now, 0, emptySet()))
        assertEquals(WakeAlarm.State.READY, WakeAlarm.stateFor(at, now, at + 60_000, emptySet()))
        assertEquals(WakeAlarm.State.CANCELLED, WakeAlarm.stateFor(at, now, 0, setOf("$at")))
    }

    @Test fun `an armed alarm wins over an old cancellation of the same time`() {
        assertEquals(WakeAlarm.State.ARMED, WakeAlarm.stateFor(at, now, at, setOf("$at")))
    }

    private fun card(state: WakeAlarm.State?): String {
        ChatMarkdown.alarmStateLookup = { state }
        try { return ChatMarkdown.alarmCardHtml("terminus://alarm?at=4102444800000&msg=Wake", "Wake me") }
        finally { ChatMarkdown.alarmStateLookup = null }
    }

    @Test fun `the card badge matches the state`() {
        assertTrue(card(WakeAlarm.State.ARMED).contains("Armed"))
        assertTrue(card(WakeAlarm.State.READY).contains("Tap to arm"))
        assertTrue(card(WakeAlarm.State.CANCELLED).contains("re-arm"))
        assertTrue(card(WakeAlarm.State.PASSED).contains("Passed"))
    }

    @Test fun `without a lookup the card has no badge`() {
        val out = card(null)
        assertFalse(out, out.contains("Armed") || out.contains("Tap to arm"))
    }
}
