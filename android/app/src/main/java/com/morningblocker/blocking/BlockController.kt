package com.morningblocker.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morningblocker.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class BlockController(
    private val context: Context,
    private val goHome: () -> Boolean,
    private val openSettings: () -> Unit,
) : BlockingSurface {
    private val windows = context.getSystemService(WindowManager::class.java)
    internal var overlay: View? = null
        private set

    override fun show(block: RestrictionDecision.Blocked): Boolean {
        val app = SupportedApps.labels[block.packageName] ?: block.packageName
        val end = block.session.window.endsAt.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val description = context.getString(R.string.block_description, app, end)
        overlay?.let { root ->
            val label = root.findViewWithTag<TextView>("block-description")
            if (label.text.toString() != description) label.text = description
            return true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.block_title)
                textSize = 30f
                setTextColor(Color.rgb(25, 37, 29))
                gravity = Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            addView(TextView(context).apply {
                tag = "block-description"
                text = description
                textSize = 18f
                setTextColor(Color.rgb(51, 67, 56))
                gravity = Gravity.CENTER
                setPadding(0, padding, 0, padding)
            })
            addView(Button(context).apply {
                tag = "go-home"
                setText(R.string.go_home)
                setOnClickListener {
                    if (!goHome()) {
                        EnforcementStatus.error = "Home action unavailable. Use system navigation or accessibility settings."
                        Log.w("MorningBlocker", "Global Home action unavailable")
                    }
                    // Keep intercepting until a subsequent foreground observation confirms departure.
                }
            })
            addView(Button(context).apply {
                tag = "settings"
                setText(R.string.accessibility_settings)
                setOnClickListener { openSettings() }
            })
        }
        val root = ScrollView(context).apply {
            setBackgroundColor(Color.rgb(250, 248, 243))
            isFillViewport = true
            isClickable = true
            addView(content)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle("Morning Blocker")
        }
        return try {
            windows.addView(root, params)
            overlay = root
            EnforcementStatus.error = null
            Log.i("MorningBlocker", "Blocking overlay shown")
            true
        } catch (error: RuntimeException) {
            EnforcementStatus.error = "Blocking interface unavailable: ${error.javaClass.simpleName}"
            Log.e("MorningBlocker", "Cannot present blocking overlay", error)
            false
        }
    }

    override fun hide() {
        val root = overlay ?: return
        try {
            windows.removeViewImmediate(root)
        } catch (error: IllegalArgumentException) {
            Log.w("MorningBlocker", "Overlay was already removed", error)
        } finally {
            overlay = null
        }
    }
}
