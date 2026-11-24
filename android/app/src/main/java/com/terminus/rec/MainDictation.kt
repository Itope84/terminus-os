package com.terminus.rec

import android.content.Intent
import android.os.Build
import android.view.View
import com.terminus.rec.MainActivity.Companion.recordingsDir
import java.io.File

/** Dictation and voice recording in chat. Extension functions of [MainActivity], moved out of it unchanged. */

/** Chat mic: dictate in-app (waits through pauses); tap again to stop. Falls back to the system popup. */
internal fun MainActivity.onChatMic() {
    if (dictating) { stopDictation(); return }
    if (!hasMic()) { toast("Allow the mic, then tap again"); askMic(); return }
    startDictation()
}

/** System speech popup: one phrase per tap. Used when this phone has no in-app recognizer. */
internal fun MainActivity.onChatMicIntent() {
    if (!isThreadOpen()) openDraft()
    val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
            "Message terminus — say ‘${prefs.voiceSendPhrase()}’ to send")
        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
    }
    try {
        speechLauncher.launch(i)
    } catch (_: Exception) {
        toast("This phone has no speech recognition")
    }
}

internal fun MainActivity.dictJoin(a: String, b: String): String =
    if (a.isBlank()) b else if (b.isBlank()) a else a.trimEnd() + " " + b

/** If the rendered text currently ends with the send word, start (or keep) the send timer;
 *  anything else stands it down. Fired from the idle tick, so a send word that never gets a
 *  final result still sends after a short silence. */
internal fun MainActivity.armSendIfReady() {
    if (!dictating || !prefs.voiceSendEnabled()) { dictSendArmedAt = 0L; return }
    val phrase = Regex.escape(prefs.voiceSendPhrase())
    if (Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$").containsMatchIn(composerInput.text.toString())) {
        if (dictSendArmedAt == 0L) dictSendArmedAt = android.os.SystemClock.elapsedRealtime()
    } else dictSendArmedAt = 0L
}

/** The armed send word survived its silence: strip it and send what remains. */
internal fun MainActivity.fireArmedSend() {
    if (!dictating || dictSendArmedAt == 0L) return
    dictSendArmedAt = 0L
    commitPartial()
    renderDictation("")
    if (!dictating) return
    val phrase = Regex.escape(prefs.voiceSendPhrase())
    val sendSuffix = Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$")
    val said = composerInput.text.toString()
    if (!sendSuffix.containsMatchIn(said)) return
    dictDone = said.replace(sendSuffix, "").trimEnd().let { full ->
        if (dictBase.isNotBlank() && full.startsWith(dictBase)) full.removePrefix(dictBase).trim() else full
    }
    renderDictation("")
    if (!dictating) return
    stopDictation()
    if (composerInput.text.isNotBlank()) sendChat(voiceSendCommand = true)
}

/** The phrase heard so far becomes final text (a pause must never discard it). */
internal fun MainActivity.commitPartial() {
    if (dictPartial.isNotBlank()) dictDone = dictJoin(dictDone, dictPartial)
    dictPartial = ""
}

internal fun MainActivity.firstWord(t: String): String =
    t.trimStart().substringBefore(' ').lowercase().trim('.', ',', '!', '?')

internal fun MainActivity.startDictation() {
    if (!isThreadOpen()) openDraft()
    replyVoice.stop()
    dictating = true
    dictBase = composerInput.text.toString()
    dictDone = ""
    dictRendered = dictBase
    dictPartial = ""
    dictSendArmedAt = 0L
    dictErrors = 0
    dictLastHeard = android.os.SystemClock.elapsedRealtime()
    setDictationUi(true)
    startMacDictation()
    dictHandler.postDelayed(dictIdleCheck, 500)
}

/** Dictation runs on saywhat on the Mac when it can (local, live text); the phone's recogniser takes
 *  over the moment it can't: Mac unreachable, saywhat missing, or the link dropping mid-dictation. */
internal fun MainActivity.startMacDictation() {
    val d = MacDictation(prefs.serverUrl(), prefs.token(),
        onEvent = { e -> if (dictating && macDict != null) { if (e.final) dictHeardFinal(e.text, relisten = false) else dictHeardPartial(e.text) } },
        onFail = { why, worked ->
            if (dictating && macDict != null) {
                macDict = null
                commitPartial(); renderDictation("")
                if (worked) toast("Mac dictation stopped ($why); using the phone")
                if (dictating) listenOnce()
            }
        })
    macDict = d
    d.start()
}

internal fun MainActivity.listenOnce() {
    if (!dictating) return
    // No in-app recogniser on this phone: the system popup, one phrase per tap.
    if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { stopDictation(); onChatMicIntent(); return }
    val r = try {
        recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(dictListener); recognizer = it
        }
    } catch (_: Exception) { stopDictation(); onChatMicIntent(); return }
    val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
    }
    try { r.startListening(i) } catch (_: Exception) { toast("Voice input isn't available"); stopDictation() }
}

