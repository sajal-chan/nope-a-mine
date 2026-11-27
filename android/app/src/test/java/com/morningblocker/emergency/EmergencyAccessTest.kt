package com.morningblocker.emergency

import com.morningblocker.emergency.EmergencyPhase.*
import org.junit.Assert.*
import org.junit.Test

class EmergencyAccessTest {
    private val initial = EmergencyContext("2026-11-02", OverrideClock(100_000, 7), 7_200_000)
    private fun at(elapsed: Long, remaining: Long = 7_200_000, boot: Int = 7, session: String = initial.sessionId) =
        EmergencyContext(session, OverrideClock(elapsed, boot), remaining)
    private fun waiting() = EmergencyAccess.request(null, initial)
    private fun unlocked() = EmergencyAccess.confirm(waiting(), at(160_000))

    @Test fun `active morning begins blocking with unused allowance`() {
        val state = EmergencyAccess.advance(null, initial)
        assertEquals(BLOCKING, state.phase)
        assertFalse(state.used)
    }

    @Test fun `outside morning window is inactive`() {
        assertEquals(INACTIVE, EmergencyAccess.advance(null, at(100_000, remaining = 0)).phase)
    }

    @Test fun `request establishes a real sixty-second deadline`() {
        val state = waiting()
        assertEquals(WAITING, state.phase)
        assertEquals(160_000L, state.readyAtElapsedMillis)
        assertNull(state.unlockUntilElapsedMillis)
        assertFalse(state.used)
    }

    @Test fun `apps remain restricted immediately before the wait ends`() {
        assertEquals(WAITING, EmergencyAccess.advance(waiting(), at(159_999)).phase)
    }

    @Test fun `elapsed wait becomes ready but never grants access automatically`() {
        val state = EmergencyAccess.advance(waiting(), at(160_000))
        assertEquals(READY_TO_UNLOCK, state.phase)
        assertFalse(state.used)
        assertNull(state.unlockUntilElapsedMillis)
    }

