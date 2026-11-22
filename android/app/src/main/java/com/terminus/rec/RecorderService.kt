package com.terminus.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    private var rec: MediaRecorder? = null
    private var chunkIndex = 0
    private var sessionTs = ""
    private var sessionLabel = ""
    private var t0 = 0L
    private var chatCapture = false
    private var captureStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startedAt = SystemClock.elapsedRealtime()
        sessionTs = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.UK).format(Date())
        // Consume-and-clear: a label never leaks into a later session.
        sessionLabel = pendingLabel.also { pendingLabel = "" }
        activeLabel = sessionLabel
        chunkIndex = 0
        startSilentForeground()
    }

    override fun onDestroy() {
        running = false
        startedAt = 0L
        activeLabel = ""
        chatCaptureActive = false
        stopChunk(upload = true)
        if (chunkIndex > 0 && !chatCapture) {
            val queued = try { ChunkStore(this).all().count { it.state == ChunkStore.STATE_QUEUED } } catch (_: Exception) { 0 }
            ChatNotify.notifyMilestone(this, "Recording stopped",
                if (queued > 0) "$queued queued · uploading" else "chunk saved")
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!captureStarted) {
            if (intent == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            chatCapture = intent.getBooleanExtra(EXTRA_CHAT_CAPTURE, false)
            chatCaptureActive = chatCapture
            captureStarted = true
            updateForegroundStatus()
            startChunk()
        }
        return START_STICKY
    }

    private fun startSilentForeground() {
        val ch = "rec"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Recording", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val n = buildNotification(ch, "Recording", "Audio saved on this device")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateForegroundStatus() {
        val channel = "rec"
        val title = if (chatCapture) "Chat voice note" else "Recording"
        val text = if (chatCapture) "Saved locally until you approve it"
            else "Uploads on 5 min rolls or when stopped"
        val notification = buildNotification(channel, title, text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(channel: String, title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startChunk() {
        stopChunk(upload = false)
        chunkIndex++
        // No milestone ping here: the foreground recording notice is enough.
        t0 = SystemClock.elapsedRealtime()
        val f = chunkFile()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64000)
        r.setAudioSamplingRate(16000)
        r.setOutputFile(f.absolutePath)
        r.setMaxDuration(CHUNK_MS)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                stopChunk(upload = true)
                startChunk()
            }
        }
        try {
            r.prepare()
            r.start()
            rec = r
            liveRecorder = r
            chunkNo = chunkIndex
            sessionPrefix = sessionTs
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            rec = null
            stopSelf()
        }
    }

    private fun stopChunk(upload: Boolean) {
        val r = rec ?: return
        rec = null
        liveRecorder = null
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        if (upload) {
            val f = currentFile()
            if (f.exists() && f.length() > 1024) {
                if (chatCapture) ChatVoiceStore(this).add(f.name)
                else {
                    ChunkStore(this).add(f.name)
                    Uploader.enqueue(this)
                }
            } else {
                f.delete()
            }
        }
    }

    private fun chunkFile(): File =
        File(MainActivity.recordingsDir(this), chunkName(sessionTs, chunkIndex, sessionLabel))

    private fun currentFile(): File =
        File(MainActivity.recordingsDir(this), chunkName(sessionTs, chunkIndex, sessionLabel))

    companion object {
        const val EXTRA_CHAT_CAPTURE = "chat_capture"
        const val CHUNK_MS = 5 * 60 * 1000
        @Volatile var running = false
        @Volatile var chatCaptureActive = false
        @Volatile var startedAt = 0L
        /** Label for the NEXT session: UI sets it before start, onCreate
         *  consumes it. Blank = unlabeled (today's behavior). */
        @Volatile var pendingLabel = ""
        /** Label of the running session (shown while recording). */
        @Volatile var activeLabel = ""
        /** Running chunk number and session timestamp, for the recording screen's status line. */
        @Volatile var chunkNo = 0
        @Volatile var sessionPrefix = ""
        @Volatile private var liveRecorder: MediaRecorder? = null

        /** Peak level since the last call (0..32767) for the waveform; 0 when idle or on any error. */
        fun amplitude(): Int = try { liveRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }

        /** Pure: filename-safe label (lowercase a-z0-9 dashes, ≤24).
         *  Blank stays blank. */
        fun sanitizeLabel(raw: String): String =
            raw.lowercase(Locale.UK).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trim('-')

        /** Pure: chunk filename. The label trails the chunk index so the
         *  day/time parsers (ChunkAdapter.dayKey/slotStartMin) keep working
         *  on indices 1-3 / 4-6. */
        fun chunkName(ts: String, index: Int, label: String): String {
            val base = "rec-$ts-${"%02d".format(index)}"
            return if (label.isBlank()) "$base.m4a" else "$base-$label.m4a"
        }
    }
}
