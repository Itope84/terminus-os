package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTextTest {
    @Test
    fun spokenTextDropsMarkdown() {
        assertEquals("Leave by 08:12 via A127. See Example Park.",
            CarChatsScreen.carText("**Leave by 08:12** via `A127`.\n\nSee [Example Park](https://example.com)."))
        assertEquals("Run this: (code omitted). done", CarChatsScreen.carText("Run this:\n```sh\nls\n```\ndone"))
    }

    @Test
    fun listsHeadingsAndTablesReadAsSentences() {
        val md = """
            ## Today
            - **Dentist** at 10:00
            - Pick up *parcel*
            1. First step
            2) Second step
            > note this
            ---
            | Time | Place |
            |------|-------|
            | 09:00 | Office |
        """.trimIndent()
        assertEquals(
            "Today. Dentist at 10:00. Pick up parcel. 1. First step. 2. Second step. note this. Time, Place. 09:00, Office",
            SpokenText.of(md),
        )
    }

    @Test
    fun urlsCodeAndSymbolsAreNamedNotSpelled() {
        val out = SpokenText.of("Open https://example.com/a?b=1 -> then run `make` ~~old~~ and __bold__ <b>x</b>")
        assertEquals("Open link to then run make old and bold x", out)
    }

    @Test
    fun keepsSnakeCaseAndMathStars() {
        assertEquals("set max_tokens to 2 times 3", SpokenText.of("set max_tokens to 2 * 3"))
    }

    @Test
    fun longRepliesCutAtASentence() {
        val md = (1..60).joinToString(" ") { "Sentence number $it is here." }
        val out = SpokenText.of(md, 200)
        assertTrue(out.endsWith("here. The rest is in the chat."))
        assertTrue(out.length < 240)
        assertFalse(out.contains("…"))
    }

    @Test
    fun carChatShowsUnderEveryBackend() {
        val mk = { id: String, b: String -> ChatSession(id = id, title = id, updatedAt = 1L, backend = b, model = "m") }
        val all = listOf(mk("a", "opencode"), mk("car", "opencode"), mk("b", "claude"))
        assertEquals(listOf("car", "b"), backendScoped(all, "claude", setOf("car")).map { it.id })
    }
}
