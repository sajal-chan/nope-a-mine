package com.morningblocker.storage

import com.morningblocker.scheduling.ClockReading
import java.time.LocalDateTime
import java.time.ZoneOffset

// Local-rule fixtures use a fixed UTC zone.
private fun LocalDateTime.testClock(): ClockReading {
    val instant = toInstant(ZoneOffset.UTC)
    return ClockReading(instant, ZoneOffset.UTC)
}

internal suspend fun PolicyRepository.save(policy: BlockingPolicy, now: LocalDateTime) = save(policy, now.testClock())
internal suspend fun PolicyRepository.activeSessionAt(now: LocalDateTime) = activeSessionAt(now.testClock())
