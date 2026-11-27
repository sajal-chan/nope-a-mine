package com.morningblocker.emergency

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EmergencyStoreTest {
    @Test fun `repositories from context wrappers share the same application store`() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        var now = EmergencyContext("2026-11-02", OverrideClock(100_000, 7), 7_200_000)
        val first = emergencyRepository(ContextWrapper(app)) { now }
        val second = emergencyRepository(app) { now }
        first.request()
        assertEquals(160_000L, second.status().readyAtElapsedMillis)
        now = now.copy(clock = OverrideClock(160_000, 7))
        second.confirm()
        assertTrue(first.status().used)
    }
}
