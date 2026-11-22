package com.terminus.rec

/** One quoted passage staged in the composer, with the user's optional note on it. */
data class QuoteItem(val excerpt: String, var comment: String = "")

object QuoteDraft {
    const val MAX_EXCERPT = 600
    const val MAX_QUOTES = 8

    /** Selection text as it is quoted: whitespace runs inside a line collapse, blank lines drop, capped. */
    fun clean(raw: String): String =
        raw.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
            .joinToString("\n").take(MAX_EXCERPT).trim()

    /** Wire text: each quote as a markdown blockquote followed by its note, then the message itself. */
    fun wire(quotes: List<QuoteItem>, text: String): String {
        if (quotes.isEmpty()) return text
        val parts = quotes.map { q ->
            val block = q.excerpt.lines().joinToString("\n") { "> $it" }
            if (q.comment.isBlank()) block else "$block\n${q.comment.trim()}"
        }.toMutableList()
        if (text.isNotBlank()) parts.add(text)
        return parts.joinToString("\n\n")
    }
}
