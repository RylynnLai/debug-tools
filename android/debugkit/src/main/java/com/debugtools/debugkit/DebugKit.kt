package com.debugtools.debugkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object DebugKit {
    private const val DEFAULT_PORT = 4939

    private lateinit var application: Application
    private val mockRegistry = MockRegistry()
    private val leakWatcher = LeakWatcher()
    private val tracker = ActivityTracker(leakWatcher)
    private var server: DebugServer? = null

    fun install(app: Application, port: Int = DEFAULT_PORT) {
        application = app
        application.registerActivityLifecycleCallbacks(tracker)
        // Bridge LeakCanary heap analysis results into the watch list so the
        // desktop panel can display leak class and reference-path location.
        LeakCanaryBridge.install(leakWatcher)
        if (server == null) {
            server = DebugServer(
                context = app,
                port = port,
                activityTracker = tracker,
                mockRegistry = mockRegistry,
                leakWatcher = leakWatcher
            ).also { it.start() }
        }
    }

    fun mockRegistry(): MockRegistry = mockRegistry

    fun watch(label: String, target: Any) {
        leakWatcher.watch(label, target)
    }

    fun describeState(): DebugKitState {
        val currentServer = server
        return DebugKitState(
            running = currentServer != null,
            host = HostResolver.findLanAddress(),
            port = currentServer?.port ?: DEFAULT_PORT,
            connectedClients = currentServer?.connectedClients() ?: 0,
            mockRules = mockRegistry.all().size,
            watchedObjects = leakWatcher.snapshot().items.size
        )
    }
}

data class DebugKitState(
    val running: Boolean,
    val host: String,
    val port: Int,
    val connectedClients: Int,
    val mockRules: Int,
    val watchedObjects: Int
)

internal class ActivityTracker(
    private val leakWatcher: LeakWatcher
) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    fun currentActivity(): Activity? = currentActivityRef?.get()

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        leakWatcher.watch("activity:${activity::class.java.simpleName}", activity)
    }
}
