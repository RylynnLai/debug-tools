package com.debugtools.debugvpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

internal class TunProxyEngine(
    private val service: VpnService,
    private val tun: ParcelFileDescriptor
) {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap<ConnectionKey, TcpConnection>()
    private val writeLock = Any()
    private val httpHandler = UpstreamHttpHandler { socket -> service.protect(socket) }
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::runLoop)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        connections.values.forEach { it.closeQuietly() }
        connections.clear()
        try {
            input.close()
        } catch (_: Throwable) {
        }
        try {
            output.close()
        } catch (_: Throwable) {
        }
        try {
            tun.close()
        } catch (_: Throwable) {
        }
        executor.shutdownNow()
    }

    private fun runLoop() {
        while (running.get()) {
            try {
                val buffer = ByteArray(32767)
                val length = input.read(buffer)
                if (length <= 0) break
                handlePacket(buffer.copyOf(length))
            } catch (_: Throwable) {
                break
            }
        }
        stop()
    }

    private fun handlePacket(packet: ByteArray) {
        val ipv4 = Ipv4Packet.parse(packet) ?: return
        when (ipv4.protocol) {
            IP_PROTOCOL_TCP -> handleTcp(ipv4)
            IP_PROTOCOL_UDP -> handleUdp(ipv4)
        }
    }

    private fun handleUdp(ipv4: Ipv4Packet) {
        val udp = UdpSegment.parse(ipv4.payload) ?: return
        if (udp.destinationPort != 53) return
        executor.execute {
            val responsePayload = relayDns(ipv4.destinationAddress, udp.payload) ?: return@execute
            val responseUdp = UdpSegment(
                sourcePort = udp.destinationPort,
                destinationPort = udp.sourcePort,
                payload = responsePayload
            )
            val responseIp = Ipv4Packet(
                sourceAddress = ipv4.destinationAddress,
                destinationAddress = ipv4.sourceAddress,
                protocol = IP_PROTOCOL_UDP,
                identification = Random.nextInt(0, 0xffff),
                payload = responseUdp.toBytes(ipv4.destinationAddress, ipv4.sourceAddress)
            )
            writePacket(responseIp.toBytes())
        }
    }

    private fun relayDns(serverAddress: Int, payload: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                service.protect(socket)
                socket.soTimeout = 3_000
                val address = InetAddress.getByAddress(intToIpBytes(serverAddress))
                socket.send(DatagramPacket(payload, payload.size, address, 53))
                val response = ByteArray(4096)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                response.copyOf(packet.length)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleTcp(ipv4: Ipv4Packet) {
        val tcp = TcpSegment.parse(ipv4.payload) ?: return
        val key = ConnectionKey(
            clientIp = ipv4.sourceAddress,
            clientPort = tcp.sourcePort,
            remoteIp = ipv4.destinationAddress,
            remotePort = tcp.destinationPort
        )

        if (tcp.isSyn && !tcp.isAck) {
            val mode = if (tcp.destinationPort == 80) ConnectionMode.HTTP_INTERCEPT else ConnectionMode.TCP_TUNNEL
            val connection = TcpConnection(
                key = key,
                service = service,
                mode = mode,
                executor = executor,
                httpHandler = httpHandler,
                sendPacket = { packet -> writePacket(packet) },
                onClosed = { connections.remove(key) }
            )
            connections[key] = connection
            connection.onSyn(tcp)
            return
        }

        val connection = connections[key]
        if (connection != null) {
            connection.onClientPacket(tcp)
            return
        }

        if (!tcp.isRst) {
            writePacket(
                buildTcpPacket(
                    key = key.reverse(),
                    seq = 0,
                    ack = tcp.sequenceNumber + tcp.payload.size + if (tcp.isSyn || tcp.isFin) 1 else 0,
                    flags = TCP_RST or TCP_ACK,
                    payload = ByteArray(0)
                )
            )
        }
    }

    private fun writePacket(packet: ByteArray) {
        synchronized(writeLock) {
            output.write(packet)
            output.flush()
        }
    }

    companion object {
        private const val IP_PROTOCOL_TCP = 6
        private const val IP_PROTOCOL_UDP = 17
        private const val TCP_ACK = 0x10
        private const val TCP_PSH = 0x08
        private const val TCP_RST = 0x04
        private const val TCP_SYN = 0x02
        private const val TCP_FIN = 0x01

        fun buildTcpPacket(
            key: ConnectionKey,
            seq: Int,
            ack: Int,
            flags: Int,
            payload: ByteArray
        ): ByteArray {
            val tcp = TcpSegment(
                sourcePort = key.remotePort,
                destinationPort = key.clientPort,
                sequenceNumber = seq,
                acknowledgmentNumber = ack,
                flags = flags,
                windowSize = 65_535,
                payload = payload
            )
            val ip = Ipv4Packet(
                sourceAddress = key.remoteIp,
                destinationAddress = key.clientIp,
                protocol = IP_PROTOCOL_TCP,
                identification = Random.nextInt(0, 0xffff),
                payload = tcp.toBytes(key.remoteIp, key.clientIp)
            )
            return ip.toBytes()
        }

        fun isHttpRequestComplete(data: ByteArray): Boolean {
            val headerEnd = data.indexOfSubsequence(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()))
            if (headerEnd < 0) return false
            val headersText = data.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
            val contentLengthLine = headersText
                .lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            val contentLength = contentLengthLine
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val bodyLength = data.size - (headerEnd + 4)
            return bodyLength >= contentLength
        }

        private fun ByteArray.indexOfSubsequence(target: ByteArray): Int {
            if (target.isEmpty() || size < target.size) return -1
            for (index in 0..(size - target.size)) {
                var matched = true
                for (offset in target.indices) {
                    if (this[index + offset] != target[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched) return index
            }
            return -1
        }
    }
}

private enum class ConnectionMode {
    HTTP_INTERCEPT,
    TCP_TUNNEL
}

internal data class ConnectionKey(
    val clientIp: Int,
    val clientPort: Int,
    val remoteIp: Int,
    val remotePort: Int
) {
    fun reverse(): ConnectionKey = ConnectionKey(
        clientIp = clientIp,
        clientPort = clientPort,
        remoteIp = remoteIp,
        remotePort = remotePort
    )
}

private class TcpConnection(
    private val key: ConnectionKey,
    private val service: VpnService,
    private val mode: ConnectionMode,
    private val executor: ExecutorService,
    private val httpHandler: UpstreamHttpHandler,
    private val sendPacket: (ByteArray) -> Unit,
    private val onClosed: () -> Unit
) {
    private val requestBuffer = ByteArrayOutputStream()
    private val lock = Any()
    private var established = false
    private var processingHttp = false
    private var serverSeq = Random.nextInt(1, Int.MAX_VALUE / 2)
    private var nextServerSeq = serverSeq + 1
    private var nextClientSeq = 0
    private var finSent = false
    private var closed = false
    private var upstreamSocket: Socket? = null

    fun onSyn(tcp: TcpSegment) {
        synchronized(lock) {
            nextClientSeq = tcp.sequenceNumber + 1
            sendControl(flags = TCP_SYN or TCP_ACK, sequenceNumber = serverSeq)
            if (mode == ConnectionMode.TCP_TUNNEL) {
                connectUpstream()
            }
        }
    }

    fun onClientPacket(tcp: TcpSegment) {
        synchronized(lock) {
            if (closed) return
            if (!established && tcp.isAck && tcp.acknowledgmentNumber == nextServerSeq) {
                established = true
            }
            if (tcp.payload.isNotEmpty()) {
                if (tcp.sequenceNumber != nextClientSeq) {
                    sendControl(flags = TCP_ACK)
                    return
                }
                nextClientSeq += tcp.payload.size
                when (mode) {
                    ConnectionMode.HTTP_INTERCEPT -> onHttpPayload(tcp.payload)
                    ConnectionMode.TCP_TUNNEL -> onTunnelPayload(tcp.payload)
                }
                sendControl(flags = TCP_ACK)
            }
            if (tcp.isFin) {
                nextClientSeq += 1
                sendControl(flags = TCP_ACK)
                if (mode == ConnectionMode.TCP_TUNNEL) {
                    try {
                        upstreamSocket?.shutdownOutput()
                    } catch (_: Throwable) {
                    }
                } else if (!finSent) {
                    sendFin()
                }
            }
            if (finSent && tcp.isAck && tcp.acknowledgmentNumber == nextServerSeq) {
                closeQuietly()
            }
        }
    }

    private fun connectUpstream() {
        executor.execute {
            val socket = Socket()
            try {
                service.protect(socket)
                socket.tcpNoDelay = true
                socket.connect(
                    InetSocketAddress(
                        InetAddress.getByAddress(intToIpBytes(key.remoteIp)),
                        key.remotePort
                    ),
                    5_000
                )
                synchronized(lock) {
                    if (closed) {
                        socket.close()
                        return@execute
                    }
                    upstreamSocket = socket
                }
                relayUpstream(socket)
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (!closed) {
                        sendRst()
                        closeQuietly()
                    }
                }
            }
        }
    }

    private fun relayUpstream(socket: Socket) {
        executor.execute {
            val buffer = ByteArray(1400)
            try {
                val input = socket.getInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sendData(buffer.copyOf(read))
                }
            } catch (_: Throwable) {
            } finally {
                synchronized(lock) {
                    if (!closed && !finSent) {
                        sendFin()
                    }
                }
            }
        }
    }

    private fun onTunnelPayload(payload: ByteArray) {
        try {
            upstreamSocket?.getOutputStream()?.write(payload)
            upstreamSocket?.getOutputStream()?.flush()
        } catch (_: Throwable) {
            sendRst()
            closeQuietly()
        }
    }

    private fun onHttpPayload(payload: ByteArray) {
        requestBuffer.write(payload)
        if (processingHttp || !TunProxyEngine.isHttpRequestComplete(requestBuffer.toByteArray())) return
        processingHttp = true
        val rawRequest = requestBuffer.toByteArray()
        executor.execute {
            val response = httpHandler.handle(rawRequest)
            synchronized(lock) {
                if (closed) return@execute
                sendData(response)
                if (!finSent) {
                    sendFin()
                }
            }
        }
    }

    private fun sendData(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + 1300, data.size)
            val chunk = data.copyOfRange(offset, end)
            val packet = TunProxyEngine.buildTcpPacket(
                key = key,
                seq = nextServerSeq,
                ack = nextClientSeq,
                flags = TCP_ACK or TCP_PSH,
                payload = chunk
            )
            sendPacket(packet)
            nextServerSeq += chunk.size
            offset = end
        }
    }

    private fun sendControl(flags: Int, sequenceNumber: Int = nextServerSeq) {
        sendPacket(
            TunProxyEngine.buildTcpPacket(
                key = key,
                seq = sequenceNumber,
                ack = nextClientSeq,
                flags = flags,
                payload = ByteArray(0)
            )
        )
    }

    private fun sendFin() {
        sendPacket(
            TunProxyEngine.buildTcpPacket(
                key = key,
                seq = nextServerSeq,
                ack = nextClientSeq,
                flags = TCP_ACK or TCP_FIN,
                payload = ByteArray(0)
            )
        )
        nextServerSeq += 1
        finSent = true
    }

    private fun sendRst() {
        sendPacket(
            TunProxyEngine.buildTcpPacket(
                key = key,
                seq = nextServerSeq,
                ack = nextClientSeq,
                flags = TCP_RST or TCP_ACK,
                payload = ByteArray(0)
            )
        )
    }

    fun closeQuietly() {
        if (closed) return
        closed = true
        try {
            upstreamSocket?.close()
        } catch (_: Throwable) {
        }
        onClosed()
    }

    companion object {
        private const val TCP_ACK = 0x10
        private const val TCP_PSH = 0x08
        private const val TCP_RST = 0x04
        private const val TCP_SYN = 0x02
        private const val TCP_FIN = 0x01
    }
}

