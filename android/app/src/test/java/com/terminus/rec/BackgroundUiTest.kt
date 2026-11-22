package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Background job lifecycle rows: both ends land as durable event rows so a
 *  job that starts or finishes with no turn live is still visible. */
class BackgroundUiTest {

    @Test fun `started row names the command`() {
        assertEquals("backgrounded: sleep 6; echo BG-MARKER", BackgroundUi.rowText("started", "sleep 6; echo BG-MARKER", ""))
    }

    @Test fun `done row carries the output`() {
        assertEquals("background done: python3 -c 1+1 (2)", BackgroundUi.rowText("done", "python3 -c 1+1", "2"))
    }

    @Test fun `done row without output stays clean`() {
        assertEquals("background done: ls", BackgroundUi.rowText("done", "ls", ""))
    }

    @Test fun `a failed job reads distinctly`() {
        assertEquals("background failed: make (exit 2)", BackgroundUi.rowText("failed", "make", "exit 2"))
    }

    @Test fun `blank label falls back, never an empty row`() {
        assertEquals("backgrounded: background job", BackgroundUi.rowText("started", "  ", ""))
    }

    @Test fun `rows use no em-dashes`() {
        for (st in listOf("started", "done", "failed", "odd")) {
            assertFalse(BackgroundUi.rowText(st, "cmd", "out").contains('\u2014'))
        }
    }

    @Test fun `a finished job is one quiet line with its time, the start line folded away`() {
        val h = ChatMarkdown.threadHtml(
            "t", "",
            listOf(
                ChatMessage("event", BackgroundUi.rowText("started", "Build APK", "")),
                ChatMessage("event", BackgroundUi.rowText("done", "Build APK", "exit=0 39 tasks", "1m 24s")),
            ),
        )
        assertTrue(h.contains("bgline st-done"))
        assertFalse(h.contains("st-run"))
        assertTrue(h.contains("1m 24s"))
        assertTrue(h.contains(">output<"))
        assertFalse(h.contains("cdiv evt"))
        assertFalse(h.contains("row assistant"))
    }

