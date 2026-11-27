package com.morningblocker.blocking

import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.storage.BlockingPolicy
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ScheduledForegroundGateTest {
    private class Surface : BlockingSurface {
        val blocks = mutableListOf<RestrictionDecision.Blocked>()
        var hides = 0
        var succeeds = true
        override fun show(block: RestrictionDecision.Blocked): Boolean { blocks += block; return succeeds }
        override fun hide() { hides++ }
    }
    private val session = RestrictionSession(LocalDate.of(2026, 11, 2), BlockingPolicy(
        MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), DayOfWeek.values().toSet()),
        setOf("com.instagram.android", "com.google.android.youtube"),
    ))
    private fun block(packageName: String = "com.instagram.android", effective: RestrictionSession = session) =
        RestrictionDecision.Blocked(packageName, effective)

    @Test fun `repeated equivalent scheduled decisions show one overlay`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        repeat(10) { assertTrue(gate.applyDecision(block(effective = RestrictionSession(session.date, session.policy)))) }
        assertEquals(1, surface.blocks.size)
        assertTrue(gate.isBlocking)
    }

    @Test fun `allowed and inactive decisions do not block`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        assertTrue(gate.applyDecision(RestrictionDecision.Allowed(session)))
        assertTrue(gate.applyDecision(RestrictionDecision.Inactive))
        assertTrue(surface.blocks.isEmpty())
        assertFalse(gate.isBlocking)
    }

    @Test fun `session expiry removes overlay even without a known foreground`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.applyDecision(block())
        assertTrue(gate.applyDecision(RestrictionDecision.Inactive))
        assertFalse(gate.isBlocking)
        assertEquals(1, surface.hides)
    }

    @Test fun `allowed app removes overlay and reopening a selected app blocks again`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.applyDecision(block())
        gate.applyDecision(RestrictionDecision.Allowed(session))
        assertFalse(gate.isBlocking)
        assertEquals(1, surface.hides)
        gate.applyDecision(block())
        assertEquals(2, surface.blocks.size)
    }

    @Test fun `unknown active foreground preserves a visible block and requests recovery`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.applyDecision(block())
        assertFalse(gate.applyDecision(RestrictionDecision.UnknownForeground(session)))
        assertTrue(gate.isBlocking)
        assertEquals(0, surface.hides)
    }

    @Test fun `selected app and session end changes refresh existing presentation`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.applyDecision(block())
        gate.applyDecision(block("com.google.android.youtube"))
        val next = RestrictionSession(session.date.plusDays(1), session.policy)
        gate.applyDecision(block("com.google.android.youtube", next))
        assertEquals(3, surface.blocks.size)
        assertEquals("com.google.android.youtube", surface.blocks.last().packageName)
        assertEquals(next.window.endsAt, surface.blocks.last().session.window.endsAt)
        assertEquals(0, surface.hides)
    }

    @Test fun `failed presentation is not reported as blocking and retries`() {
        val surface = Surface().apply { succeeds = false }
        val gate = ForegroundGate(surface)
        assertFalse(gate.applyDecision(block()))
        assertFalse(gate.isBlocking)
        surface.succeeds = true
        assertTrue(gate.applyDecision(block()))
        assertTrue(gate.isBlocking)
        assertEquals(2, surface.blocks.size)
    }

    @Test fun `service shutdown cleans up overlay idempotently`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.applyDecision(block())
        gate.close()
        gate.close()
        assertFalse(gate.isBlocking)
        assertEquals(1, surface.hides)
    }
}
