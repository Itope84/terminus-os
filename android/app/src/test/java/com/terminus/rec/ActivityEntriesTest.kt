package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Activity-sheet model: headers, order, empties. */
class ActivityEntriesTest {

    @Test fun `unread heads under its header, events follow`() {
        val rows = activityEntries(
            listOf(Triple("c1", "Triage", 2)),
            listOf(Triple("price", "BTC above", 100L)),
        )
        assertEquals(4, rows.size)
        assertEquals(ActivityEntry.Header("Unread"), rows[0])
        assertEquals(ActivityEntry.Chat("c1", "Triage", 2), rows[1])
        assertEquals(ActivityEntry.Header("Activity"), rows[2])
        assertEquals(ActivityEntry.Event(0, "price", "BTC above", 100L), rows[3])
    }

    @Test fun `chats only has no activity header`() {
        val rows = activityEntries(listOf(Triple("c1", "Triage", 1)), emptyList())
        assertEquals(2, rows.size)
        assertTrue(rows.none { it is ActivityEntry.Header && it.title == "Activity" })
    }

    @Test fun `events only has no unread header`() {
        val rows = activityEntries(emptyList(), listOf(Triple("j", "p", 1L)))
        assertEquals(2, rows.size)
        assertTrue(rows.none { it is ActivityEntry.Header && it.title == "Unread" })
    }

    @Test fun `empty stays empty`() {
        assertTrue(activityEntries(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `event indices track source order`() {
        val rows = activityEntries(
            emptyList(),
            listOf(Triple("a", "pa", 3L), Triple("b", "pb", 2L)),
        )
        assertEquals(ActivityEntry.Event(0, "a", "pa", 3L), rows[1])
        assertEquals(ActivityEntry.Event(1, "b", "pb", 2L), rows[2])
    }
}
