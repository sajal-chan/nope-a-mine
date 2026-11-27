package com.morningblocker.scheduling

import android.app.Application
import android.provider.Settings
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidSessionClockTest {
    @Test fun `reads current wall time and supplied zone`() {
        val instant = Instant.parse("2026-11-02T01:30:00Z")
        val reading = AndroidSessionClock(Clock.fixed(instant, ZoneOffset.UTC)) { ZoneId.of("Asia/Kolkata") }.read()
        assertEquals(instant, reading.instant)
        assertEquals(ZoneId.of("Asia/Kolkata"), reading.zone)
    }

    @Test fun `zone is reread on each sample rather than cached`() {
        var zone = ZoneId.of("Asia/Kolkata")
        val clock = AndroidSessionClock(Clock.systemUTC()) { zone }
        assertEquals(zone, clock.read().zone)
        zone = ZoneId.of("Europe/London")
        assertEquals(zone, clock.read().zone)
    }

    @Test fun `morning clock can be read without boot identity`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertNotNull(AndroidSessionClock().read())
    }
}