/** Show what has been heard so far in the composer. If the person edited the box meanwhile, they take over. */
internal fun MainActivity.renderDictation(partial: String) {
    if (composerInput.text.toString() != dictRendered) { stopDictation(); return }
    val joined = dictJoin(dictBase, dictJoin(dictDone, partial))
    composerInput.setText(joined)
    composerInput.setSelection(composerInput.text.length)
    dictRendered = composerInput.text.toString()
}

/** The phrase being spoken, as heard so far (either engine). */
internal fun MainActivity.dictHeardPartial(t: String) {
    if (!dictating || t.isEmpty()) return
    // Some engines start a fresh phrase after a pause without a final result for the old one.
    if (dictPartial.isNotEmpty() && t.length < dictPartial.length && firstWord(t) != firstWord(dictPartial)) commitPartial()
    dictPartial = t
    dictLastHeard = android.os.SystemClock.elapsedRealtime(); dictErrors = 0
    renderDictation(t)
    armSendIfReady()
}

/** A finished phrase (either engine). [relisten]: the phone recogniser needs restarting after each one. */
internal fun MainActivity.dictHeardFinal(said: String, relisten: Boolean) {
    if (!dictating) return
    dictErrors = 0
    if (said.isNotEmpty()) dictLastHeard = android.os.SystemClock.elapsedRealtime()
    // A spoken “send” at the end of a phrase is an action marker, not message text.
    val phrase = Regex.escape(prefs.voiceSendPhrase())
    val sendSuffix = Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$")
    val send = prefs.voiceSendEnabled() && said.isNotEmpty() && sendSuffix.containsMatchIn(said)
    val text = if (send) said.replace(sendSuffix, "").trimEnd() else said
    dictDone = dictJoin(dictDone, text)
    dictPartial = ""
    renderDictation("")
    if (send) {
        stopDictation()
        if (composerInput.text.isNotBlank()) sendChat(voiceSendCommand = true)
    } else {
        armSendIfReady()
        if (relisten) dictHandler.postDelayed({ listenOnce() }, 75)
    }
}

/** End dictation, keeping whatever is in the composer. */
internal fun MainActivity.stopDictation() {
    if (!dictating) return
    dictating = false
    dictSendArmedAt = 0L
    dictHandler.removeCallbacksAndMessages(null)
    macDict?.stop(); macDict = null
    try { recognizer?.cancel() } catch (_: Exception) {}
    try { recognizer?.destroy() } catch (_: Exception) {}
    recognizer = null
    setDictationUi(false)
}

/** While dictating: a small chip above the composer, tap it to stop. The send/mic button is left alone. */
internal fun MainActivity.setDictationUi(on: Boolean) {
    updateScreenOn() // dictating is already set; hold or release the screen with it
    if (!chatMicReady) return
    val dp = resources.displayMetrics.density
    val st = chatRecStatus
    if (on) {
        val label = android.text.SpannableString("●  Listening, tap to stop")
        label.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.t_ok)), 0, 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        st.text = label
        st.typeface = android.graphics.Typeface.DEFAULT
        st.textSize = 13f
        st.setTextColor(getColor(R.color.t_ink))
        st.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(R.color.t_chip)); cornerRadius = 16 * dp
        }
        st.setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        // Width lives on the base LayoutParams: no cast (the parent here is a FrameLayout).
        st.layoutParams = st.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
        st.requestLayout()
        st.setOnClickListener { stopDictation() }
        st.visibility = View.VISIBLE
    } else {
        // Give the line back to the old recorder's look (it is shared).
        st.setOnClickListener(null); st.isClickable = false
        st.background = null; st.setPadding(0, 0, 0, 0)
        st.typeface = android.graphics.Typeface.MONOSPACE
        st.textSize = 14f
        st.setTextColor(getColor(R.color.t_err))
        st.layoutParams = st.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
        st.requestLayout()
        updateChatRecUI()
    }
    updateComposerSlot()
}

/** Overflow for a settled assistant reply: everything the row icons
 *  do, plus quote — same sheet style as the thread menu. */
internal fun MainActivity.replyMoreSheet(chat: String, i: Int, text: String) {
    if (text.isBlank()) return
    val key = "$chat:$i"
    showSelectSheet("Reply", listOf(
        SelectRow("Copy", "copy reply text", action = {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
            toast("reply copied")
        }),
        SelectRow("Read aloud", "speak this reply", action = { replyVoice.speak(key, text) }),
        SelectRow("Quote", "reply with this quoted", action = { showQuote("assistant", text) }),
    ))
}

