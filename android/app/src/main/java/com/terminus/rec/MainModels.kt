package com.terminus.rec

import android.view.View
import android.widget.EditText
import android.widget.TextView

/** Backends and models: catalogs, pickers and new-chat defaults. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.chatBackendOf(id: String): String? =
    try { chatStore.get(id)?.backend } catch (_: Exception) { null }

/** Backend driving pickers for the current context: the open chat owns
 *  its backend permanently (M5); without an open chat the global
 *  default seeds the next new chat. Never derived from a model id. */
internal fun MainActivity.activeBackend(): String =
    activeSessionId?.let { chatStore.get(it)?.backend }?.let { Prefs.cannonBackend(it) }
        ?: prefs.defaultBackend()

internal fun MainActivity.modelsForBackend(backend: String): List<RemoteModel> =
    remoteModelsByBackend[backend].orEmpty()

/** start...end for long provider/model ids. */
internal fun MainActivity.midId(id: String): String =
    if (id.length <= 28) id else "${id.take(12)}...${id.takeLast(12)}"

internal fun MainActivity.refreshModels(then: ((List<RemoteModel>) -> Unit)? = null) =
    refreshModels(activeBackend(), then)

internal fun MainActivity.refreshModels(backend: String, then: ((List<RemoteModel>) -> Unit)? = null) {
    if (!modelLoadingBackends.add(backend)) return
    Thread {
        val list = try {
            OpencodeApi.fetchModels(prefs.serverUrl(), prefs.token(), backend)
        } catch (_: Exception) { null }
        runOnUiThread {
            modelLoadingBackends.remove(backend)
            if (list != null) {
                remoteModelsByBackend[backend] = list
                unavailableModelCatalogs.remove(backend)
                noteLanFallback(activeSessionId)
                val id = activeSessionId
                if (id != null) renderThread(id) else if (draftOpen) renderDraft()
            } else {
                remoteModelsByBackend.remove(backend)
                unavailableModelCatalogs.add(backend)
            }
            then?.invoke(list ?: modelsForBackend(backend))
        }
    }.start()
}

/** Refresh the backend catalog (descriptors + capabilities). Throws
 *  on failure — callers keep the last catalog when offline. */
internal fun MainActivity.refreshBackends(then: ((BackendCatalog) -> Unit)? = null) {
    Thread {
        val cat = try {
            OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token())
        } catch (_: Exception) { null }
        runOnUiThread {
            if (cat != null) {
                backendCatalog = cat
                noteLanFallback(activeSessionId)
            }
            then?.invoke(cat ?: backendCatalog ?: BackendCatalog(emptyList(), prefs.defaultBackend()))
        }
    }.start()
}

