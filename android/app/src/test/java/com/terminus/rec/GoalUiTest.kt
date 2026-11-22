package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Goal strip text: hidden without a goal, short labeled otherwise. */
class GoalUiTest {

    private fun g(status: String, loop: Boolean = true, cont: Int = 0) = OpencodeApi.GoalInfo(
        chatId = "c", objective = "cut the video and stage the link",
        status = status, stuck = 0, continuations = cont, loop = loop,
    )

    @Test fun `null goal hides the strip`() {
        assertNull(GoalUi.plainText(null))
    }

    @Test fun `strip shows objective with no state words`() {
        val t = GoalUi.plainText(g("blocked")) ?: ""
        assertEquals("cut the video and stage the link", t)
        assertFalse(t.contains("["))
    }

    @Test fun `manage sub carries counts`() {
        assertEquals("Goal looping", GoalUi.sheetTitle(g("active", cont = 2)))
        assertEquals("Goal blocked", GoalUi.sheetTitle(g("blocked")))
        assertEquals("Goal on", GoalUi.sheetTitle(g("active", loop = false)))
        assertEquals("Goal paused", GoalUi.sheetTitle(g("paused")))
        assertEquals("Goal done", GoalUi.sheetTitle(g("complete")))
        assertEquals("loop on", GoalUi.manageSub(g("active")))
        assertEquals("loop 2, stuck 0", GoalUi.manageSub(g("active", cont = 2)))
        assertEquals("stuck 3, needs you", GoalUi.manageSub(g("blocked").copy(stuck = 3)))
        assertEquals("judged each turn", GoalUi.manageSub(g("active", loop = false)))
    }
}
