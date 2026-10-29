package com.morningblocker.scheduling

import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleRulesTest {
    private val six = LocalTime.of(6, 0)
    private val nine = LocalTime.of(9, 0)
    private val monday = MorningSchedule(six, nine, setOf(MONDAY))
    private fun at(value: String) = LocalDateTime.parse(value)
    private fun session(date: String) = ScheduledSession(at("${date}T06:00"), at("${date}T09:00"))

    @Test fun `session starts inclusively at configured start`() {
        assertEquals(session("2026-11-02"), ScheduleRules.activeSession(monday, at("2026-11-02T06:00")))
    }

    @Test fun `session is active between its boundaries`() {
        assertEquals(session("2026-11-02"), ScheduleRules.activeSession(monday, at("2026-11-02T07:30")))
    }

    @Test fun `session remains active immediately before end`() {
        assertEquals(session("2026-11-02"), ScheduleRules.activeSession(monday, at("2026-11-02T08:59:59.999999999")))
    }

    @Test fun `session ends exclusively at configured end`() {
        assertNull(ScheduleRules.activeSession(monday, at("2026-11-02T09:00")))
    }

    @Test fun `no session before configured start`() {
        assertNull(ScheduleRules.activeSession(monday, at("2026-11-02T05:59:59.999999999")))
    }

    @Test fun `no session after configured end`() {
        assertNull(ScheduleRules.activeSession(monday, at("2026-11-02T10:00")))
    }

    @Test fun `matching time on unselected weekday is inactive`() {
        assertNull(ScheduleRules.activeSession(monday, at("2026-11-03T07:00")))
    }

    @Test fun `each weekday including Sunday is selectable independently`() {
        val weekStart = at("2026-11-02T07:00")
        for (day in DayOfWeek.values()) {
            val schedule = MorningSchedule(six, nine, setOf(day))
            val now = weekStart.plusDays((day.value - 1).toLong())
            assertNotNull("Selected $day must be active", ScheduleRules.activeSession(schedule, now))
            assertNull("Following day must be inactive", ScheduleRules.activeSession(schedule, now.plusDays(1)))
        }
    }

    @Test fun `no selected weekdays disables current and upcoming sessions`() {
        val schedule = MorningSchedule(six, nine, emptySet())
        assertNull(ScheduleRules.activeSession(schedule, at("2026-11-02T07:00")))
        assertNull(ScheduleRules.nextSession(schedule, at("2026-11-02T05:00")))
    }

    @Test fun `equal start and end is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MorningSchedule(six, six, setOf(MONDAY)) }
    }

    @Test fun `window crossing midnight is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MorningSchedule(LocalTime.of(23, 0), six, setOf(MONDAY))
        }
    }

    @Test fun `a same-day window may start at midnight`() {
        val schedule = MorningSchedule(LocalTime.MIDNIGHT, six, setOf(MONDAY))
        assertEquals(
            ScheduledSession(at("2026-11-02T00:00"), at("2026-11-02T06:00")),
            ScheduleRules.activeSession(schedule, at("2026-11-02T00:00")),
        )
    }

    @Test fun `before start the next session is today`() {
        assertEquals(session("2026-11-02"), ScheduleRules.nextSession(monday, at("2026-11-02T05:00")))
    }

    @Test fun `exactly at start next session excludes the one just started`() {
        assertEquals(session("2026-11-09"), ScheduleRules.nextSession(monday, at("2026-11-02T06:00")))
    }

    @Test fun `during a session the next session starts on a later selected day`() {
        val schedule = MorningSchedule(six, nine, setOf(MONDAY, WEDNESDAY))
        assertEquals(session("2026-11-04"), ScheduleRules.nextSession(schedule, at("2026-11-02T07:00")))
    }

    @Test fun `at end the next session may be the same weekday next week`() {
        assertEquals(session("2026-11-09"), ScheduleRules.nextSession(monday, at("2026-11-02T09:00")))
    }

    @Test fun `after end daily schedule advances to tomorrow`() {
        val daily = MorningSchedule(six, nine, DayOfWeek.values().toSet())
        assertEquals(session("2026-11-03"), ScheduleRules.nextSession(daily, at("2026-11-02T12:00")))
    }

    @Test fun `next session chooses closest eligible day regardless of set order`() {
        val schedule = MorningSchedule(six, nine, linkedSetOf(FRIDAY, WEDNESDAY))
        assertEquals(session("2026-11-04"), ScheduleRules.nextSession(schedule, at("2026-11-02T05:00")))
    }

    @Test fun `next session crosses Sunday into the following week and month`() {
        assertEquals(session("2026-11-02"), ScheduleRules.nextSession(monday, at("2026-10-31T12:00")))
    }

    @Test fun `next session crosses the year boundary`() {
        val friday = MorningSchedule(six, nine, setOf(FRIDAY))
        assertEquals(session("2027-01-01"), ScheduleRules.nextSession(friday, at("2026-12-31T12:00")))
    }

    @Test fun `changing caller weekday set cannot mutate an existing schedule`() {
        val days = mutableSetOf(MONDAY)
        val schedule = MorningSchedule(six, nine, days)
        days.clear()
        days.add(TUESDAY)
        assertEquals(setOf(MONDAY), schedule.activeDays)
        assertEquals(session("2026-11-02"), ScheduleRules.activeSession(schedule, at("2026-11-02T07:00")))
        assertNull(ScheduleRules.activeSession(schedule, at("2026-11-03T07:00")))
    }
}
