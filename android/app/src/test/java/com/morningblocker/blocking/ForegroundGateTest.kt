package com.morningblocker.blocking

import org.junit.Assert.*
import org.junit.Test

class ForegroundGateTest {
    private class Surface : BlockingSurface {
        var shows = 0
        var hides = 0
        var succeeds = true
        override fun show(): Boolean { shows++; return succeeds }
        override fun hide() { hides++ }
    }

    @Test fun `Instagram opens one overlay despite repeated events`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        repeat(10) { gate.onForegroundApp("com.instagram.android") }
        assertEquals(1, surface.shows)
        assertTrue(gate.isBlocking)
    }

    @Test fun `unrelated apps are never blocked`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        listOf("com.android.settings", "com.google.android.youtube", "com.morningblocker").forEach(gate::onForegroundApp)
        assertEquals(0, surface.shows)
        assertFalse(gate.isBlocking)
    }

    @Test fun `leaving Instagram removes overlay and reopening blocks again`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.onForegroundApp("com.instagram.android")
        gate.onForegroundApp("com.android.settings")
        assertFalse(gate.isBlocking)
        assertEquals(1, surface.hides)
        gate.onForegroundApp("com.instagram.android")
        assertEquals(2, surface.shows)
    }

    @Test fun `unknown foreground does not dismiss a visible block`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.onForegroundApp("com.instagram.android")
        gate.onForegroundApp(null)
        assertTrue(gate.isBlocking)
        assertEquals(0, surface.hides)
    }

    @Test fun `failed presentation is not reported as blocking and can retry`() {
        val surface = Surface().apply { succeeds = false }
        val gate = ForegroundGate(surface)
        gate.onForegroundApp("com.instagram.android")
        assertFalse(gate.isBlocking)
        surface.succeeds = true
        gate.onForegroundApp("com.instagram.android")
        assertTrue(gate.isBlocking)
        assertEquals(2, surface.shows)
    }

    @Test fun `service shutdown cleans up overlay`() {
        val surface = Surface()
        val gate = ForegroundGate(surface)
        gate.onForegroundApp("com.instagram.android")
        gate.close()
        gate.close()
        assertFalse(gate.isBlocking)
        assertEquals(1, surface.hides)
    }
}
