package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ThreadUiTest {

    // ---- cleanPreview ----

    @Test fun `markdown noise is stripped from a preview`() {
        assertEquals("Then take the middle: 70 brisk (~350)", cleanPreview("**Then** take the middle: `70` brisk (~350)"))
        assertEquals("see the docs", cleanPreview("see [the docs](https://example.com/x?y=1)"))
        assertEquals("image", cleanPreview("![chart](https://example.com/a.png)"))
        assertEquals("image", cleanPreview("[image:/tmp/a.png]"))
    }

    @Test fun `bullets quotes and numbering lose their markers`() {
        assertEquals("first item", cleanPreview("- first item\n- second"))
        assertEquals("a quote", cleanPreview("> a quote"))
        assertEquals("step one", cleanPreview("1. step one"))
    }

    @Test fun `a brief previews the day line, not the heading hashes`() {
        val brief = "# 🌅 Thu 29 Oct\n\nMild and showery; standup at 09:00.\n\n## YOUR DAY"
        assertEquals("Mild and showery; standup at 09:00.", cleanPreview(brief))
    }

    @Test fun `heading-only text previews the heading`() {
        assertEquals("Summary", cleanPreview("## Summary"))
    }

    @Test fun `code fences collapse to the word code`() {
        assertEquals("code", cleanPreview("```kotlin\nval x = 1\n```"))
        assertEquals("run it: code", cleanPreview("run it: ```x```"))
    }

    @Test fun `long text is cut at a word with an ellipsis`() {
        val out = cleanPreview("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda", 30)
        assertTrue(out, out.endsWith("…"))
        assertTrue(out, out.length <= 30)
        assertFalse(out, out.contains("  "))
        assertEquals("alpha beta gamma delta", out.removeSuffix("…"))
    }

    @Test fun `blank in, blank out`() {
        assertEquals("", cleanPreview(""))
        assertEquals("", cleanPreview("  \n \n"))
    }

    @Test fun `snake_case and star math survive`() {
        assertEquals("use my_var_name and 2*3", cleanPreview("use my_var_name and 2*3"))
    }

    // ---- sections ----

    private fun at(daysAgo: Int, hour: Int = 12, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        c.add(Calendar.DAY_OF_YEAR, -daysAgo); c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0)
        return c.timeInMillis
    }

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 29, 19, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `date buckets follow the calendar`() {
        assertEquals(ListBucket.TODAY, listBucket(false, false, at(0, 1, now), now))
        assertEquals(ListBucket.YESTERDAY, listBucket(false, false, at(1, 23, now), now))
        assertEquals(ListBucket.THIS_WEEK, listBucket(false, false, at(3, 9, now), now))
        assertEquals(ListBucket.EARLIER, listBucket(false, false, at(9, 9, now), now))
    }

    @Test fun `needs-you outranks pinned outranks date`() {
        assertEquals(ListBucket.NEEDS_YOU, listBucket(true, true, at(30, 9, now), now))
        assertEquals(ListBucket.PINNED, listBucket(false, true, at(0, 9, now), now))
    }

    @Test fun `small lists get no headers`() {
        val plan = sectionPlan(listOf(ListBucket.TODAY, ListBucket.EARLIER))
        assertTrue(plan.all { it.first == null })
        assertEquals(listOf(0, 1), plan.map { it.second })
    }

    @Test fun `a header precedes the first row of each bucket`() {
        val b = listOf(ListBucket.NEEDS_YOU, ListBucket.TODAY, ListBucket.TODAY, ListBucket.YESTERDAY, ListBucket.EARLIER)
        val plan = sectionPlan(b)
        assertEquals(listOf(ListBucket.NEEDS_YOU, null, ListBucket.TODAY, null, null, ListBucket.YESTERDAY, null, ListBucket.EARLIER, null),
            plan.map { it.first })
        assertEquals(listOf(0, 1, 2, 3, 4), plan.filter { it.first == null }.map { it.second })
    }

    // ---- needs-you ----

    @Test fun `event goes to its chat when it exists, else Alerts`() {
        val has = { id: String -> id == "s-1" }
        assertEquals("s-1", inboxTarget("phone-browser", "s-1", "event", has))
        assertEquals("alerts", inboxTarget("phone-browser", "s-gone", "event", has))
        assertEquals("alerts", inboxTarget("phone-browser", "", "event", has))
        assertEquals("alerts", inboxTarget("phone-browser", "bad id!", "event", has))
    }

    @Test fun `non-event entries keep the old routing`() {
        val none = { _: String -> false }
        assertEquals("s-9", inboxTarget("browser", "s-9", "", none))
        assertEquals("schedule:weather", inboxTarget("weather", "", "", none))
        assertEquals("alerts", inboxTarget("system", "", "", none))
    }

    @Test fun `flags expire, browser asks in minutes and failures in a day`() {
        assertEquals(10 * 60_000L, needsYouTtlMs("phone-browser"))
        assertEquals(10 * 60_000L, needsYouTtlMs("approvals"))
        assertEquals(24 * 3_600_000L, needsYouTtlMs("morning-brief"))
        assertTrue(needsYouActive("x", 2_000, 1_000))
        assertFalse(needsYouActive("x", 1_000, 1_000))
        assertFalse(needsYouActive("", 9_000, 1_000))
    }

    @Test fun `reason prefers the title and falls back to the body`() {
        assertEquals("Agent is waiting for your phone browser", needsYouReason("Agent is waiting for your phone browser", "Open Terminus"))
        assertEquals("Open Terminus", needsYouReason("", "\nOpen Terminus so it can continue."  ).take(13))
    }

    @Test fun `event text round-trips into a card`() {
        val t = needsYouText("Browser needs you", "CAPTCHA on example.com")
        assertTrue(isNeedsYouText(t))
        assertFalse(isNeedsYouText("interrupted"))
        val html = needsYouCardHtml(t, 7)
        assertTrue(html, html.contains("needcard") && html.contains("Browser needs you") && html.contains("CAPTCHA on example.com"))
        assertTrue(html.contains("data-idx=\"7\""))
    }

    @Test fun `card html escapes the entry`() {
        val html = needsYouCardHtml(needsYouText("<b>x</b>", "a & b"), 0)
        assertFalse(html.contains("<b>x"))
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;") && html.contains("a &amp; b"))
    }

    // ---- brief card ----

    private val brief = "# 🌅 Thu 29 Oct\nMild and showery; standup at 09:00.\n\n**Money:** Trade X at open"

    @Test fun `fresh unread brief shows its day line and money`() {
        val t = briefCardText("schedule:morning-brief", 1, brief, now - 3_600_000, now)
        assertNotNull(t)
        assertTrue(t!!, t.startsWith("Mild and showery") && t.contains("Trade X"))
    }

    @Test fun `read, stale or other threads show no card`() {
        assertNull(briefCardText("schedule:morning-brief", 0, brief, now - 1000, now))
        assertNull(briefCardText("schedule:morning-brief", 1, brief, now - 25 * 3_600_000L, now))
        assertNull(briefCardText("schedule:weather", 1, brief, now - 1000, now))
        assertNull(briefCardText("schedule:morning-brief", 1, "", now - 1000, now))
    }

    // ---- time clusters ----

    @Test fun `a burst is one cluster, a pause or a new day starts the next`() {
        val t = now
        assertTrue(startsCluster(0, t))
        assertFalse(startsCluster(t, t + 5 * 60_000))
        assertFalse(startsCluster(t, t + CLUSTER_GAP_MS - 1))
        assertTrue(startsCluster(t, t + CLUSTER_GAP_MS))
        assertTrue(startsCluster(t, t + 6 * 3_600_000))
        val c = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        assertTrue("midnight starts a cluster even after a short gap", startsCluster(c.timeInMillis, c.timeInMillis + 2 * 60_000))
    }

    @Test fun `needs-you age reads naturally`() {
        val now = 10_000_000_000L
        assertEquals("now", needsYouAge(now - 20_000, now))
        assertEquals("5m ago", needsYouAge(now - 5 * 60_000, now))
        assertEquals("2h ago", needsYouAge(now - 2 * 3_600_000L - 5, now))
        assertEquals("3d ago", needsYouAge(now - 3 * 86_400_000L, now))
        assertEquals("now", needsYouAge(now + 60_000, now))
    }
}
