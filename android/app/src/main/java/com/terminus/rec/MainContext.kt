package com.terminus.rec

import android.view.View

/** Model chip, context ring and the context sheet. Extension functions of [MainActivity], moved out of it unchanged. */

/** The model a turn is sent with. Blank = the backend's default, but Claude gets it spelled out: with no
 *  --model a resumed session keeps whatever model it last ran on, so "Default" after picking haiku
 *  silently stayed on haiku. The chip shows the same default (chipText). */
internal fun MainActivity.sendModelFor(backend: String, chatModel: String?): String? {
    val m = chatModel.orEmpty()
    if (m.isNotBlank()) return m
    if (chatModel == null || backend != Prefs.BACKEND_CLAUDE) return null
    // A provider/model id is another backend's default; Claude only takes plain aliases (sonnet, haiku, ...).
    return prefs.defaultModel(backend).ifBlank { serverDefaultModel }.takeIf { it.isNotBlank() && !it.contains('/') }
}

/** Compact thread chip: [backend icon] [short model]. The icon carries
 *  the backend (set at the call sites); text never repeats it. */
internal fun MainActivity.chipText(backend: String, model: String): String {
    if (model.isBlank()) {
        val short = shortModelLabel(prefs.defaultModel(backend).ifBlank { serverDefaultModel }).take(8)
        return if (short.isNotBlank()) "Default ($short)" else "Model"
    }
    val short = shortModelLabel(model).take(8)
    return if (short.isNotBlank()) short else "Model"
}

internal fun MainActivity.fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** The ring fills against the model's window; without one it fills against the auto-compact ceiling.
 *  No data yet reads as an empty 0% ring, never hidden mid-thread. */
/** Backends with no usage reporting get no ring: an eternal 0% lies.
 *  Backends declare it (capabilities.usage = false): Agy has no token
 *  accounting the server can read from their CLI surface. */
internal fun MainActivity.usageBackend(): Boolean {
    val b = activeSessionId?.let { try { chatStore.get(it)?.backend } catch (_: Exception) { null } }
        ?: prefs.defaultBackend()
    val cb = Prefs.cannonBackend(b)
    // Server-declared (capabilities.usage); until the catalog loads, assume it reports.
    return backendCatalog?.backends?.firstOrNull { it.id == cb }?.capabilities?.usage ?: true
}

internal fun MainActivity.paintContextRing() {
    if (!contextRingReady) return
    if (!usageBackend()) { contextRing.visibility = View.GONE; return }
    val c = contextInfo
    val denom = when {
        c == null -> 0L
        c.window > 0 -> c.window
        c.compactAt > 0 -> c.compactAt.toLong()
        else -> 0L
    }
    val ceiling = when {
        c == null || c.compactAt <= 0 -> null
        c.window > 0 -> c.compactAt.toFloat() / c.window
        else -> 1f
    }
    if (c == null || c.used <= 0 || denom <= 0) {
        contextRing.set(0f, ceiling)
        contextRing.visibility = View.VISIBLE
        contextRing.contentDescription = "context empty"
        return
    }
    contextRing.set(c.used.toFloat() / denom, ceiling, c.used)
    contextRing.visibility = View.VISIBLE
    contextRing.contentDescription = "context ${ContextRingView.fmtTokens(c.used)} of ${ContextRingView.fmtTokens(denom)} used"
}

/** Usage moves after every model request (claude, codex) and every settle (all): refresh the open chat's
 *  ring now, or once the throttle window ends. A throttled call is deferred, never dropped, so the last
 *  change of a burst always lands. Safe from any thread. */
internal fun MainActivity.pokeContext(id: String) {
    progressHandler.post {
        if (activeSessionId != id) return@post
        contextTrailing?.let { progressHandler.removeCallbacks(it) }
        val r = Runnable {
            contextTrailing = null
            if (activeSessionId == id) chatStore.get(id)?.let { refreshContext(id, it.model) }
        }
        contextTrailing = r
        val wait = CONTEXT_THROTTLE_MS - (System.currentTimeMillis() - contextFetchAt)
        if (wait <= 0) r.run() else progressHandler.postDelayed(r, wait)
    }
}

