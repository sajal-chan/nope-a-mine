package com.morningblocker.blocking

/** Process-local observation, not persisted policy and not a reliability guarantee. */
object EnforcementStatus {
    @Volatile var connected = false
    @Volatile var blocking = false
    @Volatile var error: String? = null
}
