package com.debugtools.debugkit

import java.net.Inet4Address
import java.net.NetworkInterface

internal object HostResolver {
    fun findLanAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        var candidate: String? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                if (address.isSiteLocalAddress) {
                    return host
                }
                if (candidate == null) candidate = host
            }
        }
        return candidate ?: "127.0.0.1"
    }
}
