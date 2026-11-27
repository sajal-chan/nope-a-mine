package com.morningblocker.scheduling

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Collections

/** A recurring same-day window in local wall-clock time. Empty weekdays disable it. */
class MorningSchedule(
    val startTime: LocalTime,
    val endTime: LocalTime,
    activeDays: Set<DayOfWeek>,
) {
    val activeDays: Set<DayOfWeek> = Collections.unmodifiableSet(activeDays.toSet())

    init {
        require(startTime.isBefore(endTime)) {
            "Start must be earlier than end; empty and overnight windows are not supported"
        }
    }
}
