package com.morningblocker.storage

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.scheduling.MorningSchedule
import java.io.File
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class PolicyConfigurationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private fun open(file: File = File(temporary.root, "configuration.preferences_pb")): Pair<DataStore<Preferences>, Job> {
        val job = SupervisorJob().also(jobs::add)
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }) to job
    }
    @After fun close() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun at(local: String): ClockReading {
        val zone = ZoneId.of("Asia/Kolkata")
        return ClockReading(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)
    }
    private val before = at("2026-11-02T05:00")
    private val during = at("2026-11-02T07:00")
    private fun policy(app: String = "com.instagram.android", end: String = "09:00") = BlockingPolicy(
        MorningSchedule(LocalTime.of(6, 0), LocalTime.parse(end), DayOfWeek.values().toSet()), setOf(app),
    )
    private suspend inline fun <reified T : Throwable> failure(block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `unconfigured read distinguishes absent policy from an active session`() = runBlocking<Unit> {
        val view = PolicyRepository(open().first).configurationAt(during)
        assertNull(view.policy)
        assertNull(view.activeSession)
        assertNull(view.effectiveFrom)
        assertFalse(view.changesDeferred)
    }
    @Test fun `save before start returns the committed configuration and effective date`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        val view = repository.saveConfiguration(policy(), before)
        assertEquals(setOf("com.instagram.android"), view.policy!!.blockedPackages)
        assertEquals(LocalDate.parse("2026-11-02"), view.effectiveFrom)
        assertNull(view.activeSession)
        assertFalse(view.changesDeferred)
        assertEquals(view.policy!!.blockedPackages, repository.load()!!.blockedPackages)
    }
    @Test fun `first save inside the window atomically captures the new active policy`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        val view = repository.saveConfiguration(policy(), during)
        assertNotNull(view.activeSession)
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), view.activeSession!!.window.endsAt)
        assertFalse(view.changesDeferred)
        val edit = repository.saveConfiguration(policy("com.reddit.frontpage", "08:00"), during)
        assertEquals(setOf("com.instagram.android"), edit.activeSession!!.policy.blockedPackages)
    }
    @Test fun `a read captures the original session before a future effective edit`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        repository.save(policy(), before)
        assertNotNull(repository.configurationAt(during).activeSession)
        val view = repository.saveConfiguration(policy("com.reddit.frontpage", "08:00"), during)
        assertEquals(setOf("com.reddit.frontpage"), view.policy!!.blockedPackages)
        assertEquals(setOf("com.instagram.android"), view.activeSession!!.policy.blockedPackages)
        assertEquals(LocalTime.of(9, 0), view.activeSession!!.policy.schedule.endTime)
        assertEquals(LocalDate.parse("2026-11-03"), view.effectiveFrom)
        assertTrue(view.changesDeferred)
    }
    @Test fun `after session end edits remain deferred despite no currently active session`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        repository.save(policy(), before)
        val view = repository.saveConfiguration(policy("com.reddit.frontpage"), at("2026-11-02T10:00"))
        assertNull(view.activeSession)
        assertTrue(view.changesDeferred)
        assertEquals(LocalDate.parse("2026-11-03"), view.effectiveFrom)
    }
    @Test fun `a later day reports the newly effective selection and clears deferral`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        repository.save(policy(), before)
        repository.saveConfiguration(policy("com.reddit.frontpage"), during)
        val view = repository.configurationAt(at("2026-11-03T07:00"))
        assertEquals(setOf("com.reddit.frontpage"), view.activeSession!!.policy.blockedPackages)
        assertFalse(view.changesDeferred)
    }
    @Test fun `file reopening retains editable policy and its distinct frozen session`() = runBlocking<Unit> {
        val file = File(temporary.root, "reopen.preferences_pb")
        val first = open(file)
        val repository = PolicyRepository(first.first)
        repository.save(policy(), before)
        repository.saveConfiguration(policy("com.reddit.frontpage"), during)
        first.second.cancelAndJoin()
        val view = PolicyRepository(open(file).first).configurationAt(during)
        assertEquals(setOf("com.reddit.frontpage"), view.policy!!.blockedPackages)
        assertEquals(setOf("com.instagram.android"), view.activeSession!!.policy.blockedPackages)
        assertTrue(view.changesDeferred)
    }
    @Test fun `disabled selections save without inventing an active session`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        val disabled = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), emptySet()), emptySet())
        val view = repository.saveConfiguration(disabled, during)
        assertTrue(view.policy!!.blockedPackages.isEmpty())
        assertTrue(view.policy!!.schedule.activeDays.isEmpty())
        assertNull(view.activeSession)
    }
    @Test fun `concurrent saves each return their own committed view without mixing policies`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().first)
        val views = (1..6).map { index -> async { repository.saveConfiguration(policy("org.example.app$index", LocalTime.of(index + 6, 0).toString()), before) } }.awaitAll()
        views.forEachIndexed { index, view ->
            assertEquals(setOf("org.example.app${index + 1}"), view.policy!!.blockedPackages)
            assertEquals(LocalTime.of(index + 7, 0), view.policy!!.schedule.endTime)
        }
    }
    @Test fun `invalid persisted session is not replaced by configuration reads or saves`() = runBlocking<Unit> {
        val raw = open().first
        val repository = PolicyRepository(raw)
        repository.save(policy(), before)
        raw.edit { it[stringPreferencesKey("session_date")] = "not-a-date" }
        val original = raw.data.first().asMap()
        failure<CorruptionException> { repository.configurationAt(during) }
        failure<CorruptionException> { repository.saveConfiguration(policy(), during) }
        assertEquals(original, raw.data.first().asMap())
    }
    @Test fun `IO failures are surfaced for both operations`() = runBlocking<Unit> {
        val exception = IOException("Disk unavailable")
        val raw = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw exception }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw exception
        }
        val repository = PolicyRepository(raw)
        assertSame(exception, failure<IOException> { repository.configurationAt(during) })
        assertSame(exception, failure<IOException> { repository.saveConfiguration(policy(), during) })
    }
}
