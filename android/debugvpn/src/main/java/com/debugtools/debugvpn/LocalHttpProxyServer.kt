package com.debugtools.debugvpn

import com.debugtools.debugkit.DebugKit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalHttpProxyServer {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder().build()

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(): Int {
        if (running.get()) return serverSocket?.localPort ?: 0
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)
        executor.execute {
            while (running.get()) {
                try {
                    val clientSocket = socket.accept()
                    executor.execute { handleClient(clientSocket) }
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    private fun handleClient(clientSocket: Socket) {
        clientSocket.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = parseRequest(input) ?: return
            if (request.method.equals("CONNECT", ignoreCase = true)) {
                tunnelHttps(request.target, input, output)
                return
            }

            val host = request.headers["host"] ?: ""
            val url = resolveUrl(target = request.target, hostHeader = host) ?: run {
                writeSimpleError(output, 400, "Bad Request")
                return
            }

            val mock = DebugKit.mockRegistry().find(request.method, url.encodedPath)
            if (mock != null) {
                writeMockResponse(output, mock.statusCode, mock.headers, mock.body.toByteArray())
                return
            }

            val response = forwardToOrigin(request, url)
            writeUpstreamResponse(output, response)
        }
    }

    private fun parseRequest(input: BufferedInputStream): ProxyRequest? {
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

        return ProxyRequest(
            method = lineParts[0],
            target = lineParts[1],
            version = lineParts[2],
            headers = headers,
            body = body
        )
    }

    private fun resolveUrl(target: String, hostHeader: String): okhttp3.HttpUrl? {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target.toHttpUrlOrNull()
        }
        if (hostHeader.isBlank()) return null
        return "http://$hostHeader$target".toHttpUrlOrNull()
    }

    private fun forwardToOrigin(request: ProxyRequest, url: okhttp3.HttpUrl): Response {
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

        return client.newCall(builder.build()).execute()
    }

    private fun writeMockResponse(
        output: BufferedOutputStream,
        code: Int,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val message = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Mocked"
        }
        output.write("HTTP/1.1 $code $message\r\n".toByteArray())
        output.write("Content-Length: ${body.size}\r\n".toByteArray())
        output.write("Connection: close\r\n".toByteArray())
        headers.forEach { (k, v) ->
            output.write("$k: $v\r\n".toByteArray())
        }
        output.write("\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun writeUpstreamResponse(output: BufferedOutputStream, response: Response) {
        response.use { upstream ->
            val bodyBytes = upstream.body?.bytes() ?: ByteArray(0)
            val message = upstream.message.ifBlank { "OK" }
            output.write("HTTP/1.1 ${upstream.code} $message\r\n".toByteArray())
            upstream.headers.names().forEach { name ->
                if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
                if (name.equals("Content-Length", ignoreCase = true)) return@forEach
                val value = upstream.header(name) ?: return@forEach
                output.write("$name: $value\r\n".toByteArray())
            }
            output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(bodyBytes)
            output.flush()
        }
    }

    private fun tunnelHttps(target: String, input: InputStream, output: BufferedOutputStream) {
        val hostPort = ProxyTargetParser.parseConnectTarget(target)
        if (hostPort == null) {
            writeSimpleError(output, 400, "Bad CONNECT target")
            return
        }

        try {
            Socket(hostPort.host, hostPort.port).use { upstream ->
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()

                val uplink = executor.submit {
                    try {
                        input.copyTo(upstream.getOutputStream())
                    } catch (_: SocketException) {
                    }
                }
                try {
                    upstream.getInputStream().copyTo(output)
                } catch (_: SocketException) {
                }
                uplink.cancel(true)
            }
        } catch (_: Throwable) {
            writeSimpleError(output, 502, "Bad Gateway")
        }
    }

    private fun writeSimpleError(output: BufferedOutputStream, code: Int, message: String) {
        val body = "$code $message".toByteArray()
        output.write("HTTP/1.1 $code $message\r\n".toByteArray())
        output.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }
}

private data class ProxyRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

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

