package com.morningblocker.blocking

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.scheduling.ScheduleRules
import com.morningblocker.storage.BlockingPolicy
import com.morningblocker.storage.policyRepository
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduledAppMonitorServiceTest {
    class Service : AppMonitorService() {
        var foreground: String? = "com.instagram.android"
        @Volatile var now = reading("2026-11-02T07:00")
        var configured = true
        var selected = setOf("com.instagram.android")
        var shows = 0
        var hides = 0
        var reads = 0
        var evaluations = 0
        @Volatile var completed = 0
        @Volatile var policyOnMain = true
        @Volatile var lastDecision: RestrictionDecision? = null
        var cancelled = 0
        var failRead = false
        var churn = false
        var clockFails = false
        var policyFailure: Exception? = null
        var presentationSucceeds = true
        var useRealPolicy = false
        var hold: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var displayed: RestrictionDecision.Blocked? = null

        override fun readClock(): ClockReading {
            if (clockFails) throw IllegalStateException("Clock unavailable")
            return now
        }
        override fun policyDispatcher(): CoroutineDispatcher = if (useRealPolicy) super.policyDispatcher() else Dispatchers.Unconfined
        override fun readForegroundPackage(): String? {
            reads++
            if (failRead) throw IllegalStateException("Window snapshot temporarily unavailable")
            // Finite churn keeps a broken zero-delay implementation from hanging the test.
            if (churn && reads <= 40) return if (reads % 2 == 1) "com.instagram.android" else "com.google.android.youtube"
            return foreground
        }
        override suspend fun evaluateRestriction(packageName: String?): RestrictionDecision {
            policyOnMain = Looper.myLooper() == Looper.getMainLooper()
            evaluations++
            try {
                val decision = if (useRealPolicy) super.evaluateRestriction(packageName) else fixtureDecision(packageName)
                val wait = hold
                if (ignoreCancellation) withContext(NonCancellable) { wait?.await() } else wait?.await()
                policyFailure?.let { throw it }
                lastDecision = decision
                return decision
            } catch (error: CancellationException) {
                cancelled++
                throw error
            } finally { completed++ }
        }
        private fun fixtureDecision(packageName: String?): RestrictionDecision {
            val local = readClock().instant.atZone(now.zone).toLocalDateTime()
            val policy = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), DayOfWeek.values().toSet()), selected)
            if (!configured || selected.isEmpty() || ScheduleRules.activeSession(policy.schedule, local) == null) return RestrictionDecision.Inactive
            val session = RestrictionSession(local.toLocalDate(), policy)
            return when {
                packageName.isNullOrBlank() -> RestrictionDecision.UnknownForeground(session)
                packageName in selected && packageName != "com.android.settings" && packageName != "com.morningblocker" -> RestrictionDecision.Blocked(packageName, session)
                else -> RestrictionDecision.Allowed(session)
            }
        }
        override fun createSurface(): BlockingSurface = object : BlockingSurface {
            override fun show(block: RestrictionDecision.Blocked): Boolean {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Overlay must be presented on main" }
                displayed = block
                shows++
                return presentationSucceeds
            }
            override fun hide() { hides++ }
        }
    }

    @Test fun `service uses selected YouTube rather than a hardcoded Instagram gate`() = withService { service ->
        service.selected = setOf("com.google.android.youtube")
        service.onServiceConnected()
        assertFalse(EnforcementStatus.blocking)
        service.foreground = "com.google.android.youtube"
        event(service)
        assertTrue(EnforcementStatus.blocking)
        assertEquals("com.google.android.youtube", service.displayed!!.packageName)
    }

    @Test fun `unconfigured service does not block Instagram`() = withService { service ->
        service.configured = false
        service.onServiceConnected()
        assertFalse(EnforcementStatus.blocking)
        assertEquals(0, service.shows)
    }

    @Test fun `schedule starts while selected app remains foreground without any event or React context`() = withService { service ->
        service.now = reading("2026-11-02T05:59:59")
        service.onServiceConnected()
        assertFalse(EnforcementStatus.blocking)
        service.now = reading("2026-11-02T06:00")
        advance(1_000)
        assertTrue(EnforcementStatus.blocking)
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), service.displayed!!.session.window.endsAt)
    }

    @Test fun `exact end releases an already foreground app without any event`() = withService { service ->
        service.now = reading("2026-11-02T08:59:59")
        service.onServiceConnected()
        assertTrue(EnforcementStatus.blocking)
        service.now = reading("2026-11-02T09:00")
        advance(250)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
    }

    @Test fun `clock exit and reentry reevaluate without new window events`() = withService { service ->
        service.onServiceConnected()
        service.now = reading("2026-11-02T10:00")
        advance(1_000)
        assertFalse(EnforcementStatus.blocking)
        service.now = reading("2026-11-02T08:00")
        advance(1_000)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `timezone changes use fresh local hours`() = withService { service ->
        service.onServiceConnected()
        val original = service.now
        service.now = original.copy(zone = ZoneId.of("UTC"))
        advance(1_000)
        assertFalse(EnforcementStatus.blocking)
        service.now = original
        advance(1_000)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `unknown snapshots preserve an active block but not beyond the session end`() = withService { service ->
        service.onServiceConnected()
        service.foreground = null
        advance(250)
        assertTrue(EnforcementStatus.blocking)
        service.now = reading("2026-11-02T09:00")
        advance(1_000)
        assertFalse(EnforcementStatus.blocking)
    }

    @Test fun `foreground read exceptions cannot keep an overlay beyond its known end`() = withService { service ->
        service.onServiceConnected()
        service.failRead = true
        service.now = reading("2026-11-02T09:00")
        advance(1_000)
        assertFalse(EnforcementStatus.blocking)
        assertNotNull(EnforcementStatus.error)
    }

    @Test fun `late snapshot recovers without another accessibility event`() = withService { service ->
        service.foreground = null
        service.onServiceConnected()
        advance(100)
        assertEquals(0, service.shows)
        service.foreground = "com.instagram.android"
        advance(250)
        assertEquals(1, service.shows)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `transient presentation failure retries without another event`() = withService { service ->
        service.presentationSucceeds = false
        service.onServiceConnected()
        advance(100)
        assertFalse(EnforcementStatus.blocking)
        service.presentationSucceeds = true
        advance(250)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `foreground failure recovers and clears only its own error`() = withService { service ->
        service.failRead = true
        service.onServiceConnected()
        advance(100)
        assertNotNull(EnforcementStatus.error)
        service.failRead = false
        advance(250)
        assertTrue(EnforcementStatus.blocking)
        assertNull(EnforcementStatus.error)
        EnforcementStatus.error = "Home action unavailable"
        advance(250)
        assertEquals("Home action unavailable", EnforcementStatus.error)
    }

    @Test fun `storage failure reports unavailable and recovers without another event`() = withService { service ->
        service.policyFailure = IOException("Store unavailable")
        service.onServiceConnected()
        assertFalse(EnforcementStatus.blocking)
        assertTrue(EnforcementStatus.error.orEmpty().contains("policy", ignoreCase = true))
        service.policyFailure = null
        advance(1_000)
        assertTrue(EnforcementStatus.blocking)
        assertNull(EnforcementStatus.error)
    }

    @Test fun `clock failure is reported and a stale overlay is released`() = withService { service ->
        service.onServiceConnected()
        service.clockFails = true
        advance(1_000)
        assertFalse(EnforcementStatus.blocking)
        assertNotNull(EnforcementStatus.error)
        service.clockFails = false
        advance(1_000)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `idle and unresolved monitoring retain a bounded cadence for schedule changes`() = withService { service ->
        service.foreground = null
        service.onServiceConnected()
        advance(10_000)
        val reads = service.reads
        assertTrue(reads in 10..60)
        advance(2_000)
        assertTrue(service.reads > reads)
        assertTrue(service.reads - reads <= 8)
    }

    @Test fun `navigation removes block without an event while schedule monitoring continues`() = withService { service ->
        service.onServiceConnected()
        service.foreground = "com.android.settings"
        advance(250)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
        val reads = service.reads
        advance(2_000)
        assertTrue(service.reads > reads)
    }

    @Test fun `slow policy work does not overlap or block accessibility callbacks`() = withService { service ->
        val wait = CompletableDeferred<Unit>()
        service.hold = wait
        service.onServiceConnected()
        repeat(20) { event(service) }
        advance(1_000)
        assertEquals(1, service.evaluations)
        assertFalse(EnforcementStatus.blocking)
        service.hold = null
        wait.complete(Unit)
        advance(0)
        assertTrue(EnforcementStatus.blocking)
    }

    @Test fun `navigation during a slow read discards the obsolete block even without an event`() = withService { service ->
        val wait = CompletableDeferred<Unit>()
        service.hold = wait
        service.onServiceConnected()
        service.foreground = "com.android.settings"
        service.hold = null
        wait.complete(Unit)
        advance(1_000)
        assertEquals(0, service.shows)
        assertFalse(EnforcementStatus.blocking)
    }

    @Test fun `an obsolete policy response cannot show a block after its end`() = withService { service ->
        val wait = CompletableDeferred<Unit>()
        service.hold = wait
        service.onServiceConnected()
        service.now = reading("2026-11-02T09:00")
        service.hold = null
        wait.complete(Unit)
        advance(1_000)
        assertEquals(0, service.shows)
        assertFalse(EnforcementStatus.blocking)
    }

    @Test fun `visible block expires even while a subsequent policy read is suspended`() = withService { service ->
        service.onServiceConnected()
        service.hold = CompletableDeferred()
        event(service)
        service.now = reading("2026-11-02T09:00")
        advance(250)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
    }

    @Test fun `settings stays usable even while policy work is suspended`() = withService { service ->
        service.onServiceConnected()
        service.hold = CompletableDeferred()
        event(service)
        service.foreground = "com.android.settings"
        event(service)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
    }

    @Test fun `unrelated apps are uncovered immediately while policy work is suspended`() = withService { service ->
        service.onServiceConnected()
        service.hold = CompletableDeferred()
        event(service)
        service.foreground = "org.example.maps"
        event(service)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
    }

    @Test fun `unstable foreground snapshots use backoff rather than a busy retry loop`() = withService { service ->
        service.churn = true
        service.onServiceConnected()
        advance(1_000)
        assertTrue("Foreground churn must not cause an immediate retry loop: ${service.reads} reads", service.reads <= 16)
        assertEquals(0, service.shows)
    }

    @Test fun `unbinding cancels policy work and all periodic callbacks`() = withService { service ->
        val wait = CompletableDeferred<Unit>()
        service.hold = wait
        service.onServiceConnected()
        service.onUnbind(Intent())
        val reads = service.reads
        wait.complete(Unit)
        advance(10_000)
        assertEquals(1, service.cancelled)
        assertEquals(reads, service.reads)
        assertEquals(0, service.shows)
        assertFalse(EnforcementStatus.connected)
    }

    @Test fun `noncooperative old results cannot affect a new service connection`() = withService { service ->
        val wait = CompletableDeferred<Unit>()
        service.hold = wait
        service.ignoreCancellation = true
        service.onServiceConnected()
        service.configured = false
        service.hold = null
        service.ignoreCancellation = false
        service.onServiceConnected()
        wait.complete(Unit)
        advance(1_000)
        assertEquals(0, service.shows)
        assertTrue(EnforcementStatus.connected)
        assertFalse(EnforcementStatus.blocking)
        assertNull(EnforcementStatus.error)
    }

    @Test fun `interrupting feedback does not disable scheduled enforcement`() = withService { service ->
        service.onServiceConnected()
        service.onInterrupt()
        assertTrue(EnforcementStatus.connected)
        assertTrue(EnforcementStatus.blocking)
        assertEquals(0, service.hides)
    }

    @Test fun `window events update selected app metadata without duplicate overlays`() = withService { service ->
        service.selected = setOf("com.instagram.android", "com.google.android.youtube")
        service.onServiceConnected()
        repeat(3) { event(service) }
        assertEquals(1, service.shows)
        service.foreground = "com.google.android.youtube"
        event(service)
        assertEquals("com.google.android.youtube", service.displayed!!.packageName)
        assertEquals(0, service.hides)
        service.onUnbind(Intent())
        assertFalse(EnforcementStatus.connected)
        assertFalse(EnforcementStatus.blocking)
        assertEquals(1, service.hides)
    }

    @Test fun `production evaluator reads persisted frozen policy and pending edits`() = withService { service ->
        service.useRealPolicy = true
        service.foreground = "com.google.android.youtube"
        val repository = policyRepository(service)
        val schedule = MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), DayOfWeek.values().toSet())
        runBlocking { repository.save(BlockingPolicy(schedule, setOf("com.google.android.youtube")), reading("2026-11-02T05:00")) }
        service.onServiceConnected()
        awaitResult { service.completed >= 1 && EnforcementStatus.blocking }
        assertTrue(EnforcementStatus.blocking)
        assertFalse("Persisted policy must be read off main", service.policyOnMain)
        assertEquals("com.google.android.youtube", service.displayed!!.packageName)
        runBlocking { repository.save(BlockingPolicy(schedule, setOf("com.reddit.frontpage")), service.now) }
        service.now = reading("2026-11-02T08:30")
        val completed = service.completed
        event(service)
        awaitResult { service.completed > completed }
        assertTrue(EnforcementStatus.blocking)
        assertEquals(setOf("com.google.android.youtube"), (service.lastDecision as RestrictionDecision.Blocked).session.policy.blockedPackages)
        assertEquals("com.google.android.youtube", service.displayed!!.packageName)
    }

    @Test fun `production evaluator has no continuous fallback when configuration is absent`() = withService { service ->
        service.useRealPolicy = true
        service.onServiceConnected()
        awaitResult { service.completed >= 1 }
        assertEquals(0, service.shows)
        assertFalse("Persisted policy must be read off main", service.policyOnMain)
        assertFalse(EnforcementStatus.blocking)
        assertNull(EnforcementStatus.error)
    }

    private fun event(service: Service) {
        @Suppress("DEPRECATION")
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
    }
    private fun advance(milliseconds: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)) }
    private fun awaitResult(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) { advance(0); Thread.sleep(10) }
        advance(0)
        assertTrue("Native evaluation did not complete", done())
    }
    private fun withService(test: (Service) -> Unit) {
        val controller = Robolectric.buildService(Service::class.java).create()
        try { test(controller.get()) } finally { controller.destroy() }
    }
    companion object {
        private fun reading(local: String): ClockReading {
            val zone = ZoneId.of("Asia/Kolkata")
            return ClockReading(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)
        }
    }
}
