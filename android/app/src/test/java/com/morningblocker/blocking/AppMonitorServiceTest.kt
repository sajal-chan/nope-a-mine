package com.morningblocker.blocking

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppMonitorServiceTest {
    class Service : AppMonitorService() {
        var foreground: String? = null
        var shows = 0
        var hides = 0
        var reads = 0
        var failRead = false
        var presentationSucceeds = true
        override fun readForegroundPackage(): String? {
            reads++
            if (failRead) throw IllegalStateException("Window snapshot temporarily unavailable")
            return foreground
        }
        override fun createSurface(): BlockingSurface = object : BlockingSurface {
            override fun show(): Boolean { shows++; return presentationSucceeds }
            override fun hide() { hides++ }
        }
    }

    @Test fun `late foreground snapshot recovers without another accessibility event`() = withService { service ->
        service.onServiceConnected()
        advance(100)
        assertEquals(0, service.shows)
        service.foreground = "com.instagram.android"
        advance(250)
        assertEquals(1, service.shows)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `transient presentation failure retries without another accessibility event`() = withService { service ->
        service.foreground = "com.instagram.android"
        service.presentationSucceeds = false
        service.onServiceConnected()
        advance(100)
        assertFalse(EnforcementStatus.blocking)
        service.presentationSucceeds = true
        advance(250)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `transient foreground read failure recovers and clears the reported error`() = withService { service ->
        service.foreground = "com.instagram.android"
        service.failRead = true
        service.onServiceConnected()
        advance(100)
        assertNotNull(EnforcementStatus.error)
        assertFalse(EnforcementStatus.blocking)
        service.failRead = false
        advance(250)
        assertTrue(EnforcementStatus.blocking)
        assertNull(EnforcementStatus.error)
    }

    @Test fun `unresolved foreground gets bounded retries then stops idle polling`() = withService { service ->
        service.onServiceConnected()
        advance(10_000)
        val readsAfterRetries = service.reads
        assertTrue("Allow multiple recovery attempts", readsAfterRetries >= 3)
        assertTrue("Recovery must remain bounded", readsAfterRetries <= 8)
        advance(10_000)
        assertEquals(readsAfterRetries, service.reads)
    }

    @Test fun `unbinding cancels pending recovery callbacks`() = withService { service ->
        service.onServiceConnected()
        service.onUnbind(Intent())
        val readsAtUnbind = service.reads
        service.foreground = "com.instagram.android"
        advance(2_000)
        assertEquals(readsAtUnbind, service.reads)
        assertEquals(0, service.shows)
        assertFalse(EnforcementStatus.connected)
    }

    @Test fun `foreground polling removes block after navigation even if no event arrives`() = withService { service ->
        service.foreground = "com.instagram.android"
        service.onServiceConnected()
        service.foreground = "com.android.settings"
        advance(350)
        assertEquals(1, service.hides)
        assertFalse(EnforcementStatus.blocking)
        val readsAfterNavigation = service.reads
        advance(2_000)
        assertEquals(readsAfterNavigation, service.reads)
    }

    @Test fun `interrupting feedback does not disable enforcement`() = withService { service ->
        service.foreground = "com.instagram.android"
        service.onServiceConnected()
        service.onInterrupt()
        assertTrue(EnforcementStatus.connected)
        assertTrue(EnforcementStatus.blocking)
        assertEquals(0, service.hides)
    }

    private fun advance(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    }

    private fun withService(test: (Service) -> Unit) {
        val controller = Robolectric.buildService(Service::class.java).create()
        try { test(controller.get()) } finally { controller.destroy() }
    }

    @Test fun `native events enforce without a React Native context`() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get()
        try {
            service.onServiceConnected()
            assertTrue(EnforcementStatus.connected)
            service.foreground = "com.instagram.android"
            repeat(3) {
                service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
            }
            assertEquals(1, service.shows)
            assertTrue(EnforcementStatus.blocking)
            service.foreground = "com.android.settings"
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            assertEquals(1, service.hides)
            assertFalse(EnforcementStatus.blocking)
            service.foreground = "com.instagram.android"
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            assertEquals(2, service.shows)
            service.onUnbind(Intent())
            assertFalse(EnforcementStatus.connected)
            assertEquals(2, service.hides)
        } finally { controller.destroy() }
    }

    @Test fun `service connection evaluates an already foreground Instagram`() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get()
        try {
            service.foreground = "com.instagram.android"
            service.onServiceConnected()
            assertEquals(1, service.shows)
        } finally { controller.destroy() }
        assertFalse(EnforcementStatus.connected)
        assertFalse(EnforcementStatus.blocking)
    }
}
