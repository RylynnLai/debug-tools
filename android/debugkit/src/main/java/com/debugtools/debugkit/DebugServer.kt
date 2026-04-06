package com.debugtools.debugkit

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class DebugServer(
    context: Context,
    val port: Int,
    private val activityTracker: ActivityTracker,
    private val mockRegistry: MockRegistry,
    private val leakWatcher: LeakWatcher
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            ServerSocket(port).use { serverSocket ->
                while (running.get()) {
                    val socket = serverSocket.accept()
                    sockets += socket
                    executor.execute { handleClient(socket) }
                }
            }
        }
    }

    fun connectedClients(): Int = sockets.count { !it.isClosed }

    private fun handleClient(socket: Socket) {
        socket.use { current ->
            val reader = BufferedReader(InputStreamReader(current.getInputStream()))
            val writer = PrintWriter(current.getOutputStream(), true)
            writer.println(hello())
            while (true) {
                val line = reader.readLine() ?: break
                val request = DebugProtocol.parseRequest(line)
                if (request == null) {
                    writer.println(error("unknown", "invalid_request", "Malformed JSON line"))
                    continue
                }
                val response = try {
                    dispatch(request)
                } catch (t: Throwable) {
                    error(
                        request.id,
                        "internal_error",
                        "${t::class.java.simpleName}: ${t.message ?: "Unknown error"}"
                    )
                }
                writer.println(response)
            }
        }
        sockets.remove(socket)
    }

    private fun dispatch(request: DebugRequest): String {
        return when (request.type) {
            "hello" -> ok(request.id, "hello", """{"host":"${HostResolver.findLanAddress()}","port":$port}""")
            "get_view_tree" -> {
                val snapshot = ViewInspector.inspect(activityTracker.currentActivity())
                if (snapshot == null) {
                    error(request.id, "no_activity", "No resumed activity")
                } else {
                    ok(request.id, "view_tree", snapshot.toJson())
                }
            }
            "get_view_preview" -> {
                val preview = ViewInspector.capturePreview(activityTracker.currentActivity())
                if (preview == null) {
                    error(request.id, "no_activity", "No resumed activity or view is not ready")
                } else {
                    ok(request.id, "view_preview", preview.toJson())
                }
            }
            "get_memory_stats" -> ok(request.id, "memory_stats", MemorySampler.sample().toJson())
            "update_view_props" -> {
                val path = DebugProtocol.readString(request.raw, "path")
                    ?: return error(request.id, "bad_request", "path required")
                val label = DebugProtocol.readString(request.raw, "label")
                val colorHex = DebugProtocol.readString(request.raw, "color")
                val contentDescription = DebugProtocol.readString(request.raw, "contentDescription")
                val hint = DebugProtocol.readString(request.raw, "hint")
                val marginLeft = DebugProtocol.readInt(request.raw, "marginLeft")
                val marginTop = DebugProtocol.readInt(request.raw, "marginTop")
                val marginRight = DebugProtocol.readInt(request.raw, "marginRight")
                val marginBottom = DebugProtocol.readInt(request.raw, "marginBottom")
                val paddingLeft = DebugProtocol.readInt(request.raw, "paddingLeft")
                val paddingTop = DebugProtocol.readInt(request.raw, "paddingTop")
                val paddingRight = DebugProtocol.readInt(request.raw, "paddingRight")
                val paddingBottom = DebugProtocol.readInt(request.raw, "paddingBottom")
                val alpha = DebugProtocol.readString(request.raw, "alpha")?.toFloatOrNull()
                val textColor = DebugProtocol.readString(request.raw, "textColor")
                val textSizeSp = DebugProtocol.readString(request.raw, "textSizeSp")?.toFloatOrNull()
                val updated = ViewInspector.updateViewProperties(
                    activityTracker.currentActivity(),
                    path,
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
                if (updated) {
                    ok(request.id, "view_updated", """{"path":"${Json.escape(path)}","updated":true}""")
                } else {
                    error(request.id, "update_failed", "View update failed")
                }
            }
            "list_mocks" -> ok(request.id, "mock_list", mocksToJson(mockRegistry.all()))
            "set_mock" -> {
                val method = DebugProtocol.readString(request.raw, "method") ?: "GET"
                val path = DebugProtocol.readString(request.raw, "path") ?: return error(request.id, "bad_request", "path required")
                val body = DebugProtocol.readString(request.raw, "body") ?: ""
                val statusCode = DebugProtocol.readInt(request.raw, "statusCode") ?: 200
                val headers = DebugProtocol.readStringMap(request.raw, "headers")
                mockRegistry.upsert(MockRule(method, path, statusCode, body, headers))
                ok(request.id, "mock_saved", mocksToJson(mockRegistry.all()))
            }
            "clear_mock" -> {
                val method = DebugProtocol.readString(request.raw, "method")
                val path = DebugProtocol.readString(request.raw, "path")
                mockRegistry.clear(method, path)
                ok(request.id, "mock_list", mocksToJson(mockRegistry.all()))
            }
            "list_watches" -> ok(request.id, "watch_list", leakWatcher.snapshot().toJson())
            else -> error(request.id, "unsupported", "Unsupported command ${request.type}")
        }
    }

    private fun hello(): String {
        return """{"id":"server","type":"hello","success":true,"payload":{"app":"${appContext.packageName}","host":"${HostResolver.findLanAddress()}","port":$port}}"""
    }

    private fun ok(id: String, type: String, payload: String): String {
        return """{"id":"${Json.escape(id)}","type":"$type","success":true,"payload":$payload}"""
    }

    private fun error(id: String, code: String, message: String): String {
        return """{"id":"${Json.escape(id)}","type":"error","success":false,"error":{"code":"${Json.escape(code)}","message":"${Json.escape(message)}"}}"""
    }

    private fun mocksToJson(items: List<MockRule>): String {
        return items.joinToString(prefix = """{"items":[""", separator = ",", postfix = "]}") { it.toJson() }
    }
}

