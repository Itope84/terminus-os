package com.terminus.rec

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Pure helpers for the recording screen. Unit-tested. */

/** MediaRecorder.getMaxAmplitude (0..32767) → bar height 0..1. Log scaled so speech fills the bars
 *  and a quiet room stays a low ripple instead of a flat line. */
internal fun waveLevel(amp: Int): Float {
    if (amp <= 0) return 0.04f
    val db = 20.0 * ln(amp / 32767.0) / ln(10.0)        // 0 dB = full scale
    return min(1f, max(0.04f, ((db + 55.0) / 55.0).toFloat()))
}

/** "Chunk 3 · 2 uploaded" under the timer. */
internal fun chunkLine(chunkNo: Int, uploaded: Int): String {
    val n = max(chunkNo, 1)
    return "Chunk $n · " + if (uploaded == 0) "uploads every 5 min" else "$uploaded uploaded"
}

/** Uploaded chunks belonging to the running session (file names start `rec-<sessionTs>-`). */
internal fun uploadedInSession(uploadedNames: List<String>, sessionTs: String): Int =
    if (sessionTs.isBlank()) 0 else uploadedNames.count { it.startsWith("rec-$sessionTs-") }
