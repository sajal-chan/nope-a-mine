package com.morningblocker.blocking

import android.app.Application
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppMonitorServiceTest {
    class Service : AppMonitorService() {
        var foreground: String? = null
        var shows = 0
        var hides = 0
        override fun readForegroundPackage(): String? = foreground
        override fun createSurface(): BlockingSurface = object : BlockingSurface {
            override fun show(): Boolean { shows++; return true }
            override fun hide() { hides++ }
        }
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
