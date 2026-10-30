package com.morningblocker.scheduling

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ZonedSessionTest {
    private fun active(start: String, end: String, instant: String, zone: String): Boolean {
        val schedule = MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), DayOfWeek.values().toSet())
        val local = Instant.parse(instant).atZone(ZoneId.of(zone)).toLocalDateTime()
        return ScheduleRules.activeSession(schedule, local) != null
    }

    @Test fun `India uses its current half-hour local offset`() {
        assertTrue(active("06:00", "09:00", "2026-11-02T00:30:00Z", "Asia/Kolkata"))
        assertFalse(active("06:00", "09:00", "2026-11-02T03:30:00Z", "Asia/Kolkata"))
    }

    @Test fun `ordinary New York start is inclusive and end exclusive`() {
        assertTrue(active("06:00", "09:00", "2026-11-02T11:00:00Z", "America/New_York"))
        assertFalse(active("06:00", "09:00", "2026-11-02T14:00:00Z", "America/New_York"))
    }

    @Test fun `spring gap follows the clock directly when it jumps into the window`() {
        assertTrue(active("02:30", "04:00", "2026-03-08T07:10:00Z", "America/New_York"))
        assertFalse(active("02:30", "04:00", "2026-03-08T08:00:00Z", "America/New_York"))
    }

    @Test fun `window entirely in a skipped hour never becomes active`() {
        assertFalse(active("02:15", "02:45", "2026-03-08T06:59:00Z", "America/New_York"))
        assertFalse(active("02:15", "02:45", "2026-03-08T07:00:00Z", "America/New_York"))
    }

    @Test fun `fall repeated hour blocks only while local clock is inside the window`() {
        assertTrue(active("01:15", "01:45", "2026-11-01T05:20:00Z", "America/New_York"))
        assertFalse(active("01:15", "01:45", "2026-11-01T05:50:00Z", "America/New_York"))
        assertTrue(active("01:15", "01:45", "2026-11-01T06:20:00Z", "America/New_York"))
        assertFalse(active("01:15", "01:45", "2026-11-01T06:45:00Z", "America/New_York"))
    }

    @Test fun `same instant can be inside one zones hours and outside another`() {
        assertTrue(active("06:00", "09:00", "2026-11-02T07:00:00Z", "Europe/London"))
        assertFalse(active("06:00", "09:00", "2026-11-02T07:00:00Z", "Asia/Kolkata"))
    }
}
