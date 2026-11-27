package com.morningblocker.emergency

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException

class AndroidOverrideClock(context: Context) {
    private val applicationContext = context.applicationContext

    fun read(): OverrideClock {
        val bootCount = try {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (error: Settings.SettingNotFoundException) {
            throw IOException("Cannot determine boot identity for emergency access", error)
        } catch (error: SecurityException) {
            throw IOException("Cannot read boot identity for emergency access", error)
        }
        if (bootCount < 0) throw IOException("Invalid boot identity for emergency access")
        return OverrideClock(SystemClock.elapsedRealtime(), bootCount)
    }
}
