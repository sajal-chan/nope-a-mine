package com.morningblocker.scheduling

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class ClockReading(val instant: Instant, val elapsedRealtimeMillis: Long, val bootCount: Int, val zone: ZoneId) {
    init {
        require(elapsedRealtimeMillis >= 0) { "Elapsed realtime cannot be negative" }
        require(bootCount >= 0) { "Boot identity is unavailable" }
    }
}

data class ZonedSessionWindow(val startsAt: Instant, val endsAt: Instant, val zone: ZoneId) {
    init { require(startsAt.isBefore(endsAt)) { "Resolved session must have positive duration" } }
}

/** Immutable checkpoint. A repository must persist recovered checkpoints before relying on them. */
data class SessionClockState(
    val window: ZonedSessionWindow,
    val bootCount: Int,
    val anchorElapsedMillis: Long,
    val remaining: Duration,
    val recoveryDeadline: Instant,
    val completed: Boolean,
) {
    init {
        require(bootCount >= 0 && anchorElapsedMillis >= 0) { "Invalid boot/elapsed anchor" }
        require(!remaining.isNegative && remaining <= Duration.between(window.startsAt, window.endsAt)) {
            "Remaining duration must fit within the original session"
        }
        require(completed == remaining.isZero) { "Completed state must agree with remaining duration" }
    }
}

object SessionClock {
    /** Gap boundaries shift forward. An overlap starts at its first offset and ends at its last. */
    fun resolve(session: RestrictionSession, zone: ZoneId): ZonedSessionWindow? {
        val start = session.window.startsAt.atZone(zone).withEarlierOffsetAtOverlap().toInstant()
        val end = session.window.endsAt.atZone(zone).withLaterOffsetAtOverlap().toInstant()
        return if (start.isBefore(end)) ZonedSessionWindow(start, end, zone) else null
    }

    fun start(window: ZonedSessionWindow, now: ClockReading): SessionClockState {
        require(!now.instant.isBefore(window.startsAt)) { "Cannot start a future session" }
        val remaining = Duration.between(now.instant, window.endsAt).coerceAtLeast(Duration.ZERO)
        return SessionClockState(window, now.bootCount, now.elapsedRealtimeMillis, remaining, window.endsAt, remaining.isZero)
    }

    /** Monotonic within one boot; capped absolute-time recovery after boot identity changes. */
    fun recover(saved: SessionClockState, now: ClockReading): SessionClockState {
        if (saved.completed) return saved
        val remaining = if (saved.bootCount == now.bootCount) {
            require(now.elapsedRealtimeMillis >= saved.anchorElapsedMillis) { "Elapsed clock regressed within the same boot" }
            saved.remaining.minusMillis(now.elapsedRealtimeMillis - saved.anchorElapsedMillis)
        } else {
            // Reboot loses the previous monotonic clock. Never increase the last checkpoint's remainder.
            Duration.between(now.instant, saved.recoveryDeadline).coerceAtMost(saved.remaining)
        }.coerceAtLeast(Duration.ZERO)
        return saved.copy(
            bootCount = now.bootCount,
            anchorElapsedMillis = now.elapsedRealtimeMillis,
            remaining = remaining,
            // Re-anchor observed wall-clock changes for a later reboot without changing the original window.
            recoveryDeadline = now.instant.plus(remaining),
            completed = remaining.isZero,
        )
    }
}
