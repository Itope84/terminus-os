package com.terminus.rec

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The chat list's third line for a schedule thread: when it runs next and how often, e.g.
 *  "next Sun 18:30 · weekly", "next 13:00 · every 10 min", "due 1 Dec · one-off", "finished".
 *  Pure (time and zone passed in), unit-tested. Raw facts come from GET /api/schedule-rows. */
object ScheduleLine {
    data class Facts(
        val cron: String? = null,
        val everyMin: Int? = null,
        val at: String? = null,
        val next: Long? = null,
        val retired: Boolean = false,
    )

    fun line(f: Facts, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (f.retired) return "finished"
        val how = cadence(f)
        val oneOff = how == "one-off"
        val whenText = f.next?.let { (if (oneOff) "due " else "next ") + whenOf(it, now, zone) }
        return listOfNotNull(whenText, how.ifBlank { null }).joinToString(" · ")
    }

    /** "every 10 min", "hourly", "daily", "weekdays", "weekly", "monthly", "one-off", or "" when unclear. */
    fun cadence(f: Facts): String {
        f.everyMin?.takeIf { it > 0 }?.let { m ->
            return when {
                m < 60 -> "every $m min"
                m == 60 -> "hourly"
                m % 1440 == 0 -> if (m == 1440) "daily" else if (m == 10080) "weekly" else "every ${m / 1440} days"
                m % 60 == 0 -> "every ${m / 60} h"
                else -> "every $m min"
            }
        }
        val c = f.cron?.trim()?.split(Regex("\\s+"))
        if (c != null && c.size == 5) {
            val (min, hour, dom, mon, dow) = c
            min.removePrefix("*/").toIntOrNull()?.takeIf { min.startsWith("*/") }?.let { return "every $it min" }
            hour.removePrefix("*/").toIntOrNull()?.takeIf { hour.startsWith("*/") }?.let { return if (it == 1) "hourly" else "every $it h" }
            if (hour == "*") return "hourly"
            return when {
                dom == "*" && mon == "*" && dow == "*" -> "daily"
                dom == "*" && mon == "*" && dow == "1-5" -> "weekdays"
                dom == "*" && mon == "*" && dow.toIntOrNull() != null -> "weekly"
                dom.toIntOrNull() != null && mon == "*" && dow == "*" -> "monthly"
                dom.toIntOrNull() != null && mon.toIntOrNull() != null -> "yearly"
                else -> "repeats"
            }
        }
        return if (!f.at.isNullOrBlank()) "one-off" else ""
    }

    /** "13:00" today, "tomorrow 07:00", "Sun 18:30" within a week, else "1 Dec" (+ year when not this year). */
    fun whenOf(ms: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day: LocalDate = t.toLocalDate()
        val hm = "%02d:%02d".format(t.hour, t.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, day)
        return when {
            days == 0L -> hm
            days == 1L -> "tomorrow $hm"
            days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) + " $hm"
            else -> "${day.dayOfMonth} " + day.month.getDisplayName(TextStyle.SHORT, Locale.UK) +
                (if (day.year != today.year) " ${day.year}" else "")
        }
    }
}
