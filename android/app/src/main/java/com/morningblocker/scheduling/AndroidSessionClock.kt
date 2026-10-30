package com.morningblocker.scheduling

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.time.Clock
import java.time.ZoneId

class AndroidSessionClock(
    context: Context,
    private val wallClock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val applicationContext = context.applicationContext

    fun read(): ClockReading {
        val bootCount = try {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (error: Settings.SettingNotFoundException) {
            throw IOException("Cannot determine device boot identity", error)
        } catch (error: SecurityException) {
            throw IOException("Cannot read device boot identity", error)
        }
        if (bootCount < 0) throw IOException("Invalid device boot identity")
        return ClockReading(wallClock.instant(), SystemClock.elapsedRealtime(), bootCount, zone())
    }
}
