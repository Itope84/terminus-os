package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Late-done router: merged or replayed drops, partial upgrades,
 *  new-messageID appends even on coincidental same text. */
class LateDoneTest {

    @Test fun `merged turns drop even with new text`() {
        assertEquals(LateDone.DROP, TurnActivity.lateDoneRoute(true, false, false, true, false))
        assertEquals(LateDone.DROP, TurnActivity.lateDoneRoute(true, true, true, true, true))
    }

    @Test fun `kept partial upgrades in place`() {
        assertEquals(LateDone.UPGRADE, TurnActivity.lateDoneRoute(false, true, false, true, false))
    }

    @Test fun `own turn already rendered drops`() {
        assertEquals(LateDone.DROP, TurnActivity.lateDoneRoute(false, false, true, true, false))
    }

    @Test fun `new messageID appends despite coincidental same text`() {
        assertEquals(LateDone.APPEND, TurnActivity.lateDoneRoute(false, false, false, true, true))
    }

    @Test fun `no messageID plus rendered text drops`() {
        assertEquals(LateDone.DROP, TurnActivity.lateDoneRoute(false, false, false, false, true))
    }

    @Test fun `genuinely new text appends`() {
        assertEquals(LateDone.APPEND, TurnActivity.lateDoneRoute(false, false, false, true, false))
        assertEquals(LateDone.APPEND, TurnActivity.lateDoneRoute(false, false, false, false, false))
    }
}
