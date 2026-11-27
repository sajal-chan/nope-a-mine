package com.morningblocker.scheduling

import com.morningblocker.storage.BlockingPolicy
import java.time.LocalDate

class RestrictionSession(val date: LocalDate, val policy: BlockingPolicy) {
    val window = ScheduledSession(date.atTime(policy.schedule.startTime), date.atTime(policy.schedule.endTime))

    init {
        require(date.dayOfWeek in policy.schedule.activeDays) { "Session date must be selected in its policy" }
        require(policy.blockedPackages.isNotEmpty()) { "A restriction session needs selected applications" }
    }
}
