package com.morningblocker.blocking

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.morningblocker.scheduling.ClockReading
import com.morningblocker.storage.PolicyRepository
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
class ScheduleConfigurationAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private fun repository(): PolicyRepository {
        val job = SupervisorJob().also(jobs::add)
        return PolicyRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { File(temporary.root, "${jobs.size}.preferences_pb") }))
    }
    @After fun close() = runBlocking { jobs.forEach { it.cancelAndJoin() } }
    private fun at(local: String): ClockReading {
        val zone = ZoneId.of("Asia/Kolkata")
        return ClockReading(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)
    }
    private fun input(app: String = "com.instagram.android", end: String = "09:00") = JavaOnlyMap.of(
        "startTime", "06:00", "endTime", end,
        "weekdays", JavaOnlyArray.of(1, 2, 3, 4, 5, 6, 7), "packages", JavaOnlyArray.of(app),
    )
    private suspend inline fun <reified T : Throwable> failure(block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `native view encodes an explicit absent policy and the supported catalog`() = runBlocking<Unit> {
        val view = ScheduleConfigurationAccess(repository()) { at("2026-11-02T05:00") }.read()
        assertTrue(view.containsKey("policy"))
        assertNull(view["policy"])
        assertNull(view["activeSession"])
        assertNull(view["effectiveFrom"])
        assertEquals(false, view["changesDeferred"])
        val apps = view["supportedApps"] as List<*>
        assertEquals(3, apps.size)
        assertEquals("Instagram", (apps[0] as Map<*, *>)["label"])
    }
    @Test fun `valid bridge input persists and native clock starts an in-window session`() = runBlocking<Unit> {
        val repository = repository()
        val view = ScheduleConfigurationAccess(repository) { at("2026-11-02T07:00") }.save(input())
        assertEquals("06:00", (view["policy"] as Map<*, *>)["startTime"])
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), (view["policy"] as Map<*, *>)["weekdays"])
        assertEquals("2026-11-02", (view["activeSession"] as Map<*, *>)["date"])
        assertEquals(setOf("com.instagram.android"), repository.load()!!.blockedPackages)
    }
    @Test fun `bridge view keeps editable packages separate from effective packages`() = runBlocking<Unit> {
        var now = at("2026-11-02T05:00")
        val access = ScheduleConfigurationAccess(repository()) { now }
        access.save(input())
        now = at("2026-11-02T07:00")
        val view = access.save(input("com.reddit.frontpage", "08:00"))
        assertEquals(listOf("com.reddit.frontpage"), (view["policy"] as Map<*, *>)["packages"])
        assertEquals(listOf("com.instagram.android"), (view["activeSession"] as Map<*, *>)["packages"])
        assertEquals("09:00", (view["activeSession"] as Map<*, *>)["endTime"])
        assertEquals("2026-11-03", view["effectiveFrom"])
        assertEquals(true, view["changesDeferred"])
        now = at("2026-11-03T07:00")
        assertEquals(false, access.read()["changesDeferred"])
    }
    @Test fun `JavaScript supplied time is rejected before clock sampling or mutation`() = runBlocking<Unit> {
        val repository = repository()
        var clockCalls = 0
        val access = ScheduleConfigurationAccess(repository) { clockCalls++; at("2026-11-02T07:00") }
        val invalid = input().apply { putString("now", "2026-11-02T01:00") }
        failure<IllegalArgumentException> { access.save(invalid) }
        assertEquals(0, clockCalls)
        assertNull(repository.load())
    }
    @Test fun `storage failure is propagated rather than an empty successful configuration`() = runBlocking<Unit> {
        val exception = IOException("Disk full")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw exception }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw exception
        }
        val access = ScheduleConfigurationAccess(PolicyRepository(store)) { at("2026-11-02T07:00") }
        assertSame(exception, failure<IOException> { access.read() })
        assertSame(exception, failure<IOException> { access.save(input()) })
    }
    @Test fun `clock is sampled afresh for every operation and failures do not write`() = runBlocking<Unit> {
        val repository = repository()
        var calls = 0
        var fails = false
        val access = ScheduleConfigurationAccess(repository) {
            calls++
            if (fails) throw IllegalStateException("Clock unavailable")
            at("2026-11-02T05:00")
        }
        access.read()
        access.save(input())
        assertEquals(2, calls)
        fails = true
        failure<IllegalStateException> { access.save(input("com.reddit.frontpage")) }
        assertEquals(setOf("com.instagram.android"), repository.load()!!.blockedPackages)
    }

    @Test fun `valid inputs accept an explicitly disabled schedule`() {
        val value = input().apply { putArray("weekdays", JavaOnlyArray()); putArray("packages", JavaOnlyArray()) }
        val policy = ScheduleConfigurationCodec.decode(value)
        assertTrue(policy.blockedPackages.isEmpty())
        assertTrue(policy.schedule.activeDays.isEmpty())
    }
    @Test fun `bridge rejects malformed missing and unexpected fields`() = runBlocking<Unit> {
        val invalid = listOf(null, JavaOnlyMap(), input().apply { putInt("startTime", 600) }, input().apply { putString("extra", "ignored") }, input().apply { putNull("packages") })
        for (value in invalid) failure<IllegalArgumentException> { ScheduleConfigurationCodec.decode(value) }
    }
    @Test fun `bridge rejects invalid times equal windows and overnight windows`() = runBlocking<Unit> {
        for (time in listOf("6:00", "24:00", "06:60", "06:00:01", " 06:00", "bad", "09:00", "10:00")) {
            failure<IllegalArgumentException> { ScheduleConfigurationCodec.decode(input().apply { putString("startTime", time) }) }
        }
    }
    @Test fun `bridge rejects invalid fractional duplicate and wrongly typed weekdays`() = runBlocking<Unit> {
        for (days in listOf(JavaOnlyArray.of(0), JavaOnlyArray.of(8), JavaOnlyArray.of(1.5), JavaOnlyArray.of(1, 1), JavaOnlyArray.of("1"), JavaOnlyArray.of(null), JavaOnlyArray.of(Double.NaN))) {
            failure<IllegalArgumentException> { ScheduleConfigurationCodec.decode(input().apply { putArray("weekdays", days) }) }
        }
    }
    @Test fun `bridge rejects unsupported system malformed and duplicate package selections`() = runBlocking<Unit> {
        for (apps in listOf(JavaOnlyArray.of("com.android.settings"), JavaOnlyArray.of("com.morningblocker"), JavaOnlyArray.of("org.example.future"), JavaOnlyArray.of("Instagram"), JavaOnlyArray.of(42), JavaOnlyArray.of("com.instagram.android", "com.instagram.android"))) {
            failure<IllegalArgumentException> { ScheduleConfigurationCodec.decode(input().apply { putArray("packages", apps) }) }
        }
    }
    @Test fun `all supported apps and unordered ISO weekdays retain their identities`() {
        val value = input().apply {
            putArray("packages", JavaOnlyArray.of("com.instagram.android", "com.google.android.youtube", "com.reddit.frontpage"))
            putArray("weekdays", JavaOnlyArray.of(7, 1, 5))
        }
        val policy = ScheduleConfigurationCodec.decode(value)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY), policy.schedule.activeDays)
        assertEquals(3, policy.blockedPackages.size)
    }
}
