package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Read-aloud chunking: a short first chunk so speech starts quickly, nothing lost, nothing oversized. */
class SpeechChunksTest {
    private val sentence = "This is a sentence of moderate length that keeps going. "

    @Test fun `short text is one chunk`() {
        assertEquals(listOf("Hello there."), ChatMarkdown.speechChunks("Hello there.", 3900))
    }

    @Test fun `first chunk is small and ends at a sentence`() {
        val c = ChatMarkdown.speechChunks(sentence.repeat(60), 3900)
        assertTrue("first chunk ${c[0].length}", c[0].length <= 200)
        assertTrue(c[0].endsWith("."))
        assertTrue(c.drop(1).all { it.length <= 600 })
    }

    @Test fun `no text is lost`() {
        val text = sentence.repeat(40)
        val joined = ChatMarkdown.speechChunks(text, 3900).joinToString(" ")
        assertEquals(text.trim().replace(Regex("\\s+"), " "), joined.replace(Regex("\\s+"), " "))
    }

    @Test fun `never exceeds the engine limit and survives unbroken text`() {
        val c = ChatMarkdown.speechChunks("x".repeat(5000), 1000)
        assertTrue(c.all { it.length <= 1000 })
        assertEquals(5000, c.sumOf { it.length })
    }

    @Test fun `blank input gives no chunks`() {
        assertTrue(ChatMarkdown.speechChunks("   ", 3900).isEmpty())
    }
}
