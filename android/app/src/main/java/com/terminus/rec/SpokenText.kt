package com.terminus.rec

/** Markdown turned into text a voice can read: what the car shows and speaks. Lists, tables, headings and
 *  emphasis lose their symbols; each line becomes its own sentence so the voice pauses between items; code
 *  and URLs are named instead of spelled out. Pure, unit-tested. */
object SpokenText {
    private val FENCE = Regex("```[\\s\\S]*?(```|$)")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val URL = Regex("<?https?://[^\\s)>]+>?")
    private val TAG = Regex("</?[a-zA-Z][^>]*>")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
    private val STRIKE = Regex("~~(.+?)~~")
    private val ITALIC_STAR = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")
    private val ITALIC_UNDER = Regex("(?<![\\w_])_(?!\\s)([^_\\n]+?)(?<!\\s)_(?![\\w_])")
    private val RULE = Regex("^\\s*([-*_]\\s*){3,}$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("^\\s*(>\\s?)+")
    private val BULLET = Regex("^\\s*[-*+•]\\s+(\\[[ xX]]\\s+)?")
    private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+")

    fun of(md: String, max: Int = 2000): String {
        var t = md.replace("\r\n", "\n")
            .replace(FENCE, "\n(code omitted)\n")
            .replace(IMAGE, "$1")
            .replace(LINK, "$1")
            .replace(URL, "link")
            .replace(TAG, "")
            .replace(INLINE_CODE, "$1")
        val sentences = t.split("\n").mapNotNull { raw ->
            var line = raw
            if (RULE.matches(line) || TABLE_SEP.matches(line)) return@mapNotNull null
            if (line.trim().startsWith("|") || line.count { it == '|' } >= 2) {
                line = line.trim().trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            }
            line = line.replace(HEADING, "").replace(QUOTE, "").replace(BULLET, "")
                .replace(NUMBERED, "$1. ")
            line = line.replace(BOLD, "$2").replace(STRIKE, "$1").replace(ITALIC_STAR, "$1").replace(ITALIC_UNDER, "$1")
                .replace(Regex("\\*{2,}|~~"), "")
                .replace(Regex("(?<=\\d) \\* (?=\\d)"), " times ")
                .replace(Regex("(?<=\\s|^)[*#]+(?=\\s|$)"), "")
                .replace(" -> ", " to ").replace("→", " to ")
                .replace(Regex("\\s+"), " ").trim()
            line.ifEmpty { null }
        }
        t = sentences.mapIndexed { i, s ->
            if (i < sentences.size - 1 && s.last() !in ".!?:;,…") "$s." else s
        }.joinToString(" ")
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return (if (end > max / 2) cut.take(end + 1) else cut.trimEnd() + "…") + " The rest is in the chat."
    }
}
