package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Backend catalog parsing + display labels (M4b). */
class BackendCatalogTest {

    @Test fun `prefs backend validation is a closed set`() {
        assertTrue(Prefs.validBackend("opencode"))
        assertTrue(Prefs.validBackend("codex"))
        assertTrue(Prefs.validBackend("claude"))
        assertTrue(Prefs.validBackend("agy"))
        assertFalse(Prefs.validBackend("Opencode"))
        assertFalse(Prefs.validBackend(""))
        assertFalse(Prefs.validBackend("codex exec"))
        assertEquals("opencode", Prefs.cannonBackend("nope"))
        assertEquals("opencode", Prefs.cannonBackend(null))
        assertEquals("codex", Prefs.cannonBackend("codex"))
    }

    @Test fun `model keys are fixed per backend`() {
        assertEquals("defaultModel.opencode", Prefs.modelKeyFor("opencode"))
        assertEquals("defaultModel.codex", Prefs.modelKeyFor("codex"))
    }

    @Test fun `parseModels keeps opaque ids, drops blanks`() {
        val body = """{"models":[
          {"id":"openai/gpt-5","provider":"openai","label":"GPT-5"},
          {"id":"gpt-5.6-codex","label":"GPT-5.6 Codex"},
          {"id":""},
          {}]}"""
        val out = parseModels(body)
        assertEquals(2, out.size)
        assertEquals(RemoteModel("openai/gpt-5", "openai", "GPT-5"), out[0])
        assertEquals(RemoteModel("gpt-5.6-codex", "", "GPT-5.6 Codex"), out[1])
    }

    @Test fun `parseBackends reads descriptors, defaults closed on garbage`() {
        val body = """{"defaultBackend":"opencode","backends":[
          {"id":"opencode","label":"OpenCode","shortLabel":"OpenCode",
           "available":true,"authenticated":true,
           "capabilities":{"overlappingTurns":true,"approvals":false}}]}"""
        val cat = parseBackends(body)
        assertEquals("opencode", cat.defaultBackend)
        assertEquals(1, cat.backends.size)
        assertEquals("OpenCode", cat.backends[0].label)
        assertTrue(cat.backends[0].capabilities.overlappingTurns)
        assertFalse(cat.backends[0].capabilities.approvals)
        val bad = parseBackends("not json")
        assertTrue(bad.backends.isEmpty())
        assertEquals("opencode", bad.defaultBackend)
    }

    @Test fun `effort and usage flags are read, old servers default effort closed and usage open`() {
        val body = """{"defaultBackend":"codex","backends":[
          {"id":"codex","label":"Codex","available":true,"authenticated":true,
           "capabilities":{"effort":true,"usage":true}},
          {"id":"agy","label":"Agy","available":true,"authenticated":true,
           "capabilities":{"effort":false,"usage":false}},
          {"id":"claude","label":"Claude","available":true,"authenticated":true,"capabilities":{}}]}"""
        val cat = parseBackends(body)
        assertTrue(cat.backends[0].capabilities.effort)
        assertFalse(cat.backends[1].capabilities.effort)
        assertFalse(cat.backends[1].capabilities.usage)
        assertFalse(cat.backends[2].capabilities.effort)
        assertTrue(cat.backends[2].capabilities.usage)
    }

    @Test fun `parseDefaults reads backend fields, tolerates old shape`() {
        val full = """{"defaultBackend":"opencode",
          "defaultModels":{"opencode":"openai/gpt-5"},
          "defaultModel":"openai/gpt-5","systemPrompt":"s"}"""
        val d = parseDefaults(full)
        assertEquals("opencode", d.defaultBackend)
        assertEquals("openai/gpt-5", d.defaultModels["opencode"])
        val legacy = parseDefaults("""{"defaultModel":"openai/gpt-5"}""")
        assertEquals("opencode", legacy.defaultBackend)
        assertEquals("openai/gpt-5", legacy.defaultModel)
    }

    @Test fun `chip labels qualify backend, never parse it from model`() {
        assertEquals("Codex · gpt-5.6-codex", chipLabel("codex", "gpt-5.6-codex"))
        assertEquals("OpenCode · gpt-5", chipLabel("opencode", "openai/gpt-5"))
        assertEquals("OpenCode · Default (gpt-5)", chipLabel("opencode", "", "openai/gpt-5"))
        assertEquals("Claude · Default", chipLabel("claude", ""))
        assertEquals("OpenCode · gpt-5", chipLabel("nope", "openai/gpt-5"))
    }

