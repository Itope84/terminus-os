package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Late-done upgrade: a kept partial for the same turn takes the full
 *  text in place (one row, never two). */
class UpgradeTurnTest {

    private fun row(text: String, turn: String) =
        ChatMessage("assistant", text, turnId = turn, settled = true)

    @Test fun `partial upgrades to full`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(0, upgradeTurnIndex(msgs, "t1", "Hello world"))
    }

    @Test fun `equal text is not an upgrade`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(-1, upgradeTurnIndex(msgs, "t1", "Hello"))
    }

    @Test fun `unrelated text never overwrites`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(-1, upgradeTurnIndex(msgs, "t1", "Something else entirely"))
    }

    @Test fun `unknown turn has nowhere to upgrade`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(-1, upgradeTurnIndex(msgs, "t2", "Hello world"))
    }

    @Test fun `live rows are not upgrade targets`() {
        val msgs = listOf(ChatMessage("assistant", "Hello", turnId = "t1", settled = false))
        assertEquals(-1, upgradeTurnIndex(msgs, "t1", "Hello world"))
    }

    @Test fun `blanks never match`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(-1, upgradeTurnIndex(msgs, "", "Hello world"))
        assertEquals(-1, upgradeTurnIndex(msgs, "t1", ""))
        assertEquals(-1, upgradeTurnIndex(emptyList(), "t1", "Hello world"))
    }

    @Test fun `whitespace variant still upgrades`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(0, upgradeTurnIndex(msgs, "t1", "Hello world  \n"))
    }

    @Test fun `stale partial superseded by merged reply`() {
        val msgs = listOf(row("Hello", "t1"), row("Hello world, full", "t9"))
        assertEquals(0, stalePartialIndex(msgs, "t1", "Hello world, full"))
    }

    @Test fun `stale partial needs strict prefix`() {
        val msgs = listOf(row("Hello", "t1"))
        assertEquals(-1, stalePartialIndex(msgs, "t1", "Something else"))
        assertEquals(-1, stalePartialIndex(msgs, "t1", "Hello"))
        assertEquals(-1, stalePartialIndex(msgs, "t2", "Hello world"))
        assertEquals(-1, stalePartialIndex(msgs, "t1", ""))
        assertEquals(-1, stalePartialIndex(msgs, "", "Hello world"))
    }
}
