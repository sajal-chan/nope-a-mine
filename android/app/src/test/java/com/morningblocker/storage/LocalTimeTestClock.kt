package com.morningblocker.storage

import com.morningblocker.scheduling.ClockReading
import java.time.LocalDateTime
import java.time.ZoneOffset

// Existing local-rule tests advance wall and elapsed time together in a fixed UTC boot.
private fun LocalDateTime.testClock(): ClockReading {
    val instant = toInstant(ZoneOffset.UTC)
    return ClockReading(instant, instant.toEpochMilli(), 1, ZoneOffset.UTC)
}

internal suspend fun PolicyRepository.save(policy: BlockingPolicy, now: LocalDateTime) = save(policy, now.testClock())
internal suspend fun PolicyRepository.activeSessionAt(now: LocalDateTime) = activeSessionAt(now.testClock())
