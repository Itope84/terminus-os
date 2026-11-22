package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Emails and phone numbers in a reply become tap-to-mail / tap-to-dial links; look-alikes stay text. */
class ContactLinksTest {
    private fun html(text: String) = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("assistant", text)))

    @Test fun `email becomes a mailto link`() {
        val out = html("Write to john_smith@example.co.uk today.")
        assertTrue(out, out.contains("<a href=\"mailto:john_smith@example.co.uk\">john_smith@example.co.uk</a>"))
    }

    @Test fun `uk national and international numbers become tel links`() {
        val out = html("Call 01268 123456 or +44 7700 900123.")
        assertTrue(out, out.contains("<a href=\"tel:01268123456\">01268 123456</a>"))
        assertTrue(out, out.contains("<a href=\"tel:+447700900123\">+44 7700 900123</a>"))
    }

    @Test fun `look-alikes stay text`() {
        val out = html("Order 0123456789012, date 2026-11-15, price 1,234,567.89, ref 12345.")
        assertFalse(out, out.contains("tel:"))
        assertFalse(out, out.contains("mailto:"))
    }

    @Test fun `existing links are left alone and real code stays code`() {
        val out = html("See [mail me](mailto:a@b.com) and https://x.com/a@b.com and `call(07700 900123)`")
        assertEquals(out, 1, out.split("mailto:").size - 1)
        assertFalse(out, out.contains("tel:"))
        assertTrue(out, out.contains("<code>call(07700 900123)</code>"))
    }

    @Test fun `backticked contacts still become links`() {
        val out = html("Ring `01268 123456`, mail `a_b@x.com` or open `https://example.com/a?x=1&y=2`.")
        assertTrue(out, out.contains("<a href=\"tel:01268123456\">01268 123456</a>"))
        assertTrue(out, out.contains("<a href=\"mailto:a_b@x.com\">a_b@x.com</a>"))
        assertTrue(out, out.contains("href=\"https://example.com/a?x=1&amp;y=2\">"))
        assertFalse(out, out.contains("<code>"))
    }

    @Test fun `a backticked id is still code`() {
        assertTrue(html("order `0123456789012`").contains("<code>0123456789012</code>"))
    }

    @Test fun `explicit tel markdown link renders`() {
        val out = html("[Call the garage](tel:+441268123456)")
        assertTrue(out, out.contains("<a href=\"tel:+441268123456\">Call the garage</a>"))
    }

    @Test fun `tap targets the webview cannot load are recognised`() {
        assertTrue(isContactUrl("tel:+447700900123"))
        assertTrue(isContactUrl("mailto:a@b.com"))
        assertTrue(isContactUrl("SMS:+447700900123"))
        assertFalse(isContactUrl("https://example.com"))
        assertFalse(isContactUrl("terminus://retry"))
    }

    @Test fun `holding a link copies what a person would paste`() {
        assertEquals("+447700900123", copyTextFor("tel:+447700900123"))
        assertEquals("01268123456", copyTextFor("tel:01268123456"))
        assertEquals("john_smith@example.co.uk", copyTextFor("mailto:john_smith@example.co.uk"))
        assertEquals("a@b.com", copyTextFor("mailto:a@b.com?subject=hi"))
        assertEquals("https://example.com/a?x=1", copyTextFor("https://example.com/a?x=1"))
    }

    @Test fun `the app's own token is never copied`() {
        assertEquals("http://192.0.2.10:9009/terminus/2026-11-15/a.apk", copyTextFor("http://192.0.2.10:9009/terminus/2026-11-15/a.apk?token=l1.abc.def"))
        assertEquals("https://h/x?a=1", copyTextFor("https://h/x?token=zzz&a=1"))
        assertEquals("https://h/x?a=1", copyTextFor("https://h/x?a=1&token=zzz"))
    }

    @Test fun `app actions and files keep their normal long press`() {
        assertNull(copyTextFor("terminus://retry?x=1"))
        assertNull(copyTextFor("file:///sdcard/a.txt"))
        assertNull(copyTextFor(""))
    }

    @Test fun `phone and email hits copy the bare number and address`() {
        assertEquals("+447700900123", longPressCopy(android.webkit.WebView.HitTestResult.PHONE_TYPE, "+44 7700 900123"))
        assertEquals("01268123456", longPressCopy(android.webkit.WebView.HitTestResult.PHONE_TYPE, "01268123456"))
        assertEquals("john_smith@example.co.uk", longPressCopy(android.webkit.WebView.HitTestResult.EMAIL_TYPE, "john_smith@example.co.uk"))
        assertEquals("https://example.com/a", longPressCopy(android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE, "https://example.com/a"))
        // An image wrapped in a link reports the IMAGE url: never copy that as the link (the listener asks for the href).
        assertNull(longPressCopy(android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://www.google.com/s2/favicons?sz=64&domain=x.com"))
        assertNull(longPressCopy(android.webkit.WebView.HitTestResult.UNKNOWN_TYPE, "https://example.com/a"))
        assertNull(longPressCopy(android.webkit.WebView.HitTestResult.IMAGE_TYPE, "https://example.com/a.png"))
    }
}