    @Test fun `a running job shows a spinner line`() {
        val h = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("event", BackgroundUi.rowText("started", "sleep 6", ""), ts = System.currentTimeMillis())))
        assertTrue(h.contains("bgline st-run"))
        assertTrue(h.contains("bgspin"))
        assertTrue(h.contains("running"))
    }

    @Test fun `a running job has a stop cross carrying its name, finished and stale ones have none`() {
        val now = System.currentTimeMillis()
        val run = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("event", BackgroundUi.rowText("started", "count \"to\" ten", ""), ts = now)))
        assertTrue(run.contains("class='bgstop' data-cmd=\"count &quot;to&quot; ten\""))
        val stale = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("event", BackgroundUi.rowText("started", "old", ""), ts = now - BackgroundUi.SHOWN_RUNNING_MS - 1000)))
        assertFalse(stale.contains("class='bgstop'"))
        val done = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("event", BackgroundUi.rowText("done", "x", "", "3s"))))
        assertFalse(done.contains("class='bgstop'"))
    }

    @Test fun `a late finish is drawn where its start was, carrying the start time`() {
        val msgs = listOf(
            ChatMessage("user", "go", ts = 100),
            ChatMessage("assistant", "started it", turnId = "t1", settled = true, ts = 110),
            ChatMessage("event", BackgroundUi.rowText("started", "Build APK", ""), ts = 120),
            ChatMessage("user", "next", ts = 200),
            ChatMessage("assistant", "other reply", turnId = "t2", settled = true, ts = 210),
            ChatMessage("event", BackgroundUi.rowText("done", "Build APK", "", "1m 24s"), ts = 300),
        )
        assertEquals(mapOf(2 to 5), BackgroundUi.finishPairs(msgs))
        val h = ChatMarkdown.threadHtml("t", "", msgs)
        val line = h.indexOf("bgline st-done")
        assertTrue(line > 0)
        assertTrue("finish sits before the later user row", line < h.indexOf(">next<"))
        assertTrue(h.contains("data-ts=\"300\" data-at=\"120\""))
        assertEquals(1, Regex("bgline st-").findAll(h).count())
        assertTrue(h.contains("data-role=\"assistant\" data-ts=\"110\""))
    }

    @Test fun `a job stopped from the phone reads stopped, with no output link`() {
        val h = ChatMarkdown.threadHtml("t", "", listOf(ChatMessage("event", BackgroundUi.rowText("failed", "count to ten", "stopped", "12s"))))
        assertTrue(h.contains("stopped · 12s"))
        assertTrue(h.contains("■"))
        assertFalse(h.contains(">output<"))
        assertFalse(h.contains("failed ·"))
    }

    @Test fun `line splits command, elapsed and output`() {
        val l = BackgroundUi.line(BackgroundUi.rowText("failed", "npm test (unit)", "2 failing", "45s"))!!
        assertEquals("failed", l.state)
        assertEquals("npm test (unit)", l.cmd)
        assertEquals("45s", l.elapsed)
        assertEquals("2 failing", l.output)
    }

    @Test fun `a finish named by Claude's summary still pairs with its start`() {
        val msgs = listOf(
            ChatMessage("event", BackgroundUi.rowText("started", "Second test background job", "")),
            ChatMessage("event", BackgroundUi.rowText("done", "Background command \"Second test background job\" completed (exit code 0)", "", "15s")),
        )
        assertEquals(setOf(0), BackgroundUi.finishedStarts(msgs))
        assertEquals("Second test background job", BackgroundUi.line(msgs[1].text)!!.cmd)
    }

    @Test fun `background rows never ride along to the model`() {
        val msgs = listOf(
            ChatMessage("user", "go", turnId = "t1"),
            ChatMessage("event", BackgroundUi.rowText("done", "Build APK", "ok", "5s")),
            ChatMessage("user", "next", turnId = "t2"),
        )
        assertTrue(DeliveryContext.collect(msgs, "t2").isEmpty())
    }

    @Test fun `a started job holds the chat open until it lands`() {
        val j = BackgroundJobs()
        assertFalse(j.any("c1"))
        j.note("c1", "started")
        assertTrue(j.any("c1"))
        j.note("c1", "done")
        assertFalse(j.any("c1"))
    }

    @Test fun `overlapping jobs keep the chat open until the last lands`() {
        val j = BackgroundJobs()
        j.note("c1", "started")
        j.note("c1", "started")
        assertTrue(j.any("c1"))
        j.note("c1", "done")
        assertTrue(j.any("c1"))
        j.note("c1", "failed")
        assertFalse(j.any("c1"))
    }

    @Test fun `jobs are per chat`() {
        val j = BackgroundJobs()
        j.note("c1", "started")
        assertFalse(j.any("c2"))
        assertTrue(j.any("c1"))
    }

    @Test fun `a landing with no seen start never opens the chat`() {
        val j = BackgroundJobs()
        j.note("c1", "done")
        assertFalse(j.any("c1"))
    }

    @Test fun `cardParts splits pill label and body`() {
        assertEquals(Triple("background", "sleep 6", ""), BackgroundUi.cardParts("backgrounded: sleep 6"))
        assertEquals(Triple("background done", "ls (2)", ""), BackgroundUi.cardParts("background done: ls (2)"))
        assertEquals(Triple("background failed", "make", ""), BackgroundUi.cardParts("background failed: make"))
        assertNull(BackgroundUi.cardParts("session gone, starting fresh"))
    }

    @Test fun `cardParts pulls the fenced full output`() {
        val row = BackgroundUi.rowText("done", "make", "ok", "2m 14s", "line1\nline2\nline3")
        val parts = BackgroundUi.cardParts(row)
        assertEquals("background done", parts?.first)
        assertEquals("make (ok, 2m 14s)", parts?.second)
        assertEquals("line1\nline2\nline3", parts?.third)
    }

    @Test fun `elapsed formats compactly`() {
        assertEquals("45s", BackgroundUi.elapsedText(45_000))
        assertEquals("2m 14s", BackgroundUi.elapsedText(134_000))
        assertEquals("1h 2m", BackgroundUi.elapsedText(3_720_000))
    }

    @Test fun `finish row carries elapsed, drops output when long`() {
        assertEquals(
            "background done: sleep 60 (0, 2m 14s)",
            BackgroundUi.rowText("done", "sleep 60", "0", "2m 14s"),
        )
        // Detail capped at 160; a long command + long output + elapsed
        // exceeds the row budget, so the output drops and only elapsed stays.
        val longCmd = "x".repeat(200)
        assertEquals(
            "background done: " + "x".repeat(160) + " (2m 14s)",
            BackgroundUi.rowText("done", longCmd, "y".repeat(300), "2m 14s"),
        )
    }

    @Test fun `an unbalanced terminal never drives the count negative`() {
        val j = BackgroundJobs()
        j.note("c1", "done")
        j.note("c1", "started")
        assertTrue(j.any("c1"))
        j.note("c1", "done")
        assertFalse(j.any("c1"))
        j.note("c1", "done")
        assertFalse(j.any("c1"))
    }

    @Test fun `a stale start with no finish stops holding the stream`() {
        val j = BackgroundJobs()
        j.note("c1", "started")
        assertTrue(j.any("c1"))
        j.staleAfterMs = -1
        assertFalse(j.any("c1"))
    }
}