internal fun MainActivity.modelDialog(prefilter: String = "", asDefault: Boolean = false, onDefaultPick: ((RemoteModel) -> Unit)? = null) {
    val id = activeSessionId
    val backend = activeBackend()
    val backendName = backendDisplayName(backend)
    val current = if (asDefault) prefs.defaultModel(backend)
        else id?.let { chatStore.get(it)?.model }
            ?: prefs.defaultModel(backend).ifBlank { ChatStore.defaultModel() }
    val sheetTitle = if (asDefault) "Default model, $backendName" else "Model for this chat, $backendName"
    // What "follow the default" resolves to right now (blank = unknown).
    val defResolved = prefs.defaultModel(backend).ifBlank { serverDefaultModel }
    val defShort = shortModelLabel(defResolved)
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val v = layoutInflater.inflate(R.layout.sheet_model, null)
    val title: TextView = v.findViewById(R.id.modelTitle)
    val box: EditText = v.findViewById(R.id.modelFilter)
    val listView: android.widget.ListView = v.findViewById(R.id.modelList)
    box.setText(prefilter)
    title.text = sheetTitle
    if (modelsForBackend(backend).isEmpty()) title.text = "$sheetTitle · Loading live list…"
    var shown: List<RemoteModel> = emptyList()
    val adapter = object : android.widget.ArrayAdapter<RemoteModel>(
        this, R.layout.item_model, mutableListOf()) {
        override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup): View {
            val row = convert ?: layoutInflater.inflate(R.layout.item_model, parent, false)
            val m = getItem(pos) ?: return row
            val name: TextView = row.findViewById(R.id.modelName)
            val prov: TextView = row.findViewById(R.id.modelProvider)
            val check: TextView = row.findViewById(R.id.modelCheck)
            if (m.id.isBlank()) {
                // Synthetic default row: always named, checked when
                // nothing picked, resolves to the effective model.
                name.text = if (defShort.isNotBlank()) "Default ($defShort)" else "Default"
                prov.text = "$backendName default"
                check.visibility = if (current.isBlank()) View.VISIBLE else View.GONE
            } else {
                name.text = shortModelLabel(m.id).ifBlank { m.id }
                val sub = m.provider.takeUnless { it.isBlank() }
                    ?: m.id.substringBefore("/").takeUnless { it == m.id }
                    ?: backendName
                prov.text = sub
                check.visibility = if (m.id == current) View.VISIBLE else View.GONE
            }
            return row
        }
    }
    listView.adapter = adapter
    fun applyFilter(q: String) {
        val all = modelsForBackend(backend)
        val rest = if (q.isBlank()) all
            else all.filter { it.id.contains(q, true) || it.label.contains(q, true) }
        // Selected model first with its tick, then the Default row,
        // then the rest. Default row still checked when nothing picked.
        val defRow = RemoteModel("", "server default", "Default")
        val matchesDef = q.isBlank() || "default".contains(q, true)
        val sel = if (current.isBlank()) emptyList() else rest.filter { it.id == current }
        val others = rest.filter { it.id != current }
        shown = sel + (if (matchesDef) listOf(defRow) else emptyList()) + others
        adapter.clear()
        adapter.addAll(shown)
        adapter.notifyDataSetChanged()
        title.text = when {
            modelLoadingBackends.contains(backend) -> "$sheetTitle · Loading live list…"
            unavailableModelCatalogs.contains(backend) -> "$sheetTitle · Live list unavailable"
            all.isEmpty() -> "$sheetTitle · Server returned no models"
            else -> "$sheetTitle · ${all.size} models"
        }
    }
    box.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter(s?.toString() ?: "") }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    listView.setOnItemClickListener { _, _, pos, _ ->
        val m = shown.getOrNull(pos) ?: return@setOnItemClickListener
        if (asDefault) {
            // Default pick: saved as the device default AND applied to
            // the current chat. Pushed to the server best-effort; the
            // cog Save posts the full set anyway.
            prefs.setDefaultModel(backend, m.id)
            if (id != null) { chatStore.setModel(id, m.id); renderThread(id) }
            renderSessions()
            onDefaultPick?.invoke(m)
            pushDefaultModel(backend, m.id)
        } else if (id != null) {
            // Per-chat pick: local record only. The model travels on
            // every send, so no server sync is needed — and pushing
            // here flipped the server default (plus the device echo)
            // for every other chat. Settings owns defaults, not picks.
            chatStore.setModel(id, m.id)
            renderThread(id)
            renderSessions()
            if (backendCatalog?.backends?.firstOrNull { it.id == backend }?.capabilities?.effort == true) effortDialog(id)
        }
        else if (!asDefault) {
            // Draft pick: persist the draft on first model pick, carrying
            // the picked model (composer text/quote/attach ride along).
            val nid = ensureDraftSession(m.id)
            renderThread(nid)
        }
        sheet.dismiss()
    }
    sheet.setContentView(v)
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    applyFilter(prefilter)
    box.setSelection(box.text.length)
    refreshModels(backend) { applyFilter(box.text.toString()) }
}

/** Effort picker, shown right after a model pick in a codex chat
 *  (reuses the row component; nothing new in settings or thread).
 *  Stored per chat, sent with every turn. Other backends ignore it. */
internal fun MainActivity.effortDialog(id: String) {
    val cur = try { chatStore.get(id)?.effort ?: "" } catch (_: Exception) { "" }
    val opts = listOf("low" to "Low", "medium" to "Medium", "high" to "High")
    val effBackend = try { backendDisplayName(chatStore.get(id)?.backend ?: "codex") } catch (_: Exception) { "Codex" }
    showSelectSheet("Effort · $effBackend", opts.map { (value, label) ->
        SelectRow(label, checked = (cur.ifBlank { "medium" }) == value, action = {
            chatStore.setEffort(id, value)
            renderThread(id)
            renderSessions()
        })
    })
}

/** POST a default-model pick on its own (prompt key omitted → unchanged).
 *  Uses the per-backend map so the model lands in this backend's slot
 *  even if the server default backend differs. */
/** Settings-owned sync: push a new device default to the server and
 *  adopt its normalized echo. Never called from per-chat picks. */
internal fun MainActivity.pushDefaultModel(backend: String, modelId: String) {
    Thread {
        try {
            val stored = OpencodeApi.postDefaults(
                prefs.serverUrl(), prefs.token(),
                if (backend == "opencode") modelId else null,
                null, null, mapOf(backend to modelId))
            val normalized = stored.defaultModels[backend] ?: modelId
            runOnUiThread { prefs.setDefaultModel(backend, normalized) }
        } catch (_: Exception) {}
    }.start()
}

