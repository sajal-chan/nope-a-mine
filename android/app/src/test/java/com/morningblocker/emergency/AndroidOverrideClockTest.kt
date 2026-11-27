package com.morningblocker.emergency

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidOverrideClockTest {
    @Test fun `reads actual Android elapsed realtime and boot count`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val reading = AndroidOverrideClock(app).read()
        assertEquals(12, reading.bootCount)
        assertEquals(SystemClock.elapsedRealtime(), reading.elapsedRealtimeMillis)
    }

    @Test fun `clock advances without resetting when reread`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val clock = AndroidOverrideClock(app)
        val first = clock.read()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        val second = clock.read()
        assertEquals(first.elapsedRealtimeMillis + 30_000, second.elapsedRealtimeMillis)
        assertEquals(first.bootCount, second.bootCount)
    }

    @Test fun `boot identity is reread rather than cached`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val clock = AndroidOverrideClock(app)
        assertEquals(12, clock.read().bootCount)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 13)
        assertEquals(13, clock.read().bootCount)
    }

    @Test fun `missing boot identity is an IO error rather than a guessed boot`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertThrows(IOException::class.java) { AndroidOverrideClock(app).read() }
    }

    @Test fun `negative boot identity is rejected`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertThrows(IOException::class.java) { AndroidOverrideClock(app).read() }
    }
}
