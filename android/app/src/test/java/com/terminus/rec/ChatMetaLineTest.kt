package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMetaLineTest {
    @Test fun quietChatsHideTheLine() {
        assertEquals("", chatMetaLine(0, "", 40, 0))
        assertEquals("", chatMetaLine(0, "   ", 74, 0))
    }

    @Test fun onlyActionableThingsShowInOrder() {
        assertEquals("1 waiting to send · Draft: book the MOT", chatMetaLine(1, "book the MOT", 90, 2))
        assertEquals("context 84% · 2 files", chatMetaLine(0, "", 84, 2))
        assertEquals("1 file", chatMetaLine(0, "", 10, 1))
    }

    @Test fun longDraftsAreCutOnOneLine() {
        val line = chatMetaLine(0, "a very long draft\nthat goes on and on about many many things", 0, 0)
        assertEquals("Draft: a very long draft that goes on and on ab…", line)
    }
}
