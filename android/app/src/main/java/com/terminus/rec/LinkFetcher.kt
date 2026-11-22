package com.terminus.rec

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Title lookup for link chips, run on the phone the way WhatsApp does it on the sender's device: no server ever
 *  fetches a link a model wrote. The fetch can only read: it sends no cookies or credentials, resolves the host
 *  itself and refuses private / loopback / link-local / tailnet addresses at connect time (so a redirect or DNS trick
 *  cannot reach the home network), follows redirects by hand under the same rules, reads at most 256 KB, and only
 *  of an HTML response. The body is held in memory and parsed as text: never saved, executed or rendered. */
object LinkFetcher {
    data class Meta(val url: String, val title: String, val site: String)

    private const val MAX_BYTES = 256_000L
    private const val MAX_REDIRECTS = 5
    private const val MAX_URL = 2000

    /** Pure: addresses a preview fetch must never touch. */
    internal fun isBlocked(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address) {
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            return x == 0 || x >= 224 || (x == 100 && y in 64..127)   // "this" network, reserved, CGNAT incl. the tailnet
        }
        if (a is Inet6Address) return (b[0].toInt() and 0xfe) == 0xfc   // unique-local fc00::/7
        return true
    }

    /** Pure: only plain web URLs on the default ports, no credentials in the URL, no obviously local names. */
    internal fun previewable(raw: String): HttpUrl? {
        if (raw.isBlank() || raw.length > MAX_URL) return null
        val u = raw.toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        if (u.port != (if (u.isHttps) 443 else 80)) return null
        val h = u.host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")) return null
        return u
    }

    private val FILE_EXT = Regex("\\.(apk|xapk|apks|aab|ipa|exe|msi|dmg|pkg|deb|rpm|zip|rar|7z|gz|tgz|bz2|iso|jar|bin|sh|bat|cmd|ps1|scr|dll|torrent|mp3|mp4|mov|mkv|avi|wav|pdf|docx?|xlsx?|pptx?)$", RegexOption.IGNORE_CASE)
    private val ACTION_PATH = Regex("(unsubscribe|opt-?out|confirm|verify|activate|invite|login|log-?in|sign-?in|sign-?up|magic|reset|logout|log-?out|delete|remove|cancel|approve|authorize|download|redeem|claim|accept)", RegexOption.IGNORE_CASE)
    private val ACTION_QUERY = Regex("(^|&)(token|key|code|sig|signature|auth|session|otp|nonce|ticket|secret|access_token|id_token|hash)=", RegexOption.IGNORE_CASE)

    /** Pure: links a plain GET could change something behind, or that are files. A preview is a GET, so these stay
     *  as plain links the person taps on purpose: one-time sign-in and confirm links, unsubscribe, downloads. */
    internal fun looksActionable(u: HttpUrl): Boolean {
        val path = u.encodedPath
        return FILE_EXT.containsMatchIn(path) || ACTION_PATH.containsMatchIn(path) ||
            (u.encodedQuery?.let { ACTION_QUERY.containsMatchIn(it) || ACTION_PATH.containsMatchIn(it) } ?: false)
    }

    private val guardedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (all.isEmpty() || all.any { isBlocked(it) }) throw UnknownHostException("blocked address")
            return all
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(guardedDns)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** The titled preview, or null (no title, blocked, actionable, down). Never throws. */
    fun fetch(raw: String): Meta? {
        var u = previewable(raw) ?: return null
        if (looksActionable(u)) return null
        for (hop in 0..MAX_REDIRECTS) {
            val req = Request.Builder().url(u)
                .header("User-Agent", "Mozilla/5.0 (compatible; TerminusPreview/1.0)")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                .get().build()
            try {
                client.newCall(req).execute().use { res ->
                    if (res.code in 300..399) {
                        val loc = res.header("Location") ?: return null
                        val next = res.request.url.resolve(loc)?.let { previewable(it.toString()) } ?: return null
                        if (looksActionable(next)) return null
                        u = next
                        return@use
                    }
                    if (!res.isSuccessful) return null
                    val type = res.header("Content-Type").orEmpty().lowercase()
                    if (!type.contains("html")) return null   // anything else (a file, an image): close without reading
                    val body = res.body ?: return null
                    val src = body.source()
                    src.request(MAX_BYTES)
                    val text = src.buffer.snapshot().utf8()
                    val m = parseMeta(text.take(MAX_BYTES.toInt()), u.toString())
                    return if (m.title.isBlank()) null else m
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…",
        "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“")

    private fun decode(s: String): String =
        s.replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16).coerceIn(0, 0x10FFFF))) }
            .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt().coerceIn(0, 0x10FFFF))) }
            .replace(Regex("&([a-zA-Z]+);")) { ENTITIES[it.groupValues[1].lowercase()] ?: it.value }
            .replace(Regex("\\s+"), " ").trim()

    private fun metaContent(html: String, keys: List<String>): String {
        for (m in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            val key = Regex("\\b(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            if (key !in keys) continue
            val c = Regex("\\bcontent\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
            val v = decode(c?.groupValues?.get(1)?.ifEmpty { c.groupValues[2] } ?: "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** Pure: title and site name out of a page's markup. */
    internal fun parseMeta(html: String, finalUrl: String): Meta {
        val titleTag = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: ""
        val title = metaContent(html, listOf("og:title", "twitter:title")).ifEmpty { decode(titleTag) }
        val host = finalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: ""
        val site = metaContent(html, listOf("og:site_name")).ifEmpty { host }
        return Meta(finalUrl, title.take(200), site.take(80))
    }
}
