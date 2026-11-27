package com.morningblocker.blocking

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.storage.BlockingPolicy
import com.morningblocker.storage.PolicyRepository
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RestrictionEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private data class OpenStore(val data: DataStore<Preferences>, val job: Job) {
        val repository = PolicyRepository(data)
    }
    private fun open(file: File = File(temporary.root, "engine.preferences_pb")): OpenStore {
        val job = SupervisorJob().also(jobs::add)
        return OpenStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        ), job)
    }
    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun at(local: String, zone: String = "Asia/Kolkata"): ClockReading {
        val zoneId = ZoneId.of(zone)
        return ClockReading(LocalDateTime.parse(local).atZone(zoneId).toInstant(), zoneId)
    }
    private val before = at("2026-11-02T05:00")
    private var now = at("2026-11-02T07:00")
    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val reddit = "com.reddit.frontpage"
    private fun policy(
        packages: Set<String> = setOf(instagram),
        days: Set<DayOfWeek> = DayOfWeek.values().toSet(),
        end: String = "09:00",
    ) = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.parse(end), days), packages)
    private fun engine(repository: PolicyRepository) = RestrictionEngine(repository) { now }
    private fun blocked(decision: RestrictionDecision, packageName: String = instagram): RestrictionDecision.Blocked {
        assertTrue("Expected block, got $decision", decision is RestrictionDecision.Blocked)
        return (decision as RestrictionDecision.Blocked).also { assertEquals(packageName, it.packageName) }
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `no configuration is inactive rather than a hardcoded Instagram block`() = runBlocking<Unit> {
        assertEquals(RestrictionDecision.Inactive, engine(open().repository).evaluate(instagram))
    }

    @Test fun `fresh native time controls exact start and end while the same app remains foreground`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        var samples = 0
        val engine = RestrictionEngine(repository) { samples++; now }
        now = at("2026-11-02T05:59:59.999")
        assertEquals(RestrictionDecision.Inactive, engine.evaluate(instagram))
        now = at("2026-11-02T06:00")
        val decision = blocked(engine.evaluate(instagram))
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), decision.session.window.endsAt)
        now = at("2026-11-02T08:59:59.999")
        blocked(engine.evaluate(instagram))
        now = at("2026-11-02T09:00")
        assertEquals(RestrictionDecision.Inactive, engine.evaluate(instagram))
        assertEquals(4, samples)
    }

    @Test fun `first configuration inside the morning window is immediately eligible`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), now)
        blocked(engine(repository).evaluate(instagram))
    }

    @Test fun `unselected weekday and empty app or weekday selections stay inactive`() = runBlocking<Unit> {
        val configurations = listOf(policy(days = setOf(DayOfWeek.TUESDAY)), policy(packages = emptySet()), policy(days = emptySet()))
        configurations.forEachIndexed { index, configuration ->
            val repository = open(File(temporary.root, "$index.preferences_pb")).repository
            repository.save(configuration, before)
            assertEquals(RestrictionDecision.Inactive, engine(repository).evaluate(instagram))
        }
    }

    @Test fun `selected supported and future packages block while unselected Instagram is allowed`() = runBlocking<Unit> {
        val repository = open().repository
        val future = "org.example.future"
        repository.save(policy(packages = setOf(youtube, reddit, future)), before)
        val engine = engine(repository)
        for (packageName in listOf(youtube, reddit, future)) blocked(engine.evaluate(packageName), packageName)
        val allowed = engine.evaluate(instagram)
        assertTrue(allowed is RestrictionDecision.Allowed)
        assertEquals(setOf(youtube, reddit, future), (allowed as RestrictionDecision.Allowed).session.policy.blockedPackages)
    }

    @Test fun `settings and the blocker remain accessible even if accidentally selected`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(packages = setOf(instagram, "com.android.settings", "com.morningblocker")), before)
        val engine = engine(repository)
        blocked(engine.evaluate(instagram))
        assertTrue(engine.evaluate("com.android.settings") is RestrictionDecision.Allowed)
        assertTrue(engine.evaluate("com.morningblocker") is RestrictionDecision.Allowed)
    }

    @Test fun `unknown foreground during an active session is unresolved rather than permission to dismiss`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        for (packageName in listOf(null, "", "  ")) {
            val decision = engine(repository).evaluate(packageName)
            assertTrue(decision is RestrictionDecision.UnknownForeground)
            assertEquals(setOf(instagram), (decision as RestrictionDecision.UnknownForeground).session.policy.blockedPackages)
        }
    }

    @Test fun `unknown foreground cannot keep an expired session active`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        val engine = engine(repository)
        blocked(engine.evaluate(instagram))
        now = at("2026-11-02T09:00")
        assertEquals(RestrictionDecision.Inactive, engine.evaluate(null))
    }

    @Test fun `pending edits cannot change selected apps or displayed end of the active session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        // Saving captures the original session even before this evaluator has run.
        repository.save(policy(packages = setOf(youtube), end = "08:00"), now)
        assertEquals(setOf(youtube), repository.load()!!.blockedPackages)
        now = at("2026-11-02T08:30")
        val engine = engine(repository)
        val original = blocked(engine.evaluate(instagram))
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), original.session.window.endsAt)
        assertTrue(engine.evaluate(youtube) is RestrictionDecision.Allowed)
    }

    @Test fun `next eligible day uses pending packages and session end`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy(packages = setOf(reddit), end = "08:00"), now)
        now = at("2026-11-03T07:00")
        val engine = engine(repository)
        val next = blocked(engine.evaluate(reddit), reddit)
        assertEquals(LocalDateTime.parse("2026-11-03T08:00"), next.session.window.endsAt)
        assertTrue(engine.evaluate(instagram) is RestrictionDecision.Allowed)
    }

    @Test fun `a reopened evaluator and actual store file retain the original effective policy`() = runBlocking<Unit> {
        val file = File(temporary.root, "reopen.preferences_pb")
        val first = open(file)
        first.repository.save(policy(), before)
        blocked(engine(first.repository).evaluate(instagram))
        first.repository.save(policy(packages = setOf(reddit)), now)
        first.job.cancelAndJoin()
        val restored = engine(open(file).repository)
        blocked(restored.evaluate(instagram))
        assertTrue(restored.evaluate(reddit) is RestrictionDecision.Allowed)
    }

    @Test fun `clock exit and rollback reentry use the frozen policy without extending the morning`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        val engine = engine(repository)
        blocked(engine.evaluate(instagram))
        repository.save(policy(packages = setOf(reddit)), now)
        now = at("2026-11-02T10:00")
        assertEquals(RestrictionDecision.Inactive, engine.evaluate(instagram))
        now = at("2026-11-02T08:00")
        blocked(engine.evaluate(instagram))
        assertTrue(engine.evaluate(reddit) is RestrictionDecision.Allowed)
    }

    @Test fun `current timezone is reevaluated rather than caching the earlier active decision`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        val engine = engine(repository)
        blocked(engine.evaluate(instagram))
        val localMorning = now
        now = now.copy(zone = ZoneId.of("UTC"))
        assertEquals(RestrictionDecision.Inactive, engine.evaluate(instagram))
        now = localMorning
        blocked(engine.evaluate(instagram))
    }

    @Test fun `IO failure is surfaced instead of pretending restrictions are inactive`() = runBlocking<Unit> {
        val failure = IOException("Store unavailable")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw failure
        }
        assertSame(failure, expectFailure<IOException> { engine(PolicyRepository(store)).evaluate(instagram) })
    }

    @Test fun `invalid saved state is surfaced and not silently repaired`() = runBlocking<Unit> {
        val opened = open()
        opened.repository.save(policy(), before)
        opened.data.edit { it[stringPreferencesKey("session_date")] = "2026-11-02" }
        expectFailure<CorruptionException> { engine(opened.repository).evaluate(instagram) }
        assertEquals("2026-11-02", opened.data.updateData { it }[stringPreferencesKey("session_date")])
    }

    @Test fun `native clock failure is surfaced without returning a cached block or allowance`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        var fails = false
        val failure = IllegalStateException("Clock unavailable")
        val engine = RestrictionEngine(repository) { if (fails) throw failure else now }
        blocked(engine.evaluate(instagram))
        fails = true
        assertSame(failure, expectFailure<IllegalStateException> { engine.evaluate(instagram) })
    }

    @Test fun `cancelled repository work is not converted into an inactive decision`() = runBlocking<Unit> {
        val failure = CancellationException("Service disconnected")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw failure
        }
        assertSame(failure, expectFailure<CancellationException> { engine(PolicyRepository(store)).evaluate(instagram) })
    }
}
