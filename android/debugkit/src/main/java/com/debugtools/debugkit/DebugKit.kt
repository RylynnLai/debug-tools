package com.debugtools.debugkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object DebugKit {
    private const val DEFAULT_PORT = 4939

    private lateinit var application: Application
    private val mockRegistry = MockRegistry()
    private val httpTrafficRegistry = HttpTrafficRegistry()
    private val leakWatcher = LeakWatcher()
    private val tracker = ActivityTracker(leakWatcher)
    private var server: DebugServer? = null
    private var installed = false
    private var floatingEntryController: DebugFloatingEntryController? = null

    fun install(
        app: Application,
        port: Int = DEFAULT_PORT,
        uiConfig: DebugUiConfig = DebugUiConfig()
    ) {
        if (installed) return
        installed = true
        application = app
        application.registerActivityLifecycleCallbacks(tracker)
        if (uiConfig.enableFloatingEntry) {
            floatingEntryController = DebugFloatingEntryController { panelSnapshot() }
            application.registerActivityLifecycleCallbacks(floatingEntryController!!)
        }
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

    fun httpTrafficRegistry(): HttpTrafficRegistry = httpTrafficRegistry

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

    fun panelSnapshot(maxTrafficEntries: Int = 12): DebugPanelSnapshot {
        return DebugPanelSnapshot(
            state = describeState(),
            vpnRunning = DebugVpnController.isRunning(),
            mockRules = mockRegistry.all(),
            watches = leakWatcher.snapshot(),
            recentTraffic = httpTrafficRegistry.all().take(maxTrafficEntries)
        )
    }

    fun clearHttpTraffic() {
        httpTrafficRegistry.clear()
    }
}

data class DebugUiConfig(
    val enableFloatingEntry: Boolean = true
)

data class DebugKitState(
    val running: Boolean,
    val host: String,
    val port: Int,
    val connectedClients: Int,
    val mockRules: Int,
    val watchedObjects: Int
)

data class DebugPanelSnapshot(
    val state: DebugKitState,
    val vpnRunning: Boolean,
    val mockRules: List<MockRule>,
    val watches: LeakSnapshot,
    val recentTraffic: List<HttpTrafficRecord>
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