private data class Ipv4Packet(
    val sourceAddress: Int,
    val destinationAddress: Int,
    val protocol: Int,
    val identification: Int,
    val payload: ByteArray
) {
    fun toBytes(): ByteArray {
        val header = ByteArray(20)
        header[0] = 0x45
        header[1] = 0
        val totalLength = 20 + payload.size
        writeShort(header, 2, totalLength)
        writeShort(header, 4, identification)
        writeShort(header, 6, 0)
        header[8] = 64
        header[9] = protocol.toByte()
        writeInt(header, 12, sourceAddress)
        writeInt(header, 16, destinationAddress)
        writeShort(header, 10, checksum(header, 0, header.size))
        return header + payload
    }

    companion object {
        fun parse(packet: ByteArray): Ipv4Packet? {
            if (packet.size < 20) return null
            val version = (packet[0].toInt() ushr 4) and 0x0f
            if (version != 4) return null
            val headerLength = (packet[0].toInt() and 0x0f) * 4
            if (packet.size < headerLength || headerLength < 20) return null
            val totalLength = readShort(packet, 2)
            if (totalLength < headerLength || totalLength > packet.size) return null
            return Ipv4Packet(
                sourceAddress = readInt(packet, 12),
                destinationAddress = readInt(packet, 16),
                protocol = packet[9].toInt() and 0xff,
                identification = readShort(packet, 4),
                payload = packet.copyOfRange(headerLength, totalLength)
            )
        }
    }
}

