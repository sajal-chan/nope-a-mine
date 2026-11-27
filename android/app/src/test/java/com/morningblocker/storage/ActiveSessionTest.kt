package com.morningblocker.storage

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
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
class ActiveSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private data class OpenStore(val data: DataStore<Preferences>, val job: Job) {
        val repository = PolicyRepository(data)
    }
    private fun open(file: File = File(temporary.root, "policy.preferences_pb")): OpenStore {
        val job = SupervisorJob().also(jobs::add)
        return OpenStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        ), job)
    }
    @After fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun at(value: String) = LocalDateTime.parse(value)
    private val before = at("2026-11-02T05:00")
    private val during = at("2026-11-02T07:00")
    private fun policy(start: String = "06:00", end: String = "09:00", packages: Set<String> = setOf("com.instagram.android")) =
        BlockingPolicy(MorningSchedule(LocalTime.parse(start), LocalTime.parse(end), DayOfWeek.values().toSet()), packages)
    private fun assertOriginal(session: RestrictionSession?) {
        assertNotNull(session)
        assertEquals(at("2026-11-02T06:00"), session!!.window.startsAt)
        assertEquals(at("2026-11-02T09:00"), session.window.endsAt)
        assertEquals(setOf("com.instagram.android"), session.policy.blockedPackages)
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `unconfigured store has no active session`() = runBlocking<Unit> {
        assertNull(open().repository.activeSessionAt(during))
    }

    @Test fun `configured future session does not start early`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertNull(repository.activeSessionAt(before))
    }

    @Test fun `session freezes exactly at start and retains its original start time`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertOriginal(repository.activeSessionAt(at("2026-11-02T06:00")))
    }

    @Test fun `first setup within its window starts immediately`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), during)
        assertOriginal(repository.activeSessionAt(during))
    }

    @Test fun `elapsed unobserved session prevents a second window on the same date`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("10:00", "11:00"), at("2026-11-02T09:30"))
        assertNull(repository.activeSessionAt(at("2026-11-02T10:30")))
        assertNotNull(repository.activeSessionAt(at("2026-11-03T10:30")))
    }

    @Test fun `late first evaluation captures the scheduled start rather than observation time`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertOriginal(repository.activeSessionAt(during))
    }

    @Test fun `session ends exactly at original end`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertOriginal(repository.activeSessionAt(during))
        assertNull(repository.activeSessionAt(at("2026-11-02T09:00")))
    }

    @Test fun `earlier end and removed apps cannot weaken an active session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.activeSessionAt(during)
        repository.save(policy("06:00", "07:15", setOf("com.reddit.frontpage")), during)
        assertOriginal(repository.activeSessionAt(at("2026-11-02T08:00")))
    }

    @Test fun `later end cannot extend the active session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertOriginal(repository.activeSessionAt(during))
        repository.save(policy(end = "11:00"), during)
        assertOriginal(repository.activeSessionAt(at("2026-11-02T08:00")))
        assertNull(repository.activeSessionAt(at("2026-11-02T09:00")))
        assertNull(repository.activeSessionAt(at("2026-11-02T10:00")))
    }

    @Test fun `clearing selected packages cannot disable the current session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy(packages = emptySet()), during)
        assertOriginal(repository.activeSessionAt(during))
        assertNull(repository.activeSessionAt(at("2026-11-03T07:00")))
    }

    @Test fun `clearing active days cannot disable the current session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        val disabled = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), emptySet()), setOf("com.instagram.android"))
        repository.save(disabled, during)
        assertOriginal(repository.activeSessionAt(during))
        assertNull(repository.activeSessionAt(at("2026-11-03T07:00")))
    }

    @Test fun `saving inside original window freezes it before applying the edit even without prior evaluation`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("08:00", "10:00", setOf("com.reddit.frontpage")), during)
        assertOriginal(repository.activeSessionAt(during))
    }

    @Test fun `save exactly at original start cannot avoid capturing the original session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("08:00", "10:00"), at("2026-11-02T06:00"))
        assertOriginal(repository.activeSessionAt(during))
    }

    @Test fun `edits before any session starts can take effect that same morning`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("07:00", "08:00", setOf("com.reddit.frontpage")), at("2026-11-02T05:30"))
        assertNull(repository.activeSessionAt(at("2026-11-02T06:30")))
        val session = repository.activeSessionAt(during)!!
        assertEquals(at("2026-11-02T08:00"), session.window.endsAt)
        assertEquals(setOf("com.reddit.frontpage"), session.policy.blockedPackages)
    }

    @Test fun `latest repeated edit is used by the next eligible session`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("07:00", "10:00"), during)
        repository.save(policy("08:00", "11:00", setOf("com.reddit.frontpage")), at("2026-11-02T08:00"))
        assertOriginal(repository.activeSessionAt(at("2026-11-02T08:30")))
        val next = repository.activeSessionAt(at("2026-11-03T08:30"))!!
        assertEquals(at("2026-11-03T08:00"), next.window.startsAt)
        assertEquals(at("2026-11-03T11:00"), next.window.endsAt)
        assertEquals(setOf("com.reddit.frontpage"), next.policy.blockedPackages)
    }

    @Test fun `deferred weekday edits wait for the next selected day`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        val wednesday = BlockingPolicy(MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), setOf(DayOfWeek.WEDNESDAY)), setOf("com.reddit.frontpage"))
        repository.save(wednesday, during)
        assertOriginal(repository.activeSessionAt(during))
        assertNull(repository.activeSessionAt(at("2026-11-03T07:00")))
        assertEquals(setOf("com.reddit.frontpage"), repository.activeSessionAt(at("2026-11-04T07:00"))!!.policy.blockedPackages)
    }

    @Test fun `edited later window cannot start a second session on the same date`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        repository.save(policy("10:00", "11:00"), during)
        assertNull(repository.activeSessionAt(at("2026-11-02T10:30")))
        assertNotNull(repository.activeSessionAt(at("2026-11-03T10:30")))
    }

    @Test fun `edits after a completed session still wait until a later date`() = runBlocking<Unit> {
        val repository = open().repository
        repository.save(policy(), before)
        assertOriginal(repository.activeSessionAt(during))
        repository.save(policy("10:00", "11:00"), at("2026-11-02T09:30"))
        assertNull(repository.activeSessionAt(at("2026-11-02T10:30")))
    }

    @Test fun `frozen policy and pending edits survive reopening storage`() = runBlocking<Unit> {
        val file = File(temporary.root, "reopen.preferences_pb")
        val first = open(file)
        first.repository.save(policy(), before)
        first.repository.save(policy("08:00", "10:00", setOf("com.reddit.frontpage")), during)
        first.job.cancelAndJoin()
        val second = open(file).repository
        assertOriginal(second.activeSessionAt(at("2026-11-02T08:30")))
        assertEquals(setOf("com.reddit.frontpage"), second.load()!!.blockedPackages)
        assertEquals(setOf("com.reddit.frontpage"), second.activeSessionAt(at("2026-11-03T08:30"))!!.policy.blockedPackages)
    }

    @Test fun `concurrent evaluation and edit preserve the same original policy`() = runBlocking<Unit> {
        val store = open().data
        val first = PolicyRepository(store)
        val second = PolicyRepository(store)
        first.save(policy(), before)
        val capture = async { first.activeSessionAt(during) }
        val edit = async { second.save(policy("08:00", "10:00", setOf("com.reddit.frontpage")), during) }
        assertOriginal(capture.await())
        edit.await()
        assertOriginal(first.activeSessionAt(during))
    }

    @Test fun `partial saved session is an error and cannot be overwritten`() = runBlocking<Unit> {
        val raw = open().data
        val repository = PolicyRepository(raw)
        repository.save(policy(), before)
        raw.edit { it[stringPreferencesKey("session_date")] = "2026-11-02" }
        expectFailure<CorruptionException> { repository.activeSessionAt(during) }
        expectFailure<CorruptionException> { repository.save(policy(), during) }
    }

    @Test fun `storage failure while capturing a session is surfaced`() = runBlocking<Unit> {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("Unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("Unavailable")
        }
        expectFailure<IOException> { PolicyRepository(store).activeSessionAt(during) }
    }
}
