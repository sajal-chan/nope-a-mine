package com.morningblocker.storage

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import java.io.File
import java.io.IOException
import java.time.DayOfWeek.*
import java.time.LocalTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
class PolicyRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private data class OpenStore(val data: DataStore<Preferences>, val job: Job)
    private fun open(file: File = File(temporary.root, "policy.preferences_pb")): OpenStore {
        val job = SupervisorJob()
        jobs.add(job)
        val data = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        )
        return OpenStore(data, job)
    }
    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun policy(
        start: String = "06:00", end: String = "09:00",
        packages: Set<String> = setOf("com.instagram.android", "com.google.android.youtube", "com.reddit.frontpage"),
    ) = BlockingPolicy(MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), setOf(MONDAY, FRIDAY, SUNDAY)), packages)
    private fun assertPolicy(expected: BlockingPolicy, actual: BlockingPolicy?) {
        assertNotNull(actual)
        assertEquals(expected.schedule.startTime, actual!!.schedule.startTime)
        assertEquals(expected.schedule.endTime, actual.schedule.endTime)
        assertEquals(expected.schedule.activeDays, actual.schedule.activeDays)
        assertEquals(expected.blockedPackages, actual.blockedPackages)
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `fresh store is explicitly unconfigured`() = runBlocking<Unit> {
        assertNull(PolicyRepository(open().data).load())
    }

    @Test fun `complete schedule and all selected packages round trip`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().data)
        val expected = policy()
        repository.save(expected)
        assertPolicy(expected, repository.load())
    }

    @Test fun `configuration survives closing and reopening the actual DataStore file`() = runBlocking<Unit> {
        val file = File(temporary.root, "reopen.preferences_pb")
        val first = open(file)
        val expected = policy()
        PolicyRepository(first.data).save(expected)
        first.job.cancelAndJoin()
        assertPolicy(expected, PolicyRepository(open(file).data).load())
    }

    @Test fun `times retain seconds and nanoseconds without rounding`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().data)
        val expected = policy("06:01:02.123456789", "09:04:05.987654321")
        repository.save(expected)
        assertPolicy(expected, repository.load())
    }

    @Test fun `empty weekdays and package selections remain empty`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().data)
        val expected = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), emptySet()), emptySet())
        repository.save(expected)
        assertPolicy(expected, repository.load())
    }

    @Test fun `saving replaces the entire prior schedule and package selection`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().data)
        repository.save(policy())
        val updated = BlockingPolicy(
            MorningSchedule(LocalTime.of(7, 0), LocalTime.of(8, 30), setOf(TUESDAY)),
            setOf("org.example.futureapp"),
        )
        repository.save(updated)
        assertPolicy(updated, repository.load())
    }

    @Test fun `caller cannot mutate a policy package selection after construction`() = runBlocking<Unit> {
        val selected = mutableSetOf("com.instagram.android")
        val expected = policy(packages = selected)
        selected.clear()
        selected.add("com.reddit.frontpage")
        assertEquals(setOf("com.instagram.android"), expected.blockedPackages)
        val repository = PolicyRepository(open().data)
        repository.save(expected)
        assertEquals(setOf("com.instagram.android"), repository.load()!!.blockedPackages)
    }

    @Test fun `malformed package identifiers are rejected before storage`() = runBlocking<Unit> {
        for (invalid in listOf("", " ", "com.foo bar", " com.foo", "com.foo/Activity", "com..foo")) {
            expectFailure<IllegalArgumentException> { policy(packages = setOf(invalid)) }
        }
    }

    @Test fun `observer receives complete policy replacements`() = runBlocking<Unit> {
        withTimeout(10_000) {
            val repository = PolicyRepository(open().data)
            val emissions = Channel<BlockingPolicy?>(Channel.UNLIMITED)
            val collector = launch { repository.policies.collect { emissions.send(it) } }
            try {
                assertNull(emissions.receive())
                val first = policy()
                repository.save(first)
                assertPolicy(first, emissions.receive())
                val second = policy("07:00", "08:00", setOf("com.reddit.frontpage"))
                repository.save(second)
                assertPolicy(second, emissions.receive())
            } finally { collector.cancelAndJoin(); emissions.close() }
        }
    }

    @Test fun `concurrent saves cannot mix schedule from one policy with another package set`() = runBlocking<Unit> {
        val repository = PolicyRepository(open().data)
        val choices = (1..8).map { hour -> policy("0$hour:00", "09:00", setOf("org.example.app$hour")) }
        choices.map { candidate -> async { repository.save(candidate) } }.awaitAll()
        val saved = repository.load()!!
        assertPolicy(choices.single { it.schedule.startTime == saved.schedule.startTime }, saved)
    }

    private fun validPreferences() = mutablePreferencesOf(
        intPreferencesKey("policy_schema") to 1,
        stringPreferencesKey("schedule_start") to "06:00",
        stringPreferencesKey("schedule_end") to "09:00",
        stringSetPreferencesKey("schedule_days") to setOf("MONDAY"),
        stringSetPreferencesKey("blocked_packages") to setOf("com.instagram.android"),
    )
    private suspend fun assertRejectedPreferences(change: MutablePreferences.() -> Unit) {
        val raw = open(File(temporary.root, "bad-${jobs.size}.preferences_pb")).data
        raw.updateData { validPreferences().apply(change) }
        expectFailure<CorruptionException> { PolicyRepository(raw).load() }
    }

    @Test fun `partial saved policy is not mistaken for a fresh install`() = runBlocking<Unit> {
        assertRejectedPreferences { remove(stringPreferencesKey("schedule_end")) }
    }

    @Test fun `invalid stored time is reported as corruption`() = runBlocking<Unit> {
        assertRejectedPreferences { this[stringPreferencesKey("schedule_start")] = "25:00" }
    }

    @Test fun `stored overnight window is reported as corruption`() = runBlocking<Unit> {
        assertRejectedPreferences { this[stringPreferencesKey("schedule_start")] = "23:00" }
    }

    @Test fun `unknown stored weekday is reported as corruption`() = runBlocking<Unit> {
        assertRejectedPreferences { this[stringSetPreferencesKey("schedule_days")] = setOf("FUNDAY") }
    }

    @Test fun `invalid stored package is reported as corruption`() = runBlocking<Unit> {
        assertRejectedPreferences { this[stringSetPreferencesKey("blocked_packages")] = setOf("") }
    }

    @Test fun `wrong stored preference type is reported as corruption`() = runBlocking<Unit> {
        assertRejectedPreferences {
            remove(stringPreferencesKey("schedule_start"))
            this[intPreferencesKey("schedule_start")] = 600
        }
    }

    @Test fun `future schema cannot be read or overwritten`() = runBlocking<Unit> {
        val store = open().data
        store.updateData { validPreferences().apply { this[intPreferencesKey("policy_schema")] = 2 } }
        val before = store.data.first().asMap()
        val repository = PolicyRepository(store)
        expectFailure<IOException> { repository.load() }
        expectFailure<IOException> { repository.save(policy()) }
        assertEquals(before, store.data.first().asMap())
    }

    @Test fun `corrupt protobuf is surfaced and original bytes are preserved`() = runBlocking<Unit> {
        val file = File(temporary.root, "corrupt.preferences_pb")
        val bytes = byteArrayOf(0x0f, 0x7f, 0x01)
        file.writeBytes(bytes)
        val repository = PolicyRepository(open(file).data)
        expectFailure<CorruptionException> { repository.load() }
        expectFailure<CorruptionException> { repository.save(policy()) }
        assertArrayEquals(bytes, file.readBytes())
    }

    private fun failingStore(failure: IOException) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw failure
    }

    @Test fun `read IO failure is not silently converted to an empty policy`() = runBlocking<Unit> {
        val failure = IOException("Disk unavailable")
        assertSame(failure, expectFailure<IOException> { PolicyRepository(failingStore(failure)).load() })
    }

    @Test fun `save IO failure is propagated to caller`() = runBlocking<Unit> {
        val failure = IOException("Disk full")
        assertSame(failure, expectFailure<IOException> { PolicyRepository(failingStore(failure)).save(policy()) })
    }
}
