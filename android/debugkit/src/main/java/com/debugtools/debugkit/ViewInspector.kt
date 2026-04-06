package com.debugtools.debugkit

import android.app.Activity
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ViewInspector {
    fun inspect(activity: Activity?): ViewTreeSnapshot? {
        val current = activity ?: return null
        val root = current.window?.decorView ?: return null
        val latch = CountDownLatch(1)
        var result: ViewTreeSnapshot? = null
        current.runOnUiThread {
            try {
                result = ViewTreeSnapshot(
                    activity = current::class.java.simpleName,
                    tree = capture(root, "0")
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

    private fun capture(view: View, path: String): ViewNode {
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
            children = emptyList()
        )
        if (view !is ViewGroup) return base
        return base.copy(
            children = (0 until view.childCount).map { index ->
                capture(view.getChildAt(index), "$path.$index")
            }
        )
    }

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
    val tree: ViewNode
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
    val children: List<ViewNode>
)
