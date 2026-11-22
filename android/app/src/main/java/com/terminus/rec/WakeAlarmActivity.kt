package com.terminus.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen wake screen: ring + buzz + message until dismissed or
 *  snoozed. Shows over the lock screen. */
class WakeAlarmActivity : AppCompatActivity() {
    companion object {
        const val ACTION_CLOSE = "com.terminus.rec.WAKE_CLOSE"
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try { finishAndRemoveTask() } catch (_: Exception) {
                try { finish() } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_wake_alarm)

        val msg = intent.getStringExtra(WakeAlarm.EXTRA_MESSAGE).orEmpty()
            .ifBlank { "Time to wake up" }
        findViewById<TextView>(R.id.wakeMessage).text = msg
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        findViewById<TextView>(R.id.wakeClock).text = clock.format(java.util.Date())

        try {
            registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE),
                Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}

        startRing()
        findViewById<Button>(R.id.wakeDismiss).setOnClickListener { dismiss() }
        findViewById<Button>(R.id.wakeSnooze).setOnClickListener {
            try { WakeAlarm.snooze(this, 10) } catch (_: Exception) {}
            toast("snoozed 10 min")
            dismiss()
        }
    }

    private fun startRing() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@WakeAlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
            player = mp
        } catch (_: Exception) {
            try {
                RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.play()
            } catch (_: Exception) {}
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            try {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } catch (_: Exception) { null }
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800), 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 800, 400, 800), 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopRing() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun dismiss() {
        stopRing()
        try { WakeAlarm.cancel(this) } catch (_: Exception) {}
        try { finishAndRemoveTask() } catch (_: Exception) {
            try { finish() } catch (_: Exception) {}
        }
    }

    private fun toast(s: String) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopRing()
        try { unregisterReceiver(closeReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
