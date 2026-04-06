package com.debugtools.debugkit

import java.net.NetworkInterface

internal object HostResolver {
    fun findLanAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && !address.hostAddress.contains(":")) {
                    return address.hostAddress
                }
            }
        }
        return "127.0.0.1"
    }
}
