package com.terminus.rec

/** Backend-qualified display labels. Pure, unit-tested. Never derives the
 *  backend from a model id — backend and model are separate fields. */
fun backendDisplayName(backend: String): String = Prefs.backendLabel(backend)

fun shortModelLabel(model: String): String =
    if (model.isBlank()) "" else model.substringAfter("/")

/** A chat row's third line: only what asks you to act, else blank (the row stays two lines).
 *  Order: sends waiting for a connection, an unsent draft, context nearly full, then files.
 *  Pure, unit-tested. */
fun chatMetaLine(waiting: Int, draft: String, contextPct: Int, files: Int): String {
    val parts = ArrayList<String>(3)
    if (waiting > 0) parts += "$waiting waiting to send"
    val d = draft.trim().replace(Regex("\\s+"), " ")
    if (d.isNotEmpty()) parts += "Draft: " + (if (d.length > 40) d.take(40).trimEnd() + "…" else d)
    if (contextPct >= CONTEXT_WARN_PCT) parts += "context $contextPct%"
    if (files > 0) parts += "$files file" + (if (files == 1) "" else "s")
    return parts.take(2).joinToString(" · ")
}

const val CONTEXT_WARN_PCT = 75

/** Agent badge icon per backend (pure, unit-tested): official marks where
 *  obtainable (opencode square, claude starburst, agy gradient spark),
 *  a neutral code glyph for codex which publishes no
 *  standalone mark. Unknown backends canon to opencode. The badge is a
 *  non-interactive indicator; row actions live in the overflow menu, so icon
 *  and action never share a target. */
fun agentIcon(backend: String): Int = when (Prefs.cannonBackend(backend)) {
    Prefs.BACKEND_CLAUDE -> R.drawable.ic_agent_claude
    Prefs.BACKEND_CODEX -> R.drawable.ic_agent_codex
    Prefs.BACKEND_AGY -> R.drawable.ic_agent_agy
    else -> R.drawable.ic_agent_opencode
}

/** Failure-row prefix: "Codex · ", empty when the backend is unknown.
 *  Pure, unit-tested. No brackets: tool rows already carry enough punctuation. */
fun backendFailurePrefix(backend: String?): String =
    if (backend.isNullOrBlank()) "" else "${backendDisplayName(backend)} · "

/** A backend without a live catalog has no selectable models to show. */
fun fallbackModels(@Suppress("UNUSED_PARAMETER") backend: String): List<RemoteModel> = emptyList()

/** Backend-scoped list (pure, unit-tested): the session list follows the
 *  selected backend — view-only, never migration. Unknown ids canon.
 *  [always] ids (the car chat) show under every backend. */
fun backendScoped(sessions: List<ChatSession>, backend: String, always: Set<String> = emptySet()): List<ChatSession> {
    val b = Prefs.cannonBackend(backend)
    return sessions.filter { s -> isSystemChat(s.id) || s.id in always || Prefs.cannonBackend(s.backend) == b }
}

/** System chats (alerts, schedule jobs, stable threads) belong to no
 *  backend scope: hiding them under a non-opencode filter would bury
 *  unread alerts and job threads. Pure, unit-tested. */
fun isSystemChat(id: String): Boolean =
    id == "alerts" || id.startsWith("schedule:") || id.startsWith("job:")

/** Schedule threads (per-job `schedule:<name>` / `job:<name>`): their own
 *  list tab; Recent hides them unless they have unread. Pure, unit-tested. */
fun isScheduleChat(id: String): Boolean =
    id.startsWith("schedule:") || id.startsWith("job:")

/** Capability lookup with safe fallbacks (pure, unit-tested): known
 *  backend in catalog → its flags; opencode offline → historic behavior;
 *  anything else → fully closed (features hide instead of misfiring). */
fun capsForBackend(catalog: BackendCatalog?, backend: String): BackendCapabilities {
    val b = Prefs.cannonBackend(backend)
    catalog?.backends?.firstOrNull { it.id == b }?.capabilities?.let { return it }
    if (b == Prefs.BACKEND_OPENCODE) return BackendCapabilities()
    return BackendCapabilities(
        streaming = false, models = false, commands = false, sessionsList = false,
        history = false, resume = false, interruptTurn = false, killProcess = false,
        compact = false, title = false, deleteSession = false, fork = false,
        approvals = false, attachments = false, overlappingTurns = false,
        effort = false, usage = true,
    )
}

/** Chat header / picker chip: "Codex · GPT-5", blank model follows the
 *  backend default: "OpenCode · Default (GPT-5)". */
fun chipLabel(backend: String, model: String, defaultModel: String = ""): String {
    val b = backendDisplayName(backend)
    if (model.isBlank()) {
        val d = shortModelLabel(defaultModel)
        return if (d.isNotBlank()) "$b · Default ($d)" else "$b · Default"
    }
    return "$b · ${shortModelLabel(model)}"
}

/** File paths an assistant mentioned in prose (pure, unit-tested): absolute
 *  filesystem paths and http(s) URLs with file-ish tails. Lets the
 *  attachments sheet surface agent-sent artifacts (images, reports, zips)
 *  that never rode the outgoing attachment field. */
fun extractMentionedFiles(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val out = linkedSetOf<String>()
    // Absolute paths: /a/b/c.ext — trailing sentence punctuation trimmed.
    for (m in Regex("""/[^\s"'<>()\[\]]+""").findAll(text)) {
        var p = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'')
        if (p.count { it == '/' } < 2) continue
        if (p.length > 300) continue
        out += p
    }
    // http(s) URLs with a file tail (extension or /terminus/ gallery path).
    for (m in Regex("""https?://[^\s"'<>()\[\]]+""").findAll(text)) {
        var u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'')
        val low = u.lowercase().substringBefore("?").substringBefore("#")
        val tail = low.substringAfterLast("/")
        if ("." in tail || "/terminus/" in low) out += u
    }
    // [image:/abs/path] markers the renderer embeds inline.
    for (m in Regex("""\[image:([^\]]+)]""").findAll(text)) {
        val q = m.groupValues[1].trim()
        if (q.isNotBlank()) out += q
    }
    return out.toList()
}

/** Tidy local title from a first message (pure, unit-tested): first ~6
 *  meaningful words, command prefixes (/cmd, >quote) and bare URLs
 *  stripped. Non-opencode chats never get server titles, so this is
 *  what keeps them out of first-message-as-title ugliness. */
fun autoTitle(text: String): String {
    var s = text.trim().lineSequence().firstOrNull()?.trim() ?: ""
    s = s.replace(Regex("""/[a-z]+\s*"""), "")
    s = s.replace(Regex(""">\s*"""), "")
    val words = s.split(Regex("""\s+""")).filter { w ->
        w.isNotBlank() && !w.startsWith("http", true) && !w.startsWith("terminus://")
    }.take(6)
    // Punctuation-only input ("??", "...") is not a title.
    if (words.isEmpty() || words.none { w -> w.any { it.isLetterOrDigit() } }) return "untitled"
    return words.joinToString(" ").take(42)
}
