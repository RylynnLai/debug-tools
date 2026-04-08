package com.debugtools.debugkit

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

internal class DebugFloatingEntryController(
    private val snapshotProvider: () -> DebugPanelSnapshot
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        attachFloatingEntry(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        detachFloatingEntry(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        detachFloatingEntry(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun attachFloatingEntry(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(FLOAT_TAG) != null) return

        val buttonSize = dp(activity, 46)
        val button = TextView(activity).apply {
            tag = FLOAT_TAG
            text = "DBG"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 11f
            alpha = 0.75f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC000000"))
            }
            contentDescription = "Open Debug Panel"
        }

        val layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.END or Gravity.TOP
            marginEnd = dp(activity, 14)
            topMargin = dp(activity, 220)
        }
        button.setOnTouchListener(FloatingDragTouchListener(layoutParams))
        button.setOnClickListener {
            DebugPanelDialog.show(activity, snapshotProvider)
        }
        root.addView(button, layoutParams)
    }

    private fun detachFloatingEntry(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val existing = root.findViewWithTag<View>(FLOAT_TAG) ?: return
        root.removeView(existing)
    }

    private class FloatingDragTouchListener(
        private val layoutParams: FrameLayout.LayoutParams
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val slop = ViewConfiguration.get(view.context).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.marginEnd = (layoutParams.marginEnd - dx.toInt()).coerceAtLeast(0)
                        layoutParams.topMargin = (layoutParams.topMargin + dy.toInt()).coerceAtLeast(0)
                        (view.parent as? ViewGroup)?.updateViewLayout(view, layoutParams)
                        downRawX = event.rawX
                        downRawY = event.rawY
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        return true
                    }
                    view.performClick()
                    return true
                }
            }
            return false
        }
    }

    private companion object {
        const val FLOAT_TAG = "debug_tools_floating_entry"

        fun dp(activity: Activity, value: Int): Int {
            return (value * activity.resources.displayMetrics.density).toInt()
        }
    }
}

private object DebugPanelDialog {
    fun show(activity: Activity, snapshotProvider: () -> DebugPanelSnapshot) {
        val content = createPanelTextView(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Debug Panel")
            .setView(wrapInScrollView(activity, content))
            .setPositiveButton("Refresh", null)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear Traffic", null)
            .create()

        dialog.setOnShowListener {
            fun render() {
                content.text = snapshotToText(snapshotProvider())
            }
            render()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { render() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                DebugKit.clearHttpTraffic()
                render()
            }
        }
        dialog.show()
    }

    private fun createPanelTextView(activity: Activity): TextView {
        return TextView(activity).apply {
            setTextColor(Color.parseColor("#202124"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
    }

    private fun wrapInScrollView(activity: Activity, content: TextView): View {
        return ScrollView(activity).apply {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(content)
            }
            addView(container)
        }
    }

    private fun snapshotToText(snapshot: DebugPanelSnapshot): String {
        val state = snapshot.state
        val mockLines = if (snapshot.mockRules.isEmpty()) {
            "(none)"
        } else {
            snapshot.mockRules.take(8).joinToString("\n") { rule ->
                "${rule.method.padEnd(6)} ${rule.path} -> ${rule.statusCode}"
            }
        }

        val watchLines = if (snapshot.watches.items.isEmpty()) {
            "(none)"
        } else {
            snapshot.watches.items.take(8).joinToString("\n") { item ->
                val status = if (item.retained) "retained" else "released"
                "${item.label} (${item.className}) [$status]"
            }
        }

        val trafficLines = if (snapshot.recentTraffic.isEmpty()) {
            "(none)"
        } else {
            snapshot.recentTraffic.take(10).joinToString("\n") { record ->
                val mockedFlag = if (record.mocked) " [mock]" else ""
                "${record.method.padEnd(6)} ${record.host}${record.path} -> ${record.statusCode}$mockedFlag"
            }
        }

        return buildString {
            appendLine("Server")
            appendLine("  running  : ${state.running}")
            appendLine("  connect  : ${state.host}:${state.port}")
            appendLine("  clients  : ${state.connectedClients}")
            appendLine("  vpnProxy : ${snapshot.vpnRunning}")
            appendLine()
            appendLine("Mock Rules (${snapshot.mockRules.size})")
            appendLine(mockLines)
            appendLine()
            appendLine("Leak Watches (${snapshot.watches.items.size}, retained=${snapshot.watches.retainedObjectCount})")
            appendLine(watchLines)
            appendLine()
            appendLine("Recent Traffic (${snapshot.recentTraffic.size})")
            appendLine(trafficLines)
            appendLine()
            append("Tip: drag the DBG button to move it")
        }
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}