private data class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Int,
    val acknowledgmentNumber: Int,
    val flags: Int,
    val windowSize: Int,
    val payload: ByteArray
) {
    val isSyn: Boolean get() = flags and 0x02 != 0
    val isAck: Boolean get() = flags and 0x10 != 0
    val isFin: Boolean get() = flags and 0x01 != 0
    val isRst: Boolean get() = flags and 0x04 != 0

    fun toBytes(sourceIp: Int, destinationIp: Int): ByteArray {
        val header = ByteArray(20)
        writeShort(header, 0, sourcePort)
        writeShort(header, 2, destinationPort)
        writeInt(header, 4, sequenceNumber)
        writeInt(header, 8, acknowledgmentNumber)
        header[12] = (5 shl 4).toByte()
        header[13] = flags.toByte()
        writeShort(header, 14, windowSize)
        writeShort(header, 16, 0)
        writeShort(header, 18, 0)
        val segment = header + payload
        val pseudo = buildPseudoHeader(sourceIp, destinationIp, 6, segment.size)
        writeShort(segment, 16, checksum(pseudo + segment, 0, pseudo.size + segment.size))
        return segment
    }

    companion object {
        fun parse(packet: ByteArray): TcpSegment? {
            if (packet.size < 20) return null
            val dataOffset = ((packet[12].toInt() ushr 4) and 0x0f) * 4
            if (dataOffset < 20 || dataOffset > packet.size) return null
            return TcpSegment(
                sourcePort = readShort(packet, 0),
                destinationPort = readShort(packet, 2),
                sequenceNumber = readInt(packet, 4),
                acknowledgmentNumber = readInt(packet, 8),
                flags = packet[13].toInt() and 0x3f,
                windowSize = readShort(packet, 14),
                payload = packet.copyOfRange(dataOffset, packet.size)
            )
        }
    }
}

