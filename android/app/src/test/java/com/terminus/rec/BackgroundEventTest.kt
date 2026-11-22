package com.terminus.rec

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundEventTest {
    @Test fun parsesSeqAndServerElapsed() {
        val e = OpencodeApi.parseBackground(JSONObject("""{"seq":7,"status":"done","label":"build","detail":"ok","elapsedMs":134000}"""))!!
        assertEquals(7L, e.seq)
        assertEquals(134000L, e.elapsedMs)
        assertEquals("2m 14s", BackgroundUi.elapsedText(e.elapsedMs))
    }

    @Test fun olderServerFramesStillParse() {
        val e = OpencodeApi.parseBackground(JSONObject("""{"status":"started","label":"x"}"""))!!
        assertEquals(0L, e.seq)
        assertEquals(-1L, e.elapsedMs)
        assertNull(OpencodeApi.parseBackground(JSONObject("""{"label":"no status"}""")))
    }
}
