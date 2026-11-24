package com.terminus.rec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.view.View
import android.widget.EditText
import androidx.core.app.ActivityCompat
import com.terminus.rec.MainActivity.Companion.recordingsDir
import java.io.File

/** The echo tab, mic and notification permissions, audio playback. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.showEcho() {
    chatPane.visibility = View.GONE
    echoPane.visibility = View.VISIBLE
    galleryPane.visibility = View.GONE
    opsPane.visibility = View.GONE
    notesPane.visibility = View.GONE
    crossFade(echoPane)
    // Echo is never a thread: full bar, unconditionally.
    syncNavForThread(false)
    refresh()
}

internal fun MainActivity.hasMic(): Boolean =
    ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal fun MainActivity.askMic() {
    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
    ensureNotifPermission()
}

/** POST_NOTIFICATIONS is declared in the manifest; ask on first use
 *  (chat send, record start, tone picker) so milestones/turn pings land. */
internal fun MainActivity.ensureNotifPermission() {
    if (Build.VERSION.SDK_INT >= 33 &&
        ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
    }
}

/** Display name of the stored chat tone for the cog sheet. */
internal fun MainActivity.toneName(): String {
    val uri = ChatNotify.soundUri(this) ?: return "system default"
    return try {
        RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "custom tone"
    } catch (_: Exception) { "custom tone" }
}

internal fun MainActivity.toggle() {
    val i = Intent(this, RecorderService::class.java)
    if (RecorderService.running) stopService(i)
    else {
        ensureNotifPermission()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
    echoCard.postDelayed({ refresh() }, 600)
}

/** One optional label per Echo session (e.g. "walk", "standup").
 *  Sanitized + baked into chunk filenames; blank starts unlabeled. */
internal fun MainActivity.promptEchoLabel() {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val view = layoutInflater.inflate(R.layout.sheet_echo_label, null)
    sheet.setContentView(view)
    val input: EditText = view.findViewById(R.id.echoLabelField)
    view.findViewById<View>(R.id.echoLabelCancel).setOnClickListener { sheet.dismiss() }
    view.findViewById<View>(R.id.echoLabelRecord).setOnClickListener {
        RecorderService.pendingLabel = RecorderService.sanitizeLabel(input.text.toString())
        sheet.dismiss()
        toggle()
    }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}

internal fun MainActivity.refresh() {
    updateScreenOn()
    val live = RecorderService.running
    if (!live || chatRecActive || RecorderService.chatCaptureActive) {
        if (chatRecActive || RecorderService.chatCaptureActive) {
            echoTitle.text = "voice note"
            echoSub.text = "recording for chat · tap mic to stop"
        } else {
            echoTitle.text = "ready"
            echoSub.text = "tap to record"
        }
        timerText.visibility = android.view.View.GONE
        recChip.visibility = android.view.View.GONE
        waveView.visibility = android.view.View.GONE
        echoOrb.setBackgroundResource(R.drawable.echo_orb)
        setPulsing(false)
    } else {
        val label = RecorderService.activeLabel
        echoTitle.text = if (label.isNotBlank()) "recording · $label" else "recording"
        echoSub.text = chunkLine(RecorderService.chunkNo, uploadedInSession(chunks.all().filter { it.state == ChunkStore.STATE_UPLOADED }.map { it.name }, RecorderService.sessionPrefix))
        timerText.visibility = android.view.View.VISIBLE
        recChip.visibility = android.view.View.VISIBLE
        waveView.visibility = android.view.View.VISIBLE
        echoOrb.setBackgroundResource(R.drawable.echo_orb_stop)
        timerHandler.removeCallbacks(waveTick)
        waveTick.run()
        timerTick.run()
        setPulsing(true)
    }
    adapter.update(chunks.all(), clipLens(chunks.all()))
    findViewById<View>(R.id.echoEmpty).visibility =
        if (chunks.all().isEmpty()) View.VISIBLE else View.GONE
    renderSys()
    if (chatMicReady) {
        if (chatRecActive && RecorderService.running) {
            chatPollHandler.removeCallbacks(chatPoll)
            chatPollHandler.post(chatPoll)
            scanChatChunks()
        }
        updateChatRecUI()
    }
}

/** Real clip lengths via metadata, cached by name (store keeps names
 *  stable; a missing file simply stays unmeasured). */
internal fun MainActivity.clipLens(items: List<ChunkItem>): Map<String, Long> {
    for (it in items) {
        if (clipLens.containsKey(it.name)) continue
        try {
            val f = File(recordingsDir(this), it.name)
            if (!f.exists()) continue
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (ms != null && ms > 0) clipLens[it.name] = ms
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
    return clipLens
}

internal fun MainActivity.renderSys() {
    if (activeBackend() != healthBackend) pingServer()
    val items = chunks.all()
    val queued = items.count { it.state == ChunkStore.STATE_QUEUED }
    val failed = items.count { it.state == ChunkStore.STATE_REJECTED }
    if (!RecorderService.running) {
        echoSub.text = if (playingName != null) "▶ $playingName"
            else if (queued > 0 && !serverOk) "$queued queued · offline"
            else if (queued > 0) "$queued queued · syncing"
            else if (failed > 0) "$failed failed"
            else "tap to record"
    }
    val bytes = try {
        recordingsDir(this).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    } catch (_: Exception) { 0L }
    val dotColor = android.content.res.ColorStateList.valueOf(
        androidx.core.content.ContextCompat.getColor(
            this, if (serverOk) R.color.t_ok else R.color.t_muted))
    for (d in serverDots) {
        try { d.backgroundTintList = dotColor } catch (_: Exception) {}
    }
    statQueue.text = "$queued"
    statStore.text = "${bytes / 1048576} MB"
}

internal fun MainActivity.playChunk(item: ChunkItem) {
    val was = playingName
    stopPlayback()
    if (was == item.name) { playingName = null; refresh(); return }
    try {
        val f = File(recordingsDir(this), item.name)
        if (!f.exists()) { echoSub.text = "file gone"; return }
        player = android.media.MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            setOnCompletionListener { playingName = null; refresh() }
            start()
        }
        playingName = item.name
    } catch (e: Exception) {
        echoSub.text = "can't play"
        playingName = null
    }
    refresh()
}

internal fun MainActivity.stopPlayback() {
    try { player?.stop(); player?.release() } catch (_: Exception) {}
    player = null
    updateScreenOn()
}
