package com.terminus.rec

import android.content.Intent
import android.media.RingtoneManager
import android.view.View
import android.widget.EditText
import android.widget.TextView

/** The settings screen. Extension functions of [MainActivity], moved out of it unchanged. */

/** Settings cog: default model + system prompt (server /api/defaults)
 *  plus Server URL + token (local Prefs). Save writes both. */
internal fun MainActivity.showDefaults() {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val v = layoutInflater.inflate(R.layout.sheet_defaults, null)
    sheet.setContentView(v)
    val modelValue: TextView = v.findViewById(R.id.defModelValue)
    val backendValue: TextView = v.findViewById(R.id.defBackendValue)
    val promptBox: EditText = v.findViewById(R.id.defSystemPrompt)
    val compactValue: TextView = v.findViewById(R.id.defCompactValue)
    val urlBox: EditText = v.findViewById(R.id.defServerUrl)
    val tokBox: EditText = v.findViewById(R.id.defApiToken)
    val saved: TextView = v.findViewById(R.id.defSaved)
    val voiceSend: android.widget.CheckBox = v.findViewById(R.id.defVoiceSend)
    val voiceSendPhrase: EditText = v.findViewById(R.id.defVoiceSendPhrase)
    val voiceSendHelp: TextView = v.findViewById(R.id.defVoiceSendHelp)
    voiceSend.isChecked = prefs.voiceSendEnabled()
    voiceSendPhrase.setText(prefs.voiceSendPhrase())
    val linkPreviews: android.widget.CheckBox = v.findViewById(R.id.defLinkPreviews)
    linkPreviews.isChecked = prefs.linkPreviews()
    linkPreviews.setOnCheckedChangeListener { _, checked -> prefs.setLinkPreviews(checked) }
    val hotkeys: android.widget.CheckBox = v.findViewById(R.id.defHotkeys)
    hotkeys.isChecked = prefs.hotkeysEnabled()
    hotkeys.setOnCheckedChangeListener { _, checked -> prefs.setHotkeysEnabled(checked) }

    fun wireSection(headerId: Int, bodyId: Int, arrowId: Int, expandedInitially: Boolean) {
        val header = v.findViewById<View>(headerId)
        val body = v.findViewById<View>(bodyId)
        val arrow = v.findViewById<TextView>(arrowId)
        fun render(expanded: Boolean) {
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.text = if (expanded) "⌃" else "⌄"
            header.contentDescription = "${header.findViewWithTag<TextView>("title")?.text} settings, " +
                if (expanded) "expanded" else "collapsed"
        }
        render(expandedInitially)
        header.setOnClickListener { render(body.visibility != View.VISIBLE) }
    }
    wireSection(R.id.defSectionChatHeader, R.id.defSectionChatBody, R.id.defSectionChatArrow, true)
    wireSection(R.id.defSectionVoiceHeader, R.id.defSectionVoiceBody, R.id.defSectionVoiceArrow, false)
    wireSection(R.id.defSectionAppearanceHeader, R.id.defSectionAppearanceBody, R.id.defSectionAppearanceArrow, false)
    // Connection opens by itself on first run (no password yet), where it is what the person needs.
    wireSection(R.id.defSectionConnHeader, R.id.defSectionConnBody, R.id.defSectionConnArrow, prefs.token().isBlank())
    wireSection(R.id.defSectionAdvancedHeader, R.id.defSectionAdvancedBody, R.id.defSectionAdvancedArrow, false)

    // One-line summaries on the section cards; the render functions below call summaryRefresh().
    var summaryRefresh: () -> Unit = {}

    fun renderVoicePhrase() {
        val visible = voiceSend.isChecked
        voiceSendPhrase.visibility = if (visible) View.VISIBLE else View.GONE
        voiceSendHelp.visibility = if (visible) View.VISIBLE else View.GONE
    }
    renderVoicePhrase()
    voiceSend.setOnCheckedChangeListener { _, checked ->
        prefs.setVoiceSendEnabled(checked)
        renderVoicePhrase()
        summaryRefresh()
    }
    voiceSendPhrase.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            prefs.setVoiceSendPhrase(s?.toString().orEmpty())
            summaryRefresh()
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    (v.findViewById<TextView>(R.id.defBuild)).text =
        "build ${com.terminus.rec.BuildConfig.VERSION_CODE} · ${com.terminus.rec.BuildConfig.VERSION_NAME}"
    fun renderBackend() {
        backendValue.text = backendDisplayName(prefs.defaultBackend())
        summaryRefresh()
    }
    fun renderDefault() {
        val id = prefs.defaultModel(prefs.defaultBackend())
        val picked = shortModelLabel(id).ifBlank { null }
        val eff = picked ?: shortModelLabel(serverDefaultModel).ifBlank { null }
        modelValue.text = if (picked != null) picked
            else if (eff != null) "Default ($eff)" else "server default"
        summaryRefresh()
    }
    renderBackend()
    renderDefault()
    // Silent catalog refresh so capability gates (compact/fork/title)
    // track the live server; offline keeps the last catalog.
    refreshBackends()
    urlBox.setText(prefs.serverUrl())
    tokBox.setText(prefs.token())
    // Chat sound: system tone picker → stored pref → applied to channel.
    val soundValue: android.widget.TextView = v.findViewById(R.id.defSoundValue)
    toneLabel = soundValue
    soundValue.text = toneName()
    sheet.setOnDismissListener { if (toneLabel === soundValue) toneLabel = null }
    v.findViewById<View>(R.id.defSoundRow).setOnClickListener {
        ensureNotifPermission()
        val cur = ChatNotify.soundUri(this)
        val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
        }
        try { soundPickerLauncher.launch(pick) } catch (_: Exception) {}
    }
    v.findViewById<View>(R.id.defChannelSettings).setOnClickListener {
        try { startActivity(ChatNotify.channelSettings(this)) } catch (_: Exception) {}
    }
    // Appearance: System / Light / Dark → stored pref, applied via
    // AppCompatDelegate, activity recreates so the theme swaps cleanly.
    val appearanceValue: TextView = v.findViewById(R.id.defAppearanceValue)
    fun renderAppearance() {
        appearanceValue.text = when (prefs.appearance()) {
            "light" -> "light"
            "dark" -> "dark"
            else -> "system"
        }
        summaryRefresh()
    }
    renderAppearance()
    v.findViewById<View>(R.id.defAppearanceRow).setOnClickListener {
        val opts = listOf("System", "Light", "Dark")
        val cur = when (prefs.appearance()) {
            "light" -> 1
            "dark" -> 2
            else -> 0
        }
        showSelectSheet("Appearance", opts.mapIndexed { index, label ->
            SelectRow(label, checked = index == cur, action = {
                val mode = when (index) {
                    1 -> "light"
                    2 -> "dark"
                    else -> "system"
                }
                prefs.setAppearance(mode)
                renderAppearance()
                applyAppearance(mode)
                // Recreate cleanly; normal launches return to the home list.
                try { recreate() } catch (_: Exception) {}
                try { sheet.dismiss() } catch (_: Exception) {}
            })
        })
    }
    var promptDirty = false
    var promptLoaded = false
    val promptPreview: TextView = v.findViewById(R.id.defPromptPreview)
    val promptChevron: TextView = v.findViewById(R.id.defPromptChevron)
    fun renderPromptPreview() {
        val t = promptBox.text.toString()
        promptPreview.text = if (t.isBlank()) "built-in" else "${t.length} chars"
        promptChevron.text = if (promptBox.visibility == View.VISIBLE) "⌃" else "›"
    }
    v.findViewById<View>(R.id.defPromptHeader).setOnClickListener {
        promptBox.visibility = if (promptBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderPromptPreview()
    }
    promptBox.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { promptDirty = true; renderPromptPreview() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    renderPromptPreview()
    var compactAt = 0
    fun renderCompact() { compactValue.text = if (compactAt <= 0) "Default" else "${compactAt / 1000}k tokens" }
    renderCompact()
    // Current server values fill the sheet; a user edit in flight wins.
    Thread {
        try {
            val cur = OpencodeApi.fetchDefaults(prefs.serverUrl(), prefs.token())
            runOnUiThread {
                noteLanFallback(activeSessionId)
                compactAt = cur.compactAt
                renderCompact()
                if (!promptDirty) {
                    promptBox.setText(cur.systemPrompt)
                    // Only a loaded value may travel: if this fetch
                    // failed (offline), the box stays blank and a save
                    // must leave the server prompt untouched, never wipe
                    // it with a blank-means-clear.
                    promptLoaded = true
                }
                if (cur.serverDefault.isNotBlank() && cur.serverDefault != serverDefaultModel) {
                    serverDefaultModel = cur.serverDefault
                    val open = activeSessionId
                    if (open != null) renderThread(open)
                }
                if (cur.defaultBackend != prefs.defaultBackend()) {
                    prefs.setDefaultBackend(cur.defaultBackend)
                    renderBackend()
                }
                var modelsChanged = false
                for ((k, mid) in cur.defaultModels) {
                    if (Prefs.validBackend(k) && mid != prefs.defaultModel(k)) {
                        prefs.setDefaultModel(k, mid)
                        modelsChanged = true
                    }
                }
                if (cur.defaultModels.isEmpty() && cur.defaultModel.isNotBlank() &&
                    cur.defaultModel != prefs.defaultModel(cur.defaultBackend)) {
                    prefs.setDefaultModel(cur.defaultBackend, cur.defaultModel)
                    modelsChanged = true
                }
                if (modelsChanged) renderDefault()
            }
        } catch (_: Exception) {}
    }.start()
    v.findViewById<View>(R.id.defBackendRow).setOnClickListener {
        backendDialog { renderBackend(); renderDefault() }
    }
    v.findViewById<View>(R.id.defModelRow).setOnClickListener {
        modelDialog(asDefault = true, onDefaultPick = { renderDefault() })
    }
    v.findViewById<View>(R.id.defCompactRow).setOnClickListener {
        val rows = listOf(0, 80_000, 100_000, 150_000, 200_000, 500_000).map { n ->
            SelectRow(
                if (n == 0) "Default" else "${n / 1000}k tokens",
                if (n == 0) "Each backend keeps its own behaviour" else "",
                checked = n == compactAt,
                action = {
                    compactAt = n
                    renderCompact()
                    Thread {
                        try { OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, null, null, n) } catch (_: Exception) {}
                    }.start()
                })
        }
        showSelectSheet("Auto-compact at", rows)
    }
    v.findViewById<View>(R.id.defCancel).setOnClickListener { sheet.dismiss() }
    // Connection: password reveal, a test that never touches saved values, and plain-language results.
    val connStatus: TextView = v.findViewById(R.id.defConnStatus)
    val tokToggle: TextView = v.findViewById(R.id.defTokenToggle)
    var tokShown = false
    tokToggle.setOnClickListener {
        tokShown = !tokShown
        tokBox.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            (if (tokShown) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
             else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        tokBox.setSelection(tokBox.text.length)
        tokToggle.text = if (tokShown) "Hide" else "Show"
    }
    fun colorOf(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
    fun checkConnection(url: String, tok: String, done: (Boolean, String) -> Unit) {
        Thread {
            try {
                OpencodeApi.fetchDefaults(url, tok)
                runOnUiThread { done(true, "") }
            } catch (e: Exception) {
                runOnUiThread { done(false, ConnectionCheck.describe(e)) }
            }
        }.start()
    }
    v.findViewById<View>(R.id.defTestConn).setOnClickListener {
        val url = ConnectionCheck.normalizeUrl(urlBox.text.toString())
        if (url == null) {
            connStatus.text = "Address must start with http:// or https://"
            connStatus.setTextColor(colorOf(R.color.t_err))
            return@setOnClickListener
        }
        connStatus.text = "Testing…"
        connStatus.setTextColor(colorOf(R.color.t_muted))
        checkConnection(url, tokBox.text.toString().trim()) { ok, why ->
            connStatus.text = if (ok) "Connected ✓" else "Can't connect: $why"
            connStatus.setTextColor(colorOf(if (ok) R.color.t_ok else R.color.t_err))
        }
    }
    urlBox.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { summaryRefresh() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    tokBox.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { summaryRefresh() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
    // Live one-line summaries on the cards (shown when a section is collapsed).
    val chatSummary: TextView = v.findViewById(R.id.defChatSummary)
    val voiceSummary: TextView = v.findViewById(R.id.defVoiceSummary)
    val appearanceSummary: TextView = v.findViewById(R.id.defAppearanceSummary)
    val connSummary: TextView = v.findViewById(R.id.defConnSummary)
    val advancedSummary: TextView = v.findViewById(R.id.defAdvancedSummary)
    summaryRefresh = {
        chatSummary.text = "${backendValue.text} · ${modelValue.text}"
        voiceSummary.text = if (voiceSend.isChecked)
            "Send word: \u201C${voiceSendPhrase.text.toString().trim().ifBlank { "send" }}\u201D"
        else "Voice send off"
        appearanceSummary.text = appearanceValue.text.toString().replaceFirstChar { it.uppercase() }
        connSummary.text = ConnectionCheck.hostOf(urlBox.text.toString()) +
            if (tokBox.text.isNullOrBlank()) " · no password" else ""
        advancedSummary.text = "App build " + BuildConfig.VERSION_CODE
    }
    summaryRefresh()

    v.findViewById<View>(R.id.defSave).setOnClickListener {
        saved.visibility = View.GONE
        fun status(text: String, colorId: Int) {
            saved.text = text
            saved.setTextColor(colorOf(colorId))
            saved.visibility = View.VISIBLE
        }
        val newUrl = ConnectionCheck.normalizeUrl(urlBox.text.toString())
        val newTok = tokBox.text.toString().trim()
        if (newUrl == null) {
            status("Not saved: the server address must start with http:// or https://", R.color.t_err)
            v.findViewById<View>(R.id.defSectionConnBody).visibility = View.VISIBLE
            return@setOnClickListener
        }
        val newPrompt = promptBox.text.toString()
        val connectionChanged = newUrl != prefs.serverUrl().trim().trimEnd('/') || newTok != prefs.token()
        // Commit only after the new connection answered, so a typo never replaces a working setup.
        fun commitAndPush() {
            if (connectionChanged) {
                prefs.setServerUrl(newUrl)
                prefs.setToken(newTok)
                LinkToken.refresh(this@showDefaults, prefs)
                ensureServerCookie()
                galleryWeb.loadUrl("about:blank")
                opsWeb.loadUrl("about:blank")
                notesWeb.loadUrl("about:blank")
            }
            prefs.setVoiceSendEnabled(voiceSend.isChecked)
            prefs.setVoiceSendPhrase(voiceSendPhrase.text.toString())
            pushDefaults(prefs.defaultModel(prefs.defaultBackend()), if (promptLoaded) newPrompt else null, prefs.defaultBackend()) { ok, msg ->
                if (ok) {
                    status("saved ✓", R.color.t_ok)
                    saved.postDelayed({ try { sheet.dismiss() } catch (_: Exception) {} }, 700)
                } else {
                    // Local settings are already kept; only the server update failed.
                    status("Saved on this phone. Server not updated: $msg", R.color.t_err)
                }
            }
        }
        if (connectionChanged) {
            status("Checking connection…", R.color.t_muted)
            checkConnection(newUrl, newTok) { ok, why ->
                if (ok) commitAndPush()
                else {
                    status("Not saved: can't connect ($why). Your previous connection is unchanged.", R.color.t_err)
                    v.findViewById<View>(R.id.defSectionConnBody).visibility = View.VISIBLE
                }
            }
        } else commitAndPush()
    }
    // Let the sheet go nearly full height and start expanded: the settings
    // list is long, and a locked half-height sheet traps the lower half.
    sheet.setOnShowListener {
        val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottom?.layoutParams = bottom?.layoutParams?.apply { height = (resources.displayMetrics.heightPixels * 0.92f).toInt() }
        try {
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        } catch (_: Exception) {}
    }
    sheet.show()
}

/** POST device defaults in the background. Null prompt = leave the
 *  server prompt untouched (never send a blank that would clear it).
 *  Token passes through memory only; only the outcome (never values)
 *  reaches the UI. */
internal fun MainActivity.pushDefaults(
    defaultModel: String,
    systemPrompt: String?,
    backend: String = prefs.defaultBackend(),
    done: (Boolean, String) -> Unit,
) {
    Thread {
        try {
            val stored = OpencodeApi.postDefaults(
                prefs.serverUrl(), prefs.token(), defaultModel.takeIf { backend == Prefs.BACKEND_OPENCODE }, systemPrompt, backend,
                mapOf(backend to defaultModel))
            prefs.setDefaultBackend(stored.defaultBackend.ifBlank { backend })
            for ((k, v) in stored.defaultModels) prefs.setDefaultModel(k, v)
            if (stored.defaultModels.isEmpty() && stored.defaultModel.isNotBlank())
                prefs.setDefaultModel(backend, stored.defaultModel)
            runOnUiThread {
                noteLanFallback(activeSessionId)
                done(true, "")
            }
        } catch (e: Exception) {
            runOnUiThread { done(false, e.message ?: "network error") }
        }
    }.start()
}
