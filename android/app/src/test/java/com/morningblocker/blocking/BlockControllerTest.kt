package com.morningblocker.blocking

import android.app.Application
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.morningblocker.scheduling.MorningSchedule
import com.morningblocker.scheduling.RestrictionSession
import com.morningblocker.storage.BlockingPolicy
import com.morningblocker.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlockControllerTest {
    private fun block(packageName: String = "com.instagram.android", end: LocalTime = LocalTime.of(9, 0)) =
        RestrictionDecision.Blocked(packageName, RestrictionSession(LocalDate.of(2026, 11, 2), BlockingPolicy(
            MorningSchedule(LocalTime.of(6, 0), end, DayOfWeek.values().toSet()), setOf(packageName),
        )))

    @Test fun `overlay identifies the actual supported app and scheduled unlock time`() {
        val oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val controller = BlockController(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            for ((packageName, label) in mapOf("com.instagram.android" to "Instagram", "com.google.android.youtube" to "YouTube", "com.reddit.frontpage" to "Reddit")) {
                assertTrue(controller.show(block(packageName)))
                val description = controller.overlay!!.findViewWithTag<TextView>("block-description").text.toString()
                assertTrue(description.contains(label))
                assertTrue(description.contains("9:00 AM"))
                assertFalse(description.contains("prototype"))
                controller.hide()
            }
        } finally { controller.hide(); Locale.setDefault(oldLocale) }
    }

    @Test fun `changed target and end time update one existing overlay`() {
        val oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val controller = BlockController(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            controller.show(block())
            val original = controller.overlay!!
            controller.show(block("com.google.android.youtube", LocalTime.of(10, 30)))
            assertSame(original, controller.overlay)
            val description = original.findViewWithTag<TextView>("block-description").text.toString()
            assertTrue(description.contains("YouTube"))
            assertTrue(description.contains("10:30 AM"))
        } finally { controller.hide(); Locale.setDefault(oldLocale) }
    }

    @Test fun `future package labels fall back to their package identifier`() {
        val controller = BlockController(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            controller.show(block("org.example.future"))
            assertTrue(controller.overlay!!.findViewWithTag<TextView>("block-description").text.contains("org.example.future"))
        } finally { controller.hide() }
    }

    @Test fun `accessibility disclosure describes saved schedules instead of continuous prototype enforcement`() {
        val description = RuntimeEnvironment.getApplication().getString(R.string.accessibility_description)
        assertTrue(description.contains("saved schedule"))
        assertFalse(description.contains("blocks whenever enabled"))
    }
    @Test fun `overlay intercepts touches without taking system navigation focus`() {
        val controller = BlockController(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            assertTrue(controller.show(block()))
            val view = controller.overlay!!
            val params = view.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(controller.show(block()))
            assertSame(view, controller.overlay)
        } finally { controller.hide() }
        assertNull(controller.overlay)
    }

    @Test fun `home action never dismisses block before foreground changes`() {
        var homes = 0
        val controller = BlockController(RuntimeEnvironment.getApplication(), { homes++; true }, {})
        try {
            controller.show(block())
            controller.overlay!!.findViewWithTag<Button>("go-home").performClick()
            assertEquals(1, homes)
            assertNotNull(controller.overlay)
        } finally { controller.hide() }
    }

    @Test fun `settings action remains available to revoke access`() {
        var settings = 0
        val controller = BlockController(RuntimeEnvironment.getApplication(), { false }, { settings++ })
        try {
            controller.show(block())
            controller.overlay!!.findViewWithTag<Button>("settings").performClick()
            assertEquals(1, settings)
        } finally { controller.hide() }
    }
}
