package com.morningblocker.scheduling

import java.time.Instant
import java.time.ZoneId

/** Current wall-clock time and zone. Morning windows are not duration-based countdowns. */
data class ClockReading(val instant: Instant, val zone: ZoneId)
