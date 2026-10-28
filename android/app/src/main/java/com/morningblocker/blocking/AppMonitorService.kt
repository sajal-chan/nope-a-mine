package com.morningblocker.blocking

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

open class AppMonitorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var gate: ForegroundGate? = null
    private val refresh = object : Runnable {
        override fun run() {
            evaluateForeground()
            // Recheck while covering an app so navigation cannot leave a stale overlay.
            if (gate?.isBlocking == true) handler.postDelayed(this, 250)
        }
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        gate?.close()
        gate = ForegroundGate(createSurface())
        EnforcementStatus.connected = true
        EnforcementStatus.error = null
        Log.i("MorningBlocker", "Accessibility service connected")
        evaluateForeground()
        scheduleRefresh()
    }

    protected open fun createSurface(): BlockingSurface = BlockController(
        this,
        { performGlobalAction(GLOBAL_ACTION_HOME) },
        {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: RuntimeException) {
                EnforcementStatus.error = "Cannot open accessibility settings. Open Android Settings manually."
                Log.e("MorningBlocker", "Cannot open accessibility settings", error)
            }
        },
    )

    /** Inspect package metadata only, never node text or children. Ignore our own overlay window. */
    protected open fun readForegroundPackage(): String? {
        val foreground = windows.firstOrNull {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.isFocused
        } ?: windows.firstOrNull {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.isActive
        }
        val root = foreground?.root ?: rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        evaluateForeground()
        // Root/window snapshots may lag the initial event.
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 100)
    }

    private fun evaluateForeground() {
        val current = gate ?: return
        try {
            current.onForegroundApp(readForegroundPackage())
            EnforcementStatus.blocking = current.isBlocking
        } catch (error: RuntimeException) {
            current.close()
            EnforcementStatus.blocking = false
            EnforcementStatus.error = "Foreground detection unavailable: ${error.javaClass.simpleName}"
            Log.e("MorningBlocker", "Foreground detection failed", error)
        }
    }

    // onInterrupt stops feedback; it does not mean Android disabled/unbound this service.
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    private fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        gate?.close()
        gate = null
        EnforcementStatus.blocking = false
        EnforcementStatus.connected = false
        Log.i("MorningBlocker", "Accessibility service disconnected")
    }
}
