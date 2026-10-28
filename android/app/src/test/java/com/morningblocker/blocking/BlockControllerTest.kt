package com.morningblocker.blocking

import android.app.Application
import android.view.WindowManager
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlockControllerTest {
    @Test fun `overlay intercepts touches without taking system navigation focus`() {
        val controller = BlockController(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            assertTrue(controller.show())
            val view = controller.overlay!!
            val params = view.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(controller.show())
            assertSame(view, controller.overlay)
        } finally { controller.hide() }
        assertNull(controller.overlay)
    }

    @Test fun `home action never dismisses block before foreground changes`() {
        var homes = 0
        val controller = BlockController(RuntimeEnvironment.getApplication(), { homes++; true }, {})
        try {
            controller.show()
            controller.overlay!!.findViewWithTag<Button>("go-home").performClick()
            assertEquals(1, homes)
            assertNotNull(controller.overlay)
        } finally { controller.hide() }
    }

    @Test fun `settings action remains available to revoke access`() {
        var settings = 0
        val controller = BlockController(RuntimeEnvironment.getApplication(), { false }, { settings++ })
        try {
            controller.show()
            controller.overlay!!.findViewWithTag<Button>("settings").performClick()
            assertEquals(1, settings)
        } finally { controller.hide() }
    }
}
