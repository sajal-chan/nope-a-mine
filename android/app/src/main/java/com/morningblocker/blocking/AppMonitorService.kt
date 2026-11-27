package com.morningblocker.blocking

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.morningblocker.scheduling.AndroidSessionClock
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.storage.policyRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

open class AppMonitorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    // Keep window reads, status changes and presentation on the main looper without requiring
    // a JavaScript runtime or a separate coroutines-android dependency.
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
    }
    private val engine by lazy { RestrictionEngine(policyRepository(this), ::readClock) }
    private var scope: CoroutineScope? = null
    private var evaluationJob: Job? = null
    private var generation = 0
    private var gate: ForegroundGate? = null
    private var unresolved = false
    private val recoveryDelays = longArrayOf(100, 250, 500, 1_000)
    private var nextRecoveryDelay = 0
    private var serviceError: String? = null
    private var foregroundFailure: String? = null
    private val refresh = Runnable { refreshForeground() }

    protected open fun readClock(): ClockReading = AndroidSessionClock().read()
    protected open fun policyDispatcher(): CoroutineDispatcher = Dispatchers.IO
    protected open suspend fun evaluateRestriction(packageName: String?): RestrictionDecision = engine.evaluate(packageName)

    public override fun onServiceConnected() {
        super.onServiceConnected()
        cleanUp()
        gate = ForegroundGate(createSurface())
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        unresolved = false
        nextRecoveryDelay = 0
        foregroundFailure = null
        serviceError = null
        EnforcementStatus.connected = true
        EnforcementStatus.error = null
        Log.i("MorningBlocker", "Accessibility service connected")
        refreshForeground()
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
        nextRecoveryDelay = 0
        refreshForeground()
    }

    private fun refreshForeground() {
        val current = gate ?: return
        val serviceScope = scope ?: return
        releaseExpiredBlock(current)
        val observed = observeForeground(current)
        // A slow DataStore operation must neither block Android callbacks nor overlap another
        // policy transaction. The periodic callback still checks navigation/expiry while it waits.
        if (evaluationJob?.isActive == true) {
            scheduleRefresh()
            return
        }
        val token = generation
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val decision = withContext(policyDispatcher()) { evaluateRestriction(observed) }
                if (token != generation || gate !== current) return@launch
                releaseExpiredBlock(current)
                val latest = observeForeground(current)
                if (latest != observed || !decisionIsCurrent(decision)) {
                    // Never put a late overlay on the app the user navigated to, or show a
                    // response whose session expired while persistence was suspended.
                    unresolved = true
                    return@launch
                }
                if (foregroundFailure == null) clearServiceError()
                unresolved = !current.applyDecision(decision)
                EnforcementStatus.blocking = current.isBlocking
                if (foregroundFailure != null) reportError(foregroundFailure!!)
            } catch (error: CancellationException) {
                if (token == generation && gate === current) {
                    current.close()
                    EnforcementStatus.blocking = false
                    unresolved = true
                    reportError("Blocking policy evaluation cancelled")
                }
                throw error
            } catch (error: Exception) {
                if (token == generation && gate === current) {
                    current.close()
                    EnforcementStatus.blocking = false
                    unresolved = true
                    reportError("Blocking policy unavailable: ${error.javaClass.simpleName}")
                    Log.e("MorningBlocker", "Policy evaluation failed", error)
                }
            } finally {
                if (token == generation && gate === current) {
                    evaluationJob = null
                    scheduleRefresh()
                }
            }
        }
        evaluationJob = job
        job.start()
        if (job.isActive) scheduleRefresh()
    }

    private fun observeForeground(current: ForegroundGate): String? = try {
        val observed = readForegroundPackage()
        foregroundFailure = null
        // The visible session already tells us which apps it can cover. Do not leave its
        // overlay on an unrelated app while persistence is pending. Unknown windows retain it.
        val shown = current.displayedBlock
        val unrelated = !observed.isNullOrBlank() && shown != null &&
            observed !in shown.session.policy.blockedPackages
        if (observed == "com.android.settings" || observed == packageName || unrelated) {
            current.close()
            EnforcementStatus.blocking = false
        }
        observed
    } catch (error: RuntimeException) {
        foregroundFailure = "Foreground detection unavailable: ${error.javaClass.simpleName}"
        reportError(foregroundFailure!!)
        Log.e("MorningBlocker", "Foreground detection failed", error)
        null
    }

    private fun sessionIsCurrent(session: RestrictionSession): Boolean {
        val reading = readClock()
        val local = reading.instant.atZone(reading.zone).toLocalDateTime()
        return local.toLocalDate() == session.date &&
            !local.isBefore(session.window.startsAt) && local.isBefore(session.window.endsAt)
    }

    private fun decisionIsCurrent(decision: RestrictionDecision): Boolean = when (decision) {
        RestrictionDecision.Inactive -> true
        is RestrictionDecision.Allowed -> sessionIsCurrent(decision.session)
        is RestrictionDecision.Blocked -> sessionIsCurrent(decision.session)
        is RestrictionDecision.UnknownForeground -> sessionIsCurrent(decision.session)
    }

    private fun releaseExpiredBlock(current: ForegroundGate) {
        val block = current.displayedBlock ?: return
        try {
            if (!sessionIsCurrent(block.session)) {
                current.close()
                EnforcementStatus.blocking = false
            }
        } catch (error: RuntimeException) {
            current.close()
            EnforcementStatus.blocking = false
            reportError("Native clock unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun reportError(message: String) {
        serviceError = message
        EnforcementStatus.error = message
    }

    private fun clearServiceError() {
        // Do not erase independent Home/settings/presentation errors on a healthy policy read.
        if (EnforcementStatus.error == serviceError) EnforcementStatus.error = null
        serviceError = null
    }

    private fun scheduleRefresh() {
        if (gate == null) return
        handler.removeCallbacks(refresh)
        val next = when {
            gate?.isBlocking == true -> 250L
            unresolved && nextRecoveryDelay < recoveryDelays.size -> recoveryDelays[nextRecoveryDelay++]
            else -> 1_000L
        }
        // Handler delays use uptime: they do not wake the phone from deep sleep. Each callback
        // re-reads wall time/zone; no elapsed-duration checkpoint authorizes morning access.
        handler.postDelayed(refresh, next)
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
        generation++
        scope?.cancel()
        scope = null
        evaluationJob = null
        // Coroutine cancellation continuations also use this handler and must be allowed to
        // finish. Cancel only our timer; generation checks reject late policy results.
        handler.removeCallbacks(refresh)
        gate?.close()
        gate = null
        EnforcementStatus.blocking = false
        EnforcementStatus.connected = false
    }
}
