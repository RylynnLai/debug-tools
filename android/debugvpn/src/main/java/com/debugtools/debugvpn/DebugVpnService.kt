package com.debugtools.debugvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class DebugVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                val proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 0)
                if (targetPackage.isBlank() || proxyPort <= 0) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(targetPackage))
                startVpn(targetPackage, proxyPort)
            }

            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tunInterface?.close()
        tunInterface = null
        DebugVpnController.onServiceStopped()
    }

    private fun startVpn(targetPackage: String, proxyPort: Int) {
        if (tunInterface != null) return
        val builder = Builder()
            .setSession("DebugTools VPN")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            // Route all IPv4 traffic for the selected app into this VPN profile.
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))
        }

        try {
            builder.addAllowedApplication(targetPackage)
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            stopSelf()
        }
    }

    private fun buildNotification(targetPackage: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Debug VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Debug VPN active")
            .setContentText("Proxying package: $targetPackage")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.debugtools.debugvpn.START"
        const val ACTION_STOP = "com.debugtools.debugvpn.STOP"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_PROXY_PORT = "proxy_port"

        private const val CHANNEL_ID = "debug_vpn_channel"
        private const val NOTIFICATION_ID = 2107
    }
}

