package com.terminus.rec

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/** Dictation through saywhat on the Mac: the mic is recorded here as 16 kHz mono PCM and posted every
 *  [CHUNK_MS]; each reply carries the text recognised so far (partials while speaking, a final at each
 *  pause). Anything that stops it working (no route to the Mac, saywhat not installed, saywhat exiting)
 *  calls [onFail] once and releases the mic, so the caller can switch to the phone's own recogniser.
 *  Callbacks run on the main thread. */
class MacDictation(
    private val base: String,
    private val token: String,
    private val onEvent: (OpencodeApi.ListenEvent) -> Unit,
    private val onFail: (why: String, worked: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val id = "d" + System.currentTimeMillis().toString(36) + (0..0xffff).random().toString(36)
    @Volatile private var running = false
    @Volatile private var failed = false
    private var thread: Thread? = null

    /** Needs RECORD_AUDIO (the caller checks it). */
    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        thread = Thread({ loop() }, "mac-dictation").also { it.start() }
    }

    /** Ends the session; the Mac decodes what was still being said. Late events are not delivered. */
    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = RATE * 2 * CHUNK_MS / 1000
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
        } catch (e: Exception) { fail("microphone unavailable", false); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); fail("microphone unavailable", false); return }
        var worked = false
        try {
            rec.startRecording()
            val buf = ByteArray(chunk)
            while (running) {
                var got = 0
                while (got < chunk && running) {
                    val n = rec.read(buf, got, chunk - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) { fail("microphone stopped", worked); return }
                val r = try {
                    OpencodeApi.postListen(base, token, id, buf.copyOf(got))
                } catch (e: Exception) { fail("Mac unreachable", worked); return }
                when (r.code) {
                    200 -> {}
                    503 -> { fail("saywhat not installed on the Mac", worked); return }
                    else -> { fail("Mac dictation error ${r.code}", worked); return }
                }
                worked = true
                deliver(r.events)
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        // Stopped by the user: close the Mac side so its decoder exits (best effort, nothing delivered).
        if (worked) try { OpencodeApi.postListen(base, token, id, ByteArray(0), stop = true) } catch (_: Exception) {}
    }

    private fun deliver(events: List<OpencodeApi.ListenEvent>) {
        if (events.isEmpty()) return
        main.post { if (running) events.forEach(onEvent) }
    }

    private fun fail(why: String, worked: Boolean) {
        if (failed) return
        failed = true
        val wasRunning = running
        running = false
        if (wasRunning) main.post { onFail(why, worked) }
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK_MS = 250
    }
}
