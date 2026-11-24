package com.terminus.rec

import com.terminus.rec.StreamPolicy.Handoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who owns the app's one live stream, and when it is released. */
class StreamPolicyTest {

    private fun goal(status: String = "active", loop: Boolean = true) =
        OpencodeApi.GoalInfo(chatId = "c", objective = "ship it", status = status, stuck = 0, continuations = 0, loop = loop)

    // ---- streamWanted ----

    @Test fun `an outstanding background job keeps the stream wanted`() {
        assertTrue(StreamPolicy.streamWanted(hasBackgroundJob = true, goal = null, isOpenThread = false))
    }

    @Test fun `a looping active goal on the open thread keeps the stream wanted`() {
        assertTrue(StreamPolicy.streamWanted(hasBackgroundJob = false, goal = goal(), isOpenThread = true))
    }

    @Test fun `a looping goal on a thread that is not open does not`() {
        assertFalse(StreamPolicy.streamWanted(hasBackgroundJob = false, goal = goal(), isOpenThread = false))
    }

    @Test fun `a paused or finished goal does not`() {
        for (status in listOf("paused", "done", "stuck", "")) {
            assertFalse(status, StreamPolicy.streamWanted(false, goal(status = status), isOpenThread = true))
        }
    }

    @Test fun `a goal that is not a loop does not`() {
        assertFalse(StreamPolicy.streamWanted(false, goal(loop = false), isOpenThread = true))
    }

    @Test fun `no job and no goal means nothing to listen for`() {
        assertFalse(StreamPolicy.streamWanted(false, null, true))
        assertFalse(StreamPolicy.streamWanted(false, null, false))
    }

    @Test fun `a background job counts even when a goal does not`() {
        assertTrue(StreamPolicy.streamWanted(true, goal(status = "paused"), isOpenThread = false))
    }

    // ---- handoff ----

    @Test fun `a chat that still has a reason to listen and owns the stream keeps it`() {
        assertEquals(Handoff.Keep, StreamPolicy.handoff("a", wanted = true, owner = "a", liveChats = listOf("b")))
    }

    @Test fun `a chat that has a reason to listen opens the stream when it does not own it`() {
        assertEquals(Handoff.Open("a"), StreamPolicy.handoff("a", wanted = true, owner = "b", liveChats = emptyList()))
        assertEquals(Handoff.Open("a"), StreamPolicy.handoff("a", wanted = true, owner = null, liveChats = emptyList()))
    }

    @Test fun `wanting to listen beats another chat that is running`() {
        assertEquals(Handoff.Open("a"), StreamPolicy.handoff("a", wanted = true, owner = "b", liveChats = listOf("b", "c")))
    }

    @Test fun `an idle chat hands the stream to the newest other chat with work`() {
        assertEquals(Handoff.Adopt("c"), StreamPolicy.handoff("a", wanted = false, owner = "a", liveChats = listOf("b", "c")))
    }

    @Test fun `the idle chat itself is never adopted`() {
        assertEquals(Handoff.Adopt("b"), StreamPolicy.handoff("a", wanted = false, owner = "a", liveChats = listOf("b", "a")))
        assertEquals(Handoff.Adopt("b"), StreamPolicy.handoff("a", wanted = false, owner = "a", liveChats = listOf("a", "b")))
    }

    @Test fun `with no other chat running the stream sleeps`() {
        assertEquals(Handoff.Sleep, StreamPolicy.handoff("a", wanted = false, owner = "a", liveChats = emptyList()))
        assertEquals(Handoff.Sleep, StreamPolicy.handoff("a", wanted = false, owner = "a", liveChats = listOf("a")))
    }

    // ---- releaseAfterJobFinish ----

    @Test fun `the last job finishing in the chat that owns an otherwise idle stream releases it`() {
        assertTrue(StreamPolicy.releaseAfterJobFinish(hasJobsLeft = false, ownsStream = true, replyRunning = false))
    }

    @Test fun `a finishing job never releases the stream while a reply is running in that chat`() {
        // The bug this guards: the reply's done rides this stream, so dropping it hid the reply until the next send.
        assertFalse(StreamPolicy.releaseAfterJobFinish(hasJobsLeft = false, ownsStream = true, replyRunning = true))
    }

    @Test fun `other jobs still outstanding keep the stream`() {
        assertFalse(StreamPolicy.releaseAfterJobFinish(hasJobsLeft = true, ownsStream = true, replyRunning = false))
    }

    @Test fun `a chat that does not own the stream has nothing to release`() {
        assertFalse(StreamPolicy.releaseAfterJobFinish(hasJobsLeft = false, ownsStream = false, replyRunning = false))
    }

    @Test fun `only one combination releases the stream`() {
        var releasing = 0
        for (jobs in listOf(true, false)) for (owns in listOf(true, false)) for (running in listOf(true, false)) {
            if (StreamPolicy.releaseAfterJobFinish(jobs, owns, running)) releasing++
        }
        assertEquals(1, releasing)
    }
}
