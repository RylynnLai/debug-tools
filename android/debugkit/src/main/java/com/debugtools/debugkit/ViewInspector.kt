package com.debugtools.debugkit

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object ViewInspector {
    fun inspect(activity: Activity?): ViewTreeSnapshot? {
        val current = activity ?: return null
        val root = current.window?.decorView ?: return null
        val latch = CountDownLatch(1)
        var result: ViewTreeSnapshot? = null
        val stats = CaptureStats()
        current.runOnUiThread {
            try {
                result = ViewTreeSnapshot(
                    activity = current::class.java.simpleName,
                    tree = capture(root, "0", stats),
                    diagnostics = captureDiagnostics(current, stats)
                )
            } catch (_: Throwable) {
                result = null
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(8000, TimeUnit.MILLISECONDS)) return null
        return result
    }

    fun capturePreview(activity: Activity?): ViewPreviewSnapshot? {
        val current = activity ?: return null
        val root = current.window?.decorView ?: return null
        if (root.width <= 0 || root.height <= 0) return null

        val latch = CountDownLatch(1)
        var captured: Bitmap? = null
        current.runOnUiThread {
            try {
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                root.draw(canvas)
                captured = bitmap
            } catch (_: Throwable) {
                captured = null
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(600, TimeUnit.MILLISECONDS)) return null
        val bitmap = captured ?: return null

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        return ViewPreviewSnapshot(
            activity = current::class.java.simpleName,
            format = "jpeg",
            width = bitmap.width,
            height = bitmap.height,
            imageBase64 = base64
        )
    }

    fun updateViewProperties(
        activity: Activity?,
        path: String,
        label: String?,
        colorHex: String?,
        contentDescription: String?,
        hint: String?,
        marginLeft: Int?,
        marginTop: Int?,
        marginRight: Int?,
        marginBottom: Int?,
        paddingLeft: Int?,
        paddingTop: Int?,
        paddingRight: Int?,
        paddingBottom: Int?,
        alpha: Float?,
        textColor: String?,
        textSizeSp: Float?
    ): Boolean {
        val current = activity ?: return false
        val root = current.window?.decorView ?: return false
        val latch = CountDownLatch(1)
        var updated = false
        current.runOnUiThread {
            try {
                val target = findViewByPath(root, path)
                if (target != null) {
                    applyProperties(
                        target,
                        label,
                        colorHex,
                        contentDescription,
                        hint,
                        marginLeft,
                        marginTop,
                        marginRight,
                        marginBottom,
                        paddingLeft,
                        paddingTop,
                        paddingRight,
                        paddingBottom,
                        alpha,
                        textColor,
                        textSizeSp
                    )
                    updated = true
                }
            } catch (_: Throwable) {
                updated = false
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2000, TimeUnit.MILLISECONDS)) return false
        return updated
    }

    private fun capture(view: View, path: String, stats: CaptureStats): ViewNode {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val base = ViewNode(
            path = path,
            id = safeResourceName(view),
            idValue = view.id,
            className = view::class.java.name,
            visible = view.visibility == View.VISIBLE,
            visibility = visibilityName(view.visibility),
            enabled = view.isEnabled,
            clickable = view.isClickable,
            focusable = view.isFocusable,
            left = location[0],
            top = location[1],
            width = view.width,
            height = view.height,
            alpha = view.alpha,
            label = extractLabel(view),
            contentDescription = view.contentDescription?.toString() ?: "",
            bgColor = extractBackgroundColor(view),
            marginLeft = extractMargins(view).first,
            marginTop = extractMargins(view).second,
            marginRight = extractMargins(view).third,
            marginBottom = extractMargins(view).fourth,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            textColor = extractTextColor(view),
            textSizeSp = extractTextSizeSp(view),
            hint = extractHint(view),
            cornerRadiusPx = extractCornerRadius(view),
            iconHint = extractIconHint(view),
            imageBase64 = "",
            nodeType = "view",
            children = emptyList()
        )

        if (isComposeHostView(view)) {
            stats.composeHostViews += 1
            val composeChildren = captureComposeSemanticsChildren(view, path, stats)
            if (composeChildren.isNotEmpty()) {
                return base.copy(children = composeChildren)
            }
        }

        if (view !is ViewGroup) return base
        return base.copy(
            children = (0 until view.childCount).map { index ->
                capture(view.getChildAt(index), "$path.$index", stats)
            }
        )
    }

    private fun captureComposeSemanticsChildren(view: View, path: String, stats: CaptureStats): List<ViewNode> {
        return try {
            val hostLocation = IntArray(2)
            view.getLocationOnScreen(hostLocation)
            val owner = callNoArg(view, "getSemanticsOwner") ?: return emptyList()
            val root = callNoArg(owner, "getRootSemanticsNode") ?: return emptyList()
            stats.composeReflectionOk = true
            val rootChildren = readComposeChildren(root)
            val nodes = if (rootChildren.isNotEmpty()) rootChildren else listOf(root)
            nodes.mapIndexed { index, child ->
                captureComposeNode(
                    node = child,
                    path = "$path.$index",
                    hostLeft = hostLocation[0],
                    hostTop = hostLocation[1],
                    stats = stats,
                    depth = 0
                )
            }
        } catch (t: Throwable) {
            stats.composeReflectionError = "${t::class.java.simpleName}: ${t.message ?: "unknown"}"
            emptyList()
        }
    }

    private fun captureComposeNode(
        node: Any,
        path: String,
        hostLeft: Int,
        hostTop: Int,
        stats: CaptureStats,
        depth: Int
    ): ViewNode {
        if (depth > 64 || stats.composeNodeCount >= 1200) {
            return ViewNode(
                path = path,
                id = "compose-truncated",
                idValue = -1,
                className = "androidx.compose.ui.semantics.SemanticsNode",
                visible = true,
                visibility = "VISIBLE",
                enabled = true,
                clickable = false,
                focusable = false,
                left = hostLeft,
                top = hostTop,
                width = 0,
                height = 0,
                label = "[truncated]",
                iconHint = "compose",
                nodeType = "compose",
                children = emptyList()
            )
        }
        stats.composeNodeCount += 1

        val id = (callNoArg(node, "getId") as? Int) ?: -1
        val config = callNoArg(node, "getConfig")
        val text = readComposeText(config)
        val contentDescription = readComposeContentDescription(config)
        val testTag = readComposeTestTag(config)
        val clickable = readComposeClickable(config)

        val windowRect = callNoArg(node, "getBoundsInWindow")
        val rootRect = if (windowRect == null) callNoArg(node, "getBoundsInRoot") else null
        val rect = windowRect ?: rootRect
        val rawLeft = readFloat(rect, "getLeft")
        val rawTop = readFloat(rect, "getTop")
        val rawRight = readFloat(rect, "getRight")
        val rawBottom = readFloat(rect, "getBottom")
        val addHostOffset = windowRect == null && rootRect != null
        val left = (if (addHostOffset) rawLeft + hostLeft else rawLeft).roundToInt()
        val top = (if (addHostOffset) rawTop + hostTop else rawTop).roundToInt()
        val width = (rawRight - rawLeft).coerceAtLeast(0f).roundToInt()
        val height = (rawBottom - rawTop).coerceAtLeast(0f).roundToInt()
        val label = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            testTag.isNotBlank() -> testTag
            else -> "compose#$id"
        }

        val children = readComposeChildren(node).mapIndexed { index, child ->
            captureComposeNode(
                node = child,
                path = "$path.$index",
                hostLeft = hostLeft,
                hostTop = hostTop,
                stats = stats,
                depth = depth + 1
            )
        }

        return ViewNode(
            path = path,
            id = if (testTag.isNotBlank()) testTag else "compose-$id",
            idValue = -1,
            className = "androidx.compose.ui.semantics.SemanticsNode",
            visible = true,
            visibility = "VISIBLE",
            enabled = true,
            clickable = clickable,
            focusable = false,
            left = left,
            top = top,
            width = width,
            height = height,
            label = label,
            contentDescription = contentDescription,
            iconHint = if (clickable) "button" else "compose",
            nodeType = "compose",
            composeNodeId = id,
            testTag = testTag,
            children = children
        )
    }

    private fun isComposeHostView(view: View): Boolean {
        return view::class.java.name.contains("AndroidComposeView")
    }

    private fun readComposeChildren(node: Any): List<Any> {
        val value = callNoArg(node, "getChildren") ?: return emptyList()
        val iterable = value as? Iterable<*> ?: return emptyList()
        return iterable.filterNotNull()
    }

    private fun readComposeText(config: Any?): String {
        val value = readSemanticsValue(config, "androidx.compose.ui.semantics.SemanticsProperties", "getText")
        return valueToString(value)
    }

    private fun readComposeContentDescription(config: Any?): String {
        val value = readSemanticsValue(config, "androidx.compose.ui.semantics.SemanticsProperties", "getContentDescription")
        return valueToString(value)
    }

    private fun readComposeTestTag(config: Any?): String {
        val value = readSemanticsValue(config, "androidx.compose.ui.semantics.SemanticsProperties", "getTestTag")
        return value?.toString() ?: ""
    }

    private fun readComposeClickable(config: Any?): Boolean {
        val value = readSemanticsValue(config, "androidx.compose.ui.semantics.SemanticsActions", "getOnClick")
        return value != null
    }

    private fun readSemanticsValue(config: Any?, ownerClassName: String, keyMethod: String): Any? {
        if (config == null) return null
        val key = runCatching {
            val ownerClass = Class.forName(ownerClassName)
            val owner = runCatching { ownerClass.getField("INSTANCE").get(null) }.getOrNull()
            val getter = ownerClass.methods.firstOrNull { it.name == keyMethod && it.parameterCount == 0 }
            getter?.invoke(owner)
        }.getOrNull() ?: return null
        val method = config::class.java.methods.firstOrNull { it.name == "getOrNull" && it.parameterCount == 1 } ?: return null
        return runCatching { method.invoke(config, key) }.getOrNull()
    }

    private fun valueToString(value: Any?): String {
        if (value == null) return ""
        return when (value) {
            is String -> value
            is Iterable<*> -> value.mapNotNull { item ->
                when (item) {
                    null -> null
                    is String -> item
                    else -> {
                        callNoArg(item, "getText")?.toString() ?: item.toString()
                    }
                }
            }.joinToString(" ").trim()
            else -> value.toString()
        }
    }

    private fun readFloat(target: Any?, methodName: String): Float {
        if (target == null) return 0f
        val number = callNoArg(target, methodName) as? Number ?: return 0f
        return number.toFloat()
    }

    private fun callNoArg(target: Any, methodName: String): Any? {
        val method = findNoArgMethod(target::class.java, methodName) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? {
        return type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.also { it.isAccessible = true }
    }

    private fun captureDiagnostics(activity: Activity, stats: CaptureStats): ViewTreeDiagnostics {
        val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val composeRuntimePresent = runCatching {
            Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            true
        }.getOrDefault(false)
        return ViewTreeDiagnostics(
            debuggable = debuggable,
            composeRuntimePresent = composeRuntimePresent,
            composeHostViews = stats.composeHostViews,
            composeSemanticsNodes = stats.composeNodeCount,
            composeReflectionOk = stats.composeReflectionOk,
            composeReflectionError = stats.composeReflectionError
        )
    }

    private data class CaptureStats(
        var composeHostViews: Int = 0,
        var composeNodeCount: Int = 0,
        var composeReflectionOk: Boolean = false,
        var composeReflectionError: String = ""
    )

    private fun extractLabel(view: View): String {
        return when (view) {
            is TextView -> view.text?.toString() ?: ""
            else -> view.contentDescription?.toString() ?: ""
        }
    }

    private fun extractBackgroundColor(view: View): String {
        val colorDrawable = view.background as? ColorDrawable ?: return ""
        return String.format("#%08X", colorDrawable.color)
    }

    private fun extractTextColor(view: View): String {
        val textView = view as? TextView ?: return ""
        return String.format("#%08X", textView.currentTextColor)
    }

    private fun extractTextSizeSp(view: View): Float {
        val textView = view as? TextView ?: return 0f
        val scaledDensity = view.resources.displayMetrics.scaledDensity
        if (scaledDensity <= 0f) return 0f
        return textView.textSize / scaledDensity
    }

    private fun extractHint(view: View): String {
        val textView = view as? TextView ?: return ""
        return textView.hint?.toString() ?: ""
    }

    private fun visibilityName(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "UNKNOWN"
        }
    }

    private fun extractCornerRadius(view: View): Float {
        val gradient = view.background as? GradientDrawable ?: return 0f
        return runCatching { gradient.cornerRadius }.getOrDefault(0f)
    }

    private fun extractIconHint(view: View): String {
        if (view is TextView) {
            val hasCompound = view.compoundDrawables.any { it != null }
            return if (hasCompound) "text-icon" else "text"
        }
        val className = view::class.java.name
        return when {
            className.contains("Image", ignoreCase = true) -> "image"
            className.contains("Button", ignoreCase = true) -> "button"
            className.contains("Check", ignoreCase = true) -> "check"
            className.contains("Switch", ignoreCase = true) -> "switch"
            className.contains("Edit", ignoreCase = true) -> "input"
            else -> "view"
        }
    }

    private data class Margins(
        val first: Int,
        val second: Int,
        val third: Int,
        val fourth: Int
    )

    private fun extractMargins(view: View): Margins {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return Margins(0, 0, 0, 0)
        return Margins(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin)
    }

    private fun findViewByPath(root: View, path: String): View? {
        val indices = path.split(".").mapNotNull { it.toIntOrNull() }
        if (indices.isEmpty() || indices[0] != 0) return null
        var current: View = root
        for (i in 1 until indices.size) {
            val group = current as? ViewGroup ?: return null
            val childIndex = indices[i]
            if (childIndex !in 0 until group.childCount) return null
            current = group.getChildAt(childIndex)
        }
        return current
    }

    private fun applyProperties(
        view: View,
        label: String?,
        colorHex: String?,
        contentDescription: String?,
        hint: String?,
        marginLeft: Int?,
        marginTop: Int?,
        marginRight: Int?,
        marginBottom: Int?,
        paddingLeft: Int?,
        paddingTop: Int?,
        paddingRight: Int?,
        paddingBottom: Int?,
        alpha: Float?,
        textColor: String?,
        textSizeSp: Float?
    ) {
        if (label != null) {
            if (view is TextView) {
                view.text = label
            } else {
                view.contentDescription = label
            }
        }

        if (!colorHex.isNullOrBlank()) {
            runCatching { Color.parseColor(colorHex) }
                .onSuccess { view.setBackgroundColor(it) }
        }

        if (contentDescription != null) {
            view.contentDescription = contentDescription
        }

        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null && (marginLeft != null || marginTop != null || marginRight != null || marginBottom != null)) {
            params.setMargins(
                marginLeft ?: params.leftMargin,
                marginTop ?: params.topMargin,
                marginRight ?: params.rightMargin,
                marginBottom ?: params.bottomMargin
            )
            view.layoutParams = params
        }

        if (paddingLeft != null || paddingTop != null || paddingRight != null || paddingBottom != null) {
            view.setPadding(
                paddingLeft ?: view.paddingLeft,
                paddingTop ?: view.paddingTop,
                paddingRight ?: view.paddingRight,
                paddingBottom ?: view.paddingBottom
            )
        }

        if (alpha != null) {
            view.alpha = alpha.coerceIn(0f, 1f)
        }

        val textView = view as? TextView
        if (textView != null) {
            if (!textColor.isNullOrBlank()) {
                runCatching { Color.parseColor(textColor) }
                    .onSuccess { textView.setTextColor(it) }
            }
            if (textSizeSp != null && textSizeSp > 0f) {
                textView.textSize = textSizeSp
            }
            if (hint != null) {
                textView.hint = hint
            }
        }

        view.requestLayout()
        view.invalidate()
    }


    private fun safeResourceName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "no-id"
        return try {
            view.resources.getResourceEntryName(id)
        } catch (_: Exception) {
            id.toString()
        }
    }
}

