package com.morningblocker.scheduling

import java.time.Clock
import java.time.ZoneId

class AndroidSessionClock(
    private val wallClock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun read(): ClockReading = ClockReading(wallClock.instant(), zone())
}
