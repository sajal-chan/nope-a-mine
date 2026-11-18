package com.morningblocker.blocking

import com.facebook.react.bridge.ReadableMap
import com.morningblocker.scheduling.AndroidSessionClock
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.storage.PolicyRepository

class ScheduleConfigurationAccess(
    private val repository: PolicyRepository,
    private val clock: () -> ClockReading = AndroidSessionClock()::read,
) {
    suspend fun read(): Map<String, Any?> = ScheduleConfigurationCodec.encode(repository.configurationAt(clock()))
    suspend fun save(input: ReadableMap?): Map<String, Any?> {
        val policy = ScheduleConfigurationCodec.decode(input)
        return ScheduleConfigurationCodec.encode(repository.saveConfiguration(policy, clock()))
    }
}
