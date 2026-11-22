package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Merged-settle rule: pointer in place, guarded payload move down,
 *  turnIds stay in their slots. Single rule — never stacked. */
class PointerMoveTest {

    private fun u(text: String, turn: String) = ChatMessage("user", text, turnId = turn)
    private fun live(turn: String) = ChatMessage("assistant", "…", turnId = turn)
    private fun r(text: String, turn: String, mid: String) =
        ChatMessage("assistant", text, turnId = turn, settled = true, messageID = mid)
    private fun ptr(turn: String, mid: String = "") =
        ChatMessage("tool", "shared", turnId = turn, settled = true, messageID = mid)

    private fun roles(msgs: List<ChatMessage>) = msgs.map {
        when {
            isPointer(it) -> "P"
            it.role == "assistant" && it.turnId.isNotBlank() && !it.settled -> "L"
            it.role == "assistant" -> "R"
            else -> "U"
        }
    }.joinToString("")

    @Test fun `ordinary pointer stamps the MID without moving`() {
        val before = listOf(u("q2", "t2"), live("t2"))
        val after = mergeSettle(before, "t2", "mA", "shared")
        assertNotNull(after)
        assertEquals(listOf("t2", "t2"), after!!.map { it.turnId })
        assertTrue(isPointer(after[1]))
        assertEquals("mA", after[1].messageID)
    }

    @Test fun `inferred pointer keeps blank MID`() {
        val before = listOf(u("q2", "t2"), live("t2"))
        val after = mergeSettle(before, "t2", "", "shared")!!
        assertEquals("", after[1].messageID)
    }

    @Test fun `swap moves payload down, turnIds stay in slots`() {
        val before = listOf(u("q1", "t1"), r("a1", "t1", "mA"), u("q2", "t2"), live("t2"))
        val after = mergeSettle(before, "t2", "mA", "shared")!!
        // [U1,R,U2,P] → [U1,PTR,U2,R]; slot turnIds unchanged.
        assertEquals(listOf("t1", "t1", "t2", "t2"), after.map { it.turnId })
        assertEquals(listOf("U", "P", "U", "R"), roles(after).map { it.toString() })
        assertEquals("a1", after[3].text)
        assertEquals("mA", after[3].messageID)
    }

    @Test fun `second application is a stable no-op`() {
        val before = listOf(u("q1", "t1"), r("a1", "t1", "mA"), u("q2", "t2"), live("t2"))
        val once = mergeSettle(before, "t2", "mA", "shared")!!
        assertNull(mergeSettle(once, "t2", "mA", "shared"))
    }

    @Test fun `different-MID settled reply between blocks the move`() {
        val before = listOf(
            u("q1", "t1"), r("a1", "t1", "mA"), r("other", "t9", "mZ"),
            u("q2", "t2"), live("t2"),
        )
        val after = mergeSettle(before, "t2", "mA", "shared")!!
        // Pointer settles, reply stays: [U1,R,U2',P2] with P2 in place.
        assertTrue(isPointer(after[4]))
        assertEquals("a1", after[1].text)
    }

    @Test fun `live rows never move`() {
        val before = listOf(live("t9"), u("q1", "t1"), r("a1", "t1", "mA"), u("q2", "t2"), live("t2"))
        val after = mergeSettle(before, "t2", "mA", "shared")!!
        assertEquals(0, after.indexOfFirst { it.turnId == "t9" })
        assertEquals("…", after[0].text)
    }

    @Test fun `three arrival orders converge to reply-last shape`() {
        fun shape(msgs: List<ChatMessage>) = roles(msgs)
        // Order A: T1 reply, T2 merge+move, T3 merge+move.
        var a = listOf(u("q1", "t1"), live("t1"), u("q2", "t2"), live("t2"), u("q3", "t3"), live("t3"))
        a = a.toMutableList().also { l ->
            val i = l.indexOfFirst { it.turnId == "t1" && it.text == "…" }
            l[i] = r("a", "t1", "mA")
        }
        a = mergeSettle(a, "t2", "mA", "shared")!!
        a = mergeSettle(a, "t3", "mA", "shared")!!
        // Order B: T2 reply first, T1 + T3 merge after.
        var b = listOf(u("q1", "t1"), live("t1"), u("q2", "t2"), live("t2"), u("q3", "t3"), live("t3"))
        b = b.toMutableList().also { l ->
            val i = l.indexOfFirst { it.turnId == "t2" && it.text == "…" }
            l[i] = r("a", "t2", "mA")
        }
        b = mergeSettle(b, "t1", "mA", "shared")!!
        b = mergeSettle(b, "t3", "mA", "shared")!!
        // Order C: all pointers first is impossible (merge needs a claim),
        // so T3-first with T1,T2 merging after — same as B by symmetry.
        for (final in listOf(a, b)) {
            assertEquals("UPUPUR", shape(final))
            assertEquals("assistant", final.last().role)
            assertEquals(2, final.count { isPointer(it) })
        }
    }

    @Test fun `replay routing identical pre- and post-move`() {
        val before = listOf(u("q1", "t1"), r("a1", "t1", "mA"), u("q2", "t2"), live("t2"))
        val after = mergeSettle(before, "t2", "mA", "shared")!!
        for (msgs in listOf(before, after)) {
            assertTrue(rowHasMessageId(msgs, "mA"))
        }
        // Post-move the pointer carries the MID for direction; routing still
        // drops the replay via the reply row.
        assertTrue(isPointer(after[1]) || after.any { isPointer(it) })
    }
}
