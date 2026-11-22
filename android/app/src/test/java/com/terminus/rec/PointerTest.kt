package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Pointer rows: structural predicate, durable merge key, replacer safety. */
class PointerTest {

    private fun ptr(turn: String) =
        ChatMessage("tool", "answered together with the shared reply", turnId = turn, settled = true)

    @Test fun `isPointer is structural, never textual`() {
        assertTrue(isPointer(ptr("t1")))
        assertFalse(isPointer(ChatMessage("event", "interrupted")))
        assertFalse(isPointer(ChatMessage("tool", "answered together with the shared reply")))
        assertFalse(isPointer(ChatMessage("assistant", "x", turnId = "t1", settled = true)))
        assertFalse(isPointer(ChatMessage("user", "x", turnId = "t1")))
    }

    @Test fun `turnRendered counts replies and pointers only`() {
        val msgs = listOf(
            ChatMessage("user", "q", turnId = "t1"),
            ptr("t2"),
            ChatMessage("assistant", "…", turnId = "t3"),
            ChatMessage("assistant", "done", turnId = "t4", settled = true),
        )
        assertFalse(turnRendered(msgs, "t1"))
        assertTrue(turnRendered(msgs, "t2"))
        assertFalse(turnRendered(msgs, "t3"))
        assertTrue(turnRendered(msgs, "t4"))
        assertFalse(turnRendered(msgs, ""))
        assertFalse(turnRendered(emptyList(), "t1"))
    }

    @Test fun `rowHasMessageId matches stamped rows`() {
        val msgs = listOf(
            ChatMessage("assistant", "a", turnId = "t1", settled = true, messageID = "m1"),
            ptr("t2"),
        )
        assertTrue(rowHasMessageId(msgs, "m1"))
        assertFalse(rowHasMessageId(msgs, "m2"))
        assertFalse(rowHasMessageId(msgs, ""))
        assertFalse(rowHasMessageId(emptyList(), "m1"))
    }

    @Test fun `rowHasMessageId ignores pointer-held MIDs`() {
        val msgs = listOf(
            ChatMessage("tool", "shared", turnId = "t2", settled = true, messageID = "mA"),
        )
        assertFalse(rowHasMessageId(msgs, "mA"))
    }

    @Test fun `delete-reply golden upgrades, never drops`() {
        // Reply row deleted; pointer holds the MID; dead partial kept.
        // Redelivered full done must UPGRADE the partial, not DROP.
        val partial = ChatMessage("assistant", "Hello", turnId = "t1", settled = true)
        val pointer = ChatMessage("tool", "shared", turnId = "t2", settled = true, messageID = "mA")
        val msgs = listOf(partial, pointer)
        val full = "Hello world"
        assertFalse(rowHasMessageId(msgs, "mA"))
        val route = TurnActivity.lateDoneRoute(
            messageIdClaimed = false,
            upgradeApplies = upgradeTurnIndex(msgs, "t1", full) >= 0,
            sameTurnRendered = turnRendered(msgs, "t1"),
            messageIdPresent = true,
            textRendered = false,
        )
        assertEquals(LateDone.UPGRADE, route)
    }

    @Test fun `differential blanking pointer MIDs changes no route`() {
        // Same redelivery evaluated with pointer MIDs present vs blanked
        // must route identically (pointers never decide).
        val reply = ChatMessage("assistant", "done text", turnId = "t1", settled = true, messageID = "mA")
        val withMid = listOf(reply, ChatMessage("tool", "shared", turnId = "t2", settled = true, messageID = "mA"))
        val blanked = listOf(reply, ChatMessage("tool", "shared", turnId = "t2", settled = true))
        for (msgs in listOf(withMid, blanked)) {
            assertTrue(rowHasMessageId(msgs, "mA"))
        }
        // And a pointer-only MID decides nothing either way.
        val orphan = listOf(ChatMessage("tool", "shared", turnId = "t9", settled = true, messageID = "mZ"))
        assertFalse(rowHasMessageId(orphan, "mZ"))
        assertFalse(rowHasMessageId(orphan.map { it.copy(messageID = "") }, "mZ"))
    }

    @Test fun `stale partial ignores pointer rows`() {
        val msgs = listOf(ptr("t1"))
        assertEquals(-1, stalePartialIndex(msgs, "t1", "unrelated full text"))
        assertEquals(-1, upgradeTurnIndex(msgs, "t1", "anything"))
    }
}
