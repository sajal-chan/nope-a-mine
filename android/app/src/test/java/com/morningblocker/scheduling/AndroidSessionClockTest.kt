package com.morningblocker.scheduling

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
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
    @Test fun `reads Android elapsed clock and boot count with an injectable wall clock`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val instant = Instant.parse("2026-11-02T01:30:00Z")
        val reading = AndroidSessionClock(app, Clock.fixed(instant, ZoneOffset.UTC)) { ZoneId.of("Asia/Kolkata") }.read()
        assertEquals(instant, reading.instant)
        assertEquals(SystemClock.elapsedRealtime(), reading.elapsedRealtimeMillis)
        assertEquals(12, reading.bootCount)
        assertEquals(ZoneId.of("Asia/Kolkata"), reading.zone)
    }

    @Test fun `zone is reread on each sample rather than cached`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        var zone = ZoneId.of("Asia/Kolkata")
        val clock = AndroidSessionClock(app, Clock.systemUTC()) { zone }
        assertEquals(zone, clock.read().zone)
        zone = ZoneId.of("Europe/London")
        assertEquals(zone, clock.read().zone)
    }

    @Test fun `missing boot identity is an error instead of pretending to share a boot`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertThrows(IOException::class.java) { AndroidSessionClock(app).read() }
    }
}
