package com.morningblocker.emergency

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.emergency.EmergencyPhase.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
class EmergencyRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private var now = EmergencyContext("2026-11-02", OverrideClock(100_000, 7), 7_200_000)
    private data class OpenStore(val data: DataStore<Preferences>, val job: Job)
    private fun open(file: File = File(temporary.root, "emergency.preferences_pb")): OpenStore {
        val job = SupervisorJob().also(jobs::add)
        return OpenStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }), job)
    }
    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun repository(store: OpenStore) = EmergencyRepository(store.data) { now }
    private fun time(elapsed: Long, boot: Int = now.clock.bootCount) { now = now.copy(clock = OverrideClock(elapsed, boot)) }
    private suspend fun reopened(first: OpenStore, file: File): OpenStore { first.job.cancelAndJoin(); return open(file) }
    private suspend fun granted(repo: EmergencyRepository): EmergencyState {
        assertEquals(WAITING, repo.request().phase)
        time(160_000)
        return repo.confirm().also { assertEquals(OVERRIDE_ACTIVE, it.phase); assertTrue(it.used) }
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) { if (error is T) return; throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `fresh repository starts with unused allowance`() = runBlocking<Unit> {
        val state = repository(open()).status()
        assertEquals(BLOCKING, state.phase)
        assertFalse(state.used)
    }

    @Test fun `request deadline survives real file close and reopen`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        assertEquals(160_000L, repository(first).request().readyAtElapsedMillis)
        time(130_000)
        val restored = repository(reopened(first, file)).status()
        assertEquals(WAITING, restored.phase)
        assertEquals(160_000L, restored.readyAtElapsedMillis)
    }

    @Test fun `restored elapsed wait is ready but never automatically unlocks`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        repository(first).request()
        time(160_000)
        val restored = repository(reopened(first, file)).status()
        assertEquals(READY_TO_UNLOCK, restored.phase)
        assertFalse(restored.used)
        assertNull(restored.unlockUntilElapsedMillis)
    }

    @Test fun `granted deadline survives reopening and expires exactly once`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        granted(repository(first))
        time(300_000)
        val second = repository(reopened(first, file))
        assertEquals(460_000L, second.status().unlockUntilElapsedMillis)
        time(460_000)
        val expired = second.status()
        assertEquals(BLOCKING, expired.phase)
        assertTrue(expired.used)
        expectFailure<IllegalStateException> { second.request() }
    }

    @Test fun `repeat confirmation after reopening cannot extend access`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        granted(repository(first))
        time(200_000)
        assertEquals(460_000L, repository(reopened(first, file)).confirm().unlockUntilElapsedMillis)
    }

    @Test fun `cancellation persists without consumption and retry requires a new full wait`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        val repo = repository(first)
        repo.request()
        assertEquals(WAITING, repo.status().phase)
        time(130_000)
        assertFalse(repo.cancel().used)
        time(140_000)
        val retry = repository(reopened(first, file)).request()
        assertEquals(200_000L, retry.readyAtElapsedMillis)
        assertFalse(retry.used)
    }

    @Test fun `reboot cancellation of pending wait is persisted`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        repository(first).request()
        assertEquals(WAITING, repository(first).status().phase)
        time(1_000, boot = 8)
        val second = reopened(first, file)
        val canceled = repository(second).status()
        assertEquals(BLOCKING, canceled.phase)
        assertFalse(canceled.used)
        assertNull(canceled.readyAtElapsedMillis)
        time(70_000)
        assertEquals(BLOCKING, repository(reopened(second, file)).status().phase)
    }

    @Test fun `ready request cannot be confirmed after reboot`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        repo.request()
        time(160_000)
        assertEquals(READY_TO_UNLOCK, repo.status().phase)
        time(1_000, boot = 8)
        expectFailure<IllegalStateException> { repo.confirm() }
        assertEquals(BLOCKING, repo.status().phase)
        assertFalse(repo.status().used)
    }

    @Test fun `reboot ends active access and keeps used allowance across another reopen`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        granted(repository(first))
        time(1_000, boot = 8)
        val second = reopened(first, file)
        val recovered = repository(second).status()
        assertEquals(BLOCKING, recovered.phase)
        assertTrue(recovered.used)
        assertNull(recovered.unlockUntilElapsedMillis)
        time(2_000)
        expectFailure<IllegalStateException> { repository(reopened(second, file)).request() }
    }

    @Test fun `visiting another session cannot restore an older consumed allowance`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        val repo = repository(first)
        granted(repo)
        time(200_000)
        now = now.copy(sessionId = "2026-11-03")
        assertFalse(repo.status().used)
        now = now.copy(sessionId = "2026-11-02")
        val restored = repository(reopened(first, file))
        assertTrue(restored.status().used)
        expectFailure<IllegalStateException> { restored.request() }
    }

    @Test fun `concurrent requests keep one original waiting deadline`() = runBlocking<Unit> {
        val counter = AtomicLong(100_000)
        val repo = EmergencyRepository(open().data) { now.copy(clock = OverrideClock(counter.getAndIncrement(), 7)) }
        val states = (1..12).map { async { repo.request() } }.awaitAll()
        assertTrue(states.all { it.phase == WAITING && it.readyAtElapsedMillis == 160_000L && !it.used })
    }

    @Test fun `concurrent confirmations grant only one fixed access period`() = runBlocking<Unit> {
        val counter = AtomicLong(100_000)
        val repo = EmergencyRepository(open().data) { now.copy(clock = OverrideClock(counter.getAndIncrement(), 7)) }
        repo.request()
        counter.set(160_000)
        val states = (1..12).map { async { repo.confirm() } }.awaitAll()
        assertTrue(states.all { it.phase == OVERRIDE_ACTIVE && it.used && it.unlockUntilElapsedMillis == 460_000L })
    }

    @Test fun `morning window ending cancels pending request without using allowance`() = runBlocking<Unit> {
        val repo = repository(open())
        repo.request()
        assertEquals(WAITING, repo.status().phase)
        time(130_000)
        now = now.copy(sessionRemainingMillis = 0)
        assertEquals(INACTIVE, repo.status().phase)
        assertFalse(repo.status().used)
        now = now.copy(sessionRemainingMillis = 7_200_000)
        time(140_000)
        assertEquals(200_000L, repo.request().readyAtElapsedMillis)
    }

    @Test fun `shortened access cap persists and cannot grow on reopen`() = runBlocking<Unit> {
        val file = File(temporary.root, "emergency.preferences_pb")
        val first = open(file)
        val repo = repository(first)
        granted(repo)
        time(200_000)
        now = now.copy(sessionRemainingMillis = 10_000)
        assertEquals(210_000L, repo.status().unlockUntilElapsedMillis)
        time(205_000)
        now = now.copy(sessionRemainingMillis = 7_200_000)
        assertEquals(210_000L, repository(reopened(first, file)).status().unlockUntilElapsedMillis)
    }

    @Test fun `partial saved request is rejected rather than reset`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        repo.request()
        store.data.edit { it.remove(longPreferencesKey("override_ready_at")) }
        val before = store.data.data.first().asMap()
        expectFailure<CorruptionException> { repo.status() }
        expectFailure<CorruptionException> { repo.request() }
        assertEquals(before, store.data.data.first().asMap())
    }

    @Test fun `overlong stored access cannot grant an extended override`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        granted(repo)
        store.data.edit { it[longPreferencesKey("override_until")] = Long.MAX_VALUE }
        expectFailure<CorruptionException> { repo.status() }
    }

    @Test fun `missing usage ledger is corruption not an unused allowance`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        granted(repo)
        store.data.edit { it.remove(stringSetPreferencesKey("override_used_sessions")) }
        expectFailure<CorruptionException> { repo.status() }
        expectFailure<CorruptionException> { repo.request() }
    }

    @Test fun `usage ledger and saved used flag must agree`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        granted(repo)
        time(460_000)
        assertTrue(repo.status().used)
        store.data.edit { it[stringSetPreferencesKey("override_used_sessions")] = emptySet() }
        expectFailure<CorruptionException> { repo.status() }
    }

    @Test fun `future schema is rejected without overwriting state`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        repo.request()
        store.data.edit { it[intPreferencesKey("override_schema")] = 99 }
        val before = store.data.data.first().asMap()
        expectFailure<IOException> { repo.status() }
        expectFailure<IOException> { repo.request() }
        assertEquals(before, store.data.data.first().asMap())
    }

    @Test fun `damaged protobuf bytes are retained and surfaced`() = runBlocking<Unit> {
        val file = File(temporary.root, "broken.preferences_pb")
        val bytes = byteArrayOf(0x0f, 0x7f, 0x01)
        file.writeBytes(bytes)
        val repo = repository(open(file))
        expectFailure<CorruptionException> { repo.status() }
        expectFailure<CorruptionException> { repo.request() }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `storage IO failure is propagated without returning fresh state`() = runBlocking<Unit> {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("Unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("Unavailable")
        }
        val repo = EmergencyRepository(broken) { now }
        expectFailure<IOException> { repo.status() }
        expectFailure<IOException> { repo.request() }
    }

    @Test fun `failed confirmation commit returns an error and stores no partial grant`() = runBlocking<Unit> {
        val store = open()
        var failWrites = false
        val flaky = object : DataStore<Preferences> {
            override val data = store.data.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = store.data.updateData {
                val changed = transform(it)
                if (failWrites) throw IOException("Disk full")
                changed
            }
        }
        val repo = EmergencyRepository(flaky) { now }
        repo.request()
        time(160_000)
        failWrites = true
        expectFailure<IOException> { repo.confirm() }
        failWrites = false
        assertEquals(READY_TO_UNLOCK, repo.status().phase)
        assertFalse(repo.status().used)
        assertTrue(repo.confirm().used)
    }

    @Test fun `native context failure leaves persisted request unchanged`() = runBlocking<Unit> {
        val store = open()
        var unavailable = false
        val repo = EmergencyRepository(store.data) { if (unavailable) throw IOException("Clock unavailable"); now }
        repo.request()
        val before = store.data.data.first().asMap()
        unavailable = true
        expectFailure<IOException> { repo.status() }
        assertEquals(before, store.data.data.first().asMap())
        unavailable = false
        assertEquals(160_000L, repo.status().readyAtElapsedMillis)
    }

    @Test fun `same boot clock regression cannot discard the stored wait`() = runBlocking<Unit> {
        val repo = repository(open())
        repo.request()
        time(1)
        expectFailure<IllegalArgumentException> { repo.status() }
        time(130_000)
        assertEquals(160_000L, repo.status().readyAtElapsedMillis)
    }

    @Test fun `rejected confirmation still persists required reboot cancellation`() = runBlocking<Unit> {
        val store = open()
        val repo = repository(store)
        repo.request()
        time(1_000, boot = 8)
        expectFailure<IllegalStateException> { repo.confirm() }
        val saved = store.data.data.first()
        assertEquals(8, saved[intPreferencesKey("override_boot_count")])
        assertEquals("BLOCKING", saved[stringPreferencesKey("override_phase")])
        assertNull(saved[longPreferencesKey("override_ready_at")])
        assertEquals(false, saved[booleanPreferencesKey("override_used")])
    }
}