private fun MemoryStats.toJson(): String {
    return """{"javaUsedMb":$javaUsedMb,"javaMaxMb":$javaMaxMb,"nativeHeapMb":$nativeHeapMb,"totalPssKb":$totalPssKb,"nativePssKb":$nativePssKb,"dalvikPssKb":$dalvikPssKb,"otherPssKb":$otherPssKb}"""
}

private fun ViewTreeSnapshot.toJson(): String {
    return """{"activity":"${Json.escape(activity)}","tree":${tree.toJson()}}"""
}

private fun ViewPreviewSnapshot.toJson(): String {
    return """{"activity":"${Json.escape(activity)}","format":"$format","width":$width,"height":$height,"imageBase64":"${Json.escape(imageBase64)}"}"""
}

private fun ViewNode.toJson(): String {
    return """{"path":"${Json.escape(path)}","id":"${Json.escape(id)}","idValue":$idValue,"className":"${Json.escape(className)}","visible":$visible,"visibility":"${Json.escape(visibility)}","enabled":$enabled,"clickable":$clickable,"focusable":$focusable,"left":$left,"top":$top,"width":$width,"height":$height,"alpha":$alpha,"label":"${Json.escape(label)}","contentDescription":"${Json.escape(contentDescription)}","bgColor":"${Json.escape(bgColor)}","marginLeft":$marginLeft,"marginTop":$marginTop,"marginRight":$marginRight,"marginBottom":$marginBottom,"paddingLeft":$paddingLeft,"paddingTop":$paddingTop,"paddingRight":$paddingRight,"paddingBottom":$paddingBottom,"textColor":"${Json.escape(textColor)}","textSizeSp":$textSizeSp,"hint":"${Json.escape(hint)}","cornerRadiusPx":$cornerRadiusPx,"iconHint":"${Json.escape(iconHint)}","imageBase64":"${Json.escape(imageBase64)}","children":[${children.joinToString(",") { it.toJson() }}]}"""
}

private fun MockRule.toJson(): String {
    val headersJson = headers.entries.joinToString(",") {
        """"${Json.escape(it.key)}":"${Json.escape(it.value)}""""
    }
    return """{"method":"${Json.escape(method)}","path":"${Json.escape(path)}","statusCode":$statusCode,"body":"${Json.escape(body)}","headers":{$headersJson}}"""
}

private fun LeakSnapshot.toJson(): String {
    val itemsJson = items.joinToString(separator = ",") {
        """{"label":"${Json.escape(it.label)}","retained":${it.retained},"className":"${Json.escape(it.className)}","location":"${Json.escape(it.location)}","retainedDurationMs":${it.retainedDurationMs},"source":"${Json.escape(it.source)}","traceSummary":"${Json.escape(it.traceSummary)}","analysisTimestampMs":${it.analysisTimestampMs}}"""
    }
    return """{"retainedObjectCount":$retainedObjectCount,"items":[$itemsJson]}"""
}
