package com.debugtools.debugvpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

object DebugVpnController {
    private val running = AtomicBoolean(false)
    private val proxyServer = LocalHttpProxyServer()

    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun isRunning(): Boolean = running.get()

    fun start(context: Context, targetPackage: String) {
        if (running.get()) return
        val proxyPort = proxyServer.start()
        val intent = Intent(context, DebugVpnService::class.java).apply {
            action = DebugVpnService.ACTION_START
            putExtra(DebugVpnService.EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(DebugVpnService.EXTRA_PROXY_PORT, proxyPort)
        }
        ContextCompat.startForegroundService(context, intent)
        running.set(true)
    }

    fun stop(context: Context) {
        if (!running.get()) return
        val intent = Intent(context, DebugVpnService::class.java).apply {
            action = DebugVpnService.ACTION_STOP
        }
        context.startService(intent)
        proxyServer.stop()
        running.set(false)
    }

    internal fun onServiceStopped() {
        proxyServer.stop()
        running.set(false)
    }
}

