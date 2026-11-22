package com.terminus.rec

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap from the shade (lock screen included) starts or stops a voice note, the same
 *  recording the home-screen widget toggles. Recordings take the usual path (local transcription, filing). */
class RecTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        // A microphone service may only start from the foreground: hop through an invisible activity.
        val i = Intent(this, RecTileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun paint() {
        val t = qsTile ?: return
        val on = RecorderService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (on) "Stop note" else "Voice note"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Recording" else "Terminus"
        t.updateTile()
    }
}

/** Invisible hop: toggles the recorder from the foreground, then closes. */
class RecTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, RecorderService::class.java)
        try {
            if (RecorderService.running) stopService(i)
            else if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {
            // No microphone permission yet: open the app so it can ask.
            startActivity(Intent(this, MainActivity::class.java))
        }
        try { TileService.requestListeningState(this, android.content.ComponentName(this, RecTile::class.java)) } catch (_: Exception) {}
        finish()
    }
}
