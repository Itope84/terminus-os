package com.terminus.rec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotifyDedupeTest {
    @Test
    fun oneReplyPingPerChatPerMinute() {
        assertTrue(ChatNotify.firstTurnPing("dedupe-a", now = 1_000_000))
        assertFalse(ChatNotify.firstTurnPing("dedupe-a", now = 1_030_000)) // app stream + service saw the same reply
        assertTrue(ChatNotify.firstTurnPing("dedupe-b", now = 1_030_000)) // another chat is its own ping
        assertTrue(ChatNotify.firstTurnPing("dedupe-a", now = 1_061_000)) // a later reply pings again
    }
}
