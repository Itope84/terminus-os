package com.terminus.rec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Alarm firing (exact alarm → full-screen ring) and notification actions.
 *  Sound + vibration live in WakeAlarmActivity; the channel stays silent. */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            WakeAlarm.ACTION_DISMISS -> {
                WakeAlarm.cancel(ctx)
                try {
                    ctx.sendBroadcast(Intent(WakeAlarmActivity.ACTION_CLOSE))
                } catch (_: Exception) {}
                return
            }
            else -> fire(ctx, intent.getStringExtra(WakeAlarm.EXTRA_MESSAGE) ?: "")
        }
    }

    private fun isForeground(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return false
            am.runningAppProcesses?.any {
                it.processName == ctx.packageName &&
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun fire(ctx: Context, message: String) {
        // App in foreground: ring directly, no permission dance needed.
        // The full-screen toggle is only required to break through from
        // background — a foreground launch is always allowed.
        if (isForeground(ctx)) {
            try {
                ctx.startActivity(Intent(ctx, WakeAlarmActivity::class.java).apply {
                    putExtra(WakeAlarm.EXTRA_MESSAGE, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                return
            } catch (_: Exception) {}
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Full-screen permission off (Android often withholds it from sideloaded apps, or drops it on update): the ring
        // screen cannot open over other apps, and its sound would never play. Then the notification itself rings:
        // alarm-usage sound, vibration, repeating until dismissed. Permission on: the silent channel plus the ring screen.
        val loud = !WakeAlarm.canFullScreen(ctx)
        val channelId = if (loud) "alarm_loud" else "alarm"
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                nm.createNotificationChannel(
                    NotificationChannel("alarm", "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Wake alarms"
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
                nm.createNotificationChannel(
                    NotificationChannel("alarm_loud", "Alarms (rings as a notification)", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Used when full-screen alerts are not allowed: the alarm sounds from the notification"
                        setSound(
                            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                        setBypassDnd(true)
                    }
                )
            } catch (_: Exception) {}
        }
        val full = Intent(ctx, WakeAlarmActivity::class.java).apply {
            putExtra(WakeAlarm.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullPi = PendingIntent.getActivity(
            ctx, 2, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Wake up")
            .setContentText(message.ifBlank { "Time to wake up" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(fullPi)
            .setFullScreenIntent(fullPi, true)
            .addAction(0, "Dismiss", WakeAlarm.dismissIntent(ctx))
            .build()
            .also { if (loud) it.flags = it.flags or android.app.Notification.FLAG_INSISTENT }   // keep sounding until dismissed
        try {
            NotificationManagerCompat.from(ctx).notify(WakeAlarm.NOTIF_ID, notif)
        } catch (_: Exception) {
            // No tray permission: launch the screen directly as fallback.
            try { ctx.startActivity(full) } catch (_: Exception) {}
        }
    }
}
