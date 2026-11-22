package com.terminus.rec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private fun chat(updated: Long, archivedAt: Long = 0, pinned: Boolean = false, unread: Int = 0) =
        ChatSession(id = "s-1", title = "t", updatedAt = updated, model = "", pinned = pinned, archivedAt = archivedAt, unread = unread)

    @Test
    fun quietChatsArchiveAfterThirtyDays() {
        assertFalse(SessionAdapter.isArchived(chat(now - 29 * day), now, false))
        assertTrue(SessionAdapter.isArchived(chat(now - 31 * day), now, false))
    }

    @Test
    fun pinnedUnreadAndSchedulesNeverArchive() {
        assertFalse(SessionAdapter.isArchived(chat(now - 90 * day, pinned = true), now, false))
        assertFalse(SessionAdapter.isArchived(chat(now - 90 * day, unread = 2), now, false))
        assertFalse(SessionAdapter.isArchived(chat(now - 90 * day), now, true))
    }

    @Test
    fun handArchiveHoldsUntilNewActivity() {
        assertTrue(SessionAdapter.isArchived(chat(now - day, archivedAt = now - 10), now, false))
        assertFalse(SessionAdapter.isArchived(chat(now, archivedAt = now - 10), now, false), "a new message brings it back")
    }

    @Test
    fun moveToRecentRestartsTheClock() {
        assertFalse(SessionAdapter.isArchived(chat(now - 90 * day, archivedAt = -(now - day)), now, false))
        assertTrue(SessionAdapter.isArchived(chat(now - 90 * day, archivedAt = -(now - 31 * day)), now, false))
    }

    private fun assertFalse(v: Boolean, msg: String) = org.junit.Assert.assertFalse(msg, v)
}