internal fun MainActivity.renameDialog(s: ChatSession) {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val v = layoutInflater.inflate(R.layout.sheet_rename, null)
    val box: EditText = v.findViewById(R.id.renameField)
    box.setText(s.title)
    box.setSelection(box.text.length)
    sheet.setContentView(v)
    v.findViewById<View>(R.id.renameSave).setOnClickListener {
        val name = box.text.toString().trim()
        chatStore.rename(s.id, name, locked = true)
        sheet.dismiss()
        renderSessions()
        if (activeSessionId == s.id) renderThread(s.id)
        // Propagate the manual name to the bound provider session.
        if (name.isNotBlank()) {
            val sid = s.remoteId
            if (sid.isNotBlank() && capsForBackend(backendCatalog, s.backend).title) Thread {
                try { OpencodeApi.postTitleSet(prefs.serverUrl(), prefs.token(), sid, name, s.backend, s.id) }
                catch (_: Exception) { runOnUiThread {
                    android.widget.Toast.makeText(this, "Renamed here, but couldn't rename it in ${backendDisplayName(s.backend)}", android.widget.Toast.LENGTH_LONG).show()
                } }
            }.start()
        }
    }
    v.findViewById<View>(R.id.renameCancel).setOnClickListener { sheet.dismiss() }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}

/** Device default model for freshly created chats
 *  ("provider/model", blank = server default). */
internal fun MainActivity.newChatModel(): String = prefs.defaultModel()

internal fun MainActivity.newChatConfig(): ChatConfig {
    val backend = prefs.defaultBackend()
    return ChatConfig(backend, prefs.defaultModel(backend))
}

/** Supported-backends selector for the settings cog. Changing the global default
 *  never touches existing chats — it only seeds the next new chat.
 *  Unavailable backends stay selectable only in label, never applied. */
internal fun MainActivity.backendDialog(onPick: () -> Unit = {}) {
    refreshBackends { cat -> showBackendChoices(cat, onPick) }
}

/** Instant toggle to the previous backend (triple-tap chat tab): no
 *  list, no choosing — default flips, list re-scopes, toast confirms.
 *  Falls back to the other primary backend on first use. */
internal fun MainActivity.quickToggleBackend() {
    val cur = prefs.defaultBackend()
    var target = prefs.prevBackend().takeIf { Prefs.validBackend(it) && it != cur }
    if (target == null) {
        target = if (cur == "codex") "opencode" else "codex"
    }
    prefs.setPrevBackend(cur)
    prefs.setDefaultBackend(target)
    activeSessionId?.let { if (alignScheduleChatBackend(it)) renderThread(it) }
    refreshModels(target)
    refreshSessions()
    Thread {
        try {
            OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, target, null)
        } catch (_: Exception) {}
    }.start()
    android.widget.Toast.makeText(this,
        "New chats will use ${backendDisplayName(target)}",
        android.widget.Toast.LENGTH_SHORT).show()
}

/** Backend choice rows (pure-adjacent: catalog in, ids/labels out).
 *  Unavailable backends render struck-out and never apply. */
internal fun MainActivity.backendChoices(cat: BackendCatalog): Pair<List<String>, List<String>> {
    val ids = if (cat.backends.isNotEmpty()) cat.backends.map { it.id }
        else listOf("opencode", "codex", "claude", "agy")
    val labels = ids.map { id ->
        val info = cat.backends.firstOrNull { it.id == id }
        val base = info?.label ?: backendDisplayName(id)
        if (info != null && !info.available) "$base (unavailable)"
        else if (info != null && !info.authenticated) "$base (login required)"
        else base
    }
    return ids to labels
}

internal fun MainActivity.showBackendChoices(cat: BackendCatalog, onPick: () -> Unit) {
    val ids = backendChoices(cat).first
    val cur = prefs.defaultBackend()
    val rows = ids.mapIndexed { index, id ->
        val info = cat.backends.firstOrNull { it.id == id }
        // Logo, name, and the model new chats will start on; status replaces the model when it can't run.
        val sub = when {
            info?.available == false -> "Unavailable on the Mac"
            info?.authenticated == false -> "Login required"
            else -> shortModelLabel(prefs.defaultModel(id))
        }
        SelectRow(info?.label ?: backendDisplayName(id), sub,
            checked = id == cur, icon = agentIcon(id), dim = info?.available == false,
            action = {
                if (!Prefs.validBackend(id) || info?.available == false) return@SelectRow
                val old = prefs.defaultBackend()
                if (old != id) prefs.setPrevBackend(old)
                prefs.setDefaultBackend(id)
                activeSessionId?.let { if (alignScheduleChatBackend(it)) renderThread(it) }
                onPick()
                refreshModels(id)
                refreshSessions()
                Thread {
                    try { OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, id, null) } catch (_: Exception) {}
                }.start()
            })
    }
    showSelectSheet("Backend", rows)
}
