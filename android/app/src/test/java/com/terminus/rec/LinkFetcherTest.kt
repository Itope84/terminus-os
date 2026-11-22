package com.terminus.rec

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** The phone-side title lookup: what it may touch, what it refuses to fetch, and how it reads a page. */
class LinkFetcherTest {
    private fun blocked(ip: String) = LinkFetcher.isBlocked(InetAddress.getByName(ip))

    @Test fun `private loopback link-local and tailnet addresses are blocked`() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.1", "169.254.169.254",
            "100.64.1.2", "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1"))
            assertTrue(ip, blocked(ip))
    }

    @Test fun `public addresses are allowed`() {
        for (ip in listOf("8.8.8.8", "93.184.216.34", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "2606:4700::1111"))
            assertFalse(ip, blocked(ip))
    }

    @Test fun `only plain web urls on default ports are previewable`() {
        assertNotNull(LinkFetcher.previewable("https://example.com/a?b=1"))
        assertNotNull(LinkFetcher.previewable("http://example.com:80/x"))
        for (bad in listOf("ftp://example.com", "file:///etc/passwd", "https://user:pw@example.com", "https://example.com:8443/",
            "http://localhost/", "http://printer.local/", "javascript:alert(1)", "", "not a url"))
            assertNull(bad, LinkFetcher.previewable(bad))
    }

    private fun action(u: String) = LinkFetcher.looksActionable(u.toHttpUrl())

    @Test fun `links a GET could act on, and files, are not fetched`() {
        for (u in listOf("https://x.com/unsubscribe?u=1", "https://x.com/confirm/abc", "https://x.com/verify-email", "https://x.com/login/magic",
            "https://x.com/a?token=abc", "https://x.com/a?x=1&sig=zz", "https://x.com/setup.apk", "https://x.com/app.xapk", "https://x.com/p/report.pdf",
            "https://x.com/get?action=delete", "https://x.com/download/latest"))
            assertTrue(u, action(u))
    }

    @Test fun `ordinary article links are fetched`() {
        for (u in listOf("https://www.example.org/news/sample-story.html", "https://example.com/", "https://share.google/RxeGVycbWk9gchBo4",
            "https://en.wikipedia.org/wiki/Link_preview?utm_source=x"))
            assertFalse(u, action(u))
    }

    @Test fun `parseMeta prefers og fields and decodes entities`() {
        val m = LinkFetcher.parseMeta("""<html><head><title>Fallback</title>
            <meta property="og:title" content="Example Corp says Jane Doe left &amp; launches probe">
            <meta property="og:site_name" content="Example News"></head></html>""", "https://www.example.org/x")
        assertEquals("Example Corp says Jane Doe left & launches probe", m.title)
        assertEquals("Example News", m.site)
    }

    @Test fun `parseMeta falls back to the title tag and the host`() {
        val m = LinkFetcher.parseMeta("<title>  Hello\n world </title>", "https://www.example.com/p")
        assertEquals("Hello world", m.title)
        assertEquals("example.com", m.site)
    }
}