    @Test fun `confirmation before the waiting deadline is rejected`() {
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.confirm(waiting(), at(159_999)) }
    }

    @Test fun `confirmation without requesting access is rejected`() {
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.confirm(null, initial) }
    }

    @Test fun `explicit confirmation grants five minutes and consumes the allowance`() {
        val state = unlocked()
        assertEquals(OVERRIDE_ACTIVE, state.phase)
        assertTrue(state.used)
        assertEquals(460_000L, state.unlockUntilElapsedMillis)
        assertNull(state.readyAtElapsedMillis)
    }

    @Test fun `delayed confirmation starts the five minutes at confirmation time`() {
        val state = EmergencyAccess.confirm(waiting(), at(200_000))
        assertEquals(500_000L, state.unlockUntilElapsedMillis)
    }

    @Test fun `cancel during wait does not consume allowance and a new request waits again`() {
        val canceled = EmergencyAccess.cancel(waiting(), at(130_000))
        assertEquals(BLOCKING, canceled.phase)
        assertFalse(canceled.used)
        assertNull(canceled.readyAtElapsedMillis)
        val retry = EmergencyAccess.request(canceled, at(140_000))
        assertEquals(200_000L, retry.readyAtElapsedMillis)
    }

    @Test fun `cancel after ready also preserves allowance`() {
        val ready = EmergencyAccess.advance(waiting(), at(170_000))
        assertEquals(READY_TO_UNLOCK, ready.phase)
        val state = EmergencyAccess.cancel(ready, at(170_000))
        assertEquals(BLOCKING, state.phase)
        assertFalse(state.used)
        assertNull(state.readyAtElapsedMillis)
    }

    @Test fun `repeated requests cannot reset or shorten the waiting deadline`() {
        val repeated = EmergencyAccess.request(waiting(), at(120_000))
        assertEquals(160_000L, repeated.readyAtElapsedMillis)
        assertEquals(READY_TO_UNLOCK, EmergencyAccess.request(repeated, at(180_000)).phase)
    }

    @Test fun `repeated confirmation cannot extend temporary access`() {
        val repeated = EmergencyAccess.confirm(unlocked(), at(200_000))
        assertEquals(OVERRIDE_ACTIVE, repeated.phase)
        assertEquals(460_000L, repeated.unlockUntilElapsedMillis)
    }

    @Test fun `requesting during active override does not extend it`() {
        assertEquals(460_000L, EmergencyAccess.request(unlocked(), at(200_000)).unlockUntilElapsedMillis)
    }

    @Test fun `expiry occurs exactly at the fixed unlock deadline`() {
        assertEquals(OVERRIDE_ACTIVE, EmergencyAccess.advance(unlocked(), at(459_999)).phase)
        val expired = EmergencyAccess.advance(unlocked(), at(460_000))
        assertEquals(BLOCKING, expired.phase)
        assertTrue(expired.used)
        assertNull(expired.unlockUntilElapsedMillis)
    }

    @Test fun `one completed override prevents another request in that session`() {
        val expired = EmergencyAccess.advance(unlocked(), at(460_000))
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.request(expired, at(470_000)) }
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.confirm(expired, at(470_000)) }
    }

    @Test fun `confirmation caps access at the remaining morning window`() {
        val state = EmergencyAccess.confirm(waiting(), at(160_000, remaining = 30_000))
        assertEquals(190_000L, state.unlockUntilElapsedMillis)
        assertEquals(BLOCKING, EmergencyAccess.advance(state, at(190_000)).phase)
    }

    @Test fun `shrinking morning window shortens an active override`() {
        val shortened = EmergencyAccess.advance(unlocked(), at(200_000, remaining = 10_000))
        assertEquals(210_000L, shortened.unlockUntilElapsedMillis)
        assertEquals(BLOCKING, EmergencyAccess.advance(shortened, at(210_000)).phase)
    }

    @Test fun `increasing remaining morning time cannot extend an already confirmed override`() {
        val short = EmergencyAccess.confirm(waiting(), at(160_000, remaining = 30_000))
        val later = EmergencyAccess.advance(short, at(170_000, remaining = 7_200_000))
        assertEquals(190_000L, later.unlockUntilElapsedMillis)
    }

    @Test fun `morning window ending during wait cancels without consuming allowance`() {
        val ended = EmergencyAccess.advance(waiting(), at(130_000, remaining = 0))
        assertEquals(INACTIVE, ended.phase)
        assertFalse(ended.used)
        assertNull(ended.readyAtElapsedMillis)
        assertEquals(BLOCKING, EmergencyAccess.advance(ended, at(140_000)).phase)
    }

    @Test fun `confirmation outside morning window cannot unlock or consume allowance`() {
        assertThrows(IllegalStateException::class.java) {
            EmergencyAccess.confirm(waiting(), at(160_000, remaining = 0))
        }
    }

    @Test fun `request outside morning window is rejected`() {
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.request(null, at(100_000, remaining = 0)) }
    }

    @Test fun `morning window ending during override terminates it and preserves used flag on reentry`() {
        val ended = EmergencyAccess.advance(unlocked(), at(200_000, remaining = 0))
        assertEquals(INACTIVE, ended.phase)
        assertTrue(ended.used)
        assertNull(ended.unlockUntilElapsedMillis)
        val reentered = EmergencyAccess.advance(ended, at(210_000))
        assertEquals(BLOCKING, reentered.phase)
        assertTrue(reentered.used)
    }

    @Test fun `same-boot process restore keeps the original waiting deadline`() {
        val restored = waiting().copy()
        assertEquals(160_000L, EmergencyAccess.advance(restored, at(130_000)).readyAtElapsedMillis)
        assertEquals(READY_TO_UNLOCK, EmergencyAccess.advance(restored, at(160_000)).phase)
    }

    @Test fun `same-boot process restore keeps original access expiry`() {
        val restored = unlocked().copy()
        assertEquals(460_000L, EmergencyAccess.advance(restored, at(300_000)).unlockUntilElapsedMillis)
        assertEquals(BLOCKING, EmergencyAccess.advance(restored, at(460_000)).phase)
    }

    @Test fun `reboot during wait cancels it and requires a fresh wait`() {
        val restarted = EmergencyAccess.advance(waiting(), at(1_000, boot = 8))
        assertEquals(BLOCKING, restarted.phase)
        assertFalse(restarted.used)
        assertNull(restarted.readyAtElapsedMillis)
        assertEquals(61_000L, EmergencyAccess.request(restarted, at(1_000, boot = 8)).readyAtElapsedMillis)
    }

    @Test fun `reboot while ready cancels the pending request`() {
        val ready = EmergencyAccess.advance(waiting(), at(160_000))
        assertEquals(READY_TO_UNLOCK, ready.phase)
        val restarted = EmergencyAccess.advance(ready, at(1_000, boot = 8))
        assertEquals(BLOCKING, restarted.phase)
        assertFalse(restarted.used)
        assertNull(restarted.readyAtElapsedMillis)
    }

    @Test fun `reboot during access ends it but retains completed override usage`() {
        val restarted = EmergencyAccess.advance(unlocked(), at(1_000, boot = 8))
        assertEquals(BLOCKING, restarted.phase)
        assertTrue(restarted.used)
        assertNull(restarted.unlockUntilElapsedMillis)
        assertThrows(IllegalStateException::class.java) { EmergencyAccess.request(restarted, at(2_000, boot = 8)) }
    }

    @Test fun `a different genuine morning session starts with a fresh allowance`() {
        val previous = unlocked()
        assertTrue(previous.used)
        val next = EmergencyAccess.advance(previous, at(500_000, session = "2026-11-03"))
        assertEquals(BLOCKING, next.phase)
        assertFalse(next.used)
        assertNull(next.readyAtElapsedMillis)
        assertNull(next.unlockUntilElapsedMillis)
    }

    @Test fun `elapsed time regression in the same boot is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EmergencyAccess.advance(waiting(), at(99_999)) }
    }

    @Test fun `canceling already granted access cannot restore the allowance`() {
        val state = EmergencyAccess.cancel(unlocked(), at(200_000))
        assertEquals(OVERRIDE_ACTIVE, state.phase)
        assertTrue(state.used)
        assertEquals(460_000L, state.unlockUntilElapsedMillis)
    }

    @Test fun `deadline arithmetic cannot overflow into an immediate unlock`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmergencyAccess.request(null, at(Long.MAX_VALUE - 10))
        }
    }

    @Test fun `saved wait cannot claim a deadline beyond the maximum waiting span`() {
        val state = waiting()
        assertThrows(IllegalArgumentException::class.java) {
            state.copy(readyAtElapsedMillis = state.lastElapsedMillis + 60_001)
        }
    }

    @Test fun `saved access cannot claim more than five minutes from its anchor`() {
        val state = unlocked()
        assertThrows(IllegalArgumentException::class.java) {
            state.copy(unlockUntilElapsedMillis = state.lastElapsedMillis + 300_001)
        }
    }

    @Test fun `invalid native inputs and inconsistent saved state are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OverrideClock(-1, 7) }
        assertThrows(IllegalArgumentException::class.java) { OverrideClock(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(sessionId = "") }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(sessionRemainingMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { EmergencyState(initial.sessionId, 7, 100_000, phase = OVERRIDE_ACTIVE) }
    }
}
