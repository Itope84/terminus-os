package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

/** Retry must always do something visible. */
class RetrySyncTest {
    @Test fun `a settled failure with inputs resends instead of re-delivering the same error`() {
        assertEquals(RetryOutcome.RESEND, TurnActivity.retryOutcome(settled = true, running = false, hasInputs = true, settledError = true))
    }

    @Test fun `a settled reply still delivers`() {
        assertEquals(RetryOutcome.SETTLE, TurnActivity.retryOutcome(settled = true, running = false, hasInputs = true, settledError = false))
    }

    @Test fun `a settled failure with nothing to resend still shows the error`() {
        assertEquals(RetryOutcome.SETTLE, TurnActivity.retryOutcome(settled = true, running = false, hasInputs = false, settledError = true))
    }

    @Test fun `running waits, unknown resends, no inputs is a dead link`() {
        assertEquals(RetryOutcome.WAIT, TurnActivity.retryOutcome(false, true, true))
        assertEquals(RetryOutcome.RESEND, TurnActivity.retryOutcome(false, false, true))
        assertEquals(RetryOutcome.DEADLINK, TurnActivity.retryOutcome(false, false, false))
    }
}
