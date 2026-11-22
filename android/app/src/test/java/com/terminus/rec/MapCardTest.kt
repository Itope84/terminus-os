package com.terminus.rec

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapCardTest {
    @Test fun `map block normalises places and drops bad ones`() {
        val out = MapBridge.normalise("""{"places":[{"name":"Example Station","lat":51.49,"lng":0.069},{"name":"bad","lat":120,"lng":0},{"name":"lon alias","lat":51.5,"lon":-0.1}],"me":true,"zoom":14}""")!!
        val o = JSONObject(out)
        assertEquals(2, o.getJSONArray("places").length())
        assertEquals(-0.1, o.getJSONArray("places").getJSONObject(1).getDouble("lng"), 1e-9)
        assertTrue(o.getBoolean("me"))
        assertEquals(14, o.getInt("zoom"))
    }

    @Test fun `unusable map blocks stay code`() {
        assertNull(MapBridge.normalise("not json"))
        assertNull(MapBridge.normalise("""{"places":[]}"""))
        assertNotNull(MapBridge.normalise("""{"me":true}"""))
    }

    @Test fun `thread renders a map card and escapes its data`() {
        val md = "Here:\n```map\n{\"places\":[{\"name\":\"A \\\"quoted\\\" <b>\",\"lat\":51.5,\"lng\":-0.1}]}\n```"
        val html = ChatMarkdown.bodyHtml(md)
        assertTrue(html.contains("class='mapcard'"))
        assertFalse(html.contains("<b>"))
        assertFalse(html.contains("<pre>"))
        // a broken map block falls back to a code block
        assertTrue(ChatMarkdown.bodyHtml("```map\nnope\n```").contains("<pre>"))
    }

    @Test fun `line and path options survive normalising`() {
        val o = JSONObject(MapBridge.normalise("""{"places":[{"name":"A","lat":51.5,"lng":0.1}],"me":true,"line":true,"path":[[51.5,0.1],[51.51,0.12],[999,0]],"pathLabel":"4.2 km, 12 min"}""")!!)
        assertTrue(o.getBoolean("line"))
        assertEquals(2, o.getJSONArray("path").length())
        assertEquals("4.2 km, 12 min", o.getString("pathLabel"))
        // a path alone is a map
        assertNotNull(MapBridge.normalise("""{"path":[[51.5,0.1],[51.6,0.2]]}"""))
        // line without me is dropped
        assertFalse(JSONObject(MapBridge.normalise("""{"places":[{"lat":1,"lng":1}],"line":true}""")!!).has("line"))
    }
}
