package com.debugtools.app

import android.app.Application
import com.debugtools.debugkit.DebugKit
import com.debugtools.debugkit.DebugUiConfig

class DebugToolsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugKit.install(
            app = this,
            uiConfig = DebugUiConfig(enableFloatingEntry = true)
        )
    }
}
