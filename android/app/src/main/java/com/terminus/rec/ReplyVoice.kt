package com.terminus.rec

import android.app.Activity

/** Read-aloud for assistant replies: owns the TextToSpeech engine, the chunking that gets speech
 *  started quickly, and the per-reply "reading" button state in the chat page.
 *  Extracted from MainActivity; behaviour unchanged.
 *  [runJs] evaluates a script in the chat page (no-op while it does not exist); [toast] shows a message. */
class ReplyVoice(
    private val act: Activity,
    private val runJs: (String) -> Unit,
    private val toast: (String) -> Unit,
    /** Fires on every speaking/idle transition (tap, natural end, error, focus loss). */
    private val onPlaying: (Boolean) -> Unit = {},
) {
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pending: Pair<String, String>? = null
    private var activeKey: String? = null
    private var lastUtterance: String? = null
    private var speechFocus: android.media.AudioFocusRequest? = null

    /** Start the engine ahead of the first Read aloud tap (service bind is the slow part). */
    fun warmUp() {
        act.runOnUiThread { try { ensureTts() } catch (_: Exception) {} }
    }

    /** Stop speaking and free the engine (activity destroyed). */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        abandonSpeechFocus()
    }

    /** Stop whatever reply is being read (no-op when none). */
    fun stop() {
        val key = activeKey ?: return
        tts?.stop()
        pending = null
        setActive(null)
        lastUtterance = null
        showReading(key, playing = false)
    }

    /** Re-paint the stop icon after the chat page was rebuilt mid-reading. */
    fun restore() {
        activeKey?.let { showReading(it, playing = true) }
    }

    /** Whether a reply is currently being read aloud. */
    fun isPlaying(): Boolean = activeKey != null

    /** Single choke point for speaking/idle transitions (notifies the host for screen-on). */
    private fun setActive(key: String?) {
        if (activeKey == key) return
        activeKey = key
        try { onPlaying(key != null) } catch (_: Exception) {}
    }

    fun speak(key: String, source: String) {
        if (activeKey == key) {
            stop()
            return
        }
        val spoken = ChatMarkdown.speechText(source)
        if (spoken.isBlank()) { toast("nothing to speak"); return }
        activeKey?.let { showReading(it, playing = false) }
        setActive(key)
        lastUtterance = null
        showReading(key, playing = true)
        tts?.stop()
        pending = key to spoken
        if (tts == null) ensureTts()
        else if (ttsReady) play(key, spoken)
    }

    /** Creates the TTS engine if needed. Called by the first Read aloud tap and, ahead of time, when a
     *  turn is sent, so the engine's service bind is not paid while the person waits for speech. */
    private fun ensureTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(act) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) {
                        act.runOnUiThread {
                            if (utteranceId == lastUtterance) {
                                val finishedKey = activeKey
                                setActive(null)
                                lastUtterance = null
                                finishedKey?.let { showReading(it, playing = false) }
                            }
                        }
                    }
                    override fun onError(utteranceId: String) {
                        act.runOnUiThread {
                            if (utteranceId == lastUtterance) {
                                val finishedKey = activeKey
                                setActive(null)
                                lastUtterance = null
                                finishedKey?.let { showReading(it, playing = false) }
                            }
                        }
                    }
                    override fun onStop(utteranceId: String, interrupted: Boolean) {
                        if (!interrupted) return
                        act.runOnUiThread {
                            if (utteranceId == lastUtterance) {
                                val stoppedKey = activeKey
                                setActive(null)
                                lastUtterance = null
                                stoppedKey?.let { showReading(it, playing = false) }
                            }
                        }
                    }
                })
                pending?.let { (pendingKey, text) -> play(pendingKey, text) }
            }
            else {
                pending = null
                val failedKey = activeKey
                setActive(null)
                // Drop the dead engine so the next tap retries instead of doing nothing.
                try { tts?.shutdown() } catch (_: Exception) {}
                tts = null
                ttsReady = false
                act.runOnUiThread {
                    failedKey?.let { showReading(it, playing = false) }
                    // Silent when this was only the background warm-up and nobody is waiting.
                    if (failedKey != null) toast("text to speech unavailable")
                }
            }
        }
    }

    /** Let music duck while a reply is read; a call or another app taking audio stops the reading. */
    private fun requestSpeechFocus() {
        val am = act.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val req = speechFocus ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) act.runOnUiThread { stop() }
            }.build()
        speechFocus = req
        am.requestAudioFocus(req)
    }

    private fun abandonSpeechFocus() {
        val req = speechFocus ?: return
        speechFocus = null
        (act.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(req)
    }

    private fun showReading(key: String, playing: Boolean) {
        if (!playing && activeKey == null) abandonSpeechFocus()
        val keyJson = org.json.JSONObject.quote(key)
        val script = """(function(k,on){
            document.querySelectorAll('.reply-actions [data-speech-key]').forEach(function(a){
                var active=on&&a.getAttribute('data-speech-key')===k;
                a.classList.toggle('is-reading',active);
                a.setAttribute('aria-label',active?'Stop reading':'Read reply aloud');
                a.title=active?'Stop reading':'Read reply aloud';
                var svg=a.querySelector('svg');
                if(svg)svg.innerHTML=active
                    ?'<circle cx="12" cy="12" r="12" fill="currentColor"/><rect x="9.1" y="9.1" width="5.8" height="5.8" rx="1.3" fill="#fff"/>'
                    :'<path d="M4 10v4h4l5 4V6l-5 4H4zm12-2a6 6 0 0 1 0 8m2-11a10 10 0 0 1 0 14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>';
            });
        })($keyJson,${if (playing) "true" else "false"});"""
        act.runOnUiThread { runJs(script) }
    }

    private fun play(key: String, text: String) {
        if (activeKey != key) return
        pending = null
        requestSpeechFocus()
        // A short first chunk starts speaking quickly; the engine synthesizes the rest while it plays.
        val chunks = ChatMarkdown.speechChunks(text, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 16)
        lastUtterance = "reply-${key.hashCode()}-${chunks.lastIndex}"
        chunks.forEachIndexed { index, chunk ->
            val id = "reply-${key.hashCode()}-$index"
            val queue = if (index == 0) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, queue, null, id)
        }
    }
}
