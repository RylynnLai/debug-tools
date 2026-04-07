package com.debugtools.debugvpn

internal data class HostPort(val host: String, val port: Int)

internal object ProxyTargetParser {
    fun parseConnectTarget(target: String): HostPort? {
        val hostPort = target.split(':', limit = 2)
        val host = hostPort.firstOrNull().orEmpty()
        val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
        if (host.isBlank()) return null
        return HostPort(host = host, port = port)
    }
}

