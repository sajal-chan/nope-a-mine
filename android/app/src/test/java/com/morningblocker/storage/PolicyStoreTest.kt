package com.morningblocker.storage

import android.app.Application
import android.content.ContextWrapper
import com.morningblocker.scheduling.MorningSchedule
import java.time.DayOfWeek.MONDAY
import java.time.LocalTime
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PolicyStoreTest {
    @Test fun `context wrappers share one application DataStore`() = runBlocking<Unit> {
        val application = RuntimeEnvironment.getApplication()
        val first = policyRepository(ContextWrapper(application))
        val second = policyRepository(application)
        val expected = BlockingPolicy(
            MorningSchedule(LocalTime.of(6, 0), LocalTime.of(9, 0), setOf(MONDAY)),
            setOf("com.instagram.android"),
        )
        first.save(expected, LocalDateTime.parse("2026-11-02T00:00"))
        val loaded = second.load()
        assertNotNull(loaded)
        assertEquals(expected.schedule.startTime, loaded!!.schedule.startTime)
        assertEquals(expected.schedule.endTime, loaded.schedule.endTime)
        assertEquals(expected.schedule.activeDays, loaded.schedule.activeDays)
        assertEquals(expected.blockedPackages, loaded.blockedPackages)
    }
}
