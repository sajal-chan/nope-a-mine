package com.morningblocker.scheduling

import java.time.LocalDate
import java.time.LocalDateTime

data class ScheduledSession(val startsAt: LocalDateTime, val endsAt: LocalDateTime)

object ScheduleRules {
    /** Start inclusive, end exclusive. Caller supplies local time; no global clock is read. */
    fun activeSession(schedule: MorningSchedule, now: LocalDateTime): ScheduledSession? {
        val session = sessionOn(schedule, now.toLocalDate()) ?: return null
        return session.takeIf { !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) }
    }

    /** First strictly future start, excluding the current session. Null for no active days. */
    fun nextSession(schedule: MorningSchedule, now: LocalDateTime): ScheduledSession? {
        if (schedule.activeDays.isEmpty()) return null
        // Include day seven so today's weekday can recur next week after its start has passed.
        for (daysAhead in 0L..7L) {
            val session = sessionOn(schedule, now.toLocalDate().plusDays(daysAhead)) ?: continue
            if (session.startsAt.isAfter(now)) return session
        }
        return null
    }

    private fun sessionOn(schedule: MorningSchedule, date: LocalDate): ScheduledSession? {
        if (date.dayOfWeek !in schedule.activeDays) return null
        return ScheduledSession(date.atTime(schedule.startTime), date.atTime(schedule.endTime))
    }
}