internal fun MainActivity.refreshContext(id: String, model: String, totals: Boolean = false, done: (() -> Unit)? = null) {
    if (!usageBackend()) return
    val now = System.currentTimeMillis()
    if (!totals && now - contextFetchAt < CONTEXT_THROTTLE_MS) {
        if (done == null) pokeContext(id)
        return
    }
    contextFetchAt = now
    Thread {
        val info = try { OpencodeApi.fetchContext(prefs.serverUrl(), prefs.token(), id, model, totals) } catch (_: Exception) { null }
        runOnUiThread {
            // The list's third line warns when a chat's context is nearly full: keep each chat's last fill.
            info?.let { c ->
                val denom = if (c.window > 0) c.window else c.compactAt.toLong()
                if (denom > 0) {
                    val pct = ((c.used * 100) / denom).toInt().coerceIn(0, 100)
                    if (sessionAdapter.contextPct[id] != pct) sessionAdapter.contextPct = sessionAdapter.contextPct + (id to pct)
                }
            }
            if (activeSessionId == id) {
                if (info != null) contextInfo = info
                paintContextRing()
                done?.invoke()
            }
        }
    }.start()
}

internal fun MainActivity.showContextSheet() {
    val id = activeSessionId ?: return
    val s = chatStore.get(id) ?: return
    refreshContext(id, s.model, totals = true) {
        val c = contextInfo
        fun row(label: String, sub: String) = SelectRow(label, sub, checked = false, action = {})
        if (c == null) {
            showSelectSheet("Context", listOf(row("Context", "nothing recorded yet")))
            return@refreshContext
        }
        val rows = mutableListOf<SelectRow>()
        rows += row("Context", when {
            c.used <= 0 -> "nothing recorded yet"
            c.window > 0 -> "${fmtTokens(c.used)} of ${fmtTokens(c.window)} · ${100L * c.used / c.window}%"
            else -> "${fmtTokens(c.used)} tokens · window unknown"
        })
        if (c.used > 0) rows += row("Last request",
            "in ${fmtTokens(c.input)} · out ${fmtTokens(c.output)} · cache read ${fmtTokens(c.cacheRead)} · write ${fmtTokens(c.cacheWrite)}")
        if (c.totalTokens > 0) {
            val totalScope = when (c.backend) {
                "codex" -> "cumulative thread total"
                "claude" -> "tracked during this server run"
                "opencode" -> "from up to 300 history messages"
                else -> "recorded total"
            }
            rows += row("Chat total", "${fmtTokens(c.totalTokens)} tokens · $totalScope")
        }
        rows += row("Cost", when {
            c.costUsd == null -> "not reported"
            c.backend == "claude" -> String.format(java.util.Locale.US, "$%.2f (API-price estimate)", c.costUsd)
            else -> String.format(java.util.Locale.US, "$%.2f", c.costUsd)
        })
        rows += row("Model", listOf(c.model, c.backend).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "unknown" })
        rows += row("Auto-compact", if (c.compactAt > 0) {
            val limit = c.compactAt.toLong()
            val status = when {
                c.used <= 0 -> "current usage unknown"
                c.used >= limit -> "threshold reached"
                else -> "${fmtTokens(limit - c.used)} remaining"
            }
            "at ${fmtTokens(limit)} tokens · $status"
        } else "Default · threshold not reported")
        showSelectSheet("Context", rows)
    }
}

internal fun MainActivity.paintModelChip(backend: String, model: String) {
    // Badge back on the chip: 12dp so the mark reads instead of blobbing.
    try { modelChip.setIconResource(agentIcon(backend)) } catch (_: Exception) {}
    modelChip.text = chipText(backend, model)
}

/** Schedule threads (`schedule:` / `job:`) have no backend of their own: they follow the
 *  global default, so the picker and the next turn both use the backend the user switched to.
 *  A switch parks the old backend's session id and resumes the new backend's own (ChatStore
 *  keeps one per backend). A stored id in the wrong shape for the current backend (a ses_ id
 *  left on claude) is dropped.
 *  Returns true when the thread changed (model reset to that backend's default). */
internal fun MainActivity.alignScheduleChatBackend(id: String): Boolean {
    if (!isScheduleChat(id)) return false
    val s = chatStore.get(id) ?: return false
    val def = prefs.defaultBackend()
    val switched = Prefs.cannonBackend(s.backend) != def
    if (switched) chatStore.setBackendAndModel(id, def, prefs.defaultModel(def))
    // Re-read: a switch just swapped in the new backend's id.
    val active = chatStore.get(id)?.remoteId.orEmpty()
    val foreign = active.isNotBlank() && !remoteIdFits(def, active)
    if (foreign) chatStore.setRemoteId(id, "")
    return switched || foreign
}

/** Remote id shape per backend: opencode ids are ses_-prefixed, claude ids are UUIDs,
 *  codex ids are opaque. Pure. */
internal fun MainActivity.remoteIdFits(backend: String, remote: String): Boolean = when (Prefs.cannonBackend(backend)) {
    Prefs.BACKEND_OPENCODE -> remote.startsWith("ses_")
    Prefs.BACKEND_CLAUDE -> UUID_SHAPE.matches(remote)
    else -> true
}