    @Test fun `backend failure prefix names the backend`() {
        assertEquals("Codex · ", backendFailurePrefix("codex"))
        assertEquals("OpenCode · ", backendFailurePrefix("opencode"))
        assertEquals("OpenCode · ", backendFailurePrefix("nope"))
        assertEquals("", backendFailurePrefix(null))
        assertEquals("", backendFailurePrefix(""))
    }

    @Test fun `session backend defaults to opencode and flips the row signature`() {
        val a = ChatSession(id = "s", title = "t", updatedAt = 1L, model = "m")
        assertEquals("opencode", a.backend)
        val b = a.copy(backend = "codex", model = "gpt")
        assertNotEquals(
            sessionRowSig(a, live = false, images = 0, files = 0),
            sessionRowSig(b, live = false, images = 0, files = 0),
        )
    }

    @Test fun `unavailable catalogs never invent models`() {
        assertTrue(fallbackModels("claude").isEmpty())
        assertTrue(fallbackModels("codex").isEmpty())
        assertTrue(fallbackModels("opencode").isEmpty())
        assertTrue(fallbackModels("nope").isEmpty())
    }

    @Test fun `schedule chats are their own tab`() {
        assertTrue(isScheduleChat("schedule:morning-brief"))
        assertTrue(isScheduleChat("job:abc"))
        assertFalse(isScheduleChat("alerts"))
        assertFalse(isScheduleChat("s-123"))
    }

    @Test fun `system chats escape backend scope`() {
        assertTrue(isSystemChat("alerts"))
        assertTrue(isSystemChat("schedule:morning"))
        assertFalse(isSystemChat("s-123"))
        val mk = { id: String, b: String -> ChatSession(id = id, title = id, updatedAt = 1L, backend = b, model = "m") }
        val all = listOf(mk("a", "opencode"), mk("alerts", "opencode"), mk("schedule:x", "opencode"))
        // Normal chat filters out; system chats survive regardless of scope.
        assertEquals(listOf("alerts", "schedule:x"), backendScoped(all, "codex").map { it.id })
    }

    @Test fun `backend scope filters the list view-only`() {
        val mk = { id: String, b: String -> ChatSession(id = id, title = id, updatedAt = 1L, backend = b, model = "m") }
        val all = listOf(mk("a", "opencode"), mk("b", "codex"), mk("c", ""))
        assertEquals(listOf("a", "c"), backendScoped(all, "opencode").map { it.id })
        assertEquals(listOf("b"), backendScoped(all, "codex").map { it.id })
        assertEquals(3, backendScoped(all, "opencode").size + backendScoped(all, "codex").size)
    }

    @Test fun `mentioned files extracts agent-sent artifacts`() {
        val text = "Here is the render: /home/user/work/terminus/artifacts/2026-10-07/clip.mp4. And the report https://x.io/r.pdf."
        val out = extractMentionedFiles(text)
        assertTrue(out.any { it.endsWith("clip.mp4") })
        assertTrue(out.any { it.endsWith("r.pdf") })
        assertTrue(extractMentionedFiles("no files here, just words").isEmpty())
        assertTrue(extractMentionedFiles("[image:/tmp/a.png]").any { it.endsWith("a.png") })
    }

    @Test fun `autoTitle tidies first lines`() {
        assertEquals("fix the login bug where x", autoTitle("/chat fix the login bug where x happens y z"))
        assertEquals("hello world today", autoTitle("> hello world today"))
        assertEquals("untitled", autoTitle("   "))
        assertEquals("untitled", autoTitle("??"))
        assertEquals("untitled", autoTitle("... !!!"))
    }

    @Test fun `caps fall back safe - opencode historic, others closed`() {
        val cat = parseBackends("""{"defaultBackend":"opencode","backends":[]}""")
        assertTrue(capsForBackend(cat, "opencode").compact)
        assertFalse(capsForBackend(cat, "codex").compact)
        // Unknown ids canon to opencode (never crash, never strand).
        assertTrue(capsForBackend(cat, "nope").streaming)
        val full = parseBackends("""{"defaultBackend":"opencode","backends":[
          {"id":"codex","label":"Codex","available":true,"authenticated":true,
           "capabilities":{"compact":false,"fork":false,"title":false}}]}""")
        assertFalse(capsForBackend(full, "codex").compact)
        assertTrue(capsForBackend(full, "codex").streaming)
    }
}
