package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** "Outcome unknown" row cleanup: the row is written with role "system"
 *  today, "tool" was an older shape. Cleanup must match both, or a settled
 *  turn leaves its row behind and stale rows stack up (the duplicate
 *  "no reply yet" the user saw). */
class UnknownRowTest {
    private fun unknownRow(role: String, turn: String) =
        ChatMessage(role, "no reply yet, still trying. [retry](terminus://retry?chat=c&turn=$turn)")

    @Test fun systemRoleRowMatchesItsTurn() {
        assertTrue(isUnknownRow(unknownRow("system", "t1"), "t1"))
    }

    @Test fun legacyToolRoleRowStillMatches() {
        assertTrue(isUnknownRow(unknownRow("tool", "t1"), "t1"))
    }

    @Test fun otherTurnsRowIsLeftAlone() {
        assertFalse(isUnknownRow(unknownRow("system", "t2"), "t1"))
    }

    @Test fun otherSystemRowsAreNotTouched() {
        val note = ChatMessage("system", "session gone")
        assertFalse(isUnknownRow(note, "t1"))
        val reply = ChatMessage("assistant", "no reply yet, still trying.")
        assertFalse(isUnknownRow(reply, "t1"))
    }

    @Test fun wordingPrefixStillCoversBothShapes() {
        val still = ChatMessage("system", "still no reply. [retry](terminus://retry?chat=c&turn=t9)")
        assertTrue(isUnknownRow(still, "t9"))
    }

    /** Current wording: a terminal failure statement, not "still trying". */
    @Test fun failureWordingMatches() {
        val failed = ChatMessage("system",
            "no reply — turn failed. [retry](terminus://retry?chat=c&turn=t3)")
        assertTrue(isUnknownRow(failed, "t3"))
    }

    /** Turn-agnostic test behind the one-row-per-chat rule: every shipped
     *  wording counts, so a new failure replaces the previous row. */
    @Test fun everyWordingIsRecognisedForTheSingleRowRule() {
        assertTrue(isUnknownText(ChatMessage("system", "no reply — turn failed. turn=t3")))
        assertTrue(isUnknownText(ChatMessage("system", "no reply yet, still trying. turn=t3")))
        assertTrue(isUnknownText(ChatMessage("system", "still no reply. turn=t3")))
        assertFalse(isUnknownText(ChatMessage("system", "session gone")))
    }
}
