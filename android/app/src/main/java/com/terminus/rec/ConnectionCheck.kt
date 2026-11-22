package com.terminus.rec

/** Input hygiene and plain-language errors for the server connection fields in Settings. Pure. */
object ConnectionCheck {
    private val urlShape = Regex("^https?://[^\\s/]+(/\\S*)?$", RegexOption.IGNORE_CASE)

    /** Trimmed address without trailing slashes, or null when it is not a usable http(s) URL. */
    fun normalizeUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        return if (urlShape.matches(t)) t else null
    }

    /** Short reason a connection test failed, for people (never includes the password). */
    fun describe(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            "http 401" in msg || "http 403" in msg -> "wrong password"
            "http 404" in msg -> "that address is not a Terminus server"
            "http 5" in msg -> "the server is having trouble ($msg)"
            e is java.net.UnknownHostException -> "address not found"
            e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "can't reach it"
            e is javax.net.ssl.SSLException -> "secure connection failed"
            else -> msg.ifBlank { "network error" }
        }
    }

    /** "host:port" for summaries; falls back to the raw text. */
    fun hostOf(url: String): String {
        val t = url.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        return t.ifBlank { url.trim() }
    }
}