/** Legacy voice-note recorder path, unused by the chat mic now. */
internal fun MainActivity.onChatMicRecord() {
    if (!hasMic()) { askMic(); return }
    if (chatRecActive) { stopChatRec(); return }
    ensureNotifPermission()
    if (!isThreadOpen()) openDraft()
    chatRecActive = true
    chatRecStartingAt = android.os.SystemClock.elapsedRealtime()
    if (RecorderService.running && !RecorderService.chatCaptureActive) {
        chatRecActive = false
        chatRecStartingAt = 0L
        android.widget.Toast.makeText(this, "Stop Echo recording before recording a chat voice note", android.widget.Toast.LENGTH_LONG).show()
        updateChatRecUI()
        return
    }
    chatChunkBaseline = ChatVoiceStore(this).all().toSet()
    if (!RecorderService.running) {
        val i = Intent(this, RecorderService::class.java)
            .putExtra(RecorderService.EXTRA_CHAT_CAPTURE, true)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
    chatPollHandler.removeCallbacks(chatPoll)
    chatPollHandler.post(chatPoll)
    updateChatRecUI()
}

internal fun MainActivity.stopChatRec() {
    chatRecActive = false
    chatRecStartingAt = 0L
    chatPollHandler.removeCallbacks(chatPoll)
    stopService(Intent(this, RecorderService::class.java))
    updateChatRecUI()
    echoCard.postDelayed({ refresh() }, 600)
    // Final local chat draft lands on service destroy — pick it up late.
    chatPane.postDelayed({ scanChatChunks() }, 1200)
}

/** Show freshly closed local chat captures for approve/discard. */
internal fun MainActivity.scanChatChunks() {
    if (!isThreadOpen()) return
    if (pendingVoice != null) return
    val known = try { ChatVoiceStore(this).all().toSet() } catch (_: Exception) { return }
    val fresh = (known - chatChunkBaseline).sorted()
            .map { File(recordingsDir(this), it) }
            .filter { it.exists() && it.length() > 1024 }
    if (fresh.isEmpty()) return
    // Preview-first: stage the note with ✓/✕, never auto-send to the agent.
    val f = fresh.first()
    chatChunkBaseline = chatChunkBaseline + f.name
    pendingVoice = f
    val dur = audioDurationSec(f)
    voicePreviewLabel.text = "voice note" + if (dur > 0) " (%d:%02d)".format(dur / 60, dur % 60) else ""
    voicePreview.visibility = View.VISIBLE
}

/** ✓: send the approved voice note through the normal chat send path. */
internal fun MainActivity.keepVoice() {
    val f = pendingVoice ?: return
    pendingVoice = null
    voicePreview.visibility = View.GONE
    ChatVoiceStore(this).forget(f.name)
    pendingAttaches.add(f)
    showAttach()
    sendChat()
}

/** ✕: discard this local voice draft. */
internal fun MainActivity.dropVoice() {
    val f = pendingVoice
    pendingVoice = null
    voicePreview.visibility = View.GONE
    if (pendingAttaches.isEmpty()) composerInput.hint = "Message (/ for commands)"
    // Cancel removes only this local chat draft; no Echo upload is involved.
    if (f != null) {
        try { ChatVoiceStore(this).forget(f.name) } catch (_: Exception) {}
        try { f.delete() } catch (_: Exception) {}
        scanChatChunks()
    }
}

internal fun MainActivity.updateChatRecUI() {
    updateScreenOn()
    if (!chatMicReady) return
    if (dictating) { updateComposerSlot(); return }
    val starting = (android.os.SystemClock.elapsedRealtime() - chatRecStartingAt) < 3000L
    if (chatRecActive && !RecorderService.running && !starting) {
        // Service died or was stopped elsewhere — settle pending chunks, exit chat mode.
        chatRecActive = false
        chatPollHandler.removeCallbacks(chatPoll)
        scanChatChunks()
    }
    val live = chatRecActive
    if (live) {
        val startTs = if (RecorderService.startedAt > 0) RecorderService.startedAt else chatRecStartingAt
        val s = ((android.os.SystemClock.elapsedRealtime() - startTs) / 1000).coerceAtLeast(0)
        chatMic.setImageResource(R.drawable.ic_stop)
        chatMic.setBackgroundResource(R.drawable.rec_bg)
        // Red recording oval: white glyph, never the theme ink (which is
        // near-black in light mode and would vanish on the red).
        chatMic.imageTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        if (micPulse == null) {
            micPulse = android.animation.ValueAnimator.ofFloat(1f, 1.08f).apply {
                duration = 450
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = easeOut
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    chatMic.scaleX = v; chatMic.scaleY = v
                }
                start()
            }
        }
        chatRecStatus.visibility = View.VISIBLE
        chatRecStatus.text = "recording • %02d:%02d (tap to stop)".format(s / 60, s % 60)
    } else {
        chatMic.setImageResource(R.drawable.ic_echo)
        chatMic.imageTintList = chatMicIdleTint
        chatMic.setBackgroundResource(0)
        chatRecStatus.visibility = View.GONE
        micPulse?.cancel(); micPulse = null
        chatMic.scaleX = 1f; chatMic.scaleY = 1f
    }
    updateComposerSlot()
}

internal fun MainActivity.audioDurationSec(f: File): Int = try {
    val mmr = android.media.MediaMetadataRetriever()
    mmr.setDataSource(f.absolutePath)
    val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
    try { mmr.release() } catch (_: Exception) {}
    (ms / 1000).toInt().coerceAtLeast(0)
} catch (_: Exception) { 0 }
