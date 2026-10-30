package com.morningblocker.storage

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.*
import java.io.File
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class ClockRecoveryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private data class OpenStore(val data: DataStore<Preferences>, val job: Job) { val repo = PolicyRepository(data) }
    private fun open(file: File = File(temporary.root, "policy.preferences_pb")): OpenStore {
        val job = SupervisorJob().also(jobs::add)
        return OpenStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }), job)
    }
    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private val utc = ZoneOffset.UTC
    private val before = ClockReading(Instant.parse("2026-11-02T05:00:00Z"), 100_000, 7, utc)
    private val during = before.copy(instant = Instant.parse("2026-11-02T07:00:00Z"), elapsedRealtimeMillis = 7_300_000)
    private fun policy(start: String = "06:00", end: String = "09:00", packages: Set<String> = setOf("com.instagram.android")) =
        BlockingPolicy(MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), DayOfWeek.values().toSet()), packages)
    private suspend fun started(store: OpenStore) {
        store.repo.save(policy(), before)
        assertNotNull(store.repo.activeSessionAt(during))
    }
    private suspend fun reopened(first: OpenStore, file: File): OpenStore {
        first.job.cancelAndJoin()
        return open(file)
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) { if (error is T) return; throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `forward wall jump after reopen cannot end the session early`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        val second = reopened(first, file)
        assertNotNull(second.repo.activeSessionAt(during.copy(instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 10_900_000)))
    }

    @Test fun `backward wall jump after reopen cannot extend a completed duration`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        val second = reopened(first, file)
        assertNull(second.repo.activeSessionAt(during.copy(instant = Instant.parse("2026-11-02T07:00:00Z"), elapsedRealtimeMillis = 14_500_000)))
    }

    @Test fun `zone change across dates keeps the original active policy`() = runBlocking<Unit> {
        val first = open()
        started(first)
        val shifted = during.copy(instant = during.instant.plusSeconds(1800), elapsedRealtimeMillis = 9_100_000, zone = ZoneId.of("Pacific/Honolulu"))
        val active = first.repo.activeSessionAt(shifted)!!
        assertEquals(LocalDate.parse("2026-11-02"), active.date)
        assertEquals(setOf("com.instagram.android"), active.policy.blockedPackages)
    }

    @Test fun `completed marker survives reopening and a clock rollback`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        assertNull(first.repo.activeSessionAt(during.copy(instant = Instant.parse("2026-11-02T09:00:00Z"), elapsedRealtimeMillis = 14_500_000)))
        val second = reopened(first, file)
        assertNull(second.repo.activeSessionAt(during.copy(elapsedRealtimeMillis = 14_600_000)))
    }

    @Test fun `reboot during the window recovers the frozen apps and remaining deadline`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        first.repo.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), during)
        val second = reopened(first, file)
        val rebooted = during.copy(instant = Instant.parse("2026-11-02T08:00:00Z"), elapsedRealtimeMillis = 1_000, bootCount = 8)
        assertEquals(setOf("com.instagram.android"), second.repo.activeSessionAt(rebooted)!!.policy.blockedPackages)
        assertEquals(8, second.data.data.first()[intPreferencesKey("clock_boot_count")])
        assertNull(second.repo.activeSessionAt(rebooted.copy(instant = Instant.parse("2026-11-02T09:00:00Z"), elapsedRealtimeMillis = 3_601_000)))
    }

    @Test fun `reboot after expiry does not revive original or pending policy that day`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        first.repo.save(policy("08:00", "11:00"), during)
        val second = reopened(first, file)
        val rebooted = during.copy(instant = Instant.parse("2026-11-02T10:00:00Z"), elapsedRealtimeMillis = 1_000, bootCount = 8)
        assertNull(second.repo.activeSessionAt(rebooted))
        assertNull(second.repo.activeSessionAt(rebooted.copy(instant = during.instant, elapsedRealtimeMillis = 2_000)))
    }

    @Test fun `observed wall change checkpoint is persisted for a subsequent reboot`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        val jumped = during.copy(instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 10_900_000)
        assertNotNull(first.repo.activeSessionAt(jumped))
        val second = reopened(first, file)
        assertNotNull(second.repo.activeSessionAt(jumped.copy(instant = Instant.parse("2026-11-02T12:30:00Z"), elapsedRealtimeMillis = 1_000, bootCount = 8)))
        assertNull(second.repo.activeSessionAt(jumped.copy(instant = Instant.parse("2026-11-02T13:00:00Z"), elapsedRealtimeMillis = 1_801_000, bootCount = 8)))
    }

    @Test fun `edits during clock rollback cannot change or restart the current session`() = runBlocking<Unit> {
        val first = open()
        started(first)
        val rolledBack = during.copy(instant = Instant.parse("2026-11-02T04:00:00Z"), elapsedRealtimeMillis = 9_100_000)
        first.repo.save(policy("05:00", "12:00", setOf("com.reddit.frontpage")), rolledBack)
        assertEquals(setOf("com.instagram.android"), first.repo.activeSessionAt(rolledBack)!!.policy.blockedPackages)
        assertNull(first.repo.activeSessionAt(rolledBack.copy(elapsedRealtimeMillis = 14_500_000)))
    }

    @Test fun `partial clock checkpoint is an error and cannot be overwritten`() = runBlocking<Unit> {
        val first = open()
        started(first)
        first.data.edit { it.remove(stringPreferencesKey("clock_remaining")) }
        expectFailure<CorruptionException> { first.repo.activeSessionAt(during) }
        expectFailure<CorruptionException> { first.repo.save(policy(), during) }
    }

    @Test fun `invalid persisted remaining duration is rejected`() = runBlocking<Unit> {
        val first = open()
        started(first)
        first.data.edit { it[stringPreferencesKey("clock_remaining")] = "PT-1S" }
        expectFailure<CorruptionException> { first.repo.activeSessionAt(during) }
    }

    @Test fun `elapsed regression is surfaced without discarding stored protection`() = runBlocking<Unit> {
        val first = open()
        started(first)
        expectFailure<IllegalArgumentException> { first.repo.activeSessionAt(during.copy(elapsedRealtimeMillis = 1)) }
        assertNotNull(first.repo.activeSessionAt(during))
    }

    @Test fun `concurrent recovery and edit keep one frozen policy`() = runBlocking<Unit> {
        val first = open()
        started(first)
        val jumped = during.copy(instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 10_900_000)
        val recovery = async { first.repo.activeSessionAt(jumped) }
        val edit = async { first.repo.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), jumped) }
        assertEquals(setOf("com.instagram.android"), recovery.await()!!.policy.blockedPackages)
        edit.await()
        assertEquals(setOf("com.instagram.android"), first.repo.activeSessionAt(jumped)!!.policy.blockedPackages)
    }

    @Test fun `legacy frozen session without clock data anchors once using the supplied zone`() = runBlocking<Unit> {
        val first = open()
        first.repo.save(policy(), before)
        first.data.edit {
            it[stringPreferencesKey("session_date")] = "2026-11-02"
            it[stringPreferencesKey("session_schedule_start")] = "06:00"
            it[stringPreferencesKey("session_schedule_end")] = "09:00"
            it[stringSetPreferencesKey("session_schedule_days")] = DayOfWeek.values().map { day -> day.name }.toSet()
            it[stringSetPreferencesKey("session_blocked_packages")] = setOf("com.instagram.android")
        }
        assertNotNull(first.repo.activeSessionAt(during))
        assertNotNull(first.repo.activeSessionAt(during.copy(instant = Instant.parse("2026-11-02T12:00:00Z"), elapsedRealtimeMillis = 10_900_000)))
    }

    @Test fun `DST overlap stays active through the second occurrence without restarting`() = runBlocking<Unit> {
        val first = open()
        val newYork = ZoneId.of("America/New_York")
        val setup = before.copy(instant = Instant.parse("2026-11-01T04:00:00Z"), zone = newYork)
        first.repo.save(policy("01:15", "01:45"), setup)
        val early = setup.copy(instant = Instant.parse("2026-11-01T05:20:00Z"), elapsedRealtimeMillis = 4_900_000)
        assertNotNull(first.repo.activeSessionAt(early))
        assertNotNull(first.repo.activeSessionAt(early.copy(instant = Instant.parse("2026-11-01T05:50:00Z"), elapsedRealtimeMillis = 6_700_000)))
        assertNotNull(first.repo.activeSessionAt(early.copy(instant = Instant.parse("2026-11-01T06:20:00Z"), elapsedRealtimeMillis = 8_500_000)))
        assertNull(first.repo.activeSessionAt(early.copy(instant = Instant.parse("2026-11-01T06:45:00Z"), elapsedRealtimeMillis = 10_000_000)))
    }

    @Test fun `DST gap that moves start later is not activated before its resolved instant`() = runBlocking<Unit> {
        val first = open()
        val newYork = ZoneId.of("America/New_York")
        val setup = before.copy(instant = Instant.parse("2026-03-08T05:00:00Z"), zone = newYork)
        first.repo.save(policy("02:30", "04:00"), setup)
        val tooEarly = setup.copy(instant = Instant.parse("2026-03-08T07:10:00Z"), elapsedRealtimeMillis = 7_900_000)
        assertNull(first.repo.activeSessionAt(tooEarly))
        assertNotNull(first.repo.activeSessionAt(tooEarly.copy(instant = Instant.parse("2026-03-08T07:30:00Z"), elapsedRealtimeMillis = 9_100_000)))
    }
}
