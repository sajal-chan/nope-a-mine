package com.morningblocker.scheduling

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SessionClockTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val window = ZonedSessionWindow(Instant.parse("2026-11-02T00:30:00Z"), Instant.parse("2026-11-02T03:30:00Z"), zone)
    private val initial = ClockReading(Instant.parse("2026-11-02T01:30:00Z"), 100_000, 7, zone)
    private fun start() = SessionClock.start(window, initial)

    @Test fun `starting late retains only time until the original unlock instant`() {
        val state = start()
        assertEquals(Duration.ofHours(2), state.remaining)
        assertEquals(window.endsAt, state.recoveryDeadline)
        assertFalse(state.completed)
    }

    @Test fun `starting before the window is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionClock.start(window, initial.copy(instant = window.startsAt.minusSeconds(1)))
        }
    }

    @Test fun `starting at end creates a completed marker`() {
        val state = SessionClock.start(window, initial.copy(instant = window.endsAt))
        assertTrue(state.completed)
        assertEquals(Duration.ZERO, state.remaining)
    }

    @Test fun `same boot uses elapsed time across a process restart`() {
        val restored = start().copy()
        val state = SessionClock.recover(restored, initial.copy(
            instant = initial.instant.plusSeconds(3600), elapsedRealtimeMillis = 3_700_000,
        ))
        assertEquals(Duration.ofHours(1), state.remaining)
        assertFalse(state.completed)
    }

    @Test fun `moving wall clock forward does not expire the active duration early`() {
        val state = SessionClock.recover(start(), initial.copy(
            instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 3_700_000,
        ))
        assertEquals(Duration.ofHours(1), state.remaining)
        assertFalse(state.completed)
    }

    @Test fun `moving wall clock backwards cannot extend remaining duration`() {
        val state = SessionClock.recover(start(), initial.copy(
            instant = initial.instant.minusSeconds(7200), elapsedRealtimeMillis = 3_700_000,
        ))
        assertEquals(Duration.ofHours(1), state.remaining)
    }

    @Test fun `time-zone change does not reset the active deadline`() {
        val state = SessionClock.recover(start(), initial.copy(zone = ZoneId.of("America/New_York")))
        assertEquals(Duration.ofHours(2), state.remaining)
        assertEquals(zone, state.window.zone)
        assertEquals(window.endsAt, state.window.endsAt)
    }

    @Test fun `elapsed time includes a long sleep interval without adding it back`() {
        val state = SessionClock.recover(start(), initial.copy(elapsedRealtimeMillis = 7_300_000))
        assertEquals(Duration.ZERO, state.remaining)
        assertTrue(state.completed)
    }

    @Test fun `reboot recovers from absolute deadline rather than new uptime`() {
        val state = SessionClock.recover(start(), initial.copy(
            instant = initial.instant.plusSeconds(1800), elapsedRealtimeMillis = 1_000, bootCount = 8,
        ))
        assertEquals(Duration.ofMinutes(90), state.remaining)
        assertEquals(8, state.bootCount)
        assertEquals(1_000L, state.anchorElapsedMillis)
    }

    @Test fun `wall-clock rollback across reboot cannot increase last saved remaining duration`() {
        val state = SessionClock.recover(start(), initial.copy(
            instant = initial.instant.minusSeconds(7200), elapsedRealtimeMillis = 1_000, bootCount = 8,
        ))
        assertEquals(Duration.ofHours(2), state.remaining)
    }

    @Test fun `reboot after the absolute deadline completes the session`() {
        val state = SessionClock.recover(start(), initial.copy(
            instant = window.endsAt, elapsedRealtimeMillis = 1_000, bootCount = 8,
        ))
        assertTrue(state.completed)
        assertEquals(Duration.ZERO, state.remaining)
    }

    @Test fun `completed marker cannot revive after clock rollback or another reboot`() {
        val completed = SessionClock.recover(start(), initial.copy(elapsedRealtimeMillis = 7_300_000))
        val restored = SessionClock.recover(completed, initial.copy(
            instant = initial.instant.minusSeconds(7200), elapsedRealtimeMillis = 1_000, bootCount = 8,
        ))
        assertTrue(restored.completed)
        assertEquals(Duration.ZERO, restored.remaining)
    }

    @Test fun `same boot with regressing elapsed time is an explicit error`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionClock.recover(start(), initial.copy(elapsedRealtimeMillis = 99_999))
        }
    }

    @Test fun `observed wall-clock changes update the saved reboot fallback`() {
        val adjusted = SessionClock.recover(start(), initial.copy(
            instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 3_700_000,
        ))
        assertEquals(Instant.parse("2026-11-02T13:00:00Z"), adjusted.recoveryDeadline)
        val rebooted = SessionClock.recover(adjusted, initial.copy(
            instant = Instant.parse("2026-11-02T12:30:00Z"), elapsedRealtimeMillis = 1_000, bootCount = 8,
        ))
        assertEquals(Duration.ofMinutes(30), rebooted.remaining)
    }

    @Test fun `negative uptime or boot count cannot enter recovery state`() {
        assertThrows(IllegalArgumentException::class.java) { initial.copy(elapsedRealtimeMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(bootCount = -1) }
    }

    @Test fun `invalid recovery metadata is rejected rather than weakening the deadline`() {
        val valid = SessionClockState(window, 7, 100_000, Duration.ofHours(2), window.endsAt, false)
        assertThrows(IllegalArgumentException::class.java) { valid.copy(anchorElapsedMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(bootCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(remaining = Duration.ofSeconds(-1)) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(completed = true) }
        assertThrows(IllegalArgumentException::class.java) { ZonedSessionWindow(window.endsAt, window.startsAt, zone) }
    }

    @Test fun `submillisecond remaining duration is not rounded down early`() {
        val state = SessionClock.start(window, initial.copy(instant = window.endsAt.minusNanos(1)))
        assertFalse(state.completed)
        assertEquals(Duration.ofNanos(1), state.remaining)
        assertTrue(SessionClock.recover(state, initial.copy(elapsedRealtimeMillis = 100_001)).completed)
    }
}
