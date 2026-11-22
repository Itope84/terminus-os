package com.terminus.rec

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleLineTest {
    private val z = ZoneId.of("Europe/London")
    // Saturday 21 Nov 2026, 10:00
    private val now = ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, z).toInstant().toEpochMilli()
    private fun at(d: Int, m: Int, h: Int, min: Int = 0, y: Int = 2026) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant().toEpochMilli()
    private fun line(f: ScheduleLine.Facts) = ScheduleLine.line(f, now, z)

    @Test fun cadences() {
        assertEquals("daily", ScheduleLine.cadence(ScheduleLine.Facts(cron = "0 7 * * *")))
        assertEquals("weekly", ScheduleLine.cadence(ScheduleLine.Facts(cron = "30 18 * * 0")))
        assertEquals("weekdays", ScheduleLine.cadence(ScheduleLine.Facts(cron = "30 21 * * 1-5")))
        assertEquals("monthly", ScheduleLine.cadence(ScheduleLine.Facts(cron = "0 9 1 * *")))
        assertEquals("every 10 min", ScheduleLine.cadence(ScheduleLine.Facts(cron = "*/10 13-16 * * 1-5")))
        assertEquals("every 80 days", ScheduleLine.cadence(ScheduleLine.Facts(everyMin = 115200)))
        assertEquals("hourly", ScheduleLine.cadence(ScheduleLine.Facts(everyMin = 60)))
        assertEquals("one-off", ScheduleLine.cadence(ScheduleLine.Facts(at = "2026-12-01 09:00")))
    }

    @Test fun lines() {
        assertEquals("next tomorrow 07:00 · daily", line(ScheduleLine.Facts(cron = "0 7 * * *", next = at(22, 11, 7))))
        assertEquals("next 18:30 · weekly", line(ScheduleLine.Facts(cron = "30 18 * * 6", next = at(21, 11, 18, 30))))
        assertEquals("next Mon 13:00 · every 10 min", line(ScheduleLine.Facts(cron = "*/10 13-16 * * 1-5", next = at(23, 11, 13))))
        assertEquals("due 1 Dec · one-off", line(ScheduleLine.Facts(at = "2026-12-01 09:00", next = at(1, 12, 9))))
        assertEquals("due 15 Jul 2027 · one-off", line(ScheduleLine.Facts(at = "2027-07-15 09:00", next = at(15, 7, 9, y = 2027))))
        assertEquals("finished", line(ScheduleLine.Facts(cron = "0 7 * * *", retired = true)))
        assertEquals("", line(ScheduleLine.Facts()))
    }
}
