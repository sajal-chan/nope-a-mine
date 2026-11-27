package com.morningblocker.blocking

import com.morningblocker.scheduling.AndroidSessionClock
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.storage.PolicyRepository

sealed interface RestrictionDecision {
    data object Inactive : RestrictionDecision
    data class Allowed(val session: RestrictionSession) : RestrictionDecision
    data class Blocked(val packageName: String, val session: RestrictionSession) : RestrictionDecision
    data class UnknownForeground(val session: RestrictionSession) : RestrictionDecision
}

/**
 * Evaluates one foreground snapshot using fresh local time and the persisted effective session.
 * UnknownForeground must not dismiss an existing overlay; Inactive may dismiss it even when
 * foreground detection is unavailable. IO, corruption, clock and cancellation failures propagate
 * so the service can report enforcement unavailability rather than treating them as inactivity.
 */
class RestrictionEngine(
    private val repository: PolicyRepository,
    private val clock: () -> ClockReading = AndroidSessionClock()::read,
) {
    suspend fun evaluate(packageName: String?): RestrictionDecision {
        val session = repository.activeSessionAt(clock()) ?: return RestrictionDecision.Inactive
        if (packageName.isNullOrBlank()) return RestrictionDecision.UnknownForeground(session)
        // Settings/revocation and our own configuration interface must remain available.
        if (packageName == "com.android.settings" || packageName == "com.morningblocker") {
            return RestrictionDecision.Allowed(session)
        }
        return if (packageName in session.policy.blockedPackages) {
            RestrictionDecision.Blocked(packageName, session)
        } else {
            RestrictionDecision.Allowed(session)
        }
    }
}