private data class UdpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
) {
    fun toBytes(sourceIp: Int, destinationIp: Int): ByteArray {
        val segment = ByteArray(8 + payload.size)
        writeShort(segment, 0, sourcePort)
        writeShort(segment, 2, destinationPort)
        writeShort(segment, 4, segment.size)
        writeShort(segment, 6, 0)
        payload.copyInto(segment, 8)
        val pseudo = buildPseudoHeader(sourceIp, destinationIp, 17, segment.size)
        writeShort(segment, 6, checksum(pseudo + segment, 0, pseudo.size + segment.size))
        return segment
    }

    companion object {
        fun parse(packet: ByteArray): UdpSegment? {
            if (packet.size < 8) return null
            val length = readShort(packet, 4)
            if (length < 8 || length > packet.size) return null
            return UdpSegment(
                sourcePort = readShort(packet, 0),
                destinationPort = readShort(packet, 2),
                payload = packet.copyOfRange(8, length)
            )
        }
    }
}

private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value ushr 8).toByte()
    buffer[offset + 1] = value.toByte()
}

private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value ushr 24).toByte()
    buffer[offset + 1] = (value ushr 16).toByte()
    buffer[offset + 2] = (value ushr 8).toByte()
    buffer[offset + 3] = value.toByte()
}

private fun readShort(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

private fun readInt(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xff) shl 24) or
        ((buffer[offset + 1].toInt() and 0xff) shl 16) or
        ((buffer[offset + 2].toInt() and 0xff) shl 8) or
        (buffer[offset + 3].toInt() and 0xff)

private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var index = offset
    while (index + 1 < offset + length) {
        sum += readShort(buffer, index)
        index += 2
    }
    if (index < offset + length) {
        sum += (buffer[index].toInt() and 0xff) shl 8
    }
    while ((sum ushr 16) != 0L) {
        sum = (sum and 0xffff) + (sum ushr 16)
    }
    return sum.inv().toInt() and 0xffff
}

private fun buildPseudoHeader(sourceIp: Int, destinationIp: Int, protocol: Int, length: Int): ByteArray {
    val header = ByteArray(12)
    writeInt(header, 0, sourceIp)
    writeInt(header, 4, destinationIp)
    header[8] = 0
    header[9] = protocol.toByte()
    writeShort(header, 10, length)
    return header
}

private fun intToIpBytes(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)
