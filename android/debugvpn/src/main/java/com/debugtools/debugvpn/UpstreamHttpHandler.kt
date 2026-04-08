package com.debugtools.debugvpn

import com.debugtools.debugkit.DebugKit
import com.debugtools.debugkit.HttpTrafficRecord
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.SocketFactory

internal class UpstreamHttpHandler(
    private val protectSocket: (Socket) -> Boolean
) {
    private val client = OkHttpClient.Builder()
        .socketFactory(ProtectingSocketFactory(protectSocket))
        .build()

    fun handle(rawRequest: ByteArray): ByteArray {
        val request = parseRequest(BufferedInputStream(ByteArrayInputStream(rawRequest)))
            ?: return buildSimpleError(400, "Bad Request")
        if (request.method.equals("CONNECT", ignoreCase = true)) {
            return buildSimpleError(501, "CONNECT Not Supported")
        }

        val host = request.headers["host"].orEmpty()
        val url = resolveUrl(request.target, host)
            ?: return buildSimpleError(400, "Bad Request")

        val mock = DebugKit.mockRegistry().find(request.method, url.encodedPath)
        if (mock != null) {
            DebugKit.httpTrafficRegistry().record(
                HttpTrafficRecord(
                    timestampMs = System.currentTimeMillis(),
                    method = request.method,
                    host = url.host,
                    path = url.encodedPath,
                    query = url.encodedQuery ?: "",
                    statusCode = mock.statusCode,
                    requestBody = request.body.toDebugText(),
                    responseBody = mock.body,
                    responseHeaders = mock.headers,
                    mocked = true
                )
            )
            return buildHttpResponse(
                code = mock.statusCode,
                message = if (mock.statusCode == 200) "OK" else "Mocked",
                headers = mock.headers,
                body = mock.body.toByteArray()
            )
        }

        return try {
            val upstream = forwardToOrigin(request, url)
            DebugKit.httpTrafficRegistry().record(
                HttpTrafficRecord(
                    timestampMs = System.currentTimeMillis(),
                    method = request.method,
                    host = url.host,
                    path = url.encodedPath,
                    query = url.encodedQuery ?: "",
                    statusCode = upstream.code,
                    requestBody = request.body.toDebugText(),
                    responseBody = upstream.bodyText,
                    responseHeaders = upstream.headers,
                    mocked = false
                )
            )
            buildHttpResponse(
                code = upstream.code,
                message = upstream.message.ifBlank { "OK" },
                headers = upstream.headers,
                body = upstream.bodyBytes
            )
        } catch (_: Throwable) {
            buildSimpleError(502, "Bad Gateway")
        }
    }

    private fun resolveUrl(target: String, hostHeader: String): okhttp3.HttpUrl? {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target.toHttpUrlOrNull()
        }
        if (hostHeader.isBlank()) return null
        return "http://$hostHeader$target".toHttpUrlOrNull()
    }

    private fun forwardToOrigin(request: HttpProxyRequest, url: okhttp3.HttpUrl): HttpUpstreamResponse {
        val body = if (request.body.isNotEmpty()) {
            val contentType = request.headers["content-type"]?.toMediaTypeOrNull()
            request.body.toRequestBody(contentType)
        } else {
            null
        }

        val builder = Request.Builder()
            .url(url)
            .method(request.method, body)

        request.headers.forEach { (key, value) ->
            if (key == "proxy-connection" || key == "connection" || key == "content-length") return@forEach
            builder.header(key, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            val responseHeaders = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                response.header(name)?.let { value -> responseHeaders[name] = value }
            }
            return HttpUpstreamResponse(
                code = response.code,
                message = response.message,
                headers = responseHeaders,
                bodyBytes = responseBytes,
                bodyText = responseBytes.toDebugText()
            )
        }
    }
}

private class ProtectingSocketFactory(
    private val protectSocket: (Socket) -> Boolean
) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { protectSocket(it) }

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

private data class HttpProxyRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

private data class HttpUpstreamResponse(
    val code: Int,
    val message: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    val bodyText: String
)

private fun parseRequest(input: BufferedInputStream): HttpProxyRequest? {
    val requestLine = input.readHttpLine() ?: return null
    val lineParts = requestLine.split(" ")
    if (lineParts.size < 3) return null

    val headers = linkedMapOf<String, String>()
    while (true) {
        val headerLine = input.readHttpLine() ?: return null
        if (headerLine.isEmpty()) break
        val sep = headerLine.indexOf(':')
        if (sep <= 0) continue
        val name = headerLine.substring(0, sep).trim().lowercase(Locale.US)
        val value = headerLine.substring(sep + 1).trim()
        headers[name] = value
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (contentLength > 0) input.readFully(contentLength) else ByteArray(0)

    return HttpProxyRequest(
        method = lineParts[0],
        target = lineParts[1],
        version = lineParts[2],
        headers = headers,
        body = body
    )
}

private fun buildSimpleError(code: Int, message: String): ByteArray {
    val body = "$code $message".toByteArray()
    return buildHttpResponse(code, message, emptyMap(), body)
}

private fun buildHttpResponse(
    code: Int,
    message: String,
    headers: Map<String, String>,
    body: ByteArray
): ByteArray {
    val output = ByteArrayOutputStream()
    output.write("HTTP/1.1 $code $message\r\n".toByteArray())
    headers.forEach { (name, value) ->
        if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
        if (name.equals("Content-Length", ignoreCase = true)) return@forEach
        output.write("$name: $value\r\n".toByteArray())
    }
    output.write("Content-Length: ${body.size}\r\n".toByteArray())
    output.write("Connection: close\r\n\r\n".toByteArray())
    output.write(body)
    return output.toByteArray()
}

private fun ByteArray.toDebugText(): String {
    if (isEmpty()) return ""
    return toString(StandardCharsets.UTF_8)
}

private fun BufferedInputStream.readHttpLine(): String? {
    val out = ByteArrayOutputStream(128)
    var previous = -1
    while (true) {
        val current = read()
        if (current == -1) {
            return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
        }
        if (previous == '\r'.code && current == '\n'.code) {
            break
        }
        if (previous != -1) {
            out.write(previous)
        }
        previous = current
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun BufferedInputStream.readFully(length: Int): ByteArray {
    val data = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = read(data, read, length - read)
        if (n <= 0) break
        read += n
    }
    return if (read == length) data else data.copyOf(read)
}
