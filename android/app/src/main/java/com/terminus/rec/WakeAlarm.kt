package com.terminus.rec

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Device-local wake alarm: exact alarm → full-screen ring, no server,
 *  no network, fires even with the phone asleep. Armed from /wake. */
object WakeAlarm {
    const val ACTION_FIRE = "com.terminus.rec.WAKE_FIRE"
    const val ACTION_DISMISS = "com.terminus.rec.WAKE_DISMISS"
    const val EXTRA_MESSAGE = "message"
    const val NOTIF_ID = 2001
    private const val PREFS = "wake_alarm"
    private const val KEY_AT = "at"
    private const val KEY_MSG = "msg"

    fun armedInfo(ctx: Context): Pair<Long, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0)
        if (at <= 0) return null
        return at to (p.getString(KEY_MSG, "") ?: "")
    }

    /** True only when Android accepted the alarm. A refusal (exact alarms not allowed) used to be swallowed, so a
     *  "wake me" link looked armed and never rang. */
    fun schedule(ctx: Context, triggerAtMs: Long, message: String): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val pi = fireIntent(ctx, message)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {
            return false
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, triggerAtMs).putString(KEY_MSG, message).apply()
        return true
    }

    enum class State { READY, ARMED, CANCELLED, PASSED }

    /** Pure: what an alarm card should say. `armedAt` is the alarm Android currently holds (one at a time). */
    internal fun stateFor(at: Long, now: Long, armedAt: Long, deadKeys: Set<String>): State = when {
        at <= now -> State.PASSED
        at == armedAt -> State.ARMED
        deadKeys.contains("$at") -> State.CANCELLED
        else -> State.READY
    }

    /** Cheap fingerprint of everything stateOf reads: part of the thread page cache key. */
    fun signature(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "${p.getLong(KEY_AT, 0)}:${(p.getStringSet("deadLinks", emptySet()) ?: emptySet()).hashCode()}:" +
            (p.getStringSet("armedLinks", emptySet()) ?: emptySet()).hashCode()
    }

    fun stateOf(ctx: Context, at: Long): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return stateFor(at, System.currentTimeMillis(), p.getLong(KEY_AT, 0), p.getStringSet("deadLinks", emptySet()) ?: emptySet())
    }

    /** A person tapped the card: arm it even if it was cancelled before (the tap is the intent). */
    fun armExplicit(ctx: Context, at: Long, message: String): Boolean {
        if (at <= System.currentTimeMillis()) return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dead = (p.getStringSet("deadLinks", emptySet()) ?: emptySet()) - "$at"
        if (!schedule(ctx, at, message.ifBlank { "Time to wake up" })) return false
        val armed = (p.getStringSet("armedLinks", emptySet()) ?: emptySet()) + "$at"
        p.edit().putStringSet("deadLinks", dead).putStringSet("armedLinks", armed).apply()
        return true
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            am?.cancel(fireIntent(ctx, ""))
        } catch (_: Exception) {}
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        } catch (_: Exception) {}
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A cancelled alarm must STAY dead: record its time so a later
        // sync never re-arms it. Armed-links history is kept for the same
        // reason (idempotence across re-renders and re-syncs).
        val at = p.getLong(KEY_AT, 0)
        val dead = p.getStringSet("deadLinks", emptySet()) ?: emptySet()
        val pruned = (dead + if (at > 0) setOf("$at") else emptySet())
            .filter { (it.toLongOrNull() ?: 0) > System.currentTimeMillis() - 24 * 60 * 60_000L }
            .toSet()
        p.edit().remove(KEY_AT).remove(KEY_MSG)
            .putStringSet("deadLinks", pruned).apply()
    }

    fun snooze(ctx: Context, minutes: Int = 10) {
        val (_, msg) = armedInfo(ctx) ?: (0L to "")
        schedule(ctx, System.currentTimeMillis() + minutes * 60_000L, msg)
    }

    private fun fireIntent(ctx: Context, message: String): PendingIntent {
        val intent = Intent(ctx, WakeAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MESSAGE, message)
        }
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun dismissIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, WakeAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS
        }
        return PendingIntent.getBroadcast(
            ctx, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    fun requestExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        // Exact settings page; fall back to app info if the OEM dropped it.
        val exact = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!fireSettings(ctx, exact)) {
            return fireSettings(ctx, appInfo(ctx))
        }
        return true
    }

    fun canFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            ctx.getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() ?: false
        } catch (_: Exception) { false }
    }

    fun requestFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val page = Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!fireSettings(ctx, page)) {
            return fireSettings(ctx, appInfo(ctx))
        }
        return true
    }

    private fun appInfo(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True when a settings activity actually took the intent (else the
     *  user saw nothing and would rightly call it broken). */
    private fun fireSettings(ctx: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(ctx.packageManager) == null) return false
            ctx.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    /** Parse "07:30 [message]" or "in 20 min [message]". Past times roll to
     *  tomorrow. Returns (triggerAtMs, message) or null on bad input. */
    fun parse(input: String): Pair<Long, String>? {
        val t = input.trim()
        if (t.equals("cancel", ignoreCase = true)) return null
        val inMatch = Regex("(?i)^in\\s+(\\d+)\\s*(m(?:in)?(?:ute)?s?|h(?:our)?s?)\\b\\s*(.*)$").matchEntire(t)
        if (inMatch != null) {
            val n = inMatch.groupValues[1].toLongOrNull() ?: return null
            val mins = if (inMatch.groupValues[2].lowercase().startsWith("h")) n * 60 else n
            if (mins <= 0 || mins > 24 * 60) return null
            val msg = inMatch.groupValues[3].trim().ifBlank { "Time to wake up" }
            return (System.currentTimeMillis() + mins * 60_000L) to msg
        }
        val hm = Regex("^(\\d{1,2})(?::?(\\d{2}))?\\s*(am|pm)?\\b\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(t)
            ?: return null
        var h = hm.groupValues[1].toIntOrNull() ?: return null
        val m = hm.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (m > 59) return null
        val ap = hm.groupValues[3].lowercase()
        if (ap.isNotEmpty()) {
            if (h < 1 || h > 12) return null
            if (ap == "pm" && h != 12) h += 12
            if (ap == "am" && h == 12) h = 0
        } else if (h > 23) return null
        val msg = hm.groupValues[4].trim().ifBlank { "Time to wake up" }
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        var at = cal.timeInMillis
        if (at <= System.currentTimeMillis() + 60_000L) at += 24 * 60 * 60_000L
        return at to msg
    }

    fun describeAt(at: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(at))
    }

    private val ALARM_LINK = Regex("terminus://alarm\\?at=(\\d+)(?:&msg=([^\\s)\"]*))?")

    /** Arm every future alarm link in free text. Returns the newly armed
     *  times. Idempotent: re-renders and re-syncs never double-arm, and
     *  cancelled times stay dead. */
    fun armFromText(ctx: Context, text: String): List<Long> {
        if (!text.contains("terminus://alarm")) return emptyList()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val armed = p.getStringSet("armedLinks", emptySet()) ?: emptySet()
        val dead = p.getStringSet("deadLinks", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val fresh = mutableListOf<Long>()
        for (m in ALARM_LINK.findAll(text)) {
            val at = m.groupValues[1].toLongOrNull() ?: continue
            if (at <= now) continue
            val key = "$at"
            if (armed.contains(key) || dead.contains(key)) continue
            val msg = try {
                java.net.URLDecoder.decode(m.groupValues[2], "UTF-8")
            } catch (_: Exception) { "" }
            if (!schedule(ctx, at, msg.ifBlank { "Time to wake up" })) continue   // refused: stays "ready", never marked armed
            fresh += at
        }
        if (fresh.isNotEmpty()) {
            val keep = (armed + fresh.map { "$it" }.toSet())
                .filter { (it.toLongOrNull() ?: 0) > now }.toSet()
            p.edit().putStringSet("armedLinks", keep).apply()
        }
        return fresh
    }
}