data class ViewTreeSnapshot(
    val activity: String,
    val tree: ViewNode,
    val diagnostics: ViewTreeDiagnostics
)

data class ViewTreeDiagnostics(
    val debuggable: Boolean,
    val composeRuntimePresent: Boolean,
    val composeHostViews: Int,
    val composeSemanticsNodes: Int,
    val composeReflectionOk: Boolean,
    val composeReflectionError: String = ""
)

data class ViewPreviewSnapshot(
    val activity: String,
    val format: String,
    val width: Int,
    val height: Int,
    val imageBase64: String
)

data class ViewNode(
    val path: String,
    val id: String,
    val idValue: Int,
    val className: String,
    val visible: Boolean,
    val visibility: String,
    val enabled: Boolean,
    val clickable: Boolean,
    val focusable: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val alpha: Float = 1f,
    val label: String = "",
    val contentDescription: String = "",
    val bgColor: String = "",
    val marginLeft: Int = 0,
    val marginTop: Int = 0,
    val marginRight: Int = 0,
    val marginBottom: Int = 0,
    val paddingLeft: Int = 0,
    val paddingTop: Int = 0,
    val paddingRight: Int = 0,
    val paddingBottom: Int = 0,
    val textColor: String = "",
    val textSizeSp: Float = 0f,
    val hint: String = "",
    val cornerRadiusPx: Float = 0f,
    val iconHint: String = "view",
    val imageBase64: String = "",
    val nodeType: String = "view",
    val composeNodeId: Int = -1,
    val testTag: String = "",
    val children: List<ViewNode>
)
