package com.terminus.rec

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteDraftTest {
    @Test fun noQuotesKeepsText() = assertEquals("hi", QuoteDraft.wire(emptyList(), "hi"))

    @Test fun eachQuoteCarriesItsNoteThenTheMessage() {
        val w = QuoteDraft.wire(listOf(QuoteItem("Monitor,", "foobar"), QuoteItem("webcam")), "thoughts?")
        assertEquals("> Monitor,\nfoobar\n\n> webcam\n\nthoughts?", w)
    }

    @Test fun quotesAloneAreSendable() = assertEquals("> a\nb", QuoteDraft.wire(listOf(QuoteItem("a", "b")), ""))

    @Test fun multilineExcerptStaysQuoted() =
        assertEquals("> one\n> two", QuoteDraft.wire(listOf(QuoteItem(QuoteDraft.clean("  one  \n\n two "))), ""))
}
