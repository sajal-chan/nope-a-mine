package com.morningblocker.scheduling

import com.morningblocker.storage.BlockingPolicy
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ZonedSessionTest {
    private fun resolve(date: String, start: String, end: String, zone: String): ZonedSessionWindow? {
        val policy = BlockingPolicy(
            MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), DayOfWeek.values().toSet()),
            setOf("com.instagram.android"),
        )
        return SessionClock.resolve(RestrictionSession(LocalDate.parse(date), policy), ZoneId.of(zone))
    }
    private fun assertWindow(window: ZonedSessionWindow?, start: String, end: String) {
        assertNotNull(window)
        assertEquals(Instant.parse(start), window!!.startsAt)
        assertEquals(Instant.parse(end), window.endsAt)
    }

    @Test fun `India window resolves its half-hour offset`() {
        assertWindow(resolve("2026-11-02", "06:00", "09:00", "Asia/Kolkata"), "2026-11-02T00:30:00Z", "2026-11-02T03:30:00Z")
    }

    @Test fun `ordinary New York morning resolves to absolute instants`() {
        assertWindow(resolve("2026-11-02", "06:00", "09:00", "America/New_York"), "2026-11-02T11:00:00Z", "2026-11-02T14:00:00Z")
    }

    @Test fun `spring transition reduces elapsed duration across the missing hour`() {
        val window = resolve("2026-03-08", "01:00", "04:00", "America/New_York")!!
        assertEquals(Duration.ofHours(2), Duration.between(window.startsAt, window.endsAt))
    }

    @Test fun `nonexistent start shifts forward by the DST gap`() {
        assertWindow(resolve("2026-03-08", "02:30", "04:00", "America/New_York"), "2026-03-08T07:30:00Z", "2026-03-08T08:00:00Z")
    }

    @Test fun `both nonexistent boundaries shift together`() {
        assertWindow(resolve("2026-03-08", "02:15", "02:45", "America/New_York"), "2026-03-08T07:15:00Z", "2026-03-08T07:45:00Z")
    }

    @Test fun `collapsed DST window is skipped`() {
        assertNull(resolve("2026-03-08", "02:30", "03:30", "America/New_York"))
    }

    @Test fun `inverted DST window is skipped instead of becoming overnight`() {
        assertNull(resolve("2026-03-08", "02:45", "03:15", "America/New_York"))
    }

    @Test fun `fall repeated hour is covered by one continuous window`() {
        assertWindow(resolve("2026-11-01", "01:15", "01:45", "America/New_York"), "2026-11-01T05:15:00Z", "2026-11-01T06:45:00Z")
    }

    @Test fun `future window follows the supplied zone without altering an earlier resolved window`() {
        val india = resolve("2026-11-02", "06:00", "09:00", "Asia/Kolkata")!!
        val london = resolve("2026-11-02", "06:00", "09:00", "Europe/London")!!
        assertEquals(Instant.parse("2026-11-02T03:30:00Z"), india.endsAt)
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), london.endsAt)
        assertEquals(ZoneId.of("Asia/Kolkata"), india.zone)
    }
}
