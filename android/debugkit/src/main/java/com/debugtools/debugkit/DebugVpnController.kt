package com.debugtools.debugkit

import android.content.Context
import android.content.Intent

/**
 * Stable entrypoint exposed from debugkit after merging debugvpn sources.
 */
object DebugVpnController {
    fun prepareIntent(context: Context): Intent? =
        com.debugtools.debugvpn.DebugVpnController.prepareIntent(context)

    fun isRunning(): Boolean = com.debugtools.debugvpn.DebugVpnController.isRunning()

    fun start(context: Context, targetPackage: String) {
        com.debugtools.debugvpn.DebugVpnController.start(context, targetPackage)
    }

    fun stop(context: Context) {
        com.debugtools.debugvpn.DebugVpnController.stop(context)
    }
}

