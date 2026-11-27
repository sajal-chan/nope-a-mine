package com.morningblocker.emergency

private const val WAIT_MILLIS = 60_000L
private const val ACCESS_MILLIS = 300_000L

enum class EmergencyPhase { INACTIVE, BLOCKING, WAITING, READY_TO_UNLOCK, OVERRIDE_ACTIVE }

data class OverrideClock(val elapsedRealtimeMillis: Long, val bootCount: Int) {
    init {
        require(elapsedRealtimeMillis >= 0) { "Elapsed realtime cannot be negative" }
        require(bootCount >= 0) { "A valid boot identity is required" }
    }
}

data class EmergencyContext(
    val sessionId: String,
    val clock: OverrideClock,
    val sessionRemainingMillis: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "A stable native session identity is required" }
        require(sessionRemainingMillis >= 0) { "Session time remaining cannot be negative" }
    }
}

data class EmergencyState(
    val sessionId: String,
    val bootCount: Int,
    val lastElapsedMillis: Long,
    val phase: EmergencyPhase = EmergencyPhase.BLOCKING,
    val used: Boolean = false,
    val readyAtElapsedMillis: Long? = null,
    val unlockUntilElapsedMillis: Long? = null,
) {
    init {
        require(sessionId.isNotBlank() && bootCount >= 0 && lastElapsedMillis >= 0) { "Invalid saved identity or clock" }
        require(readyAtElapsedMillis == null || readyAtElapsedMillis >= 0) { "Invalid waiting deadline" }
        require(unlockUntilElapsedMillis == null || unlockUntilElapsedMillis >= 0) { "Invalid access deadline" }
        require(readyAtElapsedMillis == null || readyAtElapsedMillis - lastElapsedMillis <= WAIT_MILLIS) {
            "Saved wait exceeds sixty seconds from its observed clock"
        }
        require(unlockUntilElapsedMillis == null || unlockUntilElapsedMillis - lastElapsedMillis <= ACCESS_MILLIS) {
            "Saved access exceeds five minutes from its observed clock"
        }
        when (phase) {
            EmergencyPhase.WAITING, EmergencyPhase.READY_TO_UNLOCK ->
                require(!used && readyAtElapsedMillis != null && unlockUntilElapsedMillis == null) { "Invalid pending request" }
            EmergencyPhase.OVERRIDE_ACTIVE ->
                require(used && readyAtElapsedMillis == null && unlockUntilElapsedMillis != null) { "Invalid active override" }
            EmergencyPhase.BLOCKING, EmergencyPhase.INACTIVE ->
                require(readyAtElapsedMillis == null && unlockUntilElapsedMillis == null) { "Inactive timers must be cleared" }
        }
    }
}

object EmergencyAccess {
    /** Re-evaluate before using the phase. No wall-clock or JavaScript timers are consulted. */
    fun advance(saved: EmergencyState?, context: EmergencyContext): EmergencyState {
        val previous = saved?.takeIf { it.sessionId == context.sessionId }
            ?: EmergencyState(context.sessionId, context.clock.bootCount, context.clock.elapsedRealtimeMillis)
        val sameBoot = previous.bootCount == context.clock.bootCount
        if (sameBoot) require(context.clock.elapsedRealtimeMillis >= previous.lastElapsedMillis) {
            "Elapsed realtime regressed within the same boot"
        }
        if (context.sessionRemainingMillis == 0L || !sameBoot) return previous.copy(
            bootCount = context.clock.bootCount,
            lastElapsedMillis = context.clock.elapsedRealtimeMillis,
            phase = if (context.sessionRemainingMillis == 0L) EmergencyPhase.INACTIVE else EmergencyPhase.BLOCKING,
            readyAtElapsedMillis = null,
            unlockUntilElapsedMillis = null,
        )
        val state = previous.copy(lastElapsedMillis = context.clock.elapsedRealtimeMillis)
        return when (state.phase) {
            EmergencyPhase.INACTIVE -> state.withoutTimers(EmergencyPhase.BLOCKING)
            EmergencyPhase.BLOCKING -> state
            EmergencyPhase.WAITING, EmergencyPhase.READY_TO_UNLOCK -> state.copy(
                phase = if (state.lastElapsedMillis >= requireNotNull(state.readyAtElapsedMillis))
                    EmergencyPhase.READY_TO_UNLOCK else EmergencyPhase.WAITING,
            )
            EmergencyPhase.OVERRIDE_ACTIVE -> {
                val until = requireNotNull(state.unlockUntilElapsedMillis)
                if (state.lastElapsedMillis >= until) state.withoutTimers(EmergencyPhase.BLOCKING)
                else {
                    // A shorter morning window may shorten access; a longer one cannot extend it.
                    val remaining = minOf(until - state.lastElapsedMillis, context.sessionRemainingMillis)
                    state.copy(unlockUntilElapsedMillis = deadline(state.lastElapsedMillis, remaining))
                }
            }
        }
    }

    fun request(saved: EmergencyState?, context: EmergencyContext): EmergencyState {
        val state = advance(saved, context)
        check(state.phase != EmergencyPhase.INACTIVE) { "No active morning restriction" }
        if (state.phase == EmergencyPhase.WAITING || state.phase == EmergencyPhase.READY_TO_UNLOCK ||
            state.phase == EmergencyPhase.OVERRIDE_ACTIVE) return state
        check(!state.used) { "Emergency access has already been used for this session" }
        return state.copy(phase = EmergencyPhase.WAITING, readyAtElapsedMillis = deadline(state.lastElapsedMillis, WAIT_MILLIS))
    }

    fun confirm(saved: EmergencyState?, context: EmergencyContext): EmergencyState {
        val state = advance(saved, context)
        if (state.phase == EmergencyPhase.OVERRIDE_ACTIVE) return state
        check(state.phase == EmergencyPhase.READY_TO_UNLOCK && !state.used) { "Finish the wait before confirming emergency access" }
        return state.copy(
            phase = EmergencyPhase.OVERRIDE_ACTIVE,
            used = true,
            readyAtElapsedMillis = null,
            unlockUntilElapsedMillis = deadline(state.lastElapsedMillis, minOf(ACCESS_MILLIS, context.sessionRemainingMillis)),
        )
    }

    fun cancel(saved: EmergencyState?, context: EmergencyContext): EmergencyState {
        val state = advance(saved, context)
        return if (state.phase == EmergencyPhase.WAITING || state.phase == EmergencyPhase.READY_TO_UNLOCK)
            state.withoutTimers(EmergencyPhase.BLOCKING) else state
    }

    private fun EmergencyState.withoutTimers(phase: EmergencyPhase) =
        copy(phase = phase, readyAtElapsedMillis = null, unlockUntilElapsedMillis = null)

    private fun deadline(now: Long, delay: Long): Long {
        require(delay >= 0 && now <= Long.MAX_VALUE - delay) { "Elapsed deadline cannot be represented safely" }
        return now + delay
    }
}
