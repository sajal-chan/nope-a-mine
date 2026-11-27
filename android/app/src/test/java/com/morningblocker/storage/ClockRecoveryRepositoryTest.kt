package com.morningblocker.storage

import android.app.Application
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
    private fun reading(value: String, zone: String = "UTC") = ClockReading(Instant.parse(value), ZoneId.of(zone))
    private val before = reading("2026-11-02T05:00:00Z")
    private val during = reading("2026-11-02T07:00:00Z")
    private val after = reading("2026-11-02T12:00:00Z")
    private fun policy(start: String = "06:00", end: String = "09:00", packages: Set<String> = setOf("com.instagram.android")) =
        BlockingPolicy(MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), DayOfWeek.values().toSet()), packages)
    private suspend fun started(store: OpenStore) {
        store.repo.save(policy(), before)
        assertNotNull(store.repo.activeSessionAt(during))
    }
    private suspend fun reopened(first: OpenStore, file: File): OpenStore { first.job.cancelAndJoin(); return open(file) }

    @Test fun `forward clock jump outside hours unblocks after reopening`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        assertNull(reopened(first, file).repo.activeSessionAt(after))
    }

    @Test fun `backward clock jump into hours blocks again after reopening`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        assertNull(first.repo.activeSessionAt(after))
        assertNotNull(reopened(first, file).repo.activeSessionAt(during))
    }

    @Test fun `clock rollback before start unblocks and returning to hours blocks`() = runBlocking<Unit> {
        val first = open()
        started(first)
        assertNull(first.repo.activeSessionAt(before))
        assertNotNull(first.repo.activeSessionAt(during))
    }

    @Test fun `zone change outside local hours unblocks and switching back blocks`() = runBlocking<Unit> {
        val first = open()
        started(first)
        assertNull(first.repo.activeSessionAt(during.copy(zone = ZoneId.of("Asia/Kolkata"))))
        assertNotNull(first.repo.activeSessionAt(during))
    }

    @Test fun `zone change across dates uses the current local hours`() = runBlocking<Unit> {
        val first = open()
        started(first)
        assertNull(first.repo.activeSessionAt(during.copy(zone = ZoneId.of("Pacific/Honolulu"))))
    }

    @Test fun `frozen apps remain unchanged when the clock reenters the window`() = runBlocking<Unit> {
        val first = open()
        started(first)
        first.repo.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), during)
        assertNull(first.repo.activeSessionAt(after))
        assertEquals(setOf("com.instagram.android"), first.repo.activeSessionAt(during)!!.policy.blockedPackages)
    }

    @Test fun `reopening during the window restores frozen apps and exact end boundary`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        first.repo.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), during)
        val second = reopened(first, file)
        assertEquals(setOf("com.instagram.android"), second.repo.activeSessionAt(reading("2026-11-02T08:00:00Z"))!!.policy.blockedPackages)
        assertNull(second.repo.activeSessionAt(reading("2026-11-02T09:00:00Z")))
    }

    @Test fun `pending later window does not replace the frozen policy after restart`() = runBlocking<Unit> {
        val file = File(temporary.root, "policy.preferences_pb")
        val first = open(file)
        started(first)
        first.repo.save(policy("08:00", "11:00"), during)
        assertNull(reopened(first, file).repo.activeSessionAt(reading("2026-11-02T10:00:00Z")))
    }

    @Test fun `first setup within hours starts immediately`() = runBlocking<Unit> {
        val first = open()
        first.repo.save(policy(), during)
        assertNotNull(first.repo.activeSessionAt(during))
    }

    @Test fun `obsolete partial duration checkpoint is ignored and removed`() = runBlocking<Unit> {
        val first = open()
        started(first)
        first.data.edit { it[stringPreferencesKey("clock_remaining")] = "PT2H"; it.remove(stringPreferencesKey("clock_zone")) }
        assertNotNull(first.repo.activeSessionAt(during))
        assertFalse(first.data.data.first().asMap().keys.any { it.name.startsWith("clock_") })
    }

    @Test fun `obsolete invalid duration metadata cannot override local hours`() = runBlocking<Unit> {
        val first = open()
        started(first)
        first.data.edit { it[stringPreferencesKey("clock_remaining")] = "PT-1S" }
        assertNull(first.repo.activeSessionAt(after))
        assertEquals(setOf("com.instagram.android"), first.repo.load()!!.blockedPackages)
    }

    @Test fun `morning evaluation stores no uptime or boot checkpoint`() = runBlocking<Unit> {
        val first = open()
        started(first)
        assertFalse(first.data.data.first().asMap().keys.any { it.name.startsWith("clock_") })
    }

    @Test fun `concurrent evaluation and edit preserve the original policy on reentry`() = runBlocking<Unit> {
        val first = open()
        started(first)
        val recovery = async { first.repo.activeSessionAt(after) }
        val edit = async { first.repo.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), after) }
        assertNull(recovery.await())
        edit.await()
        assertEquals(setOf("com.instagram.android"), first.repo.activeSessionAt(during)!!.policy.blockedPackages)
    }

    @Test fun `DST repeated hour follows local clock on each occurrence`() = runBlocking<Unit> {
        val first = open()
        val zone = "America/New_York"
        first.repo.save(policy("01:15", "01:45"), reading("2026-11-01T04:00:00Z", zone))
        assertNotNull(first.repo.activeSessionAt(reading("2026-11-01T05:20:00Z", zone)))
        assertNull(first.repo.activeSessionAt(reading("2026-11-01T05:50:00Z", zone)))
        assertNotNull(first.repo.activeSessionAt(reading("2026-11-01T06:20:00Z", zone)))
        assertNull(first.repo.activeSessionAt(reading("2026-11-01T06:45:00Z", zone)))
    }

    @Test fun `DST spring jump into configured hours activates immediately`() = runBlocking<Unit> {
        val first = open()
        val zone = "America/New_York"
        first.repo.save(policy("02:30", "04:00"), reading("2026-03-08T05:00:00Z", zone))
        assertNotNull(first.repo.activeSessionAt(reading("2026-03-08T07:10:00Z", zone)))
        assertNull(first.repo.activeSessionAt(reading("2026-03-08T08:00:00Z", zone)))
    }
}
