package com.morningblocker.blocking

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class BlockingModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName() = "Blocking"

    @ReactMethod
    fun getStatus(promise: Promise) {
        try {
            val resolver = reactApplicationContext.contentResolver
            val component = ComponentName(reactApplicationContext, AppMonitorService::class.java)
            val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
            promise.resolve(Arguments.createMap().apply {
                putBoolean("enabled", enabled)
                putBoolean("connected", enabled && EnforcementStatus.connected)
                putBoolean("blocking", enabled && EnforcementStatus.connected && EnforcementStatus.blocking)
                putString("error", EnforcementStatus.error)
            })
        } catch (error: RuntimeException) {
            promise.reject("STATUS_UNAVAILABLE", "Cannot read native enforcement status", error)
        }
    }

    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        try {
            reactApplicationContext.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject("SETTINGS_UNAVAILABLE", "Cannot open Android accessibility settings", error)
        }
    }
}
