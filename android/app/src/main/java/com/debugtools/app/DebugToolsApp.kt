package com.debugtools.app

import android.app.Application
import com.debugtools.debugkit.DebugKit

class DebugToolsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugKit.install(this)
    }
}
