package com.terminus.rec

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenEventsTest {
    @Test fun parsesPartialsAndFinalsAndSkipsJunk() {
        val arr = JSONArray("""[{"type":"partial","t":1.5,"text":" book the "},{"type":"final","t":1.5,"dur":2,"text":"Book the table."},{"type":"other","text":"x"},{"type":"final","text":"  "}]""")
        val got = parseListenEvents(arr)
        assertEquals(listOf(OpencodeApi.ListenEvent(false, 1.5, "book the"), OpencodeApi.ListenEvent(true, 1.5, "Book the table.")), got)
        assertEquals(emptyList<OpencodeApi.ListenEvent>(), parseListenEvents(null))
    }
}
