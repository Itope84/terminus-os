package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryContextTest {
    private fun u(t: String, turn: String) = ChatMessage("user", t, turnId = turn)
    private fun reply(t: String, turn: String) = ChatMessage("assistant", t, turnId = turn, settled = true)
    private fun delivery(t: String) = ChatMessage("assistant", t, settled = true)

    @Test fun deliveriesSinceLastUserMessageOnly() {
        val msgs = listOf(u("hi", "t1"), reply("hello", "t1"), delivery("old brief"), u("thanks", "t2"), reply("np", "t2"),
            delivery("Brief: car insurance ends today"), ChatMessage("event", "needs you\nApprove the order"), u("do that", "t3"))
        assertEquals(listOf("Brief: car insurance ends today", "needs you\nApprove the order"), DeliveryContext.collect(msgs, "t3"))
    }

    @Test fun ownRepliesAndPlaceholdersAreNotDeliveries() {
        val msgs = listOf(u("q", "t1"), reply("answer", "t1"), ChatMessage("assistant", "…"), u("more", "t2"))
        assertEquals(emptyList<String>(), DeliveryContext.collect(msgs, "t2"))
    }

    @Test fun keepsNewestThreeAndCaps() {
        val msgs = (1..5).map { delivery("d$it") } + u("go", "t9")
        assertEquals(listOf("d3", "d4", "d5"), DeliveryContext.collect(msgs, "t9"))
        val wrapped = DeliveryContext.wrap(listOf("x".repeat(5000)), "go")
        assertTrue(wrapped.length < 3200)
        assertTrue(wrapped.endsWith("\n\ngo"))
        assertEquals("plain", DeliveryContext.wrap(emptyList(), "plain"))
    }
}
