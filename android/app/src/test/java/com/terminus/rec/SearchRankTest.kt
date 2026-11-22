package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Search ranking: title matches outrank body matches, sort kept in-band. */
class SearchRankTest {

    private fun s(id: String, title: String, updatedAt: Long, body: String = "x") = ChatSession(
        id = id,
        title = title,
        updatedAt = updatedAt,
        model = "",
        messages = mutableListOf(ChatMessage("assistant", body, settled = true)),
    )

    @Test fun `title match beats newer body match`() {
        val body = s("b", "unrelated", 2000L, "has triage notes inside")
        val titled = s("t", "Terminus triage", 1000L)
        assertEquals(
            listOf("t", "b"),
            SessionAdapter.rankSearch(listOf(body, titled), "triage", "newest").map { it.id },
        )
    }

    @Test fun `title prefix beats title substring`() {
        val sub = s("s", "my terminus notes", 3000L)
        val pre = s("p", "Terminus triage", 1000L)
        assertEquals(
            listOf("p", "s"),
            SessionAdapter.rankSearch(listOf(sub, pre), "term", "newest").map { it.id },
        )
    }

    @Test fun `blank query keeps plain sort`() {
        val a = s("a", "b title", 1000L)
        val b = s("b", "a title", 2000L)
        assertEquals(
            listOf("b", "a"),
            SessionAdapter.rankSearch(listOf(a, b), "", "newest").map { it.id },
        )
    }

    @Test fun `bands keep name sort`() {
        val t2 = s("t2", "triage zeta", 1000L)
        val t1 = s("t1", "triage alpha", 1000L)
        assertEquals(
            listOf("t1", "t2"),
            SessionAdapter.rankSearch(listOf(t2, t1), "triage", "name").map { it.id },
        )
    }
}
