package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Liveness mapping: only confirmed states clear; unknowns observe. */
class ProgressWorkingTest {

    @Test fun `working stays, idle and done clear`() {
        assertEquals(true, progressWorkingOf(true, 200, "working", ""))
        assertEquals(false, progressWorkingOf(true, 200, "idle", ""))
        assertEquals(false, progressWorkingOf(true, 200, "done", ""))
    }

    @Test fun `authoritative death clears`() {
        assertEquals(false, progressWorkingOf(false, 404, "", "no session"))
        assertEquals(false, progressWorkingOf(false, 404, "", ""))
        assertEquals(false, progressWorkingOf(false, 500, "", "No Session"))
    }

    @Test fun `unknowns observe`() {
        assertNull(progressWorkingOf(false, 503, "", "opencode unavailable"))
        assertNull(progressWorkingOf(false, 500, "", "boom"))
        assertNull(progressWorkingOf(true, 200, "", ""))
        assertNull(progressWorkingOf(true, 200, "weird", ""))
    }
}
