package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxRoutingTest {
    @Test fun `valid chat override wins over job thread`() {
        assertEquals("s-1791833877988", inboxThreadFor("browser", "s-1791833877988"))
    }

    @Test fun `invalid chat override falls back to job thread`() {
        assertEquals("schedule:browser", inboxThreadFor("browser", "bad chat!"))
    }

    @Test fun `alerts and system route to alerts`() {
        assertEquals("alerts", inboxThreadFor("alerts"))
        assertEquals("alerts", inboxThreadFor("system"))
    }

    @Test fun `plain job routes to schedule thread`() {
        assertEquals("schedule:weather", inboxThreadFor("weather"))
    }
}
